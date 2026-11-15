package com.luke.tracks.library

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore.Audio.Media
import android.provider.MediaStore.UNKNOWN_STRING
import android.util.Log
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.UNKNOWN_ARTIST
import uniffi.tracks_engine.songIdentity

/**
 * Which of the phone's audio files are music, and how to read them whatever the phone. Phone
 * makers differ: Samsung and others flag ringtones and notification sounds as music or keep
 * them under Music, some leave columns out or empty, and any value can be null. So system
 * sounds, recordings, podcasts, audiobooks and anything too short to keep are left out three
 * ways (MediaStore's flags, the folder, the length), and every column is optional but the id
 * and the title.
 */
internal object DeviceAudio {
    /** Songs this short or shorter are never kept (Last.fm's rule), and are mostly sounds, not music. */
    const val SHORTEST_MS = 30_000L

    /** MediaStore's flags for audio that isn't music, as far as this Android has them. */
    val notMusicFlags: List<String> = buildList {
        add(Media.IS_RINGTONE)
        add(Media.IS_NOTIFICATION)
        add(Media.IS_ALARM)
        add(Media.IS_PODCAST)
        add(Media.IS_AUDIOBOOK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Media.IS_RECORDING)
    }

    /** Everything worth reading, with the optional columns some phones lack. */
    val fullColumns: Array<String> = (
        listOf(
            Media._ID, Media.TITLE, Media.ARTIST, Media.ALBUM, Media.ALBUM_ID, Media.ALBUM_ARTIST,
            Media.DURATION, Media.TRACK, Media.YEAR, Media.GENRE, Media.DATE_ADDED, Media.RELATIVE_PATH,
            @Suppress("DEPRECATION") Media.DATA,
        ) + notMusicFlags
        ).toTypedArray()

    /** What every MediaStore has had since the start, for phones that refuse the full query. */
    val basicColumns: Array<String> = arrayOf(
        Media._ID, Media.TITLE, Media.ARTIST, Media.ALBUM, Media.ALBUM_ID, Media.DURATION,
        @Suppress("DEPRECATION") Media.DATA,
    )

    const val basicSelection = "${Media.IS_MUSIC} != 0"

    /** Music, and none of the flags that say it's something else. A null flag counts as not set. */
    fun selection(flags: List<String> = notMusicFlags): String =
        (listOf(basicSelection) + flags.map { "($it IS NULL OR $it = 0)" }).joinToString(" AND ")

    /** Folders that hold sounds, not music: Android's standard ones and the recorders'. */
    private val soundFolders = setOf("ringtones", "notifications", "alarms", "recordings", "call")

    /** Where phones keep their built-in sounds (Samsung, Google, Motorola, Xiaomi and the rest). */
    private val systemRoots = listOf("/system/", "/product/", "/vendor/", "/system_ext/", "/oem/", "/odm/")

    /**
     * Whether a file lives where sounds live: the phone's own sound folders, or a folder named
     * for ringtones, notifications, alarms or recordings anywhere on the storage. [relativePath]
     * is MediaStore's ("Ringtones/", "Music/Samsung/"), [path] the file's full path.
     */
    fun isSoundFolder(relativePath: String?, path: String?): Boolean {
        if (path != null && systemRoots.any { path.startsWith(it) }) return true
        val folders = listOfNotNull(relativePath, path?.substringBeforeLast('/', ""))
            .flatMap { it.split('/') }
            .map { it.trim().lowercase() }
        return folders.any { it in soundFolders }
    }

    /** Known and no longer than [SHORTEST_MS]. An unknown length isn't held against a file. */
    fun isTooShort(durationMs: Long?): Boolean = durationMs != null && durationMs in 1..SHORTEST_MS

    /**
     * Reads the songs from a MediaStore query, tolerating any column but the id and title
     * being missing or null, and leaving out whatever isn't music.
     */
    fun read(cursor: Cursor): List<LibraryTrack> {
        val id = cursor.getColumnIndex(Media._ID)
        val title = cursor.getColumnIndex(Media.TITLE)
        if (id < 0 || title < 0) return emptyList()
        val artist = cursor.getColumnIndex(Media.ARTIST)
        val album = cursor.getColumnIndex(Media.ALBUM)
        val albumId = cursor.getColumnIndex(Media.ALBUM_ID)
        val albumArtist = cursor.getColumnIndex(Media.ALBUM_ARTIST)
        val duration = cursor.getColumnIndex(Media.DURATION)
        val track = cursor.getColumnIndex(Media.TRACK)
        val year = cursor.getColumnIndex(Media.YEAR)
        val genre = cursor.getColumnIndex(Media.GENRE)
        val added = cursor.getColumnIndex(Media.DATE_ADDED)
        val relativePath = cursor.getColumnIndex(Media.RELATIVE_PATH)
        @Suppress("DEPRECATION") val data = cursor.getColumnIndex(Media.DATA)
        val flags = notMusicFlags.map { cursor.getColumnIndex(it) }.filter { it >= 0 }

        val tracks = mutableListOf<LibraryTrack>()
        while (cursor.moveToNext()) {
            val row = runCatching {
                val songId = cursor.long(id) ?: return@runCatching null
                val name = cursor.text(title) ?: return@runCatching null
                if (flags.any { (cursor.long(it) ?: 0L) != 0L }) return@runCatching null
                val length = cursor.long(duration)?.takeIf { it > 0 }
                if (isTooShort(length)) return@runCatching null
                if (isSoundFolder(cursor.text(relativePath), cursor.text(data))) return@runCatching null

                val by = cursor.text(artist) ?: UNKNOWN_ARTIST
                val albumKey = cursor.long(albumId)
                // TRACK holds the disc in its thousands: 2005 is disc 2, track 5.
                val rawTrack = cursor.long(track)?.toInt()?.takeIf { it > 0 }
                LibraryTrack(
                    song = PlayableSong(
                        mediaId = "local:$songId",
                        uri = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, songId),
                        title = name,
                        artistName = by,
                        albumTitle = cursor.text(album),
                        // Some phones have no album for a file, so no cover to ask for.
                        artworkUri = albumKey?.let { ContentUris.withAppendedId(albumArtRoot, it) },
                        durationMs = length,
                        identity = songIdentity(name, by),
                        trackNumber = rawTrack?.rem(1000)?.takeIf { it > 0 },
                        year = cursor.long(year)?.toInt()?.takeIf { it > 0 },
                        genre = cursor.text(genre),
                    ),
                    source = LibrarySourceKind.Device,
                    albumKey = albumKey?.let { "local:album:$it" } ?: "local:album:${cursor.text(album) ?: "none"}",
                    albumArtist = cursor.text(albumArtist),
                    disc = rawTrack?.div(1000)?.takeIf { it > 0 },
                    addedAtMs = cursor.long(added)?.takeIf { it > 0 }?.times(1000),
                )
            }.onFailure { Log.w("Tracks", "Skipped an unreadable audio file", it) }.getOrNull()
            if (row != null) tracks += row
        }
        return tracks
    }

    /**
     * The songs on one volume. Tries the full query first; a phone whose MediaStore lacks one
     * of its columns throws, and then gets the basic one, with the folder and length checks
     * still applied.
     */
    fun query(run: (columns: Array<String>, selection: String) -> Cursor?): List<LibraryTrack> {
        val full = runCatching { run(fullColumns, selection())?.use(::read) }
            .onFailure { Log.w("Tracks", "Full MediaStore query failed; trying the basic one", it) }
        full.getOrNull()?.let { return it }
        return run(basicColumns, basicSelection)?.use(::read).orEmpty()
    }

    /** MediaStore's album covers, served from the files' own embedded art. */
    private val albumArtRoot: Uri = Uri.parse("content://media/external/audio/albumart")

    private fun Cursor.text(index: Int): String? =
        if (index < 0 || isNull(index)) null
        else getString(index)?.takeIf { it.isNotBlank() && it != UNKNOWN_STRING }

    private fun Cursor.long(index: Int): Long? =
        if (index < 0 || isNull(index)) null else runCatching { getLong(index) }.getOrNull()
}

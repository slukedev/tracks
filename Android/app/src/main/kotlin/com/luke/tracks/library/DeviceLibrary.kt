package com.luke.tracks.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.tracks_engine.songIdentity

/** What a music app asks for to read the phone's audio files: READ_MEDIA_AUDIO from Android 13. */
val musicPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun Context.hasMusicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, musicPermission) == PackageManager.PERMISSION_GRANTED

/**
 * The audio files on the phone, from MediaStore: read off the main thread, and read again a
 * moment after anything changes (a download finishes, a card goes in).
 */
class DeviceLibrary(private val context: Context, private val scope: CoroutineScope) {
    private val _tracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    private val _hasPermission = MutableStateFlow(context.hasMusicPermission())
    private val _loading = MutableStateFlow(true)
    private var observing = false
    private var pending: Job? = null

    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            pending?.cancel()
            pending = scope.launch {
                delay(1_000)
                refresh()
            }
        }
    }

    fun start() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        val allowed = context.hasMusicPermission()
        _hasPermission.value = allowed
        if (!allowed) {
            _tracks.value = emptyList()
            _loading.value = false
            return
        }
        if (!observing) {
            observing = true
            runCatching {
                context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            }
        }
        _tracks.value = withContext(Dispatchers.IO) { runCatching { query() }.onFailure { Log.w("Tracks", "MediaStore read failed", it) }.getOrDefault(emptyList()) }
        _loading.value = false
    }

    private fun query(): List<LibraryTrack> {
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val tracks = mutableListOf<LibraryTrack>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, selection, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val albumArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val genre = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val songId = cursor.getLong(id)
                val name = cursor.getString(title)?.takeIf { it.isNotBlank() } ?: continue
                val by = cursor.getString(artist)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "Unknown artist"
                val albumKey = cursor.getLong(albumId)
                // TRACK holds the disc in its thousands: 2005 is disc 2, track 5.
                val rawTrack = cursor.getInt(track).takeIf { it > 0 }
                tracks += LibraryTrack(
                    song = PlayableSong(
                        mediaId = "local:$songId",
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId),
                        title = name,
                        artistName = by,
                        albumTitle = cursor.getString(album)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING },
                        artworkUri = ContentUris.withAppendedId(albumArtRoot, albumKey),
                        durationMs = cursor.getLong(duration).takeIf { it > 0 },
                        identity = songIdentity(name, by),
                        trackNumber = rawTrack?.rem(1000)?.takeIf { it > 0 },
                        year = cursor.getInt(year).takeIf { it > 0 },
                        genre = cursor.getString(genre)?.takeIf { it.isNotBlank() },
                    ),
                    source = LibrarySourceKind.Device,
                    albumKey = "local:album:$albumKey",
                    albumArtist = cursor.getString(albumArtist)?.takeIf { it.isNotBlank() },
                    disc = rawTrack?.div(1000)?.takeIf { it > 0 },
                    addedAtMs = cursor.getLong(added) * 1000,
                )
            }
        }
        return tracks
    }

    private companion object {
        /** MediaStore's album covers, served from the files' own embedded art. */
        val albumArtRoot: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

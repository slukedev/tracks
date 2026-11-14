package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.luke.tracks.AppGraph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.tracks_engine.DayPart
import uniffi.tracks_engine.Mix
import uniffi.tracks_engine.MixKind

/**
 * What Android Auto, Wear OS and other controllers browse: Tracks Radio, your mixes, recently
 * played, albums and artists. A song listed inside something carries its parent in its id
 * ("album/<id>|<song>"), so choosing it plays the whole album from there.
 */
class BrowseTree(private val graph: AppGraph) {
    private var mixesCache: Pair<Long, List<Mix>>? = null

    private suspend fun index(): LibraryIndex {
        val library = graph.library as? CombinedLibrary ?: return LibraryIndex.Empty
        // A cold start from the car: give the library a moment to read.
        return withTimeoutOrNull(4_000) { library.ready() } ?: library.index.value
    }

    val root: MediaItem = folder(ROOT, "Tracks", playable = false)

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            playableItem(RADIO, "Tracks Radio", "Endless, from what you love", MediaMetadata.MEDIA_TYPE_RADIO_STATION),
            folder(MIXES, "Mixes", playable = false, type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(RECENT, "Recently played", playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST),
            folder(ALBUMS, "Albums", playable = false, type = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            folder(ARTISTS, "Artists", playable = false, type = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        )
        parentId == MIXES -> mixes().mapNotNull { mix ->
            val songs = mixSongs(mix)
            if (songs.isEmpty()) null
            else folder("$MIX${mix.id}", mixTitle(mix.kind), playable = true, type = MediaMetadata.MEDIA_TYPE_PLAYLIST, artwork = songs.firstNotNullOfOrNull { it.artworkUri })
        }
        parentId == RECENT -> recent().map { it.inside(RECENT) }
        parentId == ALBUMS -> index().snapshot.albums.map { album ->
            folder("$ALBUM${album.id}", album.title, playable = true, subtitle = album.artistName, type = MediaMetadata.MEDIA_TYPE_ALBUM, artwork = album.artworkUri)
        }
        parentId == ARTISTS -> index().snapshot.artists.map { artist ->
            folder("$ARTIST${artist.id}", artist.name, playable = true, type = MediaMetadata.MEDIA_TYPE_ARTIST, artwork = artist.albums.firstNotNullOfOrNull { it.artworkUri })
        }
        parentId.startsWith(ARTIST) -> {
            val artist = index().artist(parentId.removePrefix(ARTIST))
            artist?.albums.orEmpty().map { album ->
                folder("$ALBUM${album.id}", album.title, playable = true, subtitle = album.artistName, type = MediaMetadata.MEDIA_TYPE_ALBUM, artwork = album.artworkUri)
            }.ifEmpty { expand(parentId)?.first.orEmpty().map { it.inside(parentId) } }
        }
        parentId.startsWith(ALBUM) || parentId.startsWith(MIX) -> expand(parentId)?.first.orEmpty().map { it.inside(parentId) }
        else -> emptyList()
    }

    /** One item by id, for controllers that ask after a single one. */
    suspend fun item(id: String): MediaItem? {
        if (id == ROOT) return root
        song(id)?.let { return it.toMediaItem() }
        return children(ROOT).firstOrNull { it.mediaId == id }
    }

    /** A song by its own or a listed id. */
    suspend fun song(id: String): PlayableSong? = index().song(id.substringAfterLast(SEPARATOR))

    /**
     * What an id plays as a queue, and where in it to start: a whole album from a chosen song,
     * a mix, an artist's songs. Null when the id isn't a context.
     */
    suspend fun expand(id: String): Triple<List<PlayableSong>, Int, QueueOrigin?>? {
        val parent = id.substringBefore(SEPARATOR)
        val chosen = id.substringAfter(SEPARATOR, "")
        val (songs, origin) = when {
            parent == RECENT -> recent() to QueueOrigin("Recently played", QueueOrigin.Kind.Other)
            parent.startsWith(ALBUM) -> index().album(parent.removePrefix(ALBUM))?.let { it.songs to QueueOrigin(it.title, QueueOrigin.Kind.Album) } ?: return null
            parent.startsWith(ARTIST) -> {
                val index = index()
                index.artist(parent.removePrefix(ARTIST))?.let { index.songsBy(it) to QueueOrigin(it.name, QueueOrigin.Kind.Artist) } ?: return null
            }
            parent.startsWith(MIX) -> {
                val mix = mixes().firstOrNull { it.id == parent.removePrefix(MIX) } ?: return null
                mixSongs(mix) to QueueOrigin(mixTitle(mix.kind), QueueOrigin.Kind.Mix)
            }
            else -> return null
        }
        if (songs.isEmpty()) return null
        val start = if (chosen.isEmpty()) 0 else songs.indexOfFirst { it.mediaId == chosen }.coerceAtLeast(0)
        return Triple(songs, start, origin)
    }

    suspend fun search(query: String): List<PlayableSong> {
        val index = index()
        // "Play Saltwater": the album first if it's an album's name, the artist if an artist's.
        index.searchAlbums(query, 1).firstOrNull()?.takeIf { it.title.equals(query, ignoreCase = true) }?.let { return it.songs }
        index.searchArtists(query, 1).firstOrNull()?.takeIf { it.name.equals(query, ignoreCase = true) }?.let { return index.songsBy(it) }
        return index.search(query, 50)
    }

    private suspend fun recent(): List<PlayableSong> {
        val plays = runCatching { graph.engine.read { recentPlays(null, 200u) } }.getOrDefault(emptyList())
        val index = index()
        return plays.map { it.identity }.distinct().mapNotNull(index::find).take(50)
    }

    private suspend fun mixes(): List<Mix> {
        val now = System.currentTimeMillis()
        mixesCache?.let { (at, list) -> if (now - at < 5 * 60_000) return list }
        val hidden = graph.settings.settings.first().hiddenSongs.toList()
        val set = runCatching { graph.engine.read { mixes(now, hidden) } }.getOrNull() ?: return emptyList()
        val list = listOfNotNull(set.rightNow) + set.mixes + set.otherTimes
        val distinct = list.distinctBy { it.id }
        mixesCache = now to distinct
        return distinct
    }

    private suspend fun mixSongs(mix: Mix): List<PlayableSong> = graph.library.findAll(mix.songs.map { it.identity })

    private fun PlayableSong.inside(parent: String): MediaItem =
        toMediaItem().buildUpon().setMediaId("$parent$SEPARATOR$mediaId").build()

    private fun folder(id: String, title: String, playable: Boolean, subtitle: String? = null, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, artwork: Uri? = null) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(artwork)
                .setIsBrowsable(true)
                .setIsPlayable(playable)
                .setMediaType(type)
                .build(),
        ).build()

    private fun playableItem(id: String, title: String, subtitle: String, type: Int) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(false).setIsPlayable(true).setMediaType(type).build(),
        ).build()

    companion object {
        const val ROOT = "root"
        const val RADIO = "radio"
        const val MIXES = "mixes"
        const val RECENT = "recent"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val ALBUM = "album/"
        const val ARTIST = "artist/"
        const val MIX = "mix/"
        const val SEPARATOR = "|"

        fun mixTitle(kind: MixKind): String = when (kind) {
            is MixKind.RightNow -> when (kind.part) {
                DayPart.MORNING -> "Morning mix"
                DayPart.AFTERNOON -> "Afternoon mix"
                DayPart.EVENING -> "Evening mix"
                DayPart.NIGHT -> "Late night mix"
            }
            MixKind.OnRepeat -> "On repeat"
            MixKind.AllTimeFavorites -> "All-time favorites"
            MixKind.DeepCuts -> "Deep cuts"
            MixKind.NewFinds -> "New finds"
            MixKind.RadioFinds -> "Radio finds"
            MixKind.Rediscover -> "Rediscover"
            is MixKind.Throwback -> when (kind.monthsAgo.toInt()) {
                12 -> "A year ago"
                6 -> "Six months ago"
                else -> "Three months ago"
            }
        }
    }
}

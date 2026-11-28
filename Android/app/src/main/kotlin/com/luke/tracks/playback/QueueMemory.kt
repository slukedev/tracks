package com.luke.tracks.playback

import android.content.Context
import android.net.Uri
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.net.Redact
import com.luke.tracks.net.ServerLinks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The last queue, so playback can pick up where it was: from the media controls after a
 * restart, from a Bluetooth play button, from Android Auto.
 *
 * It keeps songs by their stable ids and nothing that works as a credential: a server's song
 * is saved without an address and gets its [ServerLinks] link back when restored, which is
 * signed only as it plays. A queue saved by an older version, with signed addresses, is
 * cleaned the same way as it's read.
 */
class QueueMemory(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    @Serializable
    private data class Saved(val songs: List<SavedSong>, val index: Int, val positionMs: Long, val origin: String? = null, val originKind: String? = null,
        /** Saved by a version where only Tracks Radio is kind Radio; before, a service's station could be too. */
        val tracksRadio: Boolean? = null,
    )

    @Serializable
    private data class SavedSong(
        val mediaId: String, val uri: String? = null, val title: String, val artist: String, val album: String? = null,
        val artwork: String? = null, val durationMs: Long? = null, val identity: String, val track: Int? = null,
        val year: Int? = null, val genre: String? = null,
    )

    /** [tracksRadio]: known to be Tracks Radio, even with a service's songs in it. */
    data class Restored(val songs: List<PlayableSong>, val index: Int, val positionMs: Long, val origin: QueueOrigin?, val tracksRadio: Boolean = false)

    fun save(songs: List<PlayableSong>, index: Int, positionMs: Long, origin: QueueOrigin?) {
        if (songs.isEmpty()) return
        // Up to a couple of hundred around the song playing now.
        val from = (index - 50).coerceAtLeast(0)
        val window = songs.subList(from, (from + 200).coerceAtMost(songs.size))
        val saved = Saved(
            songs = window.map {
                val server = ServerLibrary.parseMediaId(it.mediaId)
                SavedSong(
                    it.mediaId, if (server == null) it.uri.toString() else null, it.title, it.artistName, it.albumTitle,
                    stableArtwork(it.mediaId, it.artworkUri?.toString()), it.durationMs, it.identity, it.trackNumber, it.year, it.genre,
                )
            },
            index = index - from,
            positionMs = positionMs,
            origin = origin?.title,
            originKind = origin?.kind?.name,
            tracksRadio = (origin?.kind == QueueOrigin.Kind.Radio).takeIf { it },
        )
        prefs.edit().putString(KEY, json.encodeToString(Saved.serializer(), saved)).apply()
    }

    fun restore(): Restored? {
        val raw = prefs.getString(KEY, null) ?: return null
        val saved = runCatching { json.decodeFromString(Saved.serializer(), raw) }.getOrNull() ?: return null
        if (saved.songs.isEmpty()) return null
        val songs = saved.songs.mapNotNull {
            val uri = ServerLibrary.parseMediaId(it.mediaId)?.let { (server, song) -> ServerLinks.stream(server, song) }
                ?: it.uri?.let(Uri::parse)
                ?: return@mapNotNull null
            PlayableSong(it.mediaId, uri, it.title, it.artist, it.album, stableArtwork(it.mediaId, it.artwork)?.let(Uri::parse), it.durationMs, it.identity, it.track, it.year, it.genre)
        }
        if (songs.isEmpty()) return null
        val origin = saved.origin?.let { title ->
            QueueOrigin(title, runCatching { QueueOrigin.Kind.valueOf(saved.originKind.orEmpty()) }.getOrDefault(QueueOrigin.Kind.Other))
        }
        return Restored(songs, saved.index.coerceIn(0, songs.lastIndex), saved.positionMs, origin, tracksRadio = saved.tracksRadio == true)
    }

    companion object {
        /**
         * A cover address safe to keep: a link as it is, a server song's older signed cover
         * address turned into its link, and any other credential-carrying address of a server
         * song dropped.
         */
        fun stableArtwork(mediaId: String, artwork: String?): String? {
            artwork ?: return null
            if (ServerLinks.isLink(artwork)) return artwork
            val server = ServerLibrary.parseMediaId(mediaId)?.first ?: return artwork
            val url = artwork.toHttpUrlOrNull() ?: return artwork
            if (url.queryParameterNames.none { it.lowercase() in Redact.SECRET_PARAMETERS }) return artwork
            val cover = url.queryParameter("id")?.takeIf { url.pathSegments.lastOrNull() == "getCoverArt" } ?: return null
            return ServerLinks.coverUrl(server, cover, url.queryParameter("size")?.toIntOrNull())
        }

        private const val KEY = "queue"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

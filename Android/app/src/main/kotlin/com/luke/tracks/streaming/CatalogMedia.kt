package com.luke.tracks.streaming

import android.net.Uri
import com.luke.tracks.data.PlaySourceValue
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.net.ServerLinks
import com.luke.tracks.playback.PlayableSong

/**
 * A streaming service's song as the rest of Tracks carries it: `catalog:<server>:<ref>`.
 * Such a song never plays from an address of its own; the queue it is in plays through its
 * server's relay ([RelayEngine]). The ref is kept exactly as the server gave it.
 */
object CatalogMedia {
    private const val PREFIX = "catalog:"

    /** Never fetched: the relay plays these, so anything that tries to load one fails at once. */
    private const val SCHEME = "tracks-catalog"

    fun mediaId(serverId: String, ref: String) = "$PREFIX$serverId:$ref"

    /** The server and ref a media id names, if it's a service's song. */
    fun parse(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(PREFIX)) return null
        val rest = mediaId.removePrefix(PREFIX)
        val split = rest.indexOf(':').takeIf { it > 0 } ?: return null
        val ref = rest.substring(split + 1).takeIf { it.isNotEmpty() } ?: return null
        return rest.substring(0, split) to ref
    }

    fun isCatalog(mediaId: String) = mediaId.startsWith(PREFIX)

    /** [identity] is the engine's song identity for the title and artist, as library songs have. */
    fun playable(serverId: String, track: TracksideCatalogTrack, identity: String): PlayableSong = PlayableSong(
        mediaId = mediaId(serverId, track.ref),
        uri = Uri.parse("$SCHEME://$serverId/${track.ref}"),
        title = track.title,
        artistName = track.artist,
        albumTitle = track.album,
        artworkUri = track.artworkId?.takeIf { it.isNotEmpty() }?.let { ServerLinks.cover(serverId, it) },
        durationMs = track.durationMs,
        identity = identity,
        trackNumber = track.trackNumber,
    )

    /**
     * Whether a queue plays through the relay: it does once any song in it is a service's.
     * Then it plays on the first such song's server, which also plays that server's own songs.
     */
    fun relayServer(mediaIds: List<String>): String? = mediaIds.firstNotNullOfOrNull { parse(it)?.first }

    /**
     * A queue as the relay plays it on [serverId]: its own songs and services' songs, in
     * order; songs from anywhere else are left out. [startIndex] moves with what's left out,
     * to the next song kept. Null when nothing is left.
     */
    fun request(
        mediaIds: List<String>,
        startIndex: Int,
        serverId: String,
        positionMs: Long = 0,
        shuffle: Boolean = false,
        repeat: String = "off",
        context: com.luke.tracks.library.trackside.TracksideSpeaker.Context? = null,
    ): Kept? {
        val items = mediaIds.map { id ->
            parse(id)?.takeIf { it.first == serverId }?.let { TracksideSpeakerPlayRequest.Item(ref = it.second) }
                ?: ServerLibrary.parseMediaId(id)?.takeIf { it.first == serverId }?.let { TracksideSpeakerPlayRequest.Item(songId = it.second) }
        }
        if (items.all { it == null }) return null
        val start = startIndex.coerceIn(0, items.lastIndex)
        val from = (start until items.size).firstOrNull { items[it] != null } ?: items.indexOfLast { it != null }
        val kept = items.withIndex().filter { it.value != null }
        // Up to the limit, from a little before the start so going back still works there.
        val startPosition = kept.indexOfFirst { it.index == from }
        val first = (startPosition - BEFORE).coerceAtLeast(0).coerceAtMost((kept.size - TracksideSpeakerPlayRequest.MAX_ITEMS).coerceAtLeast(0))
        val window = kept.drop(first).take(TracksideSpeakerPlayRequest.MAX_ITEMS)
        val startAt = startPosition - first
        return Kept(
            request = TracksideSpeakerPlayRequest(
                items = window.map { it.value!! },
                mode = "replace",
                startIndex = startAt,
                positionMs = if (from == start) positionMs.coerceAtLeast(0) else 0,
                shuffle = shuffle,
                repeat = repeat,
                context = context,
            ),
            positions = window.map { it.index },
        )
    }

    /** A play request and, for each of its items, where it was in the queue it came from. */
    data class Kept(val request: TracksideSpeakerPlayRequest, val positions: List<Int>)

    private const val BEFORE = 50
}

/** Where a play on a service is kept as coming from: its kind, as the Apple apps and the engine name sources. */
object CatalogPlays {
    /** Apple Music and Spotify are sources Tracks knows; any other service is kept by its own name. */
    fun source(logoKey: String?): Pair<PlaySourceValue, String?> = when (logoKey) {
        "apple_music" -> PlaySourceValue.Streaming to null
        "spotify" -> PlaySourceValue.Spotify to null
        null, "" -> PlaySourceValue.Other to "service"
        else -> PlaySourceValue.Other to logoKey
    }

    /** The cloud fields that carry a source Tracks has no value of its own for. */
    fun cloudFields(stored: String?): String? = stored?.let { "{\"source\":\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"}" }
}

package com.luke.tracks.playback

import com.luke.tracks.streaming.CatalogMedia

/** How a saved queue comes back when the player resumes it: Tracks Radio again, or the queue as it was. */
object Resumption {
    /** Whether the queue holds a service's song, so it comes back through the relay. */
    fun streams(songs: List<PlayableSong>): Boolean = CatalogMedia.relayServer(songs.map { it.mediaId }) != null

    /**
     * Whether Tracks Radio starts again from the song it was on, wherever its songs played from
     * ([tracksRadio]: saved as Tracks Radio by a version that told it from stations). Never for a
     * service's station or queue, even one saved as Tracks Radio before stations had their own kind.
     */
    fun restartsTracksRadio(origin: QueueOrigin?, songs: List<PlayableSong>, tracksRadio: Boolean = false): Boolean =
        origin?.kind == QueueOrigin.Kind.Radio && (tracksRadio || !streams(songs))

    /** Why the queue was playing; a station saved as Tracks Radio comes back as the station it is. */
    fun origin(origin: QueueOrigin?, songs: List<PlayableSong>, tracksRadio: Boolean = false): QueueOrigin? =
        if (origin?.kind == QueueOrigin.Kind.Radio && !tracksRadio && streams(songs)) origin.copy(kind = QueueOrigin.Kind.Station) else origin
}

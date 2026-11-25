package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Songs queued before they're in your library: new songs a Trackside server suggests, or that
 * the catalog has, which it gets for you. Each waits in the queue under a stand-in address,
 * shown with its title, artist and cover, and is got as it's reached ([GetAsReached]): the
 * player sits on it loading, then plays the song once the server streams it or has it.
 */
object SongsToGet {
    const val SCHEME = "tracks-get"
    private const val PREFIX = "trackside-get:"

    fun mediaId(serverId: String, identity: String) = "$PREFIX$serverId:$identity"

    /** The server and song identity a media id names, if it's a song to get. */
    fun parse(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith(PREFIX)) return null
        val rest = mediaId.removePrefix(PREFIX)
        val split = rest.indexOf(':').takeIf { it > 0 } ?: return null
        return rest.substring(0, split) to rest.substring(split + 1)
    }

    fun isToGet(mediaId: String?) = mediaId != null && mediaId.startsWith(PREFIX)

    fun isToGet(item: MediaItem) = isToGet(item.mediaId) || item.localConfiguration?.uri?.scheme == SCHEME

    /** The stand-in address: it's never fetched, only waited on ([WaitingMediaSource]). */
    fun uri(serverId: String, identity: String): Uri = Uri.Builder().scheme(SCHEME).authority(serverId).appendPath(identity).build()

    /** A song to get from a Trackside server, queued as it would play. */
    fun song(serverId: String, identity: String, title: String, artist: String, album: String?, durationMs: Long?, artworkUri: Uri?) = PlayableSong(
        mediaId = mediaId(serverId, identity),
        uri = uri(serverId, identity),
        title = title,
        artistName = artist,
        albumTitle = album,
        artworkUri = artworkUri,
        durationMs = durationMs?.takeIf { it > 0 },
        identity = identity,
    )
}

/** A song waiting to be got, in the queue. */
val PlayableSong.isToGet: Boolean get() = SongsToGet.isToGet(mediaId)

/** How getting a song to play went: the song to play in its place, or why it can't be. */
sealed interface ToPlay {
    data class Ready(val song: PlayableSong) : ToPlay
    data class Failed(val message: String) : ToPlay
}

/**
 * Songs to get, played from a stand-in that never finishes preparing: ExoPlayer moves onto it
 * as it would a stream that's slow to start (at the end of the song before, or straight away
 * when it's chosen), says it's buffering, and plays nothing, so nothing is counted as a listen.
 * [GetAsReached] puts the real song in its place.
 */
class WaitingMediaSource(private var item: MediaItem) : BaseMediaSource() {
    private var prepared = false

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        prepared = true
        refreshSourceInfo(timeline())
    }

    private fun timeline(): Timeline = SinglePeriodTimeline(C.TIME_UNSET, false, false, false, null, item)

    override fun maybeThrowSourceInfoRefreshError() = Unit

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod = WaitingPeriod

    override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit

    override fun releaseSourceInternal() {
        prepared = false
    }

    /** The same song with new details, such as the cover once it's found, updates in place. */
    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean =
        mediaItem.mediaId == item.mediaId && mediaItem.localConfiguration?.uri == item.localConfiguration?.uri

    override fun updateMediaItem(mediaItem: MediaItem) {
        item = mediaItem
        if (prepared) refreshSourceInfo(timeline())
    }

    /** Never prepared, never loading: the player waits on it. */
    private object WaitingPeriod : MediaPeriod {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) = Unit
        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray(*emptyArray<TrackGroup>())
        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            streams.fill(null)
            return positionUs
        }
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = C.TIME_UNSET
        override fun seekToUs(positionUs: Long): Long = positionUs
        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs
        override fun getBufferedPositionUs(): Long = 0
        override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false
        override fun isLoading(): Boolean = false
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }

    /** Songs to get wait in a [WaitingMediaSource]; everything else goes to [delegate]. */
    class Factory(private val delegate: MediaSource.Factory) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory =
            apply { delegate.setDrmSessionManagerProvider(drmSessionManagerProvider) }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory =
            apply { delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }

        override fun getSupportedTypes(): IntArray = delegate.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            if (SongsToGet.isToGet(mediaItem)) WaitingMediaSource(mediaItem) else delegate.createMediaSource(mediaItem)
    }
}

/**
 * Gets the song to get that the player has reached, while it's meant to be playing: asks for it
 * once, says how it's going in [status], then puts the song in its place to play from there, or
 * takes it out of the queue and says why, so the next song plays. Moving to another song stops
 * waiting, though the server goes on getting it; coming back to it waits again.
 */
class GetAsReached(
    private val queue: Queue,
    private val scope: CoroutineScope,
    private val get: suspend (song: PlayableSong, status: (String) -> Unit) -> ToPlay,
    /** A cover for a song queued without one. */
    private val cover: suspend (PlayableSong) -> Uri? = { null },
    private val failed: (String) -> Unit,
    private val status: MutableStateFlow<PlaybackBridge.Getting?> = PlaybackBridge.getting,
) {
    /** The parts of the player this needs, so tests can stand in for it. */
    interface Queue {
        val current: PlayableSong?
        val playWhenReady: Boolean
        /** Where a song is in the queue, the one playing first. */
        fun indexOf(mediaId: String): Int?
        fun replace(index: Int, song: PlayableSong)
        fun remove(index: Int)
    }

    private var job: Job? = null
    private var gettingId: String? = null

    /** Called whenever the song on, the queue or play/pause changes. */
    fun check() {
        val song = queue.current
        if (song?.mediaId != gettingId) stop()
        if (song == null || !song.isToGet || job?.isActive == true || !queue.playWhenReady) return
        start(song)
    }

    fun stop() {
        job?.cancel()
        job = null
        if (gettingId != null) status.value = null
        gettingId = null
    }

    private fun start(song: PlayableSong) {
        val id = song.mediaId
        gettingId = id
        status.value = PlaybackBridge.Getting(id, "Getting it…")
        job = scope.launch {
            val self = coroutineContext[Job]
            var artwork = song.artworkUri
            if (artwork == null) launch {
                val found = runCatching { cover(song) }.getOrNull() ?: return@launch
                artwork = found
                queue.indexOf(id)?.let { queue.replace(it, song.copy(artworkUri = found)) }
            }
            val result = try {
                get(song) { said -> if (gettingId == id) status.value = PlaybackBridge.Getting(id, said) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ToPlay.Failed("Couldn't get “${song.title}”.")
            }
            if (gettingId == id) {
                gettingId = null
                status.value = null
            }
            // Done: the change below sets off [check] for whatever plays next.
            if (job === self) job = null
            val index = queue.indexOf(id)
            if (index != null) {
                when (result) {
                    is ToPlay.Ready -> queue.replace(index, result.song.let { if (it.artworkUri == null) it.copy(artworkUri = artwork) else it })
                    is ToPlay.Failed -> {
                        queue.remove(index)
                        failed(result.message)
                    }
                }
            }
            // The cover may still be on its way: it's no use now.
            self?.children?.forEach { it.cancel() }
        }
    }
}

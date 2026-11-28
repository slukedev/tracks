package com.luke.tracks.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.net.ServerLinks
import com.luke.tracks.streaming.CatalogMedia
import com.luke.tracks.streaming.RelayClock
import com.luke.tracks.streaming.RelayEngine
import com.luke.tracks.streaming.RelayItem
import com.luke.tracks.streaming.RelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * The session's player while a queue plays through the relay. It shows the relay's queue and
 * sends every control to [engine]; underneath, its own ExoPlayer plays the live HLS of the
 * generation the engine names, and nothing else: when the engine names none, it plays
 * nothing, so audio from before a seek, a skip or a pause is never heard.
 *
 * Audio focus and headphones coming out pause it the way they pause any of Tracks' music,
 * and the media session, notification, lock screen and car see it as they see the phone's
 * own player.
 */
class RelayPlayer(
    context: Context,
    private val engine: RelayEngine,
    private val scope: CoroutineScope,
    /** The server's own client, the one that holds its pinned certificates. */
    private val http: () -> OkHttpClient,
    /** A queue of library songs only: the phone plays those itself. */
    private val playNatively: (List<MediaItem>, Int, Long) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    /** The audio itself. Not the session's player: [RelayPlayer] stands in front of it. */
    val audio: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setMediaSourceFactory(
            HlsMediaSource.Factory(OkHttpDataSource.Factory(Call.Factory { request -> http().newCall(request) }))
                // A playlist that waits for its first audio or a segment lost to a network switch is asked again, not given up on.
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES)),
        )
        .build()

    private var status: RelayStatus = engine.status.value
    private var loaded: String? = null
    private val window = Timeline.Window()

    /** Services by id, for each song's source: set by whoever starts the queue. */
    var services: () -> Map<String, String> = { emptyMap() }

    /**
     * Whether a song waits here rather than going to the server with the queue: one Play From
     * would play from somewhere else (a service above your copy, or a song not on this server).
     */
    var waits: (serverId: String, MediaItem) -> Boolean = { _, _ -> false }

    /** The song as this server's relay plays it, after Play From: null when it can't play here. */
    var resolve: suspend (serverId: String, MediaItem) -> MediaItem? = { serverId, item -> item.takeIf { playsOn(serverId, it.mediaId) } }

    /** A song that waited and then couldn't play here: passed over, and said so. */
    var passedOver: (MediaItem) -> Unit = {}

    /**
     * Songs after the server's queue that aren't sent yet, shown after it. Each is sent once it's
     * due ([sendNext]), as the one before ends, or at once when it's skipped to.
     */
    private var held: List<MediaItem> = emptyList()
    private val answers = HashMap<String, Deferred<MediaItem?>>()
    /** What each song is, by media id, as it was handed in: the server's queue doesn't carry it. */
    private val songs = HashMap<String, PlayableSong>()
    private var sending: Job? = null

    init {
        audio.addListener(audioListener())
        scope.launch {
            engine.status.collect { fresh ->
                // The relay stopped: songs waiting to go to it go with it.
                if (status.serverId != null && fresh.serverId == null) clearHeld()
                status = fresh
                follow(fresh)
                invalidateState()
            }
        }
    }

    /** Whether the relay has anything to show: the session's player only switches here while it does. */
    val isActive: Boolean get() = status.isActive

    // MARK: What the session sees

    override fun getState(): SimpleBasePlayer.State {
        val s = status
        val builder = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(s.wantsToPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setAudioAttributes(audio.audioAttributes)
            .setShuffleModeEnabled(s.shuffle)
            .setRepeatMode(
                when (s.repeat) {
                    "one" -> Player.REPEAT_MODE_ONE
                    "all" -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                },
            )
        if (s.items.isEmpty()) return builder.setPlaybackState(Player.STATE_IDLE).build()
        val heard = audio.playbackState == Player.STATE_READY && audio.playWhenReady
        return builder
            .setPlaylist(playlist(s))
            .setCurrentMediaItemIndex(s.index.coerceIn(0, s.items.lastIndex))
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier { position() })
            // Meant to play and not yet heard: loading, as a song from the phone's server is while it buffers.
            .setPlaybackState(if (s.wantsToPlay && !heard) Player.STATE_BUFFERING else Player.STATE_READY)
            .setIsLoading(s.wantsToPlay && !heard)
            .build()
    }

    /**
     * Each song keyed by what it is (and which time it's in the queue), not by the server's
     * item id: the queue shown before the server answers and the server's own are the same
     * songs, so the song playing never seems to change, and its listen goes on counting.
     */
    private fun playlist(s: RelayStatus): List<SimpleBasePlayer.MediaItemData> {
        val seen = HashMap<String, Int>()
        val sent = s.items.map { item ->
            val n = seen.merge(item.mediaId, 1, Int::plus)!!
            data(item, "${item.mediaId}#$n", s.serverId.orEmpty())
        }
        val waiting = held.map { item ->
            val n = seen.merge(item.mediaId, 1, Int::plus)!!
            SimpleBasePlayer.MediaItemData.Builder("${item.mediaId}#$n")
                .setMediaItem(item)
                .setDurationUs(item.mediaMetadata.durationMs?.takeIf { it > 0 }?.let { it * 1_000 } ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        return sent + waiting
    }

    private fun data(item: RelayItem, uid: String, serverId: String) =
        SimpleBasePlayer.MediaItemData.Builder(uid)
            .setMediaItem(mediaItem(item, serverId))
            .setDurationUs(item.durationMs?.takeIf { it > 0 }?.let { it * 1_000 } ?: C.TIME_UNSET)
            .setIsSeekable(true)
            .build()

    /** As the app's queue and the notification show it; the address never plays (the relay does). */
    private fun mediaItem(item: RelayItem, serverId: String): MediaItem {
        val artwork = item.artworkId?.takeIf { it.isNotEmpty() }?.let { ServerLinks.cover(serverId, it) }
        val uri = Uri.parse("tracks-catalog://$serverId/${item.itemId}")
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .setArtworkUri(artwork)
                    .setDurationMs(item.durationMs)
                    .setStation(item.serviceId?.let { status.services[it] })
                    // A song handed in keeps what it is (its identity, for Tracks Radio and copies).
                    .setExtras(songs[item.mediaId]?.toMediaItem()?.mediaMetadata?.extras)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
    }

    /** Where the item is as heard: from the playlist's program date while it plays, else the server's clock less the latency. */
    private fun position(): Long {
        val s = status
        var audible: Long? = null
        if (audio.isPlaying) {
            val timeline = audio.currentTimeline
            if (!timeline.isEmpty) {
                timeline.getWindow(audio.currentMediaItemIndex, window)
                if (window.windowStartTimeMs != C.TIME_UNSET) audible = window.windowStartTimeMs + audio.currentPosition
            }
        }
        return RelayClock.position(s, engine.serverNowMs(), audible)
    }

    // MARK: What the session asks

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        (if (playWhenReady) engine.resume() else engine.pause()).asFuture()

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> = engine.pause().asFuture()

    override fun handleRelease(): ListenableFuture<*> {
        audio.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val current = status.index
        val toNext = seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        // Nothing after this song on the server yet: the song waiting next is sent, and played.
        if (held.isNotEmpty() && current >= status.items.lastIndex && (toNext || mediaItemIndex > status.items.lastIndex)) {
            val skipTo = if (toNext) 0 else (mediaItemIndex - status.items.size).coerceIn(0, held.lastIndex)
            held = held.drop(skipTo)
            return sendNext(skip = true)
        }
        val done = when {
            seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> engine.next()
            seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> engine.previous()
            mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != current -> engine.jump(mediaItemIndex)
            else -> engine.seek(if (positionMs == C.TIME_UNSET) 0 else positionMs)
        }
        return done.asFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = engine.setShuffle(shuffleModeEnabled).asFuture()

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = engine.setRepeat(
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "one"
            Player.REPEAT_MODE_ALL -> "all"
            else -> "off"
        },
    ).asFuture()

    /**
     * A new queue: through the relay while any of it is a service's, else back to the phone's
     * own player. Songs before the start go as they are where the server has them; from the
     * start, songs go until one waits ([waits]), and that one and the rest are held and sent
     * as each comes due.
     */
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val ids = mediaItems.map { it.mediaId }
        val serverId = CatalogMedia.relayServer(ids)
        if (serverId == null) {
            playNatively(mediaItems, startIndex, startPositionMs)
            return Futures.immediateVoidFuture()
        }
        remember(mediaItems)
        val start = (if (startIndex == C.INDEX_UNSET) 0 else startIndex).coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        val firstHeld = firstWaiting(serverId, mediaItems, start)
        val sent = mediaItems.subList(0, firstHeld)
        clearHeld()
        held = mediaItems.subList(firstHeld, mediaItems.size).toList()
        val kept = kept(serverId, sent, start, if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs) ?: return Futures.immediateVoidFuture()
        val preview = kept.positions.map { preview(sent[it]) }
        prefetch()
        return engine.play(serverId, kept, preview, services()).asFuture()
    }

    /**
     * Has the server hold the queue that [mediaItems] from [startIndex] would send, paused at
     * its start, while the phone's own player finishes the song before it: the same request
     * [handleSetMediaItems] makes then, which the engine starts on cue.
     */
    fun holdAhead(mediaItems: List<MediaItem>, startIndex: Int) {
        val serverId = CatalogMedia.parse(mediaItems.getOrNull(startIndex)?.mediaId ?: return)?.first ?: return
        val sent = mediaItems.subList(0, firstWaiting(serverId, mediaItems, startIndex))
        kept(serverId, sent, startIndex, 0)?.let { engine.hold(serverId, it) }
    }

    private fun firstWaiting(serverId: String, mediaItems: List<MediaItem>, start: Int): Int =
        (start + 1 until mediaItems.size).firstOrNull { waits(serverId, mediaItems[it]) } ?: mediaItems.size

    private fun kept(serverId: String, sent: List<MediaItem>, start: Int, positionMs: Long): CatalogMedia.Kept? = CatalogMedia.request(
        sent.map { it.mediaId }, start, serverId,
        positionMs = positionMs,
        shuffle = status.shuffle,
        repeat = status.repeat,
        context = PlaybackBridge.origin.value?.let { com.luke.tracks.library.trackside.TracksideSpeaker.Context(kind = contextKind(it.kind), name = it.title) },
    )

    /**
     * The service's own radio from [seed], a service's song: the server's music system picks
     * the rest as it plays. If the service can't make one, the engine says so ([RelayEngine.radioFailed]).
     */
    fun playRadio(seed: MediaItem): ListenableFuture<*> {
        val serverId = CatalogMedia.parse(seed.mediaId)?.first ?: return Futures.immediateVoidFuture()
        remember(listOf(seed))
        clearHeld()
        val kept = CatalogMedia.request(
            listOf(seed.mediaId), 0, serverId,
            context = PlaybackBridge.origin.value?.let { com.luke.tracks.library.trackside.TracksideSpeaker.Context(kind = contextKind(it.kind), name = it.title) },
        ) ?: return Futures.immediateVoidFuture()
        val radio = kept.copy(request = kept.request.copy(radio = true, startIndex = null, positionMs = null, shuffle = null, repeat = null))
        return engine.play(serverId, radio, listOf(preview(seed)), services(), radioOf = seed.mediaId).asFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val serverId = status.serverId ?: return Futures.immediateVoidFuture()
        remember(mediaItems)
        // After songs that wait: these wait with them, in their place.
        if (held.isNotEmpty() && index >= status.items.size) {
            val at = (index - status.items.size).coerceIn(0, held.size)
            held = held.toMutableList().apply { addAll(at, mediaItems) }
            prefetch()
            return Futures.immediateVoidFuture()
        }
        val kept = mediaItems.mapNotNull { item -> request(item, serverId)?.let { it to preview(item) } }
        // Only next and last are places the server's queue takes songs.
        return engine.enqueue(kept.map { it.first }, kept.map { it.second }, next = index <= status.index + 1).asFuture()
    }

    /** Songs to show after the server's queue, sent as each comes due: Tracks Radio's next pick. */
    fun hold(items: List<MediaItem>) {
        if (items.isEmpty()) return
        remember(items)
        held = held + items
        prefetch()
        invalidateState()
    }

    /** Whether a song is waiting here, not sent yet. */
    val holding: Boolean get() = held.isNotEmpty()

    /** How many songs the server's own queue holds; songs after these wait here. */
    val sentCount: Int get() = status.items.size

    /** Looks up where the next songs that wait play from, so each is ready before it's due. */
    fun prefetch() {
        val serverId = status.serverId ?: CatalogMedia.relayServer(held.map { it.mediaId }) ?: return
        held.take(AHEAD).forEach { item ->
            answers.getOrPut(item.mediaId) { scope.async { runCatching { resolve(serverId, item) }.getOrNull() } }
        }
    }

    /**
     * Sends the first song that waits to the server, after the queue or ([skip]) as the next
     * song and moved on to at once. One that can't play here is passed over for the one after.
     */
    fun sendNext(skip: Boolean = false): ListenableFuture<*> {
        val future = SettableFuture.create<Unit>()
        if (sending?.isActive == true && !skip) return future.also { it.set(Unit) }
        sending?.cancel()
        sending = scope.launch {
            try {
                while (held.isNotEmpty()) {
                    val serverId = status.serverId ?: break
                    val item = held.first()
                    val resolved = (answers[item.mediaId] ?: scope.async { runCatching { resolve(serverId, item) }.getOrNull() }).await()
                    if (held.firstOrNull() !== item) continue
                    held = held.drop(1)
                    answers.remove(item.mediaId)
                    val wire = resolved?.let { request(it, serverId) }
                    if (resolved == null || wire == null) {
                        passedOver(item)
                        invalidateState()
                        continue
                    }
                    remember(listOf(resolved))
                    invalidateState()
                    engine.enqueue(listOf(wire), listOf(preview(resolved)), next = skip, skip = skip).await()
                    break
                }
                prefetch()
            } finally {
                future.set(Unit)
            }
        }
        return future
    }

    private fun clearHeld() {
        held = emptyList()
        answers.values.forEach { it.cancel() }
        answers.clear()
        sending?.cancel()
    }

    private fun remember(items: List<MediaItem>) {
        items.forEach { item -> item.toPlayableSong().takeIf { it.identity.isNotEmpty() }?.let { songs[item.mediaId] = it } }
        if (songs.size > MAX_SONGS) songs.keys.take(songs.size - MAX_SONGS).forEach(songs::remove)
    }

    /** Songs that wait can be taken out and moved; the server's own queue can't be from here. */
    /** Whether a song already sent to the server can be taken out again: not on every server. */
    val removesSent: Boolean get() = engine.removesQueued

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val sent = status.items.size
        if (fromIndex < sent && removesSent) engine.remove(fromIndex, toIndex.coerceAtMost(sent))
        if (toIndex <= sent || held.isEmpty()) return Futures.immediateVoidFuture()
        val from = (fromIndex - sent).coerceAtLeast(0)
        val to = (toIndex - sent).coerceAtMost(held.size)
        if (from < to) {
            held.subList(from, to).forEach { answers.remove(it.mediaId)?.cancel() }
            held = held.filterIndexed { i, _ -> i < from || i >= to }
            prefetch()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val sent = status.items.size
        if (fromIndex < sent || newIndex < sent || held.isEmpty()) return Futures.immediateVoidFuture()
        val moving = held.subList((fromIndex - sent).coerceIn(0, held.size), (toIndex - sent).coerceIn(0, held.size)).toList()
        val rest = held.toMutableList().apply { removeAll(moving) }
        held = rest.apply { addAll((newIndex - sent).coerceIn(0, rest.size), moving) }
        prefetch()
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val sent = status.items.size
        if (fromIndex < sent || held.isEmpty()) return Futures.immediateVoidFuture()
        val from = (fromIndex - sent).coerceIn(0, held.size)
        val to = (toIndex - sent).coerceIn(from, held.size)
        remember(mediaItems)
        held.subList(from, to).forEach { answers.remove(it.mediaId)?.cancel() }
        held = held.toMutableList().apply {
            repeat(to - from) { removeAt(from) }
            addAll(from, mediaItems)
        }
        prefetch()
        return Futures.immediateVoidFuture()
    }

    private fun request(item: MediaItem, serverId: String): TracksideSpeakerPlayRequest.Item? = request(item.mediaId, serverId)

    private fun preview(item: MediaItem): RelayItem {
        val song = item.toPlayableSong()
        return RelayItem(
            itemId = "",
            mediaId = song.mediaId,
            title = song.title,
            artist = song.artistName.takeIf { song.hasArtist },
            album = song.albumTitle,
            artworkId = ServerLinks.parse(song.artworkUri?.toString())?.takeIf { it.kind == ServerLinks.Kind.Cover }?.id,
            durationMs = song.durationMs,
            serviceId = null,
        )
    }

    /** The relay's item at [index] in the playlist, for what it is and where it came from. */
    fun itemAt(index: Int): RelayItem? = status.items.getOrNull(index)

    val serverId: String? get() = status.serverId

    // MARK: The audio underneath

    /** Plays the generation the engine names; nothing at all when it names none. */
    private fun follow(s: RelayStatus) {
        val url = s.stream?.url
        if (url != loaded) {
            loaded = url
            if (url == null) {
                audio.stop()
                audio.clearMediaItems()
            } else {
                audio.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                audio.prepare()
            }
        }
        val play = s.wantsToPlay && url != null
        if (audio.playWhenReady != play) audio.playWhenReady = play
    }

    private fun audioListener() = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val url = loaded ?: return
            val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            // Let the engine read the session again: a new generation, a fresh token, or the same one once more.
            loaded = null
            engine.streamFailed(url, status)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Another app took the audio for good, or headphones came out: that's a pause, said to the server too.
            if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                engine.pause()
            }
            invalidateState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // The playlist ended (#EXT-X-ENDLIST) while meant to play on: the next generation is due.
            if (playbackState == Player.STATE_ENDED) loaded?.let(engine::streamEnded)
            invalidateState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = invalidateState()
    }

    private fun Deferred<Unit>.asFuture(): ListenableFuture<*> {
        val future = SettableFuture.create<Unit>()
        invokeOnCompletion { future.set(Unit) }
        return future
    }

    private fun contextKind(kind: QueueOrigin.Kind) = when (kind) {
        QueueOrigin.Kind.Mix -> "mix"
        QueueOrigin.Kind.Radio, QueueOrigin.Kind.Station -> "station"
        QueueOrigin.Kind.Album -> "album"
        QueueOrigin.Kind.Artist -> "artist"
        QueueOrigin.Kind.Playlist -> "playlist"
        QueueOrigin.Kind.Search -> "search"
        QueueOrigin.Kind.Library, QueueOrigin.Kind.Other -> "other"
    }

    companion object {
        private const val LOAD_RETRIES = 6
        /** Songs that wait looked up ahead of being due. */
        private const val AHEAD = 2
        private const val MAX_SONGS = 1_000

        /** Whether the relay on [serverId] plays this song as it is: the server's own, or one of its services'. */
        fun playsOn(serverId: String, mediaId: String): Boolean = request(mediaId, serverId) != null

        private fun request(mediaId: String, serverId: String): TracksideSpeakerPlayRequest.Item? =
            CatalogMedia.parse(mediaId)?.takeIf { it.first == serverId }?.let { TracksideSpeakerPlayRequest.Item(ref = it.second) }
                ?: com.luke.tracks.library.ServerLibrary.parseMediaId(mediaId)?.takeIf { it.first == serverId }?.let { TracksideSpeakerPlayRequest.Item(songId = it.second) }

        private val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_RELEASE,
        ).build()
    }
}

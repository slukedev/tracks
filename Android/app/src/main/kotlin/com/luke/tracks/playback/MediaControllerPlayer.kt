package com.luke.tracks.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.luke.tracks.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Tracks' player as the UI drives it: a Media3 [MediaController] connected to
 * [PlaybackService], so the app, the notification, the car and a watch all control the same
 * queue. Connects on first use (or when [connect] is called at launch), and queues commands
 * until it's connected.
 */
class MediaControllerPlayer(private val context: Context) : PlayerController {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val pending = ArrayList<MediaController.() -> Unit>()

    /** Songs by media id, as they were handed in, so the queue reads back in full. */
    private val known = HashMap<String, PlayableSong>()

    private val base = MutableStateFlow(PlayerState())
    private val _state = MutableStateFlow(PlayerState())
    private val _position = MutableStateFlow(0L)
    private var sampling: Job? = null
    private var queueCache: List<PlayableSong> = emptyList()

    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override val position: StateFlow<Long> = _position.asStateFlow()

    init {
        // The service's own news (keeping, sleep, origin) laid over what Media3 reports.
        scope.launch {
            combine(base, PlaybackBridge.keep, PlaybackBridge.sleepAtMs, PlaybackBridge.origin) { state, keep, sleep, origin ->
                val forCurrent = keep.mediaId != null && keep.mediaId == state.current?.mediaId
                state.copy(
                    keepProgress = if (forCurrent) keep.progress else 0f,
                    kept = forCurrent && keep.kept,
                    sleepAtMs = sleep,
                    origin = origin,
                )
            }.collect { _state.value = it }
        }
    }

    /** Connects now, so a queue already playing (from the car, say) shows up in the app. */
    fun connect() {
        main.post { ensureConnected() }
    }

    private fun ensureConnected() {
        if (controller != null || connecting != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        connecting = future
        future.addListener({
            connecting = null
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            connected.addListener(listener)
            read(connected, timelineChanged = true)
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(connected) }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun run(block: MediaController.() -> Unit) {
        main.post {
            val connected = controller
            if (connected != null && connected.isConnected) connected.block()
            else {
                if (connected != null) controller = null
                pending += block
                ensureConnected()
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val timeline = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)
            (player as? MediaController)?.let { read(it, timeline) }
        }
    }

    private fun read(c: MediaController, timelineChanged: Boolean) {
        if (timelineChanged || queueCache.size != c.mediaItemCount) {
            queueCache = List(c.mediaItemCount) { i ->
                val item = c.getMediaItemAt(i)
                // A song being got takes its cover from the queue, once one's found for it.
                known[item.mediaId]?.takeIf { !it.isToGet } ?: item.toPlayableSong()
            }
        }
        val index = c.currentMediaItemIndex.coerceAtLeast(0)
        val current = queueCache.getOrNull(index)
        val duration = ListenCounter.knownLength(c.duration) ?: ListenCounter.knownLength(current?.durationMs) ?: 0L
        base.value = PlayerState(
            current = current,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            isLoading = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition,
            durationMs = duration,
            queue = queueCache,
            index = index,
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatChoice.One
                Player.REPEAT_MODE_ALL -> RepeatChoice.All
                else -> RepeatChoice.Off
            },
        )
        _position.value = c.currentPosition
        if (c.isPlaying) startSampling(c) else stopSampling()
    }

    /** About four times a second while playing; nothing at all while paused. */
    private fun startSampling(c: MediaController) {
        if (sampling?.isActive == true) return
        sampling = scope.launch {
            while (isActive) {
                _position.value = c.currentPosition
                delay(250)
            }
        }
    }

    private fun stopSampling() {
        sampling?.cancel()
        sampling = null
    }

    private fun remember(songs: List<PlayableSong>) {
        songs.forEach { known[it.mediaId] = it }
    }

    override fun play(songs: List<PlayableSong>, startIndex: Int, origin: QueueOrigin?, shuffle: Boolean) {
        if (songs.isEmpty()) return
        remember(songs)
        PlaybackBridge.origin.value = origin
        val items = songs.map { it.toMediaItem() }
        val start = if (shuffle) Random.nextInt(songs.size) else startIndex.coerceIn(0, songs.lastIndex)
        run {
            if (shuffle && !shuffleModeEnabled) shuffleModeEnabled = true
            setMediaItems(items, start, 0)
            prepare()
            play()
        }
    }

    override fun playNext(song: PlayableSong) {
        remember(listOf(song))
        run {
            if (mediaItemCount == 0) {
                setMediaItem(song.toMediaItem())
                prepare()
                play()
            } else addMediaItem(currentMediaItemIndex + 1, song.toMediaItem())
        }
    }

    override fun addToQueue(song: PlayableSong) {
        remember(listOf(song))
        run {
            if (mediaItemCount == 0) {
                setMediaItem(song.toMediaItem())
                prepare()
                play()
            } else {
                // On Tracks Radio, songs you add play before the station's next, and it picks on after them.
                val radioNext = if (isRadio()) firstRadioPick() else null
                if (radioNext != null) addMediaItem(radioNext, song.toMediaItem()) else addMediaItem(song.toMediaItem())
            }
        }
    }

    override fun removeFromQueue(index: Int) = run {
        if (index !in 0 until mediaItemCount) return@run
        // A pick taken out of Tracks Radio is a no to it, heard as a skip would be.
        val song = queueCache.getOrNull(index)
        if (song != null && index > currentMediaItemIndex && isRadio() && radio.state.value.isPick(song)) radio.removed(song)
        removeMediaItem(index)
    }

    private val radio get() = context.graph.radio

    private fun isRadio() = PlaybackBridge.origin.value?.kind == QueueOrigin.Kind.Radio

    private fun MediaController.firstRadioPick(): Int? =
        (currentMediaItemIndex + 1 until mediaItemCount).firstOrNull { radio.state.value.picks.containsKey(getMediaItemAt(it).mediaId) }
    override fun moveInQueue(from: Int, to: Int) = run {
        if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) moveMediaItem(from, to)
    }
    override fun skipTo(index: Int) = run {
        if (index in 0 until mediaItemCount) {
            seekToDefaultPosition(index)
            play()
        }
    }

    override fun playPause() = run {
        // Waiting on a song counts as playing: the button pauses it.
        if (isPlaying || (playWhenReady && playbackState == Player.STATE_BUFFERING)) pause()
        else {
            if (playbackState == Player.STATE_IDLE) prepare()
            if (playbackState == Player.STATE_ENDED) seekToDefaultPosition(0)
            play()
        }
    }

    override fun next() = run { seekToNext() }
    override fun previous() = run { seekToPrevious() }
    override fun seekTo(positionMs: Long) {
        _position.value = positionMs
        run { seekTo(positionMs) }
    }

    override fun setShuffle(on: Boolean) = run { shuffleModeEnabled = on }

    override fun setRepeat(choice: RepeatChoice) = run {
        repeatMode = when (choice) {
            RepeatChoice.Off -> Player.REPEAT_MODE_OFF
            RepeatChoice.All -> Player.REPEAT_MODE_ALL
            RepeatChoice.One -> Player.REPEAT_MODE_ONE
        }
    }

    override fun setSleepTimer(minutes: Int?) = run {
        sendCustomCommand(TracksCommands.command(TracksCommands.SLEEP), Bundle().apply { putInt(TracksCommands.MINUTES, minutes ?: 0) })
    }

    override fun startRadio() = run {
        sendCustomCommand(TracksCommands.command(TracksCommands.RADIO), Bundle.EMPTY)
    }

    override fun playMoreLikeThis() = run {
        sendCustomCommand(TracksCommands.command(TracksCommands.MORE_LIKE_THIS), Bundle.EMPTY)
    }

    /** Likes or unlikes the song playing now, as the notification's heart does. */
    fun toggleLike() = run {
        sendCustomCommand(TracksCommands.command(TracksCommands.LIKE), Bundle.EMPTY)
    }
}

package com.luke.tracks.discover

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.luke.tracks.AppGraph
import com.luke.tracks.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/** The preview playing, if any, and how far through it is (0 to 1). */
data class PreviewState(val key: String? = null, val isLoading: Boolean = false, val progress: Float = 0f)

/**
 * Plays the catalog's 30 second previews on a player of its own, apart from Tracks' queue:
 * a preview isn't a play, so it never reaches your history or Last.fm. Starting one pauses
 * what was playing.
 */
class PreviewPlayer(private val context: Context, private val graph: AppGraph) {
    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var ticker: Job? = null
    private val main = CoroutineScope(Dispatchers.Main)

    /** Starts the preview, or stops it if it's the one playing. */
    fun toggle(key: String, url: String) {
        if (_state.value.key == key) return stop()
        if (graph.player.state.value.isPlaying) graph.player.playPause()
        val player = player ?: ExoPlayer.Builder(context).build().also { made ->
            made.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            made.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> _state.value = _state.value.copy(isLoading = false)
                        Player.STATE_ENDED -> stop()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) = stop()
            })
            player = made
        }
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        _state.value = PreviewState(key, isLoading = true)
        ticker?.cancel()
        ticker = main.launch {
            while (isActive) {
                val duration = player.duration.takeIf { it > 0 } ?: 30_000L
                _state.value = _state.value.copy(progress = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
                delay(100)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        player?.stop()
        _state.value = PreviewState()
    }

    /** Lets the player go when nothing is previewing, as a screen closes. */
    fun release() {
        stop()
        player?.release()
        player = null
    }
}

private val previews = WeakHashMap<AppGraph, PreviewPlayer>()

val Context.previewPlayer: PreviewPlayer
    get() = graph.let { graph -> synchronized(previews) { previews.getOrPut(graph) { PreviewPlayer(applicationContext, graph) } } }

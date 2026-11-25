package com.luke.tracks.applemusic

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.luke.tracks.net.Logs
import com.luke.tracks.playback.PcmOpener
import com.luke.tracks.playback.PcmSource
import java.io.InterruptedIOException

/**
 * Apple Music songs in Tracks' own queue. MusicKit's audio is protected, so it can't go through
 * ExoPlayer like Spotify's does. Instead ExoPlayer plays a silent stand-in of the song's length
 * ([open]) and MusicKit plays the song alongside it, following ExoPlayer: the same item, play
 * and pause, and seeks. ExoPlayer keeps everything else: the queue, the notification and media
 * session, Android Auto, the listen count and the history.
 *
 * The stand-in holds back its first audio until MusicKit is playing, so the two start together
 * despite MusicKit's slow starts, and a song MusicKit can't start fails like any other. While an
 * Apple Music song is on, MusicKit has the audio focus; when it pauses or resumes on its own (a
 * call, unplugged headphones), ExoPlayer follows it.
 *
 * Unverified on a device: built against MusicKit for Android 1.1.4's classes, never run here.
 */
class AppleMusicPlayback(
    private val player: () -> AppleMusicPlayer?,
    /** Whether [player] would give one, without making it off the main thread. */
    private val canPlay: () -> Boolean = { player() != null },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : PcmOpener {
    private val gate = StartGate(clock)

    private var exo: ExoPlayer? = null
    private var loaded: String? = null
    private var heard = false
    private var wantsPlaying = false
    private var pausedAt = 0L
    private var focusHandedOver = false

    // MARK: The stand-in

    override fun open(uri: Uri): PcmSource {
        val id = AppleMusicUris.catalogId(uri.toString()) ?: throw AppleMusicException(AppleMusicProblem.Unavailable)
        val length = AppleMusicUris.durationMs(uri) ?: throw AppleMusicException(AppleMusicProblem.Unavailable)
        if (!canPlay()) throw AppleMusicException(AppleMusicProblem.SignedOut)
        return StandIn(id, length)
    }

    private inner class StandIn(private val id: String, override val durationMs: Long) : PcmSource {
        override val sampleRate = STAND_IN_RATE
        override val channels = 1
        private var started = false

        override fun readPcm16(maxFrames: Int): ByteArray {
            if (!started) {
                gate.await(id)
                started = true
            }
            return ByteArray(maxFrames.coerceAtLeast(1) * 2)
        }

        override fun seek(positionMs: Long): Long = positionMs.coerceIn(0, durationMs)

        override fun close() = Unit
    }

    // MARK: Following ExoPlayer

    /** Starts following [player]. On the main thread, as ExoPlayer's calls are. */
    fun attach(player: ExoPlayer) {
        exo?.removeListener(following)
        exo = player
        player.addListener(following)
        follow()
    }

    fun detach() {
        exo?.removeListener(following)
        if (loaded != null) player()?.stop()
        unload()
        exo = null
    }

    private val following = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Repeating the same song loads it again from its start.
            if (loaded != null) unload()
            follow()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = follow()

        override fun onPlaybackStateChanged(playbackState: Int) = follow()

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex && loaded != null) {
                player()?.seekTo(newPosition.positionMs)
            }
        }
    }

    private val musicKit = object : AppleMusicPlayer.Listener {
        override fun onPlaying() {
            val id = loaded ?: return
            heard = true
            gate.open(id)
            if (wantsPlaying) return
            // Resumed on its own, after a call: Tracks follows. Just after Tracks paused it, this
            // is MusicKit catching up, and the pause stands.
            if (clock() - pausedAt > CATCH_UP_MS) exo?.play() else player()?.pause()
        }

        override fun onPaused() {
            // Paused on its own, for a call or unplugged headphones: Tracks follows.
            if (loaded != null && heard && wantsPlaying) exo?.pause()
        }

        override fun onError(problem: AppleMusicProblem) {
            val id = loaded ?: return
            Logs.w("Apple Music couldn't play $id: $problem")
            gate.fail(id, problem)
            if (!heard) return
            // Another device took the stream: stop here, as skipping would take it back.
            if (problem == AppleMusicProblem.OtherDevice) {
                exo?.pause()
                return
            }
            // Already under way, past the stand-in's wait: move on rather than play silence.
            exo?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.pause() }
        }
    }

    /** Brings MusicKit in line with what ExoPlayer is doing now. */
    private fun follow() {
        val exo = exo ?: return
        val id = exo.currentMediaItem?.appleMusicId()
        val active = id != null && exo.playbackState != Player.STATE_IDLE && exo.playbackState != Player.STATE_ENDED
        if (!active) {
            if (loaded != null) player()?.pause()
            unload()
            handFocus(toMusicKit = false)
            return
        }
        val musicKit = player() ?: return
        handFocus(toMusicKit = true)
        musicKit.listener = this.musicKit
        val play = exo.playWhenReady
        if (loaded != id) {
            loaded = id
            heard = false
            wantsPlaying = play
            gate.reset(asked = play)
            musicKit.load(id!!, exo.currentPosition, play)
            return
        }
        if (play != wantsPlaying) {
            wantsPlaying = play
            gate.asked(play)
            if (!play) pausedAt = clock()
            if (play) musicKit.play() else musicKit.pause()
        }
    }

    private fun unload() {
        loaded = null
        heard = false
        wantsPlaying = false
        gate.reset(asked = false)
    }

    /** MusicKit asks for the audio focus itself; ExoPlayer, playing silence, lets it. */
    private fun handFocus(toMusicKit: Boolean) {
        val exo = exo ?: return
        if (focusHandedOver == toMusicKit) return
        focusHandedOver = toMusicKit
        exo.setAudioAttributes(exo.audioAttributes, /* handleAudioFocus = */ !toMusicKit)
    }

    private fun MediaItem.appleMusicId(): String? =
        (localConfiguration?.uri ?: requestMetadata.mediaUri)?.takeIf { it.scheme == AppleMusicUris.SCHEME }?.let { AppleMusicUris.catalogId(it.toString()) }
            ?: AppleMusicUris.catalogId(mediaId)

    companion object {
        /** The stand-in is silence; the lowest common rate keeps it cheap. */
        const val STAND_IN_RATE = 8_000

        private const val CATCH_UP_MS = 1_500L
    }
}

/**
 * Holds the stand-in's first audio back until MusicKit plays the same song, or fails it. While
 * ExoPlayer is paused it waits as long as it takes; once asked to play, [START_TIMEOUT_MS].
 */
internal class StartGate(private val clock: () -> Long) {
    private val lock = Object()
    private var sounding: String? = null
    private var failed: Pair<String, AppleMusicProblem>? = null
    private var askedAt: Long? = null

    /** Blocks a loading thread until MusicKit plays [id], or says why it can't. */
    fun await(id: String) = synchronized(lock) {
        while (sounding != id) {
            failed?.takeIf { it.first == id }?.let { throw AppleMusicException(it.second) }
            val asked = askedAt
            if (asked != null && clock() - asked > START_TIMEOUT_MS) throw AppleMusicException(AppleMusicProblem.Offline)
            try {
                lock.wait(WAIT_STEP_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Stopped waiting for Apple Music")
            }
        }
    }

    /** MusicKit is playing [id] out loud. */
    fun open(id: String) = synchronized(lock) {
        sounding = id
        lock.notifyAll()
    }

    fun fail(id: String, problem: AppleMusicProblem) = synchronized(lock) {
        failed = id to problem
        lock.notifyAll()
    }

    /** A new song, or none: nothing is sounding, nothing has failed. */
    fun reset(asked: Boolean) = synchronized(lock) {
        sounding = null
        failed = null
        askedAt = if (asked) clock() else null
        lock.notifyAll()
    }

    /** ExoPlayer was asked to play, or to pause. */
    fun asked(play: Boolean) = synchronized(lock) {
        askedAt = if (play) clock() else null
    }

    companion object {
        /** MusicKit takes about 2 s to start a song; past this it isn't going to. */
        const val START_TIMEOUT_MS = 20_000L

        private const val WAIT_STEP_MS = 250L
    }
}

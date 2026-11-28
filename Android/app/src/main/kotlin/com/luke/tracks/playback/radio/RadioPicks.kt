package com.luke.tracks.playback.radio

import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.RadioSource
import com.luke.tracks.streaming.RelayStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async

/**
 * Tracks Radio's picks as they play: each song the station picks, played from the first place
 * in Play From that can play it where the station is playing ([accept]). A pick nowhere can
 * play makes way for the next, a few times over.
 *
 * One more pick is made ready behind the one asked for, looked up while the song before plays,
 * so the next is there at once even when the song on is skipped straight away. It's let go
 * when what you do changes the station's course ([steered]), so the next pick still follows it.
 */
class RadioPicks(
    private val scope: CoroutineScope,
    private val radio: RadioSource,
    /** Where a pick plays from, among the places [accept] takes; null when none of them can. */
    private val resolve: suspend (PlayableSong, accept: (PlayableSong) -> Boolean) -> PlayableSong?,
    private val tries: Int = TRIES,
) {
    /** A pick as the station made it, and as it plays. */
    data class Pick(val picked: PlayableSong, val song: PlayableSong)

    private var ready: Deferred<Pick?>? = null
    private var readyFor: Any? = null
    private var readyCourse = 0
    private var course = 0

    /** The next song to queue, played from a place [accept] takes; null when there's none. */
    suspend fun next(where: Any, accept: (PlayableSong) -> Boolean): PlayableSong? {
        val waiting = ready
        ready = null
        val pick = if (waiting != null && readyCourse == course && readyFor == where) {
            runCatching { waiting.await() }.getOrNull()?.takeIf { accept(it.song) } ?: pick(accept)
        } else {
            waiting?.let(::letGo)
            pick(accept)
        }
        if (pick != null) prepare(where, accept)
        return pick?.song
    }

    /** What you did turned the station: the pick made ready before it makes way. */
    fun steered() {
        course++
        ready?.let(::letGo)
        ready = null
    }

    /** The station stopped, or another queue took its place. */
    fun stop() = steered()

    private fun prepare(where: Any, accept: (PlayableSong) -> Boolean) {
        readyFor = where
        readyCourse = course
        ready = scope.async { pick(accept) }
    }

    private suspend fun pick(accept: (PlayableSong) -> Boolean): Pick? {
        repeat(tries) {
            val picked = try {
                radio.next()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return null
            val song = try {
                resolve(picked, accept)
            } catch (error: CancellationException) {
                radio.release(picked)
                throw error
            } catch (_: Exception) {
                null
            }
            if (song != null) {
                radio.playsAs(picked, song)
                return Pick(picked, song)
            }
            radio.release(picked)
        }
        return null
    }

    /** A pick made ready and not played: the station forgets it (one still being looked up lets go itself). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun letGo(waiting: Deferred<Pick?>) {
        if (waiting.isCompleted) runCatching { waiting.getCompleted() }.getOrNull()?.let { radio.release(it.song) }
        else waiting.cancel()
    }

    private companion object {
        const val TRIES = 3
    }
}

/** Tracks Radio through the relay. */
object RadioOnRelay {
    /** Why a session ends that playing again won't fix. Idle, closed and replaced aren't among them. */
    private val GONE = setOf("assistant_unavailable", "revoked", "failed", "server_restart")

    /** Whether the relay stopped under the station for good: it carries on on the device's own player. */
    fun stopped(status: RelayStatus): Boolean =
        status.phase == RelayStatus.Phase.Failed || (status.phase == RelayStatus.Phase.Ended && status.endedReason in GONE)
}

package com.luke.tracks.ui.tv.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart

enum class IdleStage { Awake, Idle, Dim }

/** How the player rests: its stage, and which burn-in step the art sits at. */
data class IdleFrame(val stage: IdleStage, val drift: Int = 0) {
    val asleep: Boolean get() = stage != IdleStage.Awake

    /** Where the art sits for this step, so no pixel holds the same light for long. */
    val offset: DpOffset get() = if (drift == 0) DpOffset.Zero else NowPlayingIdle.Drift[(drift - 1) % NowPlayingIdle.Drift.size]
}

/**
 * When Now Playing rests after the remote goes quiet: controls fade and the art takes the
 * middle after 15 seconds, the art moves a little every 90 seconds against burn-in, and the
 * background dims after 5 minutes. Only times; nothing ticks between them.
 */
object NowPlayingIdle {
    const val IdleAfterMs = 15_000L
    const val DriftEveryMs = 90_000L
    const val DimAfterMs = 5 * 60_000L

    val Drift = listOf(DpOffset(12.dp, (-8).dp), DpOffset((-10).dp, 10.dp), DpOffset(8.dp, 12.dp), DpOffset((-12).dp, (-10).dp))

    fun at(sinceInputMs: Long): IdleFrame = when {
        sinceInputMs < IdleAfterMs -> IdleFrame(IdleStage.Awake)
        else -> IdleFrame(
            stage = if (sinceInputMs < DimAfterMs) IdleStage.Idle else IdleStage.Dim,
            drift = ((sinceInputMs - IdleAfterMs) / DriftEveryMs).toInt(),
        )
    }

    /** The time after the last input when the frame next changes. */
    fun nextChangeAfter(sinceInputMs: Long): Long {
        if (sinceInputMs < IdleAfterMs) return IdleAfterMs
        val nextDrift = IdleAfterMs + ((sinceInputMs - IdleAfterMs) / DriftEveryMs + 1) * DriftEveryMs
        return if (sinceInputMs < DimAfterMs) minOf(nextDrift, DimAfterMs) else nextDrift
    }
}

/**
 * The player's resting frame, starting over at each of [touches]. Waits between changes with
 * the frame clock's own time, so tests drive it with a fake clock. Off ([enabled] false) it
 * stays awake: while a panel is open, say.
 */
@Composable
fun rememberIdleFrame(touches: Flow<Unit>, enabled: Boolean): State<IdleFrame> {
    val frame = remember { mutableStateOf(IdleFrame(IdleStage.Awake)) }
    LaunchedEffect(touches, enabled) {
        frame.value = IdleFrame(IdleStage.Awake)
        if (!enabled) return@LaunchedEffect
        touches.onStart { emit(Unit) }.collectLatest {
            var since = 0L
            frame.value = NowPlayingIdle.at(since)
            while (true) {
                val next = NowPlayingIdle.nextChangeAfter(since)
                delay(next - since)
                since = next
                frame.value = NowPlayingIdle.at(since)
            }
        }
    }
    return frame
}

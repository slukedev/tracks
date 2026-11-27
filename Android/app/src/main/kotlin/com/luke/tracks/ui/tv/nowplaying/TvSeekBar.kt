package com.luke.tracks.ui.tv.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.playback.ListenCounter
import com.luke.tracks.ui.nowplaying.clock
import com.luke.tracks.ui.tv.theme.TvMotion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The position to draw: the player's samples (four a second) carried forward frame by frame
 * while it plays, so the bar glides at the display's rate. Only while [ticking] and the app is
 * started; read it in drawing and small leaves, never in a big body.
 */
@Composable
fun rememberSmoothPosition(position: StateFlow<Long>, playing: Boolean, ticking: Boolean): State<Long> {
    val shown = remember { mutableLongStateOf(position.value) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(position, playing, ticking, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!playing || !ticking) {
                position.collect { shown.longValue = it }
                return@repeatOnLifecycle
            }
            var anchor = position.value
            var anchorNs = -1L
            launch {
                position.collect {
                    anchor = it
                    anchorNs = -1L
                }
            }
            while (true) {
                withFrameNanos { now ->
                    if (anchorNs < 0) anchorNs = now
                    val estimate = anchor + (now - anchorNs) / 1_000_000
                    // A sample a little behind the glide waits for it; a seek jumps.
                    if (estimate > shown.longValue || abs(estimate - shown.longValue) > JumpMs) shown.longValue = estimate
                }
            }
        }
    }
    return shown
}

/** How far a step of seeking goes, by how long the key has been held. */
internal fun seekStepMs(heldMs: Long): Long = when {
    heldMs < 600 -> 10_000
    heldMs < 2_000 -> 20_000
    else -> 30_000
}

/**
 * The progress bar. Focused, left and right seek ten seconds a press, longer the longer
 * they're held; the target shows at once and the player seeks when the key comes up, so a
 * run of presses is one seek. Up and down move on.
 */
@Composable
fun TvSeekBar(
    position: StateFlow<Long>,
    lengthMs: Long?,
    playing: Boolean,
    ticking: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val length = ListenCounter.knownLength(lengthMs)
    val live = rememberSmoothPosition(position, playing, ticking)
    var scrub by remember { mutableStateOf<Long?>(null) }
    var focused by remember { mutableStateOf(false) }
    var lastStepAt by remember { mutableLongStateOf(0L) }
    val seek by rememberUpdatedState(onSeek)
    val shown = remember(live) { derivedStateOf { scrub ?: live.value } }
    val second = remember(shown) { derivedStateOf { shown.value / 1000 } }
    val reduced = com.luke.tracks.ui.tv.theme.LocalReducedMotion.current
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(if (reduced) 0 else TvMotion.FocusInMs, easing = TvMotion.EaseOut), label = "lift")
    val colors = MaterialTheme.colorScheme

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) scrub?.let { target -> scrub = null; seek(target) }
                }
                .onKeyEvent { event ->
                    val forward = when (event.key) {
                        Key.DirectionRight -> true
                        Key.DirectionLeft -> false
                        else -> return@onKeyEvent false
                    }
                    val total = length ?: return@onKeyEvent false
                    val native = event.nativeKeyEvent
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            // Held keys repeat fast; a step every 150ms keeps it readable.
                            if (native.repeatCount > 0 && native.eventTime - lastStepAt < StepEveryMs) return@onKeyEvent true
                            lastStepAt = native.eventTime
                            val step = seekStepMs(native.eventTime - native.downTime)
                            val from = scrub ?: live.value
                            scrub = (if (forward) from + step else from - step).coerceIn(0, (total - EndRoomMs).coerceAtLeast(0))
                            true
                        }
                        KeyEventType.KeyUp -> {
                            scrub?.let(seek)
                            scrub = null
                            true
                        }
                        else -> false
                    }
                }
                .semantics {
                    contentDescription = "Seek"
                    if (length != null) {
                        progressBarRangeInfo = ProgressBarRangeInfo((second.value * 1000f / length).coerceIn(0f, 1f), 0f..1f)
                        setProgress { fraction -> seek((fraction * length).toLong()); true }
                    }
                }
                .focusable(enabled = length != null)
                .drawBehind {
                    val total = length ?: 0L
                    val fraction = if (total > 0) (shown.value.toFloat() / total).coerceIn(0f, 1f) else 0f
                    val h = (6 + 4 * lift).dp.toPx()
                    val y = size.height / 2 - h / 2
                    val r = CornerRadius(h / 2)
                    drawRoundRect(colors.onSurface.copy(alpha = 0.18f + 0.06f * lift), Offset(0f, y), Size(size.width, h), r)
                    val played = size.width * fraction
                    val fill = androidx.compose.ui.graphics.lerp(colors.onSurface.copy(alpha = 0.85f), colors.primary, lift)
                    if (played > 0) drawRoundRect(fill, Offset(0f, y), Size(played.coerceAtLeast(h), h), r)
                    if (lift > 0f) drawCircle(fill, radius = 9.dp.toPx() * lift, center = Offset(played.coerceIn(0f, size.width), size.height / 2))
                },
        )
        Times(second, length, Modifier.padding(top = 6.dp))
    }
}

/** Played and left, changing once a second however often the bar redraws. */
@Composable
private fun Times(second: State<Long>, length: Long?, modifier: Modifier) {
    val seconds by second
    val style = MaterialTheme.typography.labelLarge
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth()) {
        Text(clock(seconds * 1000f), style = style, color = color)
        Spacer(Modifier.weight(1f).width(8.dp))
        Text(if (length == null) "--:--" else "-" + clock((length - seconds * 1000).coerceAtLeast(0).toFloat()), style = style, color = color)
    }
}

private const val JumpMs = 1_500L
private const val StepEveryMs = 150L
/** Seeking stops short of the very end, so the song doesn't end under the press. */
private const val EndRoomMs = 1_000L

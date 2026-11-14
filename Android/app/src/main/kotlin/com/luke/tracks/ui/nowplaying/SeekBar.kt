package com.luke.tracks.ui.nowplaying

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * The seek bar: Material 3 Expressive's wavy progress as the track of a real slider. It
 * waves while the song plays and lies flat when paused or held, so nothing moves at rest;
 * dragging ticks under the finger every ten seconds.
 */
@Composable
fun WavySeekBar(
    position: StateFlow<Long>,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live by position.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    var lastTick by remember { mutableFloatStateOf(-1f) }
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val held = dragged || pressed || scrub != null
    val duration = durationMs.coerceAtLeast(1)
    val fraction = (scrub ?: (live.toFloat() / duration)).coerceIn(0f, 1f)
    val motion = MaterialTheme.motionScheme
    val amplitude by animateFloatAsState(if (isPlaying && !held) 1f else 0f, motion.slowEffectsSpec(), label = "wave")
    val thumbHeight by animateDpAsState(if (held) 32.dp else 24.dp, motion.fastSpatialSpec(), label = "thumb")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val stroke = with(LocalDensity.current) { Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round) }

    Column(modifier) {
        val slider = remember { SliderState(0f) }
        SideEffect { if (scrub == null) slider.value = fraction }
        Slider(
            state = slider,
            onValueChange = { value: Float ->
                scrub = value
                slider.value = value
                val tick = (value * duration / 10_000f).toInt().toFloat()
                if (tick != lastTick) {
                    if (lastTick >= 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    lastTick = tick
                }
            },
            onValueChangeFinished = {
                scrub?.let { onSeek((it * duration).toLong()) }
                scrub = null
                lastTick = -1f
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Seek" },
            thumb = {
                Box(
                    Modifier
                        .size(width = 6.dp, height = thumbHeight)
                        .background(primary, RoundedCornerShape(50)),
                )
            },
            track = {
                if (amplitude > 0f) {
                    LinearWavyProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(14.dp),
                        color = primary,
                        trackColor = track,
                        stroke = stroke,
                        trackStroke = stroke,
                        amplitude = { amplitude },
                        wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
                        waveSpeed = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
                    )
                } else {
                    FlatTrack(fraction, primary, track)
                }
            },
            colors = SliderDefaults.colors(thumbColor = primary),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(clock(fraction * duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                "-" + clock((1 - fraction) * duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The track at rest: the same two tones, straight, with a gap at the thumb. */
@Composable
private fun FlatTrack(fraction: Float, active: Color, inactive: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .drawBehind {
                val h = 4.dp.toPx()
                val y = size.height / 2 - h / 2
                val gap = 6.dp.toPx()
                val split = size.width * fraction
                val r = CornerRadius(h / 2)
                if (split - gap > 0) drawRoundRect(active, Offset(0f, y), Size(split - gap, h), r)
                if (size.width - split - gap > 0) drawRoundRect(inactive, Offset(split + gap, y), Size(size.width - split - gap, h), r)
            },
    )
}

fun clock(ms: Float): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

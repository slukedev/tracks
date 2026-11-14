package com.luke.tracks.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay

/**
 * One of Material's expressive shapes that morphs into the next whenever [shape] changes,
 * on a soft spring, while it turns slowly.
 */
@Composable
fun MorphingShape(shape: RoundedPolygon, color: Color, modifier: Modifier = Modifier, turnMillis: Int = 24_000) {
    var from by remember { mutableStateOf(shape) }
    var to by remember { mutableStateOf(shape) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(shape) {
        if (shape !== to) {
            from = to
            to = shape
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessVeryLow))
        }
    }
    val morph = remember(from, to) { Morph(from, to) }
    val turn by rememberInfiniteTransition(label = "turn").animateFloat(
        0f, 360f, infiniteRepeatable(tween(turnMillis, easing = LinearEasing), RepeatMode.Restart), label = "angle",
    )
    Box(
        modifier.drawWithCache {
            val path = Path()
            val matrix = Matrix()
            onDrawBehind {
                path.reset()
                morph.toPath(progress.value, path)
                matrix.reset()
                matrix.scale(size.width, size.height)
                path.transform(matrix)
                rotate(turn) { drawPath(path, color) }
            }
        },
    )
}

/**
 * Enters after [index] beats: rises and fades in on a spring, so a page's parts arrive one
 * after another rather than all at once.
 */
fun Modifier.staggered(index: Int, beat: Long = 70): Modifier = composed {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(140 + index * beat)
        shown.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer {
        alpha = shown.value.coerceIn(0f, 1f)
        translationY = (1f - shown.value) * 40.dp.toPx()
    }
}

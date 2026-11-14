package com.luke.tracks.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tracks' cassette, drawn in the theme's colors, with reels that turn while [playing].
 */
@Composable
fun Cassette(modifier: Modifier = Modifier, playing: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val spin = if (playing) {
        val transition = rememberInfiniteTransition(label = "reels")
        val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "angle")
        angle
    } else 0f
    val shellTop = scheme.surfaceContainerLowest
    val shellBottom = scheme.surfaceContainerHigh
    val window = scheme.onSurface
    val reel = scheme.primary
    val hub = scheme.surfaceContainerLowest
    val guard = scheme.surfaceContainerHighest
    val label = scheme.tertiary
    Canvas(modifier.aspectRatio(1.56f)) {
        val w = size.width
        val h = size.height
        val r = w * 0.07f
        drawRoundRect(Brush.linearGradient(listOf(shellTop, shellBottom), Offset.Zero, Offset(w, h)), cornerRadius = CornerRadius(r))
        // The label stripe across the top.
        drawRoundRect(label, Offset(w * 0.08f, h * 0.09f), Size(w * 0.84f, h * 0.1f), CornerRadius(h * 0.05f))
        // The window.
        val wx = w * 0.17f
        val wy = h * 0.27f
        val ww = w * 0.66f
        val wh = h * 0.36f
        drawRoundRect(window, Offset(wx, wy), Size(ww, wh), CornerRadius(wh * 0.5f))
        val left = Offset(wx + wh * 0.5f + w * 0.02f, wy + wh / 2)
        val right = Offset(wx + ww - wh * 0.5f - w * 0.02f, wy + wh / 2)
        val reelR = wh * 0.36f
        // Tape wound on the reels.
        drawCircle(lerp(window, reel, 0.35f), reelR * 1.18f, left)
        drawCircle(lerp(window, reel, 0.35f), reelR * 0.92f, right)
        reelAt(left, reelR, spin, reel, hub)
        reelAt(right, reelR, spin + 30f, reel, hub)
        // The head guard at the bottom.
        val path = Path().apply {
            moveTo(w * 0.27f, h)
            lineTo(w * 0.33f, h * 0.76f)
            lineTo(w * 0.67f, h * 0.76f)
            lineTo(w * 0.73f, h)
            close()
        }
        drawPath(path, guard)
        drawCircle(window.copy(alpha = 0.5f), h * 0.03f, Offset(w * 0.42f, h * 0.88f))
        drawCircle(window.copy(alpha = 0.5f), h * 0.03f, Offset(w * 0.58f, h * 0.88f))
    }
}

private fun DrawScope.reelAt(center: Offset, radius: Float, angle: Float, reel: Color, hub: Color) {
    drawCircle(reel, radius, center)
    drawCircle(hub, radius * 0.52f, center)
    rotate(angle, center) {
        repeat(6) { i ->
            val a = Math.toRadians(i * 60.0)
            val inner = radius * 0.2f
            val outer = radius * 0.5f
            drawLine(
                reel,
                Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
                Offset(center.x + outer * cos(a).toFloat(), center.y + outer * sin(a).toFloat()),
                strokeWidth = radius * 0.16f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
    drawCircle(reel, radius * 0.16f, center, style = Stroke(radius * 0.06f))
}

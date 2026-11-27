package com.luke.tracks.ui.tv.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Motion for TV: focus answers within a frame and settles in 150ms with a strong ease-out,
 * leaves faster than it arrives, and nothing is longer than 300ms. Everything runs on
 * Compose's frame clock, so it follows the display's refresh rate.
 */
object TvMotion {
    const val CardFocusScale = 1.06f
    const val RowFocusScale = 1.04f
    const val ButtonFocusScale = 1.06f
    const val PressScale = 0.96f
    val FocusBorderWidth = 3.dp

    const val FocusInMs = 150
    const val FocusOutMs = 120
    const val PressMs = 100
    const val ScreenMs = 220
    const val NowPlayingMs = 300
    const val PanelMs = 250

    val EaseOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut: Easing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    fun <T> enter(durationMs: Int = ScreenMs): FiniteAnimationSpec<T> = tween(durationMs, easing = EaseOut)
    fun <T> exit(durationMs: Int = FocusOutMs): FiniteAnimationSpec<T> = tween(durationMs, easing = EaseOut)
}

/** True when the person turned animations off (Settings, Accessibility, Remove animations). */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

fun reducedMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

/**
 * Grows the element while it has focus and dips it while pressed, retargeting from wherever
 * it is so quick D-pad runs never queue up. Press dips within a frame of the key going down.
 * Off under reduced motion, where the border alone shows focus.
 */
@Composable
fun Modifier.tvFocusScale(interactionSource: InteractionSource, focusedScale: Float): Modifier {
    if (LocalReducedMotion.current) return this
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource, focusedScale) {
        var focused = false
        var pressed = false
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is FocusInteraction.Focus -> focused = true
                is FocusInteraction.Unfocus -> focused = false
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                else -> return@collect
            }
            val (target, ms) = when {
                pressed -> focusedScale * TvMotion.PressScale to TvMotion.PressMs
                focused -> focusedScale to TvMotion.FocusInMs
                else -> 1f to TvMotion.FocusOutMs
            }
            // A newer animateTo interrupts the running one and starts from the current value.
            launch { scale.animateTo(target, tween(ms, easing = TvMotion.EaseOut)) }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

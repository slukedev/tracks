package com.luke.tracks.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luke.tracks.data.PaletteChoice
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/** Tracks' own color, from the icon. */
val TracksBrand = Color(0xFFE83A5F)

val TracksShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Whether the app is drawing dark, for the few places that pick their own colors. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Material 3 Expressive with the expressive motion scheme.
 *
 * With [dynamic] on and no [seed], colors are the system's own (Material You, from the
 * wallpaper), exactly as Android's apps draw them. With a [seed] (a color picked in
 * Settings, the cover playing now, a mix's hue) the palette is built from it in [style],
 * through the same tonal algorithm Android uses. Seeded palettes follow Android's contrast
 * setting; system palettes already do. [pureBlack] makes dark backgrounds true black.
 * [animate] cross-fades every color when the palette changes.
 */
@Composable
fun TracksTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    seed: Color? = null,
    style: PaletteChoice = PaletteChoice.Vibrant,
    pureBlack: Boolean = false,
    animate: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val target: ColorScheme = when {
        seed != null -> seededScheme(seed, dark, style, pureBlack)
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val system = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (dark && pureBlack) system.blackened() else system
        }
        else -> seededScheme(TracksBrand, dark, style, pureBlack)
    }
    val scheme = if (animate) target.animated() else target
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = TracksShapes,
            typography = TracksTypography,
            content = content,
        )
    }
}

/** A full Material scheme from one color, the way Android builds one from a wallpaper. */
@Composable
fun seededScheme(
    seed: Color,
    dark: Boolean,
    style: PaletteChoice = PaletteChoice.Vibrant,
    pureBlack: Boolean = false,
): ColorScheme {
    val context = LocalContext.current
    val contrast = remember(context) { systemContrast(context) }
    return rememberDynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        isAmoled = dark && pureBlack,
        style = style.toPaletteStyle(),
        contrastLevel = contrast,
    )
}

fun PaletteChoice.toPaletteStyle(): PaletteStyle = when (this) {
    PaletteChoice.TonalSpot -> PaletteStyle.TonalSpot
    PaletteChoice.Vibrant -> PaletteStyle.Vibrant
    PaletteChoice.Fidelity -> PaletteStyle.Fidelity
    PaletteChoice.Neutral -> PaletteStyle.Neutral
}

/** Android 14's contrast setting (Settings, Accessibility, Color and motion), from -1 to 1. */
private fun systemContrast(context: Context): Double =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        runCatching { context.getSystemService(UiModeManager::class.java).contrast.toDouble() }.getOrDefault(0.0)
    } else 0.0

/** The system's dark scheme with true black backgrounds, for OLED screens. */
private fun ColorScheme.blackened(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = surfaceContainerLowest.takeIf { it != Color.Black } ?: Color(0xFF0A0A0A),
)

private val colorFade: AnimationSpec<Color> = tween(450)

/** Every role cross-faded to its new value, so changing colors in Settings glides. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable fun Color.fade(): Color {
        val value by animateColorAsState(this, colorFade, label = "scheme")
        return value
    }
    return copy(
        primary = primary.fade(), onPrimary = onPrimary.fade(),
        primaryContainer = primaryContainer.fade(), onPrimaryContainer = onPrimaryContainer.fade(),
        inversePrimary = inversePrimary.fade(),
        secondary = secondary.fade(), onSecondary = onSecondary.fade(),
        secondaryContainer = secondaryContainer.fade(), onSecondaryContainer = onSecondaryContainer.fade(),
        tertiary = tertiary.fade(), onTertiary = onTertiary.fade(),
        tertiaryContainer = tertiaryContainer.fade(), onTertiaryContainer = onTertiaryContainer.fade(),
        background = background.fade(), onBackground = onBackground.fade(),
        surface = surface.fade(), onSurface = onSurface.fade(),
        surfaceVariant = surfaceVariant.fade(), onSurfaceVariant = onSurfaceVariant.fade(),
        surfaceTint = surfaceTint.fade(),
        inverseSurface = inverseSurface.fade(), inverseOnSurface = inverseOnSurface.fade(),
        outline = outline.fade(), outlineVariant = outlineVariant.fade(),
        surfaceBright = surfaceBright.fade(), surfaceDim = surfaceDim.fade(),
        surfaceContainer = surfaceContainer.fade(),
        surfaceContainerHigh = surfaceContainerHigh.fade(),
        surfaceContainerHighest = surfaceContainerHighest.fade(),
        surfaceContainerLow = surfaceContainerLow.fade(),
        surfaceContainerLowest = surfaceContainerLowest.fade(),
    )
}

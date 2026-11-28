package com.luke.tracks.ui.tv.theme

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.luke.tracks.data.PaletteChoice
import com.luke.tracks.ui.theme.TracksShapes
import com.luke.tracks.ui.theme.TracksTheme
import androidx.compose.material3.ColorScheme as M3ColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.tv.material3.ColorScheme as TvColorScheme
import androidx.tv.material3.MaterialTheme as TvTheme
import androidx.tv.material3.Shapes as TvShapes

/**
 * The app's theme for TV. The Material 3 scheme is built exactly as [TracksTheme] builds it
 * (system colors, a seed in a palette style, pure black), then handed to tv-material role by
 * role, so focusable TV components and the shared Material drawing (charts, art) agree.
 * Nest it with a [seed] for cover-tinted areas, as the phone does.
 */
@Composable
fun TvTracksTheme(
    dark: Boolean,
    dynamic: Boolean = true,
    seed: Color? = null,
    style: PaletteChoice = PaletteChoice.Vibrant,
    pureBlack: Boolean = false,
    animate: Boolean = false,
    content: @Composable () -> Unit,
) {
    TracksTheme(dark = dark, dynamic = dynamic, seed = seed, style = style, pureBlack = pureBlack, animate = animate) {
        val scheme = M3Theme.colorScheme
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = TracksShapes,
            typography = TvM3Typography,
        ) {
            TvTheme(colorScheme = scheme.toTvColorScheme(), shapes = TvTracksShapes, typography = TvTypography, content = content)
        }
    }
}

private val TvTracksShapes = TvShapes(
    extraSmall = TracksShapes.extraSmall,
    small = TracksShapes.small,
    medium = TracksShapes.medium,
    large = TracksShapes.large,
    extraLarge = TracksShapes.extraLarge,
)

/** Every role carried over; tv-material's border roles are Material 3's outlines. */
fun M3ColorScheme.toTvColorScheme(): TvColorScheme = TvColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = surfaceTint,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    border = outline,
    borderVariant = outlineVariant,
    scrim = scrim,
)

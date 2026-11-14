package com.luke.tracks.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/** Tracks' own color, from the icon: used when wallpaper colors are off or unavailable. */
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
 * Material 3 Expressive with the expressive motion scheme. Colors follow the wallpaper
 * (Material You) when [dynamic] is on, or come from [seed] (Tracks' red, or the cover playing
 * now) through the same tonal algorithm.
 */
@Composable
fun TracksTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    seed: Color? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        seed != null -> seededScheme(seed, dark)
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> seededScheme(TracksBrand, dark)
    }
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
fun seededScheme(seed: Color, dark: Boolean): ColorScheme =
    rememberDynamicColorScheme(seedColor = seed, isDark = dark, style = PaletteStyle.Vibrant)

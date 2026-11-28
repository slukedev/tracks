package com.luke.tracks.ui.tv.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.tv.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.ThemeChoice
import com.luke.tracks.graph
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import com.luke.tracks.ui.tv.theme.reducedMotion
import kotlin.math.abs
import kotlin.math.min

/** The TV app: the theme from Settings, the first-run gate, then the shell. */
@Composable
fun TvEntry(navigator: TvNavigator) {
    val context = LocalContext.current
    val settings by context.graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val dark = when (settings.theme) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }
    val reduced = remember(context) { reducedMotion(context) }
    TvTracksTheme(
        dark = dark,
        seed = if (settings.colors == ColorChoice.Custom) Color(settings.seedColor) else null,
        style = settings.paletteStyle,
        pureBlack = settings.pureBlack,
        animate = !reduced,
    ) {
        CompositionLocalProvider(LocalReducedMotion provides reduced) {
            TvFrame { TvScreens.gate { TvShell(navigator) } }
        }
    }
}

/**
 * The TV layout at its 960 by 540dp design size on any window: a phone's landscape screen or
 * a large display draws it at the density that fits, keeping the font scale. Clear of a
 * camera cutout; on a TV there's none and nothing changes.
 */
@Composable
internal fun TvFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val scaled = tvDesignDensity(constraints.maxWidth, constraints.maxHeight, density.density)
            if (scaled == null) {
                content()
            } else {
                CompositionLocalProvider(LocalDensity provides Density(scaled, density.fontScale), content = content)
            }
        }
    }
}

private const val DesignWidthDp = 960f
private const val DesignHeightDp = 540f

/**
 * The density that fits the 960 by 540dp design into a window [widthPx] by [heightPx] across,
 * scaling by whichever side is shorter for it, or null when it's within 2% already.
 */
internal fun tvDesignDensity(widthPx: Int, heightPx: Int, density: Float): Float? {
    if (widthPx <= 0 || heightPx <= 0 || widthPx == Constraints.Infinity || heightPx == Constraints.Infinity) return null
    val scale = min(widthPx / density / DesignWidthDp, heightPx / density / DesignHeightDp)
    return if (abs(scale - 1f) < 0.02f) null else density * scale
}

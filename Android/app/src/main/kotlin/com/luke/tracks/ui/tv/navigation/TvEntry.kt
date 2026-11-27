package com.luke.tracks.ui.tv.navigation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.ThemeChoice
import com.luke.tracks.graph
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import com.luke.tracks.ui.tv.theme.reducedMotion

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
            TvScreens.gate { TvShell(navigator) }
        }
    }
}

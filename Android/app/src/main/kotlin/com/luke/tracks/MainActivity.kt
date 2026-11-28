package com.luke.tracks

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.ThemeChoice
import com.luke.tracks.system.LocalFormFactor
import com.luke.tracks.ui.navigation.AppNavigation
import com.luke.tracks.ui.navigation.AppShell
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.Tab
import com.luke.tracks.ui.theme.TracksTheme
import com.luke.tracks.ui.tv.navigation.TvEntry
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.theme.TvMotion
import com.luke.tracks.ui.tv.theme.reducedMotion

class MainActivity : ComponentActivity() {
    internal val navigator = Navigator()
    internal val tvNavigator = TvNavigator()

    /** TV hardware, which decides what can work here (a browser, the share sheet). */
    private val tvHardware get() = graph.formFactor.tv

    /** The TV layout, which TV Mode can turn on for a phone or off for a TV. */
    private val tvLayout get() = graph.tvMode.layout.value

    /** Where links and searches from outside go: the TV's navigation in the TV layout, else the phone's. */
    internal val navigation: AppNavigation get() = if (tvLayout) tvNavigator else navigator

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        fitWindow(tvLayout)
        // Recreated (rotation, theme): the intent was handled the first time.
        if (savedInstanceState == null) handle(intent)
        setContent {
            val tv by graph.tvMode.layout.collectAsStateWithLifecycle()
            LaunchedEffect(tv) { fitWindow(tv) }
            val reduced = remember { reducedMotion(this) }
            CompositionLocalProvider(LocalFormFactor provides graph.formFactor) {
                // The player is a service, so playback carries on while the shells swap.
                Crossfade(tv, animationSpec = tween(if (reduced) 0 else TvMotion.ScreenMs, easing = TvMotion.EaseOut), label = "shell") { showTv ->
                    if (showTv) TvEntry(tvNavigator) else PhoneEntry()
                }
            }
        }
    }

    /** A phone in the TV layout lies on its side and fills its screen, as a TV would. */
    private fun fitWindow(tv: Boolean) {
        if (tvHardware) return
        val orientation = if (tv) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (requestedOrientation != orientation) requestedOrientation = orientation
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (tv) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Composable
    private fun PhoneEntry() {
        val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
        val dark = when (settings.theme) {
            ThemeChoice.System -> isSystemInDarkTheme()
            ThemeChoice.Light -> false
            ThemeChoice.Dark -> true
        }
        TracksTheme(
            dark = dark,
            seed = if (settings.colors == ColorChoice.Custom) androidx.compose.ui.graphics.Color(settings.seedColor) else null,
            style = settings.paletteStyle,
            pureBlack = settings.pureBlack,
            animate = true,
        ) {
            com.luke.tracks.ui.onboarding.OnboardingGate { AppShell(remember { navigator }) }
            com.luke.tracks.ui.library.servers.PairingLinkPrompt()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (val request = LaunchRequest.of(intent)) {
            null -> Unit
            is LaunchRequest.Search -> navigation.openSearch(request.query)
            is LaunchRequest.PlayFromSearch -> {
                graph.player.playFromSearch(request.query)
                navigation.openNowPlaying()
            }
            is LaunchRequest.Pairing -> if (!tvLayout && com.luke.tracks.library.trackside.PairingLinks.offer(request.link)) {
                navigator.open(com.luke.tracks.ui.navigation.SettingsPageRoute("servers"))
            }
            is LaunchRequest.Link -> open(request.uri)
            // A TV is linked from its own screen, never approves another.
            is LaunchRequest.LinkApproval -> if (!tvHardware) navigation.openLinkApproval(request.serverId, request.userCode)
        }
    }

    /**
     * Deep links and shortcuts: tracks://radio, search, library, summary, history, charts
     * (History in Charts mode), nowplaying, mix/<id>, sources and settings/<page>. The TV layout
     * takes radio, search and nowplaying.
     */
    private fun open(data: android.net.Uri) {
        when (data.host) {
            "radio" -> graph.player.startRadio().also { navigation.openNowPlaying() }
            "search" -> navigation.openSearch("")
            "nowplaying" -> navigation.openNowPlaying()
        }
        if (tvLayout) return
        if (!tvHardware) com.luke.tracks.system.Shortcuts.reportUsed(this, data)
        when (data.host) {
            "library" -> navigator.select(Tab.Library)
            "summary" -> navigator.select(Tab.Summary)
            "charts" -> navigator.open(com.luke.tracks.ui.navigation.ChartsRoute)
            "history" -> navigator.open(com.luke.tracks.ui.navigation.HistoryRoute)
            "sources" -> navigator.open(com.luke.tracks.ui.navigation.SettingsPageRoute("sources"))
            "mix" -> data.lastPathSegment?.let { navigator.open(com.luke.tracks.ui.navigation.MixRoute(it)) }
            "settings" -> navigator.open(
                data.lastPathSegment?.let { com.luke.tracks.ui.navigation.SettingsPageRoute(it) }
                    ?: com.luke.tracks.ui.navigation.SettingsRoute,
            )
        }
    }
}

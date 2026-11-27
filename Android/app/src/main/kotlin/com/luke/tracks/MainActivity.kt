package com.luke.tracks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

class MainActivity : ComponentActivity() {
    internal val navigator = Navigator()
    internal val tvNavigator = TvNavigator()

    private val tv get() = graph.formFactor.tv

    /** Where links and searches from outside go: the TV's navigation on a TV, else the phone's. */
    internal val navigation: AppNavigation get() = if (tv) tvNavigator else navigator

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Recreated (rotation, theme): the intent was handled the first time.
        if (savedInstanceState == null) handle(intent)
        setContent {
            CompositionLocalProvider(LocalFormFactor provides graph.formFactor) {
                if (tv) TvEntry(remember { tvNavigator }) else PhoneEntry()
            }
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
            is LaunchRequest.Pairing -> if (!tv && com.luke.tracks.library.trackside.PairingLinks.offer(request.link)) {
                navigator.open(com.luke.tracks.ui.navigation.SettingsPageRoute("servers"))
            }
            is LaunchRequest.Link -> open(request.uri)
        }
    }

    /**
     * Deep links and shortcuts: tracks://radio, search, library, summary, history, charts
     * (History in Charts mode), nowplaying, mix/<id>, sources and settings/<page>. A TV takes
     * radio, search and nowplaying.
     */
    private fun open(data: android.net.Uri) {
        when (data.host) {
            "radio" -> graph.player.startRadio().also { navigation.openNowPlaying() }
            "search" -> navigation.openSearch("")
            "nowplaying" -> navigation.openNowPlaying()
        }
        if (tv) return
        com.luke.tracks.system.Shortcuts.reportUsed(this, data)
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

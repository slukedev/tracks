package com.luke.tracks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.ThemeChoice
import com.luke.tracks.ui.navigation.AppShell
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.navigation.Tab
import com.luke.tracks.ui.theme.TracksTheme

class MainActivity : ComponentActivity() {
    private val navigator = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val dark = when (settings.theme) {
                ThemeChoice.System -> isSystemInDarkTheme()
                ThemeChoice.Light -> false
                ThemeChoice.Dark -> true
            }
            TracksTheme(dark = dark, dynamic = settings.colors != ColorChoice.Tracks) {
                AppShell(remember { navigator })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Deep links and shortcuts: tracks://radio, tracks://search, tracks://summary. */
    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "tracks") return
        when (data.host) {
            "radio" -> graph.player.startRadio().also { navigator.nowPlayingOpen = true }
            "search" -> navigator.open(SearchRoute)
            "summary" -> navigator.select(Tab.Summary)
            "charts" -> navigator.select(Tab.Charts)
            "history" -> navigator.select(Tab.History)
            "nowplaying" -> navigator.nowPlayingOpen = true
            "mix" -> data.lastPathSegment?.let { navigator.open(com.luke.tracks.ui.navigation.MixRoute(it)) }
        }
    }
}

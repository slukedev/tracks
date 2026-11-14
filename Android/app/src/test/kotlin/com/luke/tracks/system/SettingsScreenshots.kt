package com.luke.tracks.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.engine.Engine
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.SettingsRoute
import com.luke.tracks.ui.settings.SettingsPageScreen
import com.luke.tracks.ui.settings.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.Test
import uniffi.tracks_engine.SourceFilter

/** Settings and its pages, light and dark. */
class SettingsScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun page(name: String, content: @Composable () -> Unit) = snap(name) {
        val navigator = Navigator().apply { open(SettingsRoute) }
        CompositionLocalProvider(LocalNavigator provides navigator) { content() }
    }

    @Test fun settings() = page("settings") { SettingsScreen() }

    @Test fun capture() = page("settings-capture") { SettingsPageScreen("capture") }

    @Test fun apps() = page("settings-apps") { SettingsPageScreen("apps") }

    @Test fun lastfm() = page("settings-lastfm") { SettingsPageScreen("lastfm") }

    @Test fun appearance() = page("settings-appearance") { SettingsPageScreen("appearance") }

    @Test fun radio() = page("settings-radio") { SettingsPageScreen("radio") }

    @Test fun hidden() {
        withSampleHistory()
        runBlocking {
            val songs = graph.engine.read { songChart(Engine.allTime, null, SourceFilter.ALL, 4u) }.rows
            graph.settings.update { s -> s.copy(hiddenSongs = songs.map { it.song.identity }.toSet()) }
        }
        page("settings-hidden") { SettingsPageScreen("hidden") }
    }

    @Test fun about() = page("settings-about") { SettingsPageScreen("about") }

    @Test fun privacy() = page("settings-privacy") { SettingsPageScreen("privacy") }

    @Test fun licenses() = page("settings-licenses") { SettingsPageScreen("licenses") }
}

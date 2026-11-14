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
import org.robolectric.annotation.Config
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.PaletteChoice
import com.luke.tracks.ui.settings.PresetColors
import com.luke.tracks.ui.summary.SummaryScreen
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.TracksTheme
import uniffi.tracks_engine.SourceFilter

/** Settings and its pages, light and dark. */
class SettingsScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun page(name: String, content: @Composable () -> Unit) = snap(name) {
        val navigator = Navigator().apply { open(SettingsRoute) }
        CompositionLocalProvider(LocalNavigator provides navigator) { content() }
    }

    @Test fun settings() = page("settings") { SettingsScreen() }

    @Test fun lastfm() = page("settings-lastfm") { SettingsPageScreen("lastfm") }

    @Test fun appearance() {
        runBlocking { graph.settings.update { it.copy(colors = ColorChoice.Wallpaper) } }
        page("settings-appearance") { SettingsPageScreen("appearance") }
    }

    /** A picked color, in the palette it makes, the way the app shows it once chosen. */
    @Test fun appearanceCustom() {
        val blue = PresetColors.first { it.name == "Blue" }.argb
        runBlocking {
            graph.settings.update {
                it.copy(colors = ColorChoice.Custom, seedColor = blue, paletteStyle = PaletteChoice.Fidelity)
            }
        }
        page("settings-appearance-custom") {
            TracksTheme(dark = LocalDarkTheme.current, seed = Color(blue), style = PaletteChoice.Fidelity) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { SettingsPageScreen("appearance") }
            }
        }
        runBlocking { graph.settings.update { it.copy(colors = ColorChoice.Wallpaper) } }
    }

    /** Summary in a few picked colors and styles, to see a palette across a whole screen. */
    private fun summaryIn(name: String, style: PaletteChoice) {
        withSampleHistory()
        page("colors-summary-${name.lowercase()}") {
            TracksTheme(dark = LocalDarkTheme.current, seed = Color(PresetColors.first { it.name == name }.argb), style = style) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { SummaryScreen() }
            }
        }
    }

    @Test fun summaryTeal() = summaryIn("Teal", PaletteChoice.TonalSpot)

    @Test fun summaryViolet() = summaryIn("Violet", PaletteChoice.Fidelity)

    @Test fun summaryGold() = summaryIn("Gold", PaletteChoice.Vibrant)

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

    @Test @Config(qualifiers = "w412dp-h2600dp-xxhdpi") fun settingsFull() = page("full-settings") { SettingsScreen() }
    @Test @Config(qualifiers = "w412dp-h2200dp-xxhdpi") fun lastfmFull() = page("full-settings-lastfm") { SettingsPageScreen("lastfm") }
    @Test @Config(qualifiers = "w412dp-h2200dp-xxhdpi") fun radioFull() = page("full-settings-radio") { SettingsPageScreen("radio") }
    @Test @Config(qualifiers = "w412dp-h2200dp-xxhdpi") fun aboutFull() = page("full-settings-about") { SettingsPageScreen("about") }
    @Test @Config(qualifiers = "w412dp-h2400dp-xxhdpi") fun privacyFull() = page("full-settings-privacy") { SettingsPageScreen("privacy") }
    @Test @Config(qualifiers = "w412dp-h2800dp-xxhdpi") fun licensesFull() = page("full-settings-licenses") { SettingsPageScreen("licenses") }

    @Test @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    fun appearanceFull() {
        val blue = PresetColors.first { it.name == "Blue" }.argb
        runBlocking { graph.settings.update { it.copy(colors = ColorChoice.Custom, seedColor = blue, paletteStyle = PaletteChoice.TonalSpot) } }
        page("full-settings-appearance") {
            TracksTheme(dark = LocalDarkTheme.current, seed = Color(blue), style = PaletteChoice.TonalSpot) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { SettingsPageScreen("appearance") }
            }
        }
        runBlocking { graph.settings.update { it.copy(colors = ColorChoice.Wallpaper) } }
    }
}

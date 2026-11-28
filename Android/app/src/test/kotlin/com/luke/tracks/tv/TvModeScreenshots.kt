package com.luke.tracks.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.requestFocus
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.tv.components.LocalTvFocusStore
import com.luke.tracks.ui.tv.components.TvFocusStore
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvFrame
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.navigation.TvShell
import com.luke.tracks.ui.tv.settings.TvSettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.annotation.Config

/** TV Mode in TV Settings, and the TV layout on a phone's screen and on a 16:9 one. */
class TvModeScreenshots(dark: Boolean) : TvScreenshotTest(dark) {
    private val playing = MutableStateFlow(PlayerState(current = TvDemo.song, isPlaying = true))

    @Test fun appearance() {
        show {
            val store = remember { TvFocusStore().apply { requestClaim("") } }
            CompositionLocalProvider(
                LocalTvFocusStore provides store,
                LocalTvNavigator provides remember { TvNavigator(TvDestination.Settings) },
            ) { TvSettingsScreen(initial = "appearance") }
        }
        compose.onNode(hasText("TV Mode")).requestFocus()
        settle(600)
        capture("mode-settings")
    }

    @Composable
    private fun Framed(destination: TvDestination) = TvFrame { TvShell(TvNavigator(destination), playing) }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun phoneLandscape() {
        withSampleHistory()
        show { Framed(TvDestination.Summary) }
        capture("mode-phone-summary")
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun phoneLandscapeSettings() {
        show { Framed(TvDestination.Settings) }
        capture("mode-phone-settings")
    }

    @Test @Config(qualifiers = "w731dp-h411dp-land-xxhdpi")
    fun sixteenByNine() {
        withSampleHistory()
        show { Framed(TvDestination.Summary) }
        capture("mode-hdmi-summary")
    }
}

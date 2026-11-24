package com.luke.tracks.spotify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.spotify.SpotifySourcePageContent
import org.junit.Test

/** Settings > Sources > Spotify in each state the spec draws. */
class SpotifyScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun page(name: String, state: SpotifyState) = snap("spotify-$name") {
        Framed {
            SpotifySourcePageContent(
                state = state,
                quality = SpotifyQuality.VeryHigh to SpotifyQuality.High,
                onSignIn = {},
                onDone = {},
                onTryAgain = {},
                onRemove = {},
                onQuality = { _, _ -> },
            )
        }
    }

    @Composable
    private fun Framed(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalNavigator provides Navigator()) { content() }

    @Test fun signIn() = page("sign-in", SpotifyState.SignedOut)
    @Test fun signingIn() = page("signing-in", SpotifyState.SigningIn)
    @Test fun checking() = page("checking", SpotifyState.Checking)
    @Test fun needsPremium() = page("premium", SpotifyState.Failed(SpotifyProblem.NeedsPremium))
    @Test fun offline() = page("offline", SpotifyState.Failed(SpotifyProblem.Offline))
    @Test fun refused() = page("refused", SpotifyState.Connected("luke", refused = true))
    @Test fun connected() = page("connected", SpotifyState.Connected("luke"))
}

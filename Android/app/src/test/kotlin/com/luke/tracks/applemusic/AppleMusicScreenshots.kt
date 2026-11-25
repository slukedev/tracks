package com.luke.tracks.applemusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.ui.applemusic.AppleMusicSourcePageContent
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import org.junit.Test

/** Settings > Sources > Apple Music in each state. */
class AppleMusicScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun page(name: String, state: AppleMusicState, needsKey: Boolean = false) = snap("apple-music-$name") {
        Framed {
            AppleMusicSourcePageContent(state = state, needsKey = needsKey, onSignIn = {}, onDone = {}, onTryAgain = {}, onGetApp = {}, onLibrary = {}, onRemove = {})
        }
    }

    @Composable
    private fun Framed(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalNavigator provides Navigator()) { content() }

    @Test fun signIn() = page("sign-in", AppleMusicState.SignedOut)
    @Test fun signingIn() = page("signing-in", AppleMusicState.SigningIn)
    @Test fun checking() = page("checking", AppleMusicState.Checking)
    @Test fun needsApp() = page("needs-app", AppleMusicState.Failed(AppleMusicProblem.NeedsApp))
    @Test fun noSubscription() = page("no-subscription", AppleMusicState.Failed(AppleMusicProblem.NoSubscription))
    @Test fun offline() = page("offline", AppleMusicState.Failed(AppleMusicProblem.Offline))
    @Test fun signedOut() = page("signed-out", AppleMusicState.NeedsSignIn(AppleMusicProblem.SignedOut))
    @Test fun needsKey() = page("needs-key", AppleMusicState.SignedOut, needsKey = true)
    @Test fun connected() = page("connected", AppleMusicState.Connected("gb"))
}

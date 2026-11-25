package com.luke.tracks.spotify

import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The store build: no Spotify in the app, and none in the engine it links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpotifyGatingTest {
    @Test fun theStoreBuildHasNoSpotify() {
        assertFalse(BuildConfig.SPOTIFY)
        assertFalse(Spotify.available)
        Spotify.install(ApplicationProvider.getApplicationContext(), CoroutineScope(Dispatchers.Unconfined))
        assertNull(Spotify.integration)
    }

    @Test fun theEngineItLinksWasBuiltWithoutSpotify() {
        try {
            Class.forName("uniffi.tracks_engine.SpotifyAccount")
            fail("the store build's bindings include Spotify")
        } catch (_: ClassNotFoundException) {
        }
    }
}

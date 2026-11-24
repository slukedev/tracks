package com.luke.tracks.spotify

import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.BuildConfig
import com.luke.tracks.sources.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sideload build: Spotify is there, offered in Add Source, with nothing signed in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpotifyGatingTest {
    @Test fun theSideloadBuildHasSpotify() {
        assertTrue(BuildConfig.SPOTIFY)
        Class.forName("uniffi.tracks_engine.SpotifyAccount")
        Spotify.install(ApplicationProvider.getApplicationContext(), CoroutineScope(Dispatchers.Unconfined))
        val spotify = Spotify.integration
        assertNotNull(spotify)
        spotify!!
        assertEquals(SourceKind.Spotify, spotify.provider.kind)
        assertTrue(spotify.provider.sources.value.isEmpty())
        assertTrue(spotify.provider.offersAdd.value)
        assertEquals("Needs Premium", spotify.provider.addSubtitle)
    }
}

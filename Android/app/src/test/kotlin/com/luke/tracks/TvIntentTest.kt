package com.luke.tracks

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.sources
import com.luke.tracks.ui.navigation.Tab
import com.luke.tracks.ui.tv.navigation.TvDestination
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On a TV, outside requests reach the TV's navigation, and only Trackside is a source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvIntentTest {
    private class RecordingPlayer : PlayerController by IdlePlayer() {
        val searches = mutableListOf<String>()
        override fun playFromSearch(query: String) {
            searches += query
        }
    }

    private val app get() = ApplicationProvider.getApplicationContext<TracksApplication>()
    private val player = RecordingPlayer()
    private lateinit var previous: PlayerController

    @Before fun setUp() {
        previous = app.graph.player
        app.graph.player = player
        runBlocking { app.graph.settings.update { it.copy(onboarded = true) } }
    }

    @After fun tearDown() {
        app.graph.player = previous
    }

    @Test fun theGraphKnowsItsATvAndHoldsOnlyServers() {
        assertTrue(app.graph.formFactor.tv)
        assertEquals(listOf(SourceKind.Server), app.graph.sources.registered.value.map { it.kind })
    }

    @Test fun searchGoesToTheTvSearch() {
        val intent = Intent(Intent.ACTION_SEARCH).setClass(app, MainActivity::class.java).putExtra(SearchManager.QUERY, "Saltwater")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(TvDestination.Search, activity.tvNavigator.destination)
                assertEquals(Tab.Play, activity.navigator.tab)
            }
        }
    }

    @Test fun playFromSearchPlays() {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setClass(app, MainActivity::class.java)
            .putExtra(SearchManager.QUERY, "Saltwater")
        ActivityScenario.launch<MainActivity>(intent).use {
            assertEquals(listOf("Saltwater"), player.searches)
        }
    }

    @Test fun linkApprovalsAreIgnored() {
        val link = Uri.parse("tracks://link?server=5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b&code=Q7RM-4X2K")
        ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_VIEW, link).setClass(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(TvDestination.Play, activity.tvNavigator.destination)
                assertFalse(activity.tvNavigator.nowPlayingOpen)
            }
        }
    }

    @Test fun pairingLinksAreIgnored() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("trackside://pair?code=1")).setClass(app, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.navigator.backStack.size > 1) }
        }
    }
}

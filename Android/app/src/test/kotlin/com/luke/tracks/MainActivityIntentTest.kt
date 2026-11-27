package com.luke.tracks

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.ui.navigation.Tab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Searches and Assistant requests reach Search and the player; their text is checked first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityIntentTest {
    /** Something playing, so Now Playing has a song to stay open for. */
    private class RecordingPlayer : PlayerController by IdlePlayer(PlayerState(current = saltwater)) {
        val searches = mutableListOf<String>()
        override fun playFromSearch(query: String) {
            searches += query
        }
    }

    private companion object {
        val saltwater = PlayableSong("local:1", Uri.parse("content://media/1"), "Saltwater", "Chicane", null, null, 240_000, "saltwater|chicane")
    }

    private val app get() = ApplicationProvider.getApplicationContext<TracksApplication>()
    private val player = RecordingPlayer()
    private lateinit var previous: PlayerController

    @Before fun setUp() {
        previous = app.graph.player
        app.graph.player = player
        // A returning listener, so onboarding's animation doesn't keep the looper busy.
        runBlocking { app.graph.settings.update { it.copy(onboarded = true) } }
    }

    @After fun tearDown() {
        app.graph.player = previous
    }

    private fun intent(action: String, query: String?) =
        Intent(action).setClass(app, MainActivity::class.java).apply { query?.let { putExtra(SearchManager.QUERY, it) } }

    @Test fun searchOpensSearchWithTheQuery() {
        ActivityScenario.launch<MainActivity>(intent(Intent.ACTION_SEARCH, "Saltwater")).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(Tab.Search, activity.navigator.tab)
                val pending = activity.navigator.pendingSearch
                assertTrue("query handed to Search", pending == null || pending == "Saltwater")
            }
        }
    }

    @Test fun playFromSearchPlaysAndOpensNowPlaying() {
        ActivityScenario.launch<MainActivity>(intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, "Saltwater by Chicane")).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(listOf("Saltwater by Chicane"), player.searches)
                assertTrue(activity.navigator.nowPlayingOpen)
            }
        }
    }

    @Test fun recreatingDoesNotPlayAgain() {
        ActivityScenario.launch<MainActivity>(intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, "Saltwater")).use { scenario ->
            scenario.recreate()
            assertEquals(listOf("Saltwater"), player.searches)
        }
    }

    @Test fun queriesArePlainBoundedText() {
        val long = "a".repeat(500)
        assertEquals(LaunchRequest.Search("a".repeat(LaunchRequest.MAX_QUERY)), LaunchRequest.of(intent(Intent.ACTION_SEARCH, long)))
        assertEquals(LaunchRequest.Search("Salt water"), LaunchRequest.of(intent(Intent.ACTION_SEARCH, "  Salt\u0000\n water ")))
        assertEquals(LaunchRequest.PlayFromSearch(""), LaunchRequest.of(intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, null)))
        val emoji = "a".repeat(LaunchRequest.MAX_QUERY - 1) + "🎵"
        val cut = LaunchRequest.of(intent(Intent.ACTION_SEARCH, emoji)) as LaunchRequest.Search
        assertFalse(cut.query.last().isHighSurrogate())
    }

    @Test fun linkApprovalsAreCheckedStrictly() {
        fun of(link: String) = LaunchRequest.of(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        val server = "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b"
        assertEquals(LaunchRequest.LinkApproval(server, "Q7RM-4X2K"), of("tracks://link?server=$server&code=Q7RM-4X2K"))
        assertEquals(LaunchRequest.LinkApproval(server, "Q7RM-4X2K"), of("tracks://link?server=${server.uppercase()}&code=Q7RM4X2K"))
        assertNull(of("tracks://link?server=$server"))
        assertNull(of("tracks://link?code=Q7RM-4X2K"))
        assertNull(of("tracks://link?server=home&code=Q7RM-4X2K"))
        assertNull(of("tracks://link?server=$server&code=q7rm-4x2k"))
        assertNull(of("tracks://link?server=$server&code=Q7RM-4X2K-EXTRA"))
        assertNull(of("tracks://link?server=$server%0A&code=Q7RM-4X2K"))
        assertNull(of("tracks://link?server=$server&code=Q7RM--4X2K"))
    }

    @Test fun linksAreReadByScheme() {
        assertEquals(LaunchRequest.Link(Uri.parse("tracks://radio")), LaunchRequest.of(Intent(Intent.ACTION_VIEW, Uri.parse("tracks://radio"))))
        assertEquals(LaunchRequest.Pairing("trackside://pair?x=1"), LaunchRequest.of(Intent(Intent.ACTION_VIEW, Uri.parse("trackside://pair?x=1"))))
        assertNull(LaunchRequest.of(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))))
        assertNull(LaunchRequest.of(Intent(Intent.ACTION_MAIN)))
        val fromRecents = intent(Intent.ACTION_SEARCH, "x").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(LaunchRequest.of(fromRecents))
    }
}

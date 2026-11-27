package com.luke.tracks.tv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvSidePanel
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.navigation.TvScreens
import com.luke.tracks.ui.tv.navigation.TvShell
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shell's focus rules, driven with the remote's keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvShellFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val originalPlay = TvScreens.play
    private val originalAlbum = TvScreens.album
    private val originalLibrary = TvScreens.library
    private val navigator = TvNavigator()
    private val albums = TvDemo.albums

    @Before fun show() {
        TvScreens.play = { DemoHome() }
        compose.setContent {
            TvTracksTheme(dark = true, dynamic = false, seed = TracksBrand) {
                TvShell(navigator, MutableStateFlow(PlayerState(current = TvDemo.song, isPlaying = true)))
            }
        }
        compose.waitForIdle()
    }

    @After fun restore() {
        TvScreens.play = originalPlay
        TvScreens.album = originalAlbum
        TvScreens.library = originalLibrary
    }

    @Test fun launchFocusesTheFirstCard() {
        assertEquals(albums[0].title, focused())
    }

    @Test fun leftFromTheFirstColumnOpensTheRailOnTheCurrentPage() {
        press(listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft))
        assertEquals("Play", focused())
    }

    @Test fun rightFromTheRailGoesBackToTheCardYouLeft() {
        press(listOf(Key.DirectionRight, Key.DirectionRight))
        back()
        assertEquals("Play", focused())
        press(listOf(Key.DirectionDown, Key.DirectionRight))
        assertEquals(albums[2].title, focused())
    }

    @Test fun backWalksFromAPageToTheRailToPlayAndOut() {
        press(listOf(Key.DirectionRight))
        back()
        assertEquals("Play", focused())
        press(listOf(Key.DirectionDown))
        assertEquals("Library", focused())
        press(listOf(Key.DirectionCenter))
        assertEquals(TvDestination.Library, navigator.destination)
        // Library isn't built here, so focus has nowhere to go but the rail.
        back()
        assertEquals(TvDestination.Play, navigator.destination)
        assertEquals("Play", focused())
        assertFalse(navigator.canGoBack(railFocused = true))
    }

    @Test fun openingAPageAndComingBackRefocusesTheCardYouOpened() {
        TvScreens.album = { DetailPage() }
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        assertEquals(2, navigator.backStack.size)
        assertEquals("Shuffle", focused())
        back()
        assertEquals(1, navigator.backStack.size)
        assertEquals(albums[1].title, focused())
    }

    @Test fun nowPlayingTakesFocusAndGivesItBack() {
        press(listOf(Key.DirectionRight))
        compose.runOnIdle { navigator.openNowPlaying() }
        settle()
        assertTrue(navigator.nowPlayingOpen)
        back()
        assertFalse(navigator.nowPlayingOpen)
        assertEquals(albums[1].title, focused())
    }

    @Test fun aPageWithNothingToFocusHandsFocusToTheRail() {
        compose.runOnIdle { navigator.select(TvDestination.History) }
        settle()
        assertEquals("History", focused())
    }

    @Test fun aSidePanelHoldsFocusAndBackReturnsIt() {
        TvScreens.library = { PanelPage() }
        compose.runOnIdle { navigator.select(TvDestination.Library) }
        settle()
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        assertEquals("First", focused())
        // Focus can't wander back out to the page behind.
        press(listOf(Key.DirectionLeft, Key.DirectionLeft))
        assertTrue(focused() in setOf("First", "Second"))
        back()
        assertEquals("Queue", focused())
        assertEquals(TvDestination.Library, navigator.destination)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(keys: List<Key>) {
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            settle()
        }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    /** What whatever has focus is called: its description, else its first text. */
    private fun focused(): String? {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.substringBefore(',')
            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
    }
}

@Composable
private fun PanelPage() {
    val memory = rememberFocusMemory()
    var open by remember { mutableStateOf(false) }
    memory.ClaimFocus(fallback = memory.requester("shuffle"))
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = TvLayout.ContentStart, top = 100.dp)) {
            TvButton("Shuffle", {}, Modifier.rememberFocus(memory, "shuffle"))
            TvButton("Queue", { open = true }, Modifier.rememberFocus(memory, "queue"))
        }
        TvSidePanel(visible = open, onDismiss = { open = false }, title = "Up Next") {
            TvButton("First", {})
            TvButton("Second", {})
        }
    }
}

@Composable
private fun DetailPage() {
    val memory = rememberFocusMemory()
    memory.ClaimFocus(fallback = memory.requester("shuffle"))
    TvButton("Shuffle", {}, Modifier.padding(start = TvLayout.ContentStart, top = 100.dp).rememberFocus(memory, "shuffle"))
}

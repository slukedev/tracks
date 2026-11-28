package com.luke.tracks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.nowplaying.TvSeekBar
import com.luke.tracks.ui.tv.nowplaying.touchSeekMs
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** tv-material's clickables answer only keys; the shared TV components answer touch as well, and keys still. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land-xxhdpi")
class TvTouchTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun tapsAndLongPressesClickAndFocusWhileKeysStillWork() {
        var played by mutableIntStateOf(0)
        var opened by mutableIntStateOf(0)
        var held by mutableIntStateOf(0)
        val first = FocusRequester()
        compose.setContent {
            TvTracksTheme(dark = false, dynamic = false, seed = TracksBrand) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Play", onClick = { played++ }, modifier = Modifier.focusRequester(first))
                    TvListRow(onClick = { opened++ }, headline = "Saltwater", onLongClick = { held++ }, modifier = Modifier.width(300.dp))
                    TvCoverCard(null, "Blue", "Blue", onClick = { opened++ }, subtitle = "Joni Mitchell")
                }
            }
        }
        compose.onNodeWithText("Play").performTouchInput { click() }
        assertEquals(1, played)
        compose.onNodeWithText("Play").assertIsFocused()

        compose.onNodeWithText("Saltwater").performTouchInput { longClick() }
        assertEquals(1, held)
        assertEquals(0, opened)
        compose.onNodeWithText("Saltwater").performTouchInput { click() }
        assertEquals(1, opened)
        compose.onNodeWithText("Saltwater").assertIsFocused()
        compose.onNodeWithText("Play").assertIsNotFocused()

        compose.onNodeWithContentDescription("Blue, Joni Mitchell").performTouchInput { click() }
        assertEquals(2, opened)

        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithText("Play").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(2, played)
    }

    @Test fun aDragScrollsARowWithoutClickingWhatItStartedOn() {
        var clicks by mutableIntStateOf(0)
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        compose.setContent {
            state = rememberLazyListState()
            TvTracksTheme(dark = false, dynamic = false, seed = TracksBrand) {
                LazyRow(state = state, modifier = Modifier.testTag("row"), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    items(20) { i -> TvCoverCard(null, "Album $i", "Album $i", onClick = { clicks++ }) }
                }
            }
        }
        compose.onNodeWithTag("row").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0)
        assertEquals(0, clicks)
    }

    @Test fun aTapOnTheSeekBarSeeksThere() {
        var seekedTo by mutableLongStateOf(-1L)
        compose.setContent {
            TvTracksTheme(dark = false, dynamic = false, seed = TracksBrand) {
                val position = remember { MutableStateFlow(0L) }
                TvSeekBar(position, lengthMs = 200_000, playing = false, ticking = false, onSeek = { seekedTo = it }, modifier = Modifier.width(400.dp).testTag("seek"))
            }
        }
        compose.onNodeWithTag("seek").performTouchInput { click(Offset(centerX, 20f)) }
        compose.waitForIdle()
        assertEquals(100_000.0, seekedTo.toDouble(), 2_000.0)

        assertEquals(0L, touchSeekMs(-20f, 400, 200_000))
        assertEquals(199_000L, touchSeekMs(500f, 400, 200_000))
        assertEquals(0L, touchSeekMs(10f, 0, 200_000))
    }
}

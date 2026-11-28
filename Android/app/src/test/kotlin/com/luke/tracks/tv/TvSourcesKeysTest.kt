package com.luke.tracks.tv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.sources.SourceStatus
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.tv.components.LocalTvFocusStore
import com.luke.tracks.ui.tv.components.TvFocusStore
import com.luke.tracks.ui.tv.settings.TvSettingsScreen
import com.luke.tracks.ui.tv.settings.TvSourcesContent
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Sources with the remote: choosing a server, and Play From's pick up, carry, put down and Back. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvSourcesKeysTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val saved = mutableListOf<List<String>>()
    private val chosen = mutableListOf<SourceSelection>()

    private fun show(sources: List<Source> = TvSourcesDemo.servers, inSettings: Boolean = false) {
        compose.mainClock.autoAdvance = false
        val store = TvFocusStore().apply { requestClaim("") }
        compose.setContent {
            var order by mutableStateOf(sources.map { PlayFromPlace.From(it.id) as PlayFromPlace })
            var selection by mutableStateOf<SourceSelection>(SourceSelection.All)
            TvTracksTheme(dark = true, dynamic = false, seed = TracksBrand) {
                CompositionLocalProvider(LocalTvFocusStore provides store) {
                    val page = @Composable {
                        TvSourcesContent(
                            sources = sources,
                            selection = selection,
                            playFrom = order,
                            onSelect = { selection = it; chosen += it },
                            onReorder = { order = it; saved += it.map(PlayFromPlace::raw) },
                            onRefresh = {},
                            onUnlink = {},
                        )
                    }
                    if (inSettings) TvSettingsScreen { page() } else page()
                }
            }
        }
        advance(600)
    }

    private fun focusPlayFrom(position: String) {
        compose.onNode(hasStateDescription(position)).requestFocus()
        advance(300)
    }

    @Test fun selectPicksARowUpDownCarriesItAndSelectPutsItDown() {
        show()
        focusPlayFrom("1 of 3")
        assertEquals("1 of 3", focusedState())
        press(Key.DirectionCenter)
        assertEquals("Picked up, 1 of 3", focusedState())
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        // Still the same row, with focus, now last; nothing saved while it's carried.
        assertEquals("Picked up, 3 of 3", focusedState())
        assertTrue(focusedText().contains("Living Room"))
        assertEquals(emptyList<List<String>>(), saved)
        press(Key.DirectionCenter)
        assertEquals("3 of 3", focusedState())
        assertEquals(listOf(listOf("server:den", "server:attic", "server:living-room")), saved)
    }

    @Test fun backPutsTheRowBackAndSavesNothing() {
        show()
        focusPlayFrom("2 of 3")
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        assertEquals("Picked up, 1 of 3", focusedState())
        back()
        assertEquals("2 of 3", focusedState())
        assertTrue(focusedText().contains("Den"))
        assertEquals(emptyList<List<String>>(), saved)
    }

    @Test fun aCarriedRowStopsAtTheEndsAndKeepsFocus() {
        show()
        focusPlayFrom("1 of 3")
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        assertEquals("Picked up, 1 of 3", focusedState())
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        assertEquals("Picked up, 1 of 3", focusedState())
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertEquals("Picked up, 3 of 3", focusedState())
        press(Key.DirectionCenter)
        assertEquals(1, saved.size)
    }

    @Test fun theSettingsListStaysPutWhileThePageScrolls() {
        val many = TvSourcesDemo.servers + (1..6).map { i ->
            Source(SourceId.Server("extra-$i"), "Server $i", SourceKind.Server, SourceStatus.Connected, songCount = i * 1_000)
        }
        show(many, inSettings = true)
        fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.top
        // A row that never takes focus, so its focus scale can't move it.
        val list = hasText("About")
        val listTop = top(list)
        val headingTop = top(hasText("Servers"))
        focusPlayFrom("8 of 9")
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        assertEquals("Picked up, 9 of 9", focusedState())
        assertTrue("the page scrolled", top(hasText("Servers")) < headingTop)
        assertEquals("the list didn't", listTop, top(list), 0f)
    }

    @Test fun talkBackMovesARowStraightAway() {
        show()
        val row = compose.onNode(hasStateDescription("1 of 3"))
        val actions = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move Down"), actions.map { it.label })
        compose.runOnIdle { actions.single().action() }
        advance(300)
        assertEquals(listOf(listOf("server:den", "server:living-room", "server:attic")), saved)
    }

    @Test fun choosingAServerNarrowsToItAndAllSourcesWidensAgain() {
        show()
        val choice = { name: String -> compose.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)) }
        choice("Den").requestFocus()
        advance(300)
        press(Key.DirectionCenter)
        assertEquals(SourceSelection.Only(SourceId.Server("den")), chosen.last())
        assertTrue(choice("Den").fetchSemanticsNode().config[SemanticsProperties.Selected])
        choice("All Sources").performSemanticsAction(SemanticsActions.OnClick)
        advance(300)
        assertEquals(SourceSelection.All, chosen.last())
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        advance(300)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(300)
    }

    private fun advance(ms: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun focusedState(): String? = compose.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun focusedText(): String =
        compose.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
}

/** Servers for the Sources page's tests and screenshots. */
object TvSourcesDemo {
    val living = Source(SourceId.Server("living-room"), "Living Room Server", SourceKind.Server, SourceStatus.Connected, songCount = 12_480)
    val den = Source(SourceId.Server("den"), "Den", SourceKind.Server, SourceStatus.Connected, songCount = 3_210)
    val attic = Source(SourceId.Server("attic"), "Attic NAS", SourceKind.Server, SourceStatus.Offline("Couldn't reach Attic NAS. Check that it's on."))
    val servers = listOf(living, den, attic)
}

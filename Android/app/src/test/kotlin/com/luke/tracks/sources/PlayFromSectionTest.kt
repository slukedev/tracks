package com.luke.tracks.sources

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.sources.SourcesPageContent
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.theme.TracksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's Play From with a streaming service and Get: moved like any source, and Get switched off and on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class PlayFromSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val apple = PlayFromPlace.Service(PlayFromPlace.APPLE_MUSIC)
    private val saved = mutableListOf<List<String>>()
    private val gets = mutableListOf<Boolean>()

    private fun show() {
        compose.setContent {
            var order by mutableStateOf(listOf<PlayFromPlace>(PlayFromPlace.From(FakeSources.home.id), apple, PlayFromPlace.Get))
            var getOn by mutableStateOf(true)
            val names = mapOf(PlayFromPlace.From(FakeSources.home.id) to "Home", apple to "Apple Music", PlayFromPlace.Get to "Get")
            TracksTheme(dark = false, dynamic = false, seed = TracksBrand) {
                CompositionLocalProvider(LocalNavigator provides Navigator()) {
                    SourcesPageContent(
                        sources = listOf(FakeSources.home),
                        playFrom = order.map { PlayFromChoice(it, names.getValue(it)) },
                        options = emptyList(),
                        onOpen = {},
                        onRemove = {},
                        onReorder = { places ->
                            order = order.let { all -> PlayFromOrder(all).reordered(places).places }
                            saved += places.map(PlayFromPlace::raw)
                        },
                        get = getOn,
                        onGet = { getOn = it; gets += it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun moveAction(state: String, label: String) {
        val row = compose.onNode(hasStateDescription(state))
        row.performScrollTo()
        val action = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
        compose.waitForIdle()
    }

    @Test fun aServiceAndGetMoveLikeAnySource() {
        show()
        moveAction("3 of 3", "Move up")
        assertEquals(listOf(listOf("server:s1", "get", "appleMusic")), saved)
        moveAction("1 of 3", "Move down")
        assertEquals(listOf("get", "server:s1", "appleMusic"), saved.last())
    }

    @Test fun getsRowIsItsSwitchAndOffItKeepsItsPlaceWithoutANumber() {
        show()
        val row = compose.onNode(hasText("Adds it to your library"))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        row.assert(hasStateDescription("3 of 3"))
        row.performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), gets)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        row.assert(hasStateDescription("3 of 3"))
        row.assert(hasText("3").not())
        row.performClick()
        compose.waitForIdle()
        assertEquals(listOf(false, true), gets)
        row.assert(hasText("3"))
    }
}

package com.luke.tracks

import android.app.SearchManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import com.github.takahirom.roborazzi.captureRoboImage
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.sources.sources
import com.luke.tracks.system.TvMode
import com.luke.tracks.system.TvModeChoice
import com.luke.tracks.ui.navigation.Tab
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvScreens
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** The real activity under TV Mode: which shell shows, swapping live both ways, and what the window does. */
abstract class TvModeShellBase {
    @get:Rule val compose = createEmptyComposeRule()

    protected val app get() = ApplicationProvider.getApplicationContext<TracksApplication>()
    private lateinit var previousPlayer: PlayerController
    private val originalGate = TvScreens.gate

    @Before fun setUp() {
        previousPlayer = app.graph.player
        app.graph.player = IdlePlayer()
        // A returning listener, so neither first run shows.
        runBlocking { app.graph.settings.update { it.copy(onboarded = true) } }
        TvScreens.gate = { Text(TvShellMarker) }
    }

    @After fun tearDown() {
        TvScreens.gate = originalGate
        app.graph.player = previousPlayer
    }

    protected fun launch(intent: Intent = Intent(app, MainActivity::class.java)): ActivityScenario<MainActivity> {
        compose.mainClock.autoAdvance = false
        return ActivityScenario.launch<MainActivity>(intent).also { settle() }
    }

    protected fun settle(ms: Long = 800) {
        repeat((ms / 50).toInt()) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(50)
        }
    }

    protected fun switchTo(on: Boolean) {
        compose.runOnUiThread { app.graph.tvMode.set(on = on) }
        settle()
    }

    protected fun assertTvShell() {
        compose.onNodeWithText(TvShellMarker).assertExists()
        assertTrue(compose.onAllNodesWithText("Summary").fetchSemanticsNodes().isEmpty())
    }

    protected fun assertPhoneShell() {
        compose.onNodeWithText(TvShellMarker).assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("Summary").fetchSemanticsNodes().isNotEmpty())
    }

    protected fun ActivityScenario<MainActivity>.orientation(): Int {
        var orientation = 0
        onActivity { orientation = it.requestedOrientation }
        return orientation
    }

    /** What the next process reads: a fresh instance over the same stored value. */
    protected fun storedLayout(): Boolean = TvMode.of(app, app.graph.formFactor.tv).layout.value

    private companion object {
        const val TvShellMarker = "TV shell"
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TvModePhoneTest : TvModeShellBase() {
    @Test fun aPhoneSwapsToTheTvLayoutAndBackWithoutRestarting() {
        launch().use { scenario ->
            assertPhoneShell()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, scenario.orientation())

            switchTo(on = true)
            assertTvShell()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, scenario.orientation())
            scenario.onActivity { assertTrue(it.navigation === it.tvNavigator) }
            assertTrue(storedLayout())

            switchTo(on = false)
            assertPhoneShell()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, scenario.orientation())
            scenario.onActivity { assertTrue(it.navigation === it.navigator) }
            assertFalse(storedLayout())
        }
    }

    @Test fun reopenedInTvModeTheFirstFrameIsTheTvLayout() {
        app.graph.tvMode.set(TvModeChoice.On)
        val search = Intent(Intent.ACTION_SEARCH).setClass(app, MainActivity::class.java).putExtra(SearchManager.QUERY, "Saltwater")
        launch(search).use { scenario ->
            assertTvShell()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, scenario.orientation())
            scenario.onActivity { activity ->
                // Outside requests go to the layout that shows.
                assertEquals(TvDestination.Search, activity.tvNavigator.destination)
                assertEquals(Tab.Play, activity.navigator.tab)
            }
            // A recreate (the turn to landscape) keeps it.
            scenario.recreate()
            settle()
            assertTvShell()
        }
    }

    @Test fun aPhoneInTheTvLayoutStillHasItsOwnSources() {
        app.graph.tvMode.set(TvModeChoice.On)
        launch().use {
            assertFalse(app.graph.formFactor.tv)
            assertTrue(app.graph.sources.registered.value.any { it.kind == com.luke.tracks.sources.SourceKind.Device })
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvModeTelevisionTest : TvModeShellBase() {
    @Test fun aTvSwapsToThePhoneLayoutAndBack() {
        launch().use { scenario ->
            assertTvShell()
            switchTo(on = false)
            assertPhoneShell()
            // The TV's window is left as it is either way.
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, scenario.orientation())
            assertFalse(storedLayout())
            switchTo(on = true)
            assertTvShell()
            assertEquals(TvModeChoice.Automatic, app.graph.tvMode.choice.value)
        }
    }

    /** The phone layout on a TV, by remote alone: Settings' TV mode row takes focus and turns the TV layout back on. */
    @OptIn(ExperimentalTestApi::class)
    @Test fun theRemoteCanTurnTvModeBackOn() {
        app.graph.tvMode.set(TvModeChoice.Off)
        val settings = Intent(Intent.ACTION_VIEW, Uri.parse("tracks://settings")).setClass(app, MainActivity::class.java)
        launch(settings).use {
            settle(1_500)
            assertPhoneShell()
            val row = hasText("TV mode") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)
            val focused = SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)
            var reached = false
            // A TV is never in touch mode, where a clickable takes no focus, and the system
            // focuses the window's first control; Robolectric does neither, so do both here.
            it.onActivity { activity -> leaveTouchMode(activity.window.decorView) }
            settle(150)
            compose.onAllNodes(hasText("Sources") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))[0].requestFocus()
            settle(150)
            repeat(40) {
                if (compose.onAllNodes(row and focused, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty()) {
                    reached = true
                    return@repeat
                }
                compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                settle(150)
            }
            assertTrue("D-pad Down never reached the TV mode row", reached)
            compose.onRoot().captureRoboImage("build/screenshots/tv-mode-phone-layout-on-tv-focused.png")
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            settle()
            assertTvShell()
            assertTrue(storedLayout())
        }
    }
}

/** What a remote's first key does to the window, through the hidden ViewRootImpl. */
private fun leaveTouchMode(decor: android.view.View) {
    val root = decor.javaClass.getMethod("getViewRootImpl").invoke(decor) ?: return
    root.javaClass.getMethod("touchModeChanged", Boolean::class.javaPrimitiveType).invoke(root, false)
}

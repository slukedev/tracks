package com.luke.tracks.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.luke.tracks.AppGraph
import com.luke.tracks.TracksApplication
import com.luke.tracks.data.SampleData
import com.luke.tracks.discover.LocalDiscovery
import com.luke.tracks.discover.LocalServerPicks
import com.luke.tracks.discover.NoDiscovery
import com.luke.tracks.discover.NoServerPicks
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import androidx.tv.material3.MaterialTheme as TvTheme

/**
 * Renders TV screens at 1080p (960 by 540dp) on the JVM with the real engine and sample
 * history, once light and once dark, into app/build/screenshots/tv-<name>-light.png and
 * -dark.png.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests 'com.luke.tracks.tv.*Screenshots*'
 *
 * Subclass it, keep the `(dark: Boolean)` constructor, then either [snap] a screen as it
 * first appears, or [show] it, drive focus with [press], and [capture] each state.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
abstract class TvScreenshotTest(protected val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    val app: Application get() = ApplicationProvider.getApplicationContext()
    val graph: AppGraph get() = (app as TracksApplication).graph

    /** Fills the history with sample plays. */
    fun withSampleHistory() = runBlocking {
        graph.history.replaceAll(SampleData.plays())
    }

    /** Lets real background work finish and the UI, focus included, settle. */
    fun settle(ms: Long = 1500) {
        repeat((ms / 50).toInt()) {
            Thread.sleep(25)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(50)
        }
        compose.waitForIdle()
    }

    fun show(reducedMotion: Boolean = false, settleMs: Long = 1500, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TvTracksTheme(dark = dark, dynamic = false, seed = TracksBrand) {
                CompositionLocalProvider(
                    LocalReducedMotion provides reducedMotion,
                    LocalDiscovery provides NoDiscovery,
                    LocalServerPicks provides NoServerPicks,
                ) {
                    Box(Modifier.fillMaxSize().background(TvTheme.colorScheme.background)) { content() }
                }
            }
        }
        settle(settleMs)
    }

    /** D-pad and remote keys, sent to whatever has focus, then a settle for focus motion. */
    fun press(key: Key) = press(listOf(key))

    @OptIn(ExperimentalTestApi::class)
    fun press(keys: List<Key>, settleMs: Long = 600) {
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            settle(150)
        }
        settle(settleMs)
    }

    fun capture(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/tv-$name-${if (dark) "dark" else "light"}.png")
    }

    fun snap(name: String, settleMs: Long = 1500, content: @Composable () -> Unit) {
        show(settleMs = settleMs, content = content)
        capture(name)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }
}

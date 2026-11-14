package com.luke.tracks

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.luke.tracks.data.SampleData
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.theme.TracksTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Renders screens on the JVM with the real engine (built for this computer by
 * Engine/build-android.sh) and sample history, once light and once dark. Images land in
 * app/build/screenshots/<name>-light.png and -dark.png.
 *
 * Run: ./gradlew :app:testDebugUnitTest --tests '*Screenshots*'
 *
 * Subclass it, keep the `(dark: Boolean)` constructor, and call [snap] once per test.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
abstract class ScreenshotTest(protected val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    val app: Application get() = ApplicationProvider.getApplicationContext()
    val graph: AppGraph get() = (app as TracksApplication).graph

    /** Fills the history with sample plays. */
    fun withSampleHistory() = runBlocking {
        graph.history.replaceAll(SampleData.plays())
    }

    /** Lets real background work (engine reads on Dispatchers.IO) finish and the UI settle. */
    fun settle(ms: Long = 1500) {
        repeat((ms / 50).toInt()) {
            Thread.sleep(25)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(50)
        }
        compose.waitForIdle()
    }

    fun snap(name: String, settleMs: Long = 1500, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TracksTheme(dark = dark, dynamic = false, seed = TracksBrand) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { content() }
            }
        }
        settle(settleMs)
        compose.onRoot().captureRoboImage("build/screenshots/$name-${if (dark) "dark" else "light"}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }
}

/** The whole app frame on the Play tab, as a smoke test of the harness. */
class ShellScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    @org.junit.Test
    fun shell() {
        withSampleHistory()
        snap("shell") {
            com.luke.tracks.ui.navigation.AppShell(com.luke.tracks.ui.navigation.Navigator())
        }
    }
}

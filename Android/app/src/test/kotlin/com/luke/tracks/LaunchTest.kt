package com.luke.tracks

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.data.SampleData
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** The real activity starts, draws its first frames and survives a recreate (rotation, theme). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LaunchTest {
    @Test
    fun launchesAndRecreates() {
        // A returning listener: onboarding's cassette spins until it's dismissed, which would
        // keep Robolectric's looper from ever going idle.
        val graph = (ApplicationProvider.getApplicationContext<TracksApplication>()).graph
        runBlocking {
            graph.settings.update { it.copy(onboarded = true) }
            graph.history.replaceAll(SampleData.plays(days = 60))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.recreate()
            repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}

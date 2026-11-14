package com.luke.tracks.insights

import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.data.PeriodKind
import com.luke.tracks.ui.summary.SummaryScreen
import com.luke.tracks.ui.summary.loadSummary
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config

class SummaryScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    /** Reads the page once first, so the render doesn't race the first read of the history. */
    private fun warm() = runBlocking {
        loadSummary(graph.engine, PeriodKind.Month, 0)
    }

    @Test
    fun summary() {
        withSampleHistory()
        warm()
        snap("summary", settleMs = 6000) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { SummaryScreen() }
        }
    }

    /** The whole page in one tall image, to check every section. */
    @Test
    @Config(qualifiers = "w412dp-h3600dp-xxhdpi")
    fun summaryFull() {
        withSampleHistory()
        warm()
        snap("summary-full", settleMs = 9000) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { SummaryScreen() }
        }
    }

    @Test
    fun summaryEmpty() {
        runBlocking { graph.history.replaceAll(emptyList()) }
        snap("summary-empty") {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { SummaryScreen() }
        }
    }
}

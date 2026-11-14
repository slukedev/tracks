package com.luke.tracks.insights

import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.ui.charts.ChartsScreen
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.luke.tracks.data.PeriodKind
import com.luke.tracks.ui.charts.ChartKind
import com.luke.tracks.ui.charts.SourceChoice
import com.luke.tracks.ui.charts.loadCharts
import org.robolectric.annotation.Config

class ChartsScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    @Test
    @Config(qualifiers = "w412dp-h1800dp-xxhdpi")
    fun charts() {
        withSampleHistory()
        runBlocking { loadCharts(graph.engine, ChartKind.Songs, PeriodKind.Week, 0, SourceChoice.All) }
        snap("charts", settleMs = 6000) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { ChartsScreen() }
        }
    }
}

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

    /** History in Charts mode, as the tab shows it, with the Plays and Charts switch in the bar. */
    @Test
    fun historyCharts() {
        withSampleHistory()
        runBlocking { loadCharts(graph.engine, ChartKind.Songs, PeriodKind.Week, 0, SourceChoice.All) }
        val navigator = Navigator().apply { historyMode = com.luke.tracks.ui.navigation.HistoryMode.Charts }
        snap("history-charts", settleMs = 6000) {
            CompositionLocalProvider(LocalNavigator provides navigator) { com.luke.tracks.ui.history.HistoryTab() }
        }
    }

    @Test
    fun historyPlays() {
        withSampleHistory()
        snap("history-plays", settleMs = 4000) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { com.luke.tracks.ui.history.HistoryTab() }
        }
    }

    /** The whole chart, all hundred rows' worth of page. */
    @Test
    @Config(qualifiers = "w412dp-h5200dp-xxhdpi")
    fun chartsFull() {
        withSampleHistory()
        runBlocking { loadCharts(graph.engine, ChartKind.Songs, PeriodKind.Week, 0, SourceChoice.All) }
        snap("full-charts", settleMs = 8000) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { ChartsScreen() }
        }
    }
}

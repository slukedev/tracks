package com.luke.tracks.ui.history

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.charts.ChartsScreen
import com.luke.tracks.ui.insights.ConnectedChoice
import com.luke.tracks.ui.navigation.HistoryMode
import com.luke.tracks.ui.navigation.LocalNavigator

/**
 * The History tab: two views of the same record, every play or the charts, switched in the
 * bar. `tracks://charts`, the Charts shortcut and Summary's See Charts open it on Charts.
 */
@Composable
fun HistoryTab() {
    val navigator = LocalNavigator.current
    val switch: @Composable () -> Unit = {
        ConnectedChoice(
            options = HistoryMode.entries,
            selected = navigator.historyMode,
            onSelect = { navigator.historyMode = it },
            label = { it.name },
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
    Crossfade(navigator.historyMode, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "history-mode") { mode ->
        when (mode) {
            HistoryMode.Plays -> HistoryScreen(modeSwitch = switch)
            HistoryMode.Charts -> ChartsScreen(modeSwitch = switch)
        }
    }
}

/** History's bar: the Plays and Charts switch where a title would be, and the mode's own actions. */
@Composable
fun HistoryBar(modeSwitch: @Composable () -> Unit, scroll: TopAppBarScrollBehavior, actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = modeSwitch,
        actions = actions,
        scrollBehavior = scroll,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

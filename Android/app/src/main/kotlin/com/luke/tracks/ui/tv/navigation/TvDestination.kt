package com.luke.tracks.ui.tv.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.luke.tracks.ui.navigation.ChartsRoute
import com.luke.tracks.ui.navigation.HistoryRoute
import com.luke.tracks.ui.navigation.LibraryRoute
import com.luke.tracks.ui.navigation.PlayRoute
import com.luke.tracks.ui.navigation.Route
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.navigation.SettingsRoute
import com.luke.tracks.ui.navigation.SummaryRoute

/** The rail, top to bottom; Settings sits apart at the bottom. */
enum class TvDestination(val label: String, val root: Route, val selectedIcon: ImageVector, val icon: ImageVector) {
    Search("Search", SearchRoute, Icons.Rounded.Search, Icons.Outlined.Search),
    Play("Play", PlayRoute, Icons.Rounded.PlayCircle, Icons.Outlined.PlayCircle),
    Library("Library", LibraryRoute, Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
    Summary("Summary", SummaryRoute, Icons.Rounded.Insights, Icons.Outlined.Insights),
    Charts("Charts", ChartsRoute, Icons.Rounded.BarChart, Icons.Outlined.BarChart),
    History("History", HistoryRoute, Icons.Rounded.History, Icons.Outlined.History),
    Settings("Settings", SettingsRoute, Icons.Rounded.Settings, Icons.Outlined.Settings),
}

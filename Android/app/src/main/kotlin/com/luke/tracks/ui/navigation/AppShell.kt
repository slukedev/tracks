package com.luke.tracks.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.luke.tracks.ui.charts.ChartsScreen
import com.luke.tracks.ui.detail.AlbumScreen
import com.luke.tracks.ui.detail.ArtistScreen
import com.luke.tracks.ui.detail.SongScreen
import com.luke.tracks.ui.history.HistoryScreen
import com.luke.tracks.ui.library.LibraryAlbumScreen
import com.luke.tracks.ui.library.LibraryArtistScreen
import com.luke.tracks.ui.library.LibraryScreen
import com.luke.tracks.ui.nowplaying.MiniPlayer
import com.luke.tracks.ui.nowplaying.NowPlayingScreen
import com.luke.tracks.ui.play.MixScreen
import com.luke.tracks.ui.play.PlayScreen
import com.luke.tracks.ui.search.SearchScreen
import com.luke.tracks.ui.settings.SettingsPageScreen
import com.luke.tracks.ui.settings.SettingsScreen
import com.luke.tracks.ui.summary.SummaryScreen

private data class TabItem(val tab: Tab, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val tabs = listOf(
    TabItem(Tab.Play, "Play", Icons.Rounded.PlayCircle, Icons.Outlined.PlayCircle),
    TabItem(Tab.Summary, "Summary", Icons.Rounded.Insights, Icons.Outlined.Insights),
    TabItem(Tab.Charts, "Charts", Icons.Rounded.BarChart, Icons.Outlined.BarChart),
    TabItem(Tab.History, "History", Icons.Rounded.History, Icons.Outlined.History),
    TabItem(Tab.Library, "Your Music", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
)

/**
 * The app's frame: a navigation bar on phones and a rail on wider screens (Material's
 * adaptive navigation suite), each tab's own back stack with predictive back, the mini player
 * above the bar, and the full player over everything.
 */
@Composable
fun AppShell(navigator: Navigator) {
    SharedTransitionLayout {
    CompositionLocalProvider(LocalNavigator provides navigator, LocalSharedTransitionScope provides this) {
        Box(Modifier.fillMaxSize()) {
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    tabs.forEach { item ->
                        val selected = navigator.tab == item.tab
                        item(
                            selected = selected,
                            onClick = { navigator.select(item.tab) },
                            icon = { Icon(if (selected) item.selected else item.unselected, contentDescription = null) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    NavDisplay(
                        backStack = navigator.backStack,
                        onBack = { navigator.back() },
                        entryProvider = entryProvider {
                            entry<PlayRoute> { PlayScreen() }
                            entry<SummaryRoute> { SummaryScreen() }
                            entry<ChartsRoute> { ChartsScreen() }
                            entry<HistoryRoute> { HistoryScreen() }
                            entry<LibraryRoute> { LibraryScreen() }
                            entry<ArtistRoute> { ArtistScreen(it.identity, it.name) }
                            entry<AlbumRoute> { AlbumScreen(it.identity, it.title, it.artistName) }
                            entry<SongRoute> { SongScreen(it.identity, it.title, it.artistName) }
                            entry<MixRoute> { MixScreen(it.mixId) }
                            entry<SearchRoute> { SearchScreen() }
                            entry<SettingsRoute> { SettingsScreen() }
                            entry<SettingsPageRoute> { SettingsPageScreen(it.page) }
                            entry<LibraryAlbumRoute> { LibraryAlbumScreen(it.albumId) }
                            entry<LibraryArtistRoute> { LibraryArtistScreen(it.artistId) }
                        },
                    )
                    MiniPlayer(
                        onExpand = { navigator.nowPlayingOpen = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            // Back from a tab's root goes to Play before leaving the app.
            BackHandler(enabled = !navigator.nowPlayingOpen && navigator.backStack.size == 1 && navigator.tab != Tab.Play) {
                navigator.select(Tab.Play)
            }

            AnimatedVisibility(
                visible = navigator.nowPlayingOpen,
                enter = slideInVertically(tween(450)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(350)) { it } + fadeOut(tween(250)),
            ) {
                BackHandler { navigator.nowPlayingOpen = false }
                CompositionLocalProvider(LocalNowPlayingVisibilityScope provides this) {
                    NowPlayingScreen(onClose = { navigator.nowPlayingOpen = false })
                }
            }
        }
    }
    }
}

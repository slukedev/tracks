package com.luke.tracks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvChip
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvIconButton
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvListSkeleton
import com.luke.tracks.ui.tv.components.TvPageSkeleton
import com.luke.tracks.ui.tv.components.TvSectionTitle
import com.luke.tracks.ui.tv.components.TvShelfSkeleton
import com.luke.tracks.ui.tv.components.TvSidePanel
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.navigation.TvScreens
import com.luke.tracks.ui.tv.navigation.TvShell
import com.luke.tracks.ui.tv.theme.TvLayout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The TV frame and the shared components, in their focus states. */
class TvShellScreenshots(dark: Boolean) : TvScreenshotTest(dark) {
    private val originalPlay = TvScreens.play

    @Before fun demoPages() {
        TvScreens.play = { DemoHome() }
    }

    @After fun restore() {
        TvScreens.play = originalPlay
    }

    private val playing = MutableStateFlow(PlayerState(current = TvDemo.song, isPlaying = true))

    @Test fun shell() {
        withSampleHistory()
        show { TvShell(TvNavigator(), playing) }
        // Launch focuses the first card.
        capture("shell-card-focused")
        press(listOf(Key.DirectionRight, Key.DirectionRight))
        capture("shell-card-moved")
        // Back from a page's top opens the rail on the page you're on.
        back()
        capture("shell-rail-open")
        // Right from the rail goes back to the card you left.
        press(Key.DirectionRight)
        capture("shell-rail-closed")
    }

    @Test fun reducedMotion() {
        show(reducedMotion = true) { TvShell(TvNavigator(), MutableStateFlow(PlayerState(current = TvDemo.song, isPlaying = false))) }
        press(Key.DirectionRight)
        capture("shell-reduced-motion")
    }

    @Test fun nothingPlaying() {
        show { TvShell(TvNavigator(TvDestination.History), MutableStateFlow(PlayerState())) }
        capture("shell-nothing-playing")
    }

    @Test fun firstLoad() {
        TvScreens.play = {
            rememberFocusMemory()
            TvPageSkeleton()
        }
        show { TvShell(TvNavigator(), MutableStateFlow(PlayerState())) }
        capture("shell-loading")
    }

    @Test fun components() {
        val first = FocusRequester()
        show {
            Column(
                Modifier.fillMaxSize().padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = TvLayout.SafeTop),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TvSectionTitle("Buttons")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvButton("Play", {}, Modifier.focusRequester(first), icon = Icons.Rounded.PlayArrow, style = TvButtonStyle.Primary)
                    TvButton("Shuffle", {}, icon = Icons.Rounded.Shuffle)
                    TvButton("Radio", {}, icon = Icons.Rounded.Radio, pending = true)
                    TvIconButton(Icons.Rounded.SkipNext, "Next", {})
                    TvIconButton(Icons.Rounded.Shuffle, "Shuffle", {}, selected = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Week", "Month", "Year", "All Time").forEachIndexed { i, period -> TvChip(period, selected = i == 1, onClick = {}) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TvDemo.albums.take(3).forEachIndexed { i, album ->
                            TvListRow(
                                onClick = {},
                                headline = if (i == 2) "${album.title}, the Deluxe Edition Remastered With Extras" else album.title,
                                supporting = album.artist,
                                selected = i == 1,
                                leading = { TvArtwork(null, album.title, Modifier.size(48.dp), sizePx = 96) },
                                trailing = { Text("3:${41 + i}", style = MaterialTheme.typography.bodyMedium) },
                            )
                        }
                    }
                    TvListSkeleton(Modifier.width(300.dp), rows = 3)
                }
            }
        }
        first.requestFocus()
        settle(600)
        capture("components-buttons")
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        capture("components-row")
    }

    @Test fun cardsAndEmpty() {
        show {
            Column(Modifier.fillMaxSize().padding(top = TvLayout.SafeTop)) {
                Row(Modifier.padding(start = TvLayout.ContentStart, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(TvLayout.ShelfSpacing)) {
                    TvCoverCard(null, "Blue", "Blue", {}, subtitle = "Joni Mitchell")
                    TvCoverCard(null, "A Very Long Album Title That Will Not Fit", "A Very Long Album Title That Will Not Fit", {}, subtitle = "Somebody With a Long Name", pending = true)
                    TvCoverCard(null, "Joni Mitchell", "Joni Mitchell", {}, circle = true)
                }
                TvShelfSkeleton(Modifier.padding(top = 24.dp), cards = 5, titled = false)
            }
        }
        capture("components-cards")
    }

    @Test fun emptyAndPanel() {
        show {
            Box(Modifier.fillMaxSize()) {
                TvEmptyState("No Plays Yet", actionLabel = "Start Tracks Radio", onAction = {})
                TvSidePanel(visible = true, onDismiss = {}, title = "Up Next") {
                    TvDemo.albums.take(5).forEach { album ->
                        TvListRow(
                            onClick = {},
                            headline = album.title,
                            supporting = album.artist,
                            dense = true,
                            leading = { TvArtwork(null, album.title, Modifier.size(40.dp), sizePx = 96) },
                        )
                    }
                }
            }
        }
        capture("components-panel")
    }
}

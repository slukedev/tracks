package com.luke.tracks.play

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.navigation.AppShell
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.Tab
import org.junit.Test

/**
 * Each tab inside the real frame (navigation bar, floating mini player) at phone size, at the
 * top and after scrolling, to see how much room the content gets once the big title collapses.
 */
class ScrollScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun shell(tab: Tab, name: String, swipes: Int) {
        withSampleHistory()
        val library = SampleLibrary().also { graph.library = it }
        if (tab == Tab.Library) {
            graph.library = object : com.luke.tracks.library.MusicLibrary by library {
                override val snapshot = kotlinx.coroutines.flow.MutableStateFlow(com.luke.tracks.library.FakeLibrary.index.snapshot)
            }
        }
        val queue = library.songs.take(8)
        graph.player = IdlePlayer(
            PlayerState(
                current = queue[1], isPlaying = true, positionMs = 60_000, durationMs = 210_000,
                queue = queue, index = 1, origin = QueueOrigin("On Repeat", QueueOrigin.Kind.Mix),
            ),
        )
        val navigator = Navigator(start = tab)
        snap(name, settleMs = 6000) { AppShell(navigator) }
        if (swipes > 0) {
            val vertical = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            repeat(swipes) {
                compose.onAllNodes(vertical).onFirst().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = top + height * 0.15f) }
                settle(400)
            }
            settle(1200)
            // Mid-scroll, before the bar returns, is what a reader sees most of the time.
            compose.onRoot().captureRoboImage("build/screenshots/$name-scrolled-${if (dark) "dark" else "light"}.png")
        }
    }

    @Test fun play() = shell(Tab.Play, "scroll-play", 2)
    @Test fun summary() = shell(Tab.Summary, "scroll-summary", 2)
    @Test fun charts() = shell(Tab.Charts, "scroll-charts", 2)
    @Test fun history() = shell(Tab.History, "scroll-history", 2)
    @Test fun library() = shell(Tab.Library, "scroll-library", 2)
}

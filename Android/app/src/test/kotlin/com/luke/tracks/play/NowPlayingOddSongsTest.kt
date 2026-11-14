package com.luke.tracks.play

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.nowplaying.MiniPlayer
import com.luke.tracks.ui.nowplaying.NowPlayingScreen
import com.luke.tracks.ui.theme.TracksTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import uniffi.tracks_engine.songIdentity

/**
 * Now Playing and the mini player with a ringtone, a song of unknown length and a song with no
 * names, on every Android Tracks runs on, with the system's own colors (as on a Samsung or a
 * Motorola) rather than a fixed seed. Screenshots of the same songs are in [OddSongScreenshots].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31, 33, 34, 35, 36])
class NowPlayingOddSongsTest {
    @get:Rule val compose = createComposeRule()

    private val graph get() = ApplicationProvider.getApplicationContext<TracksApplication>().graph

    private fun song(title: String, artist: String, durationMs: Long?) = PlayableSong(
        mediaId = "local:9001",
        uri = Uri.parse("content://media/external/audio/media/9001"),
        title = title,
        artistName = artist,
        albumTitle = null,
        artworkUri = Uri.parse("content://media/external/audio/albumart/0"),
        durationMs = durationMs,
        identity = songIdentity(title, artist),
    )

    private fun show(song: PlayableSong, durationMs: Long, positionMs: Long): MutableStateFlow<PlayerState> {
        val player = IdlePlayer(PlayerState(current = song, isPlaying = true, positionMs = positionMs, durationMs = durationMs, queue = listOf(song)))
        graph.player = player
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TracksTheme(dynamic = true) {
                CompositionLocalProvider(LocalNavigator provides Navigator()) {
                    Box(Modifier.fillMaxSize()) {
                        NowPlayingScreen(onClose = {})
                        MiniPlayer(onExpand = {}, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        settle()
        @Suppress("UNCHECKED_CAST")
        return player.state as MutableStateFlow<PlayerState>
    }

    private fun settle() {
        repeat(30) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(50)
        }
    }

    @Test fun ringtone() {
        show(song("Over the Horizon", "<unknown>", 3_000), durationMs = 3_000, positionMs = 1_400)
        // Never kept, so no ring counting toward it; the unknown artist reads as such, twice
        // (the full player and the mini player).
        compose.onNodeWithContentDescription("Counting toward your history").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("Unknown artist").fetchSemanticsNodes().size == 2)
        compose.onNodeWithContentDescription("Seek").performTouchInput { swipeRight() }
        settle()
    }

    @Test fun unknownLength() {
        val state = show(song("Live Stream", "Radio", null), durationMs = C.TIME_UNSET, positionMs = 65_000)
        compose.onNodeWithContentDescription("Seek").assertDoesNotExist()
        compose.onNodeWithContentDescription("Length unknown").assertExists()
        compose.onNodeWithContentDescription("Counting toward your history").assertExists()
        // The length arrives: the seek bar comes back.
        state.value = state.value.copy(durationMs = 240_000)
        settle()
        compose.onNodeWithContentDescription("Seek").assertExists()
    }

    @Test fun noNames() {
        show(song("", "", 0), durationMs = 0, positionMs = -500)
        assertTrue(compose.onAllNodesWithText("Untitled").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Length unknown").assertExists()
    }
}

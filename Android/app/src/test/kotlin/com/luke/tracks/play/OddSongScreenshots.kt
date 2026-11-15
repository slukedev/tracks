package com.luke.tracks.play

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.C
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.nowplaying.MiniPlayer
import com.luke.tracks.ui.nowplaying.NowPlayingScreen
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.luke.tracks.ui.navigation.AppShell
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uniffi.tracks_engine.songIdentity

/**
 * Songs at the edges: a three-second ringtone with MediaStore's "<unknown>" artist and an album
 * cover address that leads nowhere, a stream of unknown length, and a song with no names at all.
 * Now Playing and the mini player have to draw every one of them without crashing.
 */
class OddSongScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun song(title: String, artist: String, durationMs: Long?, album: String? = null) = PlayableSong(
        mediaId = "local:9001",
        uri = Uri.parse("content://media/external/audio/media/9001"),
        title = title,
        artistName = artist,
        albumTitle = album,
        // What DeviceLibrary gives a file without embedded art: an album cover that doesn't exist.
        artworkUri = Uri.parse("content://media/external/audio/albumart/0"),
        durationMs = durationMs,
        identity = songIdentity(title, artist),
    )

    private fun state(song: PlayableSong, durationMs: Long, positionMs: Long, isPlaying: Boolean = true) =
        PlayerState(current = song, isPlaying = isPlaying, positionMs = positionMs, durationMs = durationMs, queue = listOf(song))

    private fun both(name: String, state: PlayerState) {
        graph.player = IdlePlayer(state)
        snap("nowplaying-$name", settleMs = 2500) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) {
                Box(Modifier.fillMaxSize()) {
                    NowPlayingScreen(onClose = {})
                    MiniPlayer(onExpand = {}, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    @Test fun ringtone() {
        val ring = song("Over the Horizon", "<unknown>", durationMs = 3_000, album = "Ringtones")
        both("ringtone", state(ring, durationMs = 3_000, positionMs = 1_400))
    }

    @Test fun ringtonePastItsEnd() {
        // The position can run a little past a tiny file's reported length.
        val ring = song("Whistle", "Samsung", durationMs = 1_200)
        both("ringtone-past-end", state(ring, durationMs = 1_200, positionMs = 2_500))
    }

    @Test fun unknownLength() {
        val stream = song("Live Stream", "Unknown artist", durationMs = null)
        both("unknown-length", state(stream, durationMs = C.TIME_UNSET, positionMs = C.TIME_UNSET))
    }

    @Test fun zeroLength() {
        val zero = song("Silence", "Unknown artist", durationMs = 0)
        both("zero-length", state(zero, durationMs = 0, positionMs = 0, isPlaying = false))
    }

    @Test fun hugeLength() {
        val long = song("Rain Sounds, Ten Hours", "Nature", durationMs = 36_000_000)
        both("huge-length", state(long, durationMs = Long.MAX_VALUE, positionMs = 35_999_000))
    }

    @Test fun noNames() {
        val blank = song("", "", durationMs = 3_000)
        both("no-names", state(blank, durationMs = 3_000, positionMs = -200))
    }

    /**
     * The width most Samsung Galaxy phones report at their default display size (1080 pixels
     * at 3x), and narrower, as a larger display size or a Flip's cover screen gives. Now
     * Playing's transport used to overflow its button group here and crash on opening.
     */
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun galaxyWidth() {
        val ring = song("Over the Horizon", "<unknown>", durationMs = 3_000, album = "Ringtones")
        both("galaxy-360dp", state(ring, durationMs = 3_000, positionMs = 1_400))
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun narrowestWidth() {
        val song = song("A Much Longer Song Title Than Usual", "Someone With A Long Name", durationMs = 214_000)
        both("narrow-320dp", state(song, durationMs = 214_000, positionMs = 74_000))
    }

    /** The largest font size and display size Android offers together. */
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun largestText() {
        RuntimeEnvironment.setFontScale(2f)
        val song = song("Undertow", "Low Tide Club", durationMs = 214_000)
        both("narrow-largest-text", state(song, durationMs = 214_000, positionMs = 74_000))
    }

    /** The report's path: the ringtone playing, the player opened from the shell, then scrubbed. */
    @Test fun openFromShellAndScrub() {
        val ring = song("Over the Horizon", "<unknown>", durationMs = 3_000, album = "Ringtones")
        val player = IdlePlayer(state(ring, durationMs = 3_000, positionMs = 1_400))
        graph.player = player
        val navigator = Navigator()
        snap("nowplaying-shell-ringtone", settleMs = 2500) { AppShell(navigator) }
        navigator.nowPlayingOpen = true
        settle(1500)
        for (length in listOf(3_000L, 0L, C.TIME_UNSET, Long.MAX_VALUE)) {
            (player.state as MutableStateFlow).value = state(ring.copy(durationMs = length.takeIf { it > 0 }), durationMs = length, positionMs = 900)
            settle(300)
            // Only a known length can be sought along; otherwise there's no seek bar to drag.
            if (length == 3_000L) {
                compose.onNodeWithContentDescription("Seek").performTouchInput { swipeRight() }
                compose.onNodeWithContentDescription("Seek").performTouchInput { swipeLeft() }
            } else {
                compose.onNodeWithContentDescription("Length unknown").assertExists()
            }
            settle(300)
        }
        // The ringtone ends and nothing follows: the player closes itself.
        (player.state as MutableStateFlow).value = PlayerState()
        settle(1000)
    }
}

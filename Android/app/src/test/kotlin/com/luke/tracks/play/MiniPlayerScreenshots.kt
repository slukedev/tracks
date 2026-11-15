package com.luke.tracks.play

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.data.MiniPlayerColors
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.navigation.AppShell
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.nowplaying.MiniPlayer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import uniffi.tracks_engine.songIdentity

/**
 * The mini player playing and paused, with a title too long for it and with no artist, alone
 * and in the app's frame (at the top of Play and scrolled, with the bar hidden), at a common
 * phone width and at 360dp, where everything still has to fit.
 */
abstract class MiniPlayerShots(dark: Boolean, private val width: Int) : ScreenshotTest(dark) {
    protected fun song(title: String, artist: String) = PlayableSong(
        mediaId = "local:${title.hashCode()}",
        uri = Uri.parse("content://media/external/audio/media/1"),
        title = title,
        artistName = artist,
        albumTitle = title,
        artworkUri = null,
        durationMs = 214_000,
        identity = songIdentity(title, artist),
    )

    protected fun shot(name: String, song: PlayableSong, isPlaying: Boolean, positionMs: Long) {
        graph.player = IdlePlayer(
            PlayerState(current = song, isPlaying = isPlaying, positionMs = positionMs, durationMs = 214_000, queue = listOf(song)),
        )
        snap("miniplayer-$name-$width") {
            CompositionLocalProvider(LocalNavigator provides Navigator()) {
                Box(Modifier.fillMaxSize()) { MiniPlayer(onExpand = {}, modifier = Modifier.align(Alignment.BottomCenter)) }
            }
        }
    }

    private fun shell(isPlaying: Boolean) {
        withSampleHistory()
        val library = SampleLibrary().also { graph.library = it }
        val queue = library.songs.take(8)
        graph.player = IdlePlayer(
            PlayerState(
                current = queue[1], isPlaying = isPlaying, positionMs = 120_000, durationMs = 210_000,
                queue = queue, index = 1, origin = QueueOrigin("On Repeat", QueueOrigin.Kind.Mix),
            ),
        )
        val name = "miniplayer-shell-${if (isPlaying) "playing" else "paused"}-$width"
        snap(name, settleMs = 3000) { AppShell(Navigator()) }
        val vertical = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        repeat(2) {
            compose.onAllNodes(vertical).onFirst().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = top + height * 0.15f) }
            settle(400)
        }
        settle(1200)
        compose.onRoot().captureRoboImage("build/screenshots/$name-scrolled-${if (dark) "dark" else "light"}.png")
    }

    @Test fun playing() = shot("playing", song("Open Road", "Juniper Lane"), isPlaying = true, positionMs = 120_000)

    @Test fun paused() = shot("paused", song("Open Road", "Juniper Lane"), isPlaying = false, positionMs = 120_000)

    @Test fun longTitle() = shot(
        "long",
        song("The Long Way Back Home (Live at the Royal Albert Hall, 2019 Remaster)", "The Extraordinarily Long Named Orchestra and Choir"),
        isPlaying = true,
        positionMs = 180_000,
    )

    @Test fun noArtist() = shot("no-artist", song("Over the Horizon", "<unknown>"), isPlaying = false, positionMs = 30_000)

    @Test fun shellPlaying() = shell(isPlaying = true)

    @Test fun shellPaused() = shell(isPlaying = false)
}

@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class MiniPlayerScreenshots(dark: Boolean) : MiniPlayerShots(dark, 412) {
    /** Set to the app's colors in Settings: this cover tints green (see no-artist), here it doesn't. */
    @Test fun appColors() {
        runBlocking { graph.settings.update { it.copy(miniPlayerColors = MiniPlayerColors.App) } }
        try {
            shot("app-colors", song("Over the Horizon", "Juniper Lane"), isPlaying = true, positionMs = 120_000)
        } finally {
            runBlocking { graph.settings.update { it.copy(miniPlayerColors = MiniPlayerColors.Album) } }
        }
    }
}

@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class MiniPlayerNarrowScreenshots(dark: Boolean) : MiniPlayerShots(dark, 360)

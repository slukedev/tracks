package com.luke.tracks.play

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.nowplaying.MiniPlayer
import org.junit.Test
import org.robolectric.annotation.Config
import uniffi.tracks_engine.songIdentity

/**
 * The mini player alone, playing and paused, with a title too long for the card and with no
 * artist, at a common phone width and at 360dp, where everything still has to fit.
 */
abstract class MiniPlayerShots(dark: Boolean, private val width: Int) : ScreenshotTest(dark) {
    private fun song(title: String, artist: String) = PlayableSong(
        mediaId = "local:${title.hashCode()}",
        uri = Uri.parse("content://media/external/audio/media/1"),
        title = title,
        artistName = artist,
        albumTitle = title,
        artworkUri = null,
        durationMs = 214_000,
        identity = songIdentity(title, artist),
    )

    private fun shot(name: String, song: PlayableSong, isPlaying: Boolean, positionMs: Long) {
        graph.player = IdlePlayer(
            PlayerState(current = song, isPlaying = isPlaying, positionMs = positionMs, durationMs = 214_000, queue = listOf(song)),
        )
        snap("miniplayer-$name-$width") {
            CompositionLocalProvider(LocalNavigator provides Navigator()) {
                Box(Modifier.fillMaxSize()) { MiniPlayer(onExpand = {}, modifier = Modifier.align(Alignment.BottomCenter)) }
            }
        }
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
}

@Config(qualifiers = "w412dp-h200dp-xxhdpi")
class MiniPlayerScreenshots(dark: Boolean) : MiniPlayerShots(dark, 412)

@Config(qualifiers = "w360dp-h200dp-xxhdpi")
class MiniPlayerNarrowScreenshots(dark: Boolean) : MiniPlayerShots(dark, 360)

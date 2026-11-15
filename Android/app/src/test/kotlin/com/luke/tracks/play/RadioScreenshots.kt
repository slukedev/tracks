package com.luke.tracks.play

import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.NoRadio
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RadioState
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.SettingsRoute
import com.luke.tracks.ui.nowplaying.NowPlayingScreen
import com.luke.tracks.ui.nowplaying.QueueContent
import com.luke.tracks.ui.settings.SettingsPageScreen
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import uniffi.tracks_engine.RadioDiscovery
import uniffi.tracks_engine.RadioReason
import uniffi.tracks_engine.RadioSteering

/** Tracks Radio playing, with why each song came up, and its settings, light and dark. */
class RadioScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun playingRadio(steering: RadioSteering? = null): PlayerState {
        val library = GenreLibrary().also { graph.library = it }
        val songs = library.songs
        // You queued one song; the station's next pick waits behind it.
        val queue = listOf(songs[4], songs[11], songs[23], songs[30])
        graph.radio = NoRadio(
            RadioState(
                picks = mapOf(
                    songs[4].mediaId to RadioReason.Probing,
                    songs[11].mediaId to RadioReason.GoesWith(songs[4].title),
                    songs[30].mediaId to RadioReason.NewFromYourArtist,
                ),
                steering = steering,
            ),
        )
        return PlayerState(
            current = songs[11],
            isPlaying = true,
            positionMs = 52_000,
            durationMs = 231_000,
            queue = queue,
            index = 1,
            origin = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio),
            keepProgress = 0.4f,
        )
    }

    @Test fun nowPlaying() {
        withSampleHistory()
        graph.player = IdlePlayer(playingRadio())
        snap("radio-nowplaying", settleMs = 2500) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { NowPlayingScreen(onClose = {}) }
        }
    }

    @Test fun queue() {
        withSampleHistory()
        val state = playingRadio(RadioSteering.AwayFromGenre("Alternative"))
        graph.player = IdlePlayer(state)
        snap("radio-queue") {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { QueueContent(state, graph.player, onTuneRadio = {}) }
        }
    }

    @Test fun settings() {
        withSampleHistory()
        runBlocking { graph.settings.update { it.copy(radioDiscovery = RadioDiscovery.ADVENTUROUS, radioGenres = setOf("Alternative"), radioOldFavorites = true) } }
        try {
            snap("radio-settings") {
                CompositionLocalProvider(LocalNavigator provides Navigator().apply { open(SettingsRoute) }) { SettingsPageScreen("radio") }
            }
        } finally {
            runBlocking { graph.settings.update { it.copy(radioDiscovery = RadioDiscovery.BALANCED, radioGenres = emptySet(), radioOldFavorites = false) } }
        }
    }

    /** The whole settings page, past the fold, as it opens. */
    @Test @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    fun settingsFull() {
        withSampleHistory()
        snap("full-radio-settings") {
            CompositionLocalProvider(LocalNavigator provides Navigator().apply { open(SettingsRoute) }) { SettingsPageScreen("radio") }
        }
    }

    @Test fun genres() {
        withSampleHistory()
        GenreLibrary().also { graph.library = it }
        runBlocking { graph.settings.update { it.copy(radioGenres = setOf("Alternative")) } }
        try {
            snap("radio-genres", settleMs = 2500) {
                CompositionLocalProvider(LocalNavigator provides Navigator().apply { open(SettingsRoute) }) { SettingsPageScreen("radio-genres") }
            }
        } finally {
            runBlocking { graph.settings.update { it.copy(radioGenres = emptySet()) } }
        }
    }
}

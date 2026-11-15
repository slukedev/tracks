package com.luke.tracks.play

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.data.SampleData
import com.luke.tracks.discover.Discovery
import com.luke.tracks.discover.FakeDiscovery
import com.luke.tracks.discover.FetchSources
import com.luke.tracks.discover.LocalDiscovery
import com.luke.tracks.library.LibrarySnapshot
import com.luke.tracks.library.MusicLibrary
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.navigation.AppShell
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.nowplaying.MiniPlayer
import com.luke.tracks.ui.nowplaying.NowPlayingScreen
import com.luke.tracks.ui.nowplaying.QueueContent
import com.luke.tracks.ui.play.MixScreen
import com.luke.tracks.ui.play.PlayScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import uniffi.tracks_engine.songIdentity

/**
 * A library holding most of the sample history's songs, so mixes show both what plays here and
 * what has to open elsewhere: roughly one song in five is missing.
 */
class SampleLibrary : MusicLibrary {
    private val byIdentity: Map<String, PlayableSong> = SampleData.plays(days = 300)
        .distinctBy { it.title to it.artistName }
        .mapIndexedNotNull { i, play ->
            val identity = songIdentity(play.title, play.artistName)
            if (Math.floorMod(identity.hashCode(), 5) == 0) return@mapIndexedNotNull null
            identity to PlayableSong(
                mediaId = "local:$i",
                uri = Uri.parse("content://media/external/audio/media/$i"),
                title = play.title,
                artistName = play.artistName,
                albumTitle = play.albumTitle,
                artworkUri = null,
                durationMs = play.durationMs,
                identity = identity,
            )
        }.toMap()

    override val snapshot: StateFlow<LibrarySnapshot> = MutableStateFlow(LibrarySnapshot(songs = byIdentity.values.toList()))
    override suspend fun refresh() = Unit
    override fun find(identity: String): PlayableSong? = byIdentity[identity]
    override suspend fun search(text: String): List<PlayableSong> = emptyList()

    val songs: List<PlayableSong> get() = byIdentity.values.toList()
}

class PlayScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun withLibrary(): SampleLibrary = SampleLibrary().also { graph.library = it }

    private fun playing(library: SampleLibrary, isPlaying: Boolean = true): PlayerState {
        val queue = library.songs.take(12)
        return PlayerState(
            current = queue[2],
            isPlaying = isPlaying,
            positionMs = 74_000,
            durationMs = 214_000,
            queue = queue,
            index = 2,
            shuffle = true,
            origin = QueueOrigin("On Repeat", QueueOrigin.Kind.Mix),
            keepProgress = 0.62f,
            kept = false,
        )
    }

    @Test
    fun play() {
        withSampleHistory()
        withLibrary()
        snap("play", settleMs = 3000) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { PlayScreen() }
        }
    }

    /** The whole page, past the fold, with suggestions and nothing that could get them. */
    @Test
    @Config(qualifiers = "w412dp-h3200dp-xxhdpi")
    fun playFull() = suggesting("play-full", FakeDiscovery(FetchSources()))

    /** Suggested songs under the crate: previews and Open in another app, no Get. */
    @Test fun playSuggested() = suggesting("play-suggested", FakeDiscovery(FetchSources()))

    /** With a server that fetches: Get buttons on the songs it can find. */
    @Test fun playSuggestedWithServer() = suggesting("play-suggested-server", FakeDiscovery(FetchSources(octo = listOf("Octo at home"))))

    @Test fun playSuggestedLoading() = suggesting("play-suggested-loading", FakeDiscovery(FetchSources(), waitForever = true))

    /** Offline with nothing kept, or nothing found: the sections stay away. */
    @Test fun playSuggestedOffline() = suggesting("play-suggested-offline", FakeDiscovery(FetchSources(), noSuggestions = true))

    /** Further down: suggested artists, recently played, your artists. */
    @Test
    @Config(qualifiers = "w412dp-h1900dp-xxhdpi")
    fun playSuggestedArtists() = suggesting("play-suggested-artists", FakeDiscovery(FetchSources(lidarr = true)))

    private fun suggesting(name: String, discovery: Discovery) {
        withSampleHistory()
        withLibrary()
        snap(name, settleMs = 3000) {
            CompositionLocalProvider(LocalNavigator provides Navigator(), LocalDiscovery provides discovery) { PlayScreen() }
        }
    }

    @Test
    fun playEmpty() {
        runBlocking { graph.history.replaceAll(emptyList()) }
        snap("play-empty") {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { PlayScreen() }
        }
    }

    @Test
    fun mix() {
        withSampleHistory()
        withLibrary()
        snap("mix", settleMs = 3000) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { MixScreen("onRepeat") }
        }
    }

    @Test
    fun nowPlaying() {
        withSampleHistory()
        val library = withLibrary()
        graph.player = IdlePlayer(playing(library))
        snap("nowplaying", settleMs = 2500) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { NowPlayingScreen(onClose = {}) }
        }
    }

    @Test
    fun nowPlayingPaused() {
        withSampleHistory()
        val library = withLibrary()
        graph.player = IdlePlayer(playing(library, isPlaying = false).copy(current = library.songs[7], kept = true, keepProgress = 1f))
        snap("nowplaying-paused", settleMs = 2500) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { NowPlayingScreen(onClose = {}) }
        }
    }

    @Test
    fun queue() {
        withSampleHistory()
        val library = withLibrary()
        val state = playing(library)
        graph.player = IdlePlayer(state)
        snap("queue") {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { QueueContent(state, graph.player) }
        }
    }

    @Test
    fun shellWithMiniPlayer() {
        withSampleHistory()
        val library = withLibrary()
        graph.player = IdlePlayer(playing(library))
        snap("shell-miniplayer", settleMs = 3000) { AppShell(Navigator()) }
    }

    /** The whole mix page, past the fold. */
    @Test
    @Config(qualifiers = "w412dp-h3400dp-xxhdpi")
    fun mixFull() {
        withSampleHistory()
        withLibrary()
        snap("full-mix", settleMs = 3000) {
            CompositionLocalProvider(LocalNavigator provides Navigator()) { MixScreen("onRepeat") }
        }
    }
}

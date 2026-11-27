package com.luke.tracks.play

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.library.FakeLibrary
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.sources.FakeSources
import com.luke.tracks.sources.PlayFromOrder
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.SourceStatus
import com.luke.tracks.sources.SourcesRegistry
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.nowplaying.NowPlayingScreen
import com.luke.tracks.ui.nowplaying.QueueContent
import com.luke.tracks.ui.sources.AddOption
import com.luke.tracks.ui.sources.DeviceSourcePage
import com.luke.tracks.ui.sources.LocalSources
import com.luke.tracks.ui.sources.ServerRoute
import com.luke.tracks.ui.sources.ServerSourcePageContent
import com.luke.tracks.ui.sources.SourcesPageContent
import org.junit.Test
import org.robolectric.annotation.Config

/** Sources, adding one, a server's page, and what sources change on Now Playing and Up next. */
class SourceScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private val all = listOf(FakeSources.device, FakeSources.home, FakeSources.studio, FakeSources.spotify)

    @Composable
    private fun Frame(registry: SourcesRegistry? = null, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNavigator provides Navigator(), LocalSources provides registry) { content() }
    }

    private fun page(name: String, sources: List<Source>, adding: Boolean = false) = snap(name) {
        Frame {
            SourcesPageContent(
                sources = sources,
                playFrom = PlayFromOrder.Standard.arranged(all.map { PlayFromPlace.From(it.id) }),
                options = listOf(
                    AddOption("server", "Server", SourceKind.Server) {},
                    AddOption("spotify", "Spotify", SourceKind.Spotify, subtitle = "Needs Premium") {},
                ),
                onOpen = {},
                onRemove = {},
                onReorder = {},
                initiallyAdding = adding,
            )
        }
    }

    @Test fun sources() = page("sources", all)

    /** The largest text Android offers: names truncate, status words stay readable. */
    @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi", fontScale = 2.0f)
    fun sourcesLargeText() = page("sources-fontscale2", all)

    /** Right to left: the page mirrors; the numbers of Play From stay with their rows. */
    @Test @Config(qualifiers = "ar-rXB-ldrtl-w412dp-h915dp-xxhdpi")
    fun sourcesRtl() = page("sources-rtl", all)

    /** A tablet: the expanded rail with labels, and Play in the wide layout. */
    @Test @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tablet() {
        withSampleHistory()
        graph.library = SampleLibrary()
        snap("shell-tablet", settleMs = 4000) { com.luke.tracks.ui.navigation.AppShell(Navigator()) }
    }
    @Test fun sourcesOne() = page("sources-one", listOf(FakeSources.device))
    @Test fun addSource() = page("add-source", all, adding = true)

    @Test fun serverPage() = snap("source-server") {
        Frame {
            ServerSourcePageContent(
                source = FakeSources.home,
                addresses = listOf(
                    ServerRoute("192.168.1.20:4747", "Home network"),
                    ServerRoute("home.tail1234.ts.net", "Tailscale"),
                    ServerRoute("home.example.net", "Internet"),
                ),
                showBack = true,
                onRename = {},
                onCheck = {},
                onFix = null,
                onRemove = {},
                onLinkTv = {},
            )
        }
    }

    @Test fun offlineServerPage() = snap("source-server-offline") {
        Frame {
            ServerSourcePageContent(FakeSources.studio, listOf(ServerRoute("192.168.1.20:4533", "Home network")), true, {}, {}, null, {})
        }
    }

    @Test fun devicePage() = snap("source-device") {
        Frame { DeviceSourcePage(FakeSources.device) }
    }

    @Test fun deviceOff() = snap("source-device-off") {
        Frame { DeviceSourcePage(FakeSources.device.copy(status = SourceStatus.Off)) }
    }

    /** A queue that mixes your files and two servers, one of them offline. */
    private fun mixedQueue(): PlayerState {
        val songs = FakeLibrary.index.snapshot.songs.take(9)
        val queue = songs.mapIndexed { i, song: PlayableSong ->
            when (i % 3) {
                1 -> song.copy(mediaId = "subsonic:s1:$i")
                2 -> song.copy(mediaId = "subsonic:s2:$i")
                else -> song
            }
        }
        return PlayerState(
            current = queue[1], isPlaying = true, positionMs = 74_000, durationMs = 214_000,
            queue = queue, index = 1, origin = QueueOrigin("On Repeat", QueueOrigin.Kind.Mix), keepProgress = 0.4f,
        )
    }

    @Test fun nowPlaying() {
        withSampleHistory()
        val state = mixedQueue()
        graph.player = IdlePlayer(state)
        snap("nowplaying-source", settleMs = 2500) {
            Frame(FakeSources.registry(*all.toTypedArray())) { NowPlayingScreen(onClose = {}) }
        }
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun nowPlayingTablet() {
        withSampleHistory()
        graph.player = IdlePlayer(mixedQueue())
        snap("nowplaying-tablet", settleMs = 2500) {
            Frame(FakeSources.registry(*all.toTypedArray())) { NowPlayingScreen(onClose = {}) }
        }
    }

    @Test fun upNext() {
        withSampleHistory()
        val state = mixedQueue()
        graph.player = IdlePlayer(state)
        snap("upnext-sources") {
            Frame(FakeSources.registry(*all.toTypedArray())) { QueueContent(state, graph.player) }
        }
    }
}

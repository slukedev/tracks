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
    private fun shell(tab: Tab, name: String, swipes: Int, keepLibrary: Boolean = false, route: (() -> com.luke.tracks.ui.navigation.Route)? = null) {
        withSampleHistory()
        val library = SampleLibrary().also { graph.library = it }
        if (tab == Tab.Library && !keepLibrary) {
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
        val navigator = Navigator(start = tab).apply { route?.let { open(it()) } }
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

    private fun topArtist() = kotlinx.coroutines.runBlocking {
        graph.engine.read { artistChart(com.luke.tracks.engine.Engine.allTime, null, uniffi.tracks_engine.SourceFilter.ALL, 1u).rows.first().artist }
    }
    private fun topAlbum() = kotlinx.coroutines.runBlocking {
        graph.engine.read { albumChart(com.luke.tracks.engine.Engine.allTime, null, uniffi.tracks_engine.SourceFilter.ALL, 1u).rows.first().album }
    }

    @Test fun artist() = shell(Tab.Summary, "scroll-artist", 2) {
        topArtist().let { com.luke.tracks.ui.navigation.ArtistRoute(it.identity, it.name) }
    }

    @Test fun album() = shell(Tab.Summary, "scroll-album", 2) {
        topAlbum().let { com.luke.tracks.ui.navigation.AlbumRoute(it.identity, it.title, it.artistName) }
    }

    /** The app's own library in sample-music mode, as it runs with sample data on. */
    private fun realLibrary(): com.luke.tracks.library.CombinedLibrary {
        val plain = object : com.luke.tracks.library.SecretBox {
            override fun seal(plain: String) = plain
            override fun open(sealed: String) = sealed
        }
        val library = com.luke.tracks.library.CombinedLibrary(
            context = app,
            scope = graph.scope,
            device = com.luke.tracks.library.DeviceLibrary(app, graph.scope),
            servers = com.luke.tracks.library.ServerLibrary(app, graph.settings, plain, graph.scope),
            engine = graph.engine,
            sampleData = true,
        ).also { it.start() }
        repeat(200) {
            if (library.index.value.snapshot.albums.isNotEmpty()) return library
            Thread.sleep(100)
        }
        error("The sample library didn't fill")
    }

    @Test fun libraryAlbum() = shell(Tab.Library, "scroll-library-album", 2, keepLibrary = true) {
        val library = realLibrary().also { graph.library = it }
        val album = library.index.value.snapshot.albums.maxBy { it.songs.size }
        com.luke.tracks.ui.navigation.LibraryAlbumRoute(album.id)
    }

    @Test fun libraryArtist() = shell(Tab.Library, "scroll-library-artist", 2, keepLibrary = true) {
        val library = realLibrary().also { graph.library = it }
        val artist = library.index.value.snapshot.artists.maxBy { it.albums.size }
        com.luke.tracks.ui.navigation.LibraryArtistRoute(artist.id)
    }
}

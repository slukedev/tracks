package com.luke.tracks.tv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import com.luke.tracks.discover.LocalServerPicks
import com.luke.tracks.library.FakeLibrary
import com.luke.tracks.library.LibrarySnapshot
import com.luke.tracks.play.FakeServerPicks
import com.luke.tracks.play.SampleLibrary
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.insights.AllTime
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.MixRoute
import com.luke.tracks.ui.navigation.Route
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.play.loadFeed
import com.luke.tracks.ui.tv.TvContentScreens
import com.luke.tracks.ui.tv.detail.TvDetailSkeleton
import com.luke.tracks.ui.tv.library.TvLibraryContent
import com.luke.tracks.ui.tv.library.TvLibraryTab
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.navigation.TvScreens
import com.luke.tracks.ui.tv.navigation.TvShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Play, Library, Summary, Charts, History, Search and their detail pages, inside the shell,
 * from the sample history and a sample library, in their first-run, loading and full states.
 */
class TvContentScreenshots(dark: Boolean) : TvScreenshotTest(dark) {
    private val play = TvScreens.play
    private val libraryPage = TvScreens.library
    private val summary = TvScreens.summary
    private val charts = TvScreens.charts
    private val history = TvScreens.history
    private val search = TvScreens.search
    private val album = TvScreens.album
    private val artist = TvScreens.artist
    private val song = TvScreens.song
    private val mix = TvScreens.mix
    private val libraryAlbum = TvScreens.libraryAlbum
    private val libraryArtist = TvScreens.libraryArtist
    private val catalogAlbum = TvScreens.catalogAlbum
    private val catalogArtist = TvScreens.catalogArtist

    @Before fun install() = TvContentScreens.install()

    // The slots are global: put back what the other suites expect.
    @After fun restore() {
        TvScreens.play = play
        TvScreens.library = libraryPage
        TvScreens.summary = summary
        TvScreens.charts = charts
        TvScreens.history = history
        TvScreens.search = search
        TvScreens.album = album
        TvScreens.artist = artist
        TvScreens.song = song
        TvScreens.mix = mix
        TvScreens.libraryAlbum = libraryAlbum
        TvScreens.libraryArtist = libraryArtist
        TvScreens.catalogAlbum = catalogAlbum
        TvScreens.catalogArtist = catalogArtist
    }

    private val idle = MutableStateFlow(PlayerState())

    private fun library(): SampleLibrary = SampleLibrary().also { graph.library = it }

    /** The shell on [destination], optionally with [route] opened on it, with Trackside's shelves. */
    private fun shell(
        destination: TvDestination,
        route: Route? = null,
        picks: Boolean = true,
        settleMs: Long = 3000,
        navigator: TvNavigator = TvNavigator(destination),
    ): TvNavigator {
        val lib = graph.library as? SampleLibrary
        route?.let(navigator::open)
        show(settleMs = settleMs) {
            if (picks && lib != null) {
                CompositionLocalProvider(LocalServerPicks provides FakeServerPicks(lib, fetches = false)) { TvShell(navigator, idle) }
            } else {
                TvShell(navigator, idle)
            }
        }
        return navigator
    }

    @Test fun play() {
        withSampleHistory()
        library()
        shell(TvDestination.Play)
        capture("play")
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        capture("play-shelves")
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-tvdpi")
    fun play720p() {
        withSampleHistory()
        library()
        shell(TvDestination.Play)
        capture("play-720p")
    }

    /** A fresh TV: no plays, nothing linked. Tracks Radio alone, focused. */
    @Test fun playFirstRun() {
        shell(TvDestination.Play, picks = false)
        capture("play-first-run")
    }

    @Test fun playLoading() {
        withSampleHistory()
        library()
        shell(TvDestination.Play, settleMs = 0)
        capture("play-loading")
    }

    /** Into an album from Play and back: focus returns to the card you left. */
    @Test fun backFromDetailRestoresFocus() {
        withSampleHistory()
        library()
        shell(TvDestination.Play)
        press(listOf(Key.DirectionDown, Key.DirectionRight))
        val before = focusedLabel()
        press(Key.DirectionCenter)
        settle(1500)
        back(settleMs = 1500)
        assertEquals(before, focusedLabel())
    }

    private fun focusedLabel(): String? =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    @Test fun mix() {
        withSampleHistory()
        library()
        val mix = runBlocking { loadFeed(graph, emptySet()) }.let { feed -> feed.shelfMixes.firstOrNull() ?: feed.crate.first() }
        shell(TvDestination.Play, MixRoute(mix.id))
        capture("mix")
    }

    @Test fun album() {
        withSampleHistory()
        library()
        val album = runBlocking { AllTime.albums(graph.engine).first() }
        shell(TvDestination.Summary, AlbumRoute(album.identity, album.title, album.artistName))
        capture("album")
    }

    @Test fun artist() {
        withSampleHistory()
        library()
        val artist = runBlocking { AllTime.artists(graph.engine).first() }
        shell(TvDestination.Summary, ArtistRoute(artist.identity, artist.name))
        capture("artist")
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        capture("artist-songs")
    }

    @Test fun song() {
        withSampleHistory()
        library()
        val song = runBlocking { AllTime.songs(graph.engine).first() }
        shell(TvDestination.History, SongRoute(song.identity, song.title, song.artistName))
        capture("song")
    }

    @Test fun libraryAlbumAndArtist() {
        withSampleHistory()
        val lib = library()
        val album = lib.index.value.snapshot.albums.first()
        shell(TvDestination.Library, LibraryAlbumRoute(album.id))
        capture("library-album")
    }

    @Test fun libraryArtist() {
        withSampleHistory()
        val lib = library()
        val artist = lib.index.value.snapshot.artists.maxBy { it.albums.size }
        shell(TvDestination.Library, LibraryArtistRoute(artist.id))
        capture("library-artist")
    }

    @Test fun detailLoading() {
        show { TvDetailSkeleton() }
        capture("detail-loading")
    }

    @Test fun libraryTabs() {
        withSampleHistory()
        library()
        shell(TvDestination.Library)
        capture("library-albums")
        press(Key.DirectionUp)
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        capture("library-artists")
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        capture("library-songs")
    }

    @Test fun libraryNotLinked() {
        show { TvLibraryContent(LibrarySnapshot(), linked = false, onLink = {}) }
        capture("library-not-linked")
    }

    @Test fun libraryLoading() {
        show { TvLibraryContent(LibrarySnapshot(isLoading = true), linked = true, onLink = {}) }
        capture("library-loading")
    }

    @Test fun libraryEmpty() {
        show { TvLibraryContent(LibrarySnapshot(), linked = true, onLink = {}) }
        capture("library-empty")
    }

    /** A big library: every album of the fake library, songs two to a row. */
    @Test fun libraryHuge() {
        show { TvLibraryContent(FakeLibrary.index.snapshot, linked = true, onLink = {}, initialTab = TvLibraryTab.Songs) }
        capture("library-huge-songs")
    }

    @Test fun summary() {
        withSampleHistory()
        library()
        shell(TvDestination.Summary)
        capture("summary")
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        capture("summary-shelves")
    }

    @Test fun summaryFirstRun() {
        shell(TvDestination.Summary, picks = false)
        capture("summary-first-run")
    }

    @Test fun charts() {
        withSampleHistory()
        library()
        shell(TvDestination.Charts)
        capture("charts")
    }

    @Test fun chartsFirstRun() {
        shell(TvDestination.Charts, picks = false)
        capture("charts-first-run")
    }

    @Test fun history() {
        withSampleHistory()
        library()
        shell(TvDestination.History)
        capture("history")
    }

    @Test fun historyFirstRun() {
        shell(TvDestination.History, picks = false)
        capture("history-first-run")
    }

    @Test fun search() {
        withSampleHistory()
        library()
        val navigator = TvNavigator(TvDestination.Search)
        navigator.openSearch("ma")
        shell(TvDestination.Search, navigator = navigator)
        capture("search-results")
    }

    @Test fun searchBlankAndNothing() {
        withSampleHistory()
        library()
        shell(TvDestination.Search)
        capture("search-blank")
    }

    @Test fun searchNoResults() {
        withSampleHistory()
        library()
        val navigator = TvNavigator(TvDestination.Search)
        navigator.openSearch("zzqx")
        shell(TvDestination.Search, navigator = navigator)
        capture("search-none")
    }
}

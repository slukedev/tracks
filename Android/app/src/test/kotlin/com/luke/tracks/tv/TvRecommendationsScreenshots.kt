package com.luke.tracks.tv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import com.luke.tracks.discover.LocalServerPicks
import com.luke.tracks.library.trackside.TracksideCatalogArtistRef
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogPage
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.play.FakeServerPicks
import com.luke.tracks.play.SampleLibrary
import com.luke.tracks.play.SampleRecommendations
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.navigation.ServiceItemRoute
import com.luke.tracks.ui.services.LocalCatalogRecommendations
import com.luke.tracks.ui.tv.detail.TvServiceItemScreen
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.navigation.TvScreens
import com.luke.tracks.ui.tv.navigation.TvShell
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test

/** Apple Music's rows on TV Play, and the page a pick opens. */
class TvRecommendationsScreenshots(dark: Boolean) : TvScreenshotTest(dark) {
    private val idle = MutableStateFlow(PlayerState())
    private val standard = TvScreens.serviceItem

    @After fun restore() {
        TvScreens.serviceItem = standard
    }

    private fun shell(navigator: TvNavigator = TvNavigator(TvDestination.Play), answers: Boolean = true) {
        withSampleHistory()
        val library = SampleLibrary().also { graph.library = it }
        val picks = FakeServerPicks(library, fetches = false)
        val recs = SampleRecommendations.loader(answers)
        show(settleMs = 3000) {
            CompositionLocalProvider(LocalServerPicks provides picks, LocalCatalogRecommendations provides recs) { TvShell(navigator, idle) }
        }
    }

    /** Down past the mixes and Picked for You, onto Made for You. */
    @Test fun play() {
        shell()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        capture("play-recs")
        press(listOf(Key.DirectionDown, Key.DirectionRight))
        capture("play-recs-stations")
    }

    @Test fun playLoading() {
        shell(answers = false)
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        capture("play-recs-loading")
    }

    private val album = TracksideCatalogItem(
        "album", "mc1.album.Glasshouse", SampleRecommendations.appleMusic.id, "Glasshouse Weather",
        artist = "Mara Solis", artists = listOf(TracksideCatalogArtistRef("Mara Solis", "mc1.artist.mara")), year = 2025, trackCount = 11,
    )

    private val tracks = listOf(
        "Morning Glass", "Weathervane", "Paper Lanterns", "Undertow", "The Long Way Round to Somewhere Quiet",
        "Second Light", "Harbour", "Signal Fires", "Overcast", "Glasshouse", "Last Train",
    ).mapIndexed { i, title ->
        TracksideCatalogTrack(
            "mc1.track.$i", SampleRecommendations.appleMusic.id, title, "Mara Solis",
            album = album.name, durationMs = 180_000L + i * 11_000L, trackNumber = i + 1, playable = if (i == 9) false else null,
        )
    }

    private fun page(route: ServiceItemRoute, page: TracksideCatalogPage, name: String) {
        TvScreens.serviceItem = { TvServiceItemScreen(it) { page } }
        val navigator = TvNavigator(TvDestination.Play).also { it.open(route) }
        shell(navigator)
        capture(name)
    }

    @Test fun albumPage() = page(
        ServiceItemRoute(SampleRecommendations.SERVER, album.ref, "album", album.name, album.artist),
        TracksideCatalogPage(album = album, tracks = tracks),
        "service-album",
    )

    @Test fun stationPage() {
        val station = TracksideCatalogItem("radio", "mc1.radio.mara", SampleRecommendations.appleMusic.id, "Mara Solis & Similar Artists Station")
        page(ServiceItemRoute(SampleRecommendations.SERVER, station.ref, "radio", station.name, null), TracksideCatalogPage(radio = station), "service-station")
    }
}

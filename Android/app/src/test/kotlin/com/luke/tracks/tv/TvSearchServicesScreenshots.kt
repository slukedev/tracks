package com.luke.tracks.tv

import androidx.compose.ui.input.key.Key
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogLibrary
import com.luke.tracks.library.trackside.TracksideCatalogPage
import com.luke.tracks.library.trackside.TracksideCatalogRecommendations
import com.luke.tracks.library.trackside.TracksideCatalogSearch
import com.luke.tracks.library.trackside.TracksideCatalogServices
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.play.SampleLibrary
import com.luke.tracks.play.SampleRecommendations
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.ServicePlace
import com.luke.tracks.sources.sources
import com.luke.tracks.streaming.CatalogClient
import com.luke.tracks.streaming.StreamingRepository
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.navigation.TvScreens
import com.luke.tracks.ui.tv.navigation.TvShell
import com.luke.tracks.ui.tv.search.TvSearchScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Test

/** TV Search with Apple Music's answer beside your library, in the Play From order. */
class TvSearchServicesScreenshots(dark: Boolean) : TvScreenshotTest(dark) {
    private val idle = MutableStateFlow(PlayerState())
    private val standard = TvScreens.search
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun restore() {
        TvScreens.search = standard
        scope.cancel()
    }

    private val service = SampleRecommendations.appleMusic

    private fun track(title: String, artist: String, album: String?, art: Boolean = true) =
        TracksideCatalogTrack("mc1.track.$title", service.id, title, artist, album = album, artworkId = "m$album".takeIf { art }, durationMs = 214_000)

    private fun item(kind: String, name: String, artist: String? = null, owner: String? = null, art: Boolean = true) =
        TracksideCatalogItem(kind, "mc1.$kind.$name", service.id, name, artist = artist, owner = owner, artworkId = "m$name".takeIf { art })

    private val answer = TracksideCatalogSearch(
        tracks = TracksideCatalogSearch.Section(listOf(
            track("Marigold", "Mara Solis", "Glasshouse Weather"),
            track("Manhattan Skyline at Four in the Morning, Extended Version", "The Long Names Society", "Night Shift"),
            track("Make Believe", "Juniper Lane", null, art = false),
            track("Maps", "Nova Harbor", "Lanterns on the Water"),
            track("Mayday", "Kestrel Park", "Static Bloom"),
        )),
        albums = TracksideCatalogSearch.Section(listOf(
            item("album", "Glasshouse Weather", artist = "Mara Solis"),
            item("album", "Manifesto for Quiet Rooms and Other Small Victories", artist = "The Long Names Society"),
            item("album", "Magnolia", artist = "Juniper Lane", art = false),
        )),
        artists = TracksideCatalogSearch.Section(listOf(item("artist", "Mara Solis"), item("artist", "Mavis Reed", art = false))),
        playlists = TracksideCatalogSearch.Section(listOf(item("playlist", "Mara Solis Essentials", owner = "Apple Music"))),
        radios = TracksideCatalogSearch.Section(listOf(item("radio", "Mara Solis Station"))),
    )

    private fun repository(answers: Boolean): StreamingRepository {
        val client = object : CatalogClient {
            override suspend fun access() = SampleRecommendations.server.access
            override suspend fun services() = TracksideCatalogServices(listOf(service))
            override suspend fun search(query: String, services: List<String>, limit: Int, types: List<String>) =
                if (answers) answer else awaitCancellation()
            override suspend fun item(ref: String, cursor: String?, limit: Int?) = TracksideCatalogPage()
            override suspend fun library(serviceId: String, kind: String, cursor: String?) = TracksideCatalogLibrary<TracksideCatalogItem>()
            override suspend fun libraryTracks(serviceId: String, cursor: String?) = TracksideCatalogLibrary<TracksideCatalogTrack>()
            override suspend fun recommendations(services: List<String>, limit: Int) = TracksideCatalogRecommendations()
        }
        return StreamingRepository(scope, clientFor = { client }).also { it.watchServers(flowOf(listOf(SampleRecommendations.SERVER to "Home"))) }
    }

    private fun search(answers: Boolean = true, appleMusicFirst: Boolean) {
        withSampleHistory()
        graph.library = SampleLibrary()
        val streaming = repository(answers)
        TvScreens.search = { TvSearchScreen(streaming) }
        val navigator = TvNavigator(TvDestination.Search).also { it.openSearch("ma") }
        show(settleMs = 3000) { TvShell(navigator, idle) }
        // Apple Music offered by the server, then dragged above it in Play From.
        if (appleMusicFirst) {
            val appleMusic = PlayFromPlace.Service(PlayFromPlace.APPLE_MUSIC)
            graph.sources.noteOffered(listOf(ServicePlace(PlayFromPlace.APPLE_MUSIC, service.name)), getsCopies = false)
            graph.sources.setPlayFrom(listOf(appleMusic) + graph.sources.arrangedPlayFrom().filter { it != appleMusic })
            settle(1000)
        }
    }

    /** Apple Music first in Play From: its songs, albums, artists, playlists and stations come before your library. */
    @Test fun appleMusicFirst() {
        search(appleMusicFirst = true)
        capture("search-am-first")
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        capture("search-am-first-down")
    }

    /** Your library first, as Play From has it out of the box: Apple Music follows. */
    @Test fun libraryFirst() {
        search(appleMusicFirst = false)
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        capture("search-am-after")
    }

    /** Apple Music still answering: its shelf's place is kept where its answer will go. */
    @Test fun appleMusicLoading() {
        search(answers = false, appleMusicFirst = true)
        capture("search-am-loading")
    }
}

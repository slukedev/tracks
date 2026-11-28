package com.luke.tracks.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.discover.LocalServerPicks
import com.luke.tracks.library.trackside.TracksideCatalogEntry
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogLibrary
import com.luke.tracks.library.trackside.TracksideCatalogPage
import com.luke.tracks.library.trackside.TracksideCatalogRecommendations
import com.luke.tracks.library.trackside.TracksideCatalogSearch
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideCatalogServices
import com.luke.tracks.library.trackside.TracksideCatalogShelf
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.streaming.CatalogClient
import com.luke.tracks.streaming.CatalogRecommendations
import com.luke.tracks.streaming.RecommendationShelf
import com.luke.tracks.streaming.StreamingServer
import com.luke.tracks.streaming.StreamingState
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.play.PlayScreen
import com.luke.tracks.ui.play.RecommendationShelfLoading
import com.luke.tracks.ui.play.RecommendationShelfRow
import com.luke.tracks.ui.services.LocalCatalogRecommendations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.annotation.Config

/** Apple Music's picks on a Trackside server, as Music Assistant would hand them over. */
object SampleRecommendations {
    const val SERVER = "s1"
    val appleMusic = TracksideCatalogService(
        id = "bm1zt0k6dk6mzz0n3vw5d",
        name = "Apple Music",
        logoKey = "apple_music",
        capabilities = listOf("library", "playlists", "radio", "recommendations", "search"),
        account = TracksideCatalogService.Account("ok"),
        quality = "lossless",
    )

    private fun item(kind: String, name: String, artist: String? = null, owner: String? = null, year: Int? = null, count: Int? = null) =
        TracksideCatalogEntry.Item(TracksideCatalogItem(kind, "mc1.$kind.$name", appleMusic.id, name, artist = artist, owner = owner, year = year, trackCount = count, artworkId = "m$name"))

    private fun song(title: String, artist: String, album: String) =
        TracksideCatalogEntry.Track(TracksideCatalogTrack("mc1.track.$title", appleMusic.id, title, artist, album = album, artworkId = "m$album", durationMs = 214_000))

    val shelves = listOf(
        TracksideCatalogShelf(
            "w01", appleMusic.id, "Made for You",
            items = listOf(
                item("playlist", "Favorites Mix", owner = "Apple Music"),
                item("playlist", "New Music Mix", owner = "Apple Music"),
                item("playlist", "Chill Mix", owner = "Apple Music"),
                item("playlist", "Get Up! Mix", owner = "Apple Music"),
                item("playlist", "Friends Mix", owner = "Apple Music"),
            ),
        ),
        TracksideCatalogShelf(
            "w02", appleMusic.id, "Stations for You",
            items = listOf(
                item("radio", "Mara Solis & Similar Artists Station"),
                item("radio", "Juniper Lane Station"),
                item("radio", "Discovery Station"),
                item("radio", "Nova Harbor & Similar Artists Station"),
            ),
        ),
        TracksideCatalogShelf(
            "w03", appleMusic.id, "Recommended Albums",
            items = listOf(
                item("album", "Glasshouse Weather", artist = "Mara Solis", year = 2025, count = 11),
                song("Low Tide Signals", "Nova Harbor", "Lanterns on the Water"),
                item("artist", "Juniper Lane"),
                item("album", "An Album With a Very Long Name That Will Not Fit on Its Card", artist = "The Long Names Society", year = 2024, count = 9),
                item("album", "Static Bloom", artist = "Kestrel Park", year = 2023, count = 10),
            ),
        ),
        // The service's own history: Play has Recently Played already, so this row never shows.
        TracksideCatalogShelf("w04", appleMusic.id, "Recently Played", items = listOf(item("album", "Old Favourite", artist = "Someone"))),
    )

    val server = StreamingServer(SERVER, "Home", TracksideClient.CatalogAccess.Play(cellular = true, recommends = true), StreamingServer.Status.Ok, listOf(appleMusic))

    /** A loader that answers at once, or never (its rows' places kept), already asked. */
    fun loader(answers: Boolean = true): CatalogRecommendations {
        val client = object : CatalogClient {
            override suspend fun recommendations(services: List<String>, limit: Int): TracksideCatalogRecommendations =
                if (answers) TracksideCatalogRecommendations(shelves = shelves) else awaitCancellation()
            override suspend fun access() = server.access
            override suspend fun services() = TracksideCatalogServices(listOf(appleMusic))
            override suspend fun search(query: String, services: List<String>, limit: Int, types: List<String>) = TracksideCatalogSearch()
            override suspend fun item(ref: String, cursor: String?, limit: Int?) = TracksideCatalogPage()
            override suspend fun library(serviceId: String, kind: String, cursor: String?) = TracksideCatalogLibrary<TracksideCatalogItem>()
            override suspend fun libraryTracks(serviceId: String, cursor: String?) = TracksideCatalogLibrary<TracksideCatalogTrack>()
        }
        return CatalogRecommendations(
            scope = CoroutineScope(Dispatchers.Unconfined),
            streaming = MutableStateFlow(StreamingState(listOf(server))),
            clientFor = { client },
            io = Dispatchers.Unconfined,
            work = Dispatchers.Unconfined,
        ).also { it.want() }
    }

    /** The rows Play shows, for a page on its own. */
    val rows: List<RecommendationShelf> get() = shelves.take(3).map { RecommendationShelf(SERVER, appleMusic, it) }
}

class RecommendationsScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    /** The rows on their own, each kind of card, and one row still coming. */
    @Test fun shelves() {
        snap("play-recs") {
            CompositionLocalProvider(LocalNavigator provides Navigator()) {
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    SampleRecommendations.rows.forEach { RecommendationShelfRow(it, showsService = false) }
                    RecommendationShelfLoading()
                }
            }
        }
    }

    /** Two services: each row says whose it is. */
    @Test fun shelvesFromSeveralServices() {
        snap("play-recs-services") {
            CompositionLocalProvider(LocalNavigator provides Navigator()) {
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    SampleRecommendations.rows.take(2).forEach { RecommendationShelfRow(it, showsService = true) }
                }
            }
        }
    }

    private fun page(name: String, answers: Boolean) {
        withSampleHistory()
        val library = SampleLibrary().also { graph.library = it }
        val picks = FakeServerPicks(library)
        val recs = SampleRecommendations.loader(answers)
        snap(name, settleMs = 3000) {
            CompositionLocalProvider(LocalNavigator provides Navigator(), LocalServerPicks provides picks, LocalCatalogRecommendations provides recs) { PlayScreen() }
        }
    }

    /** The whole of Play with Apple Music's rows after Picked for you. */
    @Test
    @Config(qualifiers = "w412dp-h5200dp-xxhdpi")
    fun full() = page("play-recs-full", answers = true)

    @Test
    @Config(qualifiers = "w412dp-h5200dp-xxhdpi")
    fun loading() = page("play-recs-loading", answers = false)
}

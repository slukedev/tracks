package com.luke.tracks.streaming

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
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideEvent
import com.luke.tracks.library.trackside.TracksideJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun fixture(name: String): String =
    requireNotNull(CatalogRecommendationsTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

private const val HOME = "s1"
private const val SPOTIFY = "bqtyy4kcexcsg6wa0y4w5"

/** Answers with [answer] and counts what it was asked. */
private class FakeCatalog(var answer: suspend () -> TracksideCatalogRecommendations) : CatalogClient {
    var asked = 0
    override suspend fun recommendations(services: List<String>, limit: Int): TracksideCatalogRecommendations {
        asked++
        return answer()
    }
    override suspend fun access() = TracksideClient.CatalogAccess.Play(cellular = false, recommends = true)
    override suspend fun services() = TracksideCatalogServices()
    override suspend fun search(query: String, services: List<String>, limit: Int, types: List<String>) = TracksideCatalogSearch()
    override suspend fun item(ref: String, cursor: String?, limit: Int?) = TracksideCatalogPage()
    override suspend fun library(serviceId: String, kind: String, cursor: String?) = TracksideCatalogLibrary<TracksideCatalogItem>()
    override suspend fun libraryTracks(serviceId: String, cursor: String?) = TracksideCatalogLibrary<TracksideCatalogTrack>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRecommendationsTest {
    private val services = TracksideJson.decodeFromString(TracksideCatalogServices.serializer(), fixture("catalog-services.json")).services
    private val picks = TracksideJson.decodeFromString(TracksideCatalogRecommendations.serializer(), fixture("catalog-recommendations.json"))

    /** The fixture's answer, whole, with only the Spotify row (the one service listed here). */
    private val whole = picks.copy(complete = true)

    private fun server(recommends: Boolean = true, list: List<TracksideCatalogService> = services, status: StreamingServer.Status = StreamingServer.Status.Ok) =
        StreamingServer(HOME, "Home", TracksideClient.CatalogAccess.Play(cellular = false, recommends = recommends), status, list)

    private class Rig(val loader: CatalogRecommendations, val streaming: MutableStateFlow<StreamingState>, val client: FakeCatalog)

    private fun TestScope.rig(client: FakeCatalog, state: StreamingState = StreamingState(listOf(server()))): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val streaming = MutableStateFlow(state)
        val loader = CatalogRecommendations(
            scope = backgroundScope,
            streaming = streaming,
            clientFor = { client },
            now = { Instant.ofEpochMilli(testScheduler.currentTime) },
            io = dispatcher,
            work = dispatcher,
        )
        return Rig(loader, streaming, client)
    }

    private fun Rig.shelves() = (loader.state.value[HOME] as? ServerRecommendations.Ready)?.shelves.orEmpty().map { it.shelf.title }

    @Test fun nothingIsAskedUntilPlayShowsThenItsKeptForTheSession() = runTest {
        val rig = rig(FakeCatalog { whole })
        runCurrent()
        assertEquals(0, rig.client.asked)
        assertTrue(rig.loader.state.value.isEmpty())

        rig.loader.want()
        runCurrent()
        assertEquals("only rows of services listed here, by name", listOf("Jump back in"), rig.shelves())
        assertEquals("Spotify", (rig.loader.state.value[HOME] as ServerRecommendations.Ready).shelves.single().service.name)

        advanceTimeBy(19 * 60_000L)
        rig.loader.want()
        runCurrent()
        assertEquals("coming back soon after is served from memory", 1, rig.client.asked)

        advanceTimeBy(2 * 60_000L)
        rig.loader.want()
        runCurrent()
        assertEquals("after 20 minutes it's asked again", 2, rig.client.asked)
    }

    @Test fun aRowsPlaceIsKeptWhileItsAsked() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = rig(FakeCatalog { gate.await(); whole })
        rig.loader.want()
        runCurrent()
        assertEquals(ServerRecommendations.Loading, rig.loader.state.value[HOME])
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Jump back in"), rig.shelves())
    }

    @Test fun aPartialAnswerShowsAndIsAskedAgainOnce() = runTest {
        var complete = false
        val rig = rig(FakeCatalog { picks.copy(complete = complete) })
        rig.loader.want()
        runCurrent()
        assertEquals(listOf("Jump back in"), rig.shelves())
        assertEquals(1, rig.client.asked)
        advanceTimeBy(13_000)
        assertEquals("once the server's short hold ends", 2, rig.client.asked)
        advanceTimeBy(60_000)
        assertEquals("still partial, but not asked again", 2, rig.client.asked)

        complete = true
        rig.loader.handle(HOME, TracksideEvent("catalog_services", "{\"state\":\"ok\"}"))
        runCurrent()
        assertEquals("the services changed: asked at once", 3, rig.client.asked)
    }

    @Test fun servicesUnavailableHidesQuietlyUntilTheServerSaysToAskAgain() = runTest {
        val rig = rig(FakeCatalog { throw TracksideError.CatalogUnavailable(10) })
        rig.loader.want()
        runCurrent()
        assertTrue("no rows and no placeholder", rig.loader.state.value.isEmpty())

        rig.client.answer = { whole }
        rig.streaming.value = StreamingState(listOf(server(status = StreamingServer.Status.Connecting)))
        rig.loader.want()
        runCurrent()
        assertEquals("Retry-After is kept", 1, rig.client.asked)
    }

    @Test fun shownRowsStayThroughABusyOrFailedRefresh() = runTest {
        val rig = rig(FakeCatalog { whole })
        rig.loader.want()
        runCurrent()
        rig.client.answer = { throw TracksideError.RateLimited(30) }
        rig.loader.handle(HOME, TracksideEvent("catalog_services", "{}"))
        runCurrent()
        assertEquals(listOf("Jump back in"), rig.shelves())
        rig.client.answer = { throw java.io.IOException("offline") }
        advanceTimeBy(31_000)
        rig.loader.handle(HOME, TracksideEvent("catalog_services", "{}"))
        runCurrent()
        assertEquals(listOf("Jump back in"), rig.shelves())
    }

    @Test fun anOldServerNoAccessOrNoServiceWithPicksIsNeverAsked() = runTest {
        for (state in listOf(
            StreamingState(listOf(server(recommends = false))),
            StreamingState(),
            StreamingState(listOf(server(list = services.filterNot { it.id == SPOTIFY }))),
            StreamingState(listOf(server(status = StreamingServer.Status.Unavailable))),
        )) {
            val rig = rig(FakeCatalog { whole }, state)
            rig.loader.want()
            runCurrent()
            assertEquals(0, rig.client.asked)
            assertTrue(rig.loader.state.value.isEmpty())
        }
    }

    @Test fun aServiceSigningInOrOutOrTheServerGoingFollowsAtOnce() = runTest {
        val rig = rig(FakeCatalog { whole })
        rig.loader.want()
        runCurrent()
        val signedOut = services.map { if (it.id == SPOTIFY) it.copy(account = TracksideCatalogService.Account("signed_out")) else it }
        rig.streaming.value = StreamingState(listOf(server(list = signedOut)))
        runCurrent()
        assertTrue(rig.loader.state.value.isEmpty())
        rig.streaming.value = StreamingState(listOf(server()))
        runCurrent()
        assertEquals("signed back in: asked again", 2, rig.client.asked)
        assertEquals(listOf("Jump back in"), rig.shelves())
        rig.streaming.value = StreamingState()
        runCurrent()
        assertTrue(rig.loader.state.value.isEmpty())
    }

    @Test fun noRouteForThisAccountShowsNothing() = runTest {
        val rig = rig(FakeCatalog { throw TracksideError.NotFound })
        rig.loader.want()
        runCurrent()
        assertTrue(rig.loader.state.value.isEmpty())
        rig.loader.want()
        runCurrent()
        assertEquals("not asked again this visit", 1, rig.client.asked)
    }
}

class RecommendationRowsTest {
    private val spotify = TracksideCatalogService("b1", "Spotify")
    private val apple = TracksideCatalogService("b2", "Apple Music")
    private fun key(title: String, artist: String) = title.lowercase() + "|" + artist.lowercase()

    private fun album(name: String, artist: String) = TracksideCatalogEntry.Item(TracksideCatalogItem(kind = "album", ref = "a:$name", name = name, artist = artist))
    private fun song(title: String, album: String, artist: String) = TracksideCatalogEntry.Track(TracksideCatalogTrack(ref = "t:$title", title = title, artist = artist, album = album))
    private fun playlist(name: String) = TracksideCatalogEntry.Item(TracksideCatalogItem(kind = "playlist", ref = "p:$name", name = name))

    private fun row(title: String, service: TracksideCatalogService = spotify, items: List<TracksideCatalogEntry>) =
        RecommendationShelf("s1", service, TracksideCatalogShelf("w$title", service.id, title, items = items))

    @Test fun whatRecentlyPlayedHoldsIsLeftOutAndMostlyRepeatedRowsGo() {
        val recent = setOf(key("Renaissance", "Beyoncé"), key("Lemonade", "Beyoncé"))
        val rows = listOf(
            row("Made for You", items = listOf(album("RENAISSANCE", "Beyoncé"), album("Cowboy Carter", "Beyoncé"), playlist("Chill"))),
            row("Heavy Rotation", items = listOf(album("Renaissance", "Beyoncé"), song("Formation", "Lemonade", "Beyoncé"), playlist("Mix"))),
            row("Recently Played", items = listOf(playlist("Other"))),
            row("Your recently played", items = listOf(playlist("More"))),
        )
        val shown = RecommendationRows.shown(rows, recent, ::key)
        assertEquals(listOf("Made for You"), shown.map { it.shelf.title })
        assertEquals(listOf("Cowboy Carter", "Chill"), shown.single().shelf.items.map { (it as TracksideCatalogEntry.Item).item.name })
    }

    @Test fun atMostThreeRowsFromEachService() {
        val rows = (1..5).map { row("S$it", items = listOf(playlist("p$it"))) } + (1..2).map { row("A$it", apple, listOf(playlist("a$it"))) }
        assertEquals(listOf("S1", "S2", "S3", "A1", "A2"), RecommendationRows.shown(rows, emptySet(), ::key).map { it.shelf.title })
    }

    @Test fun nothingRecentKeepsEverythingInOrder() {
        val rows = listOf(row("One", items = listOf(album("X", "Y"))), row("Two", apple, listOf(song("S", "X", "Y"))))
        assertEquals(rows, RecommendationRows.shown(rows, emptySet(), ::key))
    }
}

package com.luke.tracks.streaming

import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.TracksideAnswer
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideTransport
import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.ServerEndpoints
import com.luke.tracks.sources.PlayFromOrder
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.SourceId
import com.luke.tracks.ui.services.ServiceSearch
import com.luke.tracks.ui.services.ServiceSearcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String): String =
    requireNotNull(ServiceSearchTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

/** The contract's fixtures as a server with Apple Music (in Spotify's place) and Tidal; Qobuz is signed out. */
private fun appleMusic(json: String) = json.replace("\"spotify\"", "\"apple_music\"").replace("Spotify", "Apple Music")

private const val HOME = "s1"
private const val APPLE_MUSIC = "bqtyy4kcexcsg6wa0y4w5"
private const val TIDAL = "bnr6z9ds6zzv5xjpv0j1s"

/**
 * The services' search as the phone and TV run it: the real client over a fake transport that
 * answers with the wire contract's fixtures, through the repository and the searcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceSearchTest {
    private val home = Endpoint("http://192.168.1.20:4747".toHttpUrl(), Endpoint.Kind.Lan)

    private class Server {
        var capabilities = fixture("capabilities.json")
        var me = fixture("me.json")
        var services: suspend () -> TracksideAnswer = { TracksideAnswer(200, appleMusic(fixture("catalog-services.json"))) }
        var search: suspend (Request) -> TracksideAnswer = { TracksideAnswer(200, appleMusic(fixture("catalog-search.json"))) }
        val searches = mutableListOf<Request>()
    }

    private class Rig(val server: Server, val queries: MutableStateFlow<ServiceSearcher.Query>, val states: MutableList<ServiceSearch>) {
        val last get() = states.last()
        fun ask(text: String) { queries.value = ServiceSearcher.Query(text) }
    }

    private fun TestScope.rig(
        server: Server = Server(),
        owns: (String) -> Boolean = { false },
        debounceMs: Long = 0,
        types: List<String> = TracksideClient.DEFAULT_CATALOG_TYPES,
        first: String = "",
    ): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = TracksideTransport { request, _ ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/capabilities") -> TracksideAnswer(200, server.capabilities)
                path.endsWith("/me") -> TracksideAnswer(200, server.me)
                path.endsWith("/catalog/services") -> server.services()
                path.endsWith("/catalog/search") -> { server.searches += request; server.search(request) }
                else -> TracksideAnswer(404, fixture("error-not-found.json"))
            }
        }
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        val client = TracksideCatalogClient(TracksideClient(endpoints, "tsk_9pQvL2mN6tY0wB4kD9hJ1sF5gA7cE3uI8oP2zR6x", "d1", null, transport))
        val repository = StreamingRepository(backgroundScope, clientFor = { client }, io = dispatcher, work = dispatcher)
        repository.watchServers(flowOf(listOf(HOME to "Home")))
        val queries = MutableStateFlow(ServiceSearcher.Query(first))
        val states = mutableListOf<ServiceSearch>()
        backgroundScope.launch(dispatcher) {
            ServiceSearcher(repository, owns, debounceMs, types, work = dispatcher).states(queries).collect { states += it }
        }
        runCurrent()
        return Rig(server, queries, states)
    }

    private val Rig.results get() = (last as ServiceSearch.Loaded).results

    @Test fun appleMusicAnswersWithItsOwnSection() = runTest {
        val rig = rig()
        rig.ask("halo")
        runCurrent()
        val section = rig.results.sections.single()
        assertEquals("Apple Music", section.service.name)
        assertEquals(ServiceKey(HOME, APPLE_MUSIC), section.key)
        assertEquals(listOf("Halo"), section.tracks.map { it.title })
        assertEquals(listOf("I Am... Sasha Fierce"), section.albums.map { it.name })
        assertEquals(listOf("Beyoncé"), section.artists.map { it.name })
        assertEquals(listOf("This Is Beyoncé"), section.playlists.map { it.name })
        assertEquals("Tidal timed out: what came back is shown, Tidal named", listOf("Tidal"), rig.results.missing)
        val asked = rig.server.searches.single().url
        assertEquals("signed-out Qobuz isn't asked", "$APPLE_MUSIC,$TIDAL", asked.queryParameter("services"))
        assertEquals("track,album,artist,playlist", asked.queryParameter("types"))
    }

    @Test fun aSongInYourLibraryIsShownThereNotUnderTheService() = runTest {
        val rig = rig(owns = { it == ServerLibrary.mediaId(HOME, "sdjcz08vf67h7cx28snq6") })
        rig.ask("halo")
        runCurrent()
        val section = rig.results.sections.single()
        assertTrue(section.tracks.isEmpty())
        assertEquals(listOf("Apple Music"), rig.results.folded[ServerLibrary.mediaId(HOME, "sdjcz08vf67h7cx28snq6")])
        assertEquals("the rest of the service's answer stays", 1, section.albums.size)
    }

    @Test fun nothingIsAskedWithoutAccessToTheServices() = runTest {
        // No `features.catalog`: Music Assistant off, or the account may not play services.
        val noCatalog = Server().apply { me = fixture("me-without-acquisition.json") }
        val withoutCatalog = rig(noCatalog)
        withoutCatalog.ask("halo")
        runCurrent()
        assertEquals(ServiceSearch.Idle, withoutCatalog.last)
        assertTrue(noCatalog.searches.isEmpty())

        val oldServer = Server().apply { capabilities = capabilities.replace("\"speakers\": true,", "") }
        val old = rig(oldServer)
        old.ask("halo")
        runCurrent()
        assertEquals(ServiceSearch.Idle, old.last)
        assertTrue(oldServer.searches.isEmpty())

        val one = rig()
        one.ask("h")
        runCurrent()
        assertEquals("one letter asks nothing", ServiceSearch.Idle, one.last)
        assertTrue(one.server.searches.isEmpty())
    }

    @Test fun aSearchTypedBeforeTheServicesLoadIsAnsweredWhenTheyDo() = runTest {
        val listed = CompletableDeferred<Unit>()
        val server = Server().apply {
            val answer = services
            services = { listed.await(); answer() }
        }
        val rig = rig(server, first = "halo")
        assertEquals(ServiceSearch.Idle, rig.last)
        listed.complete(Unit)
        runCurrent()
        assertEquals(listOf("Halo"), rig.results.sections.single().tracks.map { it.title })
        assertEquals(1, server.searches.size)
    }

    @Test fun aNewerQueryCancelsTheOlderOnesRequest() = runTest {
        var cancelled = false
        val server = Server().apply {
            search = { request ->
                if (request.url.queryParameter("q") == "beyon") {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                TracksideAnswer(200, appleMusic(fixture("catalog-search.json")))
            }
        }
        val rig = rig(server)
        rig.ask("beyon")
        runCurrent()
        assertTrue(rig.last is ServiceSearch.Loading)
        rig.ask("halo")
        runCurrent()
        assertTrue("the first request was let go", cancelled)
        assertEquals(listOf("beyon", "halo"), server.searches.map { it.url.queryParameter("q") })
        assertEquals(listOf("Halo"), rig.results.sections.single().tracks.map { it.title })
    }

    @Test fun typingQuicklyAsksOnceAfterThePause() = runTest {
        val rig = rig(debounceMs = 250)
        for (text in listOf("ha", "hal", "halo")) {
            rig.ask(text)
            advanceTimeBy(100)
        }
        assertTrue(rig.server.searches.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("halo"), rig.server.searches.map { it.url.queryParameter("q") })
        assertTrue(rig.last is ServiceSearch.Loaded)
    }

    @Test fun aBusyOrUnreachableServiceFailsWithoutThrowing() = runTest {
        val busy = Server().apply { search = { TracksideAnswer(503, fixture("error-catalog-unavailable.json"), retryAfter = "10") } }
        val unavailable = rig(busy)
        unavailable.ask("halo")
        runCurrent()
        assertTrue(unavailable.last is ServiceSearch.Failed)

        val limited = Server().apply {
            search = { TracksideAnswer(429, """{"error":{"code":"rate_limited","message":"Slow down.","request_id":"r1"}}""", retryAfter = "5") }
        }
        val rateLimited = rig(limited)
        rateLimited.ask("halo")
        runCurrent()
        assertTrue(rateLimited.last is ServiceSearch.Failed)

        rateLimited.server.search = { TracksideAnswer(200, appleMusic(fixture("catalog-search.json"))) }
        rateLimited.ask("halo again")
        runCurrent()
        assertTrue("the next query is asked as usual", rateLimited.last is ServiceSearch.Loaded)
    }

    @Test fun tvAsksForStationsToo() = runTest {
        val withStations = Server().apply {
            search = {
                val answer = appleMusic(fixture("catalog-search.json"))
                TracksideAnswer(200, answer.replaceFirst("{", """{"radio": {"items": [{"kind": "radio", "ref": "mc1.radio.halo", "service_id": "$APPLE_MUSIC", "name": "Beyoncé Station"}]},"""))
            }
        }
        val rig = rig(withStations, types = listOf("track", "album", "artist", "playlist", "radio"))
        rig.ask("halo")
        runCurrent()
        assertEquals("track,album,artist,playlist,radio", withStations.searches.single().url.queryParameter("types"))
        assertEquals(listOf("Beyoncé Station"), rig.results.sections.single().stations.map { it.name })
    }

    @Test fun servicesGoAroundYourLibraryInThePlayFromOrder() = runTest {
        val rig = rig()
        rig.ask("halo")
        runCurrent()
        val results = rig.results
        val library = PlayFromPlace.From(SourceId.Server(HOME))
        val appleMusic = PlayFromPlace.Service(PlayFromPlace.APPLE_MUSIC)

        val first = results.aroundLibrary(PlayFromOrder(listOf(appleMusic, library)))
        assertEquals(listOf("Apple Music"), first.before.map { it.service.name })
        assertTrue(first.after.isEmpty())

        val second = results.aroundLibrary(PlayFromOrder(listOf(library, appleMusic)))
        assertTrue(second.before.isEmpty())
        assertEquals(listOf("Apple Music"), second.after.map { it.service.name })

        assertEquals("an order that doesn't name it puts it after your library", listOf("Apple Music"), results.aroundLibrary(PlayFromOrder.Standard).after.map { it.service.name })
    }
}

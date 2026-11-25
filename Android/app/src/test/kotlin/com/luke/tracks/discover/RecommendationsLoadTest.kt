package com.luke.tracks.discover

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.DeviceLibrary
import com.luke.tracks.library.SecretBox
import com.luke.tracks.library.ServerKind
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.library.trackside.TracksideProof
import com.luke.tracks.net.PinKey
import com.luke.tracks.net.PinStore
import com.luke.tracks.ui.play.ForYouShelfView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.tracks_engine.ForYouShelf
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Recommendations never load": Play's server shelves, from a real Trackside over the wire
 * (the endpoint proof, media tokens, covers through the signed links), in the conditions a
 * phone meets: a server from before picks, the newest one, a library still on its first sync,
 * and https with a self-signed certificate you trusted. Every shelf has to settle: songs, a
 * word on why there are none, or a way to try again, never a loading shelf for good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecommendationsLoadTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as TracksApplication
    private val graph get() = app.graph
    private val web = MockWebServer()
    private val asked = CopyOnWriteArrayList<String>()
    private val syncGate = CountDownLatch(1)
    private val held = HeldCertificate.Builder().commonName("Trackside").addSubjectAlternativeName("127.0.0.1").build()

    /** The newest server answers `/me/picks`; one from before it, 404. */
    private var newest = true
    /** Whether the library's first sync is still under way: its last page waits. */
    private var syncing = false
    /** A server from before the similar-songs fix finds nothing like a song. */
    private var findsSimilar = true
    private var slowMs = 0L
    /** Whether you've played anything yet. */
    private var listened = true
    /** Whether the server gets songs it doesn't have (`features.acquisition` in `/me`). */
    private var acquires = false
    /** Whether the server's library has no songs at all. */
    private var emptyLibrary = false

    /** Songs Last.fm suggests that the server doesn't have: what it could get. */
    private fun external(prefix: String, count: Int) = buildJsonArray {
        repeat(count) { n -> add(buildJsonObject { put("title", "$prefix Song ${n + 1}"); put("artist", "$prefix Artist ${n + 1}") }) }
    }

    private val me = """{"user":{"id":"u1","username":"luke","role":"member","permissions":{"can_request":true}},
        "server":{"server_id":"$SERVER_UUID","name":"Home","version":"1.0.0"},
        "features":{"acquisition":{"instant_play":false,"external_play":false}}}"""

    private val plain = object : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
    }

    /** Twelve artists, each with a song you've played and one you haven't on the server's side. */
    private val artists = (1..12).map { "Artist $it" }
    private fun yours(artist: String) = song("y-${artist.hashCode()}", "Played by $artist", artist)
    private fun theirs(artist: String) = song("t-${artist.hashCode()}", "New from $artist", artist)
    private val stranger = song("s-1", "Somebody New", "A Stranger")

    private fun song(id: String, title: String, artist: String) = buildJsonObject {
        put("id", id); put("title", title); put("artist", artist); put("album", "$artist's Album")
        put("album_id", "a-${artist.hashCode()}"); put("artwork_id", "art-$id"); put("duration_ms", 200_000)
    }

    private fun songs(list: List<kotlinx.serialization.json.JsonObject>) = buildJsonObject { put("songs", JsonArray(list)) }.toString()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val notFound = """{"error":{"code":"not_found","message":"Not found."}}"""

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            val path = url.encodedPath.removePrefix("/api/v1/")
            asked += path
            if (slowMs > 0 && path != "ping") Thread.sleep(slowMs)
            return when {
                path == "ping" -> {
                    val proof = TracksideProof.expected(TracksideProof.Key.Device(DEVICE, KEY), SERVER_UUID, base, url.queryParameter("challenge")!!)
                    json("""{"server_id":"$SERVER_UUID","proof":"$proof"}""")
                }
                path.startsWith("art/") -> if (url.queryParameter("mt") == null) json(notFound, 401)
                    else MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body(Buffer().write(ByteArray(2_048) { 7 })).build()
                request.headers["Authorization"] != "Bearer $KEY" -> json(notFound, 401)
                path == "server/urls" -> MockResponse.Builder().code(304).build()
                path == "media-token" -> json("""{"token":"mt1.test","expires_at":"2099-01-01T00:00:00.000Z"}""")
                path == "sync" && url.queryParameter("cursor") == null -> json(
                    buildJsonObject {
                        put("mode", "snapshot"); put("next_cursor", "page-2")
                        put("libraries", buildJsonArray { add(buildJsonObject { put("song_count", if (emptyLibrary) 0 else artists.size * 400) }) })
                        put("songs", JsonArray(if (emptyLibrary) emptyList() else artists.map(::yours)))
                    }.toString(),
                )
                path == "me" -> if (acquires) json(me) else json(notFound, 404)
                path == "me/get-more" -> if (acquires) json(buildJsonObject { put("songs", JsonArray(emptyList())); put("external", external("More", 2)) }.toString()) else json(notFound, 404)
                path == "sync" -> {
                    if (syncing) syncGate.await(20, TimeUnit.SECONDS)
                    json("""{"mode":"snapshot","complete":true,"next_cursor":"done","songs":[]}""")
                }
                path == "me/picks" -> when {
                    !newest -> json(notFound, 404)
                    emptyLibrary -> json(buildJsonObject { put("songs", JsonArray(emptyList())); if (acquires) put("external", JsonArray(emptyList())) }.toString())
                    else -> json(
                        buildJsonObject {
                            put("songs", JsonArray(listOf(theirs("Artist 1"), theirs("Artist 2"), stranger)))
                            if (acquires) put("external", external("Picked", 6))
                        }.toString(),
                    )
                }
                path == "search" && url.queryParameter("types") == "external" -> {
                    // The server looks for songs it could get by an artist it has none of.
                    val artist = url.queryParameter("q").orEmpty()
                    json("""{"external":{"search_id":"s1","complete":true,"candidates":[{"token":"t-$artist","title":"Got by $artist","artist":"$artist","duration_ms":201000}]}}""")
                }
                path == "search" -> {
                    val artist = url.queryParameter("q").orEmpty()
                    if (emptyLibrary) json("""{"songs":{"items":[]}}""")
                    else json("""{"songs":{"items":${JsonArray(listOf(yours(artist)) + if (findsSimilar) listOf(theirs(artist)) else emptyList())}}}""")
                }
                path.startsWith("songs/") && path.endsWith("/similar") -> json(songs(if (findsSimilar) listOf(stranger) else emptyList()))
                path == "songs/random" -> json(songs(artists.map(::yours)))
                path == "albums" -> json("""{"albums":[]}""")
                else -> json(notFound, 404)
            }
        }
    }

    private lateinit var base: okhttp3.HttpUrl
    private lateinit var server: SubsonicServer

    private suspend fun start(https: Boolean) {
        org.robolectric.shadows.ShadowLog.stream = System.out
        web.dispatcher = dispatcher
        if (https) web.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        web.start(InetAddress.getByName("127.0.0.1"), 0)
        base = web.url("/")
        server = SubsonicServer(
            id = "home", name = "Home", url = base.toString().trimEnd('/'), username = "luke", secret = KEY,
            kind = ServerKind.Trackside, serverUuid = SERVER_UUID, deviceId = DEVICE,
            pins = if (https) mapOf(PinKey.of(web.hostName, web.port).toString() to PinStore.fingerprint(held.certificate)) else emptyMap(),
        )
        graph.settings.update { it.copy(subsonicServers = ServerLibrary.encode(listOf(server))) }
        // The app's own library can't open a Keystore secret here: let it settle first, so the
        // resolver this test's library registers for the server is the one that stays.
        withTimeout(10_000) { (graph.library as CombinedLibrary).servers.entries.first { entries -> entries.any { it.server == server } } }
        // You've played one song by each artist.
        val plays = artists.mapIndexed { n, artist ->
            PlayEntity(
                id = "6F1D9C2A-3B4E-4F5A-8B7C-0000000000%02d".format(n), title = "Played by $artist", artistName = artist, albumTitle = null, artworkUri = null,
                playedAt = System.currentTimeMillis() - (n + 1) * 3_600_000L, durationMs = 200_000, listenedMs = 200_000,
                kind = PlayKindValue.OnDemand, source = PlaySourceValue.YourMusic, appPackage = null, appName = "Tracks", stationName = null, mediaId = "",
            )
        }
        graph.history.replaceAll(if (listened) plays else emptyList())
        graph.history.syncIndex()
    }

    @After fun tearDown() {
        syncGate.countDown()
        web.close()
    }

    private suspend fun picks(): Pair<CombinedLibrary, ServerPicksService> {
        val library = CombinedLibrary(
            context = app,
            scope = graph.scope,
            device = DeviceLibrary(app, graph.scope),
            servers = ServerLibrary(app, graph.settings, plain, graph.scope, network = null),
            engine = graph.engine,
            sampleData = false,
        ).also { it.start() }
        val picks = ServerPicksService(
            app, graph, library,
            OctoService(app.getSharedPreferences("octo-test", Context.MODE_PRIVATE), object : ServerAccess {
                override val entries get() = library.servers.entries
                override fun client(server: SubsonicServer) = library.servers.client(server)
                override suspend fun sync(server: SubsonicServer) = Unit
            }, graph.scope, { false }),
            graph.scope,
            newBudget = { RequestBudget(100, 1) },
            loadLimitMs = 8_000,
        ).also { it.start() }
        return library to picks
    }

    /** Loads as Play does, and checks every shelf settled in time. */
    private suspend fun settled(picks: ServerPicksService, withinMs: Long = 15_000): ServerForYouState {
        withTimeout(10_000) { picks.servers.first { list -> list.any { it.id == server.id } } }
        withTimeout(withinMs) { picks.load(server.id, force = true) }
        val state = picks.forYou.value.getValue(server.id)
        assertTrue("still filling: $state", !state.isFilling)
        for (shelf in listOf(ForYouShelf.SUGGESTED, ForYouShelf.PICKS)) {
            assertNotEquals("$shelf never stops loading: $state $asked", ForYouShelfView.Loading, ForYouShelfView.of(state, shelf))
        }
        return state
    }

    @Test fun theNewestServerFillsTheShelvesWhileItsFirstSyncIsStillUnderWay(): Unit = runBlocking {
        syncing = true
        start(https = false)
        val (library, picks) = picks()
        withTimeout(10_000) { library.servers.entries.first { entries -> entries.any { it.status == ServerStatus.Syncing } } }

        val state = settled(picks)
        assertTrue("asked for the server's picks: $asked", "me/picks" in asked)
        assertTrue("Picked for you, from the artists you play: $state", state.shelves.picks.map { it.title }.containsAll(listOf("New from Artist 1", "New from Artist 2")))
        assertTrue("Suggested songs: $state", state.shelves.suggested.any { it.title == "Somebody New" })
        assertEquals(ServerStatus.Syncing, library.servers.entries.value.single().status)
    }

    @Test fun beforeYouveListenedTheServersPicksArePickedForYouAndTheShelvesSettleQuickly(): Unit = runBlocking {
        listened = false
        start(https = false)
        val (library, picks) = picks()
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }

        val started = System.currentTimeMillis()
        val state = settled(picks)
        assertTrue("Picked for you, from the server's picks: $state", state.shelves.picks.map { it.title }.containsAll(listOf("New from Artist 1", "New from Artist 2", "Somebody New")))
        assertTrue("didn't wait for the load limit: ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 7_000)
    }

    /** Suggested songs' new songs, and whether every song on the shelf has its own id. */
    private fun assertMixed(state: ServerForYouState) {
        val suggested = state.shelves.suggested
        assertEquals("ids are a grid's keys: $suggested", suggested.size, suggested.map { it.id }.toSet().size)
        val first = suggested.take(4)
        assertTrue("new songs and the server's own together in the first column: $first", first.any { it.isNew } && first.any { !it.isNew })
        assertTrue(suggested.filter { it.isNew }.all { it.cover == null && it.id.startsWith(NEW_SONG_ID) })
    }

    @Test fun whereTheServerGetsSongsSuggestedMixesNewSongsWithItsOwn(): Unit = runBlocking {
        acquires = true
        start(https = false)
        val (library, picks) = picks()
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }

        val started = System.currentTimeMillis()
        val state = settled(picks)
        assertEquals(NewSongs.Offered, state.newSongs)
        assertMixed(state)
        val new = state.shelves.suggested.filter { it.isNew }.map { it.title }
        assertTrue("from picks and Get More: $new", new.any { it.startsWith("Picked") } && new.any { it.startsWith("More") })
        assertTrue("Picked for you holds the server's songs: $state", state.shelves.picks.isNotEmpty() && state.shelves.picks.none { it.isNew })
        assertTrue("didn't wait for the load limit: ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 7_000)
        assertTrue(picks.canKeep(ServerSong(server.id, state.shelves.suggested.first { it.isNew })))
    }

    @Test fun beforeYouveListenedSuggestedStillMixesInNewSongs(): Unit = runBlocking {
        acquires = true
        listened = false
        start(https = false)
        val (library, picks) = picks()
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }

        val started = System.currentTimeMillis()
        val state = settled(picks)
        assertMixed(state)
        assertTrue("Picked for you still has some of the server's picks: $state", state.shelves.picks.isNotEmpty())
        assertTrue("no Get More before you've listened: $asked", "me/get-more" !in asked)
        assertTrue("didn't wait for the load limit: ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 7_000)
    }

    @Test fun anEmptyLibraryStillSuggestsSongsToGet(): Unit = runBlocking {
        acquires = true
        emptyLibrary = true
        start(https = false)
        val (library, picks) = picks()
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }

        val state = settled(picks)
        val suggested = state.shelves.suggested
        assertTrue("songs to get, from the artists you play: $state $asked", suggested.isNotEmpty() && suggested.all { it.isNew })
        assertTrue(suggested.map { it.title }.any { it.startsWith("Got by Artist") })
        assertTrue("and from Get More", suggested.map { it.title }.any { it.startsWith("More") })
        assertEquals(suggested.size, suggested.map { it.id }.toSet().size)
        assertEquals(ForYouShelfView.Songs, ForYouShelfView.of(state, ForYouShelf.SUGGESTED))
    }

    @Test fun aServerFromBeforePicksFillsThemOverHttpsWithTheCertificateYouTrusted(): Unit = runBlocking {
        newest = false
        start(https = true)
        val (library, picks) = picks()
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }

        val state = settled(picks)
        assertTrue("asked for picks, then went on from similar songs: $asked", "me/picks" in asked && asked.any { it.endsWith("/similar") })
        assertTrue("Trackside songs show without a cover check: $asked", asked.none { it.startsWith("art/") })
        assertTrue("Picked for you: $state", state.shelves.picks.any { it.title.startsWith("New from") })
        assertTrue("Suggested songs: $state", state.shelves.suggested.any { it.title == "Somebody New" })
    }

    @Test fun aServerThatFindsNothingNewSaysSoRatherThanLoadingForGood(): Unit = runBlocking {
        newest = false
        findsSimilar = false
        start(https = false)
        val (library, picks) = picks()
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }

        val state = settled(picks)
        // More artists than a first look goes through, every song already heard.
        assertTrue("$state", state.shelves.picks.isEmpty() && state.shelves.suggested.isEmpty())
        assertEquals(ForYouShelfView.Empty, ForYouShelfView.of(state, ForYouShelf.PICKS))
        assertEquals(null, state.problem)

        // Keep exploring stops asking once the server has nothing new three times running.
        repeat(3) { picks.exploreMore() }
        assertTrue(!picks.canExploreMore.value)
    }

    @Test fun aSlowServerStillSettlesInBoundedTime(): Unit = runBlocking {
        newest = false
        start(https = false)
        val (library, picks) = picks()
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }
        slowMs = 1_500

        val began = System.currentTimeMillis()
        settled(picks, withinMs = 20_000)
        assertTrue("took ${System.currentTimeMillis() - began} ms", System.currentTimeMillis() - began < 15_000)
    }

    @Test fun aServerThatCantBeReachedSaysSo(): Unit = runBlocking {
        start(https = false)
        val (library, picks) = picks()
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }
        web.close()

        val state = settled(picks)
        assertEquals("$state", ForYouShelfView.Problem, ForYouShelfView.of(state, ForYouShelf.SUGGESTED))
        assertTrue(state.problem!!.startsWith("Couldn't reach Home."))
    }

    private companion object {
        const val KEY = "tsk_test"
        const val DEVICE = "dchjkb9k3cnapjw0kmhq6"
        const val SERVER_UUID = "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b"
    }
}

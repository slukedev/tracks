package com.luke.tracks.discover

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.DeviceLibrary
import com.luke.tracks.library.SecretBox
import com.luke.tracks.library.ServerKind
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.library.trackside.TracksideProof
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

private fun fixture(name: String): String =
    requireNotNull(TracksidePicksTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

/**
 * A Trackside server, answering with its own wire fixtures, fills Play's shelves: the same
 * walk a Subsonic server gets, over Trackside's native `/api/v1`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TracksidePicksTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as TracksApplication
    private val graph get() = app.graph
    private val web = MockWebServer()
    private val asked = CopyOnWriteArrayList<String>()
    private var similar: () -> MockResponse = { json(fixture("similar-songs.json")) }
    private var picks: () -> MockResponse = { json(fixture("picks.json")) }
    private var me: () -> MockResponse = { json(fixture("me.json")) }
    private var requested: (RecordedRequest) -> MockResponse = { json(fixture("acquisition-downloading.json"), 202) }
    private var status: () -> MockResponse = { json(fixture("acquisition-done.json")) }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var search: (RecordedRequest) -> MockResponse = { json(fixture("search.json")) }
    private var external: (RecordedRequest) -> MockResponse = { json(fixture("external-play.json")) }
    private val externalPlays = CopyOnWriteArrayList<RecordedRequest>()
    private val albumSorts = CopyOnWriteArrayList<String>()
    private lateinit var octo: OctoService

    private val plain = object : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            val path = url.encodedPath.removePrefix("/api/v1/")
            asked += path
            return when {
                path == "ping" -> {
                    val proof = TracksideProof.expected(TracksideProof.Key.Device(DEVICE, KEY), SERVER_UUID, base, url.queryParameter("challenge")!!)
                    json("""{"server_id":"$SERVER_UUID","proof":"$proof"}""")
                }
                path.startsWith("art/") -> if (url.queryParameter("mt") == null) json(fixture("error-not-found.json"), 401)
                    else MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body(Buffer().write(ByteArray(2_048) { 7 })).build()
                request.headers["Authorization"] != "Bearer $KEY" -> json(fixture("error-not-found.json"), 401)
                path == "me/picks" -> picks()
                path == "server/urls" -> MockResponse.Builder().code(304).build()
                path == "me" -> me()
                path == "acquisitions" && request.method == "POST" -> {
                    requests += request
                    requested(request)
                }
                path == "external/play" && request.method == "POST" -> {
                    externalPlays += request
                    external(request)
                }
                path.startsWith("acquisitions/") -> status()
                path == "media-token" -> json(fixture("media-token.json"))
                path == "sync" -> json(fixture(if (url.queryParameter("cursor") == null) "sync-snapshot-songs.json" else "sync-snapshot-complete.json"))
                path == "search" -> search(request)
                path.startsWith("songs/") && path.endsWith("/similar") -> similar()
                path == "songs/random" -> json(fixture("songs.json"))
                path == "albums" -> {
                    albumSorts += url.queryParameter("sort").orEmpty()
                    json(fixture("albums.json"))
                }
                else -> json(fixture("error-not-found.json"), 404)
            }
        }
    }

    private lateinit var base: okhttp3.HttpUrl
    private lateinit var server: SubsonicServer

    @Before fun setUp(): Unit = runBlocking {
        org.robolectric.shadows.ShadowLog.stream = System.out
        web.dispatcher = dispatcher
        web.start(InetAddress.getByName("127.0.0.1"), 0)
        base = web.url("/")
        server = SubsonicServer(
            id = "home", name = "Home", url = base.toString().trimEnd('/'), username = "luke", secret = KEY,
            kind = ServerKind.Trackside, serverUuid = SERVER_UUID, deviceId = DEVICE,
        )
        graph.settings.update { it.copy(subsonicServers = ServerLibrary.encode(listOf(server))) }
        // The app's own library can't open a Keystore secret here: let it settle first, so the
        // resolver this test's library registers for the server is the one that stays.
        withTimeout(10_000) { (graph.library as CombinedLibrary).servers.entries.first { entries -> entries.any { it.server == server } } }
    }

    @After fun tearDown() {
        web.close()
    }

    private suspend fun library(): CombinedLibrary {
        val library = CombinedLibrary(
            context = app,
            scope = graph.scope,
            device = DeviceLibrary(app, graph.scope),
            servers = ServerLibrary(app, graph.settings, plain, graph.scope, network = null),
            engine = graph.engine,
            sampleData = false,
        ).also { it.start() }
        withTimeout(15_000) { library.servers.entries.first { entries -> entries.any { it.status is ServerStatus.Ready } } }
        withTimeout(15_000) { library.index.first { it.snapshot.songs.size >= 2 } }
        return library
    }

    private fun picks(library: CombinedLibrary) = ServerPicksService(
        app, graph, library,
        OctoService(app.getSharedPreferences("octo-test", Context.MODE_PRIVATE), object : ServerAccess {
            override val entries get() = library.servers.entries
            override fun client(server: SubsonicServer) = library.servers.client(server)
            override suspend fun sync(server: SubsonicServer) = Unit
        }, graph.scope, { false }).also { octo = it },
        graph.scope,
        followEveryMs = 50,
    ).also { it.start() }

    private suspend fun loaded(): Pair<ServerPicksService, uniffi.tracks_engine.ForYouShelves> {
        val library = library()
        val picks = picks(library)
        withTimeout(10_000) { picks.servers.first { it.isNotEmpty() } }
        picks.load(server.id, force = true)
        return picks to picks.forYou.value.getValue(server.id).shelves
    }

    private fun titles(shelves: uniffi.tracks_engine.ForYouShelves) = (shelves.picks + shelves.suggested).map { it.title }.toSet()

    /** You play Beyoncé, though none of her songs on the server yet; Queen you've never played. */
    private suspend fun playBeyonce() {
        val plays = (1..3).map { n ->
            com.luke.tracks.data.PlayEntity(
                id = "6F1D9C2A-3B4E-4F5A-8B7C-00000000000$n", title = "Crazy in Love", artistName = "Beyoncé", albumTitle = null, artworkUri = null,
                playedAt = System.currentTimeMillis() - n * 3_600_000L, durationMs = 236_000, listenedMs = 236_000,
                kind = com.luke.tracks.data.PlayKindValue.OnDemand, source = com.luke.tracks.data.PlaySourceValue.YourMusic,
                appPackage = null, appName = "Tracks", stationName = null, mediaId = "",
            )
        }
        graph.history.replaceAll(plays)
        graph.history.syncIndex()
    }

    @Test fun aTracksideServerFillsPlaysShelvesFromItsPicks(): Unit = runBlocking {
        playBeyonce()
        val (picks, shelves) = loaded()

        assertTrue("asked for the server's picks: $asked", asked.contains("me/picks"))
        assertTrue("Picked for you: $shelves", shelves.picks.isNotEmpty())
        assertTrue("$shelves", shelves.picks.all { it.artist == "Beyoncé" })
        // An artist you have but have never played is new to you, and the server gets songs, so
        // the song Last.fm suggests that it doesn't have joins them: once, though picks and
        // similar songs both suggest it.
        assertEquals("Suggested songs: $shelves", setOf("Don't Stop Me Now", "Irreplaceable"), shelves.suggested.map { it.title }.toSet())
        assertEquals(shelves.suggested.size, shelves.suggested.map { it.id }.toSet().size)
        assertTrue(titles(shelves).containsAll(listOf("Halo", "Single Ladies (Put a Ring on It)", "Don't Stop Me Now")))
        assertTrue("Trackside never sends a stand-in cover, so its songs show without fetching one first: $asked", asked.none { it.startsWith("art/") })
        val song = ServerSong(server.id, shelves.picks.first { it.cover != null })
        assertTrue(picks.coverUrl(song)!!.startsWith("https://media.tracks-server.invalid/"))
        assertTrue(picks.playable(song) != null)

        picks.loadAlbums(server.id)
        assertTrue(asked.contains("albums"))
        picks.exploreMore()
        assertTrue(asked.contains("songs/random"))
        assertTrue("Keep exploring: ${picks.exploring.value}", picks.exploring.value.isNotEmpty())
    }

    @Test fun aServerFromBeforePicksStillFillsThemFromSimilarSongs(): Unit = runBlocking {
        picks = { json(fixture("error-not-found.json"), 404) }
        val (_, shelves) = loaded()

        assertTrue(asked.contains("search") && asked.any { it.endsWith("/similar") })
        assertTrue("$shelves $asked", titles(shelves).containsAll(listOf("Halo", "Single Ladies (Put a Ring on It)")))
    }

    @Test fun aFailingRequestIsLoggedAndTheRestStillShows(): Unit = runBlocking {
        val broken = """{"error":{"code":"internal","message":"The server had a problem."}}"""
        picks = { json(broken, 500) }
        similar = { json(broken, 500) }
        val log = java.io.ByteArrayOutputStream()
        org.robolectric.shadows.ShadowLog.stream = java.io.PrintStream(log, true)
        val (_, shelves) = loaded()

        val logged = log.toString()
        assertTrue(logged, logged.contains("couldn't pick songs") && logged.contains("couldn't find similar songs"))
        assertTrue("no key in the log", !logged.contains(KEY))
        assertTrue("$shelves", titles(shelves).contains("Halo"))
    }

    private suspend fun suggestedNewSong(): Pair<ServerPicksService, ServerSong> {
        playBeyonce()
        val (picks, shelves) = loaded()
        val new = shelves.suggested.single { it.title == "Irreplaceable" }
        return picks to ServerSong(server.id, new)
    }

    @Test fun aNewSongIsAddedWithARequestAndShowsOnItsWayUntilItsDone(): Unit = runBlocking {
        val (picks, song) = suggestedNewSong()
        assertEquals(NewSongs.Offered, picks.forYou.value.getValue(server.id).newSongs)
        assertTrue("not on the server yet: it isn't played", song.song.isNew && picks.playable(song) == null && song.song.cover == null)
        assertTrue("it can be added", picks.canKeep(song))
        val library = ServerSong(server.id, picks.forYou.value.getValue(server.id).shelves.picks.first())
        assertTrue("songs the server has aren't requested", !picks.canKeep(library))

        // Still getting it when first checked on, so it shows as on its way.
        status = { json(fixture("acquisition-downloading.json")) }
        val said = picks.keep(song)
        assertTrue(said, said.startsWith("Getting “Irreplaceable”"))
        val sent = requests.single()
        assertTrue("an idempotency key", !sent.headers["Idempotency-Key"].isNullOrBlank())
        val body = sent.body!!.utf8()
        assertTrue(body, body.contains("\"title\":\"Irreplaceable\"") && body.contains("\"artist\":\"Beyoncé\""))
        assertTrue(octo.isWaiting(song.song.identity))
        assertEquals(1, octo.requests().size)

        status = { json(fixture("acquisition-done.json")) }
        withTimeout(10_000) { octo.waiting.first { song.song.identity !in it } }
        assertTrue("checked on with 15.4: $asked", asked.any { it == "acquisitions/qc5hgnjk920q78t8wpcg1" })
        assertTrue(octo.requests().isEmpty())
    }

    @Test fun aSongTheServerHasAlreadyIsSaidSo(): Unit = runBlocking {
        val (picks, song) = suggestedNewSong()
        requested = { json(fixture("acquisition-done.json"), 200) }
        val said = picks.keep(song)
        assertTrue(said, said.contains("is on Home already"))
    }

    @Test fun aUsedUpQuotaSaysWhenItResets(): Unit = runBlocking {
        val (picks, song) = suggestedNewSong()
        requested = { json(fixture("error-quota.json"), 429) }
        val said = picks.keep(song)
        assertTrue(said, said.startsWith("You've reached Home's limit on requested songs. It resets "))
        assertTrue("not on its way", !octo.isWaiting(song.song.identity))
        val zone = java.time.ZoneId.of("Europe/London")
        val resets = java.time.Instant.parse("2026-11-15T09:30:00Z")
        assertTrue(quotaMessage("Home", resets, zone, java.time.Instant.parse("2026-11-14T20:00:00Z")).contains("It resets tomorrow at "))
        assertTrue(quotaMessage("Home", resets, zone, java.time.Instant.parse("2026-11-15T08:00:00Z")).contains("It resets at "))
        assertEquals("You've reached Home's limit on requested songs. Try again later.", quotaMessage("Home", null))
    }

    @Test fun aServerThatDoesntGetSongsSaysWhySuggestedHasNoNewOnes(): Unit = runBlocking {
        me = { json(fixture("me-without-acquisition.json")) }
        playBeyonce()
        val (picks, shelves) = loaded()
        val state = picks.forYou.value.getValue(server.id)
        assertEquals(NewSongs.TurnedOff, state.newSongs)
        assertTrue("no new songs: $shelves", shelves.suggested.none { it.isNew } && shelves.picks.none { it.isNew })
        assertTrue(shelves.suggested.none { picks.canKeep(ServerSong(server.id, it)) })
        assertTrue("no requests asked: $asked", asked.none { it.startsWith("acquisitions") || it == "me/get-more" })
        val note = com.luke.tracks.ui.play.newSongsNote(server, state.newSongs)!!
        assertTrue(note, note.contains("Requests page"))
        val empty = com.luke.tracks.ui.play.emptyMessage(server, uniffi.tracks_engine.ForYouShelf.SUGGESTED, state.newSongs)
        assertTrue(empty, empty.contains("Requests page") && empty.contains("Last.fm API key") && !empty.contains("didn't find"))
        // Even with no songs to show, Suggested songs says why rather than hiding.
        val bare = state.copy(shelves = state.shelves.copy(suggested = emptyList(), from = emptyList()))
        assertEquals(com.luke.tracks.ui.play.ForYouShelfView.Empty, com.luke.tracks.ui.play.ForYouShelfView.of(bare, uniffi.tracks_engine.ForYouShelf.SUGGESTED))
        assertEquals(com.luke.tracks.ui.play.ForYouShelfView.Hidden, com.luke.tracks.ui.play.ForYouShelfView.of(bare.copy(newSongs = null), uniffi.tracks_engine.ForYouShelf.SUGGESTED))
    }

    @Test fun anAccountThatCantRequestSongsIsToldWhoCanAllowIt(): Unit = runBlocking {
        me = { json(fixture("me-without-acquisition.json").replace("\"can_request\": true", "\"can_request\": false")) }
        val (picks, _) = loaded()
        val state = picks.forYou.value.getValue(server.id)
        assertEquals(NewSongs.NotAllowed, state.newSongs)
        val empty = com.luke.tracks.ui.play.emptyMessage(server, uniffi.tracks_engine.ForYouShelf.SUGGESTED, state.newSongs)
        assertTrue(empty, empty.contains("isn't allowed to request songs") && empty.contains("People"))
    }

    /** Picks naming new songs by several artists, so both shelves have some. */
    private fun picksWithNewSongs(vararg songs: Pair<String, String>) {
        val external = songs.joinToString(",") { (title, artist) -> """{"title":"$title","artist":"$artist"}""" }
        picks = { json(fixture("picks.json").replace(Regex("\"external\"\\s*:\\s*\\[[^\\]]*\\]"), "\"external\": [$external]")) }
    }

    @Test fun pickedForYouAndKeepExploringHaveNewSongsTooNeverTheSameOnesAsSuggested(): Unit = runBlocking {
        picksWithNewSongs(
            "Irreplaceable" to "Beyoncé", "Formation" to "Beyoncé", "Hello" to "Adele", "Rolling in the Deep" to "Adele",
            "Easy on Me" to "Adele", "Bad Guy" to "Billie Eilish", "Ocean Eyes" to "Billie Eilish", "Lovely" to "Billie Eilish",
            "Vampire" to "Olivia Rodrigo", "Drivers License" to "Olivia Rodrigo", "Levitating" to "Dua Lipa", "Houdini" to "Dua Lipa",
        )
        playBeyonce()
        val (picks, shelves) = loaded()
        val suggestedNew = shelves.suggested.filter { it.isNew }.map { it.identity }
        val picksNew = shelves.picks.filter { it.isNew }.map { it.identity }
        assertTrue("Suggested songs has new ones: $shelves", suggestedNew.isNotEmpty())
        assertTrue("Picked for you has new ones too: $shelves", picksNew.isNotEmpty())
        assertTrue("never the same new song on both: $suggestedNew $picksNew", suggestedNew.intersect(picksNew.toSet()).isEmpty())
        assertTrue("each can be added", picksNew.all { id -> picks.canKeep(ServerSong(server.id, shelves.picks.first { it.identity == id })) })
        assertEquals(shelves.picks.size, shelves.picks.map { it.id }.toSet().size)

        // Keep exploring goes on with new ones among the server's own, none already on show.
        repeat(3) { picks.exploreMore() }
        val exploringNew = picks.exploring.value.filter { it.song.isNew }.map { it.song.identity }
        assertTrue("Keep exploring: ${picks.exploring.value}", exploringNew.isNotEmpty())
        assertTrue(exploringNew.intersect((suggestedNew + picksNew).toSet()).isEmpty())
        assertEquals(picks.exploring.value.size, picks.exploring.value.map { it.serverId + it.song.id }.toSet().size)
    }

    @Test fun somethingDifferentAsksOnlyForRandomAlbums(): Unit = runBlocking {
        val (picks, _) = loaded()
        picks.loadAlbums(server.id)
        picks.shuffleRandom(server.id)
        assertTrue("albums asked: $albumSorts", albumSorts.isNotEmpty() && albumSorts.all { it == "random" })
    }

    @Test fun somethingDifferentMixesInAlbumsToGetWithUniqueKeys() {
        val onServer = (1..5).map { ServerAlbum("subsonic:home:album:$it", "Album $it", "Artist", null) }
        val toGet = (1..2).map { uniffi.tracks_engine.CatalogAlbum(it.toULong(), "New $it", "Someone", null, null, null, null, null, uniffi.tracks_engine.CatalogAlbumKind.ALBUM, null) }
        val shown = com.luke.tracks.ui.play.somethingDifferent(ServerAlbums(random = onServer, toGet = toGet))
        assertEquals(listOf("server", "server", "catalog", "server", "server", "catalog", "server"), shown.map { it.key.substringBefore(':') })
        assertEquals(shown.size, shown.map { it.key }.toSet().size)
        assertEquals(listOf(1, 2, 3), sprinkle(listOf(1, 3), listOf(2), every = 1))
        assertEquals(listOf(1, 3, 2, 4), sprinkle(listOf(1, 3), listOf(2, 4), every = 2))
    }

    @Test fun tracksRadioPlaysANewSongWithoutKeepingItWhereTheServerOffersThat(): Unit = runBlocking {
        val (picks, song) = suggestedNewSong()
        val forRadio = picks.newForRadio()
        assertTrue("$forRadio", forRadio.any { it.song.identity == song.song.identity })
        val playable = picks.playNew(song)!!
        val sent = externalPlays.single().body!!.utf8()
        assertTrue(sent, sent.contains("\"title\":\"Irreplaceable\"") && sent.contains("\"artist\":\"Beyoncé\"") && !sent.contains("candidate"))
        assertTrue("nothing requested to keep", requests.isEmpty())
        assertEquals(com.luke.tracks.library.ServerLibrary.newSongMediaId(server.id, song.song.identity), playable.mediaId)
        assertEquals(server.id, com.luke.tracks.library.ServerLibrary.parseNewSongMediaId(playable.mediaId))
        assertEquals(null, com.luke.tracks.library.ServerLibrary.parseMediaId(playable.mediaId))
        assertEquals(song.song.identity, playable.identity)
        // A link without credentials, swapped for the server's own address with the media token as it goes out.
        val link = com.luke.tracks.net.ServerLinks.parse(playable.uri.toString())!!
        assertEquals(com.luke.tracks.net.ServerLinks.Kind.Play, link.kind)
        val resolved = com.luke.tracks.net.ServerLinks.resolvers.getValue(server.id).resolve(link, fresh = false).url
        assertEquals("/api/v1/external/plays/xzkhbcr08sk1ny8kg5mvy/stream", resolved.encodedPath)
        assertTrue("$resolved", resolved.queryParameter("mt") != null && resolved.host == base.host && resolved.port == base.port)
    }

    @Test fun aSongTheServerHasAfterAllPlaysFromItsLibrary(): Unit = runBlocking {
        val (picks, song) = suggestedNewSong()
        external = { json("""{"title":"Irreplaceable","artist":"Beyoncé","song_id":"s7m2k4q9x1v8b3n6c0de"}""") }
        val playable = picks.playNew(song)!!
        assertEquals(com.luke.tracks.library.ServerLibrary.mediaId(server.id, "s7m2k4q9x1v8b3n6c0de"), playable.mediaId)
    }

    @Test fun withInstantPlayOnlyTracksRadioGetsTheSongAndPlaysItWhileItDownloads(): Unit = runBlocking {
        me = { json(fixture("me.json").replace("\"external_play\": true", "\"external_play\": false")) }
        search = { request ->
            if (request.url.queryParameter("types") != "external") json(fixture("search.json"))
            else json("""{"external":{"candidates":[{"token":"xtka7fegwc8p56dnxvg3w","title":"Irreplaceable","artist":"Beyoncé","instant":true,"match":"exact"}],"complete":true}}""")
        }
        status = { json(fixture("acquisition-downloading.json")) }
        val (picks, song) = suggestedNewSong()
        val playable = picks.playNew(song)!!
        assertTrue("not played without keeping", externalPlays.isEmpty())
        val body = requests.single().body!!.utf8()
        assertTrue(body, body.contains("\"instant\":true") && body.contains("\"candidate\":\"xtka7fegwc8p56dnxvg3w\""))
        assertEquals(com.luke.tracks.net.ServerLinks.Kind.Play, com.luke.tracks.net.ServerLinks.parse(playable.uri.toString())!!.kind)
        val resolved = com.luke.tracks.net.ServerLinks.resolvers.getValue(server.id).resolve(com.luke.tracks.net.ServerLinks.parse(playable.uri.toString())!!, fresh = false).url
        assertEquals("/api/v1/acquisitions/q/stream", resolved.encodedPath)
        // Kept, as any request is: on its way to your library.
        assertTrue(octo.isWaiting(song.song.identity))
    }

    @Test fun withNeitherWayToPlayItTracksRadioLeavesNewSongsOut(): Unit = runBlocking {
        me = { json(fixture("me.json").replace("\"external_play\": true", "\"external_play\": false").replace("\"instant_play\": true", "\"instant_play\": false")) }
        val (picks, song) = suggestedNewSong()
        assertTrue(picks.newForRadio().isEmpty())
        assertEquals(null, picks.playNew(song))
        assertTrue(externalPlays.isEmpty() && requests.isEmpty())
    }

    @Test fun anAlbumIsAskedForSongBySong(): Unit = runBlocking {
        val (picks, _) = loaded()
        val said = picks.keepAll(server.id, listOf(newSong("Halo", "Beyoncé", "I Am... Sasha Fierce"), newSong("Sweet Dreams", "Beyoncé", "I Am... Sasha Fierce")), "I Am... Sasha Fierce")
        assertEquals("Getting 2 songs from “I Am... Sasha Fierce”", said)
        assertEquals(2, requests.size)
        requested = { json(fixture("error-quota.json"), 429) }
        val stopped = picks.keepAll(server.id, listOf(newSong("Ego", "Beyoncé"), newSong("Diva", "Beyoncé")), "More")
        assertTrue(stopped, stopped.startsWith("You've reached Home's limit"))
        assertEquals("stopped at the first refusal", 3, requests.size)
    }

    @Test fun readsTheServersShelves() {
        val decode = { name: String -> com.luke.tracks.library.trackside.TracksideJson.decodeFromString(com.luke.tracks.library.trackside.TracksideSuggestedSongs.serializer(), fixture(name)) }
        val picked = decode("picks.json")
        assertEquals(listOf("Single Ladies (Put a Ring on It)", "Don't Stop Me Now"), picked.songs.map { it.title })
        assertEquals("Irreplaceable", picked.external!!.single().title)
        assertEquals(1, decode("get-more.json").songs.size)
        assertEquals(listOf("Halo"), decode("mix.json").songs.map { it.title })
        assertEquals(2, decode("top-songs.json").songs.size)
        assertEquals("ca81rcgy3d9pfg6saekwd", picked.songs.first().subsonic.coverArt)
    }

    private companion object {
        const val KEY = "tsk_test"
        const val DEVICE = "dchjkb9k3cnapjw0kmhq6"
        const val SERVER_UUID = "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b"
    }
}

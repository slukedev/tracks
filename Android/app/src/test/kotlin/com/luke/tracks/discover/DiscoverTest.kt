package com.luke.tracks.discover

import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicClient
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.library.SubsonicTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tracks_engine.CatalogAlbumKind
import uniffi.tracks_engine.HttpResponse
import uniffi.tracks_engine.LidarrSongState
import uniffi.tracks_engine.songIdentity

class ItunesSourceTest {
    @Test fun searchAsksForEachKindInTheStorefrontAndKeepsAnswers() = runBlocking {
        val transport = RecordingTransport(
            "entity=song" to { Fixtures.ok("itunes_song") },
            "entity=album" to { Fixtures.ok("itunes_album") },
            "entity=musicArtist" to { Fixtures.ok("itunes_artist") },
        )
        val itunes = ItunesSource(transport, country = "GB")
        val found = itunes.search("  radiohead ")
        assertEquals(listOf("Let Down", "Karma Police", "Creep").size, found.songs.size)
        assertEquals("OK Computer", found.albums.first().title)
        assertEquals("Radiohead", found.artists.first().name)
        assertTrue(transport.sent.all { it.url.contains("country=gb") && it.url.contains("term=radiohead&") })

        itunes.search("radiohead")
        assertEquals("a second search within the half hour is answered from memory", 3, transport.sent.size)
        assertTrue(itunes.search(" ").songs.isEmpty())
        assertEquals(3, transport.sent.size)
    }

    @Test fun anArtistPageHasAlbumsBeforeSinglesNewestFirst() = runBlocking {
        val transport = RecordingTransport(
            "entity=album" to { Fixtures.ok("itunes_artist_albums") },
            "entity=song" to { Fixtures.ok("itunes_artist_songs") },
        )
        val page = ItunesSource(transport).artist(catalogArtist("Radiohead", 657_515u))
        assertEquals(listOf("A Moon Shaped Pool", "In Rainbows", "OK Computer", "The Bends"), page.albums.map { it.title })
        assertTrue(page.albums.all { it.kind == CatalogAlbumKind.ALBUM })
        assertEquals("Creep", page.songs.first().title)
    }

    @Test fun anAlbumsSongsComeInOrderAndFailuresThrow() = runBlocking {
        val itunes = ItunesSource(RecordingTransport("id=1097861387" to { Fixtures.ok("itunes_album_tracks") }))
        val album = itunes.album(1_097_861_387u)!!
        assertEquals("OK Computer", album.album.title)
        assertEquals((1..12).toList(), album.songs.map { it.trackNumber!!.toInt() })
        val failed = runCatching { ItunesSource(RecordingTransport()).album(1u) }
        assertTrue("a 404 is an error, not an empty album", failed.isFailure)
    }
}

class LidarrServiceTest {
    private val prefs = MemoryPrefs()

    private fun lidarr(vararg extra: Pair<String, () -> HttpResponse>) = RecordingTransport(
        *extra,
        "GET http://nas:8686/api/v1/system/status" to { Fixtures.ok("lidarr_status") },
        "qualityprofile" to { Fixtures.ok("lidarr_profiles") },
        "metadataprofile" to { Fixtures.ok("lidarr_profiles") },
        "rootfolder" to { Fixtures.ok("lidarr_rootfolders") },
        "GET http://nas:8686/api/v1/artist/lookup" to { Fixtures.ok("lidarr_lookup") },
        "GET http://nas:8686/api/v1/artist" to { Fixtures.ok("lidarr_artists") },
        "/album?artistId=" to { Fixtures.ok("lidarr_albums") },
        "/track?artistId=" to { Fixtures.ok("lidarr_tracks") },
        "/queue" to { Fixtures.ok("lidarr_queue") },
        "PUT http://nas:8686/api/v1/album/monitor" to { HttpResponse(202u, ByteArray(0)) },
        "POST http://nas:8686/api/v1/command" to { HttpResponse(201u, "{}".toByteArray()) },
    )

    @Test fun connectingKeepsTheKeySealedAndReadsTheSetup() = runBlocking {
        val transport = lidarr()
        val service = LidarrService(prefs, ReversingBox(), transport)
        assertNull(service.save("nas:8686", "abc123"))
        assertEquals(LidarrState.Connected("2.9.6.4552"), service.state.value)
        assertEquals("http://nas:8686", prefs.getString("url", null))
        assertEquals("the key is stored sealed", "sealed:321cba", prefs.getString("key", null))
        assertTrue(transport.sent.all { it.headers.any { h -> h.name == "X-Api-Key" && h.value == "abc123" } && !it.url.contains("abc123") })
        val setup = service.setup.value!!
        assertEquals("Any", setup.quality?.name)
        assertEquals("/music", setup.root?.path)

        // A new launch reads it all back without asking for the key again.
        val again = LidarrService(prefs, ReversingBox(), lidarr())
        assertTrue(again.isSetUp)
        again.check()
        assertEquals(LidarrState.Connected("2.9.6.4552"), again.state.value)
    }

    @Test fun aKeyThatCantBeOpenedAsksForItAgain() {
        prefs.edit().putString("url", "http://nas:8686").putString("key", "from another phone").apply()
        val service = LidarrService(prefs, ReversingBox(), lidarr())
        assertEquals(LidarrState.NeedsKey, service.state.value)
        assertFalse(service.isSetUp)
    }

    @Test fun aWrongKeyOrAddressSaysSoAndKeepsNothing() = runBlocking {
        val refused = RecordingTransport("system/status" to { HttpResponse(401u, ByteArray(0)) })
        val service = LidarrService(prefs, ReversingBox(), refused)
        assertEquals("Lidarr didn't accept that API key", service.save("nas:8686", "nope"))
        assertNull(prefs.getString("url", null))
        val html = RecordingTransport("system/status" to { HttpResponse(200u, "<html>".toByteArray()) })
        assertEquals("That address doesn't look like Lidarr. Check it and the port.", LidarrService(prefs, ReversingBox(), html).test("nas", "k"))
    }

    @Test fun songsReadAsCollectedWantedOrMissing() = runBlocking {
        val service = LidarrService(prefs, ReversingBox(), lidarr())
        service.save("nas:8686", "k")
        assertEquals(LidarrSongState.Collected("OK Computer"), service.songState("Let Down", "Radiohead"))
        assertEquals(LidarrSongState.Wanted, service.songState("Everything in Its Right Place", "Radiohead feat. Someone"))
        assertEquals(LidarrSongState.Missing, service.songState("Joga", "Wet Leg"))
    }

    @Test fun askingForAnAlbumFollowsItAndSearches() = runBlocking {
        val transport = lidarr()
        val service = LidarrService(prefs, ReversingBox(), transport)
        service.save("nas:8686", "k")
        assertEquals("Lidarr is looking for Amnesiac", service.requestAlbum("Amnesiac", "Radiohead", waitMs = 0))
        val monitor = transport.sent("PUT", "album/monitor").single()
        assertEquals("""{"albumIds":[13],"monitored":true}""", String(monitor.body))
        assertEquals("""{"name":"AlbumSearch","albumIds":[13]}""", String(transport.sent("POST", "command").single().body))
        assertTrue("Radiohead was already followed: nothing added", transport.sent("POST", "/artist").isEmpty())
    }

    @Test fun anArtistLidarrDoesntFollowIsAddedQuietlyFirst() = runBlocking {
        var added = false
        val transport = lidarr("POST http://nas:8686/api/v1/artist" to {
            added = true
            HttpResponse(201u, """{"id":9,"artistName":"Wet Leg","foreignArtistId":"1b2a","images":[]}""".toByteArray())
        })
        val service = LidarrService(prefs, ReversingBox(), transport)
        service.save("nas:8686", "k")
        val message = service.requestAlbum("Wet Leg", "Wet Leg", waitMs = 0, attempts = 2)
        assertTrue(added)
        val body = String(transport.sent("POST", "/artist").single().body)
        assertTrue("added without taking every album", body.contains("\"monitor\":\"none\"") && body.contains("\"searchForMissingAlbums\":false"))
        assertEquals("Lidarr doesn't know Wet Leg by Wet Leg yet", message)
    }

    @Test fun theQueueShowsProgressAndTimeLeftReadably() = runBlocking {
        val service = LidarrService(prefs, ReversingBox(), lidarr())
        service.save("nas:8686", "k")
        service.refreshQueue()
        assertEquals(0.75, service.queue.value.first().progress, 1e-9)
        val time = { raw: String? -> com.luke.tracks.ui.library.servers.timeLeft(raw) }
        assertEquals("3 min left", time(service.queue.value.first().timeLeft))
        assertEquals("Almost done", time("00:00:20"))
        assertEquals("25 hr 0 min left", time("1.01:00:00"))
        assertNull(time(null))
        assertNull(time("soon"))
    }
}

class OctoServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val server = SubsonicServer("s1", "Octo at home", "http://octo.local:4533", "luke", "sealed")

    @After fun tearDown() = scope.cancel()

    private fun song(id: String, title: String, artist: String, external: Boolean?) =
        """{"id":"$id","title":"$title","artist":"$artist"${external?.let { ",\"isExternal\":$it" }.orEmpty()}}"""

    private fun access(type: String? = null, songs: List<String>, starred: MutableList<String> = mutableListOf(), synced: MutableList<String> = mutableListOf()) = object : ServerAccess {
        override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(ServerEntry(server, ServerStatus.Ready(10))))
        override fun client(server: SubsonicServer) = SubsonicClient(server, "pw", SubsonicTransport { url ->
            val body = when (url.pathSegments.last()) {
                "ping" -> """{"subsonic-response":{"status":"ok"${type?.let { ",\"type\":\"$it\"" }.orEmpty()}}}"""
                "search3" -> """{"subsonic-response":{"status":"ok","searchResult3":{"song":[${songs.joinToString(",")}]}}}"""
                "star" -> { starred += url.queryParameter("id")!!; """{"subsonic-response":{"status":"ok"}}""" }
                else -> """{"subsonic-response":{"status":"ok"}}"""
            }
            200 to body
        })
        override suspend fun sync(server: SubsonicServer) { synced += server.id }
    }

    @Test fun aServerThatFindsSongsElsewhereCanFetch() = runBlocking {
        val octo = OctoService(MemoryPrefs(), access(songs = listOf(song("1", "Ours", "Band", false), song("x9", "Theirs", "Band", true))), scope, { false })
        octo.probe(server)
        assertEquals(FetchCapability.Fetches, octo.capability("s1"))
        assertEquals(listOf(server), octo.fetchingServers())
    }

    @Test fun plainServersOnlyPlayAndOctoByNameFetches() = runBlocking {
        val prefs = MemoryPrefs()
        val plain = OctoService(prefs, access(songs = listOf(song("1", "Ours", "Band", null))), scope, { false })
        plain.probe(server)
        assertEquals(FetchCapability.PlaysOnly, plain.capability("s1"))
        assertTrue(plain.fetchingServers().isEmpty())
        assertEquals("remembered across launches", FetchCapability.PlaysOnly, OctoService(prefs, access(songs = emptyList()), scope, { false }).capability("s1"))

        val named = OctoService(MemoryPrefs(), access(type = "octo", songs = emptyList()), scope, { false })
        named.probe(server)
        assertEquals(FetchCapability.Fetches, named.capability("s1"))
    }

    @Test fun findingAndGettingASong() = runBlocking {
        val starred = mutableListOf<String>()
        val have = mutableSetOf<String>()
        val octo = OctoService(
            MemoryPrefs(),
            access(type = "octo", songs = listOf(song("x1", "Havana (feat. Young Thug)", "Camila Cabello", true)), starred = starred),
            scope,
            inLibrary = { it in have },
        )
        octo.probe(server)
        val find = octo.find("Havana", "Camila Cabello")
        assertNotNull(find)
        assertTrue(find!!.isExternal)
        assertNull(octo.find("Creep", "Radiohead"))

        assertEquals("Getting “Havana (feat. Young Thug)”", octo.get(find))
        assertEquals(listOf("x1"), starred)
        val identity = songIdentity("Havana (feat. Young Thug)", "Camila Cabello")
        assertTrue(octo.isWaiting(identity))
        have += identity
        octo.settle()
        assertFalse("arrived, so no longer waiting", octo.isWaiting(identity))
    }
}

package com.luke.tracks.library

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubsonicClientTest {
    private val server = SubsonicServer(id = "s1", name = "Home", url = "https://music.example.com/navidrome", username = "luke+music@example.com", salt = "fixedsalt")

    private fun client(transport: SubsonicTransport = SubsonicTransport { 200 to OK }) =
        SubsonicClient(server, password = "sesame", transport = transport, newSalt = { "c19b2d" })

    @Test fun tokenIsMd5OfPasswordAndSaltInLowerHex() {
        // The example in the Subsonic API documentation.
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicClient.token("sesame", "c19b2d"))
    }

    @Test fun everyRequestCarriesTheSignature() {
        val url = client().url("ping")
        assertEquals("/navidrome/rest/ping", url.encodedPath)
        assertEquals("luke+music@example.com", url.queryParameter("u"))
        assertEquals("26719a1196d2a940705a59634eb18eab", url.queryParameter("t"))
        assertEquals("c19b2d", url.queryParameter("s"))
        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("Tracks", url.queryParameter("c"))
        assertEquals("json", url.queryParameter("f"))
        assertNull("the password never goes over the wire", url.queryParameter("p"))
        assertTrue(!url.toString().contains("sesame"))
    }

    @Test fun plusSignsAreEncodedSoServersDontReadThemAsSpaces() {
        val url = client().url("search3", mapOf("query" to "Florence + the Machine"))
        assertTrue(url.encodedQuery!!.contains("u=luke%2Bmusic%40example.com"))
        assertTrue(url.encodedQuery!!.contains("query=Florence%20%2B%20the%20Machine"))
    }

    @Test fun streamAndCoverAddresses() {
        val stream = client().streamUrl("42")
        assertEquals("/navidrome/rest/stream", stream.encodedPath)
        assertEquals("raw", stream.queryParameter("format"))
        assertNull(stream.queryParameter("f"))
        val slow = client().streamUrl("42", maxBitRate = 128)
        assertEquals("128", slow.queryParameter("maxBitRate"))
        assertEquals("mp3", slow.queryParameter("format"))

        // Covers use the server's own salt, so the address is stable and stays cached.
        val cover = client().coverArtUrl("al-7", size = 600)
        assertEquals("fixedsalt", cover.queryParameter("s"))
        assertEquals(SubsonicClient.token("sesame", "fixedsalt"), cover.queryParameter("t"))
        assertEquals(cover, client().coverArtUrl("al-7", size = 600))
    }

    @Test fun errorsReadAsWhatWentWrong() {
        assertThrows<SubsonicError.WrongCredentials> { SubsonicClient.decode(error(40), 200) }
        assertThrows<SubsonicError.WrongCredentials> { SubsonicClient.decode(error(41), 200) }
        assertThrows<SubsonicError.Incompatible> { SubsonicClient.decode(error(30), 200) }
        assertThrows<SubsonicError.NotFound> { SubsonicClient.decode(error(70), 200) }
        assertThrows<SubsonicError.Server> { SubsonicClient.decode(error(0), 200) }
        assertThrows<SubsonicError.NotSubsonic> { SubsonicClient.decode("<html>Welcome</html>", 200) }
        assertThrows<SubsonicError.Http> { SubsonicClient.decode("Bad gateway", 502) }
    }

    @Test fun parsesSearchWithNumberOrStringIds() = runBlocking {
        val body = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{
              "artist":[{"id":7,"name":"Low Tide Club"}],
              "album":[{"id":"al-1","name":"Saltwater","artist":"Low Tide Club","artistId":7,"year":2023,"coverArt":"al-1"}],
              "song":[
                {"id":101,"title":"Undertow","artist":"Low Tide Club","album":"Saltwater","albumId":"al-1","track":3,"discNumber":1,"duration":214,"suffix":"flac","coverArt":"al-1","genre":"Indie","futureField":{"x":1}},
                {"id":"102","title":"Harbor Lights","album":"Saltwater","albumId":"al-1","duration":187}
              ]}}}
        """.trimIndent()
        val result = client { 200 to body }.search("salt")
        assertEquals("7", result.artists.single().id)
        assertEquals("7", result.albums.single().artistId)
        assertEquals(listOf("101", "102"), result.songs.map { it.id })
        assertEquals(214, result.songs[0].duration)
        assertEquals(3, result.songs[0].track)
        assertNull(result.songs[1].artist)
    }

    @Test fun parsesArtistsAndAlbums() = runBlocking {
        val artists = """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"L","artist":[{"id":"a1","name":"Low Tide Club","albumCount":2}]},{"name":"M","artist":[{"id":"a2","name":"Marigold"}]}]}}}"""
        assertEquals(listOf("Low Tide Club", "Marigold"), client { 200 to artists }.artists().map { it.name })

        val album = """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Saltwater","artist":"Low Tide Club","song":[{"id":"1","title":"Undertow"}]}}}"""
        val (meta, songs) = client { 200 to album }.album("al-1")
        assertEquals("Saltwater", meta.name)
        assertEquals("Undertow", songs.single().title)

        val list = """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"al-1","name":"Saltwater"}]}}}"""
        assertEquals("al-1", client { 200 to list }.albums(SubsonicClient.AlbumListType.Newest).single().id)
    }

    @Test fun pagesThroughEverySong() = runBlocking {
        val asked = mutableListOf<HttpUrl>()
        val transport = SubsonicTransport { url ->
            asked += url
            val offset = url.queryParameter("songOffset")!!.toInt()
            val count = if (offset == 0) 2 else 1
            200 to songs((offset until offset + count).map { "s$it" })
        }
        val all = client(transport).allSongs(pageSize = 2)
        assertEquals(listOf("s0", "s1", "s2"), all.map { it.id })
        assertEquals(listOf("0", "2"), asked.map { it.queryParameter("songOffset") })
        assertEquals("", asked.first().queryParameter("query"))
    }

    @Test fun walksAlbumsWhenSearchNeedsAQuery() = runBlocking {
        val transport = SubsonicTransport { url ->
            200 to when (url.pathSegments.last()) {
                "search3" -> songs(emptyList())
                "getAlbumList2" -> """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"al-1","name":"A"}]}}}"""
                "getAlbum" -> """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"A","song":[{"id":"9","title":"Nine"}]}}}"""
                else -> OK
            }
        }
        assertEquals(listOf("9"), client(transport).allSongs().map { it.id })
    }

    @Test fun unreachableServersSaySo() = runBlocking {
        try {
            client { throw java.net.UnknownHostException("music.example.com") }.ping()
            fail("expected an error")
        } catch (error: SubsonicError) {
            assertTrue(error is SubsonicError.Unreachable)
        }
    }

    @Test fun baseAddressWithoutPath() {
        val plain = SubsonicClient(server.copy(url = "http://192.168.1.20:4533"), "pw", { 200 to OK }).url("ping")
        assertEquals("http://192.168.1.20:4533/rest/ping".toHttpUrl().encodedPath, plain.encodedPath)
    }

    private fun songs(ids: List<String>) =
        """{"subsonic-response":{"status":"ok","searchResult3":{"song":[${ids.joinToString(",") { """{"id":"$it","title":"Song $it"}""" }}]}}}"""

    private fun error(code: Int) = """{"subsonic-response":{"status":"failed","error":{"code":$code,"message":"Nope"}}}"""

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            fail("expected ${T::class.simpleName}, got $error")
        }
        fail("expected ${T::class.simpleName}")
    }

    private companion object {
        const val OK = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
    }
}

class ServerAddressTest {
    private fun url(text: String) = ServerAddress.url(text)?.let(ServerAddress::stored)

    @Test fun homeServersGainHttp() {
        mapOf(
            "192.168.1.223:5274" to "http://192.168.1.223:5274",
            "192.168.1.20" to "http://192.168.1.20",
            "localhost:4533" to "http://localhost:4533",
            "navidrome.local" to "http://navidrome.local",
            "nas" to "http://nas",
            "music.example.com:4533" to "http://music.example.com:4533",
            "[fe80::1]:4533" to "http://[fe80::1]:4533",
            "fe80::1" to "http://[fe80::1]",
        ).forEach { (typed, expected) -> assertEquals(typed, expected, url(typed)) }
    }

    @Test fun internetServersGainHttps() {
        mapOf(
            "music.example.com" to "https://music.example.com",
            "music.example.com:443" to "https://music.example.com",
            "example.com/navidrome" to "https://example.com/navidrome",
        ).forEach { (typed, expected) -> assertEquals(typed, expected, url(typed)) }
    }

    @Test fun tidiedAndSchemeKept() {
        mapOf(
            "  192.168.1.223:5274/ \n" to "http://192.168.1.223:5274",
            "https://192.168.1.4:4533//" to "https://192.168.1.4:4533",
            "HTTP://music.example.com" to "http://music.example.com",
            "http://music.example.com/sub/" to "http://music.example.com/sub",
        ).forEach { (typed, expected) -> assertEquals(typed, expected, url(typed)) }
    }

    @Test fun refusesWhatCantBeAWebServer() {
        listOf("", "   ", "/", "ftp://nope", "http://", "https:///path").forEach { assertNull(it, url(it)) }
    }

    @Test fun flagsUnencryptedAddresses() {
        assertTrue(ServerAddress.isUnencrypted(ServerAddress.url("192.168.1.20:4533")!!))
        assertTrue(!ServerAddress.isUnencrypted(ServerAddress.url("music.example.com")!!))
        assertTrue(!ServerAddress.isUnencrypted(ServerAddress.url("localhost:4533")!!))
    }
}

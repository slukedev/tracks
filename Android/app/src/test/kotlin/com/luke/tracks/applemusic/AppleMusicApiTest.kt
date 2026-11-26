package com.luke.tracks.applemusic

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fixtures are the examples in Apple's Apple Music API reference, and responses in their shape. */
fun fixture(name: String): String =
    requireNotNull(AppleMusicApiTest::class.java.classLoader!!.getResource("applemusic/$name")) { name }.readText()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppleMusicApiTest {
    private val server = MockWebServer()
    private val now = System.currentTimeMillis() / 1000
    private var userToken: String? = "music-user-token"

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private val tokens = BuildTimeDeveloperToken(jwt(now, now + 86_400))
    private val api by lazy { AppleMusicApi(OkHttpClient(), tokens, { userToken }, server.url("/")) }

    @Test fun libraryPagesKeepTheirCatalogIdsAndFollowNext() = runBlocking {
        server.enqueue(MockResponse(body = fixture("library-songs.json")))
        val page = api.librarySongs()

        val request = server.takeRequest()
        assertEquals("/v1/me/library/songs", request.url.encodedPath)
        assertEquals("0", request.url.queryParameter("offset"))
        assertEquals("Bearer ${tokens.cached()!!.jwt}", request.headers["Authorization"])
        assertEquals("music-user-token", request.headers["Music-User-Token"])

        assertEquals(listOf("Efecto", "Entropy", "Get U"), page.items.map { it.title })
        val efecto = page.items.first()
        assertEquals("i.PkdJNdAIrQozOW", efecto.id)
        assertEquals("1622045954", efecto.catalogId)
        assertEquals("Bad Bunny", efecto.artistName)
        assertEquals(213_061L, efecto.durationMs)
        assertEquals(2022, efecto.year)
        assertTrue(efecto.playable)
        assertEquals(3, page.nextOffset)
    }

    @Test fun anUploadWithoutACatalogIdIsListedButCantPlay() = runBlocking {
        server.enqueue(MockResponse(body = fixture("library-songs-last.json")))
        val page = api.librarySongs(offset = 3)
        val upload = page.items.single()
        assertNull(upload.catalogId)
        assertFalse(upload.playable)
        assertNull("the last page", page.nextOffset)
        val song = upload.toPlayableSong { title, artist -> "$title|$artist" }
        assertEquals("applemusic:library:i.upload1", song.mediaId)
    }

    @Test fun aSongPlaysByItsCatalogIdFromTheLibraryOrTheCatalog() = runBlocking {
        server.enqueue(MockResponse(body = fixture("library-songs.json")))
        val song = api.librarySongs().items[1].toPlayableSong { title, artist -> "$title|$artist" }
        assertEquals("applemusic:song:1613600188", song.mediaId)
        assertEquals("applemusic", song.uri.scheme)
        assertEquals("1613600188", AppleMusicUris.catalogId(song.uri.toString()))
        assertEquals(221_000L, AppleMusicUris.durationMs(song.uri))
        assertEquals("Entropy|Beach Bunny", song.identity)
        assertTrue(song.artworkUri.toString().endsWith("/600x600bb.jpg"))
    }

    @Test fun librarySearchSendsTheTermAndReadsEachKind() = runBlocking {
        server.enqueue(MockResponse(body = fixture("library-search.json")))
        val results = api.searchLibrary("Happier")
        val request = server.takeRequest()
        assertEquals("/v1/me/library/search", request.url.encodedPath)
        assertEquals("Happier", request.url.queryParameter("term"))
        assertEquals("library-songs,library-albums,library-playlists", request.url.queryParameter("types"))
        assertEquals(5, results.songs.size)
        assertEquals("1564530960", results.songs.first().catalogId)
        assertEquals(listOf("Happier Than Ever"), results.albums.map { it.title })
        assertEquals(AppleMusicCollection.Kind.LibraryAlbum, results.albums.single().kind)
        assertNull("Apple's example has a broken address; it's left out", results.songs.first().artworkTemplate?.let { artwork(it, 100) })
    }

    @Test fun catalogSearchNeedsNoUserToken() = runBlocking {
        userToken = null
        server.enqueue(MockResponse(body = fixture("catalog-search.json")))
        val results = api.searchCatalog("gb", "entropy")
        val request = server.takeRequest()
        assertEquals("/v1/catalog/gb/search", request.url.encodedPath)
        assertNull(request.headers["Music-User-Token"])
        assertEquals(listOf("1613600188", "1622045954"), results.songs.map { it.catalogId })
        assertEquals("Emotional Creature", results.albums.single().title)
        assertEquals("Apple Music Alternative", results.playlists.single().subtitle)
        assertEquals(AppleMusicCollection.Kind.Playlist, results.playlists.single().kind)
    }

    @Test fun theStorefrontProvesTheAccount() = runBlocking {
        server.enqueue(MockResponse(body = fixture("storefront.json")))
        assertEquals("gb", api.storefront())
        assertEquals("/v1/me/storefront", server.takeRequest().url.encodedPath)
    }

    @Test fun anAlbumsSongsComeFromEveryPage() = runBlocking {
        server.enqueue(MockResponse(body = fixture("library-songs.json")))
        server.enqueue(MockResponse(body = fixture("library-songs-last.json")))
        val album = AppleMusicCollection("l.qrRWYhq", AppleMusicCollection.Kind.LibraryAlbum, "Happier Than Ever", "Billie Eilish")
        assertEquals(4, api.songs(album, "gb").size)
        assertEquals("/v1/me/library/albums/l.qrRWYhq/tracks", server.takeRequest().url.encodedPath)
        assertEquals("3", server.takeRequest().url.queryParameter("offset"))
    }

    @Test fun aRefusedDeveloperTokenIsLetGoAndTheRequestTriedOnce() = runBlocking {
        val firstJwt = jwt(now, now + 86_400)
        val secondJwt = jwt(now, now + 2 * 86_400)
        val first = BuildTimeDeveloperToken(firstJwt)
        val second = BuildTimeDeveloperToken(secondJwt)
        val api = AppleMusicApi(OkHttpClient(), FirstDeveloperToken(listOf(first, second)), { userToken }, server.url("/"))
        server.enqueue(MockResponse(code = 401, body = fixture("error-401.json")))
        server.enqueue(MockResponse(body = fixture("storefront.json")))
        assertEquals("gb", api.storefront())
        assertFalse(first.available)
        assertEquals("Bearer $firstJwt", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer $secondJwt", server.takeRequest().headers["Authorization"])

        server.enqueue(MockResponse(code = 401, body = fixture("error-401.json")))
        server.enqueue(MockResponse(code = 401, body = fixture("error-401.json")))
        assertProblem(AppleMusicProblem.Expired) { api.storefront() }
    }

    @Test fun recommendationsAreRowsOfAlbumsAndPlaylistsWithoutStations() = runBlocking {
        server.enqueue(MockResponse(body = fixture("recommendations.json")))
        val rows = api.recommendations()
        val request = server.takeRequest()
        assertEquals("/v1/me/recommendations", request.url.encodedPath)
        assertEquals("Bearer ${tokens.cached()!!.jwt}", request.headers["Authorization"])
        assertEquals("music-user-token", request.headers["Music-User-Token"])
        assertEquals(listOf("Made for You", "Because You Listened to Beach Bunny", "Recently Played"), rows.map { it.title })
        val mix = rows.first().items.single()
        assertEquals("pl.pm-20e9f373919da1ac5d17deb5c3b55c3a", mix.id)
        assertEquals(AppleMusicCollection.Kind.Playlist, mix.kind)
        assertEquals("Apple Music for Me", mix.subtitle)
        assertEquals(AppleMusicCollection.Kind.Album, rows[1].items.single().kind)
        assertEquals(AppleMusicCollection.Kind.LibraryAlbum, rows[2].items.single().kind)
    }

    @Test fun recentlyPlayedReadsTwoPagesAndLeavesOutStations() = runBlocking {
        server.enqueue(MockResponse(body = fixture("recent-played.json")))
        server.enqueue(MockResponse(body = fixture("recent-played-last.json")))
        val recent = api.recentlyPlayed()
        val first = server.takeRequest()
        assertEquals("/v1/me/recent/played", first.url.encodedPath)
        assertEquals("10", first.url.queryParameter("limit"))
        assertEquals("music-user-token", first.headers["Music-User-Token"])
        assertEquals("10", server.takeRequest().url.queryParameter("offset"))
        assertEquals(listOf("Emotional Creature", "Road Trip", "Today's Hits"), recent.map { it.title })
        assertEquals(AppleMusicCollection.Kind.LibraryPlaylist, recent[1].kind)
    }

    @Test fun topAlbumsComeFromTheStorefrontsChartWithoutAUserToken() = runBlocking {
        server.enqueue(MockResponse(body = fixture("charts-albums.json")))
        val albums = api.topAlbums("gb")
        val request = server.takeRequest()
        assertEquals("/v1/catalog/gb/charts", request.url.encodedPath)
        assertEquals("albums", request.url.queryParameter("types"))
        assertNull(request.headers["Music-User-Token"])
        assertEquals(listOf("Abbey Road", "Un Verano Sin Ti"), albums.map { it.title })
    }

    @Test fun aPlaylistMadeForYouIsReadWithTheUserToken() = runBlocking {
        server.enqueue(MockResponse(body = fixture("library-songs-last.json")))
        val mix = AppleMusicCollection("pl.pm-20e9", AppleMusicCollection.Kind.Playlist, "Favourites Mix", null)
        api.songs(mix, "gb")
        val request = server.takeRequest()
        assertEquals("/v1/catalog/gb/playlists/pl.pm-20e9/tracks", request.url.encodedPath)
        assertEquals("music-user-token", request.headers["Music-User-Token"])
    }

    @Test fun noDeveloperTokenAtAllIsntCalledExpired() = runBlocking {
        val none = AppleMusicApi(OkHttpClient(), BuildTimeDeveloperToken(""), { userToken }, server.url("/"))
        assertProblem(AppleMusicProblem.NoToken) { none.recommendations() }
        assertFalse("not about the account", AppleMusicProblem.NoToken.accountWide)
        assertEquals(0, server.requestCount)
    }

    @Test fun eachFailureSaysWhatHappened() = runBlocking {
        server.enqueue(MockResponse(code = 403))
        assertProblem(AppleMusicProblem.SignedOut) { api.librarySongs() }
        server.enqueue(MockResponse(code = 429))
        assertProblem(AppleMusicProblem.Busy) { api.searchCatalog("gb", "x") }
        server.enqueue(MockResponse(code = 404))
        assertProblem(AppleMusicProblem.Unavailable) { api.searchCatalog("gb", "x") }
        val nowhere = AppleMusicApi(OkHttpClient(), tokens, { userToken }, okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(1).build())
        assertProblem(AppleMusicProblem.Offline) { nowhere.librarySongs() }
        userToken = null
        assertProblem(AppleMusicProblem.SignedOut) { api.librarySongs() }
    }

    private suspend fun assertProblem(expected: AppleMusicProblem, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (error: AppleMusicException) {
            assertEquals(expected, error.problem)
        }
    }
}

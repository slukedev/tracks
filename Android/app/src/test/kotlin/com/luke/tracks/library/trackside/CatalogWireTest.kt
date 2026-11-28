package com.luke.tracks.library.trackside

import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.ServerEndpoints
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun fixture(name: String): String =
    requireNotNull(CatalogWireTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

class CatalogWireTest {
    @Test fun theServices() {
        val list = TracksideJson.decodeFromString(TracksideCatalogServices.serializer(), fixture("catalog-services.json"))
        assertEquals("ok", list.state)
        assertEquals(listOf("Spotify", "Tidal", "Qobuz"), list.services.map { it.name })
        val spotify = list.services[0]
        assertEquals("bqtyy4kcexcsg6wa0y4w5", spotify.id)
        assertEquals("spotify", spotify.logoKey)
        assertEquals("high", spotify.quality)
        assertTrue(spotify.searches)
        assertTrue(spotify.can(CatalogCapability.RADIO))
        val qobuz = list.services[2]
        assertEquals("signed_out", qobuz.account.state)
        assertFalse("a signed-out service isn't searched", qobuz.searches)
        assertEquals("ok", TracksideJson.decodeFromString(CatalogEvents.Services.serializer(), fixture("catalog-services-event.json")).state)
    }

    @Test fun aSearchOnTheServices() {
        val search = TracksideJson.decodeFromString(TracksideCatalogSearch.serializer(), fixture("catalog-search.json"))
        assertFalse(search.complete)
        assertFalse(search.isUnavailable)
        assertEquals(listOf("ok", "timed_out"), search.services.map { it.state })
        val track = search.tracks!!.items.single()
        assertEquals("mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0", track.ref)
        assertEquals("Halo", track.title)
        assertEquals("Beyoncé", track.artist)
        assertEquals("sdjcz08vf67h7cx28snq6", track.songId)
        assertEquals("halo\u001fbeyonce", track.songKey)
        assertEquals(261_640L, track.durationMs)
        assertEquals(3, track.trackNumber)
        assertTrue(track.canPlay)
        assertFalse(track.isExplicit)
        assertEquals("mc1.YXJ0aXN0H3Nwb3RpZnkfNnZXRE85NjlQdk5xTllISU9XNXYwbQ", track.artists!!.single().ref)
        val album = search.albums!!.items.single()
        assertEquals(TracksideCatalogItem.Kind.Album, album.kindValue)
        assertEquals("I Am... Sasha Fierce", album.name)
        assertEquals(2008, album.year)
        assertEquals(11, album.trackCount)
        val artist = search.artists!!.items.single()
        assertEquals(TracksideCatalogItem.Kind.Artist, artist.kindValue)
        assertEquals("beyonce", artist.identity)
        val playlist = search.playlists!!.items.single()
        assertEquals("Spotify", playlist.owner)
        assertEquals(50, playlist.trackCount)
        assertNull("not asked for", search.radios)
    }

    @Test fun theLibrarySearchCarriesTheServicesApart() {
        val results = TracksideJson.decodeFromString(TracksideSearchResults.serializer(), fixture("search-with-catalog.json"))
        assertEquals("sdjcz08vf67h7cx28snq6", results.songs!!.items.single().id)
        val catalog = results.catalog!!
        assertTrue(catalog.complete)
        val track = catalog.tracks!!.items.single()
        assertEquals("lossless", track.quality)
        assertEquals("sdjcz08vf67h7cx28snq6", track.songId)
        assertNull("an artist without a page", track.artists!!.single().ref)
        assertNull(results.catalog!!.albums)

        val unavailable = TracksideJson.decodeFromString(TracksideCatalogSearch.serializer(), """{"complete": true, "services": [], "state": "unavailable"}""")
        assertTrue(unavailable.isUnavailable)
        assertTrue(unavailable.isEmpty)
        val old = TracksideJson.decodeFromString(TracksideSearchResults.serializer(), fixture("search.json"))
        assertNull("a server without services leaves it out", old.catalog)
    }

    @Test fun itemPages() {
        val album = TracksideJson.decodeFromString(TracksideCatalogPage.serializer(), fixture("catalog-album.json"))
        assertEquals("I Am... Sasha Fierce", album.item!!.name)
        assertEquals(listOf("Halo", "Single Ladies (Put a Ring on It)"), album.tracks.map { it.title })
        assertEquals(listOf(3, 9), album.tracks.map { it.trackNumber })
        assertNull(album.tracks[1].songId)

        val artist = TracksideJson.decodeFromString(TracksideCatalogPage.serializer(), fixture("catalog-artist.json"))
        assertEquals(TracksideCatalogItem.Kind.Artist, artist.item!!.kindValue)
        assertEquals(1, artist.topTracks.size)
        assertEquals(1, artist.albums.size)
        assertTrue(artist.tracks.isEmpty())

        val playlist = TracksideJson.decodeFromString(TracksideCatalogPage.serializer(), fixture("catalog-playlist.json"))
        assertEquals("This Is Beyoncé", playlist.playlist!!.name)
        assertEquals(false, playlist.playlist!!.editable)
        assertEquals("eyJvIjoxMDB9", playlist.nextCursor)
        assertEquals(1, playlist.tracks.size)
    }

    @Test fun aServicesLibrary() {
        val library = TracksideJson.decodeFromString(TracksideCatalogLibrary.serializer(TracksideCatalogItem.serializer()), fixture("catalog-library.json"))
        assertEquals("This Is Beyoncé", library.items.single().name)
        assertEquals(TracksideCatalogItem.Kind.Playlist, library.items.single().kindValue)
        assertEquals("eyJvIjoxMDB9", library.nextCursor)
        assertEquals("2026-11-14T09:00:00.000Z", library.syncedAt)
    }

    @Test fun unknownValuesAndFieldsStillRead() {
        val service = TracksideJson.decodeFromString(
            TracksideCatalogService.serializer(),
            """{"account": {"state": "frozen"}, "capabilities": ["search", "lyrics"], "id": "b1", "logo_key": "newservice", "name": "New", "colour": "red"}""",
        )
        assertFalse(service.isSignedIn)
        assertEquals(listOf("search", "lyrics"), service.capabilities)
        val item = TracksideJson.decodeFromString(TracksideCatalogItem.serializer(), """{"kind": "podcast", "ref": "mc1.x", "name": "Show"}""")
        assertEquals(TracksideCatalogItem.Kind.Unknown, item.kindValue)
        val track = TracksideJson.decodeFromString(TracksideCatalogTrack.serializer(), """{"ref": "mc1.y", "title": "T", "playable": false, "explicit": "explicit"}""")
        assertFalse(track.canPlay)
        assertTrue(track.isExplicit)
    }

    @Test fun theServicesPicks() {
        val picks = TracksideJson.decodeFromString(TracksideCatalogRecommendations.serializer(), fixture("catalog-recommendations.json"))
        assertFalse(picks.complete)
        assertEquals(listOf("ok", "ok", "timed_out"), picks.services.map { it.state })
        assertEquals(listOf("Stations for You", "Jump back in"), picks.shelves.map { it.title })
        val stations = picks.shelves[0]
        assertEquals("w1dgyc462epk20x8myctx", stations.id)
        assertEquals("bm1zt0k6dk6mzz0n3vw5d", stations.serviceId)
        assertNull(stations.subtitle)
        assertFalse(stations.isTimeline)
        val station = (stations.items.single() as TracksideCatalogEntry.Item).item
        assertEquals(TracksideCatalogItem.Kind.Radio, station.kindValue)
        assertEquals("Beyoncé & Similar Artists Station", station.name)
        assertEquals("mf3w0g2x8kq1v7r5nd9ce", station.artworkId)
        val recent = picks.shelves[1]
        assertEquals("Your recent rotation", recent.subtitle)
        assertTrue(recent.isTimeline)
        assertEquals(listOf("track", "album", "playlist", "artist"), recent.items.map {
            when (it) {
                is TracksideCatalogEntry.Track -> it.track.kind
                is TracksideCatalogEntry.Item -> it.item.kind
            }
        })
        val halo = (recent.items[0] as TracksideCatalogEntry.Track).track
        assertEquals("Halo", halo.title)
        assertEquals("sdjcz08vf67h7cx28snq6", halo.songId)
        assertEquals("mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0", recent.items[0].ref)
        assertEquals("bqtyy4kcexcsg6wa0y4w5", recent.items[0].serviceId)
        val album = (recent.items[1] as TracksideCatalogEntry.Item).item
        assertEquals("i am... sasha fierce\u001fbeyonce", album.albumKey)
        assertEquals(11, album.trackCount)
        assertEquals("beyonce", (recent.items[3] as TracksideCatalogEntry.Item).item.identity)
    }

    @Test fun picksOfAKindThisBuildDoesntKnowAreLeftOut() {
        val picks = TracksideJson.decodeFromString(
            TracksideCatalogRecommendations.serializer(),
            """{"shelves": [{"id": "w1", "service_id": "b1", "title": "Mixed", "type": "carousel", "items": [
                {"kind": "podcast", "ref": "mc1.p", "name": "Show"},
                {"kind": "album", "ref": "mc1.a", "name": "Album"},
                {"kind": "track"},
                "nonsense"
            ]}]}""",
        )
        assertTrue("left out, it's taken as whole", picks.complete)
        val shelf = picks.shelves.single()
        assertEquals("carousel", shelf.type)
        assertFalse(shelf.isTimeline)
        assertEquals(listOf("mc1.a"), shelf.items.map { it.ref })
    }

    @Test fun servicesUnavailableReadsItsWaitAndOldServicesLackThePicks() {
        val error = TracksideClient.error(TracksideAnswer(503, fixture("error-catalog-unavailable.json"), retryAfter = "10"))
        assertEquals(10L, (error as TracksideError.CatalogUnavailable).retryAfterSeconds)
        val list = TracksideJson.decodeFromString(TracksideCatalogServices.serializer(), fixture("catalog-services.json"))
        assertTrue(list.services[0].can(CatalogCapability.RECOMMENDATIONS))
        assertFalse(list.services[1].can(CatalogCapability.RECOMMENDATIONS))
        assertFalse(TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me-without-acquisition.json")).offersRecommendations)
    }

    @Test fun theMeFeaturesSayWhatThisKeyMayDo() {
        val me = TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me.json"))
        assertTrue(me.offersRecommendations)
        assertTrue(me.offersCatalog)
        assertTrue(me.offersRelay)
        assertTrue(me.offersRelayCellular)
        val old = TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me-without-acquisition.json"))
        assertFalse(old.offersCatalog)
        assertFalse(old.offersRelay)
        assertFalse(old.offersRelayCellular)
    }
}

class RelayWireTest {
    @Test fun aPlayingSession() {
        val session = TracksideJson.decodeFromString(TracksideRelaySession.serializer(), fixture("relay-session.json"))
        assertEquals("h9zvca9p26dymt4v7cxj5", session.id)
        assertEquals("o1a0psgqvec0jm734s9tv", session.speakerId)
        assertEquals(TracksideRelaySession.State.Playing, session.stateValue)
        assertEquals(3L, session.generation)
        assertEquals("cellular", session.tier)
        assertEquals("/api/v1/relay/h9zvca9p26dymt4v7cxj5/3/master.m3u8", session.hls)
        assertEquals("aac", session.stream!!.format!!.codec)
        assertEquals(256, session.stream!!.format!!.bitrateKbps)
        assertEquals(4_000L, session.latencyMs)
        assertEquals(600L, session.idleTimeoutS)
        assertEquals(30L, session.fetchTimeoutS)
    }

    @Test fun aStartingSessionAlreadyNamesTheFirstGeneration() {
        val session = TracksideJson.decodeFromString(TracksideRelaySession.serializer(), fixture("relay-session-starting.json"))
        assertEquals(TracksideRelaySession.State.Starting, session.stateValue)
        assertEquals(0L, session.generation)
        assertEquals("/api/v1/relay/h9zvca9p26dymt4v7cxj5/1/master.m3u8", session.hls)
        assertEquals("flac", session.stream!!.format!!.codec)
        assertEquals(16, session.stream!!.format!!.bitDepth)
    }

    @Test fun anEndedSessionHasNothingToPlay() {
        val session = TracksideJson.decodeFromString(TracksideRelaySession.serializer(), fixture("relay-session-ended.json"))
        assertTrue(session.isEnded)
        assertEquals("idle", session.endedReason)
        assertNull(session.hls)
    }

    @Test fun anUnknownStateIsntTakenForPlaying() {
        val session = TracksideJson.decodeFromString(TracksideRelaySession.serializer(), """{"id": "h1", "state": "warming", "tier": "spatial"}""")
        assertEquals(TracksideRelaySession.State.Unknown, session.stateValue)
        assertEquals("spatial", session.tier)
    }

    @Test fun theRelaySpeakerIsThisPhone() {
        val speaker = TracksideJson.decodeFromString(TracksideSpeaker.serializer(), fixture("relay-speaker.json"))
        assertEquals(TracksideSpeaker.Kind.Phone, speaker.kindValue)
        assertEquals(true, speaker.relay!!.thisDevice)
        assertEquals("h9zvca9p26dymt4v7cxj5", speaker.relay!!.sessionId)
        assertFalse(speaker.can(SpeakerCapability.VOLUME))
    }
}

class CatalogClientTest {
    private val key = "tsk_9pQvL2mN6tY0wB4kD9hJ1sF5gA7cE3uI8oP2zR6x"
    private val home = Endpoint("http://192.168.1.20:4747".toHttpUrl(), Endpoint.Kind.Lan)

    @Test fun accessNeedsTheServersApiAndTheAccountsFeature() = runTest {
        var me = fixture("me.json")
        var capabilities = fixture("capabilities.json")
        val transport = TracksideTransport { request, _ ->
            when (request.url.pathSegments.last()) {
                "capabilities" -> TracksideAnswer(200, capabilities)
                "me" -> TracksideAnswer(200, me)
                else -> TracksideAnswer(404, fixture("error-not-found.json"))
            }
        }
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        assertEquals(TracksideClient.CatalogAccess.Play(cellular = true, recommends = true, features = CatalogFeatures(save = true, radio = true, match = true, queueRemove = true, hold = true, takeover = true)), TracksideClient(endpoints, key, "d1", null, transport).catalogAccess())
        me = fixture("me.json").replace("\"recommendations\": true,", "")
        assertEquals("a server from before the picks", TracksideClient.CatalogAccess.Play(cellular = true, recommends = false, features = CatalogFeatures(save = true, radio = true, match = true, queueRemove = true, hold = true, takeover = true)), TracksideClient(endpoints, key, "d1", null, transport).catalogAccess())
        me = fixture("me.json").replace("\"relay\": true,", "")
        val browse = TracksideClient(endpoints, key, "d1", null, transport).catalogAccess()
        assertEquals("browsing only still has the picks", TracksideClient.CatalogAccess.Browse(recommends = true, features = CatalogFeatures(save = true, radio = true, match = true, queueRemove = true, hold = true, takeover = true)), browse)
        assertTrue(browse.recommends)
        me = fixture("me-without-acquisition.json")
        assertEquals(TracksideClient.CatalogAccess.None, TracksideClient(endpoints, key, "d1", null, transport).catalogAccess())
        me = fixture("me.json")
        capabilities = capabilities.replace("\"speakers\": true,", "")
        assertEquals("an old server is never asked", TracksideClient.CatalogAccess.None, TracksideClient(endpoints, key, "d1", null, transport).catalogAccess())
    }

    @Test fun searchesAndPagesAskForWhatTheContractSays() = runTest {
        val sent = mutableListOf<Request>()
        val transport = TracksideTransport { request, _ ->
            sent += request
            val path = request.url.encodedPath
            when {
                path.endsWith("/catalog/search") -> TracksideAnswer(200, fixture("catalog-search.json"))
                path.endsWith("/search") -> TracksideAnswer(200, fixture("search-with-catalog.json"))
                path.contains("/catalog/items/") -> TracksideAnswer(200, fixture("catalog-playlist.json"))
                path.endsWith("/library") -> TracksideAnswer(200, fixture("catalog-library.json"))
                path.endsWith("/catalog/recommendations") -> TracksideAnswer(200, fixture("catalog-recommendations.json"))
                else -> TracksideAnswer(404, fixture("error-not-found.json"))
            }
        }
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        val client = TracksideClient(endpoints, key, "d1", null, transport)

        client.catalogSearch("halo", services = listOf("b1", "b2"))
        assertEquals("track,album,artist,playlist", sent.last().url.queryParameter("types"))
        assertEquals("b1,b2", sent.last().url.queryParameter("services"))

        val both = client.searchWithCatalog("halo", listOf("song", "album", "artist"))
        assertEquals("album,artist,catalog,song".split(',').toSet(), sent.last().url.queryParameter("types")!!.split(',').toSet())
        assertEquals("track,album,artist,playlist", sent.last().url.queryParameter("catalog_types"))
        assertEquals(1, both.catalog!!.tracks!!.items.size)

        val ref = "mc1.cGxheWxpc3Qfc3BvdGlmeR8zN2k5ZFFaRjFEWDRzV1Nwd3EzTGlP"
        client.catalogItem(ref, cursor = "eyJvIjoxMDB9", limit = 100)
        assertEquals("/api/v1/catalog/items/$ref", sent.last().url.encodedPath)
        assertEquals("the ref is sent exactly as given", ref, sent.last().url.pathSegments.last())
        assertEquals("eyJvIjoxMDB9", sent.last().url.queryParameter("cursor"))

        client.catalogRecommendations()
        assertEquals("/api/v1/catalog/recommendations", sent.last().url.encodedPath)
        assertNull("every service unless told", sent.last().url.queryParameter("services"))
        assertEquals("20", sent.last().url.queryParameter("limit"))
        val picks = client.catalogRecommendations(listOf("b1", "b2"), limit = 80)
        assertEquals("b1,b2", sent.last().url.queryParameter("services"))
        assertEquals("never past the server's 50", "50", sent.last().url.queryParameter("limit"))
        assertEquals(2, picks.shelves.size)

        client.catalogLibrary("bqtyy4kcexcsg6wa0y4w5")
        assertEquals("/api/v1/catalog/services/bqtyy4kcexcsg6wa0y4w5/library", sent.last().url.encodedPath)
        assertEquals("playlist", sent.last().url.queryParameter("kind"))
        sent.forEach { assertEquals("Bearer $key", it.header("Authorization")) }
    }

    @Test fun theRelayStartsOnceAndChangesTier() = runTest {
        val sent = mutableListOf<Request>()
        var lose = true
        val transport = TracksideTransport { request, _ ->
            sent += request
            when {
                request.method == "POST" && lose -> { lose = false; throw java.io.IOException("lost") }
                request.method == "POST" -> TracksideAnswer(201, fixture("relay-session-starting.json"))
                request.method == "PATCH" -> TracksideAnswer(200, fixture("relay-session.json"))
                request.method == "DELETE" -> TracksideAnswer(204, "")
                else -> TracksideAnswer(200, fixture("relay-session.json"))
            }
        }
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        val client = TracksideClient(endpoints, key, "d1", null, transport)
        val play = TracksideSpeakerPlayRequest(listOf(TracksideSpeakerPlayRequest.Item(ref = "mc1.a"), TracksideSpeakerPlayRequest.Item(songId = "s1")))
        val session = client.startRelay(TracksideRelayRequest(play = play, name = "Pixel", tier = "cellular"), key = "k1")
        assertEquals(TracksideRelaySession.State.Starting, session.stateValue)
        assertEquals(2, sent.size)
        assertEquals("a lost start is sent again with the same key", listOf("k1", "k1"), sent.map { it.header("Idempotency-Key") })
        val body = TracksideJson.parseToJsonElement(sent.last().bodyText()).toString()
        assertTrue(body, body.contains("\"tier\":\"cellular\""))
        assertTrue(body, body.contains("\"ref\":\"mc1.a\""))
        assertTrue(body, body.contains("\"song_id\":\"s1\""))
        assertFalse("no nulls on the wire", body.contains("null"))

        val patched = client.setRelayTier(session.id, "lossless")
        assertEquals("PATCH", sent.last().method)
        assertEquals("/api/v1/relay/sessions/h9zvca9p26dymt4v7cxj5", sent.last().url.encodedPath)
        assertTrue(sent.last().bodyText().contains("\"tier\":\"lossless\""))
        assertEquals(3L, patched.generation)
        client.endRelay(session.id)
        assertEquals("DELETE", sent.last().method)
    }

    @Test fun refusalsSayWhy() {
        fun refuse(status: Int, body: String) = TracksideClient.error(TracksideAnswer(status, body))
        val limit = refuse(409, """{"error": {"code": "conflict", "details": {"reason": "relay_limit", "limit": 3}, "message": "x", "request_id": "r"}}""")
        assertEquals("relay_limit", (limit as TracksideError.Server).reason)
        assertEquals(3, limit.limit)
        val signedOut = refuse(409, """{"error": {"code": "conflict", "details": {"reason": "service_signed_out", "service_id": "b9"}, "message": "x"}}""")
        assertEquals("b9", (signedOut as TracksideError.Server).serviceId)
        val cellular = refuse(422, """{"error": {"code": "unsupported", "details": {"capability": "relay_cellular"}, "message": "x"}}""")
        assertEquals("relay_cellular", (cellular as TracksideError.Server).capability)
        assertTrue(refuse(503, """{"error": {"code": "catalog_unavailable", "message": "x"}}""") is TracksideError.CatalogUnavailable)
        try {
            throw refuse(404, fixture("error-not-found.json"))
        } catch (_: TracksideError.NotFound) {
        } catch (_: Throwable) {
            fail("404 is not found")
        }
    }
}

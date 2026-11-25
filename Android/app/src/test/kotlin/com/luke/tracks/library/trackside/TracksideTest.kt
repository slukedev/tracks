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
import java.time.Instant

private fun fixture(name: String): String =
    requireNotNull(TracksideWireTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

private const val SERVER = "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b"

class TracksideProofTest {
    @Test fun matchesTheServersOwnVectors() {
        val nonce = "AAAAAAAAAAAAAAAAAAAAAA"
        // The same vectors as trackside-auth's proof.rs, the ping-proof.json fixture and the iPhone app.
        assertEquals(
            "682d7ce06987ceed051cff6a1f7f067f67a95506ce02dd709e0675240bbf6c13",
            TracksideProof.expected(TracksideProof.Key.Device("d1", "tsk_example"), SERVER, "http://192.168.1.10:4533".toHttpUrl(), nonce),
        )
        assertEquals(
            "994d60fd2d2ebc2ef8dddf646d1c1002dad03e19541caa90a7461d6dadcf8f1a",
            TracksideProof.expected(TracksideProof.Key.Offer("g1", "Q7RM4X2K9TZP3W8HN5VC6BYD"), SERVER, "http://home.local:4747".toHttpUrl(), nonce),
        )
        val ping = TracksideJson.decodeFromString(TracksidePing.serializer(), fixture("ping-proof.json"))
        assertTrue(TracksideProof.verify(ping.proof, TracksideProof.Key.Device("d1", "tsk_example"), ping.serverId, "http://192.168.1.10:4533".toHttpUrl(), nonce))
    }

    @Test fun aProofIsBoundToItsKeyAddressServerAndChallenge() {
        val key = TracksideProof.Key.Device("d1", "tsk_example")
        val home = "http://192.168.1.10:4533".toHttpUrl()
        val proof = TracksideProof.expected(key, "s", home, "n1")
        assertTrue(TracksideProof.verify(proof.uppercase(), key, "s", home, "n1"))
        assertFalse(TracksideProof.verify(proof, key, "s", "https://music.example.com".toHttpUrl(), "n1"))
        assertFalse(TracksideProof.verify(proof, key, "other", home, "n1"))
        assertFalse(TracksideProof.verify(proof, key, "s", home, "n2"))
        assertFalse(TracksideProof.verify(proof, TracksideProof.Key.Device("d1", "tsk_other"), "s", home, "n1"))
        assertFalse(TracksideProof.verify(null, key, "s", home, "n1"))
    }

    @Test fun addressesAreWrittenAsTheServerReadsThem() {
        mapOf(
            "http://192.168.1.10:4533/music" to "192.168.1.10:4533",
            "https://Music.Example.com" to "music.example.com",
            "https://music.example.com:443" to "music.example.com",
            "http://box.local:80" to "box.local",
            "http://[fd00::20]:4747" to "[fd00::20]:4747",
        ).forEach { (url, expected) -> assertEquals(url, expected, TracksideProof.authority(url.toHttpUrl())) }
    }

    @Test fun challengesAreFreshAndUrlSafe() {
        val a = TracksideProof.nonce()
        assertEquals(22, a.length)
        assertTrue(a != TracksideProof.nonce())
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun keysNeverPrintTheirSecret() {
        assertFalse(TracksideProof.Key.Device("d1", "tsk_secret").toString().contains("tsk_secret"))
        assertFalse(TracksideProof.Key.Offer("o1", "CODE").toString().contains("CODE"))
    }
}

class TracksideWireTest {
    @Test fun readsSigningInAndTheServer() {
        val result = TracksideJson.decodeFromString(TracksidePairResult.serializer(), fixture("pair-result.json"))
        assertEquals("tsk_9pQvL2mN6tY0wB4kD9hJ1sF5gA7cE3uI8oP2zR6x", result.apiKey)
        assertEquals("dchjkb9k3cnapjw0kmhq6", result.device.id)
        assertEquals(SERVER, result.server.serverId)
        assertEquals(listOf("lan", "lan", "public"), result.server.urls!!.map { it.kind })
        assertFalse("expires 2026-11-21: a week is plenty", result.mediaToken!!.needsRenewal(Instant.parse("2026-11-14T09:30:00Z")))
        assertTrue("a day before expiry it's renewed", result.mediaToken!!.needsRenewal(Instant.parse("2026-11-20T10:00:00Z")))

        val caps = TracksideJson.decodeFromString(TracksideCapabilities.serializer(), fixture("capabilities.json"))
        assertTrue(caps.isTrackside)
        assertEquals(true, caps.auth?.pairRequests)
        val me = TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me.json"))
        assertEquals("https://music.example.com", me.server.urls!!.last().url)

        val approved = TracksideJson.decodeFromString(TracksidePairPoll.serializer(), fixture("pair-poll-approved.json"))
        assertEquals("approved", approved.state)
        assertEquals(result, approved.result)
        assertNull(TracksideJson.decodeFromString(TracksidePairPoll.serializer(), fixture("pair-poll-pending.json")).result)
        assertEquals(2_000L, TracksideJson.decodeFromString(TracksidePairRequest.serializer(), fixture("pair-request.json")).pollIntervalMs)
    }

    @Test fun songsReadAsTheRestOfTracksReadsServerSongs() {
        val page = TracksideJson.decodeFromString(TracksideAlbumPage.serializer(), fixture("album-page.json"))
        val song = page.songs.first().subsonic
        assertEquals("Single Ladies (Put a Ring on It)", song.title)
        assertEquals(193, song.duration)
        assertEquals("ca81rcgy3d9pfg6saekwd", song.coverArt)
        assertEquals("flac", song.suffix)
        assertEquals("Pop", song.genre)
        assertEquals(page.album.id, song.albumId)
        val search = TracksideJson.decodeFromString(TracksideSearchResults.serializer(), fixture("search.json"))
        assertEquals("beyonce", search.artists!!.items.single().identity)
    }

    @Test fun decisionsResolveUnderTheServersBasePath() {
        val direct = TracksideJson.decodeFromString(TracksidePlaybackDecision.serializer(), fixture("decision-direct.json"))
        val file = TracksideJson.decodeFromString(TracksidePlaybackDecision.serializer(), fixture("decision-file.json"))
        assertEquals("http://192.168.1.20:4747/api/v1/songs/sdjcz08vf67h7cx28snq6/stream", TracksideClient.resolve(direct.url, "http://192.168.1.20:4747".toHttpUrl()).toString())
        // A server under /music: its paths may or may not include the base, and land under it either way.
        val base = "https://example.com/music".toHttpUrl()
        assertEquals("https://example.com/music/api/v1/songs/sdjcz08vf67h7cx28snq6/stream?profile=aac-256", TracksideClient.resolve(file.url, base).toString())
        assertEquals("https://example.com/music/api/v1/x", TracksideClient.resolve("/music/api/v1/x", base).toString())
        assertEquals("https://cdn.example.net/a", TracksideClient.resolve("https://cdn.example.net/a", base).toString())
    }

    @Test fun suggestedSongsTheServerDoesntHaveCarryItsCoverWhenThereIsOne() {
        val picks = TracksideJson.decodeFromString(TracksideSuggestedSongs.serializer(), fixture("picks.json"))
        val external = picks.external.orEmpty()
        assertEquals("etz1d2ggyk95rs0cjhnde", external.first { it.title == "Irreplaceable" }.artworkId)
        assertEquals(null, external.first { it.title == "Run the World (Girls)" }.artworkId)
        val search = TracksideJson.decodeFromString(TracksideAcquisitionSearch.serializer(), fixture("acquisition-search.json"))
        assertTrue("candidates decode with or without a cover: $search", search.candidates.isNotEmpty())
    }

    @Test fun errorsReadByTheirStableCode() {
        val notFound = TracksideClient.error(TracksideAnswer(404, fixture("error-not-found.json")))
        assertEquals(TracksideError.NotFound, notFound)
        val quota = TracksideClient.error(TracksideAnswer(429, fixture("error-quota.json")))
        assertTrue("quota_exceeded is its own error, with when it resets: $quota", quota is TracksideError.QuotaExceeded)
        val limited = TracksideClient.error(TracksideAnswer(429, """{"error":{"code":"rate_limited"}}""", retryAfter = " 12 "))
        assertEquals(12L, (limited as TracksideError.RateLimited).retryAfterSeconds)
        assertEquals(TracksideError.Unauthenticated, TracksideClient.error(TracksideAnswer(401, """{"error":{"code":"unauthenticated"}}""")))
        // A captive portal's page isn't Trackside, whatever status it comes with.
        assertTrue(TracksideClient.error(TracksideAnswer(511, "<html>Log in</html>")) is TracksideError.Http)
        try {
            TracksideClient.decode(TracksideAnswer(200, "<html>Log in</html>"), TracksidePing.serializer())
            fail("not Trackside")
        } catch (_: TracksideError.NotTrackside) {
        }
    }

    @Test fun aPairingCodeFromTheDashboard() {
        val link = TracksideJson.parseToJsonElement(fixture("pairing-code.json")).let { (it as kotlinx.serialization.json.JsonObject)["qr_payload"].toString().trim('"') }
        val payload = PairingPayload.parse(link, now = Instant.parse("2026-11-14T09:35:00Z"))
        assertEquals(SERVER, payload.serverId)
        assertEquals("Home", payload.name)
        assertEquals(3, payload.urls.size)
        assertEquals("Q7RM4X2K9TBV6WNZ3HCJ8DPF5Y", payload.code)
        assertEquals(TracksideProof.Key.Offer("ge1gnmkv9dsa20vtnqdjq", "Q7RM4X2K9TBV6WNZ3HCJ8DPF5Y"), payload.proofKey)

        assertThrows<PairingPayload.Problem.Expired> { PairingPayload.parse(link, now = Instant.parse("2026-11-14T09:41:00Z")) }
        assertThrows<PairingPayload.Problem.NotAPairingLink> { PairingPayload.parse("https://music.example.com/pair?d=x") }
        assertThrows<PairingPayload.Problem.Unreadable> { PairingPayload.parse("trackside://pair?d=!!!") }
        assertEquals("Q7RM-4X2K", PairingPayload.typedCode("q7rm 4x2k"))
        assertNull(PairingPayload.typedCode("Q7RM4X2"))
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            fail("expected ${T::class.simpleName}, got $error")
        }
        fail("expected ${T::class.simpleName}")
    }
}

class TracksideSyncTest {
    private fun page(name: String) = TracksideJson.decodeFromString(TracksideSyncPage.serializer(), fixture(name))

    @Test fun aSnapshotThenItsChanges() = runTest {
        val asked = mutableListOf<String?>()
        val snapshot = listOf(page("sync-snapshot-songs.json"), page("sync-snapshot-complete.json"))
        val first = TracksideSync.run(TracksideMirror(), { cursor, _ -> asked += cursor; snapshot[asked.size - 1] })
        assertEquals(listOf(null, snapshot[0].nextCursor), asked)
        assertEquals(snapshot[1].nextCursor, first.cursor)
        val ids = snapshot[0].songs!!.filter { it.missing != true }.map { it.id }.toSet()
        assertEquals(ids, first.songs.keys)

        val delta = page("sync-delta.json")
        val deleted = delta.deletes!!.songs!!.single()
        val seeded = first.copy(songs = first.songs + (deleted to first.songs.values.first().copy(id = deleted)))
        val second = TracksideSync.run(seeded, { cursor, _ -> assertEquals(first.cursor, cursor); delta })
        assertFalse(deleted in second.songs)
        assertEquals(delta.nextCursor, second.cursor)
    }

    @Test fun aResetStartsOverWithoutLosingWhatItHadOnFailure() = runTest {
        val kept = TracksideMirror(mapOf("a" to com.luke.tracks.library.SubsonicSong("a", "A")), cursor = "old")
        val snapshot = page("sync-snapshot-complete.json")
        var calls = 0
        val fresh = TracksideSync.run(kept, { cursor, _ ->
            calls++
            if (cursor == "old") throw TracksideError.SyncReset else snapshot
        })
        assertEquals(2, calls)
        assertEquals(snapshot.nextCursor, fresh.cursor)
        assertTrue("a snapshot replaces what was there", fresh.songs.isEmpty())

        try {
            TracksideSync.run(kept, { _, _ -> throw java.io.IOException("offline") })
            fail("offline")
        } catch (_: java.io.IOException) {
        }
        assertEquals("the mirror passed in is never changed", "old", kept.cursor)
    }
}

class TracksideClientTest {
    private val key = "tsk_9pQvL2mN6tY0wB4kD9hJ1sF5gA7cE3uI8oP2zR6x"
    private val home = Endpoint("http://192.168.1.20:4747".toHttpUrl(), Endpoint.Kind.Lan)

    @Test fun theKeyGoesOnlyInTheHeaderAndOnlyAfterProof() = runTest {
        val sent = mutableListOf<Request>()
        val transport = TracksideTransport { request, _ ->
            sent += request
            when (request.url.pathSegments.last()) {
                "me" -> TracksideAnswer(200, fixture("me.json"))
                "media-token" -> TracksideAnswer(200, fixture("media-token.json"))
                else -> TracksideAnswer(404, fixture("error-not-found.json"))
            }
        }
        var proven = false
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> proven })
        val client = TracksideClient(endpoints, key, "d1", null, transport)
        try {
            client.me()
            fail("no address proved itself")
        } catch (_: com.luke.tracks.net.Unproven) {
        }
        assertTrue("nothing was sent before a proof", sent.isEmpty())

        proven = true
        client.me()
        val token = client.renewMediaToken()
        sent.forEach { request ->
            assertEquals("Bearer $key", request.header("Authorization"))
            assertFalse(request.url.toString().contains(key))
        }
        assertEquals("POST", sent.last().method)

        // Media addresses carry the short-lived token, never the key, and only to the server in use.
        val art = client.artworkUrl("ca81", 600)
        assertEquals(token.token, art.queryParameter("mt"))
        assertFalse(art.toString().contains(key))
        assertEquals(token.token, client.mediaUrl("/api/v1/songs/s1/stream")!!.queryParameter("mt"))
        assertNull("another site never sees it", client.mediaUrl("https://cdn.example.net/cover.jpg")!!.queryParameter("mt"))
    }

    @Test fun aDecisionAsksForWhatExoPlayerPlaysWithoutHls() = runTest {
        var body = ""
        val transport = TracksideTransport { request, _ ->
            body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            TracksideAnswer(200, fixture("decision-direct.json"))
        }
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        val decision = TracksideClient(endpoints, key, "d1", null, transport).decide(TracksidePlaybackRequest.exoPlayer("s1", 128, "cellular"))
        assertEquals("direct", decision.mode)
        val sent = TracksideJson.parseToJsonElement(body).toString()
        assertTrue(sent, sent.contains("\"song_id\":\"s1\""))
        assertTrue(sent, sent.contains("\"hls\":false"))
        assertTrue(sent, sent.contains("\"preferred_profile\":\"aac-128\""))
        assertTrue(sent, sent.contains("\"network\":\"cellular\""))
        assertTrue(sent, sent.contains("\"allow_original_fallback\":true"))
    }

    @Test fun passwordsAndCodesNeverGoOverPlainHttpBeyondHome() = runTest {
        val transport = TracksideTransport { _, _ -> fail("nothing may be sent"); TracksideAnswer(500, "") }
        val device = TracksideDeviceInfo("c1", "Pixel")
        try {
            TracksideClient.login("http://music.example.com:4747".toHttpUrl(), "luke", "pw", device, transport)
            fail("refused")
        } catch (_: TracksideError.InsecureAddress) {
        }
        try {
            TracksideClient.claim("http://203.0.113.5".toHttpUrl(), "Q7RM-4X2K", device, transport)
            fail("refused")
        } catch (_: TracksideError.InsecureAddress) {
        }
    }

    @Test fun anAddressProvesItselfOnlyWithTheRightServerAndKey() = runTest {
        val apiKey = "tsk_example"
        val keyFor = TracksideProof.Key.Device("d1", apiKey)
        fun server(id: String, signingKey: String) = TracksideTransport { request, _ ->
            val nonce = request.url.queryParameter("challenge")!!
            assertEquals("192.168.1.20:4747", request.url.queryParameter("host"))
            assertEquals("d1", request.url.queryParameter("device"))
            assertNull("the key itself is never sent to a probe", request.header("Authorization"))
            val proof = TracksideProof.expected(TracksideProof.Key.Device("d1", signingKey), id, home.url, nonce)
            TracksideAnswer(200, """{"server_id":"$id","proof":"$proof"}""")
        }
        assertTrue(TracksideClient.proves(home, SERVER, keyFor, 2_000, server(SERVER, apiKey)))
        assertFalse("another server at the same home address", TracksideClient.proves(home, SERVER, keyFor, 2_000, server("other", apiKey)))
        assertFalse("something that doesn't know the key", TracksideClient.proves(home, SERVER, keyFor, 2_000, server(SERVER, "guess")))
        assertFalse("a captive portal", TracksideClient.proves(home, SERVER, keyFor, 2_000, { _, _ -> TracksideAnswer(200, "<html>Wi-Fi</html>") }))
    }

    @Test fun serverAddressesAreAskedOnlyForChangesAndOnlyOfAProvenAddress() = runTest {
        val sent = mutableListOf<Request>()
        var unchanged = false
        val transport = TracksideTransport { request, _ ->
            sent += request
            if (unchanged) TracksideAnswer(304, "") else TracksideAnswer(200, fixture("server-urls.json"), etag = "\"v7\"")
        }
        var proven = false
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> proven })
        val client = TracksideClient(endpoints, key, "d1", null, transport)
        try {
            client.serverUrls(null)
            fail("an unproven address isn't asked")
        } catch (_: com.luke.tracks.net.Unproven) {
        }
        assertTrue(sent.isEmpty())

        proven = true
        val urls = client.serverUrls(null)!!
        assertEquals(SERVER, urls.serverId)
        assertEquals(listOf("lan", "lan", "public"), urls.urls.map { it.kind })
        assertEquals(listOf(false, false, true), urls.urls.map { it.tls })
        assertEquals("\"v7\"", urls.version)
        assertEquals("/api/v1/server/urls", sent.last().url.encodedPath)
        assertNull(sent.last().header("If-None-Match"))

        unchanged = true
        assertNull("unchanged since v7", client.serverUrls(urls.version))
        assertEquals("\"v7\"", sent.last().header("If-None-Match"))
        assertEquals("Bearer $key", sent.last().header("Authorization"))

        val event = TracksideJson.decodeFromString(TracksideServerUrlsEvent.serializer(), fixture("server-urls-event.json"))
        assertTrue("the event's version differs, so the list is asked for again", event.version != urls.version)
    }

    @Test fun aServerRefusingSecretsOverPlainHttpSaysWhatToDo() {
        val error = TracksideClient.error(TracksideAnswer(403, """{"error":{"code":"https_required","message":"Use https."}}"""))
        assertEquals(TracksideError.HttpsRequired, error)
        assertTrue(error.message!!.contains("https"))
    }

    @Test fun httpsRequiredSwitchesToTheHttpsAddressInsteadOfAskingAgain() = runTest {
        // Away from home on a network the server doesn't count as local: plain http to the
        // Tailscale-range address is refused, and the same request goes to the https address.
        val public = Endpoint("https://music.example.com".toHttpUrl(), Endpoint.Kind.Public)
        val sent = mutableListOf<Request>()
        val transport = TracksideTransport { request, _ ->
            sent += request
            if (request.url.isHttps) TracksideAnswer(200, fixture("me.json"))
            else TracksideAnswer(403, fixture("error-https-required.json"))
        }
        val endpoints = ServerEndpoints(listOf(home, public), home.url, backgroundScope, { _, _ -> true })
        val me = TracksideClient(endpoints, key, "d1", null, transport).me()
        assertEquals("luke", me.user.username)
        assertEquals(listOf("http", "https"), sent.map { it.url.scheme })
        // The refusal's own https addresses join the race; whichever proves itself is used.
        assertTrue(endpoints.baseUrl.isHttps && endpoints.baseUrl.host in setOf("music.example.com", "home.tail4a2c.ts.net"))
        assertTrue(endpoints.httpsOnly)

        // An address the refusal names is used even when Tracks didn't know it yet.
        val unknown = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        sent.clear()
        assertEquals("luke", TracksideClient(unknown, key, "d1", null, transport).me().user.username)
        assertTrue(sent.last().url.host in setOf("music.example.com", "home.tail4a2c.ts.net"))
        assertTrue(sent.last().url.isHttps)
        assertEquals(TracksideClient.httpsUrls(TracksideAnswer(403, fixture("error-https-required.json"))).size, 2)

        // With no https address at all, it says so rather than asking for a password.
        val bare = TracksideTransport { request, _ -> if (request.url.isHttps) TracksideAnswer(200, fixture("me.json")) else TracksideAnswer(403, """{"error":{"code":"https_required","message":""}}""") }
        val lonely = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        try {
            TracksideClient(lonely, key, "d1", null, bare).me()
            fail("no https address")
        } catch (_: TracksideError.HttpsRequired) {
        }
        assertEquals(TracksideError.HttpsRequired, TracksideClient.error(TracksideAnswer(403, fixture("error-https-required.json"))))
    }

    @Test fun meSaysWhetherThisPhoneCanRequestSongs() {
        val with = TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me.json"))
        assertTrue(with.offersAcquisition)
        assertTrue(with.canRequest)
        assertEquals(true, with.features?.acquisition?.externalPlay)
        val without = TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me-without-acquisition.json"))
        assertFalse(without.offersAcquisition)
        val barred = TracksideJson.decodeFromString(
            TracksideMe.serializer(),
            fixture("me-without-acquisition.json").replace("\"can_request\": true", "\"can_request\": false"),
        )
        assertFalse(barred.canRequest)
    }

    @Test fun aRequestSendsTitleAndArtistWithAnIdempotencyKeyAndRetriesWithTheSameKey() = runTest {
        val sent = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        var lose = true
        val transport = TracksideTransport { request, _ ->
            sent += request
            bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
            // The first is lost on the way: the phone can't know whether the server took it.
            if (lose) {
                lose = false
                throw java.io.IOException("connection reset")
            }
            TracksideAnswer(202, fixture("acquisition-downloading.json"))
        }
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        val client = TracksideClient(endpoints, key, "d1", null, transport)
        val asked = client.requestAcquisition(TracksideAcquisitionRequest("Halo", "Beyoncé", "I Am... Sasha Fierce", 261_640))

        assertEquals("downloading", asked.state)
        assertFalse(asked.isFinished)
        assertEquals(2, sent.size)
        sent.forEach {
            assertEquals("POST", it.method)
            assertEquals("/api/v1/acquisitions", it.url.encodedPath)
        }
        val key0 = sent[0].header("Idempotency-Key")
        assertTrue("a key: $key0", !key0.isNullOrBlank())
        assertEquals("the retry is the same request", key0, sent[1].header("Idempotency-Key"))
        assertEquals(bodies[0], bodies[1])
        val body = TracksideJson.parseToJsonElement(bodies[0]).toString()
        assertTrue(body, body.contains("\"title\":\"Halo\"") && body.contains("\"artist\":\"Beyoncé\""))
        assertTrue(body, body.contains("\"duration_ms\":261640") && body.contains("\"allow_versions\":false") && body.contains("\"instant\":false"))
        assertFalse("no candidate without one: $body", body.contains("candidate"))

        // A new request is a new key.
        lose = false
        client.requestAcquisition(TracksideAcquisitionRequest("Halo", "Beyoncé"))
        assertTrue(sent.last().header("Idempotency-Key") != key0)
    }

    @Test fun requestAnswersAndRefusals() = runTest {
        var answer = TracksideAnswer(200, fixture("acquisition-done.json"))
        val transport = TracksideTransport { _, _ -> answer }
        val endpoints = ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true })
        val client = TracksideClient(endpoints, key, "d1", null, transport)
        val request = TracksideAcquisitionRequest("Halo", "Beyoncé")

        // 200: the library has it already.
        val done = client.requestAcquisition(request)
        assertTrue(done.isDone && done.existing == true)
        assertEquals("sdjcz08vf67h7cx28snq6", done.songId)

        // 429: the quota, and when it resets.
        answer = TracksideAnswer(429, fixture("error-quota.json"))
        try {
            client.requestAcquisition(request)
            fail("quota")
        } catch (error: TracksideError.QuotaExceeded) {
            assertEquals(Instant.parse("2026-11-15T09:30:00Z"), error.resetsAt)
        }

        // 410: a candidate that's gone.
        answer = TracksideAnswer(410, """{"error":{"code":"candidate_expired","message":"Search again."}}""")
        try {
            client.requestAcquisition(request.copy(candidate = "x3n8"))
            fail("expired")
        } catch (_: TracksideError.CandidateExpired) {
        }

        // A server that doesn't get songs answers 404 to all of section 15.
        answer = TracksideAnswer(404, fixture("error-not-found.json"))
        try {
            client.requestAcquisition(request)
            fail("not offered")
        } catch (_: TracksideError.NotFound) {
        }

        // Status (15.4).
        answer = TracksideAnswer(200, fixture("acquisition-downloading.json"))
        assertEquals(12_000_000L, client.acquisition("qc5hgnjk920q78t8wpcg1").progress?.bytes)
        answer = TracksideAnswer(200, fixture("acquisitions.json"))
        assertTrue(client.acquisitions().acquisitions.isNotEmpty())
        answer = TracksideAnswer(200, fixture("search.json"))
        assertEquals("Halo", client.searchExternal("halo")!!.candidates.first().title)
        answer = TracksideAnswer(200, fixture("search-library-only.json"))
        assertEquals("left out where this phone can't request songs", null, client.searchExternal("halo"))
    }

}

package com.luke.tracks.library.trackside

import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.ServerEndpoints
import kotlinx.coroutines.test.currentTime
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
    requireNotNull(TracksideLinkTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

private const val RATE_LIMITED = """{"error":{"code":"rate_limited","message":"Slow down."}}"""

private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

class TracksideLinkTest {
    private val home = "http://192.168.1.20:4747".toHttpUrl()
    private val tv = TracksideDeviceInfo("5b1f", "Living Room TV", platform = TracksideDeviceInfo.ANDROID_TV, model = "Chromecast with Google TV")

    @Test fun readsEveryLinkFixture() {
        val start = TracksideJson.decodeFromString(TracksideLinkStart.serializer(), fixture("link-start.json"))
        assertEquals("Hx4tQ9vLm2Rk7pW3zN8cB5yF1dJ6gS0aTe4uVq2iKo8", start.deviceCode)
        assertEquals("WDJB-MJHT", start.userCode)
        assertEquals("https://music.example.com/link?code=WDJB-MJHT", start.verificationUrl)
        assertEquals("tracks://link?server=5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b&code=WDJB-MJHT", start.linkUrl)
        assertEquals(5L, start.intervalS)
        assertEquals("2026-11-14T09:40:00.000Z", start.expiresAt)
        assertFalse("the device code never reaches a log", start.toString().contains(start.deviceCode))

        mapOf("link-lookup.json" to "pending", "link-approved.json" to "approved", "link-denied.json" to "denied").forEach { (name, state) ->
            val info = TracksideJson.decodeFromString(TracksideLinkInfo.serializer(), fixture(name))
            assertEquals(name, state, info.state)
            assertEquals("Living Room TV", info.device.name)
            assertEquals("android_tv", info.device.platform)
            assertEquals("Chromecast with Google TV", info.device.model)
            assertEquals("Tracks", info.device.appName)
            assertTrue(info.nearby)
            assertEquals("2026-11-14T09:29:30.000Z", info.requestedAt)
            assertEquals("2026-11-14T09:39:30.000Z", info.expiresAt)
        }

        val result = TracksideJson.decodeFromString(TracksidePairResult.serializer(), fixture("link-poll-result.json"))
        assertEquals("5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b", result.server.serverId)
        assertTrue(result.apiKey.startsWith("tsk_"))

        val linked = TracksideJson.decodeFromString(TracksideDeviceLinkedEvent.serializer(), fixture("device-linked-event.json"))
        assertEquals(TracksideDeviceLinkedEvent("dchjkb9k3cnapjw0kmhq6", "Living Room TV", "android_tv"), linked)

        val capabilities = TracksideJson.decodeFromString(TracksideCapabilities.serializer(), fixture("capabilities.json"))
        assertEquals(true, capabilities.auth?.deviceLink)
    }

    @Test fun aLookupWithoutOptionalFieldsStillReads() {
        val info = TracksideJson.decodeFromString(
            TracksideLinkInfo.serializer(),
            """{"state":"pending","device":{"name":"TV"},"requested_at":"x","expires_at":"y","later":1}""",
        )
        assertEquals("other", info.device.platform)
        assertNull(info.device.model)
        assertFalse("not said is not nearby", info.nearby)
    }

    @Test fun approverErrorsMapToTheirOwnCases() {
        fun mapped(name: String, status: Int, retryAfter: String? = null) = TracksideClient.error(TracksideAnswer(status, fixture(name), retryAfter))
        assertEquals(TracksideError.LinkExpired, mapped("error-link-expired.json", 410))
        assertEquals(TracksideError.LinkLocked, mapped("error-link-locked.json", 410))
        assertEquals(TracksideError.LinkUsed, mapped("error-link-used.json", 409))
        assertEquals(TracksideError.NotFound, mapped("error-not-found.json", 404))
        val limited = TracksideClient.error(TracksideAnswer(429, RATE_LIMITED, "42"))
        assertTrue(limited is TracksideError.RateLimited)
        assertEquals(42L, (limited as TracksideError.RateLimited).retryAfterSeconds)
        val other = TracksideClient.error(TracksideAnswer(409, """{"error":{"code":"conflict","details":{"reason":"speaker_offline"}}}"""))
        assertTrue("another conflict stays a conflict", other is TracksideError.Server)
    }

    @Test fun startSendsTheTvAndNoKey() = runTest {
        val sent = mutableListOf<Request>()
        val transport = TracksideTransport { request, _ ->
            sent += request
            TracksideAnswer(201, fixture("link-start.json"))
        }
        val start = TracksideClient.startLink(home, tv, transport)
        assertEquals("WDJB-MJHT", start.userCode)
        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/link/start", request.url.encodedPath)
        assertNull(request.header("Authorization"))
        val body = TracksideJson.parseToJsonElement(request.bodyText()).toString()
        assertTrue(body, body.contains("\"platform\":\"android_tv\""))
        assertTrue(body, body.contains("\"client_device_id\":\"5b1f\""))
    }

    @Test fun linkingNeverStartsOrPollsOverPlainHttpBeyondHome() = runTest {
        val transport = TracksideTransport { _, _ -> fail("nothing may be sent"); TracksideAnswer(500, "") }
        val away = "http://music.example.com:4747".toHttpUrl()
        try {
            TracksideClient.startLink(away, tv, transport)
            fail("refused")
        } catch (_: TracksideError.InsecureAddress) {
        }
        try {
            TracksideClient.pollLink(away, "secret", transport)
            fail("refused")
        } catch (_: TracksideError.InsecureAddress) {
        }
    }

    @Test fun pollMapsEveryAnswer() = runTest {
        suspend fun poll(status: Int, name: String, retryAfter: String? = null): TracksideLinkPoll =
            TracksideClient.pollLink(home, "Hx4t", { request, _ ->
                assertEquals("/api/v1/link/poll", request.url.encodedPath)
                assertEquals("""{"device_code":"Hx4t"}""", request.bodyText())
                TracksideAnswer(status, if (name == "rate-limited") RATE_LIMITED else fixture(name), retryAfter)
            })
        val linked = poll(200, "link-poll-result.json")
        assertTrue(linked is TracksideLinkPoll.Linked)
        assertEquals("dchjkb9k3cnapjw0kmhq6", (linked as TracksideLinkPoll.Linked).result.device.id)
        assertEquals(TracksideLinkPoll.Pending, poll(428, "error-authorization-pending.json"))
        assertEquals(TracksideLinkPoll.SlowDown(10), poll(429, "error-slow-down.json", "10"))
        assertEquals(TracksideLinkPoll.Denied, poll(403, "error-access-denied.json"))
        assertEquals(TracksideLinkPoll.Expired, poll(410, "error-expired-token.json"))
        try {
            poll(429, "rate-limited", "30")
            fail("too many polls is an error")
        } catch (error: TracksideError.RateLimited) {
            assertEquals(30L, error.retryAfterSeconds)
        }
        try {
            poll(403, "error-https-required.json")
            fail("https_required is an error")
        } catch (_: TracksideError.HttpsRequired) {
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun awaitHonoursTheIntervalAndSlowDown() = runTest {
        val answers = ArrayDeque(
            listOf(
                TracksideAnswer(428, fixture("error-authorization-pending.json")),
                TracksideAnswer(429, fixture("error-slow-down.json"), "10"),
                TracksideAnswer(428, fixture("error-authorization-pending.json")),
                TracksideAnswer(200, fixture("link-poll-result.json")),
            ),
        )
        val polledAt = mutableListOf<Long>()
        val transport = TracksideTransport { _, _ ->
            polledAt += currentTime
            answers.removeFirst()
        }
        val start = TracksideJson.decodeFromString(TracksideLinkStart.serializer(), fixture("link-start.json")).copy(expiresAt = "2999-01-01T00:00:00.000Z")
        val outcome = TracksideClient.awaitLink(home, start, transport)
        assertTrue(outcome is TracksideLinkPoll.Linked)
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), polledAt)
    }

    @Test fun awaitStopsWhenDeniedAndRidesOutABlip() = runTest {
        var calls = 0
        val transport = TracksideTransport { _, _ ->
            calls += 1
            if (calls == 1) throw java.io.IOException("wifi dropped")
            TracksideAnswer(403, fixture("error-access-denied.json"))
        }
        val start = TracksideJson.decodeFromString(TracksideLinkStart.serializer(), fixture("link-start.json")).copy(expiresAt = "2999-01-01T00:00:00.000Z")
        assertEquals(TracksideLinkPoll.Denied, TracksideClient.awaitLink(home, start, transport))
        assertEquals(2, calls)
    }

    @Test fun awaitGivesUpOnceTheLinkHasExpired() = runTest {
        val transport = TracksideTransport { _, _ -> TracksideAnswer(428, fixture("error-authorization-pending.json")) }
        val start = TracksideJson.decodeFromString(TracksideLinkStart.serializer(), fixture("link-start.json"))
        assertEquals("expired 2026-11-14", TracksideLinkPoll.Expired, TracksideClient.awaitLink(home, start, transport))
    }

    @Test fun approvingSendsTheCodeWithThisPhonesKey() = runTest {
        val sent = mutableListOf<Request>()
        val transport = TracksideTransport { request, _ ->
            sent += request
            when (request.url.pathSegments.last()) {
                "lookup" -> TracksideAnswer(200, fixture("link-lookup.json"))
                "approve" -> TracksideAnswer(200, fixture("link-approved.json"))
                "deny" -> TracksideAnswer(200, fixture("link-denied.json"))
                else -> TracksideAnswer(404, fixture("error-not-found.json"))
            }
        }
        val endpoint = Endpoint(home, Endpoint.Kind.Lan)
        val endpoints = ServerEndpoints(listOf(endpoint), home, backgroundScope, { _, _ -> true })
        val client = TracksideClient(endpoints, "tsk_phone", "d1", null, transport)
        assertEquals("pending", client.lookupLink("wdjb mjht").state)
        assertEquals("approved", client.approveLink("WDJB-MJHT").state)
        assertEquals("denied", client.denyLink("WDJB-MJHT").state)
        assertEquals(listOf("/api/v1/link/lookup", "/api/v1/link/approve", "/api/v1/link/deny"), sent.map { it.url.encodedPath })
        sent.forEach {
            assertEquals("POST", it.method)
            assertEquals("Bearer tsk_phone", it.header("Authorization"))
        }
        assertEquals("""{"user_code":"wdjb mjht"}""", sent.first().bodyText())
    }
}

package com.luke.tracks.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random

class RetryPolicyTest {
    private val policy = RetryPolicy(maxRetries = 2, baseDelayMs = 400, maxDelayMs = 4_000, random = Random(7))

    @Test fun onlyIdempotentRequestsAreTriedAgain() {
        assertNull("a listen or a pairing could happen twice", policy.delayAfter(1, "POST", status = 503))
        assertTrue(policy.delayAfter(1, "GET", status = 503)!! in 200..400)
        assertTrue(policy.delayAfter(1, "PUT", error = SocketTimeoutException())!! in 200..400)
        assertTrue(policy.delayAfter(2, "DELETE", status = 502)!! in 400..800)
        assertNull("gives up after two", policy.delayAfter(3, "GET", status = 503))
    }

    @Test fun theServersRetryAfterIsHeldToOnlyWhenItsShort() {
        assertEquals(2_000L, policy.delayAfter(1, "GET", status = 429, retryAfter = "2"))
        assertNull("a long wait is left to the caller", policy.delayAfter(1, "GET", status = 429, retryAfter = "120"))
    }

    @Test fun answersThatWontChangeArentTriedAgain() {
        listOf(400, 401, 403, 404, 500).forEach { assertNull("$it", policy.delayAfter(1, "GET", status = it)) }
        assertNull(policy.delayAfter(1, "GET", error = ExposedAddress("music.example.com")))
        val certificate = SSLHandshakeException("no").apply { initCause(CertificateNeedsTrust(PinKey("h", 443), "aa", emptySet())) }
        assertNull("a certificate needs you, not another try", policy.delayAfter(1, "GET", error = certificate))
        assertNull("a call out of time is out of time", policy.delayAfter(1, "GET", error = java.io.InterruptedIOException("timeout")))
    }
}

class HttpInterceptorsTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    @Test fun aBusyServerIsAskedAgainAndAPostIsNot() {
        val slept = mutableListOf<Long>()
        val client = OkHttpClient.Builder().addInterceptor(RetryInterceptor(RetryPolicy(random = Random(1)), sleep = { slept += it })).build()
        server.enqueue(MockResponse(code = 503, headers = okhttp3.Headers.headersOf("Retry-After", "1")))
        server.enqueue(MockResponse(body = "songs"))
        val body = client.newCall(Request.Builder().url(server.url("/api/v1/sync")).build()).execute().use { it.body.string() }
        assertEquals("songs", body)
        assertEquals(listOf(1_000L), slept)

        server.enqueue(MockResponse(code = 503))
        val code = client.newCall(Request.Builder().url(server.url("/api/v1/listens")).post("{}".toRequestBody()).build()).execute().use { it.code }
        assertEquals(503, code)
        assertEquals(3, server.requestCount)
    }

    @Test fun plainHttpBeyondHomeIsRefusedBeforeConnecting() {
        val client = OkHttpClient.Builder().addInterceptor(CleartextGuard).addNetworkInterceptor(CleartextGuard).build()
        try {
            client.newCall(Request.Builder().url("http://203.0.113.5:4533/rest/ping?u=luke&t=x&s=y").build()).execute()
            fail("refused")
        } catch (error: ExposedAddress) {
            assertTrue(error.message!!.contains("203.0.113.5"))
        }
    }

    @Test fun aRedirectToPlainHttpOnTheInternetIsRefusedBeforeTheRequestGoes() {
        // Every name is this test server, so the redirect's target really is reachable.
        val local = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName(server.hostName))
        }
        val client = OkHttpClient.Builder().dns(local).addInterceptor(CleartextGuard).addNetworkInterceptor(CleartextGuard).build()
        server.enqueue(MockResponse(code = 302, headers = okhttp3.Headers.headersOf("Location", "http://music.example.com:${server.port}/rest/stream?t=token")))
        try {
            client.newCall(Request.Builder().url(server.url("/rest/stream")).build()).execute()
            fail("refused")
        } catch (_: ExposedAddress) {
        }
        assertEquals("only the first, nearby request reached a server", 1, server.requestCount)
    }

    @Test fun aLinkIsSignedAsItGoesOutAndKeepsItsRange() {
        val fresh = mutableListOf<Boolean>()
        val resolver = MediaResolver { link, refresh ->
            fresh += refresh
            ResolvedMedia(server.url("/api/v1/songs/${link.id}/stream").newBuilder().addQueryParameter("mt", if (refresh) "new" else "old").build())
        }
        val client = OkHttpClient.Builder().addInterceptor(ServerMediaInterceptor { if (it == "srv") resolver else null }).build()
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse(code = 206, body = "audio"))
        val link = ServerLinks.url(ServerLinks.Link("srv", ServerLinks.Kind.Stream, "s1"))
        val answer = client.newCall(Request.Builder().url(link).header("Range", "bytes=1000-").build()).execute()
        assertEquals(206, answer.code)
        answer.close()

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("old", first.url.queryParameter("mt"))
        assertEquals("a refused token is renewed and the request sent again", "new", second.url.queryParameter("mt"))
        assertEquals("bytes=1000-", second.headers["Range"])
        assertEquals(listOf(false, true), fresh)
    }

    @Test fun aLinkForAServerThatsGoneFailsWithoutGoingAnywhere() {
        val client = OkHttpClient.Builder().addInterceptor(ServerMediaInterceptor { null }).build()
        try {
            client.newCall(Request.Builder().url(ServerLinks.url(ServerLinks.Link("gone", ServerLinks.Kind.Cover, "c", 600))).build()).execute()
            fail("no server")
        } catch (_: IOException) {
        }
    }

    @Test fun linksRoundTripAndRejectAnythingElse() {
        val link = ServerLinks.Link("6f1c-uuid", ServerLinks.Kind.Cover, "al/7 + more", 300)
        val url: HttpUrl = ServerLinks.url(link)
        assertEquals(ServerLinks.HOST, url.host)
        assertEquals(link, ServerLinks.parse(url))
        assertNull(ServerLinks.parse("https://music.example.com/srv/cover/c".toHttpUrl()))
        assertNull(ServerLinks.parse("https://${ServerLinks.HOST}/srv/delete/c".toHttpUrl()))
        assertTrue(Nearby.isExposed("http://${ServerLinks.HOST}/x".toHttpUrl()))
    }
}

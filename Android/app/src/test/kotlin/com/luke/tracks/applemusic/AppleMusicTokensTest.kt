package com.luke.tracks.applemusic

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/** An unsigned-looking JWT with these claims; only Apple checks the signature. */
fun jwt(iat: Long, exp: Long, alg: String = "ES256"): String {
    fun part(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    return part("""{"alg":"$alg","kid":"ABC123DEFG"}""") + "." + part("""{"iss":"DEF123GHIJ","iat":$iat,"exp":$exp}""") + ".c2lnbmF0dXJl"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppleMusicTokensTest {
    private val now = 1_795_000_000L
    private val day = 86_400L

    @Test fun aTokenWithinApplesLimitsIsTakenWithItsExpiry() {
        val token = DeveloperToken.parse(jwt(now, now + 30 * day))
        assertEquals((now + 30 * day) * 1000, token?.expiresAtMs)
    }

    @Test fun tokensApplesRulesRefuseAreNeverUsed() {
        assertNull("over six months", DeveloperToken.parse(jwt(now, now + 183 * day)))
        assertNull("not ES256", DeveloperToken.parse(jwt(now, now + day, alg = "HS256")))
        assertNull("ends before it starts", DeveloperToken.parse(jwt(now, now - 1)))
        assertNull(DeveloperToken.parse("not.a.jwt"))
        assertNull(DeveloperToken.parse(""))
    }

    @Test fun aBuiltInTokenStopsBeingAvailableWhenItExpiresOrIsRefused() = runBlocking {
        var clock = now * 1000
        val provider = BuildTimeDeveloperToken(jwt(now, now + day), now = { clock })
        assertTrue(provider.available)
        val token = provider.token()!!
        clock = (now + day) * 1000 - 60_000
        assertFalse("within minutes of expiring it's as good as gone", provider.available)
        assertNull(provider.token())

        clock = now * 1000
        provider.rejected(token)
        assertFalse(provider.available)
        assertFalse("a build without one has no Apple Music", BuildTimeDeveloperToken("").available)
    }

    @Test fun aServersTokenComesBeforeTheBuildsOwn() = runBlocking {
        val clock = { now * 1000 }
        val server = BuildTimeDeveloperToken(jwt(now, now + day), now = clock)
        val built = BuildTimeDeveloperToken(jwt(now, now + 2 * day), now = clock)
        val first = FirstDeveloperToken(listOf(server, built))
        assertEquals(server.cached(), first.token())
        first.rejected(server.cached()!!)
        assertEquals(built.cached(), first.token())
    }
}

/** The proposed Trackside endpoint, from the client's side. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TracksideDeveloperTokensTest {
    private val server = MockWebServer()
    private val now = 1_795_000_000L

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun provider(clock: () -> Long = { now * 1000 }) =
        TracksideDeveloperTokens(OkHttpClient(), server = { server.url("/api/v1/") to "tsk_device" }, now = clock)

    @Test fun asksWithTheDeviceKeyAndKeepsTheTokenUntilRefreshAfter() = runBlocking {
        val token = jwt(now, now + 7 * 86_400)
        server.enqueue(MockResponse(body = """{"token":"$token","expires_at":"2026-12-02T00:00:00.000Z","refresh_after":"2026-11-30T00:00:00.000Z"}"""))
        var clock = now * 1000
        val tokens = provider { clock }

        val first = tokens.token()
        assertEquals(token, first?.jwt)
        val request = server.takeRequest()
        assertEquals("/api/v1/me/apple-music/developer-token", request.url.encodedPath)
        assertEquals("Bearer tsk_device", request.headers["Authorization"])

        assertSame("kept, not asked again", first, tokens.token())
        assertEquals(1, server.requestCount)

        clock = java.time.Instant.parse("2026-11-30T00:00:01Z").toEpochMilli()
        server.enqueue(MockResponse(body = """{"token":"$token"}"""))
        tokens.token()
        assertEquals("asked again after refresh_after", 2, server.requestCount)
    }

    @Test fun aServerWithoutTheRouteIsntAskedAgain() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = """{"error":{"code":"not_found","message":"Not found.","request_id":"x"}}"""))
        val tokens = provider()
        assertNull(tokens.token())
        assertFalse(tokens.available)
        assertNull(tokens.token())
        assertEquals(1, server.requestCount)
    }

    @Test fun aTokenAppleRefusedIsFetchedAgain() = runBlocking {
        val token = jwt(now, now + 86_400)
        server.enqueue(MockResponse(body = """{"token":"$token"}"""))
        server.enqueue(MockResponse(body = """{"token":"$token"}"""))
        val tokens = provider()
        tokens.rejected(tokens.token()!!)
        assertNull(tokens.cached())
        assertNotNull(tokens.token())
        assertEquals(2, server.requestCount)
    }
}

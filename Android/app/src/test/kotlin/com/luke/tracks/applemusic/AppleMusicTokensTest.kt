package com.luke.tracks.applemusic

import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideJson
import com.luke.tracks.library.trackside.TracksideMe
import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.ServerEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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

private fun tracksideFixture(name: String): String =
    requireNotNull(AppleMusicTokensTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

/** `GET /api/v1/me/apple-music/developer-token` from the client's side, against the server's fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TracksideDeveloperTokensTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tokenRequests = AtomicInteger()

    /** The fixture's token was signed at 09:30 on 14 November and lasts a day. */
    private val signedAt = Instant.parse("2026-11-14T09:30:00Z").toEpochMilli()
    @Volatile private var clock = signedAt + 60_000
    @Volatile private var me = tracksideFixture("me.json")
    @Volatile private var answer: () -> MockResponse = { json(tracksideFixture("apple-music-developer-token.json")) }

    private fun json(body: String, code: Int = 200, headers: okhttp3.Headers = headersOf()) =
        MockResponse(code = code, headers = headers.newBuilder().add("Content-Type", "application/json").build(), body = body)

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v1/me" -> json(me)
                "/api/v1/me/apple-music/developer-token" -> {
                    tokenRequests.incrementAndGet()
                    answer()
                }
                else -> MockResponse(code = 404)
            }
        }
        server.start()
    }

    @After fun stop() {
        scope.cancel()
        server.close()
    }

    private fun tokens(
        servers: kotlinx.coroutines.flow.Flow<List<String>> = MutableStateFlow(listOf("home")),
        ready: suspend () -> Unit = {},
    ): TracksideDeveloperTokens {
        val base = server.url("/")
        val client = TracksideClient(ServerEndpoints(listOf(Endpoint(base, Endpoint.Kind.Lan)), base, scope, { _, _ -> true }), "tsk_device", "d1", null)
        return TracksideDeveloperTokens(scope, servers, { id -> client.takeIf { id == "home" } }, now = { clock }, ready = ready)
    }

    @Test fun aTokenAskedForAsTheAppStartsWaitsForTheServersToBeReadAndAsked() = runBlocking {
        // Before the fix, the account check at start found no server offering a token yet
        // and Apple Music went to "needs sign-in" with an expired-access message.
        val servers = MutableStateFlow<List<String>>(emptyList())
        val loaded = MutableStateFlow(false)
        val trackside = tokens(servers, ready = { loaded.first { it } })
        val first = FirstDeveloperToken(listOf(trackside, BuildTimeDeveloperToken("")))
        assertFalse(first.available)
        scope.launch {
            delay(200)
            servers.value = listOf("home")
            loaded.value = true
        }
        val token = withTimeout(5_000) { first.token() }
        assertNotNull(token)
        assertEquals(1, tokenRequests.get())
    }

    private suspend fun TracksideDeveloperTokens.offered() = withTimeout(5_000) { availabilityChanges.first { available } }

    @Test fun meSaysWhetherTheServerHandsOutTokens() {
        assertTrue(TracksideJson.decodeFromString(TracksideMe.serializer(), tracksideFixture("me.json")).offersAppleMusic)
        val without = tracksideFixture("me.json").replace("\"apple_music\"", "\"later\"")
        assertFalse(TracksideJson.decodeFromString(TracksideMe.serializer(), without).offersAppleMusic)
    }

    @Test fun aServerWithoutTheFeatureIsNeverAskedForATokenAndTheBuildsOwnIsUsed() = runBlocking {
        me = tracksideFixture("me.json").replace("\"developer_token\": true", "\"developer_token\": false")
        val trackside = tokens()
        val built = BuildTimeDeveloperToken(jwt(signedAt / 1000, signedAt / 1000 + 86_400), now = { clock })
        val first = FirstDeveloperToken(listOf(trackside, built))
        withTimeout(5_000) { while (server.requestCount == 0) delay(10) }
        assertNull(trackside.token())
        assertFalse(trackside.available)
        assertEquals(built.cached(), first.token())
        assertEquals(0, tokenRequests.get())
    }

    @Test fun keepsTheTokenUntilRefreshAfter() = runBlocking {
        val tokens = tokens()
        tokens.offered()
        val first = tokens.token()
        assertTrue(first!!.jwt.startsWith("eyJhbGciOiJFUzI1NiIs"))
        assertEquals(Instant.parse("2026-11-15T09:30:00Z").toEpochMilli(), first.expiresAtMs)
        val request = generateSequence { server.takeRequest() }.first { it.url.encodedPath.endsWith("developer-token") }
        assertEquals("Bearer tsk_device", request.headers["Authorization"])

        clock = Instant.parse("2026-11-15T08:29:00Z").toEpochMilli()
        assertSame("kept, not asked again", first, tokens.token())
        assertEquals(1, tokenRequests.get())

        clock = Instant.parse("2026-11-15T08:30:01Z").toEpochMilli()
        tokens.token()
        assertEquals("asked again after refresh_after", 2, tokenRequests.get())
    }

    @Test fun aServerThatAnswers404IsntAskedAgainUntilTheNextStart() = runBlocking {
        answer = { json(tracksideFixture("error-not-found.json"), code = 404) }
        val tokens = tokens()
        tokens.offered()
        assertNull(tokens.token())
        assertFalse(tokens.available)
        tokens.refresh()
        assertNull(tokens.token())
        assertEquals(1, tokenRequests.get())

        answer = { json(tracksideFixture("apple-music-developer-token.json")) }
        val next = tokens()
        next.offered()
        assertNotNull("a new start asks again", next.token())
    }

    @Test fun waitsOutRetryAfterWhenRateLimited() = runBlocking {
        answer = { json("""{"error":{"code":"rate_limited","message":"Slow down.","request_id":"r1"}}""", code = 429, headers = headersOf("Retry-After", "30")) }
        val tokens = tokens()
        tokens.offered()
        assertNull(tokens.token())
        clock += 20_000
        assertNull(tokens.token())
        assertEquals("not asked again within Retry-After", 1, tokenRequests.get())

        answer = { json(tracksideFixture("apple-music-developer-token.json")) }
        clock += 11_000
        assertNotNull(tokens.token())
        assertEquals(2, tokenRequests.get())
    }

    @Test fun appleRefusingTheTokenFetchesAnotherAtOnce() = runBlocking {
        val iat = signedAt / 1000
        val issued = AtomicInteger()
        answer = {
            val n = issued.incrementAndGet()
            json("""{"token":"${jwt(iat, iat + 86_400 - n)}","expires_at":"2026-11-15T09:00:00.000Z","refresh_after":"2026-11-15T08:00:00.000Z"}""")
        }
        val tokens = tokens()
        tokens.offered()
        val apple = MockWebServer().apply { start() }
        try {
            val api = AppleMusicApi(OkHttpClient(), tokens, { "user" }, apple.url("/"))
            apple.enqueue(MockResponse(code = 401, body = fixture("error-401.json")))
            apple.enqueue(MockResponse(body = fixture("storefront.json")))
            assertEquals("gb", api.storefront())
            assertEquals(2, tokenRequests.get())
            val refused = apple.takeRequest().headers["Authorization"]
            assertTrue(refused != apple.takeRequest().headers["Authorization"])

            apple.enqueue(MockResponse(code = 403))
            apple.enqueue(MockResponse(body = fixture("storefront.json")))
            assertEquals("gb", api.storefront())
            assertEquals(3, tokenRequests.get())
        } finally {
            apple.close()
        }
    }
}

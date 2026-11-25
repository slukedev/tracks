package com.luke.tracks.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.library.trackside.OkHttpTracksideTransport
import com.luke.tracks.library.trackside.PairingPayload
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideProof
import com.luke.tracks.library.trackside.TracksideServerUrl
import com.luke.tracks.library.trackside.TracksideTransport
import com.luke.tracks.net.Http
import com.luke.tracks.net.PinStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `trackside://pair` links come from anywhere: a web page, a QR code on a poster, a chat. These
 * play the attacker's side with a real self-signed TLS server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairingSecurityTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as TracksApplication
    private val settings get() = app.graph.settings
    private val attacker = MockWebServer()
    private val held = HeldCertificate.Builder().commonName("Home").addSubjectAlternativeName("evil.example").build()
    private val fingerprint = PinStore.fingerprint(held.certificate)
    private val clients = CopyOnWriteArrayList<OkHttpClient>()
    private var claimedServerId = EVIL
    private lateinit var base: okhttp3.HttpUrl

    private val plain = object : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Before fun setUp() {
        runBlocking { settings.update { it.copy(subsonicServers = "[]") } }
        attacker.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        attacker.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v1/ping" -> {
                    // The attacker made the offer, so it can prove it.
                    val proof = TracksideProof.expected(TracksideProof.Key.Offer(OFFER, CODE), EVIL, base, request.url.queryParameter("challenge")!!)
                    json("""{"server_id":"$EVIL","proof":"$proof"}""")
                }
                "/api/v1/capabilities" -> json("""{"product":"trackside","server_id":"$EVIL"}""")
                "/api/v1/pair/claim" -> json(
                    """{"api_key":"tsk_evil","device":{"id":"d1"},"user":{"id":"u1","username":"luke"},
                    "server":{"name":"Home","server_id":"$claimedServerId","urls":[
                    {"url":"https://api.music.apple.com","kind":"public","tls":true,"fingerprint":"$fingerprint"},
                    {"url":"https://ws.audioscrobbler.com","kind":"public","tls":true,"fingerprint":"$fingerprint"},
                    {"url":"$base","kind":"public","tls":true,"fingerprint":"$fingerprint"}]}}""",
                )
                else -> MockResponse.Builder().code(404).build()
            }
        }
        attacker.start(InetAddress.getByName("127.0.0.1"), 0)
        base = attacker.url("/").toString().trimEnd('/').toHttpUrl()
    }

    @After fun tearDown() = attacker.close()

    /** The real clients, but only to the attacker's server: nothing here goes to the internet. */
    private fun library(identity: String? = null) = ServerLibrary(
        app, settings, plain, app.graph.scope, network = null,
        deviceId = { "phone" },
        tracksideTransport = { http ->
            clients += http
            val real = OkHttpTracksideTransport(http)
            TracksideTransport { request, timeout ->
                if (request.url.host != base.host) throw IOException("offline in tests")
                real.send(request, timeout)
            }
        },
        networkIdentity = { identity },
    )

    private fun link(serverId: String = EVIL, name: String = "Home") = PairingPayload(
        serverId = serverId,
        name = name,
        urls = listOf(
            TracksideServerUrl("https://api.music.apple.com", "public", true, fingerprint),
            TracksideServerUrl("https://ws.audioscrobbler.com", "public", true, fingerprint),
            TracksideServerUrl(base.toString(), "public", true, fingerprint),
        ),
        offerId = OFFER,
        code = CODE,
        expiresAt = Instant.now().plusSeconds(600),
    )

    private fun fetch(client: OkHttpClient) =
        client.newBuilder().retryOnConnectionFailure(false).build().newCall(Request.Builder().url(base.resolve("/api/v1/ping")!!).build()).execute().close()

    private fun refused(client: OkHttpClient, why: String) {
        client.connectionPool.evictAll()
        try {
            fetch(client)
            fail(why)
        } catch (_: IOException) {
        }
    }

    /** A hang fails in a minute rather than holding the build. */
    private fun bounded(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(60_000, block) }

    private suspend fun saved() = ServerLibrary.parse(settings.settings.first().subsonicServers)

    @Test fun aMaliciousLinkPinsNothingBeyondItsOwnServer() = bounded {
        val server = library().pair(link())
        assertEquals(EVIL, server.serverUuid)
        // Apple Music, Last.fm and the web never trust the link's certificate...
        refused(Http.api, "the app's own clients must not hold a link's pins")
        refused(Http.media, "the app's own clients must not hold a link's pins")
        // ...and the client made for the pairing forgets it when the pairing ends.
        clients.forEach { refused(it, "the pairing's pins must be cleared") }
        // What the server announced for Apple's and Last.fm's hosts can't be pinned for them, even in its own client.
        val announced = PinStore().apply {
            replaceAll(emptyMap(), server.addresses.mapNotNull { a -> a.fingerprint?.let { com.luke.tracks.net.PinKey.of(a.url.toHttpUrl().host, a.url.toHttpUrl().port) to setOf(it) } }.toMap())
        }
        assertTrue(announced.offered(com.luke.tracks.net.PinKey.of("api.music.apple.com", 443)).isEmpty())
        assertTrue(announced.offered(com.luke.tracks.net.PinKey.of("ws.audioscrobbler.com", 443)).isEmpty())
        assertFalse(server.pins.keys.any { it.contains("apple") || it.contains("audioscrobbler") })
    }

    @Test fun aLinkCantTakeOverAServerAlreadyPaired() = bounded {
        val home = SubsonicServer(
            id = "home", name = "Home", url = "https://music.example.com", username = "luke", secret = "tsk_real",
            kind = ServerKind.Trackside, serverUuid = EVIL, deviceId = "real-device",
        )
        settings.update { it.copy(subsonicServers = ServerLibrary.encode(listOf(home))) }
        try {
            library().pair(link(name = "Home"))
            fail("a link must never replace a server implicitly")
        } catch (error: AlreadyPaired) {
            assertTrue(error.message!!, error.message!!.contains("music.example.com:443"))
            assertTrue(error.message!!, error.message!!.contains("api.music.apple.com:443"))
        }
        assertEquals("nothing was sent to the link's addresses", 0, attacker.requestCount)
        assertEquals(listOf(home), saved())
    }

    @Test fun aClaimAnsweredByAnotherServerIsRefused() = bounded {
        claimedServerId = "8b1d3c4e-0000-4000-8000-000000000001"
        try {
            library().pair(link())
            fail("the claim must come from the server whose offer was proven")
        } catch (_: TracksideError.Unproven) {
        }
        assertTrue(saved().isEmpty())
    }

    @Test fun pairingAgainFromTheServersOwnPageReplacesIt() = bounded {
        val home = SubsonicServer(
            id = "home", name = "Home", url = "https://music.example.com", username = "luke", secret = "tsk_old",
            kind = ServerKind.Trackside, serverUuid = EVIL, deviceId = "old",
        )
        settings.update { it.copy(subsonicServers = ServerLibrary.encode(listOf(home))) }
        val library = library()
        val before = library.syncBinding("home")
        library.pair(link(), existingId = "home")
        val after = saved().single()
        assertEquals("home", after.id)
        assertEquals("tsk_evil", after.secret)
        assertNotEquals("sync sees the server changed and asks before sending anything", before, library.syncBinding("home"))
    }

    @Test fun aTypedAddressCantTakeOverAServerEither() = bounded {
        val home = SubsonicServer(
            id = "home", name = "Home", url = "https://music.example.com", username = "luke", secret = "tsk_real",
            kind = ServerKind.Trackside, serverUuid = EVIL, deviceId = "real-device",
        )
        settings.update { it.copy(subsonicServers = ServerLibrary.encode(listOf(home))) }
        val library = library()
        library.trustWhileAdding(com.luke.tracks.net.CertificateNeedsTrust(com.luke.tracks.net.PinKey.of(base.host, base.port), fingerprint, emptySet()))
        try {
            library.pair(null, "", base, ServerLibrary.Pairing.Password("luke", "hunter2"))
            fail("refused")
        } catch (_: AlreadyPaired) {
        }
        assertFalse("the password never went", attacker.takeRequestPaths().contains("/api/v1/auth/login"))
    }

    @Test fun aPlainHttpSubsonicServerRemembersTheNetworkItWasAddedOn() = bounded {
        val library = library(identity = "home-network")
        val added = library.save(null, "Navidrome", "http://192.168.1.10:4533".toHttpUrl(), "luke", "pw", others = listOf("https://music.example.com".toHttpUrl()))
        assertEquals(listOf("home-network"), added.homeNetworks)
        // Editing it elsewhere learns nothing: only adding it does.
        val elsewhere = ServerLibrary(app, settings, plain, app.graph.scope, network = null, networkIdentity = { "cafe-network" })
        val edited = elsewhere.save(added.id, "Navidrome", "http://192.168.1.10:4533".toHttpUrl(), "luke", "", others = listOf("https://music.example.com".toHttpUrl()))
        assertEquals(listOf("home-network"), edited.homeNetworks)
        // A server saved before networks were kept stays without one.
        settings.update { it.copy(subsonicServers = ServerLibrary.encode(listOf(added.copy(id = "old", homeNetworks = emptyList())))) }
        assertEquals(emptyList<String>(), elsewhere.save("old", "Navidrome", "http://192.168.1.10:4533".toHttpUrl(), "luke", "").homeNetworks)
        try {
            library.save(null, "Keyed", "http://192.168.1.10:4533".toHttpUrl(), "", "key", SubsonicAuth.ApiKey)
            fail("an API key never goes over plain http")
        } catch (_: SubsonicError.KeyNeedsHttps) {
        }
    }

    private fun MockWebServer.takeRequestPaths(): List<String> = buildList {
        repeat(requestCount) { takeRequest().url.encodedPath.let(::add) }
    }

    private companion object {
        const val EVIL = "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b"
        const val OFFER = "offer1"
        const val CODE = "secret-code"
    }
}

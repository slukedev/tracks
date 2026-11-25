package com.luke.tracks.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress

class PinRulesTest {
    @Test fun anAnnouncedFingerprintNeverStandsInForTheSystemsTrust() {
        // A certificate the system trusts is taken as it is: an announced pin can't narrow it...
        assertEquals(PinDecision.Trusted, PinDecision.of(emptySet(), "bb", offered = setOf("aa")) { true })
        // ...and vouches only for a certificate the system doesn't trust.
        assertEquals(PinDecision.Pinned, PinDecision.of(emptySet(), "aa", offered = setOf("aa")) { false })
        assertEquals(PinDecision.Changed("bb", setOf("aa")), PinDecision.of(emptySet(), "bb", offered = setOf("aa")) { false })
        // One you confirmed still holds against everything else.
        assertEquals(PinDecision.Changed("bb", setOf("cc")), PinDecision.of(setOf("cc"), "bb", offered = setOf("bb")) { true })
    }

    @Test fun servicesTracksReachesByNameAreNeverPinned() {
        val store = PinStore()
        store.replaceAll(
            mapOf(PinKey.of("api.music.apple.com", 443) to setOf("aa"), PinKey.of("nas.local", 443) to setOf("bb")),
            mapOf(PinKey.of("ws.audioscrobbler.com", 443) to setOf("aa"), PinKey.of("media.tracks-server.invalid", 443) to setOf("aa")),
        )
        assertTrue(store.pins(PinKey.of("api.music.apple.com", 443)).isEmpty())
        assertTrue(store.offered(PinKey.of("ws.audioscrobbler.com", 443)).isEmpty())
        assertTrue(store.offered(PinKey.of("media.tracks-server.invalid", 443)).isEmpty())
        assertEquals(setOf("bb"), store.pins(PinKey.of("nas.local", 443)))
        assertTrue("a lookalike isn't a subdomain", !PinStore.neverPinned("notapple.com"))
    }

    @Test fun theNeverPinnedListIsTheNetworkSecurityConfigsDomains() {
        val xml = File("src/main/res/xml/network_security_config.xml").readText()
        val domains = Regex("""<domain[^>]*>([^<]+)</domain>""").findAll(xml).map { it.groupValues[1].trim() }.toSet()
        assertEquals(domains, PinStore.NEVER_PINNED.toSet())
    }
}

/**
 * A server's pins live in its own client. Real TLS against a self-signed server, as a pairing
 * link's attacker would run one.
 */
class PinScopeTlsTest {
    private val server = MockWebServer()
    private val held = HeldCertificate.Builder().commonName("Trackside").addSubjectAlternativeName("nas.local").build()
    private val fingerprint = PinStore.fingerprint(held.certificate)
    private val key get() = PinKey.of(server.hostName, server.port)

    @Before fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun stop() = server.close()

    private fun get(client: OkHttpClient): String =
        client.newBuilder().retryOnConnectionFailure(false).build().newCall(Request.Builder().url(server.url("/api/v1/ping")).build()).execute().use { it.body.string() }

    private fun refused(client: OkHttpClient) {
        try {
            get(client)
            fail("the self-signed certificate must not be accepted")
        } catch (_: IOException) {
        }
    }

    @Test fun aServersPinsReachOnlyItsOwnClient() {
        server.enqueue(MockResponse(body = "ok"))
        val home = Http.pinned()
        home.pins.replaceAll(emptyMap(), mapOf(key to setOf(fingerprint)))
        assertEquals("ok", get(home.api))
        // Apple Music, Last.fm and the web use the app's clients, which never hold a server's pins.
        refused(Http.api)
        refused(Http.media)
        // Nor does another server's client.
        refused(Http.pinned().base)
        assertEquals(1, server.requestCount)
    }

    @Test fun aClearedStoreTrustsNothingItHeld() {
        val pairing = Http.pinned()
        pairing.pins.replaceAll(emptyMap(), mapOf(key to setOf(fingerprint)))
        server.enqueue(MockResponse(body = "ok"))
        assertEquals("ok", get(pairing.base))
        pairing.pins.replaceAll(emptyMap())
        // A fresh connection: the pooled one was made while the pin held.
        pairing.base.connectionPool.evictAll()
        refused(pairing.base)
    }
}

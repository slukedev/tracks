package com.luke.tracks.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLContext

class PinDecisionTest {
    @Test fun aPinnedAddressTakesOnlyItsPins() {
        // Even a certificate the system trusts is refused at a pinned address that it doesn't match:
        // a public CA can't stand in for your server.
        assertEquals(PinDecision.Changed("bb", setOf("aa")), PinDecision.of(setOf("aa"), "bb") { true })
        assertEquals(PinDecision.Pinned, PinDecision.of(setOf("aa", "bb"), "bb") { error("the system isn't asked") })
    }

    @Test fun anUnpinnedAddressIsTheSystemsToJudge() {
        assertEquals(PinDecision.Trusted, PinDecision.of(emptySet(), "aa") { true })
        assertEquals(PinDecision.Unknown("aa"), PinDecision.of(emptySet(), "aa") { false })
    }

    @Test fun fingerprintsCompareWhateverWayTheyAreWritten() {
        val store = PinStore()
        store.replaceAll(mapOf(PinKey.of("NAS.local", 443) to setOf("AB:CD:EF")))
        assertEquals(setOf("abcdef"), store.pins(PinKey.of("nas.local", 443)))
        assertEquals("AB:CD:EF", PinStore.display("abcdef"))
        assertEquals(PinKey("fd00::20", 4747), PinKey.parse("[fd00::20]:4747"))
        assertEquals("[fd00::20]:4747", PinKey("fd00::20", 4747).toString())
        store.replaceAll(emptyMap())
        assertTrue(store.pins(PinKey.of("nas.local", 443)).isEmpty())
    }
}

/** Real TLS against a server with a self-signed certificate, as a home server has. */
class PinningTlsTest {
    private val server = MockWebServer()
    private val store = PinStore()
    private val held = HeldCertificate.Builder().commonName("Trackside").addSubjectAlternativeName("nas.local").build()
    private val presented = PinStore.fingerprint(held.certificate)

    private val client: OkHttpClient by lazy {
        val trust = PinningTrustManager(store)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier(PinningHostnameVerifier(store))
            .retryOnConnectionFailure(false)
            .build()
    }

    private val key get() = PinKey.of(server.hostName, server.port)

    @Before fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start()
    }

    @After fun stop() = server.close()

    private fun get(): String = client.newCall(Request.Builder().url(server.url("/api/v1/ping")).build()).execute().use { it.body.string() }

    @Test fun anUnknownCertificateIsRefusedWithWhatYouNeedToDecide() {
        try {
            get()
            fail("an untrusted certificate must not be used")
        } catch (error: IOException) {
            val refusal = error.certificateNeedingTrust()!!
            assertEquals(key, refusal.key)
            assertEquals(presented, refusal.fingerprint)
            assertTrue("nothing was trusted before", refusal.previous.isEmpty())
            assertEquals(refusal, store.refusal(key))
        }
        assertEquals("the request never reached the server", 0, server.requestCount)
    }

    @Test fun onceTrustedItsUsedEvenThoughItDoesntNameTheAddress() {
        // The certificate names nas.local; the server is reached at localhost. The pin is what counts.
        server.enqueue(MockResponse(body = "ok"))
        store.replaceAll(mapOf(key to setOf(presented)))
        assertEquals("ok", get())
        assertEquals(null, store.refusal(key))
    }

    @Test fun aChangedCertificateIsRefusedUntilYouSayOtherwise() {
        val old = "a".repeat(64)
        store.replaceAll(mapOf(key to setOf(old)))
        try {
            get()
            fail("a different certificate must not be used")
        } catch (error: IOException) {
            val refusal = error.certificateNeedingTrust()!!
            assertEquals(setOf(old), refusal.previous)
            assertEquals(presented, refusal.fingerprint)
        }
        // You confirmed the new one.
        server.enqueue(MockResponse(body = "ok"))
        store.replaceAll(mapOf(key to setOf(presented)))
        assertEquals("ok", get())
    }

    @Test fun aPinForAnotherPortDoesntCount() {
        store.replaceAll(mapOf(PinKey.of(server.hostName, server.port + 1) to setOf(presented)))
        try {
            get()
            fail("pins are per address and port")
        } catch (error: IOException) {
            assertNotNull(error.certificateNeedingTrust())
        }
    }
}

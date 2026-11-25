package com.luke.tracks.net

import com.luke.tracks.library.PlainHttp
import com.luke.tracks.library.SubsonicAuth
import com.luke.tracks.library.SubsonicClient
import com.luke.tracks.library.SubsonicError
import com.luke.tracks.library.SubsonicServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A Subsonic server's credentials on someone else's network, where anyone can be 192.168.1.10. */
class AwayFromHomeTest {
    private val lan = Endpoint("http://192.168.1.10:4533".toHttpUrl(), Endpoint.Kind.Lan)
    private val public = Endpoint("https://music.example.com".toHttpUrl(), Endpoint.Kind.Public)
    private val home = "home-network"
    private val cafe = "cafe-network"

    @Test fun anApiKeyGoesOverPlainHttpOnlyToThisDevice() {
        assertFalse(PlainHttp.allows(lan.url, SubsonicAuth.ApiKey, hasHttps = false, homeNetworks = listOf(home)) { home })
        assertTrue(PlainHttp.allows("http://127.0.0.1:4533".toHttpUrl(), SubsonicAuth.ApiKey, false, emptyList()) { null })
        assertTrue(PlainHttp.allows("http://localhost:4533".toHttpUrl(), SubsonicAuth.ApiKey, false, emptyList()) { null })
        assertTrue(PlainHttp.allows(public.url, SubsonicAuth.ApiKey, true, emptyList()) { null })

        val keyed = SubsonicServer("s", "Home", lan.url.toString(), "", auth = SubsonicAuth.ApiKey)
        val client = SubsonicClient(keyed, "secret-key", { _ -> 200 to "" })
        try {
            client.streamUrl("1", base = lan.url)
            fail("an API key must not be put in a plain http address")
        } catch (_: SubsonicError.KeyNeedsHttps) {
        }
        assertEquals("secret-key", client.streamUrl("1", base = public.url).queryParameter("apiKey"))
    }

    @Test fun aPasswordsTokenGoesOverPlainHttpOnlyOnTheNetworkItWasAddedOn() {
        var asked = 0
        val here = { asked++; cafe }
        assertTrue("https needs no look at the network", PlainHttp.allows(public.url, SubsonicAuth.Password, true, listOf(home), here))
        assertEquals(0, asked)
        assertFalse("a café on the same subnet", PlainHttp.allows(lan.url, SubsonicAuth.Password, true, listOf(home), here))
        assertTrue(PlainHttp.allows(lan.url, SubsonicAuth.Password, true, listOf(home)) { home })
        assertFalse("a network that can't be told apart", PlainHttp.allows(lan.url, SubsonicAuth.Password, true, listOf(home)) { null })
        assertTrue("with no https address, plain http is all there is", PlainHttp.allows(lan.url, SubsonicAuth.Password, false, listOf(home)) { cafe })
    }

    private fun TestScope.subsonic(here: () -> String, probe: suspend (Endpoint, Long) -> Boolean) = ServerEndpoints(
        listOf(lan, public), lan.url, backgroundScope, probe, now = { currentTime },
        allows = { PlainHttp.allows(it.url, SubsonicAuth.Password, true, listOf(home), here) },
    )

    @Test fun aLanImpostorOnAForeignNetworkNeverWinsOverTheHttpsAddress() = runTest {
        val asked = mutableListOf<Endpoint>()
        // The impostor answers in the API's shape at once; the real server takes a while over the internet.
        val probe: suspend (Endpoint, Long) -> Boolean = { endpoint, _ ->
            asked += endpoint
            if (endpoint == lan) true else { delay(400); true }
        }
        val endpoints = subsonic({ cafe }, probe)
        assertEquals(public.url, endpoints.provenBaseUrl())
        assertFalse("the impostor isn't even asked", lan in asked)

        // Back home, the local address wins as it always did.
        asked.clear()
        val atHome = subsonic({ home }, probe)
        assertEquals(lan.url, atHome.provenBaseUrl())
    }

    @Test fun leavingHomeStopsThePlainHttpAddressBeforeTheNextRequest() = runTest {
        var network: Any = "home wifi"
        var identity = home
        var probes = 0
        val endpoints = ServerEndpoints(
            listOf(lan, public), lan.url, backgroundScope,
            probe = { _, _ -> probes++; true },
            now = { currentTime },
            network = { network },
            allows = { PlainHttp.allows(it.url, SubsonicAuth.Password, true, listOf(home)) { identity } },
        )
        assertEquals(lan.url, endpoints.provenBaseUrl())
        val proven = probes
        assertEquals("proven once, then reused", lan.url, endpoints.provenBaseUrl())
        assertEquals(proven, probes)

        // Another Wi-Fi on the same subnet, before any debounced callback has run.
        network = "cafe wifi"
        identity = cafe
        assertEquals(public.url, endpoints.provenBaseUrl())
        assertTrue(probes > proven)
    }
}

/** A Trackside proof over plain http holds only on the network it was made on. */
class ProofOnNetworkTest {
    private val lan = Endpoint("http://192.168.1.20:4747".toHttpUrl(), Endpoint.Kind.Lan)

    @Test fun aNewNetworkAsksForTheProofAgainBeforeAnythingIsSent() = runTest {
        var network: Any = 1
        var proofs = 0
        var answers = true
        val endpoints = ServerEndpoints(listOf(lan), lan.url, backgroundScope, { _, _ -> proofs++; answers }, now = { currentTime }, network = { network })
        endpoints.provenBaseUrl()
        endpoints.provenBaseUrl()
        assertEquals(1, proofs)

        network = 2
        answers = false
        try {
            endpoints.provenBaseUrl()
            fail("someone else's 192.168.1.20 can't prove it's the server")
        } catch (_: Unproven) {
        }
        assertTrue(proofs > 1)
    }

    @Test fun invalidatingIsImmediate() = runTest {
        var proofs = 0
        val endpoints = ServerEndpoints(listOf(lan), lan.url, backgroundScope, { _, _ -> proofs++; true }, now = { currentTime })
        endpoints.provenBaseUrl()
        endpoints.invalidate()
        endpoints.provenBaseUrl()
        assertEquals("the switch callback's invalidate forces a fresh proof", 2, proofs)
    }
}

class NetworkIdentityTest {
    @Test fun theSameRouterIsTheSameNetwork() {
        val a = NetworkIdentity.of(null, listOf("192.168.1.1"), "192.168.1.1", "lan", listOf("192.168.1.1"))
        val b = NetworkIdentity.of(null, listOf("192.168.1.1"), "192.168.1.1", "LAN", listOf("192.168.1.1"))
        assertEquals(a, b)
        assertNotEquals(a, NetworkIdentity.of(null, listOf("192.168.1.1"), "192.168.1.1", "home", listOf("192.168.1.1")))
        assertNotEquals(a, NetworkIdentity.of("\"Cafe\"", listOf("192.168.1.1"), "192.168.1.1", "lan", listOf("192.168.1.1")))
        assertNull("no gateway, nothing to go by", NetworkIdentity.of("\"Home\"", emptyList(), null, null, emptyList()))
    }
}

class RedactionTest {
    @Test fun lastFmAndOAuthSecretsAreTakenOut() {
        val line = Redact.url("https://ws.audioscrobbler.com/2.0/?method=track.scrobble&sk=SESSION&api_sig=SIG&api_key=K&key=K2&access_token=T&sig=S&signature=S2&artist=Low")
        listOf("SESSION", "SIG", "=K", "K2", "=T", "=S").forEach { assertFalse("$it in $line", line.contains(it)) }
        assertTrue(line.contains("artist=Low"))
        assertTrue(line.contains("method=track.scrobble"))
    }
}

package com.luke.tracks.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyTest {
    /** The same table as the iPhone app's EndpointProofTests, so the two agree. */
    @Test fun credentialsGoOverTlsOrToNearbyAddressesOnly() {
        mapOf(
            "https://music.example.com" to true,
            "http://192.168.1.20:4747" to true,
            "http://10.0.0.2" to true,
            "http://172.20.1.1" to true,
            "http://100.101.102.103:4747" to true,
            "http://box.tail1234.ts.net:4747" to true,
            "http://home.local:4747" to true,
            "http://nas:4747" to true,
            "http://nas.home.arpa" to true,
            "http://[fd00::20]:4747" to true,
            "http://[fe80::1]:4747" to true,
            "http://[::1]:4747" to true,
            "http://203.0.113.5:4747" to false,
            "http://172.32.0.1" to false,
            "http://music.example.com" to false,
            "http://[2001:db8::1]:4747" to false,
        ).forEach { (url, allowed) -> assertEquals(url, allowed, Nearby.allowsCredentials(url.toHttpUrl())) }
    }

    @Test fun rangeEdgesAreExact() {
        // 100.64.0.0/10 is Tailscale's; its neighbours are the public internet.
        assertFalse(Nearby.isNearby("100.63.255.255"))
        assertTrue(Nearby.isNearby("100.64.0.0"))
        assertTrue(Nearby.isNearby("100.127.255.255"))
        assertFalse(Nearby.isNearby("100.128.0.0"))
        assertFalse(Nearby.isNearby("172.15.0.1"))
        assertTrue(Nearby.isNearby("172.31.255.255"))
        assertFalse(Nearby.isNearby("192.169.0.1"))
        assertFalse(Nearby.isNearby("169.255.0.1"))
        // Something that looks like an address but isn't one is a name with dots: not nearby.
        assertFalse(Nearby.isNearby("192.168.1.300"))
        assertFalse(Nearby.isNearby("music.local.example.com"))
        assertTrue(Nearby.isNearby("NAS.LOCAL"))
        assertTrue(Nearby.isNearby("router.lan"))
        assertTrue(Nearby.isNearby("fe80::1%wlan0"))
    }

    @Test fun guessesWhatKindOfAddressYouTyped() {
        assertEquals(Endpoint.Kind.Lan, Endpoint.guess("http://192.168.1.20:4533".toHttpUrl()).kind)
        assertEquals(Endpoint.Kind.Tailnet, Endpoint.guess("http://100.100.1.2:4533".toHttpUrl()).kind)
        assertEquals(Endpoint.Kind.Tailnet, Endpoint.guess("https://nas.tail1234.ts.net".toHttpUrl()).kind)
        assertEquals(Endpoint.Kind.Public, Endpoint.guess("https://music.example.com".toHttpUrl()).kind)
    }
}

class RedactTest {
    @Test fun stripsEveryCredentialAndKeepsTheRest() {
        val signed = "https://music.example.com/rest/stream?u=luke&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=Tracks&id=42&format=raw"
        assertEquals("https://music.example.com/rest/stream?v=1.16.1&c=Tracks&id=42&format=raw", Redact.url(signed))
        assertEquals("http://nas/rest/ping?f=json", Redact.url("http://nas/rest/ping?apiKey=k&p=enc:7365&f=json"))
        assertEquals("https://h/api/v1/art/9?size=600#x", Redact.url("https://h/api/v1/art/9?mt=mt1.abc&size=600#x"))
        // Case doesn't hide a key, and an address that's all credentials keeps no '?'.
        assertEquals("https://h/x", Redact.url("https://h/x?APIKEY=k&Token=t"))
        assertEquals("https://h/x", Redact.url("https://h/x"))
    }

    @Test fun findsAddressesInsideMessages() {
        val message = "Unable to connect to http://192.168.1.20:4533/rest/stream?u=luke&t=abc&s=xyz&id=7, then https://h/a?mt=1 failed"
        val redacted = Redact.text(message)
        assertEquals("Unable to connect to http://192.168.1.20:4533/rest/stream?id=7, then https://h/a failed", redacted)
    }

    @Test fun errorChainsAreRedactedWithoutStackTraces() {
        val cause = java.io.IOException("unexpected end of stream on https://m.example.com/rest/getCoverArt?id=1&t=secret&s=salt")
        val error = RuntimeException("Couldn't play", cause)
        val line = Logs.line("Couldn't play https://m.example.com/rest/stream?p=hunter2", error)
        assertFalse(line, line.contains("secret"))
        assertFalse(line, line.contains("hunter2"))
        assertFalse(line, line.contains("salt"))
        assertTrue(line, line.contains("IOException: unexpected end of stream on https://m.example.com/rest/getCoverArt?id=1"))
        assertFalse(line, line.contains("\tat "))
    }
}

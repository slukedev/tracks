package com.luke.tracks.net

import okhttp3.HttpUrl

/**
 * Whether an address can only be reached nearby: a private, loopback, link-local or Tailscale
 * (100.64.0.0/10) address, a name without a dot, or one under `.local`, `.lan`, `.internal`,
 * `.home.arpa` or `.ts.net`. The same rule as the iPhone app and the Trackside server, so all
 * three agree on where plain http may carry credentials.
 */
object Nearby {
    fun isNearby(host: String): Boolean {
        val name = host.lowercase().trim('[', ']').substringBefore('%')
        if (name.isEmpty()) return false
        ipv4(name)?.let { return isNearbyV4(it) }
        if (name.contains(':')) return isNearbyV6(name)
        val dot = name.lastIndexOf('.')
        if (dot < 0) return true
        val tld = name.substring(dot + 1)
        return tld in NEARBY_TLDS || name.endsWith(".home.arpa") || name.endsWith(".ts.net")
    }

    fun isNearby(url: HttpUrl): Boolean = isNearby(url.host)

    /** This device itself: localhost, 127.0.0.0/8 or ::1. */
    fun isLoopback(host: String): Boolean {
        val name = host.lowercase().trim('[', ']')
        if (name == "localhost" || name == "::1") return true
        return ipv4(name)?.let { it[0] == 127 } == true
    }

    /** Credentials go over TLS, or over plain http only to a nearby address. */
    fun allowsCredentials(url: HttpUrl): Boolean = url.isHttps || isNearby(url.host)

    /** Plain http that would leave the home network, which Tracks never connects to. */
    fun isExposed(url: HttpUrl): Boolean = !url.isHttps && !isNearby(url.host)

    private val NEARBY_TLDS = setOf("local", "lan", "internal")

    private fun ipv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        return IntArray(4) { i ->
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            part.toInt().takeIf { it <= 255 } ?: return null
        }
    }

    private fun isNearbyV4(octets: IntArray): Boolean {
        val (a, b) = octets[0] to octets[1]
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
    }

    private fun isNearbyV6(address: String): Boolean {
        if (address == "::1") return true
        if (address.startsWith("::")) return false
        val first = address.substringBefore(':').toIntOrNull(16) ?: return false
        return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
    }
}

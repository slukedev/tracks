package com.luke.tracks.library

import com.luke.tracks.net.Nearby
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A server's address as someone types it, made into one Tracks can reach, read the same way
 * as on iPhone and Mac.
 *
 * Home servers rarely have https, and people type what their browser shows without the
 * scheme: "192.168.1.223:5274". So an address without one gains http only when it can be on
 * the home network alone ([Nearby]: a private, link-local or Tailscale address, `localhost`, a
 * `.local` name, a one-word name like "nas"), whatever its port. Anything else gains https:
 * "music.example.com:4533" is on the internet, where the password's token must not cross in
 * the clear. Spaces around it and slashes after it go. An address that says its scheme keeps it.
 */
object ServerAddress {
    fun url(text: String): HttpUrl? {
        var trimmed = text.trim()
        val hasScheme = trimmed.contains("://")
        while (trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
        if (trimmed.isEmpty()) return null

        if (hasScheme) {
            val scheme = trimmed.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = trimmed.substringAfter("://")
            if (rest.isEmpty() || rest.startsWith("/")) return null
            return "$scheme://$rest".toHttpUrlOrNull()?.tidy()
        }

        // A bare IPv6 address has colons of its own: it goes in brackets before a port can follow.
        if (trimmed.count { it == ':' } > 1 && !trimmed.startsWith("[")) trimmed = "[$trimmed]"
        val probe = "http://$trimmed".toHttpUrlOrNull() ?: return null
        val typedPort = Regex("^(\\[[^]]*]|[^/:]+):(\\d+)").find(trimmed)?.groupValues?.get(2)?.toIntOrNull()
        val scheme = if (isAtHome(probe.host, typedPort)) "http" else "https"
        return "$scheme://$trimmed".toHttpUrlOrNull()?.tidy()
    }

    /** Whether an address typed without a scheme is a home server's, reached over http. */
    fun isAtHome(host: String, port: Int?): Boolean = port != 443 && Nearby.isNearby(host)

    /** Whether the address travels unencrypted somewhere other than this device. */
    fun isUnencrypted(url: HttpUrl): Boolean = !url.isHttps && url.host != "localhost" && url.host != "127.0.0.1"

    /** As people read it: no scheme, no trailing slash. */
    fun display(url: String): String = url.substringAfter("://").trimEnd('/')

    private fun HttpUrl.tidy(): HttpUrl {
        if (host.isEmpty()) return this
        // Empty path segments from doubled slashes go, as does the trailing one.
        val segments = pathSegments.filter { it.isNotEmpty() }
        val builder = newBuilder().encodedPath("/")
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    /** The address as stored: without the trailing slash HttpUrl keeps for an empty path. */
    fun stored(url: HttpUrl): String = url.toString().trimEnd('/')
}

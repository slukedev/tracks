package com.luke.tracks.library.trackside

import okhttp3.HttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The endpoint proof (tracks-companion's docs/adr/0011-endpoint-proof.md): before Tracks sends
 * a device key or a pairing secret to one of a server's addresses, it asks `/ping` there with
 * a fresh challenge, and only an address that answers with the HMAC the real server can make
 * is used. Neither the key nor the secret leaves the phone: the HMAC key is a hash of it that
 * only the server also holds.
 */
object TracksideProof {
    sealed interface Key {
        val queryName: String
        val id: String
        val hmacKey: ByteArray

        /** This device's key, and the device id the server gave it. */
        data class Device(override val id: String, val apiKey: String) : Key {
            override val queryName get() = "device"
            override val hmacKey: ByteArray get() = sha256(apiKey.toByteArray())
            override fun toString() = "Device($id)"
        }

        /** A pairing code's offer id and secret. */
        data class Offer(override val id: String, val secret: String) : Key {
            override val queryName get() = "offer"
            override val hmacKey: ByteArray get() = sha256("offer qr".toByteArray() + byteArrayOf(0) + secret.toByteArray())
            override fun toString() = "Offer($id)"
        }
    }

    private val random = SecureRandom()

    /** A fresh challenge: 16 random bytes, base64url without padding. */
    fun nonce(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** The query a challenge adds to `/ping`. */
    fun query(key: Key, nonce: String, url: HttpUrl): Map<String, String> =
        mapOf("challenge" to nonce, "host" to authority(url), key.queryName to key.id)

    /** The proof the real server gives for this challenge at this address, as lowercase hex. */
    fun expected(key: Key, serverId: String, url: HttpUrl, nonce: String): String {
        val message = listOf("trackside endpoint proof v1", serverId, authority(url), nonce).joinToString("\u0000")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.hmacKey, "HmacSHA256")) }
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Whether [proof] is the one only the real server can give, compared in constant time. */
    fun verify(proof: String?, key: Key, serverId: String, url: HttpUrl, nonce: String): Boolean {
        proof ?: return false
        return MessageDigest.isEqual(proof.lowercase().toByteArray(), expected(key, serverId, url, nonce).toByteArray())
    }

    /** `host` or `host:port` as the address is written, the port only when it isn't the scheme's. */
    fun authority(url: HttpUrl): String {
        val host = url.host.lowercase()
        val written = if (host.contains(':')) "[$host]" else host
        return if (url.port == HttpUrl.defaultPort(url.scheme)) written else "$written:${url.port}"
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}

package com.luke.tracks.library.trackside

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.time.Instant
import java.util.Base64

/**
 * What a Trackside pairing QR code or `trackside://pair` link holds: which server, where it
 * can be reached, and the secret that pairs this phone to the account that showed it.
 */
data class PairingPayload(
    val serverId: String,
    val name: String?,
    val urls: List<TracksideServerUrl>,
    /** The offer's id, which the address asked has to prove it made before [code] is sent. */
    val offerId: String,
    /** Sent as the code to `POST /api/v1/pair/claim`. */
    val code: String,
    val expiresAt: Instant,
) {
    val proofKey: TracksideProof.Key get() = TracksideProof.Key.Offer(offerId, code)

    sealed class Problem(message: String) : Exception(message) {
        data object NotAPairingLink : Problem("That isn't a Trackside pairing code.")
        data class UnsupportedVersion(val version: Int) : Problem("Update Tracks to pair with this server.")
        data object Unreadable : Problem("That pairing code can't be read.")
        data object NoAddresses : Problem("That pairing code has no address Tracks can reach.")
        data object Expired : Problem("That pairing code has expired. Show a new one in your dashboard.")
    }

    @Serializable
    private data class Wire(
        val v: Int,
        @SerialName("server_id") val serverId: String,
        val name: String? = null,
        val urls: List<TracksideServerUrl> = emptyList(),
        val offer: String,
        val code: String,
        val exp: Double,
    )

    companion object {
        const val SUPPORTED_VERSION = 1

        /** Reads a scanned QR code or an opened link. Offers past their expiry are refused. */
        fun parse(text: String, now: Instant = Instant.now()): PairingPayload {
            val uri = runCatching { URI(text.trim()) }.getOrNull()
            if (uri?.scheme?.lowercase() != "trackside" || uri.host?.lowercase() != "pair") throw Problem.NotAPairingLink
            val encoded = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("d=") }?.removePrefix("d=") ?: throw Problem.NotAPairingLink
            val wire = runCatching {
                val json = String(Base64.getUrlDecoder().decode(encoded.trimEnd('=')), Charsets.UTF_8)
                TracksideJson.decodeFromString(Wire.serializer(), json)
            }.getOrNull() ?: throw Problem.Unreadable
            if (wire.v != SUPPORTED_VERSION) throw Problem.UnsupportedVersion(wire.v)
            val urls = wire.urls.filter { it.url.toHttpUrlOrNull() != null }
            if (urls.isEmpty()) throw Problem.NoAddresses
            val expires = Instant.ofEpochMilli((wire.exp * 1000).toLong())
            if (!expires.isAfter(now)) throw Problem.Expired
            return PairingPayload(wire.serverId, wire.name, urls, wire.offer, wire.code, expires)
        }

        fun isLink(text: String) = text.trim().lowercase().startsWith("trackside://pair")

        /** A code as someone types it from the dashboard, tidied: "q7rm 4x2k" is "Q7RM-4X2K". */
        fun typedCode(text: String): String? {
            val characters = text.uppercase().filter { it.code < 128 && it.isLetterOrDigit() }
            if (characters.length != 8) return null
            return characters.take(4) + "-" + characters.takeLast(4)
        }
    }
}

package com.luke.tracks.applemusic

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

/** A signed Apple Music developer token and when Apple stops taking it. */
data class DeveloperToken(val jwt: String, val expiresAtMs: Long) {
    fun usable(nowMs: Long): Boolean = expiresAtMs - nowMs > EXPIRY_MARGIN_MS

    override fun toString() = "DeveloperToken(expiresAtMs=$expiresAtMs)"

    companion object {
        /** A token this close to expiring is fetched again rather than sent. */
        const val EXPIRY_MARGIN_MS = 5 * 60_000L

        /** Apple's limit on a developer token's lifetime: 15,777,000 seconds, about 6 months. */
        const val MAX_LIFETIME_S = 15_777_000L

        /** The token in [jwt], when it's an ES256 JWT within Apple's limits; null otherwise. */
        fun parse(jwt: String): DeveloperToken? {
            val parts = jwt.trim().split('.')
            if (parts.size != 3 || parts.any { it.isEmpty() }) return null
            val header = decodePart(parts[0]) ?: return null
            val claims = decodePart(parts[1]) ?: return null
            if (header["alg"]?.jsonPrimitive?.content != "ES256") return null
            val exp = claims["exp"]?.jsonPrimitive?.longOrNull ?: return null
            val iat = claims["iat"]?.jsonPrimitive?.longOrNull ?: return null
            if (exp <= iat || exp - iat > MAX_LIFETIME_S) return null
            return DeveloperToken(jwt.trim(), exp * 1000)
        }

        private fun decodePart(part: String) = runCatching {
            val bytes = Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }
}

/**
 * Where Apple Music developer tokens come from. The MusicKit private key never ships in the app,
 * so a token is either signed ahead of time for a personal build ([BuildTimeDeveloperToken]) or
 * handed out by your Trackside server ([TracksideDeveloperTokens]).
 */
interface DeveloperTokenProvider {
    /** Whether a token can be had at all, which is what puts Apple Music in Sources. */
    val available: Boolean

    /** A token to send now, or null when none can be had right now. */
    suspend fun token(): DeveloperToken?

    /** The last token [token] gave, without asking anyone; for MusicKit's synchronous callbacks. */
    fun cached(): DeveloperToken?

    /** Apple refused [token] (401): it isn't handed out again. */
    fun rejected(token: DeveloperToken)
}

/** A token signed ahead of time and built into a personal build (secrets.properties). */
class BuildTimeDeveloperToken(jwt: String, private val now: () -> Long = System::currentTimeMillis) : DeveloperTokenProvider {
    private val parsed = jwt.takeIf { it.isNotBlank() }?.let(DeveloperToken::parse)
    @Volatile private var refused = false

    override val available: Boolean get() = !refused && parsed?.usable(now()) == true

    override suspend fun token(): DeveloperToken? = cached()

    override fun cached(): DeveloperToken? = parsed?.takeIf { !refused && it.usable(now()) }

    override fun rejected(token: DeveloperToken) {
        if (token == parsed) refused = true
    }
}

/** The first of several providers that has a token: a server's before the build's own. */
class FirstDeveloperToken(private val providers: List<DeveloperTokenProvider>) : DeveloperTokenProvider {
    override val available: Boolean get() = providers.any { it.available }

    override suspend fun token(): DeveloperToken? = providers.firstNotNullOfOrNull { if (it.available) it.token() else null }

    override fun cached(): DeveloperToken? = providers.firstNotNullOfOrNull { it.cached() }

    override fun rejected(token: DeveloperToken) = providers.forEach { it.rejected(token) }
}

/**
 * A Trackside server's developer token, signed there with the owner's MusicKit key. A proposal
 * the server doesn't offer yet; the endpoint is:
 *
 * `GET /api/v1/me/apple-music/developer-token` (scope: library), with the device key as
 * `Authorization: Bearer tsk_...`, over HTTPS or from a nearby address like every keyed route.
 * `200` with `{"token": "<jwt>", "expires_at": "<RFC 3339>", "refresh_after": "<RFC 3339>"}` and
 * `Cache-Control: no-store`; `404 not_found` when the server has no MusicKit key, which a client
 * remembers until the next start. The server signs ES256 with `kid` and `iss` from its config,
 * `exp` at most 7 days on, never an `origin` claim, and advertises the route as
 * `features.apple_music.developer_token: true` in `GET /me`. The client keeps the token in
 * memory until `refresh_after` (or 5 minutes before `exp`), and asks again after Apple
 * answers 401.
 */
class TracksideDeveloperTokens(
    private val client: OkHttpClient,
    /** The server's `/api/v1/` base on the address in use, and its device key; null while it can't be reached. */
    private val server: suspend () -> Pair<HttpUrl, String>?,
    private val now: () -> Long = System::currentTimeMillis,
) : DeveloperTokenProvider {
    @Serializable
    private data class Wire(
        val token: String,
        @SerialName("expires_at") val expiresAt: String? = null,
        @SerialName("refresh_after") val refreshAfter: String? = null,
    )

    private val lock = Mutex()
    @Volatile private var current: DeveloperToken? = null
    @Volatile private var refreshAtMs = 0L
    @Volatile private var notOffered = false

    override val available: Boolean get() = !notOffered

    override suspend fun token(): DeveloperToken? = lock.withLock {
        val held = current
        if (held != null && held.usable(now()) && now() < refreshAtMs) return held
        if (notOffered) return null
        val fetched = runCatching { fetch() }.getOrNull()
        if (fetched == null) return held?.takeIf { it.usable(now()) }
        current = fetched.first
        refreshAtMs = fetched.second
        fetched.first
    }

    override fun cached(): DeveloperToken? = current?.takeIf { it.usable(now()) }

    override fun rejected(token: DeveloperToken) {
        if (current == token) {
            current = null
            refreshAtMs = 0
        }
    }

    private suspend fun fetch(): Pair<DeveloperToken, Long>? = withContext(Dispatchers.IO) {
        val (base, key) = server() ?: return@withContext null
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(PATH).build())
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                notOffered = true
                return@withContext null
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val wire = WireJson.decodeFromString(Wire.serializer(), response.body.string())
            val token = DeveloperToken.parse(wire.token) ?: return@withContext null
            val stated = wire.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val expires = minOf(token.expiresAtMs, stated ?: token.expiresAtMs)
            val refresh = wire.refreshAfter?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: (expires - DeveloperToken.EXPIRY_MARGIN_MS)
            token.copy(expiresAtMs = expires) to minOf(refresh, expires - DeveloperToken.EXPIRY_MARGIN_MS)
        }
    }

    companion object {
        const val PATH = "me/apple-music/developer-token"
    }
}

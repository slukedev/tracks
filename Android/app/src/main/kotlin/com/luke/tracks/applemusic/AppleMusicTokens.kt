package com.luke.tracks.applemusic

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.luke.tracks.library.trackside.TracksideAppleMusicToken
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Apple answered 403 to [token]: true when a different token is now to be had. */
    suspend fun replaced(token: DeveloperToken): Boolean = false

    /** Emits when [available] may have changed. */
    val availabilityChanges: Flow<Unit> get() = emptyFlow()
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

    override suspend fun replaced(token: DeveloperToken): Boolean = providers.any { it.replaced(token) }

    override val availabilityChanges: Flow<Unit> = providers.map { it.availabilityChanges }.merge()
}

/**
 * Developer tokens from your paired Trackside servers that offer them
 * (`features.apple_music.developer_token` in `GET /me`), signed there with the owner's MusicKit
 * key. A token is kept in memory until its `refresh_after`; a server that answers 404 isn't
 * asked again until the next start, and one that answers 429 not before its `Retry-After`.
 */
class TracksideDeveloperTokens(
    private val scope: CoroutineScope,
    /** Paired Trackside servers by id, in the order they were added. */
    servers: Flow<List<String>>,
    private val clientFor: suspend (serverId: String) -> TracksideClient?,
    private val now: () -> Long = System::currentTimeMillis,
) : DeveloperTokenProvider {
    private val lock = Mutex()
    private val paired = MutableStateFlow<List<String>>(emptyList())

    /** What each server's `/me` said; a server is missing until it has answered. */
    private val offers = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Servers that answered 404 since the app started. */
    private val gone = MutableStateFlow<Set<String>>(emptySet())

    @Volatile private var current: DeveloperToken? = null
    @Volatile private var currentFrom: String? = null
    @Volatile private var refreshAtMs = 0L
    @Volatile private var quietUntilMs = 0L

    private val offering: List<String>
        get() = paired.value.filter { offers.value[it] == true && it !in gone.value }

    override val available: Boolean get() = offering.isNotEmpty()

    override val availabilityChanges: Flow<Unit> =
        combine(paired, offers, gone) { _, _, _ -> available }.distinctUntilChanged().map { }

    init {
        scope.launch {
            servers.distinctUntilChanged().collect { ids ->
                paired.value = ids
                offers.update { known -> known.filterKeys { it in ids } }
                ask(ids.filter { it !in offers.value })
            }
        }
    }

    /** Asks every server again whether it offers tokens, as when the app comes back. */
    fun refresh() {
        scope.launch { ask(paired.value.filter { it !in gone.value }) }
    }

    override suspend fun token(): DeveloperToken? = lock.withLock {
        val held = cached()
        if (held != null && now() < refreshAtMs) return held
        if (now() < quietUntilMs) return held
        if (offering.isEmpty()) ask(paired.value.filter { it !in offers.value })
        for (id in offering) {
            val client = clientFor(id) ?: continue
            val wire = try {
                client.appleMusicDeveloperToken()
            } catch (error: CancellationException) {
                throw error
            } catch (_: TracksideError.NotFound) {
                gone.update { it + id }
                if (currentFrom == id) forget()
                continue
            } catch (error: TracksideError.RateLimited) {
                quietUntilMs = now() + (error.retryAfterSeconds ?: DEFAULT_RETRY_S) * 1000
                return cached()
            } catch (_: Exception) {
                continue
            }
            val (token, refreshAt) = read(wire) ?: continue
            current = token
            currentFrom = id
            refreshAtMs = refreshAt
            return token
        }
        cached()
    }

    override fun cached(): DeveloperToken? = current?.takeIf { it.usable(now()) }

    override fun rejected(token: DeveloperToken) {
        if (current?.jwt == token.jwt) forget()
    }

    override suspend fun replaced(token: DeveloperToken): Boolean {
        rejected(token)
        val next = token() ?: return false
        return next.jwt != token.jwt
    }

    private fun forget() {
        current = null
        currentFrom = null
        refreshAtMs = 0
    }

    private suspend fun ask(ids: List<String>) {
        for (id in ids) {
            val me = try {
                clientFor(id)?.me()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: continue
            offers.update { it + (id to me.offersAppleMusic) }
        }
    }

    private fun read(wire: TracksideAppleMusicToken): Pair<DeveloperToken, Long>? {
        val token = DeveloperToken.parse(wire.token) ?: return null
        val stated = wire.expiresAt?.let(::instant)?.toEpochMilli()
        val expires = minOf(token.expiresAtMs, stated ?: token.expiresAtMs)
        val refreshAt = wire.refreshAfter?.let(::instant)?.toEpochMilli() ?: (expires - 3_600_000)
        return token.copy(expiresAtMs = expires) to minOf(refreshAt, expires - DeveloperToken.EXPIRY_MARGIN_MS)
    }

    companion object {
        const val DEFAULT_RETRY_S = 60L
    }
}

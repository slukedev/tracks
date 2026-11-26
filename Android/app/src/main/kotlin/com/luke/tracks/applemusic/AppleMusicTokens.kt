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
import com.luke.tracks.net.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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

    /** Asks again whether a token can be had, as after adding a MusicKit key to a server. */
    fun refresh() = Unit
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

/**
 * The first of several providers that has a token: a server's before the build's own. Each is
 * asked in turn, not only the ones [available] right now: just after the app starts a server
 * hasn't answered `/me` yet, and its [DeveloperTokenProvider.token] waits for that answer.
 */
class FirstDeveloperToken(private val providers: List<DeveloperTokenProvider>) : DeveloperTokenProvider {
    override val available: Boolean get() = providers.any { it.available }

    override suspend fun token(): DeveloperToken? = providers.firstNotNullOfOrNull { it.token() }

    override fun cached(): DeveloperToken? = providers.firstNotNullOfOrNull { it.cached() }

    override fun rejected(token: DeveloperToken) = providers.forEach { it.rejected(token) }

    override suspend fun replaced(token: DeveloperToken): Boolean = providers.any { it.replaced(token) }

    override val availabilityChanges: Flow<Unit> = providers.map { it.availabilityChanges }.merge()

    override fun refresh() = providers.forEach { it.refresh() }
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
    private val servers: Flow<List<String>>,
    private val clientFor: suspend (serverId: String) -> TracksideClient?,
    private val now: () -> Long = System::currentTimeMillis,
    /** Returns once the paired servers have been read from disk, so [servers] isn't empty only because they haven't been yet. */
    private val ready: suspend () -> Unit = {},
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
    override fun refresh() {
        scope.launch { ask(paired.value.filter { it !in gone.value }) }
    }

    override suspend fun token(): DeveloperToken? = lock.withLock {
        val held = cached()
        if (held != null && now() < refreshAtMs) return held
        if (now() < quietUntilMs) {
            Logs.i("Apple Music key: your server asked to wait (429), using ${if (held != null) "the token held" else "no token"} until then")
            return held
        }
        // Just after the app starts, the paired servers may not have been read yet, nor asked.
        if (withTimeoutOrNull(READY_WAIT_MS) { ready() } == null) Logs.w("Apple Music key: your servers still weren't read after ${READY_WAIT_MS / 1000} s")
        val ids = servers.first()
        paired.value = ids
        if (ids.isEmpty()) {
            Logs.i("Apple Music key: no Trackside server is paired to ask")
            return held
        }
        if (offering.isEmpty()) ask(ids.filter { it !in offers.value && it !in gone.value })
        if (offering.isEmpty()) {
            Logs.w("Apple Music key: none of your ${ids.size} Trackside server(s) offers one (" + ids.joinToString { "$it: " + offerWord(it) } + ")")
            return held
        }
        for (id in offering) {
            val client = clientFor(id)
            if (client == null) {
                Logs.w("Apple Music key: server $id has no Trackside connection right now")
                continue
            }
            Logs.i("Apple Music key: asking server $id for a developer token")
            val wire = try {
                client.appleMusicDeveloperToken()
            } catch (error: CancellationException) {
                throw error
            } catch (_: TracksideError.NotFound) {
                Logs.w("Apple Music key: server $id has no MusicKit key saved (404); not asked again until the app restarts")
                gone.update { it + id }
                if (currentFrom == id) forget()
                continue
            } catch (error: TracksideError.RateLimited) {
                quietUntilMs = now() + (error.retryAfterSeconds ?: DEFAULT_RETRY_S) * 1000
                Logs.w("Apple Music key: server $id is rate limiting (429), waiting ${error.retryAfterSeconds ?: DEFAULT_RETRY_S} s")
                return cached()
            } catch (error: Exception) {
                Logs.w("Apple Music key: server $id didn't hand out a token", error)
                continue
            }
            val read = read(wire)
            if (read == null) {
                Logs.w("Apple Music key: server $id sent a token that isn't a valid ES256 JWT within Apple's limits")
                continue
            }
            val (token, refreshAt) = read
            current = token
            currentFrom = id
            refreshAtMs = refreshAt
            Logs.i("Apple Music key: got a developer token from server $id, good for ${(token.expiresAtMs - now()) / 60_000} more minutes")
            return token
        }
        cached()
    }

    private fun offerWord(id: String): String = when {
        id in gone.value -> "no key saved"
        offers.value[id] == true -> "offers one"
        offers.value[id] == false -> "no Apple Music key in /me"
        else -> "didn't answer /me"
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
            } catch (error: Exception) {
                Logs.w("Apple Music key: couldn't ask server $id whether it offers one", error)
                null
            } ?: continue
            Logs.i("Apple Music key: server $id " + if (me.offersAppleMusic) "offers developer tokens" else "doesn't offer developer tokens (no MusicKit key saved on it)")
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

        /** How long a token waits for the paired servers to be read at the start. */
        const val READY_WAIT_MS = 10_000L
    }
}

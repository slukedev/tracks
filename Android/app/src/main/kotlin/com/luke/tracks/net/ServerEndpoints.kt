package com.luke.tracks.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import java.io.IOException

/** One of the addresses a server can be reached at. */
data class Endpoint(val url: HttpUrl, val kind: Kind) {
    enum class Kind { Lan, Tailnet, Public, Unknown }

    companion object {
        /** What kind an address is, from the address alone, for ones you type yourself. */
        fun guess(url: HttpUrl): Endpoint {
            val host = url.host.lowercase()
            val tailnet = host.endsWith(".ts.net") || Regex("""^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d+\.\d+$""").matches(host)
            val kind = when {
                tailnet -> Kind.Tailnet
                Nearby.isNearby(host) -> Kind.Lan
                else -> Kind.Public
            }
            return Endpoint(url, kind)
        }
    }
}

/** None of the server's addresses answered as the server, so nothing was sent. */
class Unproven : IOException("Couldn't reach the server at any of its addresses.")

/**
 * The addresses one server can be reached at, and which one to use right now: the same
 * choice the iPhone app makes. A server at home may answer on the local network, over
 * Tailscale and on a public address at once. Each is asked in parallel and the answer kept
 * until the network changes or requests start failing: the local address when it answers
 * within [localGraceMs] of the first, since it's the fastest for audio, otherwise whichever
 * answered first.
 *
 * An address is used only once [probe] says it's the server (for Trackside, a proof only the
 * real server can give; for Subsonic, an answer in the API's own shape, which a captive
 * portal's login page never is), and plain http only to a nearby address.
 */
class ServerEndpoints(
    candidates: List<Endpoint>,
    current: HttpUrl,
    private val scope: CoroutineScope,
    private val probe: suspend (Endpoint, timeoutMs: Long) -> Boolean,
    private val localGraceMs: Long = 300,
    private val timeoutMs: Long = 2_000,
    private val plainHttpProofLifetimeMs: Long = 600_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile var candidates: List<Endpoint> = candidates
        private set

    /** Where requests go now. Anything carrying credentials asks [provenBaseUrl] instead. */
    @Volatile var baseUrl: HttpUrl = current
        private set

    /** Set when the server refused credentials over plain http from here: only https until the network changes. */
    @Volatile var httpsOnly = false
        private set

    private val lock = Mutex()
    private var provenAt: Long? = null
    private var racing: Deferred<Endpoint?>? = null
    @Volatile private var failures = 0

    /** The address in use, once it has proven itself; races when it hasn't, or its proof went stale. */
    suspend fun provenBaseUrl(): HttpUrl {
        val proven = lock.withLock { provenAt }
        if (proven != null && (baseUrl.isHttps || now() - proven < plainHttpProofLifetimeMs)) return baseUrl
        race() ?: throw Unproven()
        return baseUrl
    }

    fun update(candidates: List<Endpoint>) {
        if (candidates.isNotEmpty()) this.candidates = candidates
    }

    /**
     * Asks every address and switches to the best that proves itself. A race under way is
     * joined. The race runs in [scope], so a caller that gives up doesn't end it for the rest.
     */
    suspend fun race(): Endpoint? {
        val race = lock.withLock {
            racing ?: scope.async {
                val chosen = try {
                    pick(candidates.filter { !httpsOnly || it.url.isHttps }, localGraceMs, timeoutMs, probe)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                lock.withLock {
                    racing = null
                    failures = 0
                    if (chosen != null) {
                        baseUrl = chosen.url
                        provenAt = now()
                    }
                }
                chosen
            }.also {
                provenAt = null
                racing = it
            }
        }
        return race.await()
    }

    /**
     * The server said this isn't its home network (`https_required`, Subsonic error 50): switches
     * to an https address, the address you'd be asked for credentials again never being the fix.
     * Returns the address now in use, or null when the server has none that answers.
     */
    suspend fun requireHttps(): HttpUrl? {
        httpsOnly = true
        if (baseUrl.isHttps && lock.withLock { provenAt } != null) return baseUrl
        return race()?.url
    }

    /** Wi-Fi joined or left, a VPN came up: nothing goes out until an address proves itself again. */
    fun networkChanged() {
        httpsOnly = false
        scope.launch {
            lock.withLock { provenAt = null }
            race()
        }
    }

    /** A request couldn't reach the server. Two in a row start a race in the background. */
    fun recordFailure() {
        if (++failures < 2) return
        failures = 0
        networkChanged()
    }

    fun recordSuccess() {
        failures = 0
    }

    private sealed interface Outcome {
        data class Answered(val endpoint: Endpoint) : Outcome
        data class Failed(val endpoint: Endpoint) : Outcome
        data object GraceOver : Outcome
        data object TimeUp : Outcome
    }

    companion object {
        internal suspend fun pick(
            candidates: List<Endpoint>,
            localGraceMs: Long,
            timeoutMs: Long,
            probe: suspend (Endpoint, Long) -> Boolean,
        ): Endpoint? = coroutineScope {
            val usable = candidates.filter { Nearby.allowsCredentials(it.url) }
            if (usable.isEmpty()) return@coroutineScope null
            val outcomes = Channel<Outcome>(Channel.UNLIMITED)
            usable.forEach { endpoint ->
                launch {
                    val ok = try {
                        probe(endpoint, timeoutMs)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                    outcomes.send(if (ok) Outcome.Answered(endpoint) else Outcome.Failed(endpoint))
                }
            }
            launch { delay(timeoutMs); outcomes.send(Outcome.TimeUp) }
            var waiting = usable.size
            var localWaiting = usable.count { it.kind == Endpoint.Kind.Lan }
            var fastest: Endpoint? = null
            var chosen: Endpoint? = null
            loop@ while (true) {
                when (val outcome = outcomes.receive()) {
                    is Outcome.Answered -> {
                        waiting--
                        if (outcome.endpoint.kind == Endpoint.Kind.Lan) {
                            chosen = outcome.endpoint
                            break@loop
                        }
                        if (fastest == null) {
                            fastest = outcome.endpoint
                            launch { delay(localGraceMs); outcomes.send(Outcome.GraceOver) }
                        }
                    }
                    is Outcome.Failed -> {
                        waiting--
                        if (outcome.endpoint.kind == Endpoint.Kind.Lan) localWaiting--
                    }
                    Outcome.GraceOver, Outcome.TimeUp -> {
                        chosen = fastest
                        break@loop
                    }
                }
                if (waiting == 0 || (fastest != null && localWaiting == 0)) {
                    chosen = fastest
                    break@loop
                }
            }
            coroutineContext.cancelChildren()
            chosen
        }
    }
}

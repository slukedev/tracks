package com.luke.tracks.net

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** A server address as pins are filed: lowercase host, IPv6 without brackets, and its port. */
data class PinKey(val host: String, val port: Int) {
    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        fun of(host: String, port: Int) = PinKey(host.lowercase().trim('[', ']'), port)

        /** "host:port" or "[v6]:port", as [toString] writes it. */
        fun parse(text: String): PinKey? {
            val colon = text.lastIndexOf(':').takeIf { it > 0 } ?: return null
            val port = text.substring(colon + 1).toIntOrNull() ?: return null
            return of(text.substring(0, colon), port)
        }
    }
}

/** What to do with the certificate an address presented. */
sealed interface PinDecision {
    /** Matches the fingerprint you chose to trust for this address. */
    data object Pinned : PinDecision
    /** Nothing pinned here, and the system trusts it, as for any website. */
    data object Trusted : PinDecision
    /** Nothing pinned here and the system doesn't trust it: you decide, once, whether to. */
    data class Unknown(val fingerprint: String) : PinDecision
    /** Not the certificate you trusted for this address. Refused until you say otherwise. */
    data class Changed(val fingerprint: String, val pinned: Set<String>) : PinDecision

    companion object {
        /**
         * The rule. An address you pinned takes only its pins, whoever signed what it presents,
         * so a public CA can't stand in for your server. A fingerprint the server announced
         * ([offered]) only vouches for a certificate the system doesn't trust: it never
         * overrides or narrows the system's trust. Anything else is left to the system.
         */
        fun of(pins: Set<String>, presented: String, offered: Set<String> = emptySet(), systemTrusts: () -> Boolean): PinDecision = when {
            pins.isNotEmpty() && presented in pins -> Pinned
            pins.isNotEmpty() -> Changed(presented, pins)
            systemTrusts() -> Trusted
            presented in offered -> Pinned
            offered.isNotEmpty() -> Changed(presented, offered)
            else -> Unknown(presented)
        }
    }
}

/**
 * A certificate Tracks won't accept without you: one the system doesn't trust (a server's
 * self-signed certificate) or one that isn't what you trusted before. Carries what's needed
 * to ask you, never to decide for you.
 */
class CertificateNeedsTrust(val key: PinKey, val fingerprint: String, val previous: Set<String>) :
    CertificateException("The certificate at $key needs your confirmation")

/**
 * The fingerprints trusted for one server's addresses, or for one add-a-server flow: never
 * shared, so a server's pins can't reach Apple Music, Last.fm or another server. Holds the
 * ones you confirmed ([pins]), the ones the server announced for its own proven addresses
 * ([offered]), and the last refusal per address so the screens can ask about it. Hosts the
 * app reaches by name ([NEVER_PINNED]) are never pinned.
 */
class PinStore {
    private val pins = ConcurrentHashMap<PinKey, Set<String>>()
    private val offered = ConcurrentHashMap<PinKey, Set<String>>()
    private val refusals = ConcurrentHashMap<PinKey, CertificateNeedsTrust>()

    /** Replaces every pin with these. */
    fun replaceAll(all: Map<PinKey, Set<String>>, announced: Map<PinKey, Set<String>> = emptyMap()) {
        fill(pins, all)
        fill(offered, announced)
    }

    private fun fill(into: ConcurrentHashMap<PinKey, Set<String>>, from: Map<PinKey, Set<String>>) {
        val kept = from.filterKeys { !neverPinned(it.host) }.mapValues { (_, value) -> value.map(::normalize).filter { it.isNotEmpty() }.toSet() }.filterValues { it.isNotEmpty() }
        into.keys.retainAll(kept.keys)
        into.putAll(kept)
    }

    fun offered(key: PinKey): Set<String> = offered[key].orEmpty()

    fun pins(key: PinKey): Set<String> = pins[key].orEmpty()

    fun refusal(key: PinKey): CertificateNeedsTrust? = refusals[key]

    internal fun refused(refusal: CertificateNeedsTrust) {
        refusals[refusal.key] = refusal
    }

    internal fun cleared(key: PinKey) {
        refusals.remove(key)
    }

    companion object {
        /**
         * The services Tracks talks to by name, as network_security_config.xml lists them:
         * the system's trust alone decides for these.
         */
        val NEVER_PINNED = listOf(
            "last.fm", "audioscrobbler.com", "fastly.net", "apple.com", "mzstatic.com",
            "fanart.tv", "musicbrainz.org", "coverartarchive.org", "tracks-server.invalid",
        )

        fun neverPinned(host: String): Boolean {
            val name = host.lowercase().trimEnd('.')
            return NEVER_PINNED.any { name == it || name.endsWith(".$it") }
        }

        /** Lowercase hex without separators: "AB:CD:..." and "abcd..." are the same pin. */
        fun normalize(fingerprint: String) = fingerprint.filter { it.isLetterOrDigit() }.lowercase()

        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

        /** "AB:CD:EF:…" in pairs, as people compare fingerprints. */
        fun display(fingerprint: String) = normalize(fingerprint).uppercase().chunked(2).joinToString(":")
    }
}

/**
 * The system's trust, with your pins on top. Needs the address being connected to, which
 * the TLS stack hands an extended trust manager; a check without one is the system's alone.
 */
class PinningTrustManager(
    private val store: PinStore,
    private val system: X509ExtendedTrustManager = systemTrustManager(),
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val session = (socket as? SSLSocket)?.handshakeSession
        val key = session?.peerHost?.let { PinKey.of(it, session.peerPort.takeIf { port -> port > 0 } ?: socket.port) }
        check(chain, key) { system.checkServerTrusted(chain, authType, socket) }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        val key = engine?.peerHost?.let { PinKey.of(it, engine.peerPort) }
        check(chain, key) { system.checkServerTrusted(chain, authType, engine) }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        system.checkServerTrusted(chain, authType)
    }

    private fun check(chain: Array<X509Certificate>, key: PinKey?, systemCheck: () -> Unit) {
        if (key == null || chain.isEmpty()) return systemCheck()
        val presented = PinStore.fingerprint(chain[0])
        val pins = store.pins(key)
        var systemError: CertificateException? = null
        val decision = PinDecision.of(pins, presented, store.offered(key)) {
            try {
                systemCheck()
                true
            } catch (error: CertificateException) {
                systemError = error
                false
            }
        }
        when (decision) {
            PinDecision.Pinned, PinDecision.Trusted -> store.cleared(key)
            is PinDecision.Unknown, is PinDecision.Changed -> {
                val refusal = CertificateNeedsTrust(key, presented, pins)
                systemError?.let(refusal::initCause)
                store.refused(refusal)
                throw refusal
            }
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = system.checkClientTrusted(chain, authType, socket)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = system.checkClientTrusted(chain, authType, engine)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        fun systemTrustManager(): X509ExtendedTrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first() as X509ExtendedTrustManager
        }
    }
}

/**
 * A pinned certificate is held to its fingerprint, not its name: a server's self-signed
 * certificate rarely names the IP address it's reached at. Everything else is checked by
 * name as usual.
 */
class PinningHostnameVerifier(private val store: PinStore, private val fallback: HostnameVerifier = OkHttpClient().hostnameVerifier) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        val key = PinKey.of(hostname, session.peerPort)
        val pins = store.pins(key)
        val offered = store.offered(key)
        if (pins.isEmpty() && offered.isEmpty()) return fallback.verify(hostname, session)
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        val presented = PinStore.fingerprint(leaf)
        if (presented in pins || (pins.isEmpty() && presented in offered)) return true
        return pins.isEmpty() && fallback.verify(hostname, session)
    }
}

/** The refusal behind a failed request, if a certificate was what stopped it. */
fun Throwable.certificateNeedingTrust(): CertificateNeedsTrust? =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(8).filterIsInstance<CertificateNeedsTrust>().firstOrNull()

/**
 * The same, or, when the TLS stack dropped the cause along the way, the refusal the trust
 * manager last recorded for [url]'s address.
 */
fun Throwable.certificateNeedingTrust(url: okhttp3.HttpUrl, store: PinStore): CertificateNeedsTrust? =
    certificateNeedingTrust() ?: generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(8)
        .firstOrNull { it is javax.net.ssl.SSLException }
        ?.let { store.refusal(PinKey.of(url.host, url.port)) }

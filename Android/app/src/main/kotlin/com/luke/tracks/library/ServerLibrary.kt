package com.luke.tracks.library

import android.content.Context
import android.os.Build
import com.luke.tracks.data.SettingsRepository
import com.luke.tracks.library.trackside.OkHttpTracksideTransport
import com.luke.tracks.library.trackside.PairingPayload
import com.luke.tracks.library.trackside.TracksideCapabilities
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideDeviceInfo
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideLinkPoll
import com.luke.tracks.library.trackside.TracksideLinkStart
import com.luke.tracks.library.trackside.TracksideMediaToken
import com.luke.tracks.library.trackside.TracksideMirror
import com.luke.tracks.library.trackside.TracksidePairRequest
import com.luke.tracks.library.trackside.TracksidePairResult
import com.luke.tracks.library.trackside.TracksidePlaybackRequest
import com.luke.tracks.library.trackside.TracksideProof
import com.luke.tracks.library.trackside.TracksideSync
import com.luke.tracks.library.trackside.TracksideTransport
import com.luke.tracks.net.CertificateNeedsTrust
import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.Http
import com.luke.tracks.net.Logs
import com.luke.tracks.net.MediaResolver
import com.luke.tracks.net.Nearby
import com.luke.tracks.net.PinnedHttp
import com.luke.tracks.net.NetworkKind
import com.luke.tracks.net.NetworkWatcher
import com.luke.tracks.net.PinKey
import com.luke.tracks.net.PinStore
import com.luke.tracks.net.ResolvedMedia
import com.luke.tracks.net.ServerEndpoints
import com.luke.tracks.net.ServerLinks
import com.luke.tracks.net.Unproven
import com.luke.tracks.net.certificateNeedingTrust
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uniffi.tracks_engine.songIdentity
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface ServerStatus {
    data object Syncing : ServerStatus
    data class Ready(val songCount: Int) : ServerStatus
    data class Failed(val message: String) : ServerStatus
    /** The sealed secret couldn't be opened (a restore onto a new phone), or the server forgot this phone: sign in again. */
    data object NeedsPassword : ServerStatus
    /** The server's certificate isn't the one you trusted, or was never trusted: you decide. */
    data class NeedsTrust(val refusal: CertificateNeedsTrust) : ServerStatus
}

data class ServerEntry(val server: SubsonicServer, val status: ServerStatus)

/** A pairing that would take over a server already here: only its own page may pair it again. */
class AlreadyPaired(name: String, at: String, offered: String) :
    Exception("$name is already paired at $at, not $offered. To pair it again, sign in from its page.")

/** What's at an address, asked before signing in, so the form can offer the right way in. */
sealed interface Inspection {
    data class Trackside(val capabilities: TracksideCapabilities) : Inspection
    data class Subsonic(val acceptsApiKey: Boolean) : Inspection
    data class NeedsTrust(val refusal: CertificateNeedsTrust) : Inspection
    data class Unreachable(val message: String) : Inspection
}

/**
 * Your music servers, Subsonic and Trackside: kept in settings, each with its whole song list
 * cached on the phone so the library opens instantly and works on a plane. Synced at launch,
 * whenever a server is added or changed, and when the network changes under a server that
 * couldn't be reached. Songs stream as you play them, from whichever of a server's addresses
 * works where you are ([ServerEndpoints]).
 *
 * Nothing outside this class sees a credential: songs and covers are [ServerLinks], signed
 * only as each request goes out.
 */
class ServerLibrary(
    private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretBox,
    private val scope: CoroutineScope,
    private val network: NetworkWatcher? = NetworkWatcher(context),
    /** This install's device id, the one Trackside Cloud knows it by too. */
    private val deviceId: suspend () -> String = { legacyDeviceId(context) },
    /** How a request reaches a server through the client given; stand-ins in tests. */
    private val tracksideTransport: (OkHttpClient) -> TracksideTransport = { OkHttpTracksideTransport(it) },
    private val subsonicTransport: (OkHttpClient, Long?) -> SubsonicTransport = { http, timeout -> OkHttpTransport(http, timeout) },
    /** Which home network the phone is on ([NetworkWatcher.identity]). */
    private val networkIdentity: () -> String? = { network?.identity() },
    /** On TV only Trackside servers are used; Subsonic ones stay saved but untouched. */
    private val tracksideOnly: Boolean = false,
) {
    private val cacheDir = File(context.filesDir, "servers")
    private val songs = MutableStateFlow<Map<String, List<SubsonicSong>>>(emptyMap())
    private val _entries = MutableStateFlow<List<ServerEntry>>(emptyList())
    private val _tracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    private val _loading = MutableStateFlow(true)
    private val mutex = Mutex()

    val entries: StateFlow<List<ServerEntry>> = _entries.asStateFlow()

    private val _away = MutableStateFlow<Set<String>>(emptySet())
    private val _syncProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Servers, by id, reached now through an address away from home: Tailscale or public. */
    val away: StateFlow<Set<String>> = _away.asStateFlow()

    /** How far each server's first sync has got, 0 to 1, where the server says how many songs it has. */
    val syncProgress: StateFlow<Map<String, Float>> = _syncProgress.asStateFlow()
    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** A server as Tracks is connected to it now: where it answers, and how to ask it. */
    private class Connection(
        val server: SubsonicServer,
        val endpoints: ServerEndpoints,
        val client: MusicServerClient,
        val subsonic: SubsonicClient?,
        val trackside: TracksideClient?,
        /** Its own clients, the only ones that hold its pinned certificates. */
        val http: PinnedHttp,
    ) {
        /** Playback decisions by song, briefly, so seeking doesn't ask the server again. */
        val decisions = ConcurrentHashMap<String, Pair<String, Long>>()
    }

    private val connections = ConcurrentHashMap<String, Connection>()
    /** Media tokens from signing in, until the server's connection is made. */
    private val mediaTokens = ConcurrentHashMap<String, TracksideMediaToken>()
    /** Certificates trusted while adding a server, before it's saved, and the clients that use them. */
    private val provisionalPins = ConcurrentHashMap<PinKey, String>()
    private val adding = Http.pinned()

    fun start() {
        scope.launch {
            settings.settings.map { it.subsonicServers }.distinctUntilChanged().collect { raw ->
                val servers = parse(raw).filter { !tracksideOnly || it.isTrackside }
                mutex.withLock {
                    connect(servers)
                    val cached = withContext(Dispatchers.IO) { servers.associate { it.id to readCache(it.id) } }
                    songs.value = cached.filterValues { it != null }.mapValues { it.value!! }
                    _entries.value = servers.map { server ->
                        val previous = _entries.value.firstOrNull { it.server.id == server.id && it.server == server }
                        ServerEntry(server, previous?.status ?: cached[server.id]?.let { ServerStatus.Ready(it.size) } ?: ServerStatus.Syncing)
                    }
                    publish()
                }
                _loading.value = false
                // Each server whose list isn't fresh this launch, in the background.
                servers.filter { it.id !in syncedThisLaunch }.forEach { server -> scope.launch { sync(server) } }
            }
        }
        network?.let { watcher ->
            watcher.onSwitch { connections.values.forEach { it.endpoints.invalidate() } }
            scope.launch {
                watcher.changes.collect {
                    connections.values.forEach { it.endpoints.networkChanged() }
                    scope.launch {
                        connections.values.forEach { it.endpoints.race() }
                        noteAway()
                    }
                    refreshAddresses()
                    // A server that couldn't be reached may be reachable from here.
                    _entries.value.filter { it.status is ServerStatus.Failed }.forEach { scope.launch { sync(it.server) } }
                }
            }
        }
    }

    private val syncedThisLaunch: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val syncing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // MARK: Connections

    private fun connect(servers: List<SubsonicServer>) {
        val ids = servers.map { it.id }.toSet()
        connections.keys.filterNot { it in ids }.forEach {
            connections.remove(it)
            ServerLinks.resolvers.remove(it)
        }
        servers.forEach { server ->
            if (connections[server.id]?.server == server) return@forEach
            val connection = makeConnection(server)
            if (connection == null) {
                connections.remove(server.id)
                ServerLinks.resolvers.remove(server.id)
            } else {
                connections[server.id] = connection
                ServerLinks.resolvers[server.id] = MediaResolver { link, fresh ->
                    runBlocking(Dispatchers.IO) { resolve(connection, link, fresh) }
                }
            }
        }
    }

    private fun makeConnection(server: SubsonicServer): Connection? {
        val secret = secrets.open(server.secret) ?: return null
        val home = server.url.toHttpUrlOrNull() ?: return null
        val candidates = candidates(server)
        val http = Http.pinned(pinsOf(server))
        val here: () -> Any? = { network?.network() }
        if (server.isTrackside) {
            val uuid = server.serverUuid ?: return null
            val key = TracksideProof.Key.Device(server.deviceId.orEmpty(), secret)
            val probeTransport = tracksideTransport(http.base)
            val endpoints = ServerEndpoints(candidates, home, scope, probe = { endpoint, timeout ->
                TracksideClient.proves(endpoint, uuid, key, timeout, probeTransport)
            }, network = here)
            val client = TracksideClient(endpoints, secret, server.deviceId, mediaTokens.remove(server.id), tracksideTransport(http.api), events = http.events)
            return Connection(server, endpoints, TracksideMusicServer(client), null, client, http)
        }
        val hasHttps = candidates.any { it.url.isHttps }
        val endpoints = ServerEndpoints(
            candidates, home, scope,
            probe = { endpoint, timeout -> SubsonicClient.answers(endpoint.url, subsonicTransport(http.base, timeout)) },
            network = here,
            allows = { PlainHttp.allows(it.url, server.auth, hasHttps, server.homeNetworks, networkIdentity) },
        )
        val client = SubsonicClient(server, secret, subsonicTransport(http.api, null), endpoints = endpoints)
        return Connection(server, endpoints, client, client, null, http)
    }

    /** A link's real address: signed now, at the address that works now. */
    private suspend fun resolve(connection: Connection, link: ServerLinks.Link, fresh: Boolean): ResolvedMedia {
        connection.subsonic?.let { client ->
            // A media address refused (HTTP 403) over plain http: the server may want https from here.
            val base = if (fresh && !connection.endpoints.baseUrl.isHttps) connection.endpoints.requireHttps() ?: client.provenBase() else client.provenBase()
            return ResolvedMedia(
                when (link.kind) {
                    ServerLinks.Kind.Stream -> client.streamUrl(link.id, base = base)
                    ServerLinks.Kind.Cover -> client.coverArtUrl(link.id, link.size, base)
                    ServerLinks.Kind.Play -> throw IOException("Only Trackside plays songs it doesn't have.")
                },
                client = connection.http.base,
            )
        }
        val client = connection.trackside ?: throw IOException("Not a server Tracks can play from.")
        if (fresh) {
            client.renewMediaToken()
            connection.decisions.keys.removeAll { it.startsWith("${link.id}|") }
        } else {
            client.freshMediaToken()
        }
        client.endpoints.provenBaseUrl()
        val url = when (link.kind) {
            ServerLinks.Kind.Cover -> client.artworkUrl(link.id, link.size)
            // Its own address, at the server in use; only a path there gets the token.
            ServerLinks.Kind.Play -> client.mediaUrl(link.id)?.takeIf { TracksideClient.isSameOrigin(it, client.endpoints.baseUrl) } ?: throw IOException("Not a stream on this server.")
            ServerLinks.Kind.Stream -> {
                val now = System.currentTimeMillis()
                val kind = network?.kind ?: NetworkKind.Unknown
                // On cellular, the server's cellular profile; elsewhere, the original where it plays.
                val profile = if (kind == NetworkKind.Cellular) client.cellularProfile() else null
                val key = "${link.id}|${kind.wire}"
                val reference = connection.decisions[key]?.takeIf { now - it.second < DECISION_KEEP_MS }?.first
                    ?: client.decide(TracksidePlaybackRequest.exoPlayer(link.id, profile?.let(TracksideClient::bitRate), kind.wire, profile)).url
                        .also { connection.decisions[key] = it to now }
                client.mediaUrl(reference) ?: throw TracksideError.NotTrackside
            }
        }
        return ResolvedMedia(url, client = connection.http.base)
    }

    private fun candidates(server: SubsonicServer): List<Endpoint> {
        val listed = server.addresses.mapNotNull { address ->
            val url = address.url.toHttpUrlOrNull() ?: return@mapNotNull null
            when (address.kind) {
                "lan" -> Endpoint(url, Endpoint.Kind.Lan)
                "tailnet" -> Endpoint(url, Endpoint.Kind.Tailnet)
                "public" -> Endpoint(url, Endpoint.Kind.Public)
                else -> Endpoint.guess(url)
            }
        }
        val home = server.url.toHttpUrlOrNull()?.let(Endpoint::guess)
        return (listOfNotNull(home) + listed).distinctBy { it.url }
    }

    /**
     * One server's certificates, for its own clients alone: the ones you confirmed, and the
     * ones it announced for its own addresses over a proven connection. An announced one only
     * lets its client reach that address to ask for the proof, and never stands in for a
     * certificate the system trusts.
     */
    private fun pinsOf(server: SubsonicServer): PinStore {
        val confirmed = server.pins.mapNotNull { (key, fingerprint) -> PinKey.parse(key)?.let { it to setOf(fingerprint) } }.toMap()
        val announced = HashMap<PinKey, MutableSet<String>>()
        server.addresses.forEach { address ->
            val url = address.url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@forEach
            address.fingerprint?.let { announced.getOrPut(PinKey.of(url.host, url.port)) { mutableSetOf() } += it }
        }
        return PinStore().apply { replaceAll(confirmed, announced) }
    }

    private fun publishAddingPins() {
        adding.pins.replaceAll(provisionalPins.mapValues { setOf(it.value) })
    }

    // MARK: Asking

    /** A client for [server], or null when its secret can't be opened. */
    /** A Trackside server's client, once the saved servers are read; null for any other. */
    suspend fun tracksideClient(serverId: String): TracksideClient? {
        loading.first { !it }
        return connections[serverId]?.trackside
    }

    fun client(server: SubsonicServer): MusicServerClient? =
        connections[server.id]?.takeIf { it.server == server }?.client ?: makeConnection(server)?.client

    /** Album and artist lookups on one of your servers, or null when it's gone or locked. */
    fun browse(serverId: String): ServerBrowse? {
        val server = _entries.value.firstOrNull { it.server.id == serverId }?.server ?: return null
        return client(server)?.let { ServerBrowse(server, it) }
    }

    /** The saved password or key, for checking a server again without typing it. */
    fun savedPassword(server: SubsonicServer): String? = secrets.open(server.secret)

    /** What's at [url]: Trackside, a Subsonic server (and whether it takes an API key), or why neither answered. */
    suspend fun inspect(url: HttpUrl): Inspection {
        if (Nearby.isExposed(url)) return Inspection.Unreachable(TracksideError.InsecureAddress.message!!)
        try {
            TracksideClient.capabilities(url, tracksideTransport(adding.base)).takeIf { it.isTrackside }?.let { return Inspection.Trackside(it) }
        } catch (error: Exception) {
            error.certificateNeedingTrust(url, adding.pins)?.let { return Inspection.NeedsTrust(it) }
        }
        val probe = subsonicTransport(adding.base, TracksideClient.PROBE_TIMEOUT_MS * 3)
        return try {
            if (SubsonicClient.answers(url, probe)) Inspection.Subsonic(SubsonicClient.acceptsApiKey(url, probe))
            else Inspection.Unreachable(SubsonicError.NotSubsonic.message!!)
        } catch (error: Exception) {
            error.certificateNeedingTrust(url, adding.pins)?.let { Inspection.NeedsTrust(it) } ?: Inspection.Unreachable(SubsonicError.Unreachable(error).message!!)
        }
    }

    /** Trusts a certificate while adding a server; it's kept with the server once saved. */
    fun trustWhileAdding(refusal: CertificateNeedsTrust) {
        provisionalPins[refusal.key] = refusal.fingerprint
        publishAddingPins()
    }

    /** Trusts the certificate a saved server now presents, as you confirmed. */
    suspend fun trust(serverId: String, refusal: CertificateNeedsTrust) {
        val all = parse(settings.settings.first().subsonicServers)
        val updated = all.map { if (it.id == serverId) it.copy(pins = it.pins + (refusal.key.toString() to refusal.fingerprint)) else it }
        syncedThisLaunch.remove(serverId)
        settings.update { it.copy(subsonicServers = encode(updated)) }
    }

    /** Checks an address and sign-in before saving it. */
    suspend fun test(url: HttpUrl, username: String, password: String, auth: SubsonicAuth = SubsonicAuth.Password): Result<Unit> = runCatching {
        if (Nearby.isExposed(url)) throw TracksideError.InsecureAddress
        val probe = SubsonicServer("probe", "", ServerAddress.stored(url), username.trim(), auth = auth)
        SubsonicClient(probe, password, subsonicTransport(adding.api, null)).ping()
    }.recoverCatching { error -> throw error.certificateNeedingTrust() ?: error }

    /**
     * Adds a Subsonic server, or changes [existingId]. A blank [password] keeps the one
     * already saved. [others] are its other addresses, raced with the first wherever you are.
     */
    suspend fun save(
        existingId: String?,
        name: String,
        url: HttpUrl,
        username: String,
        password: String,
        auth: SubsonicAuth = SubsonicAuth.Password,
        others: List<HttpUrl> = emptyList(),
    ): SubsonicServer {
        if (auth == SubsonicAuth.ApiKey && !PlainHttp.mayCarryKey(url)) throw SubsonicError.KeyNeedsHttps
        val all = parse(settings.settings.first().subsonicServers)
        val previous = all.firstOrNull { it.id == existingId }
        val addresses = others.filter { it != url && !Nearby.isExposed(it) }.distinct().map { other ->
            StoredAddress(ServerAddress.stored(other), Endpoint.guess(other).kind.name.lowercase())
        }
        // Added here, so plain http may carry the password's token on this network. Only when it's
        // added: an edit, or a server added before this, never learns a network on its own.
        val plain = (listOf(url) + others).any { !it.isHttps && !Nearby.isLoopback(it.host) }
        val homes = previous?.homeNetworks ?: listOfNotNull(if (plain) networkIdentity() else null)
        val server = SubsonicServer(
            id = previous?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { url.host },
            url = ServerAddress.stored(url),
            username = username.trim(),
            secret = if (password.isEmpty() && previous != null) previous.secret else secrets.seal(password),
            auth = auth,
            addresses = addresses,
            pins = previous?.pins.orEmpty() + pinsFor(listOf(url) + others),
            homeNetworks = homes,
        )
        return store(server, previous)
    }

    /** How this phone signs in to a Trackside server. */
    sealed interface Pairing {
        data class Password(val username: String, val password: String) : Pairing
        data class Code(val code: String) : Pairing
        data class Approved(val result: TracksidePairResult) : Pairing
    }

    /**
     * Signs in to the Trackside server at [url] and saves it as a new server, or as
     * [existingId] when you chose to pair that one again. Never takes over another server
     * that's already here.
     */
    suspend fun pair(existingId: String?, name: String, url: HttpUrl, pairing: Pairing): SubsonicServer {
        val all = parse(settings.settings.first().subsonicServers)
        val offered = ServerAddress.hostPort(url)
        if (pairing !is Pairing.Approved) {
            // Asked first, so a password or code never goes to a server that would take another's place.
            val id = runCatching { TracksideClient.capabilities(url, tracksideTransport(adding.base)).serverId }.getOrNull()
            refuseTakeover(all, id, existingId, offered)
        }
        val device = deviceInfo()
        val transport = tracksideTransport(adding.api)
        val result = when (pairing) {
            is Pairing.Password -> TracksideClient.login(url, pairing.username.trim(), pairing.password, device, transport)
            is Pairing.Code -> TracksideClient.claim(url, pairing.code, device, transport)
            is Pairing.Approved -> pairing.result
        }
        return saveTrackside(existingId, name, url, result)
    }

    /**
     * Pairs with a scanned or pasted `trackside://pair` link, once one of its addresses proves
     * it made the offer: a new server, or [existingId] when you're pairing that one again from
     * its page. The link's certificates are trusted only by a client made for this pairing,
     * and forgotten when it ends; the one at the address that proved itself is kept with the
     * server.
     */
    suspend fun pair(payload: PairingPayload, existingId: String? = null): SubsonicServer {
        val all = parse(settings.settings.first().subsonicServers)
        val existing = existingId?.let { id -> all.firstOrNull { it.id == id } }
        if (existing != null && !existing.serverUuid.equals(payload.serverId, ignoreCase = true)) {
            throw AlreadyPaired(existing.name, ServerAddress.hostPort(existing.url), payload.urls.firstOrNull()?.url?.let(ServerAddress::hostPort) ?: "this server")
        }
        val candidates = payload.urls.mapNotNull { stored -> stored.url.toHttpUrlOrNull()?.let { Endpoint(it, kindOf(stored.kind, it)) } }
        refuseTakeover(all, payload.serverId, existing?.id, candidates.firstOrNull()?.url?.let(ServerAddress::hostPort) ?: "this server")
        val linkPins = HashMap<PinKey, String>()
        payload.urls.forEach { stored ->
            val url = stored.url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return@forEach
            stored.fingerprint?.let { linkPins[PinKey.of(url.host, url.port)] = PinStore.normalize(it) }
        }
        val pairing = Http.pinned()
        pairing.pins.replaceAll(emptyMap(), linkPins.mapValues { setOf(it.value) })
        try {
            val probe = tracksideTransport(pairing.base)
            val chosen = ServerEndpoints.pick(candidates, 300, TracksideClient.PROBE_TIMEOUT_MS) { endpoint, timeout ->
                TracksideClient.proves(endpoint, payload.serverId, payload.proofKey, timeout, probe)
            } ?: throw TracksideError.Unproven
            val result = TracksideClient.claim(chosen.url, payload.code, deviceInfo(), tracksideTransport(pairing.api))
            // The server that answered the claim has to be the one whose offer was proven.
            if (!result.server.serverId.equals(payload.serverId, ignoreCase = true)) throw TracksideError.Unproven
            val proven = linkPins[PinKey.of(chosen.url.host, chosen.url.port)]
            return saveTrackside(existing?.id, payload.name.orEmpty(), chosen.url, result, proven?.let { StoredAddress(chosen.url.toString(), chosen.kind.name.lowercase(), it) })
        } finally {
            pairing.pins.replaceAll(emptyMap())
        }
    }

    /** Refuses a pairing with [serverId] when another server here already is that server. */
    private fun refuseTakeover(all: List<SubsonicServer>, serverId: String?, existingId: String?, offered: String) {
        serverId ?: return
        val other = all.firstOrNull { it.isTrackside && it.id != existingId && it.serverUuid.equals(serverId, ignoreCase = true) } ?: return
        throw AlreadyPaired(other.name, ServerAddress.hostPort(other.url), offered)
    }

    /** Asks to be approved from the dashboard: show the code it gives, then [awaitApproval]. */
    suspend fun requestApproval(url: HttpUrl): TracksidePairRequest =
        TracksideClient.requestPairing(url, deviceInfo(), tracksideTransport(adding.api))

    /** Polls until the request is approved (its result) or expires (null). */
    suspend fun awaitApproval(url: HttpUrl, request: TracksidePairRequest): TracksidePairResult? {
        val transport = tracksideTransport(adding.api)
        val expires = runCatching { java.time.Instant.parse(request.expiresAt) }.getOrNull()
        while (expires == null || java.time.Instant.now().isBefore(expires)) {
            delay((request.pollIntervalMs ?: 2_000L).coerceIn(1_000L, 10_000L))
            val poll = runCatching { TracksideClient.pollPairing(request, url, transport) }.getOrNull() ?: continue
            when (poll.state) {
                "approved" -> return poll.result
                "expired" -> return null
            }
        }
        return null
    }

    /**
     * Starts linking this TV with the server at [url]: show the code and a QR of its
     * `linkUrl`, then [awaitLink], and keep a [TracksideLinkPoll.Linked] result with [saveLink].
     */
    suspend fun startLink(url: HttpUrl): TracksideLinkStart =
        TracksideClient.startLink(url, deviceInfo(), tracksideTransport(adding.api))

    suspend fun awaitLink(url: HttpUrl, start: TracksideLinkStart): TracksideLinkPoll =
        TracksideClient.awaitLink(url, start, tracksideTransport(adding.api))

    /**
     * Keeps a linked TV's credentials, replacing the entry for the same server when one is
     * already here, as after relinking or restoring a phone's backup onto the TV.
     */
    suspend fun saveLink(url: HttpUrl, result: TracksidePairResult): SubsonicServer {
        val all = parse(settings.settings.first().subsonicServers)
        val same = all.firstOrNull { it.isTrackside && it.serverUuid.equals(result.server.serverId, ignoreCase = true) }
        return saveTrackside(same?.id, same?.name.orEmpty(), url, result)
    }

    private suspend fun saveTrackside(existingId: String?, name: String, url: HttpUrl, result: TracksidePairResult, proven: StoredAddress? = null): SubsonicServer {
        val all = parse(settings.settings.first().subsonicServers)
        refuseTakeover(all, result.server.serverId, existingId, ServerAddress.hostPort(url))
        val previous = all.firstOrNull { it.id == existingId }
        val announced = result.server.urls.orEmpty().filter { it.url.toHttpUrlOrNull() != null }.map { StoredAddress(it.url, it.kind, it.fingerprint) }
        val addresses = when {
            proven == null -> announced
            announced.any { it.url.toHttpUrlOrNull() == url } -> announced.map { if (it.url.toHttpUrlOrNull() == url && it.fingerprint == null) it.copy(fingerprint = proven.fingerprint) else it }
            else -> announced + proven
        }
        val server = SubsonicServer(
            id = previous?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { result.server.name?.takeIf { it.isNotBlank() } ?: url.host },
            url = ServerAddress.stored(url),
            username = result.user.username,
            secret = secrets.seal(result.apiKey),
            kind = ServerKind.Trackside,
            addresses = addresses,
            serverUuid = result.server.serverId,
            deviceId = result.device.id,
            pins = previous?.pins.orEmpty() + pinsFor(listOf(url) + addresses.mapNotNull { it.url.toHttpUrlOrNull() }),
        )
        result.mediaToken?.let { mediaTokens[server.id] = it }
        return store(server, previous)
    }

    /**
     * What Trackside Cloud syncs with when it uses [serverId]: its server id, address and key.
     * Changes whenever the server is paired again, so sync can ask before sending anything.
     */
    suspend fun syncBinding(serverId: String): String? {
        val server = parse(settings.settings.first().subsonicServers).firstOrNull { it.id == serverId } ?: return null
        val text = listOf(server.serverUuid.orEmpty().lowercase(), server.url, server.secret, server.deviceId.orEmpty()).joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun store(server: SubsonicServer, previous: SubsonicServer?): SubsonicServer {
        val all = parse(settings.settings.first().subsonicServers)
        syncedThisLaunch.remove(server.id)
        val updated = if (previous == null) all + server else all.map { if (it.id == server.id) server else it }
        provisionalPins.keys.removeAll(server.pins.keys.mapNotNull(PinKey::parse).toSet())
        publishAddingPins()
        settings.update { it.copy(subsonicServers = encode(updated)) }
        return server
    }

    /** The certificates trusted while adding, for these addresses. */
    private fun pinsFor(urls: List<HttpUrl>): Map<String, String> = urls.filter { it.isHttps }.mapNotNull { url ->
        val key = PinKey.of(url.host, url.port)
        provisionalPins[key]?.let { key.toString() to it }
    }.toMap()

    private fun kindOf(kind: String, url: HttpUrl) = when (kind) {
        "lan" -> Endpoint.Kind.Lan
        "tailnet" -> Endpoint.Kind.Tailnet
        "public" -> Endpoint.Kind.Public
        else -> Endpoint.guess(url).kind
    }

    private suspend fun deviceInfo(): TracksideDeviceInfo {
        val id = deviceId()
        val name = runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val tv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        return TracksideDeviceInfo(
            clientDeviceId = id,
            name = name,
            platform = if (tv) TracksideDeviceInfo.ANDROID_TV else TracksideDeviceInfo.ANDROID,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            appVersion = version,
        )
    }

    /** Removes a server. A Trackside server is asked to forget this phone's key first, if it can be reached. */
    suspend fun remove(id: String) {
        val all = parse(settings.settings.first().subsonicServers)
        connections[id]?.trackside?.let { client -> withTimeoutOrNull(5_000) { runCatching { client.revokeThisDevice() } } }
        settings.update { it.copy(subsonicServers = encode(all.filterNot { s -> s.id == id })) }
        withContext(Dispatchers.IO) {
            cacheFile(id).delete()
            cursorFile(id).delete()
        }
    }

    // MARK: Syncing

    suspend fun syncAll() {
        _entries.value.map { it.server }.forEach { sync(it) }
    }

    suspend fun sync(server: SubsonicServer) {
        if (!syncing.add(server.id)) return
        try {
            syncNow(server)
        } finally {
            syncing.remove(server.id)
            _syncProgress.update { it - server.id }
            noteAway()
        }
    }

    private suspend fun syncNow(server: SubsonicServer) {
        val connection = connections[server.id]?.takeIf { it.server == server } ?: makeConnection(server)
        if (connection == null) {
            setStatus(server.id, ServerStatus.NeedsPassword)
            return
        }
        if (songs.value[server.id] == null) setStatus(server.id, ServerStatus.Syncing)
        val started = System.currentTimeMillis()
        Logs.i("Servers: syncing ${server.name}" + if (songs.value[server.id] == null) " for the first time" else "")
        try {
            val all = connection.trackside?.let { syncTrackside(server, it) } ?: connection.client.allSongs()
            Logs.i("Servers: ${server.name} synced ${all.size} songs in ${(System.currentTimeMillis() - started) / 1000} s")
            withContext(Dispatchers.IO) { writeCache(server.id, all) }
            syncedThisLaunch += server.id
            mutex.withLock {
                songs.update { it + (server.id to all) }
                setStatus(server.id, ServerStatus.Ready(all.size))
                publish()
            }
        } catch (error: Exception) {
            Logs.w("Couldn't sync ${server.name}", error)
            // A race swallows each address's error, so a certificate refused during one is found by address.
            val refusal = connection.endpoints.candidates.firstNotNullOfOrNull {
                if (error is Unproven || error is TracksideError.Unproven || error.cause is Unproven) connection.http.pins.refusal(PinKey.of(it.url.host, it.url.port))
                else error.certificateNeedingTrust(it.url, connection.http.pins)
            }
            when {
                refusal != null -> setStatus(server.id, ServerStatus.NeedsTrust(refusal))
                error is TracksideError.Unauthenticated || error is SubsonicError.WrongCredentials || error is SubsonicError.WrongKey ->
                    setStatus(server.id, ServerStatus.NeedsPassword)
                songs.value[server.id] == null -> setStatus(server.id, ServerStatus.Failed(message(server, error)))
            }
        }
    }

    /** The changes since the last sync, or a fresh snapshot; the server's latest addresses too. */
    private suspend fun syncTrackside(server: SubsonicServer, client: TracksideClient): List<SubsonicSong> {
        refreshAddresses(server, client)
        val cached = withContext(Dispatchers.IO) { readCache(server.id) }
        val cursor = withContext(Dispatchers.IO) { runCatching { cursorFile(server.id).readText() }.getOrNull()?.takeIf { it.isNotBlank() } }
        val mirror = TracksideMirror(cached.orEmpty().associateBy { it.id }, cursor.takeIf { cached != null })
        var total = 0
        var received = 0
        val updated = TracksideSync.run(mirror, { after, limit ->
            client.sync(after, limit).also { page ->
                if (page.mode != "snapshot" || cached != null) return@also
                total = maxOf(total, page.libraries.orEmpty().sumOf { it.songCount ?: 0 })
                received += page.songs.orEmpty().size
                if (total > 0) _syncProgress.update { it + (server.id to (received.toFloat() / total).coerceIn(0f, 1f)) }
            }
        })
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            updated.cursor?.let { cursorFile(server.id).writeText(it) } ?: cursorFile(server.id).delete()
        }
        return updated.songs.values.sortedBy { it.id }
    }

    /**
     * The server's addresses as it lists them now, asked only for changes since the version
     * kept; a server too old for `/server/urls` answers through `/me`. Kept so the next launch
     * races them too. Read only from a proven address, and a new one is used only once its own
     * proof passes.
     */
    private suspend fun refreshAddresses(server: SubsonicServer, client: TracksideClient) {
        val (urls, version) = try {
            val answer = client.serverUrls(server.addressesVersion) ?: return
            if (!answer.serverId.equals(server.serverUuid, ignoreCase = true)) return
            answer.urls to answer.version
        } catch (_: TracksideError.NotFound) {
            client.me().server.urls.orEmpty() to null
        }
        if (urls.isEmpty()) return
        val addresses = urls.filter { it.url.toHttpUrlOrNull() != null }.map { StoredAddress(it.url, it.kind, it.fingerprint) }
        if (addresses == server.addresses && version == server.addressesVersion) return
        connections[server.id]?.endpoints?.update(addresses.mapNotNull { a -> a.url.toHttpUrlOrNull()?.let { Endpoint(it, kindOf(a.kind, it)) } })
        val all = parse(settings.settings.first().subsonicServers)
        settings.update { it.copy(subsonicServers = encode(all.map { s -> if (s.id == server.id) s.copy(addresses = addresses, addressesVersion = version) else s })) }
    }

    /** On coming to the foreground: each Trackside server's addresses, if they changed. */
    fun refreshAddresses() {
        _entries.value.map { it.server }.filter { it.isTrackside }.forEach { server ->
            val client = connections[server.id]?.trackside ?: return@forEach
            scope.launch { runCatching { refreshAddresses(server, client) } }
        }
    }

    private fun message(server: SubsonicServer, error: Exception): String = when (error) {
        is Unproven, is TracksideError.Unproven, is IOException -> "Can't reach ${server.name}. Check that you're online."
        else -> error.message ?: "Something went wrong."
    }

    /** Stars or unstars a song on its server, when it's a server's. */
    suspend fun star(mediaId: String, on: Boolean) {
        val (serverId, songId) = parseMediaId(mediaId) ?: return
        val server = _entries.value.firstOrNull { it.server.id == serverId }?.server ?: return
        val client = client(server) ?: return
        runCatching { if (on) client.star(songId) else client.unstar(songId) }
    }

    private fun noteAway() {
        _away.value = connections.filterValues { connection ->
            val at = connection.endpoints.baseUrl
            val kind = connection.endpoints.candidates.firstOrNull { it.url == at }?.kind ?: Endpoint.guess(at).kind
            kind == Endpoint.Kind.Tailnet || kind == Endpoint.Kind.Public
        }.keys
    }

    private fun setStatus(id: String, status: ServerStatus) {
        _entries.update { list -> list.map { if (it.server.id == id) it.copy(status = status) else it } }
        if (status !is ServerStatus.Ready) Logs.i("Servers: ${_entries.value.firstOrNull { it.server.id == id }?.server?.name ?: "a server"} is ${status.javaClass.simpleName}")
    }

    private suspend fun publish() = withContext(Dispatchers.Default) {
        val servers = _entries.value.map { it.server }
        _tracks.value = servers.flatMap { server ->
            if (connections[server.id] == null) return@flatMap emptyList()
            tracks(server, songs.value[server.id].orEmpty())
        }
    }

    private fun cacheFile(id: String) = File(cacheDir, "$id.json")

    private fun cursorFile(id: String) = File(cacheDir, "$id.cursor")

    private fun readCache(id: String): List<SubsonicSong>? = runCatching {
        val file = cacheFile(id)
        if (!file.exists()) null else cacheJson.decodeFromString(songList, file.readText())
    }.getOrNull()

    private fun writeCache(id: String, list: List<SubsonicSong>) {
        cacheDir.mkdirs()
        val temp = File(cacheDir, "$id.json.tmp")
        temp.writeText(cacheJson.encodeToString(songList, list))
        temp.renameTo(cacheFile(id))
    }

    companion object {
        private const val PREFIX = "subsonic:"
        private const val NEW_PREFIX = "trackside-new:"
        private const val DECISION_KEEP_MS = 10 * 60_000L
        private val songList = ListSerializer(SubsonicSong.serializer())
        private val serverList = ListSerializer(SubsonicServer.serializer())
        private val cacheJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

        private fun legacyDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences("trackside", Context.MODE_PRIVATE)
            return prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
        }

        fun parse(raw: String): List<SubsonicServer> =
            runCatching { cacheJson.decodeFromString(serverList, raw) }.getOrDefault(emptyList())

        fun encode(servers: List<SubsonicServer>): String = cacheJson.encodeToString(serverList, servers)

        fun mediaId(serverId: String, songId: String) = "$PREFIX$serverId:$songId"

        /** The library's id for a server's album. */
        fun albumId(serverId: String, albumId: String) = "$PREFIX$serverId:album:$albumId"

        /**
         * The media id of a song a Trackside server plays without it being in your library
         * (a new song on Tracks Radio): never a server song's, so nothing is sent with a song id.
         */
        fun newSongMediaId(serverId: String, identity: String) = "$NEW_PREFIX$serverId:$identity"

        /** The server a new song's media id names, if it's one. */
        fun parseNewSongMediaId(mediaId: String): String? {
            if (!mediaId.startsWith(NEW_PREFIX)) return null
            return mediaId.removePrefix(NEW_PREFIX).substringBefore(':', "").takeIf { it.isNotEmpty() }
        }

        /** The server and song a media id names, if it's a server's. */
        fun parseMediaId(mediaId: String): Pair<String, String>? {
            if (!mediaId.startsWith(PREFIX)) return null
            val rest = mediaId.removePrefix(PREFIX)
            val split = rest.indexOf(':').takeIf { it > 0 } ?: return null
            return rest.substring(0, split) to rest.substring(split + 1)
        }

        /** A server's songs as library tracks, with links to stream them and their covers that carry no credentials. */
        fun tracks(server: SubsonicServer, list: List<SubsonicSong>): List<LibraryTrack> =
            list.filter { it.title.isNotBlank() }.map { song ->
                val artist = song.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                LibraryTrack(
                    song = PlayableSong(
                        mediaId = mediaId(server.id, song.id),
                        uri = ServerLinks.stream(server.id, song.id),
                        title = song.title,
                        artistName = artist,
                        albumTitle = song.album,
                        artworkUri = song.coverArt?.let { ServerLinks.cover(server.id, it) },
                        durationMs = song.duration?.let { it * 1000L },
                        identity = songIdentity(song.title, artist),
                        trackNumber = song.track,
                        year = song.year,
                        genre = song.genre,
                    ),
                    source = LibrarySourceKind.Server,
                    albumKey = albumId(server.id, song.albumId ?: song.album.orEmpty()),
                    albumArtist = song.displayAlbumArtist,
                    disc = song.discNumber,
                )
            }
    }
}

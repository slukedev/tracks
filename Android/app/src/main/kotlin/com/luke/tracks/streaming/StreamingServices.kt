package com.luke.tracks.streaming

import com.luke.tracks.library.trackside.CatalogCapability
import com.luke.tracks.library.trackside.CatalogEvents
import com.luke.tracks.library.trackside.CatalogFeatures
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogLibrary
import com.luke.tracks.library.trackside.TracksideCatalogMatch
import com.luke.tracks.library.trackside.TracksideCatalogMatchRequest
import com.luke.tracks.library.trackside.TracksideCatalogSaved
import com.luke.tracks.library.trackside.TracksideCatalogPage
import com.luke.tracks.library.trackside.TracksideCatalogRecommendations
import com.luke.tracks.library.trackside.TracksideCatalogSearch
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideCatalogServices
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideEvent
import com.luke.tracks.sources.PlayFromOrder
import com.luke.tracks.sources.PlayFromPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** What the streaming services need of one server: its Trackside client in the app, a fake in tests. */
interface CatalogClient {
    suspend fun access(): TracksideClient.CatalogAccess
    suspend fun services(): TracksideCatalogServices
    suspend fun search(query: String, services: List<String>, limit: Int, types: List<String>): TracksideCatalogSearch
    suspend fun item(ref: String, cursor: String?, limit: Int?): TracksideCatalogPage
    suspend fun library(serviceId: String, kind: String, cursor: String?): TracksideCatalogLibrary<TracksideCatalogItem>
    suspend fun libraryTracks(serviceId: String, cursor: String?): TracksideCatalogLibrary<TracksideCatalogTrack>
    suspend fun recommendations(services: List<String>, limit: Int): TracksideCatalogRecommendations
    suspend fun setSaved(ref: String, saved: Boolean): TracksideCatalogSaved = throw TracksideError.NotFound
    suspend fun match(request: TracksideCatalogMatchRequest): TracksideCatalogMatch = throw TracksideError.NotFound
}

class TracksideCatalogClient(private val client: TracksideClient) : CatalogClient {
    override suspend fun access() = client.catalogAccess()
    override suspend fun services() = client.catalogServices()
    override suspend fun search(query: String, services: List<String>, limit: Int, types: List<String>) =
        client.catalogSearch(query, types = types, services = services, limit = limit)
    override suspend fun item(ref: String, cursor: String?, limit: Int?) = client.catalogItem(ref, cursor, limit)
    override suspend fun library(serviceId: String, kind: String, cursor: String?) = client.catalogLibrary(serviceId, kind, cursor)
    override suspend fun libraryTracks(serviceId: String, cursor: String?) = client.catalogLibraryTracks(serviceId, cursor)
    override suspend fun recommendations(services: List<String>, limit: Int) = client.catalogRecommendations(services, limit)
    override suspend fun setSaved(ref: String, saved: Boolean) = client.setCatalogSaved(ref, saved)
    override suspend fun match(request: TracksideCatalogMatchRequest) = client.catalogMatch(request)
}

/** One service on one of your servers. */
data class ServiceKey(val serverId: String, val serviceId: String) {
    /** For saving in a screen's state: the two ids, which never hold a space. */
    val raw: String get() = "$serverId $serviceId"

    companion object {
        fun parse(raw: String?): ServiceKey? = raw?.split(' ')?.takeIf { it.size == 2 }?.let { ServiceKey(it[0], it[1]) }
    }
}

/** A server whose streaming services you may use. */
data class StreamingServer(
    val serverId: String,
    val serverName: String,
    val access: TracksideClient.CatalogAccess,
    val status: Status,
    val services: List<TracksideCatalogService>,
) {
    enum class Status {
        Loading,
        Ok,
        /** The server is reaching its services again. */
        Connecting,
        /** The server can't reach its services right now. */
        Unavailable,
        /** Tracks can't reach the server. */
        Unreachable,
    }

    /** Whether this phone can play them here, through the server's relay. */
    val plays: Boolean get() = access is TracksideClient.CatalogAccess.Play

    val relayCellular: Boolean get() = (access as? TracksideClient.CatalogAccess.Play)?.cellular == true

    /** What else this server can do with its services. */
    val features: CatalogFeatures get() = access.features

    fun service(id: String) = services.firstOrNull { it.id == id }
}

data class StreamingState(val servers: List<StreamingServer> = emptyList()) {
    /** None at all hides every part of the app that's about services. */
    val offered: Boolean get() = servers.isNotEmpty()

    fun server(serverId: String) = servers.firstOrNull { it.serverId == serverId }

    fun service(key: ServiceKey) = server(key.serverId)?.service(key.serviceId)

    /** Every service you could search, each with its server. */
    val services: List<Pair<ServiceKey, TracksideCatalogService>>
        get() = servers.flatMap { server -> server.services.map { ServiceKey(server.serverId, it.id) to it } }

    /** Whether the service can make its own radio here: its server plays here and can ask, and it finds similar songs. */
    fun makesRadio(key: ServiceKey): Boolean {
        val server = server(key.serverId) ?: return false
        val service = server.service(key.serviceId) ?: return false
        return server.plays && server.features.radio && service.isSignedIn && service.can(CatalogCapability.SIMILAR)
    }

    /** Services searched unless one is chosen: signed in, and offering search. */
    val searchable: List<Pair<ServiceKey, TracksideCatalogService>> get() = services.filter { it.second.searches }
}

/**
 * Your servers' streaming services: which there are, kept current from each server's events,
 * and searches, pages and saved libraries asked of them. State changes on one thread of
 * [work], network calls on [io]; [state] is an immutable snapshot for the UI.
 */
class StreamingRepository(
    private val scope: CoroutineScope,
    private val clientFor: suspend (serverId: String) -> CatalogClient?,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val work: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val _state = MutableStateFlow(StreamingState())
    val state: StateFlow<StreamingState> = _state.asStateFlow()

    // Touched only on [work].
    private val records = LinkedHashMap<String, StreamingServer>()
    private val readAt = HashMap<String, Long>()
    private var names = emptyMap<String, String>()

    fun watchServers(servers: Flow<List<Pair<String, String>>>) {
        scope.launch {
            servers.collect { list ->
                withContext(work) {
                    names = list.toMap()
                    records.keys.filterNot { it in names }.toList().forEach { records.remove(it); readAt.remove(it) }
                    names.keys.filterNot { it in records }.forEach { id -> scope.launch(work) { refreshNow(id) } }
                    publish()
                }
            }
        }
    }

    /** A server's events connected: what it missed isn't replayed, so read its services again. */
    fun connected(serverId: String) {
        scope.launch(work) { refreshNow(serverId) }
    }

    fun signedOut(serverId: String) {
        scope.launch(work) {
            records.remove(serverId)
            publish()
        }
    }

    /** Reads the services again where they're older than [maxAgeMs], as a page opening does. */
    fun refreshIfStale(maxAgeMs: Long = 60_000) {
        scope.launch(work) {
            val clock = now().toEpochMilli()
            names.keys.filter { id -> readAt[id]?.let { clock - it > maxAgeMs } != false }.forEach { id -> scope.launch(work) { refreshNow(id) } }
        }
    }

    /** One server event; anything but the services' own is ignored. */
    suspend fun handle(serverId: String, event: TracksideEvent) {
        if (event.type != CatalogEvents.SERVICES) return
        withContext(work) { refreshNow(serverId) }
    }

    private suspend fun refreshNow(serverId: String) {
        val name = names[serverId] ?: return
        val client = clientFor(serverId) ?: return
        val access = try {
            withContext(io) { client.access() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            records[serverId]?.let { records[serverId] = it.copy(status = StreamingServer.Status.Unreachable); publish() }
            return
        }
        if (access == TracksideClient.CatalogAccess.None) {
            // An old server, one without services set up, or an account that may not use them: nothing shows.
            records.remove(serverId)
            publish()
            return
        }
        val before = records[serverId]
        if (before == null) records[serverId] = StreamingServer(serverId, name, access, StreamingServer.Status.Loading, emptyList())
        val list = try {
            withContext(io) { client.services() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: TracksideError.NotFound) {
            records.remove(serverId)
            publish()
            return
        } catch (_: Exception) {
            records[serverId] = (records[serverId] ?: return).copy(access = access, status = StreamingServer.Status.Unreachable)
            publish()
            return
        }
        readAt[serverId] = now().toEpochMilli()
        val status = when (list.state) {
            "ok" -> StreamingServer.Status.Ok
            "connecting" -> StreamingServer.Status.Connecting
            else -> StreamingServer.Status.Unavailable
        }
        records[serverId] = StreamingServer(serverId, name, access, status, list.services)
        publish()
    }

    private fun publish() {
        _state.value = StreamingState(records.values.filter { it.serverId in names }.map { it.copy(serverName = names[it.serverId].orEmpty()) })
    }

    // MARK: Asking

    /**
     * Searches every server's services at once, or only [only], for [types] of item. A server
     * that fails is left out, unless every one does, which throws its error.
     */
    suspend fun search(
        query: String,
        only: ServiceKey? = null,
        limit: Int = 20,
        types: List<String> = TracksideClient.DEFAULT_CATALOG_TYPES,
    ): List<Pair<StreamingServer, TracksideCatalogSearch>> = coroutineScope {
        val servers = state.value.servers.filter { only == null || it.serverId == only.serverId }
            .filter { it.status != StreamingServer.Status.Unavailable && it.services.any { service -> service.searches } }
        val answers = servers.map { server ->
            async(io) {
                val client = clientFor(server.serverId) ?: return@async null
                val ids = if (only != null) listOf(only.serviceId) else server.services.filter { it.searches }.map { it.id }
                runCatching { server to client.search(query, ids, limit, types) }
            }
        }.awaitAll().filterNotNull()
        val found = answers.mapNotNull { it.getOrNull() }
        answers.firstNotNullOfOrNull { it.exceptionOrNull() as? CancellationException }?.let { throw it }
        if (found.isEmpty()) answers.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        found
    }

    suspend fun item(serverId: String, ref: String, cursor: String? = null, limit: Int? = null): TracksideCatalogPage {
        val client = clientFor(serverId) ?: throw TracksideError.NotFound
        return withContext(io) { client.item(ref, cursor, limit) }
    }

    suspend fun library(key: ServiceKey, kind: String, cursor: String? = null): TracksideCatalogLibrary<TracksideCatalogItem> {
        val client = clientFor(key.serverId) ?: throw TracksideError.NotFound
        return withContext(io) { client.library(key.serviceId, kind, cursor) }
    }

    suspend fun libraryTracks(key: ServiceKey, cursor: String? = null): TracksideCatalogLibrary<TracksideCatalogTrack> {
        val client = clientFor(key.serverId) ?: throw TracksideError.NotFound
        return withContext(io) { client.libraryTracks(key.serviceId, cursor) }
    }

    /** Saves [ref] to its service's own library, or takes it out. */
    suspend fun setSaved(serverId: String, ref: String, saved: Boolean): TracksideCatalogSaved {
        val client = clientFor(serverId) ?: throw TracksideError.NotFound
        return withContext(io) { client.setSaved(ref, saved) }
    }

    /** Up to 50 songs matched on one server's services at once. */
    suspend fun match(serverId: String, request: TracksideCatalogMatchRequest): TracksideCatalogMatch {
        val client = clientFor(serverId) ?: throw TracksideError.NotFound
        return withContext(io) { client.match(request) }
    }
}

/** A search's answer from the services, grouped by service, with songs you have folded away. */
data class ServiceResults(
    val sections: List<ServiceSection> = emptyList(),
    /** False when a service didn't answer in time. */
    val complete: Boolean = true,
    /** Services that couldn't be searched, by name: signed out, or not answering. */
    val missing: List<String> = emptyList(),
    /** Library songs (by media id) that a service also has: shown once, as yours. */
    val folded: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = sections.all { it.isEmpty }

    /**
     * The sections in the Play From order, split around your library's: those whose service
     * comes before every source of yours, then the rest. A service the order doesn't name
     * comes after your library; sections of one service keep their order.
     */
    fun aroundLibrary(order: PlayFromOrder): LibraryAndServices {
        val ranks = SearchRanks(order)
        val (before, after) = sections.sortedBy { ranks.of(it.service.placeKind) }.partition { ranks.aboveLibrary(it.service.placeKind) }
        return LibraryAndServices(before, after)
    }
}

/** Where a service's results go among a search's, by [order]: the places here, as Play From shows them. */
class SearchRanks(order: PlayFromOrder) {
    private val rank = order.places.withIndex().associate { (i, place) -> place to i }
    private val library = order.places.indexOfFirst { it is PlayFromPlace.From || it == PlayFromPlace.Downloads }.takeIf { it >= 0 } ?: Int.MAX_VALUE

    fun of(kind: String): Int = rank[PlayFromPlace.Service(kind)] ?: Int.MAX_VALUE

    /** Whether the service of [kind] comes before every source of yours. */
    fun aboveLibrary(kind: String): Boolean = of(kind) < library
}

/** A search's services around your library: [before] it and [after] it, each in the Play From order. */
data class LibraryAndServices(val before: List<ServiceSection>, val after: List<ServiceSection>)

data class ServiceSection(
    val key: ServiceKey,
    val service: TracksideCatalogService,
    val tracks: List<TracksideCatalogTrack> = emptyList(),
    val albums: List<TracksideCatalogItem> = emptyList(),
    val artists: List<TracksideCatalogItem> = emptyList(),
    val playlists: List<TracksideCatalogItem> = emptyList(),
    /** Only when the search asked for them. */
    val stations: List<TracksideCatalogItem> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() && stations.isEmpty()
}

/**
 * The server never mixes your library and the services, so the app does. A service's song
 * that's also in your library (it names the library song) is folded into that song and not
 * listed again; the rest is grouped by service, best answering service first, each in the
 * service's own order. [ServiceResults.aroundLibrary] places them around your library.
 */
object ServiceResultsMerge {
    fun merge(
        answers: List<Pair<StreamingServer, TracksideCatalogSearch>>,
        /** Whether a library song on that server is yours to play here. */
        owned: (serverId: String, songId: String) -> Boolean,
        libraryMediaId: (serverId: String, songId: String) -> String,
    ): ServiceResults {
        val folded = LinkedHashMap<String, MutableList<String>>()
        val sections = ArrayList<ServiceSection>()
        val missing = ArrayList<String>()
        var complete = true
        for ((server, search) in answers) {
            complete = complete && search.complete
            val order = search.services.map { it.id }.ifEmpty { server.services.map { it.id } }
            search.services.filter { it.state != "ok" }.forEach { state -> server.service(state.id)?.name?.let(missing::add) }
            val tracks = search.tracks?.items.orEmpty().filter { track ->
                val songId = track.songId
                if (songId != null && owned(server.serverId, songId)) {
                    val names = folded.getOrPut(libraryMediaId(server.serverId, songId)) { mutableListOf() }
                    server.service(track.serviceId)?.name?.let { if (it !in names) names += it }
                    false
                } else true
            }
            val albums = search.albums?.items.orEmpty()
            val artists = search.artists?.items.orEmpty()
            val playlists = search.playlists?.items.orEmpty()
            val stations = search.radios?.items.orEmpty()
            val ids = (order + tracks.map { it.serviceId } + (albums + artists + playlists + stations).map { it.serviceId }).distinct()
            for (id in ids) {
                val service = server.service(id) ?: continue
                val section = ServiceSection(
                    key = ServiceKey(server.serverId, id),
                    service = service,
                    tracks = tracks.filter { it.serviceId == id }.distinctBy { it.ref },
                    albums = albums.filter { it.serviceId == id }.distinctBy { it.ref },
                    artists = artists.filter { it.serviceId == id }.distinctBy { it.ref },
                    playlists = playlists.filter { it.serviceId == id }.distinctBy { it.ref },
                    stations = stations.filter { it.serviceId == id }.distinctBy { it.ref },
                )
                if (!section.isEmpty) sections += section
            }
        }
        return ServiceResults(sections, complete, missing.distinct(), folded)
    }
}

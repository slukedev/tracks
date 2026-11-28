package com.luke.tracks.streaming

import com.luke.tracks.library.trackside.CatalogCapability
import com.luke.tracks.library.trackside.CatalogEvents
import com.luke.tracks.library.trackside.TracksideCatalogEntry
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideCatalogShelf
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** A service's row of picks on one of your servers, with the service it comes from. */
data class RecommendationShelf(val serverId: String, val service: TracksideCatalogService, val shelf: TracksideCatalogShelf) {
    /** Unique across servers, and the same for as long as Music Assistant keeps the row. */
    val key: String get() = "$serverId:${shelf.id}"
}

/** One server's picks: still coming (keep their place), or the rows it has. A server with none isn't listed. */
sealed interface ServerRecommendations {
    data object Loading : ServerRecommendations
    data class Ready(val shelves: List<RecommendationShelf>) : ServerRecommendations
}

/**
 * The streaming services' own picks for Play, from each server that has them. Nothing is
 * asked until Play first shows ([want]); after that a server's rows are kept for the session
 * and asked again when its services change, when it says they did, or when Play comes back
 * after [maxAgeMs]. A partial answer is shown and asked again once, after the server's own
 * short hold on it ends. A server that can't reach its services, or is busy, keeps what it
 * showed and isn't asked again until it says to.
 */
class CatalogRecommendations(
    private val scope: CoroutineScope,
    private val streaming: StateFlow<StreamingState>,
    private val clientFor: suspend (serverId: String) -> CatalogClient?,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val work: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val maxAgeMs: Long = 20 * 60_000L,
    private val partialRetryMs: Long = 12_000L,
) {
    private val _state = MutableStateFlow<Map<String, ServerRecommendations>>(emptyMap())
    val state: StateFlow<Map<String, ServerRecommendations>> = _state.asStateFlow()

    private class Record(var services: Set<String>) {
        var shelves: List<TracksideCatalogShelf> = emptyList()
        var readAt: Long? = null
        /** Asked at least once for these services, whatever came of it: no more placeholder. */
        var tried = false
        var retriedPartial = false
        /** Set by the server's Retry-After: busy (kept through events), or its services unreachable (until it says they changed). */
        var busyUntil = 0L
        var unavailableUntil = 0L
        var job: Job? = null
        var retry: Job? = null
    }

    // Touched only on [work].
    private val records = HashMap<String, Record>()
    private var wanted = false

    init {
        scope.launch {
            streaming.collect { withContext(work) { follow(it) } }
        }
    }

    /** Play is showing: ask each server whose picks are missing or older than [maxAgeMs]. */
    fun want() {
        scope.launch(work) {
            wanted = true
            follow(streaming.value)
            val clock = now().toEpochMilli()
            records.forEach { (id, record) ->
                if (record.readAt.let { it == null || clock - it > maxAgeMs }) fetch(id, record)
            }
        }
    }

    /** One server event: the services changed, so the server dropped its picks; ask for fresh ones. */
    fun handle(serverId: String, event: TracksideEvent) {
        if (event.type != CatalogEvents.SERVICES) return
        scope.launch(work) {
            val record = records[serverId] ?: return@launch
            record.job?.cancel()
            record.unavailableUntil = 0
            record.retriedPartial = false
            fetch(serverId, record)
        }
    }

    /** Events reconnected: one saying the services changed may have been missed, so the next visit asks again. */
    fun connected(serverId: String) {
        scope.launch(work) { records[serverId]?.readAt = null }
    }

    private fun follow(state: StreamingState) {
        val offered = state.servers.associate { server -> server.serverId to eligible(server) }.filterValues { it.isNotEmpty() }
        records.keys.filterNot { it in offered }.toList().forEach { id -> records.remove(id)?.cancel() }
        if (wanted) offered.forEach { (id, services) ->
            val record = records[id]
            when {
                record == null -> records[id] = Record(services).also { fetch(id, it) }
                record.services != services -> {
                    record.services = services
                    record.tried = false
                    record.retriedPartial = false
                    record.job?.cancel()
                    fetch(id, record)
                }
            }
        }
        publish()
    }

    private fun eligible(server: StreamingServer): Set<String> {
        if (!server.access.recommends || server.status == StreamingServer.Status.Unavailable) return emptySet()
        return server.services.filter { it.isSignedIn && it.can(CatalogCapability.RECOMMENDATIONS) }.map { it.id }.toSet()
    }

    private fun fetch(serverId: String, record: Record) {
        if (record.job?.isActive == true || now().toEpochMilli() < maxOf(record.busyUntil, record.unavailableUntil)) return
        record.retry?.cancel()
        record.job = scope.launch(work) {
            val client = clientFor(serverId)
            if (client == null) {
                record.tried = true
                publish()
                return@launch
            }
            try {
                val answer = withContext(io) { client.recommendations(emptyList(), LIMIT) }
                record.shelves = answer.shelves.filter { it.serviceId in record.services && it.items.isNotEmpty() }
                record.readAt = now().toEpochMilli()
                if (answer.complete) {
                    record.retriedPartial = false
                } else if (!record.retriedPartial) {
                    record.retriedPartial = true
                    record.retry = scope.launch(work) {
                        delay(partialRetryMs)
                        record.retry = null
                        if (records[serverId] === record) fetch(serverId, record)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: TracksideError.NotFound) {
                // No route for this account (or the server lost it): nothing to show until something changes.
                record.shelves = emptyList()
                record.readAt = now().toEpochMilli()
            } catch (error: TracksideError.CatalogUnavailable) {
                record.unavailableUntil = now().toEpochMilli() + (error.retryAfterSeconds ?: 10) * 1_000
            } catch (error: TracksideError.RateLimited) {
                record.busyUntil = now().toEpochMilli() + (error.retryAfterSeconds ?: 30) * 1_000
            } catch (_: Exception) {
                // Offline or refused: what was shown stays; the next visit asks again.
            }
            record.tried = true
            publish()
        }
    }

    private fun Record.cancel() {
        job?.cancel()
        retry?.cancel()
    }

    private fun publish() {
        _state.value = streaming.value.servers.mapNotNull { server ->
            val record = records[server.serverId] ?: return@mapNotNull null
            val shelves = record.shelves.filter { it.serviceId in record.services }
                .mapNotNull { shelf -> server.service(shelf.serviceId)?.let { RecommendationShelf(server.serverId, it, shelf) } }
            when {
                shelves.isNotEmpty() -> server.serverId to ServerRecommendations.Ready(shelves)
                !record.tried -> server.serverId to ServerRecommendations.Loading
                else -> null
            }
        }.toMap()
    }

    companion object {
        /** Items per row: a shelf's worth to scroll through. */
        const val LIMIT = 20
    }
}

/**
 * Which rows Play shows, the way the Apple apps choose them: none that's the service's own
 * recently played or mostly repeats Play's Recently Played, without what Recently Played
 * already holds, and at most [perService] rows from each service.
 */
object RecommendationRows {
    const val PER_SERVICE = 3

    /**
     * [recent] holds Recently Played's albums as [albumKey] makes them, from an album's
     * title and artist.
     */
    fun shown(
        shelves: List<RecommendationShelf>,
        recent: Set<String>,
        albumKey: (title: String, artist: String) -> String,
        perService: Int = PER_SERVICE,
    ): List<RecommendationShelf> {
        fun repeats(entry: TracksideCatalogEntry): Boolean = when (entry) {
            is TracksideCatalogEntry.Item -> entry.item.kind == "album" && albumKey(entry.item.name, entry.item.artist.orEmpty()) in recent
            is TracksideCatalogEntry.Track -> entry.track.album?.let { albumKey(it, entry.track.artist) in recent } == true
        }
        val counts = HashMap<String, Int>()
        return shelves.mapNotNull { row ->
            val items = row.shelf.items
            val kept = items.filterNot(::repeats)
            when {
                row.shelf.title.lowercase().contains("recently played") -> null
                (items.size - kept.size) * 2 >= items.size -> null
                else -> row.copy(shelf = row.shelf.copy(items = kept))
            }
        }.filter { row ->
            val key = "${row.serverId} ${row.service.id}"
            val count = counts.getOrDefault(key, 0)
            counts[key] = count + 1
            count < perService
        }
    }
}

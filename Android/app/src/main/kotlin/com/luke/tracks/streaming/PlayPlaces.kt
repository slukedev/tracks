package com.luke.tracks.streaming

import com.luke.tracks.AppGraph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.PlayResolver
import com.luke.tracks.sources.ServicePlace
import com.luke.tracks.sources.SongCopies
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.Streamer
import com.luke.tracks.sources.WantedSong
import com.luke.tracks.sources.sources
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import uniffi.tracks_engine.songIdentity

/** The service's kind, as Play From keeps it: its logo key ("apple_music"), or its id where it has none. */
val TracksideCatalogService.placeKind: String get() = logoKey?.takeIf { it.isNotBlank() } ?: id

/** Each service on each server that plays here now: signed in, through a relay this device may use. */
fun StreamingState.streamers(): List<Streamer> = servers
    .filter { it.plays && it.status == StreamingServer.Status.Ok }
    .flatMap { server -> server.services.filter { it.isSignedIn }.map { Streamer(server.serverId, it.id, it.placeKind) } }

/** The services as places in Play From: one per kind, named as the first server names it. */
fun StreamingState.servicePlaces(): List<ServicePlace> = servers
    .filter { it.plays }
    .flatMap { server -> server.services.filter { it.isSignedIn }.map { ServicePlace(it.placeKind, it.name.ifBlank { it.placeKind }) } }
    .distinctBy { it.kind }

/**
 * A service's song that is the same song as one asked for, by identity, from one search of that
 * service. Answers are kept for a while, those it doesn't have for less, so the same song isn't
 * asked for again while it's played.
 */
class ServiceMatches(
    private val repository: StreamingRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Answer(val track: TracksideCatalogTrack?, val atMs: Long)

    private val kept = object : LinkedHashMap<String, Answer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>?) = size > KEPT
    }

    suspend fun find(streamer: Streamer, wanted: WantedSong): TracksideCatalogTrack? {
        val key = "${streamer.serverId} ${streamer.serviceId} ${wanted.identity}"
        synchronized(kept) {
            kept[key]?.let { answer ->
                val age = now() - answer.atMs
                if (age < if (answer.track != null) FOUND_MS else MISSING_MS) return answer.track
            }
        }
        val found = repository.search("${wanted.title} ${wanted.artist}", ServiceKey(streamer.serverId, streamer.serviceId), limit = SEARCH_LIMIT)
            .flatMap { it.second.tracks?.items.orEmpty() }
            .filter { it.serviceId == streamer.serviceId && it.canPlay }
            .firstOrNull { songIdentity(it.title, it.artist) == wanted.identity }
        synchronized(kept) { kept[key] = Answer(found, now()) }
        return found
    }

    private companion object {
        const val KEPT = 500
        const val FOUND_MS = 30 * 60_000L
        const val MISSING_MS = 10 * 60_000L
        const val SEARCH_LIMIT = 8
    }
}

private val resolvers = WeakHashMap<AppGraph, PlayResolver>()

/** Where songs play from, for this process: the Play From order across your sources, services and Get. */
val AppGraph.playResolver: PlayResolver
    get() = synchronized(resolvers) {
        resolvers.getOrPut(this) {
            val graph = this
            val copies = SongCopies {
                (graph.library as? CombinedLibrary)?.let { listOf(it.device.tracks.value, it.servers.tracks.value) }.orEmpty()
            }
            (graph.library as? CombinedLibrary)?.let { library ->
                graph.scope.launch(Dispatchers.Default) { combine(library.device.tracks, library.servers.tracks) { _, _ -> }.collect { copies.warm() } }
            }
            PlayResolver(
                order = { graph.sources.playFrom.value },
                getOn = { graph.sources.getOn.value },
                copies = { identity -> copies.of(identity) },
                placeOf = { mediaId -> PlayFromPlace.From(graph.sources.sourceOf(mediaId) ?: SourceId.Unknown(mediaId)) },
                canPlay = { graph.sources.canPlay(it) },
                streamers = { Streaming.of(graph)?.repository?.state?.value?.streamers().orEmpty() },
                find = { streamer, wanted -> Streaming.of(graph)?.matches?.find(streamer, wanted) },
                streamed = CatalogMedia::playable,
            )
        }
    }

/** Tells Play From which services and Get there are, as the servers say. */
internal fun Streaming.offerPlaces(graph: AppGraph) {
    graph.scope.launch {
        combine(repository.state, servers.entries) { state, entries -> state.servicePlaces() to entries.any { it.server.isTrackside } }
            .distinctUntilChanged()
            .collect { (places, gets) -> graph.sources.noteOffered(places, gets) }
    }
}

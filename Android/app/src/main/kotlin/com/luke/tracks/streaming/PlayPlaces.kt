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
import java.io.File
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
 * service. Answers are kept in memory and in a small file (refs and titles only, never audio),
 * those it doesn't have for less time, so a song isn't asked for again each time it plays. A
 * search that fails isn't kept at all: the service is asked again next time.
 */
class ServiceMatches(
    private val search: suspend (query: String, key: ServiceKey) -> List<TracksideCatalogTrack>?,
    private val file: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(repository: StreamingRepository, file: File?) : this(
        search = { query, key ->
            // Songs only: a match is one search's worth of the rate limit, no more.
            val answers = repository.search(query, key, limit = SEARCH_LIMIT, types = listOf("track"))
            if (answers.isEmpty()) null else answers.flatMap { it.second.tracks?.items.orEmpty() }
        },
        file = file,
    )

    @Serializable
    private data class Answer(val track: TracksideCatalogTrack? = null, val atMs: Long)

    private val kept = object : LinkedHashMap<String, Answer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>?) = size > KEPT
    }
    private val loading = Mutex()
    @Volatile private var loaded = file == null
    private val saving = Mutex()

    /** The match kept from before, without asking: null when there's none, or it's too old. */
    suspend fun known(streamer: Streamer, wanted: WantedSong): Answered? {
        load()
        val answer = synchronized(kept) { kept[key(streamer, wanted)] } ?: return null
        val age = now() - answer.atMs
        return if (age < if (answer.track != null) FOUND_MS else MISSING_MS) Answered(answer.track) else null
    }

    suspend fun find(streamer: Streamer, wanted: WantedSong): TracksideCatalogTrack? {
        known(streamer, wanted)?.let { return it.track }
        val tracks = search("${wanted.title} ${wanted.artist}", ServiceKey(streamer.serverId, streamer.serviceId)) ?: return null
        val found = tracks
            .filter { it.serviceId == streamer.serviceId && it.canPlay }
            .firstOrNull { songIdentity(it.title, it.artist) == wanted.identity }
        synchronized(kept) { kept[key(streamer, wanted)] = Answer(found, now()) }
        save()
        return found
    }

    /** A kept answer: [track] is null when the service has no such song. */
    data class Answered(val track: TracksideCatalogTrack?)

    private fun key(streamer: Streamer, wanted: WantedSong) = "${streamer.serverId} ${streamer.serviceId} ${wanted.identity}"

    private suspend fun load() {
        if (loaded) return
        loading.withLock {
            if (loaded) return
            val read = withContext(io) {
                runCatching { file?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(saved, it) } }.getOrNull()
            }.orEmpty()
            val at = now()
            synchronized(kept) {
                read.forEach { (key, answer) ->
                    val fresh = at - answer.atMs < if (answer.track != null) FOUND_MS else MISSING_MS
                    if (fresh) kept.putIfAbsent(key, answer)
                }
            }
            loaded = true
        }
    }

    private suspend fun save() {
        val target = file ?: return
        val copy = synchronized(kept) { LinkedHashMap(kept) }
        saving.withLock {
            withContext(io) {
                runCatching {
                    val temporary = File(target.parentFile, "${target.name}.tmp")
                    temporary.writeText(json.encodeToString(saved, copy))
                    temporary.renameTo(target)
                }
            }
        }
    }

    private companion object {
        const val KEPT = 2_000
        const val FOUND_MS = 7 * 24 * 60 * 60_000L
        const val MISSING_MS = 24 * 60 * 60_000L
        const val SEARCH_LIMIT = 8
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val saved = MapSerializer(String.serializer(), Answer.serializer())
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

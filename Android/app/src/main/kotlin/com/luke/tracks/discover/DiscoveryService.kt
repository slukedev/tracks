package com.luke.tracks.discover

import android.content.Context
import com.luke.tracks.AppGraph
import com.luke.tracks.engine.Engine
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.KeystoreSecretBox
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicClient
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.tracks_engine.CatalogAlbum
import uniffi.tracks_engine.CatalogResults
import uniffi.tracks_engine.CatalogSong
import uniffi.tracks_engine.LidarrSongState
import uniffi.tracks_engine.Period
import uniffi.tracks_engine.SongName
import uniffi.tracks_engine.SourceFilter
import uniffi.tracks_engine.albumOwnership
import uniffi.tracks_engine.catalogBestMatch
import uniffi.tracks_engine.catalogMatchAlbum
import uniffi.tracks_engine.credits
import uniffi.tracks_engine.foldedName
import java.util.WeakHashMap

/**
 * The app's [Discovery]: the iTunes catalog for what exists, the library for what you have,
 * and Octo or Lidarr for getting the rest.
 */
class DiscoveryService(
    context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope = graph.scope,
    transport: WebTransport = OkHttpWebTransport(),
) : Discovery {
    private val library get() = graph.library as? CombinedLibrary
    private val index: LibraryIndex get() = library?.index?.value ?: LibraryIndex.Empty

    val itunes = ItunesSource(transport)
    val lidarr = LidarrService(
        context.getSharedPreferences("lidarr", Context.MODE_PRIVATE),
        KeystoreSecretBox("tracks.lidarr"),
        transport,
    )
    private val serverAccess = object : ServerAccess {
        private val empty = MutableStateFlow<List<ServerEntry>>(emptyList())
        override val entries: StateFlow<List<ServerEntry>> get() = library?.servers?.entries ?: empty
        override fun client(server: SubsonicServer): SubsonicClient? = library?.servers?.client(server)
        override suspend fun sync(server: SubsonicServer) { library?.servers?.sync(server) }
    }
    val octo = OctoService(
        context.getSharedPreferences("octo", Context.MODE_PRIVATE),
        serverAccess,
        scope,
        inLibrary = { index.find(it) != null },
    )

    private val _sources = MutableStateFlow(FetchSources())
    override val sources: StateFlow<FetchSources> = _sources.asStateFlow()
    override val waiting: StateFlow<Set<String>> get() = octo.waiting

    fun start() {
        val library = library ?: return
        scope.launch {
            combine(octo.capabilities, library.servers.entries, lidarr.state) { _, entries, state ->
                FetchSources(
                    octo = entries.filter { it.status is ServerStatus.Ready && octo.capability(it.server.id) == FetchCapability.Fetches }.map { it.server.name },
                    lidarr = state is LidarrState.Connected,
                )
            }.distinctUntilChanged().collect { _sources.value = it }
        }
        scope.launch {
            library.servers.entries.collect { entries ->
                octo.forgetMissing(entries.map { it.server.id }.toSet())
                val query = runCatching { topArtist() }.getOrNull() ?: "love"
                entries.filter { it.status is ServerStatus.Ready }.forEach { scope.launch { octo.probe(it.server, probeQuery = query) } }
            }
        }
        scope.launch { library.index.map { it.snapshot.songs.size }.distinctUntilChanged().collect { octo.settle() } }
        if (lidarr.isSetUp) scope.launch { lidarr.check() }
    }

    /** Probes a server again, from its settings. */
    suspend fun recheck(server: SubsonicServer) {
        val query = runCatching { topArtist() }.getOrNull() ?: "love"
        octo.probe(server, force = true, probeQuery = query)
    }

    // MARK: - The catalog

    override suspend fun search(query: String): CatalogResults = itunes.search(query)

    override suspend fun artist(name: String, catalogId: ULong?): ArtistCatalog? {
        val artist = if (catalogId != null && catalogId != 0uL) catalogArtist(name, catalogId) else itunes.artistNamed(name) ?: return null
        return itunes.artist(artist)
    }

    override suspend fun album(id: ULong): AlbumCatalog? = itunes.album(id)

    override suspend fun findAlbum(title: String, artist: String): CatalogAlbum? {
        val keys = artistKeys(artist).toSet()
        val theirs = itunes.searchAlbums("$title $artist").filter { album -> artistKeys(album.artistName).any(keys::contains) }
        return catalogMatchAlbum(title, theirs.map { it.title })?.let { theirs[it.toInt()] }
    }

    // MARK: - What you have

    /** The library's songs and albums by folded artist, made again when the library changes. */
    private data class ByArtist(val songs: Map<String, List<PlayableSong>>, val albums: Map<String, List<Pair<String, Int>>>)

    private val byArtist = WeakHashMap<LibraryIndex, ByArtist>()

    private fun lookups(): ByArtist {
        val index = index
        synchronized(byArtist) {
            byArtist[index]?.let { return it }
            val built = ByArtist(
                songs = index.snapshot.songs.groupBy { foldedName(it.artistName) },
                albums = index.snapshot.albums.groupBy({ foldedName(it.artistName) }, { it.title to it.songs.size }),
            )
            byArtist[index] = built
            return built
        }
    }

    private fun artistKeys(name: String): List<String> =
        (listOf(foldedName(name)) + credits(name, "").map { it.identity }).distinct()

    override fun owned(song: CatalogSong): PlayableSong? {
        index.find(song.identity)?.let { return it }
        val theirs = artistKeys(song.artistName).flatMap { lookups().songs[it].orEmpty() }
        if (theirs.isEmpty()) return null
        return catalogBestMatch(song.title, song.artistName, theirs.map { SongName(it.title, it.artistName) })?.let { theirs[it.toInt()] }
    }

    override fun holding(album: CatalogAlbum): AlbumHolding {
        val theirs = artistKeys(album.artistName).flatMap { lookups().albums[it].orEmpty() }
        val match = catalogMatchAlbum(album.title, theirs.map { it.first })?.let { theirs[it.toInt()] }
        val owned = match?.second ?: 0
        val capped = album.trackCount?.let { minOf(owned.toUInt(), it) } ?: owned.toUInt()
        return AlbumHolding(albumOwnership(capped, album.trackCount), capped.toInt())
    }

    override suspend fun availability(song: CatalogSong): Availability {
        owned(song)?.let { return Availability.Owned(it) }
        if (octo.isWaiting(song.identity)) return Availability.Waiting
        octo.find(song.title, song.artistName)?.let { return Availability.OnServer(it) }
        if (lidarr.isSetUp) {
            return when (val state = lidarr.songState(song.title, song.artistName)) {
                is LidarrSongState.Collected -> Availability.InLidarr(state.album)
                LidarrSongState.Wanted -> Availability.ComingFromLidarr
                LidarrSongState.Missing, null ->
                    if (lidarr.isConnected && !song.albumTitle.isNullOrBlank()) Availability.Requestable else Availability.Unavailable
            }
        }
        return Availability.Unavailable
    }

    // MARK: - Getting it

    override suspend fun get(song: CatalogSong): String = when (val availability = availability(song)) {
        is Availability.Owned -> "“${song.title}” is in your music"
        is Availability.OnServer -> octo.get(availability.find)
        Availability.Waiting, Availability.ComingFromLidarr -> "“${song.title}” is on its way"
        is Availability.InLidarr -> bringInFromLidarr(song)
        Availability.Requestable -> lidarr.requestAlbum(song.albumTitle.orEmpty(), song.artistName)
        Availability.Checking, Availability.Unavailable -> "Nothing here can get “${song.title}”"
    }

    /** Lidarr has the file: a sync of your servers, which play Lidarr's library, brings it in. */
    private suspend fun bringInFromLidarr(song: CatalogSong): String {
        library?.servers?.syncAll()
        return if (owned(song) != null) "“${song.title}” is in your music" else "Your server doesn't have it yet"
    }

    override suspend fun getAlbum(album: CatalogAlbum): String {
        if (lidarr.isConnected) return lidarr.requestAlbum(album.title, album.artistName)
        if (octo.fetchingServers().isEmpty()) return "Nothing here can get “${album.title}”"
        val songs = itunes.album(album.id)?.songs.orEmpty().filter { owned(it) == null }
        val asked = coroutineScope {
            songs.map { song ->
                async { octo.find(song.title, song.artistName)?.let { octo.get(it); 1 } ?: 0 }
            }.awaitAll().sum()
        }
        return if (asked == 0) "Couldn't find “${album.title}” to get" else "Getting $asked ${if (asked == 1) "song" else "songs"} from “${album.title}”"
    }

    override suspend fun followArtist(name: String): String? = if (lidarr.isConnected) lidarr.follow(name) else null

    /** The artist you play most, or have most songs by: what a server's probe searches for. */
    private suspend fun topArtist(): String? {
        val now = System.currentTimeMillis()
        val played = graph.engine.read {
            artistChart(Period(now - 120 * DAY, null), null, SourceFilter.ALL, 1u).rows
                .ifEmpty { artistChart(Engine.allTime, null, SourceFilter.ALL, 1u).rows }
        }.firstOrNull()?.artist?.name
        return played ?: index.snapshot.artists.maxByOrNull { it.songCount }?.name
    }

    private companion object {
        const val DAY = 24 * 3600_000L
    }
}

private val instances = WeakHashMap<AppGraph, DiscoveryService>()

/** Music you don't have, for this process. */
val AppGraph.discovery: DiscoveryService
    get() = synchronized(instances) {
        instances.getOrPut(this) { DiscoveryService(context, this).also { it.start() } }
    }

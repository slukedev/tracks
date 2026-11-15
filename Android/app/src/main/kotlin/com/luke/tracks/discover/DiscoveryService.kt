package com.luke.tracks.discover

import android.content.Context
import android.util.Log
import com.luke.tracks.AppGraph
import com.luke.tracks.BuildConfig
import com.luke.tracks.engine.Engine
import com.luke.tracks.lastfm.LastFm
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import uniffi.tracks_engine.AlbumOwnership
import uniffi.tracks_engine.CatalogAlbum
import uniffi.tracks_engine.CatalogResults
import uniffi.tracks_engine.CatalogSong
import uniffi.tracks_engine.LastFmClient
import uniffi.tracks_engine.RelatedArtists
import uniffi.tracks_engine.suggestionsRank
import uniffi.tracks_engine.suggestionsSimilarArtists
import uniffi.tracks_engine.LidarrSongState
import uniffi.tracks_engine.Period
import uniffi.tracks_engine.SongName
import uniffi.tracks_engine.SourceFilter
import uniffi.tracks_engine.SuggestionCandidate
import uniffi.tracks_engine.SuggestionSeed
import uniffi.tracks_engine.albumOwnership
import uniffi.tracks_engine.catalogAlbumKey
import uniffi.tracks_engine.catalogBestMatch
import uniffi.tracks_engine.catalogMatchAlbum
import uniffi.tracks_engine.credits
import uniffi.tracks_engine.foldedName
import uniffi.tracks_engine.lastfmParseSimilarTracks
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
                val query = runCatching { topArtists(1).firstOrNull()?.artist }.getOrNull() ?: "love"
                entries.filter { it.status is ServerStatus.Ready }.forEach { scope.launch { octo.probe(it.server, probeQuery = query) } }
            }
        }
        scope.launch { library.index.map { it.snapshot.songs.size }.distinctUntilChanged().collect { octo.settle() } }
        if (lidarr.isSetUp) scope.launch { lidarr.check() }
    }

    /** Probes a server again, from its settings. */
    suspend fun recheck(server: SubsonicServer) {
        val query = runCatching { topArtists(1).firstOrNull()?.artist }.getOrNull() ?: "love"
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

    // MARK: - Suggestions

    /** Answers for suggestions, kept on disk: asked again a few times a day, used offline. */
    private val suggestionWeb = CachedTransport(transport, java.io.File(context.cacheDir, "suggestions"), maxAgeMs = SUGGESTIONS_KEEP_MS)
    private val suggestionCatalog = ItunesSource(suggestionWeb)
    private val deezer = DeezerSource(suggestionWeb)
    private val notInterestedIn = context.getSharedPreferences("suggestions", Context.MODE_PRIVATE)

    private data class Made(val at: Long, val seeds: Set<String>, val value: Suggestions)

    @Volatile private var made: Made? = null
    private val suggesting = Mutex()

    override val lastSuggestions: Suggestions? get() = made?.value

    override suspend fun suggestions(force: Boolean): Suggestions = suggesting.withLock {
        val now = System.currentTimeMillis()
        val seeds = runCatching { topArtists(SEEDS) }.getOrDefault(emptyList())
        val key = seeds.map { foldedName(it.artist) }.toSet()
        made?.let { if (!force && it.seeds == key && now - it.at < SUGGESTIONS_KEEP_MS && !it.value.isEmpty) return it.value }
        val value = runCatching { makeSuggestions(seeds) }.onFailure { Log.w("Tracks", "Suggestions failed", it) }.getOrNull()
            ?: return made?.value ?: Suggestions()
        // An empty answer is likely the network: kept only until the next visit.
        if (!value.isEmpty) made = Made(now, key, value)
        value
    }

    override suspend fun notInterested(artist: String) {
        val hidden = hiddenArtists() + foldedName(artist)
        notInterestedIn.edit().putStringSet(HIDDEN, hidden).apply()
        suggesting.withLock { made = made?.let { it.copy(value = it.value.without(artist)) } }
    }

    private fun hiddenArtists(): Set<String> = notInterestedIn.getStringSet(HIDDEN, emptySet()).orEmpty()

    private suspend fun topArtists(limit: Int): List<SuggestionSeed> {
        val now = System.currentTimeMillis()
        val rows = graph.engine.read {
            artistChart(Period(now - 120 * DAY, null), null, SourceFilter.ALL, limit.toUInt()).rows
                .ifEmpty { artistChart(Engine.allTime, null, SourceFilter.ALL, limit.toUInt()).rows }
        }
        if (rows.isNotEmpty()) return rows.map { SuggestionSeed(it.artist.name, it.artist.plays.toDouble()) }
        // No history yet: the artists you have the most songs by.
        return index.snapshot.artists.sortedByDescending { it.songCount }.take(limit).map { SuggestionSeed(it.name, it.songCount.toDouble()) }
    }

    /**
     * Your top artists and the artists like them (Deezer, and Last.fm where this build has a
     * key), then each one's catalog page: their songs and new albums you don't have, ranked by
     * the engine.
     */
    private suspend fun makeSuggestions(seeds: List<SuggestionSeed>): Suggestions = coroutineScope {
        if (seeds.isEmpty()) return@coroutineScope Suggestions()
        val gate = Semaphore(4)
        // Your artists' pages don't wait on who's like them.
        val mine = seeds.map { seed -> async { gate.withPermit { runCatching { pageOf(seed.artist) }.getOrNull() } } }
        val asked = seeds.take(RELATED_FROM)
        val fromDeezer = asked.map { seed ->
            async { gate.withPermit { seed.artist to runCatching { deezer.related(seed.artist) }.getOrDefault(emptyList()) } }
        }
        val fromLastFm = async { runCatching { lastFmSimilar(asked) }.getOrDefault(emptyList()) }
        val deezerLists = fromDeezer.awaitAll()
        val pictures = deezerLists.flatMap { it.second }.associate { foldedName(it.name) to it.pictureUrl }
        val related = deezerLists.map { (from, artists) -> RelatedArtists(from, artists.map { it.name }) } + fromLastFm.await()
        val excluded = playedArtists() + hiddenArtists()
        val similar = suggestionsSimilarArtists(seeds, related, excluded.toList(), SIMILAR.toUInt())

        val theirs = similar.take(SIMILAR_PAGES).map { artist -> async { gate.withPermit { runCatching { pageOf(artist.name) }.getOrNull() } } }
        val myPages = mine.awaitAll().filterNotNull()
        val pages = myPages + theirs.awaitAll().filterNotNull()
        if (pages.isEmpty() && similar.isEmpty()) error("Nothing answered")

        val ranking = seeds + similar.map { SuggestionSeed(it.name, it.weight) }
        val songs = pages.flatMap { page -> page.songs.mapIndexed { rank, song -> song to rank } }
        val songOrder = suggestionsRank(
            ranking,
            songs.map { (song, rank) -> SuggestionCandidate(song.identity, song.artistName, rank.toUInt(), owned(song) != null, false) },
            3u, SONGS.toUInt(),
        )
        val since = java.time.LocalDate.now().minusMonths(6).toString()
        val releases = myPages.flatMap { it.albums }
            .filter { (it.released ?: "") >= since }
            .distinctBy { catalogAlbumKey(it.title) + "\u001F" + foldedName(it.artistName) }
            .map { SuggestedAlbum(it, holding(it)) }
            .filter { it.holding.ownership != AlbumOwnership.OWNED }
            .sortedByDescending { it.album.released }
            .take(12)
        Suggestions(
            songs = songOrder.map { songs[it.toInt()].first },
            artists = similar.map { SuggestedArtist(it.name, pictures[foldedName(it.name)], it.like) },
            releases = releases,
        )
    }

    /** An artist's catalog page, through the suggestions' own cache. */
    private suspend fun pageOf(name: String): ArtistCatalog? = suggestionCatalog.artistNamed(name)?.let { suggestionCatalog.artist(it) }

    /** Everyone you've played: suggested artists are ones you haven't. */
    private suspend fun playedArtists(): Set<String> =
        graph.engine.read { artistChart(Engine.allTime, null, SourceFilter.ALL, 400u).rows.map { foldedName(it.artist.name) } }.toSet()

    /** Artists of Last.fm's songs like your top ones, as a list per artist, when this build has a key. */
    private suspend fun lastFmSimilar(seeds: List<SuggestionSeed>): List<RelatedArtists> {
        if (BuildConfig.LASTFM_API_KEY.isBlank()) return emptyList()
        val client = LastFmClient(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_SECRET, OkHttpWebTransport.USER_AGENT)
        val now = System.currentTimeMillis()
        val chart = graph.engine.read { songChart(Period(now - 365 * DAY, null), null, SourceFilter.ALL, 60u).rows.map { it.song } }
        return seeds.mapNotNull { seed ->
            val top = chart.firstOrNull { foldedName(it.artistName) == foldedName(seed.artist) } ?: return@mapNotNull null
            val similar = runCatching { lastfmParseSimilarTracks(LastFm.send(client.similarTracks(top.title, top.artistName, 20u))) }.getOrDefault(emptyList())
            RelatedArtists(seed.artist, similar.map { it.artistName }.distinctBy(::foldedName))
        }
    }

    private companion object {
        const val DAY = 24 * 3600_000L
        /** Suggestions are worked out again at most this often, unless your top artists change. */
        const val SUGGESTIONS_KEEP_MS = 8 * 3600_000L
        const val SEEDS = 8
        /** Of your top artists, how many to look up artists like. */
        const val RELATED_FROM = 4
        const val SIMILAR = 12
        /** Of the artists like yours, how many to suggest songs by. */
        const val SIMILAR_PAGES = 6
        const val SONGS = 40
        const val HIDDEN = "notInterested"
    }
}

private val instances = WeakHashMap<AppGraph, DiscoveryService>()

/** Music you don't have, for this process. */
val AppGraph.discovery: DiscoveryService
    get() = synchronized(instances) {
        instances.getOrPut(this) { DiscoveryService(context, this).also { it.start() } }
    }

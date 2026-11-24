package com.luke.tracks.discover

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import com.luke.tracks.AppGraph
import com.luke.tracks.engine.Engine
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.MusicServerClient
import com.luke.tracks.library.SubsonicClient
import com.luke.tracks.net.Http
import com.luke.tracks.net.ServerLinks
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.library.SubsonicSong
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uniffi.tracks_engine.FoundCandidate
import uniffi.tracks_engine.FoundSong
import uniffi.tracks_engine.ForYouShelf
import uniffi.tracks_engine.ForYouShelves
import uniffi.tracks_engine.Period
import uniffi.tracks_engine.ServerForYou
import uniffi.tracks_engine.SourceFilter
import uniffi.tracks_engine.dailySeed
import uniffi.tracks_engine.foldedName
import uniffi.tracks_engine.serverArtistStart
import uniffi.tracks_engine.serverBlend
import uniffi.tracks_engine.serverForYouFromJson
import uniffi.tracks_engine.serverSeeds
import uniffi.tracks_engine.songIdentity
import java.io.File
import java.time.LocalDate
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** What one server picks for you: its For You shelves, and whether it's still looking. */
data class ServerForYouState(val shelves: ForYouShelves, val isFilling: Boolean = false)

/** A server's own shelves of albums. */
data class ServerAlbums(
    val mostPlayed: List<ServerAlbum> = emptyList(),
    val random: List<ServerAlbum> = emptyList(),
    val loadedAt: Long = 0,
)

/** An album on a server, opened as the library's album. */
data class ServerAlbum(val libraryId: String, val title: String, val artist: String, val coverUrl: String?)

/** A song a server found, and which server. */
data class ServerSong(val serverId: String, val song: FoundSong)

/**
 * What your music servers find for you, as on iPhone: songs by the artists you play and
 * artists like them (Picked for you, Suggested songs and Keep exploring), each server's
 * most played and random albums, and songs you've never played. Needs a Subsonic server;
 * with Octo in front of it, most of what's found can be fetched to keep.
 */
interface ServerPicks {
    /** Servers answering, whose shelves show. */
    val servers: StateFlow<List<SubsonicServer>>
    val forYou: StateFlow<Map<String, ServerForYouState>>
    val albums: StateFlow<Map<String, ServerAlbums>>
    /** Keep exploring: songs you've never played, woven with new ones by artists new to you. */
    val exploring: StateFlow<List<ServerSong>>
    val canExploreMore: StateFlow<Boolean>
    /** Counts answers to going on, even ones that found nothing, so a shelf asks again. */
    val answers: StateFlow<Int>

    suspend fun load(serverId: String, force: Boolean = false)
    suspend fun loadAlbums(serverId: String)
    suspend fun loadMore(serverId: String, shelf: ForYouShelf, want: Int)
    suspend fun exploreMore()
    suspend fun shuffleRandom(serverId: String)

    /** The song to play: the library's where it's synced, the server's stream otherwise. */
    fun playable(song: ServerSong): PlayableSong?
    fun coverUrl(song: ServerSong): String?
    /** Whether the server fetches songs it doesn't have (Octo): only then is Add offered. */
    fun canKeep(serverId: String): Boolean
    /** Has the server fetch a song to keep, and says how it went. */
    suspend fun keep(song: ServerSong): String
}

/** No servers: what screenshots and previews start from. */
object NoServerPicks : ServerPicks {
    override val servers: StateFlow<List<SubsonicServer>> = MutableStateFlow(emptyList())
    override val forYou: StateFlow<Map<String, ServerForYouState>> = MutableStateFlow(emptyMap())
    override val albums: StateFlow<Map<String, ServerAlbums>> = MutableStateFlow(emptyMap())
    override val exploring: StateFlow<List<ServerSong>> = MutableStateFlow(emptyList())
    override val canExploreMore: StateFlow<Boolean> = MutableStateFlow(false)
    override val answers: StateFlow<Int> = MutableStateFlow(0)
    override suspend fun load(serverId: String, force: Boolean) = Unit
    override suspend fun loadAlbums(serverId: String) = Unit
    override suspend fun loadMore(serverId: String, shelf: ForYouShelf, want: Int) = Unit
    override suspend fun exploreMore() = Unit
    override suspend fun shuffleRandom(serverId: String) = Unit
    override fun playable(song: ServerSong): PlayableSong? = null
    override fun coverUrl(song: ServerSong): String? = null
    override fun canKeep(serverId: String) = false
    override suspend fun keep(song: ServerSong) = ""
}

/** The app's [ServerPicks]; screenshots provide their own. */
val LocalServerPicks = staticCompositionLocalOf<ServerPicks?> { null }

/**
 * The app's [ServerPicks]. The engine decides (which artists to go on from, what's left out,
 * which shelf a song goes to, when picks are made again); this asks the servers and keeps
 * what they found on disk, so the last picks show straight away after a launch.
 */
class ServerPicksService(
    context: Context,
    private val graph: AppGraph,
    private val library: CombinedLibrary,
    private val octo: OctoService,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : ServerPicks {
    private val dir = File(context.cacheDir, "for-you")
    private val serverLibrary: ServerLibrary get() = library.servers
    private val index: LibraryIndex get() = library.index.value

    private val _servers = MutableStateFlow<List<SubsonicServer>>(emptyList())
    override val servers: StateFlow<List<SubsonicServer>> = _servers.asStateFlow()
    private val _forYou = MutableStateFlow<Map<String, ServerForYouState>>(emptyMap())
    override val forYou: StateFlow<Map<String, ServerForYouState>> = _forYou.asStateFlow()
    private val _albums = MutableStateFlow<Map<String, ServerAlbums>>(emptyMap())
    override val albums: StateFlow<Map<String, ServerAlbums>> = _albums.asStateFlow()
    private val _exploring = MutableStateFlow<List<ServerSong>>(emptyList())
    override val exploring: StateFlow<List<ServerSong>> = _exploring.asStateFlow()
    private val _canExploreMore = MutableStateFlow(true)
    override val canExploreMore: StateFlow<Boolean> = _canExploreMore.asStateFlow()
    private val _answers = MutableStateFlow(0)
    override val answers: StateFlow<Int> = _answers.asStateFlow()

    /** Each server's walk; the published shelves are its snapshots. */
    private val walks = java.util.concurrent.ConcurrentHashMap<String, ServerForYou>()
    /** One walk step at a time per server: a first load, or a shelf going on. */
    private val locks = HashMap<String, Mutex>()
    private val budgets = HashMap<String, RequestBudget>()
    private val exploringMutex = Mutex()
    private val exploringWaiting = ArrayDeque<ServerSong>()
    private val exploringSeen = HashSet<String>()
    private var randomFound = emptyList<ServerSong>()
    private var failuresInARow = 0

    fun start() {
        scope.launch {
            serverLibrary.entries.collect { entries ->
                _servers.value = entries.filter { it.status is ServerStatus.Ready }.map { it.server }
                withContext(Dispatchers.IO) {
                    for (entry in entries) if (!walks.containsKey(entry.server.id)) restore(entry.server.id)
                }
            }
        }
    }

    // MARK: - For You

    override suspend fun load(serverId: String, force: Boolean) {
        val server = server(serverId) ?: return
        lock(serverId).withLock {
            if (!force && walks[serverId]?.stands(PICKED, now()) == true) return
            val seeds = serverSeeds(PICKED, playedArtists(), libraryArtists(), QUEUE_START, dailySeed("for-you-seeds"))
            val fresh = ServerForYou(seeds, PICKED, now())
            if (seeds.isEmpty()) return publish(serverId, fresh, filling = false)
            // What's on show stays until the new picks are ready; an empty shelf fills as they come.
            val replaces = walks[serverId]?.shelves()?.let { it.picks.isNotEmpty() || it.suggested.isNotEmpty() } == true
            if (!replaces) publish(serverId, fresh, filling = true)
            // A column for each shelf at a time, Suggested songs first as it leads the page.
            for (shelf in listOf(ForYouShelf.SUGGESTED, ForYouShelf.PICKS, ForYouShelf.SUGGESTED, ForYouShelf.PICKS)) {
                goOn(fresh, shelf, server, publishing = !replaces, want = COLUMN)
            }
            publish(serverId, fresh, filling = false)
        }
    }

    override suspend fun loadMore(serverId: String, shelf: ForYouShelf, want: Int) {
        val server = server(serverId) ?: return
        lock(serverId).withLock {
            val walk = walks[serverId] ?: return
            if (!walk.canGoOn()) return
            publish(serverId, walk, filling = true)
            goOn(walk, shelf, server, publishing = true, want = want.coerceAtLeast(1))
            publish(serverId, walk, filling = false)
        }
        _answers.update { it + 1 }
    }

    /**
     * Adds up to [want] songs to a shelf, each shown as its cover is there: from songs already
     * found, going on from the next artist only when those run out, from at most two artists a
     * call so songs without covers can't keep it asking.
     */
    private suspend fun goOn(walk: ServerForYou, shelf: ForYouShelf, server: SubsonicServer, publishing: Boolean, want: Int) {
        var shown = 0
        var goneOn = 0
        while (shown < want) {
            if (walk.waiting(shelf) == 0u) {
                if (goneOn >= 2 || !walk.hasQueue()) break
                findMore(walk, server)
                goneOn++
                continue
            }
            val batch = walk.takeWaiting(shelf, (want - shown).toUInt())
            val kept = withCovers(server, batch)
            walk.place(shelf, kept)
            shown += kept.size
            if (publishing && kept.isNotEmpty()) publish(server.id, walk, filling = true)
        }
    }

    /** Goes on from the next artist: a few of their songs, then songs like the first, as the server's budget allows. */
    private suspend fun findMore(walk: ServerForYou, server: SubsonicServer) {
        val client = serverLibrary.client(server) ?: return
        val artist = walk.nextArtist() ?: return
        budget(server.id).take()
        val found = runCatching { client.search(artist, artists = 0, albums = 0, songs = ARTIST_SONGS).songs }
            .onFailure { com.luke.tracks.net.Logs.w("For You search failed", it) }.getOrNull()?.map { it.found() } ?: return
        val start = serverArtistStart(artist, found)
        val like = start.start?.let { index ->
            runCatching { client.similarSongs(found[index.toInt()].id, 15) }.getOrDefault(emptyList()).map { it.found() }
        }.orEmpty()
        val songs = start.theirs.map { found[it.toInt()] } + like
        val heard = heard(songs.map { it.identity } + yoursBy(artist).map { it.identity })
        val known = knownArtists()
        val candidates = songs.map { song ->
            FoundCandidate(song, heard = song.identity in heard, knownArtist = foldedName(song.artist) in known, owned = index.find(song.identity) != null)
        }
        val yours = yoursBy(artist).filter { it.identity !in heard }
        walk.add(candidates, yours, dailySeed("for-you-$artist"))
    }

    /** Your songs by an artist, as songs found. */
    private fun yoursBy(artist: String): List<FoundSong> {
        val key = foldedName(artist)
        return index.snapshot.songs.filter { foldedName(it.artistName) == key }.map {
            FoundSong(it.mediaId, it.identity, it.title, it.artistName, it.albumTitle, null, it.durationMs?.toULong())
        }
    }

    // MARK: - Covers

    /**
     * The songs with covers to show, in order, a few checked at once. A server like Octo sends
     * a stand-in for a cover it couldn't find, and a song shown with it looks unfinished.
     */
    private suspend fun withCovers(server: SubsonicServer, songs: List<FoundSong>): List<FoundSong> = coroutineScope {
        val gate = Semaphore(4)
        songs.map { song ->
            async {
                // Your own songs, from this phone or synced, always have a cover to show.
                if (index.find(song.identity) != null && song.cover == null) return@async song
                val cover = song.cover ?: return@async null
                val url = ServerLinks.coverUrl(server.id, cover, 120)
                gate.withPermit { if (hasCover(url)) song else null }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun hasCover(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            coverHttp.newCall(Request.Builder().url(url).build()).execute().use { answer ->
                answer.isSuccessful && answer.body.contentType()?.type == "image" && answer.body.bytes().size > 256
            }
        }.getOrDefault(false)
    }

    // MARK: - Shelves of albums

    override suspend fun loadAlbums(serverId: String) {
        _albums.value[serverId]?.let { if (now() - it.loadedAt < ALBUMS_KEEP_MS) return }
        val server = server(serverId) ?: return
        val client = serverLibrary.client(server) ?: return
        coroutineScope {
            val frequent = async { albums(server, client, SubsonicClient.AlbumListType.Frequent) }
            val random = async { albums(server, client, SubsonicClient.AlbumListType.Random) }
            val made = ServerAlbums(frequent.await(), random.await(), now())
            _albums.update { it + (serverId to made) }
        }
    }

    override suspend fun shuffleRandom(serverId: String) {
        val server = server(serverId) ?: return
        val client = serverLibrary.client(server) ?: return
        val random = albums(server, client, SubsonicClient.AlbumListType.Random)
        _albums.update { it + (serverId to (it[serverId] ?: ServerAlbums()).copy(random = random)) }
    }

    /** The server's albums of a kind that are in the library, to open there. */
    private suspend fun albums(server: SubsonicServer, client: MusicServerClient, type: SubsonicClient.AlbumListType): List<ServerAlbum> =
        runCatching { client.albums(type, size = 15) }.getOrDefault(emptyList()).mapNotNull { album ->
            val id = ServerLibrary.albumId(server.id, album.id)
            index.album(id)?.let { ServerAlbum(id, it.title, it.artistName, album.coverArt?.let { cover -> ServerLinks.coverUrl(server.id, cover) }) }
        }

    // MARK: - Keep exploring

    /** A page more of songs you've never played, from a server picked at random, and new ones by artists new to you. */
    override suspend fun exploreMore() {
        val servers = _servers.value
        if (servers.isEmpty()) return
        for (server in servers) loadMore(server.id, ForYouShelf.FURTHER, COLUMN)
        exploringMutex.withLock {
            if (exploringWaiting.size < PAGE) {
                val server = servers.random()
                val client = serverLibrary.client(server)
                val songs = client?.let { runCatching { it.randomSongs(40) }.getOrNull() }
                if (songs == null) {
                    failuresInARow++
                } else {
                    val found = songs.map { it.found() }
                    val heard = heard(found.map { it.identity })
                    val fresh = found.filter { it.identity !in heard && exploringSeen.add(it.identity) }
                    exploringWaiting.addAll(fresh.map { ServerSong(server.id, it) })
                    failuresInARow = if (fresh.isEmpty()) failuresInARow + 1 else 0
                }
            }
            repeat(minOf(PAGE, exploringWaiting.size)) { randomFound = randomFound + exploringWaiting.removeFirst() }
            publishExploring()
            _canExploreMore.value = failuresInARow < 3
        }
    }

    /** Songs you have and haven't played, woven with ones by artists new to you that aren't on the shelves. */
    private fun publishExploring() {
        val further = _forYou.value.flatMap { (serverId, state) -> state.shelves.further.map { ServerSong(serverId, it) } }
        val byId = (randomFound + further).associateBy { it.serverId + "\u0000" + it.song.id }
        val woven = serverBlend(randomFound.map { it.song.keyed(it.serverId) }, further.map { it.song.keyed(it.serverId) })
        _exploring.value = woven.mapNotNull { byId[it.id] }.distinctBy { it.song.identity }
    }

    private fun FoundSong.keyed(serverId: String) = copy(id = serverId + "\u0000" + id)

    // MARK: - Playing and keeping

    override fun playable(song: ServerSong): PlayableSong? {
        index.find(song.song.identity)?.let { return it }
        val server = server(song.serverId) ?: return null
        val s = song.song
        return PlayableSong(
            mediaId = ServerLibrary.mediaId(server.id, s.id),
            uri = ServerLinks.stream(server.id, s.id),
            title = s.title,
            artistName = s.artist,
            albumTitle = s.album,
            artworkUri = s.cover?.let { ServerLinks.cover(server.id, it) },
            durationMs = s.durationMs?.toLong(),
            identity = s.identity,
        )
    }

    override fun coverUrl(song: ServerSong): String? {
        val cover = song.song.cover ?: return index.find(song.song.identity)?.artworkUri?.toString()
        val server = server(song.serverId) ?: return null
        return ServerLinks.coverUrl(server.id, cover, 300)
    }

    override fun canKeep(serverId: String) = octo.capability(serverId) == FetchCapability.Fetches

    override suspend fun keep(song: ServerSong): String {
        val server = server(song.serverId) ?: return "That server isn't set up anymore"
        val s = song.song
        return octo.get(ServerFind(server.id, server.name, SubsonicSong(id = s.id, title = s.title, artist = s.artist, album = s.album, isExternal = true)))
    }

    // MARK: - What you know

    /** The artists you play, most first: what the picks start from. */
    private suspend fun playedArtists(): List<String> {
        val nowMs = now()
        return graph.engine.read {
            artistChart(Period(nowMs - 120 * DAY, null), null, SourceFilter.ALL, 40u).rows
                .ifEmpty { artistChart(Engine.allTime, null, SourceFilter.ALL, 40u).rows }
                .map { it.artist.name }
        }
    }

    private fun libraryArtists(): List<String> =
        index.snapshot.artists.sortedByDescending { it.songCount }.map { it.name }

    /** Artists you play or have, folded: their songs are Picked for you's, everyone else's Suggested songs'. */
    private suspend fun knownArtists(): Set<String> =
        graph.engine.read { artistChart(Engine.allTime, null, SourceFilter.ALL, 2000u).rows.map { foldedName(it.artist.name) } }.toSet() +
            index.snapshot.artists.map { foldedName(it.name) }

    /** Which of these songs you've played. */
    private suspend fun heard(identities: List<String>): Set<String> =
        graph.engine.read { identities.filter { song(it) != null } }.toSet()

    // MARK: - Keeping state

    private fun publish(serverId: String, walk: ServerForYou, filling: Boolean) {
        walks[serverId] = walk
        _forYou.update { it + (serverId to ServerForYouState(walk.shelves(), filling)) }
        publishExploring()
        val json = walk.toJson()
        scope.launch(Dispatchers.IO) { runCatching { dir.mkdirs(); File(dir, "$serverId.json").writeText(json) } }
    }

    private fun restore(serverId: String) {
        val walk = runCatching { File(dir, "$serverId.json").readText() }.getOrNull()?.let(::serverForYouFromJson) ?: return
        walks[serverId] = walk
        _forYou.update { it + (serverId to ServerForYouState(walk.shelves())) }
    }

    private fun server(id: String): SubsonicServer? = serverLibrary.entries.value.firstOrNull { it.server.id == id }?.server

    private fun lock(serverId: String) = synchronized(locks) { locks.getOrPut(serverId) { Mutex() } }

    private fun budget(serverId: String) = synchronized(budgets) { budgets.getOrPut(serverId) { RequestBudget(3, 8_000) } }

    private fun dailySeed(salt: String): ULong {
        val today = LocalDate.now()
        return dailySeed(today.year.toShort(), today.monthValue.toByte(), today.dayOfMonth.toByte(), salt)
    }

    private fun SubsonicSong.found(): FoundSong {
        val artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
        return FoundSong(id, songIdentity(title, artist), title, artist, album, coverArt, duration?.let { it.toULong() * 1000uL })
    }

    private companion object {
        const val DAY = 24 * 3600_000L
        /** Picks name nobody: there's no artist picker on Android yet. */
        val PICKED = emptyList<String>()
        const val QUEUE_START = 40u
        const val COLUMN = 4
        const val PAGE = 8
        const val ARTIST_SONGS = 20
        const val ALBUMS_KEEP_MS = 15 * 60_000L
        val coverHttp: OkHttpClient by lazy {
            Http.media.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        }
    }
}

/** A few requests at once, then one every [intervalMs], however fast the shelves are scrolled. */
class RequestBudget(private val capacity: Int, private val intervalMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var tokens = capacity.toDouble()
    private var updatedAt = now()

    suspend fun take() {
        while (true) {
            val wait = mutex.withLock {
                val at = now()
                tokens = minOf(capacity.toDouble(), tokens + (at - updatedAt).toDouble() / intervalMs)
                updatedAt = at
                if (tokens >= 1) { tokens -= 1; 0L } else ((1 - tokens) * intervalMs).toLong()
            }
            if (wait == 0L) return
            delay(wait)
        }
    }
}

private val picks = WeakHashMap<AppGraph, ServerPicks>()

/** What your servers find for you, for this process; nothing without a server library. */
val AppGraph.serverPicks: ServerPicks
    get() = synchronized(picks) {
        picks.getOrPut(this) {
            val library = library as? CombinedLibrary ?: return@getOrPut NoServerPicks
            ServerPicksService(context, this, library, discovery.octo, scope).also { it.start() }
        }
    }

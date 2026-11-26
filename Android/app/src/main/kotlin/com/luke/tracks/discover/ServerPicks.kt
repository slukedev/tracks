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
import com.luke.tracks.net.Logs
import com.luke.tracks.net.ServerLinks
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.library.SubsonicSong
import com.luke.tracks.library.TracksideMusicServer
import com.luke.tracks.library.trackside.TracksideAcquisition
import com.luke.tracks.library.trackside.TracksideAcquisitionRequest
import com.luke.tracks.library.trackside.TracksideCandidate
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideExternalPlayRequest
import com.luke.tracks.library.trackside.TracksideSuggestion
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.SongsToGet
import com.luke.tracks.playback.ToPlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import uniffi.tracks_engine.CatalogAlbum
import uniffi.tracks_engine.CatalogAlbumKind
import uniffi.tracks_engine.FoundCandidate
import uniffi.tracks_engine.Ownership
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
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * What one server picks for you: its For You shelves, whether it's still looking, and why it
 * found nothing when every request to it failed.
 */
data class ServerForYouState(
    val shelves: ForYouShelves,
    val isFilling: Boolean = false,
    val problem: String? = null,
    /** For Trackside, whether Suggested songs can hold new songs to add; null for other servers or not known yet. */
    val newSongs: NewSongs? = null,
)

/**
 * Whether a Trackside server gets songs it doesn't have, as `/me` says with
 * `features.acquisition`: only then can Suggested songs hold new songs, with an Add button.
 */
enum class NewSongs {
    /** It gets songs for you: new ones join Suggested songs, among ones it has. */
    Offered,
    /** Requests are off on the server, or have no source to get songs from. */
    TurnedOff,
    /** Your account isn't allowed to request songs. */
    NotAllowed,
}

/** How a song the server doesn't have is named on a shelf: never a server's id, as those are letters and digits. */
const val NEW_SONG_ID = "new:"

/** A song suggested that the server doesn't have yet: it can be added, not played. */
val FoundSong.isNew: Boolean get() = id.startsWith(NEW_SONG_ID)

/** A suggested song the server doesn't have, as a shelf shows it: no cover, an id from its identity so a shelf's keys stay unique. */
fun newSong(title: String, artist: String, album: String? = null, durationMs: Long? = null, cover: String? = null): FoundSong {
    val identity = songIdentity(title, artist)
    return FoundSong(NEW_SONG_ID + identity, identity, title, artist, album?.takeIf { it.isNotBlank() }, cover?.takeIf { it.isNotEmpty() }, durationMs?.takeIf { it > 0 }?.toULong())
}

/**
 * [own] with [new] songs among them: one new song after each [every] of the server's own, so
 * two new ones come together only once its own run out.
 */
fun <T> sprinkle(own: List<T>, new: List<T>, every: Int): List<T> {
    if (new.isEmpty()) return own
    val result = ArrayList<T>(own.size + new.size)
    var n = 0
    own.forEachIndexed { i, song ->
        result += song
        if ((i + 1) % every.coerceAtLeast(1) == 0 && n < new.size) result += new[n++]
    }
    while (n < new.size) result += new[n++]
    return result
}

/** What to say when the account's request limit is used up, with when it resets in your time. */
fun quotaMessage(serverName: String, resetsAt: Instant?, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val said = "You've reached $serverName's limit on requested songs."
    val at = resetsAt?.atZone(zone) ?: return "$said Try again later."
    // atZone, not LocalDate.ofInstant, which needs Android 14.
    val today = now.atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(at)
    val day = when (at.toLocalDate()) {
        today -> "at $time"
        today.plusDays(1) -> "tomorrow at $time"
        else -> "on ${DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(at)} at $time"
    }
    return "$said It resets $day."
}

/**
 * How a Trackside server plays a song it doesn't have, as `/me` says in
 * `features.acquisition`: without keeping it (`external_play`, section 15.6), by getting it
 * and playing while it downloads (`instant_play`, section 15.5), or not at all.
 */
enum class NewPlay { External, Instant, None }

/** Both ways a Trackside server can play a song it doesn't have, from `/me`. */
data class NewPlays(val instant: Boolean = false, val external: Boolean = false) {
    /** Tracks Radio's way: without keeping the song where it can, as it only passes through. */
    val forRadio: NewPlay get() = when {
        external -> NewPlay.External
        instant -> NewPlay.Instant
        else -> NewPlay.None
    }
}

/** A server's own shelf of albums, Something different, and albums it could get for you. */
data class ServerAlbums(
    val random: List<ServerAlbum> = emptyList(),
    /** Catalog albums by artists the server suggests that it doesn't have: only where it gets songs. */
    val toGet: List<CatalogAlbum> = emptyList(),
    /** The artists [toGet] was found from, so new suggestions find albums again. */
    val toGetFrom: Set<String> = emptySet(),
    val loadedAt: Long = 0,
)

/** An album on a server, opened as the library's album. */
data class ServerAlbum(val libraryId: String, val title: String, val artist: String, val coverUrl: String?)

/** A song a server found, and which server. */
data class ServerSong(val serverId: String, val song: FoundSong)

/**
 * What your music servers find for you, as on iPhone: songs by the artists you play and
 * artists like them (Picked for you, Suggested songs and Keep exploring), each server's
 * most played and random albums, and songs you've never played. Trackside starts them with
 * its own picks; a Subsonic server with its similar songs, and with Octo in front of it, most
 * of what's found can be fetched to keep.
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
    /** A cover for a new song the server doesn't have yet, looked up in Apple's catalog as search does. */
    suspend fun newCover(song: ServerSong): String? = null
    /**
     * Whether the server can get [song] for you: any song not in your music where it fetches
     * (Octo), and new songs it suggests where Trackside gets songs. Only then is Add offered.
     */
    fun canKeep(song: ServerSong): Boolean
    /** Has the server fetch a song to keep, and says how it went. */
    suspend fun keep(song: ServerSong): String

    /** Asks a Trackside server that gets songs for several new ones, such as an album's, and says how it went. */
    suspend fun keepAll(serverId: String, songs: List<FoundSong>, name: String): String = ""

    /**
     * New songs your Trackside servers suggest that Tracks Radio can play: none you have, and
     * only from servers that can play a song they don't have (`external_play` or `instant_play`).
     */
    suspend fun newForRadio(): List<ServerSong> = emptyList()

    /**
     * A new song made playable: streamed without keeping it where the server offers that
     * (section 15.6), else got with instant play (section 15.5). Null when it can't be played.
     */
    suspend fun playNew(song: ServerSong): PlayableSong? = null

    /**
     * A new song queued to be got when it's reached ([com.luke.tracks.playback.GetAsReached]):
     * where the server gets songs for you, or is getting this one already. Null otherwise.
     */
    fun toGet(song: ServerSong, cover: String? = null): PlayableSong? = null

    /** What tapping a song on a shelf queues: the song to play, or a new one to get as it's reached. */
    fun queueable(song: ServerSong, cover: String? = null): PlayableSong? = playable(song) ?: toGet(song, cover)

    /**
     * Gets a song queued by [toGet] so it plays: streamed as soon as the server can (instant
     * play, or playing without keeping while it's requested to keep), else requested and waited
     * on until it's in your library. A request already under way is waited on, not made again.
     * [status] hears how it's going.
     */
    suspend fun getToPlay(song: PlayableSong, status: (String) -> Unit = {}): ToPlay = ToPlay.Failed("Couldn't get “${song.title}”.")
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
    override fun canKeep(song: ServerSong) = false
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
    private val newBudget: () -> RequestBudget = { RequestBudget(3, 8_000) },
    /** How long a first load goes on finding songs before the shelves show what it has. */
    private val loadLimitMs: Long = 40_000,
    /** How often a song requested from Trackside is checked on, at first. */
    private val followEveryMs: Long = 5_000,
    /** How often a song being got to play is checked on, and how long it's waited for before the next song plays. */
    private val playCheckMs: Long = 2_000,
    private val playWaitMs: Long = 5 * 60_000,
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
    /** Each server's first load, run here so a shelf scrolled away or a tab left doesn't end it. */
    private val loads = java.util.concurrent.ConcurrentHashMap<String, Deferred<Unit>>()
    /** Whether each Trackside server gets songs, and when that was asked. */
    private val offers = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, NewSongs>>()
    /** What each server's picks were made with: picks made before it got songs are made again. */
    private val madeWith = java.util.concurrent.ConcurrentHashMap<String, NewSongs>()
    /** New songs found for each walk and not shown yet: they join Suggested songs among the server's own. */
    private val pendingNew = WeakHashMap<ServerForYou, NewFound>()
    /** How each Trackside server can play a new song without it in your library, from `/me`. */
    private val playModes = java.util.concurrent.ConcurrentHashMap<String, NewPlays>()
    /** Requests being followed, by their id. */
    private val following = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    /**
     * The request behind each new song played from its download as it came in (instant play),
     * by identity: if that stream stops working, the request is waited on and the library's
     * copy played, rather than asking for the song again.
     */
    private val streamedFrom = java.util.concurrent.ConcurrentHashMap<String, OctoService.Request>()
    /** Songs arriving (a sync bringing them in), by request id, so a request is synced for once. */
    private val arriving = java.util.concurrent.ConcurrentHashMap<String, Deferred<Unit>>()

    fun start() {
        scope.launch {
            var before = emptyMap<String, ServerStatus>()
            serverLibrary.entries.collect { entries ->
                // A server still syncing its songs picks for you already: picks don't need its library.
                _servers.value = entries.filter { it.status is ServerStatus.Ready || it.status is ServerStatus.Syncing }.map { it.server }
                val statuses = entries.associate { it.server.id to it.status }
                if (statuses.mapValues { it.value.javaClass } != before.mapValues { it.value.javaClass }) {
                    Logs.i("For You: servers ${entries.joinToString { "${it.server.name} ${describe(it.status)}" }.ifEmpty { "none" }}")
                }
                withContext(Dispatchers.IO) {
                    for (entry in entries) if (!walks.containsKey(entry.server.id)) restore(entry.server.id)
                }
                // Its songs just arrived: picks made without them start again.
                for (entry in entries) {
                    if (entry.status !is ServerStatus.Ready || before[entry.server.id] !is ServerStatus.Syncing) continue
                    val shown = walks[entry.server.id]?.shelves()
                    if (shown == null || (shown.picks.isEmpty() && shown.suggested.isEmpty())) scope.launch { load(entry.server.id, force = true) }
                }
                before = statuses
            }
        }
        // Requests from before a restart are followed again until their songs arrive.
        scope.launch {
            val requests = octo.requests()
            if (requests.isEmpty()) return@launch
            val entries = withTimeoutOrNull(30_000) { serverLibrary.entries.first { it.isNotEmpty() } }.orEmpty()
            for (request in requests) {
                val server = entries.firstOrNull { it.server.id == request.serverId }?.server
                if (server == null) octo.noLongerWaiting(request.identity) else follow(server, request)
            }
        }
    }

    private fun describe(status: ServerStatus) = when (status) {
        ServerStatus.Syncing -> "syncing"
        is ServerStatus.Ready -> "ready with ${status.songCount} songs"
        is ServerStatus.Failed -> "unreachable, so its shelves are hidden"
        ServerStatus.NeedsPassword -> "signed out, so its shelves are hidden"
        is ServerStatus.NeedsTrust -> "waiting for its certificate to be trusted, so its shelves are hidden"
    }

    // MARK: - For You

    /**
     * Makes a server's picks, or joins the load already under way. The load runs in the app's
     * scope, ends within [loadLimitMs] and always leaves the shelves settled: songs, nothing
     * found, or why the server couldn't be asked.
     */
    override suspend fun load(serverId: String, force: Boolean) {
        val job = synchronized(loads) {
            loads[serverId]?.takeIf { it.isActive } ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    loadNow(serverId, force)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // Throwable, not Exception: an error in the picks must never take the app down.
                    Logs.e("For You: picks stopped", error)
                    com.luke.tracks.net.CrashLog.describe(Thread.currentThread().name, error).lines().forEach { Logs.e("For You: $it") }
                    // Never left loading: an empty walk settles the shelves.
                    if (_forYou.value[serverId]?.isFilling != false) publish(serverId, walks[serverId] ?: ServerForYou(emptyList(), PICKED, now()), filling = false)
                }
            }.also { job ->
                loads[serverId] = job
                job.invokeOnCompletion { loads.remove(serverId, job) }
                job.start()
            }
        }
        job.await()
    }

    private suspend fun loadNow(serverId: String, force: Boolean) {
        val server = server(serverId) ?: return Logs.i("For You: that server isn't set up anymore")
        val offer = newSongs(server, null)
        lock(serverId).withLock {
            val previous = walks[serverId]
            // Picks made before the server got songs are made again, so new ones join them.
            val remake = offer == NewSongs.Offered && madeWith[serverId] != NewSongs.Offered
            if (!force && !remake && previous?.stands(PICKED, now()) == true) {
                publishOffer(serverId)
                return Logs.i("For You: ${server.name}: picks from earlier still stand")
            }
            offer?.let { madeWith[serverId] = it }
            val started = now()
            val played = playedArtists()
            val library = libraryArtists()
            val seeds = serverSeeds(PICKED, played, library, QUEUE_START, dailySeed("for-you-seeds"))
            val fresh = ServerForYou(seeds, PICKED, now())
            val tally = Tally()
            // What's on show stays until the new picks are ready; an empty shelf fills as they come.
            val replaces = previous?.shelves()?.let { it.picks.isNotEmpty() || it.suggested.isNotEmpty() } == true
            if (!replaces) publish(serverId, fresh, filling = true)
            Logs.i("For You: ${server.name}: picking from ${seeds.size} artists (${played.size} you play, ${library.size} in your library)")
            try {
                withTimeoutOrNull(loadLimitMs) {
                    val client = serverLibrary.client(server)
                    if (client == null) Logs.i("For You: ${server.name}: its sign-in can't be opened on this phone")
                    // A server that picks for you (Trackside) starts the shelves; the walk goes on from there.
                    val serverPicked = client?.let { serverPicks(it, server, tally) }
                    val picked = serverPicked?.songs
                    if (offer == NewSongs.Offered && client is TracksideMusicServer) {
                        // New songs the server suggests and could get: from its picks, and from Get More once you've listened.
                        addNew(fresh, serverPicked?.external.orEmpty().map { newSong(it.title, it.artist, cover = it.artworkId) })
                        if (played.isNotEmpty()) {
                            // A list from what's stored, like picks: not counted against the budget.
                            val more = ask(tally, "For You: ${server.name} couldn't suggest more") { client.client.getMore(GET_MORE).external }
                            addNew(fresh, more.orEmpty().map { newSong(it.title, it.artist, cover = it.artworkId) })
                        }
                        Logs.i("For You: ${server.name} suggested ${pending(fresh).size} new songs it can get")
                    }
                    if (seeds.isEmpty() && picked.isNullOrEmpty() && pending(fresh).isEmpty()) {
                        Logs.i("For You: ${server.name}: nothing to start from yet: no plays, no songs synced and no picks from the server")
                        return@withTimeoutOrNull
                    }
                    if (!picked.isNullOrEmpty()) {
                        val found = candidates(picked, server).let { list ->
                            // The server chose these for you, so both shelves get some: songs by
                            // artists you play are Picked for you, and the rest take turns, the first
                            // to Suggested songs, so that shelf mixes what the server has with the new
                            // songs it can get and Picked for you is never empty for want of plays.
                            var turn = 0
                            list.map { candidate -> if (candidate.knownArtist) candidate else candidate.copy(knownArtist = turn++ % 2 == 1) }
                        }
                        fresh.add(found, emptyList(), dailySeed("for-you-server"))
                    }
                    // A column for each shelf at a time, Suggested songs first as it leads the page.
                    // While both are empty it goes on as long as there's more to find; once one has
                    // songs, the other fills as it's scrolled or tried again, so a shelf that stays
                    // empty (no artists you play on the server, say) doesn't hold every load up.
                    var round = 0
                    while (!tally.unreachable && (round < FIRST_ROUNDS || (round < MAX_ROUNDS && fresh.canGoOn() && fresh.shelves().let { it.picks.isEmpty() && it.suggested.isEmpty() }))) {
                        goOn(fresh, if (round % 2 == 0) ForYouShelf.SUGGESTED else ForYouShelf.PICKS, server, publishing = !replaces, want = COLUMN, tally = tally)
                        round++
                    }
                    // Nothing more to go on from: the new songs still waiting show all the same, but
                    // for a third of them kept for Keep exploring.
                    if (!fresh.canGoOn()) pending(fresh).size.let { waiting -> showNew(fresh, waiting - waiting / 3) }
                } ?: Logs.i("For You: ${server.name}: stopped looking after ${loadLimitMs / 1000} s; showing what was found")
            } finally {
                withContext(NonCancellable) {
                    val shelves = fresh.shelves()
                    val foundNothing = shelves.picks.isEmpty() && shelves.suggested.isEmpty()
                    val kept = if (replaces && foundNothing) previous!! else fresh
                    publish(serverId, kept, filling = false, problem = tally.problem(server).takeIf { foundNothing && kept === fresh })
                    Logs.i(
                        "For You: ${server.name}: ${shelves.picks.size} picked for you, ${shelves.suggested.size} suggested, " +
                            "${tally.asked} requests (${tally.failed} failed) in ${(now() - started) / 100 / 10.0} s" +
                            (if (fresh.canGoOn()) ", more to find as you scroll" else ", nothing more to find") +
                            (if (kept !== fresh) "; earlier picks stay" else ""),
                    )
                }
            }
        }
    }

    override suspend fun loadMore(serverId: String, shelf: ForYouShelf, want: Int) {
        // Called from the shelves themselves, so nothing here may reach the UI as a crash.
        try {
            loadMoreNow(serverId, shelf, want)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logs.e("For You: couldn't find more", error)
            com.luke.tracks.net.CrashLog.describe(Thread.currentThread().name, error).lines().forEach { Logs.e("For You: $it") }
        }
    }

    private suspend fun loadMoreNow(serverId: String, shelf: ForYouShelf, want: Int) {
        val server = server(serverId) ?: return
        lock(serverId).withLock {
            val walk = walks[serverId] ?: return
            if (!walk.canGoOn() && pending(walk).isEmpty()) return
            val tally = Tally()
            publish(serverId, walk, filling = true)
            try {
                goOn(walk, shelf, server, publishing = true, want = want.coerceAtLeast(1), tally = tally)
            } finally {
                withContext(NonCancellable) {
                    val shelves = walk.shelves()
                    val empty = (if (shelf == ForYouShelf.PICKS) shelves.picks else if (shelf == ForYouShelf.SUGGESTED) shelves.suggested else shelves.further).isEmpty()
                    publish(serverId, walk, filling = false, problem = tally.problem(server).takeIf { empty })
                }
            }
        }
        _answers.update { it + 1 }
    }

    /** Requests asked of a server, and how many failed: all of them failing is worth saying. */
    private class Tally {
        @Volatile var asked = 0
        @Volatile var failed = 0

        /** Every one of the first few failed: the server isn't there, so asking more only waits longer. */
        val unreachable get() = asked >= 3 && failed == asked

        fun problem(server: SubsonicServer): String? =
            if (asked > 0 && failed == asked) "Couldn't reach ${server.name}. Check your connection and try again." else null
    }

    /** [block], counted in [tally]; a failure is logged and comes back as null. Cancelling still cancels. */
    private suspend fun <T> ask(tally: Tally?, what: String, block: suspend () -> T): T? {
        tally?.let { it.asked++ }
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            tally?.let { it.failed++ }
            Logs.w(what, error)
            null
        }
    }

    /**
     * Adds up to [want] songs to a shelf, each shown as its cover is there: from songs already
     * found, going on from the next artist only when those run out, from at most two artists a
     * call so songs without covers can't keep it asking.
     */
    private suspend fun goOn(walk: ServerForYou, shelf: ForYouShelf, server: SubsonicServer, publishing: Boolean, want: Int, tally: Tally? = null): Int {
        // On Suggested songs, new songs the server can get come two for each of its own.
        if (shelf == ForYouShelf.SUGGESTED) return goOnSuggested(walk, server, publishing, want, tally)
        // Picked for you has one new song in four, Keep exploring one in two, where the server gets songs.
        val every = if (shelf == ForYouShelf.PICKS) 3 else 1
        val newWanted = if (pending(walk).isEmpty()) 0 else if (shelf == ForYouShelf.PICKS) (want + 3) / 4 else (want + 1) / 2
        val ownWanted = want - minOf(newWanted, pending(walk).size)
        var own = 0
        var new = 0
        var goneOn = 0
        // Each batch is placed as it's found, so the walk never finds a song again that's waiting to be shown.
        fun place(theirs: List<FoundSong>, newCount: Int) {
            val newOnes = takeNew(walk, newCount.coerceAtMost(newWanted - new).coerceAtLeast(0))
            val mixed = sprinkle(theirs, newOnes, every)
            own += theirs.size
            new += newOnes.size
            if (mixed.isEmpty()) return
            walk.place(shelf, mixed)
            if (publishing) publish(server.id, walk, filling = true)
        }
        while (own < ownWanted) {
            if (walk.waiting(shelf) == 0u) {
                if (goneOn >= 2 || !walk.hasQueue()) break
                findMore(walk, server, tally)
                goneOn++
                continue
            }
            val batch = walk.takeWaiting(shelf, (ownWanted - own).toUInt())
            val kept = withCovers(server, batch)
            if (kept.size < batch.size) Logs.i("For You: ${server.name}: ${batch.size - kept.size} of ${batch.size} songs left out for want of a cover")
            place(kept, (own + kept.size) / every - new)
        }
        // The rest of the new songs wanted; once there's nothing more of the server's own to go on
        // from, new songs fill Keep exploring's page, which goes on for as long as you scroll.
        if (newWanted > 0) {
            val rest = if (walk.canGoOn() || shelf == ForYouShelf.PICKS) newWanted - new else want - own - new
            if (rest > 0) {
                val newOnes = takeNew(walk, rest)
                new += newOnes.size
                if (newOnes.isNotEmpty()) {
                    walk.place(shelf, newOnes)
                    if (publishing) publish(server.id, walk, filling = true)
                }
            }
        }
        return own + new
    }

    /**
     * Suggested songs: the server's own songs by artists new to you, and where Trackside gets
     * songs, new ones mixed in, two for each of its own. Songs are found first and placed
     * together, so nothing is held back from the walk while it goes on.
     */
    private suspend fun goOnSuggested(walk: ServerForYou, server: SubsonicServer, publishing: Boolean, want: Int, tally: Tally?): Int {
        val shelf = ForYouShelf.SUGGESTED
        val newWanted = if (pending(walk).isEmpty()) 0 else (want * 2 + 2) / 3
        var goneOn = 0
        while (walk.waiting(shelf) < (want - newWanted).toUInt() && goneOn < 2 && walk.hasQueue()) {
            findMore(walk, server, tally)
            goneOn++
        }
        val batch = walk.takeWaiting(shelf, (want - minOf(newWanted, pending(walk).size)).coerceAtLeast(0).toUInt())
        val theirs = withCovers(server, batch)
        if (theirs.size < batch.size) Logs.i("For You: ${server.name}: ${batch.size - theirs.size} of ${batch.size} songs left out for want of a cover")
        // New songs fill the rest of the column; ones left over go to Picked for you and Keep exploring too.
        val newOnes = takeNew(walk, if (walk.canGoOn()) want - theirs.size else maxOf(newWanted, theirs.size * 2).coerceAtMost(want - theirs.size))
        val mixed = if (newOnes.isEmpty()) theirs else serverBlend(theirs, newOnes)
        if (mixed.isNotEmpty()) {
            walk.place(shelf, mixed)
            if (publishing) publish(server.id, walk, filling = true)
        }
        return mixed.size
    }

    // MARK: - New songs

    /** New songs found for a walk: those waiting to be shown, and every one it has met, so none comes twice. */
    private class NewFound {
        val waiting = ArrayDeque<FoundSong>()
        val met = HashSet<String>()
    }

    private fun newFound(walk: ServerForYou): NewFound = synchronized(pendingNew) { pendingNew.getOrPut(walk) { NewFound() } }

    private fun pending(walk: ServerForYou): List<FoundSong> = newFound(walk).let { synchronized(it) { it.waiting.toList() } }

    private fun takeNew(walk: ServerForYou, count: Int): List<FoundSong> {
        val found = newFound(walk)
        return synchronized(found) { List(minOf(count, found.waiting.size)) { found.waiting.removeFirst() } }
    }

    /** Places up to [count] waiting new songs straight away: two on Suggested songs for each one on Picked for you. */
    private fun showNew(walk: ServerForYou, count: Int) {
        val (picks, suggested) = takeNew(walk, count).withIndex().partition { it.index % 3 == 2 }
        if (suggested.isNotEmpty()) walk.place(ForYouShelf.SUGGESTED, suggested.map { it.value })
        if (picks.isNotEmpty()) walk.place(ForYouShelf.PICKS, picks.map { it.value })
    }

    /**
     * Keeps new songs the server suggests for Suggested songs: each once, none you have or
     * have played, none already on the shelves.
     */
    private suspend fun addNew(walk: ServerForYou, songs: List<FoundSong>) {
        if (songs.isEmpty()) return
        val heard = heard(songs.map { it.identity })
        val shelves = walk.shelves()
        val found = newFound(walk)
        synchronized(found) {
            val shown = (shelves.picks + shelves.suggested + shelves.further).mapTo(HashSet()) { it.identity }
            found.waiting.addAll(songs.filter { it.identity !in heard && it.identity !in shown && index.find(it.identity) == null && found.met.add(it.identity) })
        }
    }

    /**
     * Whether a Trackside server gets songs for you, from `/me`, asked at most every few
     * minutes; null for any other server, or when it couldn't be asked and wasn't known.
     */
    private suspend fun newSongs(server: SubsonicServer, tally: Tally?): NewSongs? {
        if (!server.isTrackside) return null
        val known = offers[server.id]
        if (known != null && now() - known.first < OFFER_KEEP_MS) return known.second
        val client = serverLibrary.tracksideClient(server.id) ?: return known?.second
        val me = ask(tally, "For You: ${server.name} couldn't say whether it gets songs") { client.me() } ?: return known?.second
        val found = when {
            me.offersAcquisition -> NewSongs.Offered
            !me.canRequest -> NewSongs.NotAllowed
            else -> NewSongs.TurnedOff
        }
        if (known?.second != found) {
            Logs.i(
                "For You: ${server.name} " + when (found) {
                    NewSongs.Offered -> "gets songs for you, so Suggested songs has new ones"
                    NewSongs.NotAllowed -> "doesn't let your account request songs, so Suggested songs has only songs it has"
                    NewSongs.TurnedOff -> "doesn't get songs (no features.acquisition in /me), so Suggested songs has only songs it has"
                },
            )
        }
        offers[server.id] = now() to found
        val acquisition = me.features?.acquisition
        playModes[server.id] = NewPlays(instant = acquisition?.instantPlay == true, external = acquisition?.externalPlay == true)
        runCatching { graph.discovery.noteTracksideGets(server.id, server.name, found == NewSongs.Offered) }
        return found
    }

    private fun publishOffer(serverId: String) {
        val offer = offers[serverId]?.second
        _forYou.update { all -> all[serverId]?.takeIf { it.newSongs != offer }?.let { all + (serverId to it.copy(newSongs = offer)) } ?: all }
    }

    /** Goes on from the next artist: a few of their songs, then songs like the first, as the server's budget allows. */
    private suspend fun findMore(walk: ServerForYou, server: SubsonicServer, tally: Tally?) {
        val client = serverLibrary.client(server) ?: return
        val artist = walk.nextArtist() ?: return
        budget(server.id).take()
        val found = ask(tally, "For You: ${server.name} couldn't search") { client.search(artist, artists = 0, albums = 0, songs = ARTIST_SONGS).songs }
            ?.map { it.found() } ?: return
        // Where Trackside gets songs, songs like these it doesn't have are new songs to suggest.
        val trackside = (client as? TracksideMusicServer)?.client?.takeIf { offers[server.id]?.second == NewSongs.Offered }
        if (found.isEmpty() && trackside != null) {
            // None of the artist's songs on the server: ones it could get stand in.
            budget(server.id).take()
            val could = ask(tally, "For You: ${server.name} couldn't look for songs to get") { trackside.searchExternal(artist, ARTIST_SONGS) }
            addNew(walk, could?.candidates.orEmpty().map { newSong(it.title, it.artist, it.album, it.durationMs, it.artworkId) })
        }
        val start = serverArtistStart(artist, found)
        val like = start.start?.let { index ->
            ask(tally, "For You: ${server.name} couldn't find similar songs") {
                val id = found[index.toInt()].id
                if (trackside == null) client.similarSongs(id, 15).map { it.found() }
                else trackside.similar(id, 15).let { answer ->
                    addNew(walk, answer.external.orEmpty().map { newSong(it.title, it.artist, cover = it.artworkId) })
                    answer.songs.filter { it.missing != true }.map { it.subsonic.found() }
                }
            }.orEmpty()
        }.orEmpty()
        val songs = start.theirs.map { found[it.toInt()] } + like
        val yours = yoursBy(artist)
        val candidates = candidates(songs, server, also = yours.map { it.identity })
        val heard = candidates.filter { it.heard }.map { it.song.identity }.toSet() + heard(yours.map { it.identity })
        walk.add(candidates, yours.filter { it.identity !in heard }, dailySeed("for-you-$artist"))
    }

    /** What a server picked: its songs, and with Trackside, ones it suggests and doesn't have. */
    private class Picked(val songs: List<FoundSong>, val external: List<TracksideSuggestion>)

    /** The server's own picks, or null when it doesn't make any; a failure says why in the log. */
    private suspend fun serverPicks(client: MusicServerClient, server: SubsonicServer, tally: Tally): Picked? {
        budget(server.id).take()
        val picked = ask(tally, "For You: ${server.name} couldn't pick songs") {
            if (client is TracksideMusicServer) {
                client.suggestedPicks(SERVER_PICKS)?.let { answer -> Picked(answer.songs.filter { it.missing != true }.map { it.subsonic.found() }, answer.external.orEmpty()) }
            } else {
                client.picks(SERVER_PICKS)?.let { songs -> Picked(songs.map { it.found() }, emptyList()) }
            }
        }
        when {
            picked == null && server.isTrackside -> Logs.i("For You: ${server.name} made no picks (a Trackside from before picks, or the request failed); going on from similar songs")
            picked == null -> Logs.i("For You: ${server.name} doesn't pick songs itself; going on from similar songs")
            else -> Logs.i("For You: ${server.name} picked ${picked.songs.size} songs")
        }
        return picked
    }

    private suspend fun candidates(songs: List<FoundSong>, server: SubsonicServer, also: List<String> = emptyList()): List<FoundCandidate> {
        val heard = heard(songs.map { it.identity } + also)
        val known = knownArtists(server)
        return songs.map { song ->
            FoundCandidate(song, heard = song.identity in heard, knownArtist = foldedName(song.artist) in known, owned = index.find(song.identity) != null)
        }
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
                // Your own songs, from this phone or synced, always have a cover to show. Trackside
                // never sends a stand-in cover, so its songs aren't checked: a song without art
                // shows its placeholder rather than leaving the shelf empty.
                if (server.isTrackside || index.find(song.identity) != null) return@async song
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
        }.onFailure { Logs.w("For You: couldn't check a cover", it) }.getOrDefault(false)
    }

    // MARK: - Shelves of albums

    override suspend fun loadAlbums(serverId: String) {
        val server = server(serverId) ?: return
        val client = serverLibrary.client(server) ?: return
        val known = _albums.value[serverId]
        // Shelves that came back empty, while the library was still syncing, are asked again.
        val random = known?.takeIf { now() - it.loadedAt < ALBUMS_KEEP_MS && it.random.isNotEmpty() }?.random
            ?: albums(server, client, SubsonicClient.AlbumListType.Random)
        val artists = newArtists(serverId)
        val toGet = if (known != null && known.toGetFrom == artists && random === known.random) known.toGet else albumsToGet(server, artists, shuffled = false)
        _albums.update { it + (serverId to ServerAlbums(random, toGet, artists, if (random === known?.random) known.loadedAt else now())) }
    }

    override suspend fun shuffleRandom(serverId: String) {
        val server = server(serverId) ?: return
        val client = serverLibrary.client(server) ?: return
        val artists = newArtists(serverId)
        coroutineScope {
            val random = async { albums(server, client, SubsonicClient.AlbumListType.Random) }
            val toGet = async { albumsToGet(server, artists, shuffled = true) }
            _albums.update { it + (serverId to (it[serverId] ?: ServerAlbums()).copy(random = random.await(), toGet = toGet.await(), toGetFrom = artists)) }
        }
    }

    /** Artists of the new songs a server suggests, none you have: what albums to get are found from. */
    private fun newArtists(serverId: String): Set<String> {
        if (offers[serverId]?.second != NewSongs.Offered) return emptySet()
        val walk = walks[serverId] ?: return emptySet()
        val shelves = walk.shelves()
        val yours = index.snapshot.artists.mapTo(HashSet()) { foldedName(it.name) }
        return (shelves.suggested + shelves.picks + shelves.further + pending(walk))
            .filter { it.isNew && foldedName(it.artist) !in yours }
            .mapTo(LinkedHashSet()) { it.artist }
    }

    /**
     * An album from Apple's catalog for each of a few of [artists], none of it in your music,
     * to mix into Something different where the server gets songs. Each opens the catalog's
     * album page, whose Get asks the server for its songs.
     */
    private suspend fun albumsToGet(server: SubsonicServer, artists: Set<String>, shuffled: Boolean): List<CatalogAlbum> {
        if (artists.isEmpty()) return emptyList()
        val discovery = runCatching { graph.discovery }.getOrNull() ?: return emptyList()
        val chosen = (if (shuffled) artists.shuffled() else artists.toList()).take(ALBUM_ARTISTS)
        val gate = Semaphore(3)
        return coroutineScope {
            chosen.map { artist ->
                async {
                    gate.withPermit {
                        val found = ask(null, "For You: couldn't find albums by an artist ${server.name} suggests") { discovery.itunes.searchAlbums(artist) }.orEmpty()
                        val theirs = found.filter { foldedName(it.artistName) == foldedName(artist) && discovery.holding(it).ownership == Ownership.MISSING }
                        val albums = theirs.filter { it.kind == CatalogAlbumKind.ALBUM }.ifEmpty { theirs }
                        if (shuffled) albums.take(3).randomOrNull() else albums.firstOrNull()
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.id }
        }
    }

    /** The server's albums of a kind that are in the library, to open there. */
    private suspend fun albums(server: SubsonicServer, client: MusicServerClient, type: SubsonicClient.AlbumListType): List<ServerAlbum> =
        try {
            client.albums(type, size = 15)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logs.w("${server.name} couldn't list albums", error)
            emptyList()
        }.mapNotNull { album ->
            val id = ServerLibrary.albumId(server.id, album.id)
            index.album(id)?.let { ServerAlbum(id, it.title, it.artistName, album.coverArt?.let { cover -> ServerLinks.coverUrl(server.id, cover) }) }
        }

    // MARK: - Keep exploring

    /** A page more of songs you've never played, from a server picked at random, and new ones by artists new to you. */
    override suspend fun exploreMore() {
        val servers = _servers.value
        if (servers.isEmpty()) return
        try {
            exploreMoreFrom(servers)
        } finally {
            _answers.update { it + 1 }
        }
    }

    private suspend fun exploreMoreFrom(servers: List<SubsonicServer>) {
        for (server in servers) loadMore(server.id, ForYouShelf.FURTHER, COLUMN)
        exploringMutex.withLock {
            if (exploringWaiting.size < PAGE) {
                val server = servers.random()
                val client = serverLibrary.client(server)
                val songs = client?.let { ask(null, "Keep exploring: ${server.name} couldn't pick songs") { it.randomSongs(40) } }
                if (songs == null) {
                    failuresInARow++
                } else {
                    val found = songs.map { it.found() }
                    val heard = heard(found.map { it.identity })
                    val fresh = found.filter { it.identity !in heard && exploringSeen.add(it.identity) }
                    exploringWaiting.addAll(fresh.map { ServerSong(server.id, it) })
                    failuresInARow = if (fresh.isEmpty()) failuresInARow + 1 else 0
                }
                if (failuresInARow >= 3) Logs.i("Keep exploring: stopped after ${server.name} found nothing new three times in a row")
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
        // A new song plays once the server has it and it's in your library.
        if (song.song.isNew) return null
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

    override suspend fun newCover(song: ServerSong): String? {
        // The server's own cover comes first (coverUrl); Apple's catalog only where it has none.
        if (!song.song.isNew || song.song.cover != null) return null
        return try {
            graph.discovery.itunes.songCover(song.song.title, song.song.artist)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logs.w("For You: couldn't find a cover for a new song", error)
            null
        }
    }

    override fun coverUrl(song: ServerSong): String? {
        val cover = song.song.cover ?: return index.find(song.song.identity)?.artworkUri?.toString()
        val server = server(song.serverId) ?: return null
        return ServerLinks.coverUrl(server.id, cover, 300)
    }

    override fun canKeep(song: ServerSong): Boolean =
        if (song.song.isNew) offers[song.serverId]?.second == NewSongs.Offered
        else octo.capability(song.serverId) == FetchCapability.Fetches

    override suspend fun keep(song: ServerSong): String {
        val server = server(song.serverId) ?: return "That server isn't set up anymore"
        val s = song.song
        if (s.isNew) return request(server, s)
        return octo.get(ServerFind(server.id, server.name, SubsonicSong(id = s.id, title = s.title, artist = s.artist, album = s.album, isExternal = true)))
    }

    override suspend fun keepAll(serverId: String, songs: List<FoundSong>, name: String): String {
        val server = server(serverId) ?: return "That server isn't set up anymore"
        if (songs.isEmpty()) return "“$name” is in your library"
        var asked = 0
        for (song in songs) {
            val answer = askFor(server, song)
            if (answer.taken) asked++
            // Out of requests, requests turned off or the server out of reach: the rest can't be asked either.
            if (answer.stop) return if (asked == 0) answer.message else "Getting $asked of ${songs.size} songs from “$name”. ${answer.message}"
        }
        return if (asked == 0) "Couldn't get “$name”" else "Getting $asked ${if (asked == 1) "song" else "songs"} from “$name”"
    }

    /**
     * Asks Trackside to get a new song (`POST /acquisitions`), shows it on its way and follows
     * the request until the song is in your library or the server gives up on it.
     */
    private suspend fun request(server: SubsonicServer, song: FoundSong): String = askFor(server, song).message

    /**
     * How asking for one song went: whether it was taken, whether asking for more is pointless,
     * and what to say; once taken, the request and how it stood when asked.
     */
    private class Asked(
        val taken: Boolean,
        val message: String,
        val stop: Boolean = false,
        val request: OctoService.Request? = null,
        val answer: TracksideAcquisition? = null,
    )

    /**
     * Asks for one song (section 15.3), with a candidate to try first and with instant play
     * (section 15.5) when given one, and follows the request once it's taken.
     */
    private suspend fun askFor(server: SubsonicServer, song: FoundSong, candidate: TracksideCandidate? = null): Asked {
        val client = serverLibrary.tracksideClient(server.id) ?: return Asked(false, "Sign in to ${server.name} again", stop = true)
        val asked = try {
            client.requestAcquisition(
                TracksideAcquisitionRequest(
                    song.title, song.artist, song.album ?: candidate?.album, song.durationMs?.toLong() ?: candidate?.durationMs,
                    candidate = candidate?.token, instant = candidate != null,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: TracksideError.QuotaExceeded) {
            return Asked(false, quotaMessage(server.name, error.resetsAt), stop = true)
        } catch (error: TracksideError.CandidateExpired) {
            return Asked(false, "“${song.title}” isn't on offer anymore. Try again later.")
        } catch (error: TracksideError.NotFound) {
            // Requests were turned off since the server said it gets songs.
            offers[server.id] = now() to NewSongs.TurnedOff
            publishOffer(server.id)
            return Asked(false, "${server.name} isn't getting songs for you right now.", stop = true)
        } catch (error: TracksideError) {
            Logs.w("For You: ${server.name} couldn't take a request", error)
            return Asked(false, error.message ?: "Couldn't add “${song.title}”")
        } catch (error: IOException) {
            Logs.w("For You: couldn't reach ${server.name} to request a song", error)
            return Asked(false, "Couldn't reach ${server.name}. Try again.", stop = true)
        }
        val request = OctoService.Request(song.identity, server.id, asked.id)
        return when {
            asked.isDone -> {
                // 200: the server had it already; it's in your library with the next sync.
                octo.onItsWay(request)
                arrive(server, request)
                Asked(true, "“${song.title}” is on ${server.name} already. It'll be in your library in a moment.", request = request, answer = asked)
            }
            asked.isFinished -> Asked(false, "${server.name} couldn't get “${song.title}”.")
            else -> {
                octo.onItsWay(request)
                follow(server, request)
                Asked(true, "Getting “${song.title}”. It'll be in your library once ${server.name} has it.", request = request, answer = asked)
            }
        }
    }

    /** Checks on a request (section 15.4) until it's done, often at first, then less. */
    private fun follow(server: SubsonicServer, request: OctoService.Request) {
        if (following[request.id]?.isActive == true) return
        following[request.id] = scope.launch {
            var waited = 0L
            while (waited < FOLLOW_MS) {
                val step = if (waited < 24 * followEveryMs) followEveryMs else followEveryMs * 6
                delay(step)
                waited += step
                val client = serverLibrary.tracksideClient(server.id) ?: break
                val now = try {
                    client.acquisition(request.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: TracksideError.NotFound) {
                    octo.noLongerWaiting(request.identity)
                    break
                } catch (error: Exception) {
                    Logs.w("For You: couldn't check on a request to ${server.name}", error)
                    continue
                }
                if (now.isDone) {
                    arrive(server, request).await()
                    break
                }
                if (now.isFinished) {
                    Logs.i("For You: ${server.name} couldn't get a song: ${now.state} ${now.failure.orEmpty()}")
                    octo.noLongerWaiting(request.identity)
                    break
                }
            }
            following.remove(request.id)
        }
    }

    /** The song is on the server: the library syncs to bring it in, once however many are waiting on it. */
    private fun arrive(server: SubsonicServer, request: OctoService.Request): Deferred<Unit> =
        arriving.computeIfAbsent(request.id) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    arrived(server, request)
                } finally {
                    arriving.remove(request.id)
                }
            }
        }.also { it.start() }

    /** The song is on the server: the library syncs to bring it in, and it's no longer on its way. */
    private suspend fun arrived(server: SubsonicServer, request: OctoService.Request) {
        try {
            serverLibrary.sync(server)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logs.w("For You: couldn't sync ${server.name}", error)
        }
        octo.settle()
        octo.noLongerWaiting(request.identity)
    }

    // MARK: - Getting a song to play

    override fun toGet(song: ServerSong, cover: String?): PlayableSong? {
        val s = song.song
        if (!s.isNew || server(song.serverId) == null) return null
        if (!canKeep(song) && !octo.isWaiting(s.identity)) return null
        return SongsToGet.song(song.serverId, s.identity, s.title, s.artist, s.album, s.durationMs?.toLong(), (cover ?: coverUrl(song))?.let(Uri::parse))
    }

    override suspend fun getToPlay(song: PlayableSong, status: (String) -> Unit): ToPlay {
        val (serverId, _) = SongsToGet.parse(song.mediaId) ?: return ToPlay.Failed("Couldn't get “${song.title}”.")
        val found = newSong(song.title, song.artistName, song.albumTitle, song.durationMs)
        return try {
            getToPlay(ServerSong(serverId, found), status)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logs.w("Couldn't get a song to play", error)
            ToPlay.Failed("Couldn't get “${song.title}”.")
        }
    }

    private suspend fun getToPlay(song: ServerSong, status: (String) -> Unit): ToPlay {
        val s = song.song
        index.find(s.identity)?.let { return ToPlay.Ready(it) }
        val server = server(song.serverId) ?: return ToPlay.Failed("The server for “${s.title}” isn't set up in Tracks anymore.")
        val client = serverLibrary.tracksideClient(server.id) ?: return ToPlay.Failed("Sign in to ${server.name} again to get “${s.title}”.")
        // Played from its download before and that stopped (the download finished, say): the
        // same request is waited on until the song's in your library, never asked for again.
        streamedFrom.remove(s.identity)?.let { request ->
            Logs.i("Getting a song to play: its stream from ${server.name} stopped, so it plays from your library once it's there")
            status("Adding it to your library")
            return waitFor(server, client, request, s, status, streams = false)
        }
        val offer = newSongs(server, null)
        val plays = playModes[server.id] ?: NewPlays()
        // Already on its way: that request is waited on rather than asking again.
        val underWay = octo.requests().lastOrNull { it.identity == s.identity && it.serverId == server.id }?.takeIf { octo.isWaiting(s.identity) }
        if (underWay != null) {
            Logs.i("Getting a song to play: on its way from ${server.name} already")
            status("On its way from ${server.name}")
            follow(server, underWay)
            return waitFor(server, client, underWay, s, status, streams = plays.instant)
        }
        when (offer) {
            NewSongs.Offered -> Unit
            NewSongs.NotAllowed -> return ToPlay.Failed("Your account on ${server.name} isn't allowed to request songs.")
            else -> return ToPlay.Failed("${server.name} isn't getting songs for you right now.")
        }
        status("Asking ${server.name} for it")
        // Instant play: kept as any request is, and streamed while it downloads.
        if (plays.instant) {
            val candidate = instantCandidate(server, client, s)
            if (candidate != null) {
                Logs.i("Getting a song to play from ${server.name} with instant play")
                val asked = askFor(server, s, candidate)
                if (!asked.taken) return ToPlay.Failed(asked.message)
                return waitFor(server, client, asked.request!!, s, status, streams = true, first = asked.answer, candidateMs = candidate.durationMs)
            }
        }
        val asked = askFor(server, s)
        if (!asked.taken) return ToPlay.Failed(asked.message)
        val request = asked.request!!
        // Played without keeping while the request keeps it, where the server offers that.
        if (plays.external && asked.answer?.isDone != true) {
            Logs.i("Getting a song to play from ${server.name}: playing it without keeping while it's requested")
            streamWithoutKeeping(server, client, s)?.let { return ToPlay.Ready(it) }
        } else {
            Logs.i("Getting a song to play from ${server.name}: requested, to play once it's in your library")
        }
        return waitFor(server, client, request, s, status, streams = false, first = asked.answer)
    }

    /** A candidate that can play at once, for instant play; null when there's none. */
    private suspend fun instantCandidate(server: SubsonicServer, client: TracksideClient, song: FoundSong): TracksideCandidate? {
        val candidates = try {
            client.searchExternal("${song.artist} ${song.title}", 10)?.candidates.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logs.w("${server.name} couldn't look for a song to play at once", error)
            return null
        }
        return candidates.firstOrNull { it.instant == true && songIdentity(it.title, it.artist) == song.identity }
            ?: candidates.firstOrNull { it.instant == true && it.match == "exact" }
    }

    /** The song played without keeping it (section 15.6), or its library copy; null when the server can't. */
    private suspend fun streamWithoutKeeping(server: SubsonicServer, client: TracksideClient, song: FoundSong): PlayableSong? = try {
        val answer = client.playExternal(TracksideExternalPlayRequest(song.title, song.artist, song.album, song.durationMs?.toLong()))
        answer.songId?.let { librarySong(server, it, song) }
            ?: answer.stream?.progressive?.let { newPlayable(server, song, it, answer.durationMs) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Logs.w("${server.name} couldn't play a song without keeping it", error)
        null
    }

    /**
     * Waits on a request until the song can play: its stream once bytes flow, where [streams]
     * (instant play), else the library's copy once it's done and synced. Gives up after
     * [limitMs] or when the server does, saying why; the request goes on either way.
     */
    private suspend fun waitFor(
        server: SubsonicServer,
        client: TracksideClient,
        request: OctoService.Request,
        song: FoundSong,
        status: (String) -> Unit,
        streams: Boolean,
        first: TracksideAcquisition? = null,
        candidateMs: Long? = null,
        limitMs: Long = playWaitMs,
        stepMs: Long = playCheckMs,
    ): ToPlay {
        val started = now()
        var answer = first
        while (true) {
            index.find(song.identity)?.let { return ToPlay.Ready(it) }
            val state = answer ?: try {
                client.acquisition(request.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TracksideError.NotFound) {
                octo.noLongerWaiting(request.identity)
                return ToPlay.Failed("${server.name} isn't getting “${song.title}” anymore.")
            } catch (error: Exception) {
                Logs.w("Couldn't check on a song being got to play", error)
                null
            }
            answer = null
            val stream = state?.stream?.progressive?.takeIf { streams }
            when {
                state == null -> Unit
                state.isDone -> {
                    following.remove(request.id)?.cancel()
                    status("Adding it to your library")
                    arrive(server, request).await()
                    val found = index.find(song.identity) ?: state.songId?.let { librarySong(server, it, song) }
                    return found?.let { ToPlay.Ready(it) } ?: ToPlay.Failed("“${song.title}” is on ${server.name}, but couldn't be found to play.")
                }
                state.isFinished -> {
                    Logs.i("Getting a song to play: ${server.name} couldn't get it: ${state.state} ${state.failure.orEmpty()}")
                    octo.noLongerWaiting(request.identity)
                    return ToPlay.Failed(failureMessage(server, song, state.failure))
                }
                stream != null -> {
                    streamedFrom[song.identity] = request
                    return ToPlay.Ready(newPlayable(server, song, stream, candidateMs))
                }
                else -> status(progress(server, state))
            }
            if (now() - started >= limitMs) {
                return ToPlay.Failed("“${song.title}” is taking a while. It'll be in your library once ${server.name} has it.")
            }
            delay(stepMs)
        }
    }

    /** How a request is going, in a few words. */
    private fun progress(server: SubsonicServer, request: TracksideAcquisition): String = when (request.state) {
        "queued" -> "Waiting for ${server.name}"
        "searching" -> "Looking for it"
        "downloading" -> {
            val done = request.progress?.let { p -> p.total?.takeIf { it > 0 }?.let { (p.bytes * 100 / it).coerceIn(0, 100) } }
            if (done != null) "Downloading, $done%" else "Downloading"
        }
        else -> "Adding it to your library"
    }

    /** Why the server couldn't get a song (section 15.1's `failure`), in plain words. */
    private fun failureMessage(server: SubsonicServer, song: FoundSong, failure: String?): String = when (failure) {
        "no_match" -> "${server.name} couldn't find “${song.title}”."
        "quota_exceeded" -> "You've reached ${server.name}'s limit on requested songs."
        "disabled" -> "${server.name} isn't getting songs for you right now."
        "insufficient_space" -> "${server.name} has no room for “${song.title}”."
        "provider_unavailable" -> "${server.name} can't get songs right now. Try again later."
        else -> "${server.name} couldn't get “${song.title}”."
    }

    // MARK: - New songs on Tracks Radio

    override suspend fun newForRadio(): List<ServerSong> {
        val found = ArrayList<ServerSong>()
        for (server in _servers.value) {
            if (!server.isTrackside || newSongs(server, null) != NewSongs.Offered) continue
            if ((playModes[server.id] ?: NewPlays()).forRadio == NewPlay.None) continue
            val walk = walks[server.id] ?: continue
            val shelves = walk.shelves()
            (shelves.suggested + shelves.picks + shelves.further + pending(walk))
                .filter { it.isNew && index.find(it.identity) == null }
                .forEach { found += ServerSong(server.id, it) }
        }
        return found.distinctBy { it.song.identity }
    }

    override suspend fun playNew(song: ServerSong): PlayableSong? {
        if (!song.song.isNew) return playable(song)
        index.find(song.song.identity)?.let { return it }
        val server = server(song.serverId) ?: return null
        if (newSongs(server, null) != NewSongs.Offered) return null
        val client = serverLibrary.tracksideClient(server.id) ?: return null
        val s = song.song
        return try {
            when ((playModes[server.id] ?: NewPlays()).forRadio) {
                NewPlay.External -> streamWithoutKeeping(server, client, s)
                NewPlay.Instant -> playInstantly(server, client, s)
                NewPlay.None -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logs.w("Tracks Radio: ${server.name} couldn't play a new song", error)
            null
        }
    }

    /**
     * Instant play (section 15.5): a candidate that can play at once is requested with
     * `instant`, then followed until the server says where it streams from. The song is kept,
     * so it joins your library once it's in, as any request does.
     */
    private suspend fun playInstantly(server: SubsonicServer, client: TracksideClient, song: FoundSong): PlayableSong? {
        val candidate = instantCandidate(server, client, song) ?: return null
        val asked = askFor(server, song, candidate)
        if (!asked.taken) return null
        // Bytes usually flow within two seconds; the stream is asked after until it's there.
        val ready = waitFor(server, client, asked.request!!, song, {}, streams = true, first = asked.answer, candidateMs = candidate.durationMs, limitMs = INSTANT_WAIT_MS, stepMs = INSTANT_STEP_MS)
        return (ready as? ToPlay.Ready)?.song
    }

    /** A song the server turned out to have: its own stream, as the library plays it. */
    private fun librarySong(server: SubsonicServer, songId: String, song: FoundSong): PlayableSong =
        index.find(song.identity) ?: PlayableSong(
            mediaId = ServerLibrary.mediaId(server.id, songId),
            uri = ServerLinks.stream(server.id, songId),
            title = song.title,
            artistName = song.artist,
            albumTitle = song.album,
            artworkUri = null,
            durationMs = song.durationMs?.toLong(),
            identity = song.identity,
        )

    /** A new song streamed from where the server said, by a link that carries no credentials. */
    private suspend fun newPlayable(server: SubsonicServer, song: FoundSong, stream: String, durationMs: Long?): PlayableSong =
        PlayableSong(
            mediaId = ServerLibrary.newSongMediaId(server.id, song.identity),
            uri = ServerLinks.play(server.id, stream),
            title = song.title,
            artistName = song.artist,
            albumTitle = song.album,
            artworkUri = ServerSong(server.id, song).let { coverUrl(it) ?: newCover(it) }?.let(Uri::parse),
            durationMs = durationMs ?: song.durationMs?.toLong(),
            identity = song.identity,
        )

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

    /**
     * Artists you know, folded: their songs are Picked for you's, everyone else's Suggested
     * songs'. Those you play, and those in your music, unless the server only ever finds what's
     * in your music (Trackside): then an artist you have and have never played is new to you.
     */
    private suspend fun knownArtists(server: SubsonicServer): Set<String> {
        val played = graph.engine.read { artistChart(Engine.allTime, null, SourceFilter.ALL, 2000u).rows.map { foldedName(it.artist.name) } }.toSet()
        return if (server.isTrackside) played else played + index.snapshot.artists.map { foldedName(it.name) }
    }

    /** Which of these songs you've played. */
    private suspend fun heard(identities: List<String>): Set<String> =
        graph.engine.read { identities.filter { song(it) != null } }.toSet()

    // MARK: - Keeping state

    private fun publish(serverId: String, walk: ServerForYou, filling: Boolean, problem: String? = null) {
        walks[serverId] = walk
        _forYou.update { it + (serverId to ServerForYouState(walk.shelves(), filling, problem, offers[serverId]?.second)) }
        publishExploring()
        val json = walk.toJson()
        scope.launch(Dispatchers.IO) { runCatching { dir.mkdirs(); File(dir, "$serverId.json").writeText(json) } }
    }

    private fun restore(serverId: String) {
        val walk = runCatching { File(dir, "$serverId.json").readText() }.getOrNull()?.let(::serverForYouFromJson) ?: return
        // Picks that found nothing aren't worth showing again: the shelf waits for new ones.
        if (walk.shelves().let { it.picks.isEmpty() && it.suggested.isEmpty() }) return
        walks[serverId] = walk
        _forYou.update { it + (serverId to ServerForYouState(walk.shelves())) }
    }

    private fun server(id: String): SubsonicServer? = serverLibrary.entries.value.firstOrNull { it.server.id == id }?.server

    private fun lock(serverId: String) = synchronized(locks) { locks.getOrPut(serverId) { Mutex() } }

    private fun budget(serverId: String) = synchronized(budgets) { budgets.getOrPut(serverId, newBudget) }

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
        /** A column for each shelf twice over, and up to this many goes while a shelf is still empty. */
        const val FIRST_ROUNDS = 4
        const val MAX_ROUNDS = 10
        const val PAGE = 8
        const val ARTIST_SONGS = 20
        const val SERVER_PICKS = 40
        const val GET_MORE = 24
        /** How long whether a server gets songs is taken as known. */
        const val OFFER_KEEP_MS = 5 * 60_000L
        /** How long a request is followed before it's left to the next launch. */
        const val FOLLOW_MS = 30 * 60_000L
        const val ALBUMS_KEEP_MS = 15 * 60_000L
        /** How long instant play is waited on for its stream, and how often it's asked. */
        const val INSTANT_WAIT_MS = 12_000L
        const val INSTANT_STEP_MS = 1_000L
        /** Artists the server suggests that Something different finds an album to get for. */
        const val ALBUM_ARTISTS = 5
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

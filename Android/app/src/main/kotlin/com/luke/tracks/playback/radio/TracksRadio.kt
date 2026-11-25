package com.luke.tracks.playback.radio

import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.luke.tracks.AppGraph
import com.luke.tracks.data.radioTuning
import com.luke.tracks.discover.ServerPicks
import com.luke.tracks.discover.ServerSong
import com.luke.tracks.discover.serverPicks
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlaybackBridge
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RadioSource
import com.luke.tracks.playback.RadioState
import com.luke.tracks.playback.radio.RadioWords.isTurningAway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.tracks_engine.Listen
import uniffi.tracks_engine.RadioReason
import uniffi.tracks_engine.RadioStation
import uniffi.tracks_engine.RadioTrack
import uniffi.tracks_engine.radioRecordSkip
import java.io.File
import kotlin.random.Random

/**
 * Tracks Radio on Android, as the iPhone plays it from Your Music: every song in your music and the ones your servers
 * found for you, the more played and the more recent the likelier, with the songs you've never
 * played as its new finds, tuned in its settings. The engine's `RadioStation` (a port of the iPhone's
 * `LiveMix`) picks each song as the one before starts, steers away from runs of skips and
 * toward what lands, opens with a few songs from different directions, and remembers what
 * landed for next time.
 */
class TracksRadio(
    private val graph: AppGraph,
    private val files: File = graph.context.filesDir,
    /** Where new songs come from: your servers' picks, when there's a server library. */
    private val picks: () -> ServerPicks? = { if (graph.library is CombinedLibrary) graph.serverPicks else null },
) : RadioSource {
    private val lock = Mutex()
    @Volatile private var station: RadioStation? = null
    /** Every song the station can pick, by identity: your own copy before a server's. */
    @Volatile private var songs: Map<String, PlayableSong> = emptyMap()
    private val _state = MutableStateFlow(RadioState())
    override val state: StateFlow<RadioState> = _state.asStateFlow()
    /** Habits and skips are never kept from sample data. */
    private val keeps = !graph.sampleData
    private val habitsFile get() = File(files, "radio-habits.json")
    private val skipsFile get() = File(files, "radio-skips.json")
    private val main = Handler(Looper.getMainLooper())

    /** The station's queue is the one playing. */
    private val isPlaying: Boolean get() = station != null && PlaybackBridge.origin.value?.kind == QueueOrigin.Kind.Radio

    fun watch() {
        // A new tuning applies from the next pick: the song queued next stays.
        graph.scope.launch {
            graph.settings.settings.map { it.radioTuning }.distinctUntilChanged().drop(1).collect { retune() }
        }
        // Songs asked to hear less of while it plays are heard as skips.
        graph.scope.launch {
            var hidden: Set<String>? = null
            graph.settings.settings.map { it.hiddenSongs }.distinctUntilChanged().collect { now ->
                hidden?.let { before -> (now - before).forEach(::heardLess) }
                hidden = now
            }
        }
    }

    override suspend fun start(startingWith: PlayableSong?): List<PlayableSong> = lock.withLock {
        val made = make() ?: return@withLock emptyList()
        val (built, byIdentity) = made
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            // From a song, it says what you're in the mood for; from nothing, a probe asks.
            built.begin(read(habitsFile), startingWith == null, now)
            val first = ArrayList<PlayableSong>(2)
            if (startingWith != null) {
                built.notePicked(startingWith.identity)
                built.leanToward(startingWith.identity)
                first += startingWith
            } else {
                built.opener()?.let(byIdentity::get)?.let(first::add)
            }
            while (first.size < 2) first += built.next()?.let(byIdentity::get) ?: break
            sinceNew = first.size
            newPlayed.clear()
            station?.close()
            station = built
            songs = byIdentity
            _state.value = RadioState(picks = first.associate { it.mediaId to built.reason(it.identity) })
            keepHabits()
            first
        }
    }

    override suspend fun next(): PlayableSong? {
        val current = station ?: return null
        // Every few songs, one the server suggests and doesn't have, where it can play it.
        if (sinceNew >= NEW_EVERY) {
            newSong()?.let { song ->
                sinceNew = 0
                _state.update { it.copy(picks = (it.picks - song.mediaId + (song.mediaId to RadioReason.NewFind)).recent()) }
                return song
            }
        }
        return withContext(Dispatchers.Default) {
            val song = runCatching { current.next() }.getOrNull()?.let { songs[it] } ?: return@withContext null
            val reason = current.reason(song.identity)
            _state.update { it.copy(picks = (it.picks - song.mediaId + (song.mediaId to reason)).recent()) }
            sinceNew++
            song
        }
    }

    /** Songs of the station's own since the last new one: a new one comes after [NEW_EVERY], never two together. */
    @Volatile private var sinceNew = 0
    /** New songs played this listen, so none comes twice. */
    private val newPlayed = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * A new song from a Trackside server that gets songs, made playable as the server allows
     * (streamed without keeping it, or got with instant play); null when there's none it can
     * play, so the station's own song comes instead.
     */
    private suspend fun newSong(): PlayableSong? {
        val source = runCatching { picks() }.getOrNull() ?: return null
        val skipped = graph.settings.settings.first().hiddenSongs
        val waiting = runCatching { source.newForRadio() }.getOrDefault(emptyList())
            .filter { it.song.identity !in newPlayed && it.song.identity !in skipped }
            .shuffled()
        for (found in waiting.take(NEW_TRIES)) {
            newPlayed += found.song.identity
            val song = withTimeoutOrNull(NEW_WAIT_MS) { runCatching { source.playNew(found) }.getOrNull() } ?: continue
            songs = songs + (song.identity to song)
            return song
        }
        return null
    }

    override fun knows(song: PlayableSong): Boolean = runCatching { station?.knows(song.identity) == true }.getOrDefault(false)

    override fun note(song: PlayableSong, listen: Listen) = steer(song.identity) { note(it, listen) }

    override fun loved(song: PlayableSong) = steer(song.identity) { noteLoved(it) }

    override fun moreLikeThis(song: PlayableSong) {
        if (song.identity in _state.value.askedMoreLike) return
        steer(song.identity) { noteLoved(it) }
        _state.update { it.copy(askedMoreLike = it.askedMoreLike + song.identity) }
        say("More like this coming up")
    }

    override fun replayed(song: PlayableSong) = steer(song.identity) { noteReplayed(it) }

    override fun removed(song: PlayableSong) = steer(song.identity) { note(it, Listen.SKIPPED) }

    override fun heardLess(identity: String) = steer(identity) { note(it, Listen.SKIPPED) }

    override fun release(song: PlayableSong) {
        runCatching { station?.release(song.identity) }
        _state.update { it.copy(picks = it.picks - song.mediaId) }
    }

    override fun recordSkip(song: PlayableSong) {
        if (!keeps || song.identity.isEmpty()) return
        val now = System.currentTimeMillis()
        graph.scope.launch(Dispatchers.IO) {
            skipsLock.withLock { write(skipsFile, radioRecordSkip(read(skipsFile), song.identity, now)) }
        }
    }

    private val skipsLock = Mutex()

    /** Tells the station playing something about a song of its own, then says where it's heading. */
    private fun steer(identity: String, tell: RadioStation.(String) -> Unit) {
        val current = station ?: return
        if (!isPlaying) return
        runCatching {
            if (!current.knows(identity)) return
            current.tell(identity)
            val steering = current.steering()
            val before = _state.value.steering
            _state.update { it.copy(steering = steering) }
            if (steering != null && steering != before && steering.isTurningAway) say(RadioWords.headline(steering))
        }.onFailure { Log.w("Tracks", "Radio couldn't take that in", it) }
        keepHabits()
    }

    /** Makes the station again for a new tuning, carrying over what this listen has learned. */
    private suspend fun retune() = lock.withLock {
        val current = station ?: return@withLock
        if (!isPlaying) return@withLock
        val (retuned, byIdentity) = make() ?: return@withLock
        withContext(Dispatchers.Default) {
            runCatching { retuned.continueFrom(current) }.onFailure { return@withContext }
            // Songs already queued stay playable whatever the new tuning leaves out.
            songs = songs + byIdentity
            station = retuned
            current.close()
        }
    }

    /** The station over your music and your servers' finds, and those songs by identity. */
    private suspend fun make(): Pair<RadioStation, Map<String, PlayableSong>>? {
        val settings = graph.settings.settings.first()
        val byIdentity = LinkedHashMap<String, PlayableSong>()
        val library = graph.library.snapshot.value.songs.sortedBy { it.isFromServer }
        for (song in library) if (song.identity.isNotEmpty()) byIdentity.putIfAbsent(song.identity, song)
        for (song in serverFinds()) byIdentity.putIfAbsent(song.identity, song)
        if (byIdentity.isEmpty()) return null
        val tracks = byIdentity.values.map { it.radioTrack }
        val skips = withContext(Dispatchers.IO) { read(skipsFile) }
        val built = runCatching {
            graph.engine.read {
                tracksRadio(tracks, settings.radioTuning, skips, settings.hiddenSongs.toList(), System.currentTimeMillis(), Random.nextLong().toULong())
            }
        }.onFailure { Log.w("Tracks", "Couldn't make Tracks Radio", it) }.getOrNull() ?: return null
        if (built.isEmpty()) {
            built.close()
            return null
        }
        return built to byIdentity
    }

    /** Picked for you's songs from every server that aren't in your music yet: new finds. */
    private fun serverFinds(): List<PlayableSong> {
        val picks = runCatching { picks() }.getOrNull() ?: return emptyList()
        return picks.forYou.value.flatMap { (serverId, state) ->
            (state.shelves.picks + state.shelves.suggested + state.shelves.further).map { ServerSong(serverId, it) }
        }.mapNotNull(picks::playable)
    }

    private fun keepHabits() {
        if (!keeps) return
        val json = runCatching { station?.habitsJson(System.currentTimeMillis()) }.getOrNull() ?: return
        graph.scope.launch(Dispatchers.IO) { write(habitsFile, json) }
    }

    /** Said in a toast while Tracks is on screen, as the iPhone says it in the app. */
    private fun say(message: String) {
        main.post {
            val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
            if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                runCatching { Toast.makeText(graph.context, message, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun read(file: File): String = runCatching { file.readText() }.getOrDefault("")

    private fun write(file: File, text: String) {
        runCatching {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(text)
            temporary.renameTo(file)
        }.onFailure { Log.w("Tracks", "Couldn't keep ${file.name}", it) }
    }

    private companion object {
        /** Picks kept for saying why, enough for any Up next. */
        const val KEPT_PICKS = 200
        /** One new song after every three of the station's own. */
        const val NEW_EVERY = 3
        /** New songs tried before the station's own song comes instead, and how long each may take. */
        const val NEW_TRIES = 2
        const val NEW_WAIT_MS = 20_000L

        fun <V> Map<String, V>.recent(): Map<String, V> =
            if (size <= KEPT_PICKS) this else entries.drop(size - KEPT_PICKS).associate { it.key to it.value }
    }
}

/** On a server rather than this phone. */
private val PlayableSong.isFromServer: Boolean get() = ServerLibrary.parseMediaId(mediaId) != null

/** The song as the engine's radio takes it. */
private val PlayableSong.radioTrack: RadioTrack get() = RadioTrack(identity, title, artistName, genre, year, isFromServer)

/** The genres Tracks Radio can lean into: the ones you play most, then any picked that have since dropped out. */
suspend fun AppGraph.radioGenreChoices(picked: Set<String>): List<String> {
    val tracks = library.snapshot.value.songs.map { it.radioTrack }
    val found = runCatching { engine.read { radioGenres(tracks, 12u) } }.getOrDefault(emptyList())
    return found + picked.sorted().filter { it !in found }
}

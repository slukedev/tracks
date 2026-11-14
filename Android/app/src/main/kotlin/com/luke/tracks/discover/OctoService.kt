package com.luke.tracks.discover

import android.content.SharedPreferences
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicClient
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.library.SubsonicSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uniffi.tracks_engine.SongName
import uniffi.tracks_engine.catalogBestMatch
import uniffi.tracks_engine.songIdentity

/** Whether a server can fetch music you don't have, as Octo in front of Navidrome does. */
enum class FetchCapability { Unknown, Checking, Fetches, PlaysOnly }

/** A song found on a server: one it has, or one it found elsewhere and can fetch. */
data class ServerFind(val serverId: String, val serverName: String, val song: SubsonicSong) {
    val isExternal: Boolean get() = song.isExternal == true
}

/** The parts of the servers this needs, so tests can stand in for them. */
interface ServerAccess {
    val entries: StateFlow<List<ServerEntry>>
    fun client(server: SubsonicServer): SubsonicClient?
    suspend fun sync(server: SubsonicServer)
}

/**
 * Octo, and any server like it: a Subsonic server whose search also answers with songs it
 * can find elsewhere (`isExternal`), and that fetches one into the library when it's starred.
 *
 * A server counts as one once it says so: its OpenSubsonic ping names it Octo, or a search
 * comes back with a song it found elsewhere. Plain Navidrome, Gonic and the rest never do, so
 * Tracks never offers to get music from them.
 */
class OctoService(
    private val prefs: SharedPreferences,
    private val servers: ServerAccess,
    private val scope: CoroutineScope,
    /** Whether a song with this identity is in your music now. */
    private val inLibrary: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _capabilities = MutableStateFlow(
        prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(CAP)) null
            else key.removePrefix(CAP) to (runCatching { FetchCapability.valueOf(value as String) }.getOrNull() ?: FetchCapability.Unknown)
        }.toMap(),
    )
    val capabilities: StateFlow<Map<String, FetchCapability>> = _capabilities.asStateFlow()

    /** Songs asked for that their server hasn't brought in yet, by identity. */
    private val _waiting = MutableStateFlow(prefs.getStringSet(WAITING, emptySet()).orEmpty())
    val waiting: StateFlow<Set<String>> = _waiting.asStateFlow()

    /** Octo looks each song up elsewhere: a few at a time, or its own lookups are refused. */
    private val lookups = Semaphore(3)
    private val answers = HashMap<String, Pair<Long, List<SubsonicSong>>>()
    private val followUps = HashMap<String, Job>()

    fun capability(serverId: String): FetchCapability = _capabilities.value[serverId] ?: FetchCapability.Unknown

    /** The servers that can fetch and are answering. */
    fun fetchingServers(): List<SubsonicServer> = servers.entries.value
        .filter { it.status is ServerStatus.Ready && capability(it.server.id) == FetchCapability.Fetches }
        .map { it.server }

    /** Finds out what a server is, unless it's known; [force] asks again. */
    suspend fun probe(server: SubsonicServer, force: Boolean = false, probeQuery: String = "love") {
        if (!force && capability(server.id) != FetchCapability.Unknown) return
        val client = servers.client(server) ?: return
        set(server.id, FetchCapability.Checking)
        val type = runCatching { client.serverType() }.getOrNull()
        val found = when {
            type?.contains("octo", ignoreCase = true) == true -> FetchCapability.Fetches
            else -> runCatching {
                // Octo keeps its first 12 for the library, so ask for more than that.
                val songs = lookups.withPermit { client.search(probeQuery, artists = 0, albums = 0, songs = 24).songs }
                if (songs.any { it.isExternal == true }) FetchCapability.Fetches else FetchCapability.PlaysOnly
            }.getOrNull()
        }
        set(server.id, found ?: FetchCapability.Unknown)
    }

    /** Forgets what's known of servers that are gone. */
    fun forgetMissing(ids: Set<String>) {
        _capabilities.value.keys.filter { it !in ids }.forEach { id ->
            prefs.edit().remove(CAP + id).apply()
            _capabilities.update { it - id }
        }
    }

    private fun set(id: String, capability: FetchCapability) {
        _capabilities.update { it + (id to capability) }
        if (capability != FetchCapability.Checking) prefs.edit().putString(CAP + id, capability.name).apply()
    }

    /**
     * The song on a server that can fetch, if one has or can find it: the artist's songs
     * first, which settles a whole shelf of their songs in one answer, then the song itself.
     */
    suspend fun find(title: String, artist: String): ServerFind? {
        for (server in fetchingServers()) {
            for (query in listOf(artist, "$title $artist")) {
                val songs = songsFor(server, query) ?: continue
                val names = songs.map { SongName(it.title, it.artist.orEmpty()) }
                catalogBestMatch(title, artist, names)?.let { return ServerFind(server.id, server.name, songs[it.toInt()]) }
            }
        }
        return null
    }

    private suspend fun songsFor(server: SubsonicServer, query: String): List<SubsonicSong>? {
        val key = "${server.id}\u001F$query"
        synchronized(answers) { answers[key]?.let { (at, songs) -> if (now() - at < KEEP_MS) return songs } }
        val client = servers.client(server) ?: return null
        val songs = runCatching { lookups.withPermit { client.search(query, artists = 0, albums = 0, songs = 50).songs } }.getOrNull() ?: return null
        synchronized(answers) {
            if (answers.size > 400) answers.clear()
            answers[key] = now() to songs
        }
        if (songs.any { it.isExternal == true } && capability(server.id) != FetchCapability.Fetches) set(server.id, FetchCapability.Fetches)
        return songs
    }

    fun isWaiting(identity: String) = identity in _waiting.value

    /**
     * Stars a found song, which Octo takes as "get me this": it fetches the file into the
     * library. Syncs every half minute for five minutes, then every two, for twenty minutes
     * or until the song is in.
     */
    suspend fun get(find: ServerFind): String {
        val server = servers.entries.value.firstOrNull { it.server.id == find.serverId }?.server ?: return "That server isn't set up anymore"
        val client = servers.client(server) ?: return "Sign in to ${server.name} again"
        runCatching { client.star(find.song.id) }.onFailure { return it.message ?: "Couldn't reach ${server.name}" }
        val identity = songIdentity(find.song.title, find.song.artist.orEmpty())
        _waiting.update { it + identity }
        saveWaiting()
        followUp(server)
        return "Getting “${find.song.title}”"
    }

    private fun followUp(server: SubsonicServer) {
        synchronized(followUps) {
            followUps[server.id]?.cancel()
            followUps[server.id] = scope.launch {
                var waited = 0L
                while (waited < 20 * 60_000L && _waiting.value.isNotEmpty()) {
                    val step = if (waited < 5 * 60_000L) 30_000L else 120_000L
                    delay(step)
                    waited += step
                    servers.sync(server)
                    settle()
                }
            }
        }
    }

    /** Lets go of songs that have arrived. */
    fun settle() {
        val arrived = _waiting.value.filter(inLibrary).toSet()
        if (arrived.isEmpty()) return
        _waiting.update { it - arrived }
        saveWaiting()
    }

    private fun saveWaiting() = prefs.edit().putStringSet(WAITING, _waiting.value).apply()

    companion object {
        private const val CAP = "capability:"
        private const val WAITING = "waiting"
        private const val KEEP_MS = 12 * 60 * 60_000L
    }
}

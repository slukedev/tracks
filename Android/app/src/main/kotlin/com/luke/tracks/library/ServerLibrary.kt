package com.luke.tracks.library

import android.content.Context
import android.net.Uri
import android.util.Log
import com.luke.tracks.data.SettingsRepository
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import uniffi.tracks_engine.songIdentity
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface ServerStatus {
    data object Syncing : ServerStatus
    data class Ready(val songCount: Int) : ServerStatus
    data class Failed(val message: String) : ServerStatus
    /** The sealed password couldn't be opened (a restore onto a new phone): sign in again. */
    data object NeedsPassword : ServerStatus
}

data class ServerEntry(val server: SubsonicServer, val status: ServerStatus)

/**
 * Your Subsonic servers: kept in settings, each with its whole song list cached on the phone
 * so the library opens instantly and works on a plane. Synced at launch and whenever a server
 * is added or changed. The songs themselves stream as you play them.
 */
class ServerLibrary(
    private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretBox,
    private val scope: CoroutineScope,
    private val transport: SubsonicTransport = OkHttpTransport(defaultHttp),
) {
    private val cacheDir = File(context.filesDir, "servers")
    private val songs = MutableStateFlow<Map<String, List<SubsonicSong>>>(emptyMap())
    private val _entries = MutableStateFlow<List<ServerEntry>>(emptyList())
    private val _tracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    private val _loading = MutableStateFlow(true)
    private val mutex = Mutex()

    val entries: StateFlow<List<ServerEntry>> = _entries.asStateFlow()
    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun start() {
        scope.launch {
            settings.settings.map { it.subsonicServers }.distinctUntilChanged().collect { raw ->
                val servers = parse(raw)
                mutex.withLock {
                    val cached = withContext(Dispatchers.IO) { servers.associate { it.id to readCache(it.id) } }
                    songs.value = cached.filterValues { it != null }.mapValues { it.value!! }
                    _entries.value = servers.map { server ->
                        val previous = _entries.value.firstOrNull { it.server.id == server.id && it.server == server }
                        ServerEntry(server, previous?.status ?: cached[server.id]?.let { ServerStatus.Ready(it.size) } ?: ServerStatus.Syncing)
                    }
                    publish()
                }
                _loading.value = false
                // Each server whose list isn't fresh this launch, in the background.
                servers.filter { it.id !in syncedThisLaunch }.forEach { server -> scope.launch { sync(server) } }
            }
        }
    }

    private val syncedThisLaunch: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val syncing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** A client for [server], or null when its password can't be opened. */
    fun client(server: SubsonicServer): SubsonicClient? {
        val password = secrets.open(server.secret) ?: return null
        return SubsonicClient(server, password, transport)
    }

    /** The saved password, for checking a server again without typing it. */
    fun savedPassword(server: SubsonicServer): String? = secrets.open(server.secret)

    /** Checks an address and sign-in before saving it. */
    suspend fun test(url: HttpUrl, username: String, password: String): Result<Unit> = runCatching {
        val probe = SubsonicServer("probe", "", ServerAddress.stored(url), username.trim(), SubsonicClient.makeSalt())
        SubsonicClient(probe, password, transport).ping()
    }

    /**
     * Adds a server, or changes [existingId]. A blank [password] keeps the one already saved.
     */
    suspend fun save(existingId: String?, name: String, url: HttpUrl, username: String, password: String): SubsonicServer {
        val all = parse(settings.settings.first().subsonicServers)
        val previous = all.firstOrNull { it.id == existingId }
        val server = SubsonicServer(
            id = previous?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { url.host },
            url = ServerAddress.stored(url),
            username = username.trim(),
            salt = previous?.salt?.takeIf { previous.username == username.trim() } ?: SubsonicClient.makeSalt(),
            secret = if (password.isEmpty() && previous != null) previous.secret else secrets.seal(password),
        )
        syncedThisLaunch.remove(server.id)
        val updated = if (previous == null) all + server else all.map { if (it.id == server.id) server else it }
        settings.update { it.copy(subsonicServers = encode(updated)) }
        return server
    }

    suspend fun remove(id: String) {
        val all = parse(settings.settings.first().subsonicServers)
        settings.update { it.copy(subsonicServers = encode(all.filterNot { s -> s.id == id })) }
        withContext(Dispatchers.IO) { cacheFile(id).delete() }
    }

    suspend fun syncAll() {
        _entries.value.map { it.server }.forEach { sync(it) }
    }

    suspend fun sync(server: SubsonicServer) {
        if (!syncing.add(server.id)) return
        try {
            syncNow(server)
        } finally {
            syncing.remove(server.id)
        }
    }

    private suspend fun syncNow(server: SubsonicServer) {
        val client = client(server)
        if (client == null) {
            setStatus(server.id, ServerStatus.NeedsPassword)
            return
        }
        if (songs.value[server.id] == null) setStatus(server.id, ServerStatus.Syncing)
        try {
            val all = client.allSongs()
            withContext(Dispatchers.IO) { writeCache(server.id, all) }
            syncedThisLaunch += server.id
            mutex.withLock {
                songs.update { it + (server.id to all) }
                setStatus(server.id, ServerStatus.Ready(all.size))
                publish()
            }
        } catch (error: Exception) {
            Log.w("Tracks", "Couldn't sync ${server.name}", error)
            if (songs.value[server.id] == null) setStatus(server.id, ServerStatus.Failed(error.message ?: "Something went wrong."))
        }
    }

    /** Stars or unstars a song on its server, when it's a server's. */
    suspend fun star(mediaId: String, on: Boolean) {
        val (serverId, songId) = parseMediaId(mediaId) ?: return
        val server = _entries.value.firstOrNull { it.server.id == serverId }?.server ?: return
        val client = client(server) ?: return
        runCatching { if (on) client.star(songId) else client.unstar(songId) }
    }

    private fun setStatus(id: String, status: ServerStatus) {
        _entries.update { list -> list.map { if (it.server.id == id) it.copy(status = status) else it } }
    }

    private suspend fun publish() = withContext(Dispatchers.Default) {
        val servers = _entries.value.map { it.server }
        _tracks.value = servers.flatMap { server ->
            val client = client(server) ?: return@flatMap emptyList()
            tracks(server, client, songs.value[server.id].orEmpty())
        }
    }

    private fun cacheFile(id: String) = File(cacheDir, "$id.json")

    private fun readCache(id: String): List<SubsonicSong>? = runCatching {
        val file = cacheFile(id)
        if (!file.exists()) null else cacheJson.decodeFromString(songList, file.readText())
    }.getOrNull()

    private fun writeCache(id: String, list: List<SubsonicSong>) {
        cacheDir.mkdirs()
        val temp = File(cacheDir, "$id.json.tmp")
        temp.writeText(cacheJson.encodeToString(songList, list))
        temp.renameTo(cacheFile(id))
    }

    companion object {
        private const val PREFIX = "subsonic:"
        private val songList = ListSerializer(SubsonicSong.serializer())
        private val serverList = ListSerializer(SubsonicServer.serializer())
        private val cacheJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        fun parse(raw: String): List<SubsonicServer> =
            runCatching { cacheJson.decodeFromString(serverList, raw) }.getOrDefault(emptyList())

        fun encode(servers: List<SubsonicServer>): String = cacheJson.encodeToString(serverList, servers)

        fun mediaId(serverId: String, songId: String) = "$PREFIX$serverId:$songId"

        /** The server and song a media id names, if it's a server's. */
        fun parseMediaId(mediaId: String): Pair<String, String>? {
            if (!mediaId.startsWith(PREFIX)) return null
            val rest = mediaId.removePrefix(PREFIX)
            val split = rest.indexOf(':').takeIf { it > 0 } ?: return null
            return rest.substring(0, split) to rest.substring(split + 1)
        }

        /** A server's songs as library tracks, with signed addresses to stream them and their covers. */
        fun tracks(server: SubsonicServer, client: SubsonicClient, list: List<SubsonicSong>): List<LibraryTrack> =
            list.filter { it.title.isNotBlank() }.map { song ->
                val artist = song.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                LibraryTrack(
                    song = PlayableSong(
                        mediaId = mediaId(server.id, song.id),
                        uri = Uri.parse(client.streamUrl(song.id).toString()),
                        title = song.title,
                        artistName = artist,
                        albumTitle = song.album,
                        artworkUri = song.coverArt?.let { Uri.parse(client.coverArtUrl(it, 600).toString()) },
                        durationMs = song.duration?.let { it * 1000L },
                        identity = songIdentity(song.title, artist),
                        trackNumber = song.track,
                        year = song.year,
                        genre = song.genre,
                    ),
                    source = LibrarySourceKind.Server,
                    albumKey = "$PREFIX${server.id}:album:" + (song.albumId ?: song.album.orEmpty()),
                    albumArtist = song.displayAlbumArtist,
                    disc = song.discNumber,
                )
            }
    }
}

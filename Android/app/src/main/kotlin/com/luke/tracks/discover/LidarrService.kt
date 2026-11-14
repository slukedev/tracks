package com.luke.tracks.discover

import android.content.SharedPreferences
import com.luke.tracks.library.SecretBox
import com.luke.tracks.library.ServerAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.tracks_engine.CatalogException
import uniffi.tracks_engine.LidarrAddOptions
import uniffi.tracks_engine.LidarrAlbum
import uniffi.tracks_engine.LidarrApi
import uniffi.tracks_engine.LidarrArtist
import uniffi.tracks_engine.LidarrMonitor
import uniffi.tracks_engine.LidarrProfile
import uniffi.tracks_engine.LidarrQueueItem
import uniffi.tracks_engine.LidarrRootFolder
import uniffi.tracks_engine.LidarrSongState
import uniffi.tracks_engine.LidarrTrack
import uniffi.tracks_engine.WebRequest
import uniffi.tracks_engine.lidarrAlbumMatch
import uniffi.tracks_engine.lidarrCheck
import uniffi.tracks_engine.lidarrFollowedArtist
import uniffi.tracks_engine.lidarrLookupMatch
import uniffi.tracks_engine.lidarrParseAlbums
import uniffi.tracks_engine.lidarrParseArtists
import uniffi.tracks_engine.lidarrParseProfiles
import uniffi.tracks_engine.lidarrParseQueue
import uniffi.tracks_engine.lidarrParseRootFolders
import uniffi.tracks_engine.lidarrParseStatus
import uniffi.tracks_engine.lidarrParseTracks
import uniffi.tracks_engine.lidarrSongState
import java.io.IOException

/** Your Lidarr, as the app sees it. */
sealed interface LidarrState {
    data object Off : LidarrState
    data object Connecting : LidarrState
    data class Connected(val version: String) : LidarrState
    data object WrongKey : LidarrState
    /** The sealed key couldn't be opened (restored onto a new phone): enter it again. */
    data object NeedsKey : LidarrState
    data class Failed(val message: String) : LidarrState
}

/** How Lidarr files what Tracks asks for, and what it offers to choose from. */
data class LidarrSetup(
    val url: String,
    val qualityProfiles: List<LidarrProfile> = emptyList(),
    val metadataProfiles: List<LidarrProfile> = emptyList(),
    val rootFolders: List<LidarrRootFolder> = emptyList(),
    val qualityProfileId: Long? = null,
    val metadataProfileId: Long? = null,
    val rootFolderPath: String? = null,
    val monitor: LidarrMonitor = LidarrMonitor.ALL,
    val searchNow: Boolean = true,
) {
    val quality get() = qualityProfiles.firstOrNull { it.id == qualityProfileId } ?: qualityProfiles.firstOrNull()
    val metadata get() = metadataProfiles.firstOrNull { it.id == metadataProfileId } ?: metadataProfiles.firstOrNull()
    val root get() = rootFolders.firstOrNull { it.path == rootFolderPath } ?: rootFolders.firstOrNull()

    fun options(monitor: LidarrMonitor = this.monitor, searchNow: Boolean = this.searchNow): LidarrAddOptions? {
        val q = quality ?: return null
        val m = metadata ?: return null
        val r = root ?: return null
        return LidarrAddOptions(q.id, m.id, r.path, monitor, searchNow)
    }
}

/**
 * Lidarr: where it is, whether it answers, and asking it for artists and albums. The key is
 * sealed with a Keystore key, as server passwords are; only the address and choices are kept
 * in the clear.
 */
class LidarrService(
    private val prefs: SharedPreferences,
    private val secrets: SecretBox,
    private val transport: WebTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<LidarrState>(LidarrState.Off)
    val state: StateFlow<LidarrState> = _state.asStateFlow()

    private val _setup = MutableStateFlow<LidarrSetup?>(null)
    val setup: StateFlow<LidarrSetup?> = _setup.asStateFlow()

    private val _queue = MutableStateFlow<List<LidarrQueueItem>>(emptyList())
    val queue: StateFlow<List<LidarrQueueItem>> = _queue.asStateFlow()

    private var api: LidarrApi? = null
    private var artists: Pair<Long, List<LidarrArtist>>? = null
    private val collection = HashMap<Long, Triple<Long, List<LidarrAlbum>, List<LidarrTrack>>>()
    private val lock = Mutex()

    init {
        val url = prefs.getString(URL, null)
        if (url != null) {
            _setup.value = LidarrSetup(
                url = url,
                qualityProfileId = prefs.getLong(QUALITY, -1).takeIf { it >= 0 },
                metadataProfileId = prefs.getLong(METADATA, -1).takeIf { it >= 0 },
                rootFolderPath = prefs.getString(ROOT, null),
                monitor = runCatching { LidarrMonitor.valueOf(prefs.getString(MONITOR, "ALL")!!) }.getOrDefault(LidarrMonitor.ALL),
                searchNow = prefs.getBoolean(SEARCH_NOW, true),
            )
            val key = prefs.getString(KEY, null)?.let(secrets::open)
            if (key == null) _state.value = LidarrState.NeedsKey else api = LidarrApi(url, key)
        }
    }

    val isSetUp: Boolean get() = api != null
    val isConnected: Boolean get() = _state.value is LidarrState.Connected

    /** Checks an address and key without keeping them. Null when Lidarr answered, or why it didn't. */
    suspend fun test(address: String, key: String): String? {
        val url = ServerAddress.url(address)?.let(ServerAddress::stored) ?: return "That doesn't look like an address"
        val candidate = key.ifBlank { prefs.getString(KEY, null)?.let(secrets::open).orEmpty() }
        return runCatching { lidarrParseStatus(transport.send(LidarrApi(url, candidate).status())) }
            .exceptionOrNull()?.let(::describe)
    }

    /** Keeps an address and key that answered. A blank key keeps the one saved. */
    suspend fun save(address: String, key: String): String? {
        val url = ServerAddress.url(address)?.let(ServerAddress::stored) ?: return "That doesn't look like an address"
        val actual = key.ifBlank { prefs.getString(KEY, null)?.let(secrets::open).orEmpty() }
        test(url, actual)?.let { return it }
        prefs.edit().putString(URL, url).putString(KEY, secrets.seal(actual)).apply()
        api = LidarrApi(url, actual)
        lock.withLock { artists = null; collection.clear() }
        _setup.value = (_setup.value?.copy(url = url) ?: LidarrSetup(url))
        check()
        return null
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        api = null
        artists = null
        collection.clear()
        _setup.value = null
        _queue.value = emptyList()
        _state.value = LidarrState.Off
    }

    /** Checks Lidarr answers and reads its folders and profiles. */
    suspend fun check() {
        val api = api ?: return
        _state.value = LidarrState.Connecting
        _state.value = try {
            val status = lidarrParseStatus(transport.send(api.status()))
            val quality = runCatching { lidarrParseProfiles(transport.send(api.qualityProfiles())) }.getOrNull()
            val metadata = runCatching { lidarrParseProfiles(transport.send(api.metadataProfiles())) }.getOrNull()
            val folders = runCatching { lidarrParseRootFolders(transport.send(api.rootFolders())) }.getOrNull()
            _setup.update { setup ->
                setup?.copy(
                    qualityProfiles = quality ?: setup.qualityProfiles,
                    metadataProfiles = metadata ?: setup.metadataProfiles,
                    rootFolders = folders ?: setup.rootFolders,
                )
            }
            LidarrState.Connected(status.version)
        } catch (error: Exception) {
            if (error is CatalogException.WrongKey) LidarrState.WrongKey else LidarrState.Failed(describe(error))
        }
    }

    fun choose(quality: Long? = null, metadata: Long? = null, root: String? = null, monitor: LidarrMonitor? = null, searchNow: Boolean? = null) {
        _setup.update { s ->
            s?.copy(
                qualityProfileId = quality ?: s.qualityProfileId,
                metadataProfileId = metadata ?: s.metadataProfileId,
                rootFolderPath = root ?: s.rootFolderPath,
                monitor = monitor ?: s.monitor,
                searchNow = searchNow ?: s.searchNow,
            )
        }
        val s = _setup.value ?: return
        prefs.edit().apply {
            s.qualityProfileId?.let { putLong(QUALITY, it) }
            s.metadataProfileId?.let { putLong(METADATA, it) }
            s.rootFolderPath?.let { putString(ROOT, it) }
            putString(MONITOR, s.monitor.name)
            putBoolean(SEARCH_NOW, s.searchNow)
        }.apply()
    }

    suspend fun refreshQueue() {
        val api = api ?: return
        runCatching { lidarrParseQueue(transport.send(api.queue())) }.onSuccess { _queue.value = it }
    }

    // MARK: - What it has

    private suspend fun followed(force: Boolean = false): List<LidarrArtist> {
        val api = api ?: return emptyList()
        lock.withLock { artists?.let { (at, list) -> if (!force && now() - at < 10 * 60_000L) return list } }
        val list = lidarrParseArtists(transport.send(api.artists()))
        lock.withLock { artists = now() to list }
        return list
    }

    private suspend fun collectionOf(artistId: Long): Pair<List<LidarrAlbum>, List<LidarrTrack>> {
        val api = api ?: return emptyList<LidarrAlbum>() to emptyList()
        lock.withLock { collection[artistId]?.let { (at, a, t) -> if (now() - at < 2 * 60_000L) return a to t } }
        val albums = lidarrParseAlbums(transport.send(api.albumsOfArtist(artistId)))
        val tracks = lidarrParseTracks(transport.send(api.tracksOfArtist(artistId)))
        lock.withLock { collection[artistId] = Triple(now(), albums, tracks) }
        return albums to tracks
    }

    /** What Lidarr knows of a song, or null when it can't say (not connected, not answering). */
    suspend fun songState(title: String, artist: String): LidarrSongState? = runCatching {
        if (!isConnected) return null
        val list = followed()
        val match = lidarrFollowedArtist(artist, list) ?: return LidarrSongState.Missing
        val id = list[match.toInt()].id ?: return LidarrSongState.Missing
        val (albums, tracks) = collectionOf(id)
        lidarrSongState(title, albums, tracks)
    }.getOrNull()

    /** Whether Lidarr has filed or is after an album by this artist. */
    suspend fun albumState(title: String, artist: String): LidarrAlbum? = runCatching {
        if (!isConnected) return null
        val list = followed()
        val match = lidarrFollowedArtist(artist, list) ?: return null
        val id = list[match.toInt()].id ?: return null
        val (albums, _) = collectionOf(id)
        lidarrAlbumMatch(title, albums)?.let { albums[it.toInt()] }
    }.getOrNull()

    // MARK: - Asking for music

    /** Follows an artist, with the chosen settings. Returns what to tell the person. */
    suspend fun follow(artistName: String): String = guarded {
        val (artist, isNew) = followOrAdd(artistName, _setup.value?.options() ?: return@guarded NOT_READY)
        if (isNew) "Lidarr is following ${artist.artistName}" else "Lidarr already follows ${artist.artistName}"
    }

    /**
     * Asks for one album: the artist is followed if they aren't (without taking the rest of
     * their albums), the album is followed, and Lidarr goes to find it. A newly added
     * artist's albums take Lidarr a moment to fill in, so this waits for them.
     */
    suspend fun requestAlbum(title: String, artistName: String, waitMs: Long = 2_000, attempts: Int = 10): String = guarded {
        val api = api ?: return@guarded NOT_READY
        val quiet = _setup.value?.options(monitor = LidarrMonitor.NONE, searchNow = false) ?: return@guarded NOT_READY
        val (artist, _) = followOrAdd(artistName, quiet)
        val id = artist.id ?: return@guarded "Lidarr couldn't add $artistName"
        repeat(attempts) { attempt ->
            val albums = lidarrParseAlbums(transport.send(api.albumsOfArtist(id)))
            lidarrAlbumMatch(title, albums)?.let { index ->
                val album = albums[index.toInt()]
                lidarrCheck(transport.send(api.setMonitored(listOf(album.id), true)))
                lidarrCheck(transport.send(api.searchAlbums(listOf(album.id))))
                lock.withLock { collection.remove(id) }
                return@guarded "Lidarr is looking for $title"
            }
            if (attempt < attempts - 1) delay(waitMs)
        }
        "Lidarr doesn't know $title by $artistName yet"
    }

    private suspend fun followOrAdd(name: String, options: LidarrAddOptions): Pair<LidarrArtist, Boolean> {
        val api = api ?: throw IOException("Lidarr isn't set up")
        val list = followed()
        lidarrFollowedArtist(name, list)?.let { return list[it.toInt()] to false }
        val found = lidarrParseArtists(transport.send(api.lookUpArtists(name)))
        val match = lidarrLookupMatch(name, found) ?: throw CatalogException.Rejected("Lidarr couldn't find an artist called $name")
        val added = lidarrParseArtists(transport.send(api.addArtist(found[match.toInt()], options))).first()
        followed(force = true)
        return added to true
    }

    private inline fun guarded(block: () -> String): String = try {
        block()
    } catch (error: Exception) {
        describe(error)
    }

    companion object {
        private const val URL = "url"
        private const val KEY = "key"
        private const val QUALITY = "quality"
        private const val METADATA = "metadata"
        private const val ROOT = "root"
        private const val MONITOR = "monitor"
        private const val SEARCH_NOW = "searchNow"
        const val NOT_READY = "Lidarr isn't ready yet"

        fun describe(error: Throwable): String = when (error) {
            is CatalogException.WrongKey -> "Lidarr didn't accept that API key"
            is CatalogException.Rejected -> error.detail
            is CatalogException.WrongService -> "That address doesn't look like Lidarr. Check it and the port."
            is CatalogException.Http -> "Lidarr answered with an error"
            is IOException, is IllegalArgumentException -> "Couldn't reach Lidarr. Check the address and your network."
            else -> "Something went wrong with Lidarr"
        }
    }
}

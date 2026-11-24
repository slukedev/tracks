package com.luke.tracks.spotify

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.luke.tracks.playback.PcmOpener
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.tracks_engine.Disposable
import uniffi.tracks_engine.SpotifyAccountInterface
import uniffi.tracks_engine.SpotifyAlbum
import uniffi.tracks_engine.SpotifyAlbumPage
import uniffi.tracks_engine.SpotifyBitrate
import uniffi.tracks_engine.SpotifyConfig
import uniffi.tracks_engine.SpotifyException
import uniffi.tracks_engine.SpotifyLoginInterface
import uniffi.tracks_engine.SpotifyPlaylist
import uniffi.tracks_engine.SpotifyPlaylistPage
import uniffi.tracks_engine.SpotifySearchResults
import uniffi.tracks_engine.SpotifyStreamOptions
import uniffi.tracks_engine.SpotifyTrack
import uniffi.tracks_engine.SpotifyTrackPage
import uniffi.tracks_engine.songIdentity
import java.net.ServerSocket

/** Where Spotify stands for this phone. */
sealed interface SpotifyState {
    data object SignedOut : SpotifyState

    /** Signed in once, but Spotify no longer takes the saved sign-in. */
    data class NeedsSignIn(val name: String) : SpotifyState

    /** The browser is open on Spotify's sign-in page. */
    data object SigningIn : SpotifyState

    /** Back from the browser: signing in, then trying one song's audio key. */
    data object Checking : SpotifyState

    /** Signed in. [refused] when Spotify won't hand this account's audio keys to Tracks. */
    data class Connected(val name: String, val refused: Boolean = false) : SpotifyState

    /** Sign-in didn't finish; nothing is saved. */
    data class Failed(val problem: SpotifyProblem) : SpotifyState
}

/** Spotify's own quality names, and the engine's bitrate behind each. */
enum class SpotifyQuality(val label: String, val bitrate: SpotifyBitrate) {
    Normal("Normal", SpotifyBitrate.LOW),
    High("High", SpotifyBitrate.NORMAL),
    VeryHigh("Very High", SpotifyBitrate.HIGH),
}

/** The engine's Spotify, behind seams so tests never sign in or touch the network. */
interface SpotifyEngine {
    fun open(credentials: ByteArray, config: SpotifyConfig): SpotifyAccountInterface
    fun login(redirectUri: String): SpotifyLoginInterface
}

/**
 * Signs in and out, keeps the account open, and answers the library, search and the player.
 * Every engine call blocks for a bounded time, so each runs on [io].
 */
class SpotifyService(
    private val engine: SpotifyEngine,
    private val vault: SpotifyVault,
    private val settings: SharedPreferences,
    private val config: SpotifyConfig,
    private val scope: CoroutineScope,
    private val onCellular: () -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val freePort: () -> Int = ::loopbackPort,
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SpotifyState> = _state.asStateFlow()

    private val _quality = MutableStateFlow(readQuality())
    /** Quality on Wi-Fi, and on cellular. */
    val quality: StateFlow<Pair<SpotifyQuality, SpotifyQuality>> = _quality.asStateFlow()

    private val lock = Any()
    private var account: SpotifyAccountInterface? = null
    private var login: SpotifyLoginInterface? = null
    private var waiting: Job? = null
    private var closing: Job? = null

    private fun initialState(): SpotifyState {
        val name = vault.name ?: return SpotifyState.SignedOut
        return if (vault.credentials() != null) SpotifyState.Connected(name) else SpotifyState.NeedsSignIn(name)
    }

    // MARK: Signing in

    /** Starts a sign-in and returns the page to open in the browser, or null when it can't start. */
    fun beginSignIn(): String? {
        cancelSignIn(quietly = true)
        val started = runCatching { engine.login("http://127.0.0.1:${freePort()}/login") }
            .recoverCatching { engine.login("http://127.0.0.1:${freePort()}/login") }
            .getOrElse {
                _state.value = SpotifyState.Failed(SpotifyProblem.Offline)
                return null
            }
        login = started
        _state.value = SpotifyState.SigningIn
        waiting = scope.launch(io) { finishSignIn(started) }
        return started.url()
    }

    private suspend fun finishSignIn(login: SpotifyLoginInterface) {
        val callback = try {
            login.waitForRedirect(SIGN_IN_LIMIT_SECS)
        } catch (_: Exception) {
            // Cancelled, closed or timed out: back to where it started, with nothing said.
            if (this.login === login) {
                this.login = null
                _state.value = idleState()
            }
            return
        }
        closing?.cancel()
        _state.value = SpotifyState.Checking
        try {
            val signedIn = login.finish(callback, config)
            signedIn.connect()
            if (signedIn.isPremium() == false) {
                signedIn.signOut()
                _state.value = SpotifyState.Failed(SpotifyProblem.NeedsPremium)
                return
            }
            val name = signedIn.username()
            vault.save(signedIn.credentials(), name)
            synchronized(lock) {
                account?.let { release(it) }
                account = signedIn
            }
            _state.value = SpotifyState.Connected(name, refused = probeRefused(signedIn))
        } catch (error: Exception) {
            _state.value = SpotifyState.Failed(error.toSpotifyProblem().forSignIn())
        } finally {
            if (this.login === login) this.login = null
            (login as? Disposable)?.destroy()
        }
    }

    /**
     * Back in the app with the browser closed. The redirect may still be on its way, so this
     * waits a moment before taking it as a cancel.
     */
    fun browserClosed() {
        if (_state.value != SpotifyState.SigningIn) return
        closing?.cancel()
        closing = scope.launch {
            delay(CANCEL_GRACE_MS)
            if (_state.value == SpotifyState.SigningIn) cancelSignIn()
        }
    }

    fun cancelSignIn(quietly: Boolean = false) {
        val pending = login ?: return
        login = null
        pending.cancel()
        waiting?.cancel()
        if (!quietly || _state.value == SpotifyState.SigningIn) _state.value = idleState()
    }

    /** Done with a failed sign-in's message. */
    fun dismissProblem() {
        if (_state.value is SpotifyState.Failed) _state.value = idleState()
    }

    private fun idleState(): SpotifyState = vault.name?.let(SpotifyState::NeedsSignIn) ?: SpotifyState.SignedOut

    /** Asks Spotify again for an audio key, for an account it refused. */
    fun tryAgain() {
        val current = _state.value as? SpotifyState.Connected ?: return
        _state.value = SpotifyState.Checking
        scope.launch(io) {
            _state.value = try {
                current.copy(refused = probeRefused(account()))
            } catch (error: Exception) {
                stateAfter(error, current)
            }
        }
    }

    /**
     * Whether Spotify refuses this account's audio keys, asked with one of its saved songs.
     * Anything else that goes wrong says nothing either way, and the player finds out later.
     */
    private fun probeRefused(account: SpotifyAccountInterface): Boolean {
        val uri = runCatching { account.savedTracks(0u, 1u).items.firstOrNull()?.uri }.getOrNull() ?: return false
        return try {
            SpotifyPcm(account.openStream(uri, streamOptions())).close()
            false
        } catch (_: SpotifyException.AudioKeyRefused) {
            true
        } catch (_: Exception) {
            false
        }
    }

    // MARK: Signing out

    /** Ends the session and forgets the sign-in. History keeps its plays. */
    fun signOut() {
        cancelSignIn(quietly = true)
        val open = synchronized(lock) { account.also { account = null } }
        vault.clear()
        _state.value = SpotifyState.SignedOut
        if (open != null) {
            scope.launch(io) {
                runCatching { open.signOut() }
                release(open)
            }
        }
    }

    // MARK: The account

    /**
     * The open account, opened from the saved sign-in on first use. Blocks; call on [io] or
     * a loading thread. Spotify may rotate the credentials on connect, so they're saved again.
     */
    fun account(): SpotifyAccountInterface = synchronized(lock) {
        account?.let { return it }
        val credentials = vault.credentials() ?: throw SpotifyPlaybackException(SpotifyProblem.SignedOut)
        try {
            val opened = engine.open(credentials, config)
            opened.connect()
            vault.save(opened.credentials(), opened.username())
            account = opened
            opened
        } catch (error: SpotifyException.Auth) {
            vault.forgetCredentials()
            vault.name?.let { _state.value = SpotifyState.NeedsSignIn(it) }
            throw SpotifyPlaybackException(SpotifyProblem.SignedOut, error)
        }
    }

    private fun stateAfter(error: Exception, current: SpotifyState): SpotifyState = when (error.toSpotifyProblem()) {
        SpotifyProblem.SignedOut -> idleState()
        else -> current
    }

    /** The player hit a problem with the account itself. */
    fun playbackFailed(problem: SpotifyProblem) {
        val current = _state.value
        when (problem) {
            SpotifyProblem.Refused -> if (current is SpotifyState.Connected) _state.value = current.copy(refused = true)
            SpotifyProblem.SignedOut -> _state.value = idleState()
            else -> Unit
        }
    }

    val audio = PcmOpener { uri ->
        val account = spotifyCall { account() }
        val stream = spotifyCall { account.openStream(uri.toString(), streamOptions()) }
        val connected = _state.value
        if (connected is SpotifyState.Connected && connected.refused) _state.value = connected.copy(refused = false)
        SpotifyPcm(stream)
    }

    private fun streamOptions(): SpotifyStreamOptions {
        val (wifi, cellular) = _quality.value
        return SpotifyStreamOptions(bitrate = (if (onCellular()) cellular else wifi).bitrate, normalise = true)
    }

    fun setQuality(wifi: SpotifyQuality, cellular: SpotifyQuality) {
        _quality.value = wifi to cellular
        settings.edit {
            putString(WIFI_QUALITY, wifi.name)
            putString(CELLULAR_QUALITY, cellular.name)
        }
    }

    private fun readQuality(): Pair<SpotifyQuality, SpotifyQuality> {
        fun read(key: String, default: SpotifyQuality) =
            settings.getString(key, null)?.let { runCatching { SpotifyQuality.valueOf(it) }.getOrNull() } ?: default
        return read(WIFI_QUALITY, SpotifyQuality.VeryHigh) to read(CELLULAR_QUALITY, SpotifyQuality.High)
    }

    // MARK: Library and search

    private suspend fun <T> call(block: SpotifyAccountInterface.() -> T): T = withContext(io) {
        try {
            account().block()
        } catch (error: SpotifyException.Auth) {
            synchronized(lock) {
                account?.let { release(it) }
                account = null
            }
            vault.forgetCredentials()
            _state.value = idleState()
            throw SpotifyPlaybackException(SpotifyProblem.SignedOut, error)
        }
    }

    suspend fun savedAlbums(offset: Int): SpotifyAlbumPage = call { savedAlbums(offset.toUInt(), PAGE) }
    suspend fun playlists(offset: Int): SpotifyPlaylistPage = call { playlists(offset.toUInt(), PAGE) }
    suspend fun likedSongs(offset: Int): SpotifyTrackPage = call { savedTracks(offset.toUInt(), PAGE) }
    suspend fun album(uri: String): SpotifyAlbum = call { album(uri) }
    suspend fun playlist(uri: String): SpotifyPlaylist = call { playlist(uri) }
    suspend fun search(query: String): SpotifySearchResults = call { search(query, 0u, SEARCH_LIMIT) }

    /** Songs by address, in order, a batch at a time; ones gone from Spotify are left out. */
    suspend fun songs(uris: List<String>): List<PlayableSong> =
        uris.chunked(TRACK_BATCH).flatMap { batch -> call { tracks(batch) } }.map { it.toPlayableSong() }

    private fun release(account: SpotifyAccountInterface) {
        (account as? Disposable)?.destroy()
    }

    companion object {
        const val WIFI_QUALITY = "quality.wifi"
        const val CELLULAR_QUALITY = "quality.cellular"
        const val PAGE = 50u
        const val SEARCH_LIMIT = 10u
        const val TRACK_BATCH = 50
        const val SIGN_IN_LIMIT_SECS = 600u
        const val CANCEL_GRACE_MS = 1_500L

        /** A loopback port nothing else holds now; the engine claims it straight after. */
        fun loopbackPort(): Int = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
    }
}

/** A sign-in's failure as the page shows it: anything but Premium and the network is a network-shaped retry. */
private fun SpotifyProblem.forSignIn(): SpotifyProblem = when (this) {
    SpotifyProblem.NeedsPremium, SpotifyProblem.Offline -> this
    else -> SpotifyProblem.Offline
}

fun SpotifyTrack.toPlayableSong(): PlayableSong {
    val artist = artists.joinToString(", ") { it.name }
    return PlayableSong(
        mediaId = uri,
        uri = Uri.parse(uri),
        title = title,
        artistName = artist,
        albumTitle = album?.title,
        artworkUri = artworkUrl?.let(Uri::parse),
        durationMs = durationMs.toLong().takeIf { it > 0 },
        identity = songIdentity(title, artist),
        trackNumber = trackNumber.toInt().takeIf { it > 0 },
    )
}

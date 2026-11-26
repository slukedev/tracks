package com.luke.tracks.applemusic

import android.content.Intent
import com.luke.tracks.library.LibrarySourceKind
import com.luke.tracks.library.LibraryTrack
import com.luke.tracks.net.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** Where Apple Music stands, worst first. */
sealed interface AppleMusicState {
    data object SignedOut : AppleMusicState

    /** Apple's sign-in screen is open. */
    data object SigningIn : AppleMusicState

    /** Signed in; checking the account with Apple. */
    data object Checking : AppleMusicState

    /** Signing in didn't work; nothing is kept. */
    data class Failed(val problem: AppleMusicProblem) : AppleMusicState

    /** Was signed in, and Apple stopped taking it: the source stays, asking to sign in. */
    data class NeedsSignIn(val problem: AppleMusicProblem) : AppleMusicState

    data class Connected(val storefront: String) : AppleMusicState
}

/**
 * Apple Music on Android: signing in through Apple's SDK, the account's storefront, the library
 * as the Apple Music API lists it, and the player. The Music User Token is kept sealed in the
 * Keystore ([AppleMusicVault]); the developer token comes from a [DeveloperTokenProvider].
 */
class AppleMusicService(
    val tokens: DeveloperTokenProvider,
    private val vault: AppleMusicVault,
    private val sdk: AppleMusicSdk?,
    private val scope: CoroutineScope,
    /** Whether the Apple Music app is on the phone; sign-in goes through it. */
    private val appInstalled: () -> Boolean,
    private val identity: (title: String, artist: String) -> String,
    /** Where the library is kept between starts; null keeps it in memory only. */
    private val cache: File?,
    apiFor: (userToken: () -> String?) -> AppleMusicApi,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val api: AppleMusicApi = apiFor(vault::userToken)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AppleMusicState> = _state.asStateFlow()

    private val _library = MutableStateFlow<List<LibraryTrack>>(emptyList())

    /** The library's songs that can play here, for copies of a song and Play From. */
    val library: StateFlow<List<LibraryTrack>> = _library.asStateFlow()

    val storefront: String? get() = (state.value as? AppleMusicState.Connected)?.storefront ?: vault.storefront

    /** Whether a developer token can be had, from a paired server or the build; sign-in needs one. */
    val keyAvailable: StateFlow<Boolean> = tokens.availabilityChanges
        .map { tokens.available }
        .stateIn(scope, SharingStarted.Eagerly, tokens.available)

    private val musicKitTokens = object : AppleMusicSdk.Tokens {
        override fun developerToken() = tokens.cached()?.jwt.also {
            if (it == null) Logs.w("MusicKit asked for the developer token and none is held yet")
        }
        override fun userToken() = vault.userToken()
        override fun playbackStorefront() = vault.playbackStorefront
    }

    private var musicKit: AppleMusicPlayer? = null

    /** MusicKit's player, made on first use; null in builds without the SDK or while signed out. */
    @Synchronized
    fun player(): AppleMusicPlayer? {
        val sdk = sdk ?: return null
        if (vault.userToken() == null) return null
        musicKit?.let { return it }
        return runCatching { sdk.player(musicKitTokens) }
            .onFailure { Logs.w("MusicKit's player didn't start", it) }
            .getOrNull()
            ?.also { musicKit = it }
    }

    val playback = AppleMusicPlayback(::player, canPlay = { sdk != null && vault.userToken() != null })

    private var renewing: Job? = null

    private var refreshing: Job? = null

    private fun initialState(): AppleMusicState {
        val storefront = vault.storefront
        return if (vault.userToken() != null && storefront != null) AppleMusicState.Connected(storefront) else AppleMusicState.SignedOut
    }

    /** Loads the kept library and checks with Apple again. */
    fun start() {
        Logs.i("Apple Music: starting " + describe(_state.value) + ", Music User Token " + (if (vault.userToken() != null) "kept" else "not kept"))
        if (_state.value !is AppleMusicState.Connected) return
        scope.launch {
            readCache()?.let { (_, songs) -> _library.value = tracks(songs) }
            verify(quiet = true)
        }
    }

    /** Apple's sign-in screen, or null when it can't open (and [state] says why). */
    suspend fun beginSignIn(): Intent? {
        val sdk = sdk ?: return null
        if (!appInstalled()) {
            _state.value = AppleMusicState.Failed(AppleMusicProblem.NeedsApp)
            return null
        }
        val token = tokens.token()
        if (token == null) {
            _state.value = AppleMusicState.Failed(if (tokens.available) AppleMusicProblem.Expired else AppleMusicProblem.NoKey)
            return null
        }
        _state.value = AppleMusicState.SigningIn
        return runCatching { sdk.signInIntent(token.jwt) }
            .onFailure {
                Logs.w("Apple Music sign-in didn't open", it)
                _state.value = AppleMusicState.Failed(AppleMusicProblem.NeedsApp)
            }
            .getOrNull()
    }

    /** Back from Apple's sign-in screen. */
    fun signInFinished(data: Intent?) = signInFinished(sdk?.signInResult(data) ?: SignInResult.Cancelled)

    fun signInFinished(result: SignInResult) {
        when (result) {
            is SignInResult.Token -> {
                vault.save(result.musicUserToken)
                vault.playbackStorefront = result.playbackStorefront
                musicKit?.tokensChanged()
                _state.value = AppleMusicState.Checking
                scope.launch { verify(quiet = false) }
            }
            // Cancel isn't an error: back to where it was.
            SignInResult.Cancelled -> _state.value = initialState().takeIf { it is AppleMusicState.Connected }
                ?: (if (vault.storefront != null) AppleMusicState.NeedsSignIn(AppleMusicProblem.SignedOut) else AppleMusicState.SignedOut)
            is SignInResult.Failed -> _state.value = AppleMusicState.Failed(result.problem)
        }
    }

    fun tryAgain() {
        if (vault.userToken() == null) {
            _state.value = AppleMusicState.SignedOut
            return
        }
        _state.value = AppleMusicState.Checking
        scope.launch { verify(quiet = false) }
    }

    /** The problem was read; the page goes back to signing in. */
    fun dismissProblem() {
        if (_state.value is AppleMusicState.Failed) _state.value = AppleMusicState.SignedOut
    }

    /** Asks the paired servers again for a developer token, as after adding a MusicKit key. */
    fun findKey() {
        tokens.refresh()
        dismissProblem()
    }

    /** Asks Apple for the account's storefront, which proves the token, then the library. */
    private suspend fun verify(quiet: Boolean) {
        try {
            Logs.i("Apple Music: checking the account (storefront)")
            val storefront = api.storefront()
            vault.storefront = storefront
            _state.value = AppleMusicState.Connected(storefront)
            Logs.i("Apple Music: connected, storefront $storefront")
            refreshLibrary(force = !quiet)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val problem = error.toAppleMusicProblem()
            Logs.w("Apple Music: the account check failed (${problem.name})" + if (quiet) ", at start" else "", error)
            when {
                problem.accountWide -> accountProblem(problem)
                // Offline at start: stays connected, and works once there's a connection.
                quiet -> Unit
                else -> {
                    if (vault.storefront == null) vault.forgetToken()
                    _state.value = if (vault.storefront != null && vault.userToken() != null) {
                        AppleMusicState.Connected(vault.storefront!!)
                    } else {
                        AppleMusicState.Failed(problem)
                    }
                }
            }
        }
    }

    /**
     * A song or request failed in a way that says something about the account. A refused Music
     * User Token is renewed with Apple first, as MusicKit's guide asks; only if that fails does
     * the source ask to sign in again.
     */
    fun playbackFailed(problem: AppleMusicProblem) {
        if (!problem.accountWide) return
        if (problem != AppleMusicProblem.SignedOut || sdk == null || vault.userToken() == null) {
            accountProblem(problem)
            return
        }
        if (renewing?.isActive == true) return
        renewing = scope.launch {
            when (val renewed = runCatching { sdk.refreshUserToken(musicKitTokens) }.getOrElse { SignInResult.Failed(AppleMusicProblem.SignedOut) }) {
                is SignInResult.Token -> {
                    vault.save(renewed.musicUserToken)
                    musicKit?.tokensChanged()
                }
                is SignInResult.Failed -> accountProblem(renewed.problem.takeIf { it.accountWide } ?: AppleMusicProblem.SignedOut)
                SignInResult.Cancelled -> accountProblem(AppleMusicProblem.SignedOut)
            }
        }
    }

    private fun accountProblem(problem: AppleMusicProblem) {
        Logs.w("Apple Music: account problem ${problem.name}" + if (problem == AppleMusicProblem.SignedOut) ", the Music User Token is let go" else "")
        if (problem == AppleMusicProblem.SignedOut) vault.forgetToken()
        val listed = vault.storefront != null
        _state.value = if (listed) AppleMusicState.NeedsSignIn(problem) else AppleMusicState.Failed(problem)
    }

    fun signOut() {
        refreshing?.cancel()
        renewing?.cancel()
        vault.clear()
        // MusicKit's guide: stop, hand it the now empty tokens, then drop its kept queue and state.
        synchronized(this) {
            musicKit?.let {
                runCatching { it.stop() }
                runCatching { it.tokensChanged() }
                runCatching { it.release() }
            }
            musicKit = null
        }
        sdk?.let { runCatching { it.forget() } }
        _library.value = emptyList()
        scope.launch(Dispatchers.IO) { cache?.delete() }
        _state.value = AppleMusicState.SignedOut
    }

    /** Gets the library's songs again when the kept copy is older than [LIBRARY_FRESH_MS], or when [force]d. */
    fun refreshLibrary(force: Boolean = false) {
        if (refreshing?.isActive == true) return
        refreshing = scope.launch {
            val kept = readCache()
            if (!force && kept != null && now() - kept.first < LIBRARY_FRESH_MS) {
                if (_library.value.isEmpty()) _library.value = tracks(kept.second)
                return@launch
            }
            try {
                val songs = mutableListOf<AppleMusicSong>()
                var offset: Int? = 0
                var pages = 0
                while (offset != null && pages++ < MAX_LIBRARY_PAGES) {
                    val page = api.librarySongs(offset)
                    songs += page.items
                    offset = page.nextOffset
                }
                _library.value = tracks(songs)
                writeCache(songs)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Logs.w("Apple Music library didn't load", error)
                error.appleMusicProblem()?.let(::playbackFailed)
                if (_library.value.isEmpty() && kept != null) _library.value = tracks(kept.second)
            }
        }
    }

    private suspend fun tracks(songs: List<AppleMusicSong>): List<LibraryTrack> = withContext(Dispatchers.Default) {
        songs.filter { it.playable }.distinctBy { it.catalogId }.map { song ->
            LibraryTrack(
                song = song.toPlayableSong(identity),
                source = LibrarySourceKind.AppleMusic,
                albumKey = "applemusic:album:" + song.albumTitle.orEmpty() + "|" + song.artistName,
                disc = song.discNumber,
            )
        }
    }

    @Serializable
    private data class Kept(val savedAtMs: Long, val songs: List<AppleMusicSong>)

    private suspend fun readCache(): Pair<Long, List<AppleMusicSong>>? = withContext(Dispatchers.IO) {
        val file = cache?.takeIf { it.exists() } ?: return@withContext null
        runCatching { WireJson.decodeFromString(Kept.serializer(), file.readText()) }.getOrNull()?.let { it.savedAtMs to it.songs }
    }

    private suspend fun writeCache(songs: List<AppleMusicSong>) = withContext(Dispatchers.IO) {
        val file = cache ?: return@withContext
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(WireJson.encodeToString(Kept.serializer(), Kept(now(), songs)))
            temp.renameTo(file)
        }.onFailure { Logs.w("Apple Music library wasn't kept", it) }
    }

    companion object {
        /** A state for the log, without anything secret. */
        fun describe(state: AppleMusicState): String = when (state) {
            is AppleMusicState.Connected -> "connected (storefront ${state.storefront})"
            is AppleMusicState.NeedsSignIn -> "needing sign-in (${state.problem.name})"
            is AppleMusicState.Failed -> "failed (${state.problem.name})"
            AppleMusicState.Checking -> "checking"
            AppleMusicState.SigningIn -> "signing in"
            AppleMusicState.SignedOut -> "signed out"
        }

        /** How long a kept library is used before it's fetched again. */
        const val LIBRARY_FRESH_MS = 6 * 60 * 60_000L

        /** 50,000 songs, 100 at a time. */
        const val MAX_LIBRARY_PAGES = 500
    }
}

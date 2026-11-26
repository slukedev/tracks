package com.luke.tracks.applemusic

import com.luke.tracks.net.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** What Play shows from Apple Music. */
sealed interface AppleMusicShelvesState {
    /** Apple Music isn't signed in here: Play shows nothing of it. */
    data object Off : AppleMusicShelvesState

    data object Loading : AppleMusicShelvesState

    /**
     * What came back. Empty lists mean Apple Music had nothing; [note] says what didn't load
     * when only part of it did.
     */
    data class Ready(
        val recent: List<AppleMusicCollection>,
        val picks: List<AppleMusicRecommendation>,
        val note: String? = null,
    ) : AppleMusicShelvesState {
        val isEmpty: Boolean get() = recent.isEmpty() && picks.isEmpty()
    }

    /** Nothing loaded, and why, in a sentence for the shelf. */
    data class Problem(val message: String) : AppleMusicShelvesState
}

/**
 * Apple Music's shelves on Play, as the iPhone has them: what you played lately in Apple Music,
 * and its recommendations for you, topped up with the storefront's most played albums when it
 * gives fewer than [PICK_ROWS] rows. Loads run in the app's scope, so leaving Play doesn't cut
 * one short, and every load ends in songs, a word on why there are none, or a problem with
 * Try again. Each step is logged under the Tracks tag.
 */
class AppleMusicShelves(
    private val service: AppleMusicService,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<AppleMusicShelvesState>(AppleMusicShelvesState.Off)
    val state: StateFlow<AppleMusicShelvesState> = _state.asStateFlow()

    @Volatile private var job: Job? = null

    /** A load is under way; cleared just before it publishes, so Try again on what it shows always works. */
    @Volatile private var busy = false
    @Volatile private var loadedAt = 0L
    @Volatile private var lastAccount: AppleMusicState? = null

    init {
        scope.launch {
            service.state.collect { account -> follow(account) }
        }
    }

    private fun follow(account: AppleMusicState) {
        val before = lastAccount
        lastAccount = account
        if (before != account) Logs.i("Apple Music shelves: Apple Music is " + AppleMusicService.describe(account))
        when (account) {
            is AppleMusicState.Connected -> load(force = before !is AppleMusicState.Connected && before != null)
            AppleMusicState.Checking, AppleMusicState.SigningIn ->
                if (_state.value !is AppleMusicShelvesState.Ready) _state.value = AppleMusicShelvesState.Loading
            is AppleMusicState.NeedsSignIn -> {
                stop()
                _state.value = AppleMusicShelvesState.Problem(needsSignIn(account.problem))
            }
            // Never signed in here, or signing in didn't work: Apple Music isn't a source yet.
            AppleMusicState.SignedOut, is AppleMusicState.Failed -> {
                stop()
                loadedAt = 0
                _state.value = AppleMusicShelvesState.Off
            }
        }
    }

    private fun stop() {
        job?.cancel()
        busy = false
    }

    /** Try again: checks the account again when it's what failed, otherwise loads again. */
    fun retry() {
        Logs.i("Apple Music shelves: Try again")
        if (service.state.value is AppleMusicState.NeedsSignIn) {
            _state.value = AppleMusicShelvesState.Loading
            service.tryAgain()
        } else {
            load(force = true)
        }
    }

    /** Loads the shelves, unless they loaded in the last [FRESH_MS] or a load is under way. */
    fun load(force: Boolean = false) {
        val account = service.state.value
        val storefront = (account as? AppleMusicState.Connected)?.storefront
        if (storefront == null) {
            Logs.i("Apple Music shelves: not loading, Apple Music is " + AppleMusicService.describe(account))
            return
        }
        if (busy) return
        val current = _state.value
        if (!force && current is AppleMusicShelvesState.Ready && current.note == null && now() - loadedAt < FRESH_MS) return
        if (current !is AppleMusicShelvesState.Ready) _state.value = AppleMusicShelvesState.Loading
        busy = true
        job = scope.launch {
            val started = now()
            Logs.i("Apple Music shelves: loading (storefront $storefront)")
            val result = try {
                withTimeoutOrNull(TIMEOUT_MS) { fetch(storefront) }
                    ?: AppleMusicShelvesState.Problem("Apple Music took too long to answer. Check your connection.").also {
                        Logs.w("Apple Music shelves: gave up after ${TIMEOUT_MS / 1000} s")
                    }
            } catch (error: CancellationException) {
                Logs.i("Apple Music shelves: load stopped")
                busy = false
                throw error
            } catch (error: Exception) {
                Logs.w("Apple Music shelves: load failed", error)
                AppleMusicShelvesState.Problem(error.toAppleMusicProblem().shelfMessage())
            }
            if (result is AppleMusicShelvesState.Ready) loadedAt = now()
            busy = false
            // Keep what was shown when a later load fails.
            val previous = _state.value
            _state.value = if (result is AppleMusicShelvesState.Problem && previous is AppleMusicShelvesState.Ready && !previous.isEmpty) {
                previous.copy(note = result.message)
            } else {
                result
            }
            Logs.i("Apple Music shelves: settled in ${now() - started} ms: " + summary(_state.value))
        }
    }

    private suspend fun fetch(storefront: String): AppleMusicShelvesState = coroutineScope {
        val recentAsk = async { attempt("recently played") { service.api.recentlyPlayed() } }
        val picksAsk = async { attempt("recommendations") { service.api.recommendations() } }
        val recent = recentAsk.await()
        val picks = picksAsk.await()
        val recentItems = recent.getOrNull()
        var rows = picks.getOrNull()?.let { picksBesides(it, recentItems.orEmpty()) }
        var chartFailure: Throwable? = null
        if ((rows?.size ?: 0) < PICK_ROWS) {
            Logs.i("Apple Music shelves: ${rows?.size ?: 0} recommendation rows, adding the top albums chart")
            attempt("top albums") { service.api.topAlbums(storefront) }
                .onSuccess { albums ->
                    if (albums.isNotEmpty()) rows = rows.orEmpty() + AppleMusicRecommendation(TOP_ALBUMS_ID, "Top albums on Apple Music", albums)
                }
                .onFailure { chartFailure = it }
        }
        val failures = listOfNotNull(recent.exceptionOrNull(), picks.exceptionOrNull(), chartFailure)
        failures.mapNotNull { it.appleMusicProblem() }.firstOrNull { it.accountWide }?.let { problem ->
            Logs.w("Apple Music shelves: Apple Music says ${problem.name}, telling the account")
            service.playbackFailed(problem)
        }
        if (recentItems == null && rows.isNullOrEmpty()) {
            return@coroutineScope AppleMusicShelvesState.Problem(failures.first().toAppleMusicProblem().shelfMessage())
        }
        val note = when {
            picks.isFailure -> "Apple Music's recommendations didn't load. " + picks.exceptionOrNull()!!.toAppleMusicProblem().shelfMessage()
            recent.isFailure -> "What you played lately in Apple Music didn't load. " + recent.exceptionOrNull()!!.toAppleMusicProblem().shelfMessage()
            else -> null
        }
        AppleMusicShelvesState.Ready(recentItems.orEmpty().take(RECENT_SHOWN), rows.orEmpty().take(PICK_ROWS), note)
    }

    private suspend fun <T> attempt(what: String, block: suspend () -> List<T>): Result<List<T>> = try {
        val items = block()
        Logs.i("Apple Music shelves: $what: ${items.size}")
        Result.success(items)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Logs.w("Apple Music shelves: $what didn't load (${error.toAppleMusicProblem().name})", error)
        Result.failure(error)
    }

    private fun summary(state: AppleMusicShelvesState): String = when (state) {
        is AppleMusicShelvesState.Ready -> "${state.recent.size} recently played, ${state.picks.size} rows (" +
            state.picks.joinToString { "${it.title}: ${it.items.size}" } + ")" + (state.note?.let { ", note: $it" } ?: "")
        is AppleMusicShelvesState.Problem -> "problem: ${state.message}"
        AppleMusicShelvesState.Loading -> "loading"
        AppleMusicShelvesState.Off -> "off"
    }

    companion object {
        /** How many rows of recommendations Play shows, as on iPhone. */
        const val PICK_ROWS = 3
        const val RECENT_SHOWN = 15
        const val FRESH_MS = 5 * 60_000L
        const val TIMEOUT_MS = 45_000L
        const val TOP_ALBUMS_ID = "tracks.topAlbums"

        /**
         * Apple Music's rows less the ones Play already shows: its own Recently Played, and rows
         * that mostly repeat what you played lately.
         */
        fun picksBesides(rows: List<AppleMusicRecommendation>, recent: List<AppleMusicCollection>): List<AppleMusicRecommendation> {
            val recentIds = recent.map { it.id }.toSet()
            return rows.filter { row ->
                val repeats = row.items.count { it.id in recentIds }
                repeats * 2 < row.items.size && !row.title.lowercase(Locale.ROOT).contains("recently played")
            }
        }

        fun needsSignIn(problem: AppleMusicProblem): String = when (problem) {
            AppleMusicProblem.SignedOut -> "Apple Music signed you out. Sign in again in Settings, under Sources, to see its recommendations."
            AppleMusicProblem.NoSubscription -> "Apple Music's recommendations need an Apple Music subscription."
            AppleMusicProblem.Expired -> "Apple Music didn't accept this app's developer token. Check the Apple Music key on your server, then try again."
            else -> problem.shelfMessage()
        }
    }
}

/** The sentence a shelf shows for a problem. */
fun AppleMusicProblem.shelfMessage(): String = when (this) {
    AppleMusicProblem.Unavailable -> "Apple Music didn't send anything Tracks could show."
    AppleMusicProblem.SignedOut -> "Apple Music didn't accept your sign-in. Sign in again in Settings, under Sources."
    AppleMusicProblem.Expired -> "Apple Music didn't accept this app's developer token. Check the Apple Music key on your server, then try again."
    else -> message
}

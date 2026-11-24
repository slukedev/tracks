package com.luke.tracks.lastfm

import kotlinx.coroutines.flow.flatMapLatest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.luke.tracks.AppGraph
import com.luke.tracks.BuildConfig
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.tracks_engine.HttpMethod
import uniffi.tracks_engine.HttpRequest
import uniffi.tracks_engine.HttpResponse
import uniffi.tracks_engine.LastFmClient
import uniffi.tracks_engine.LastFmException
import uniffi.tracks_engine.LastFmSession
import uniffi.tracks_engine.Scrobble
import uniffi.tracks_engine.ScrobbleOutcome
import uniffi.tracks_engine.lastfmErrorIsAccountProblem
import uniffi.tracks_engine.lastfmErrorIsRetryable
import uniffi.tracks_engine.lastfmParseNowPlaying
import uniffi.tracks_engine.lastfmParseRecentTracks
import uniffi.tracks_engine.lastfmParseScrobbles
import uniffi.tracks_engine.lastfmParseSession
import uniffi.tracks_engine.lastfmParseToken
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * Last.fm for Android: the shared engine builds and reads every request (signing included),
 * and this sends them with OkHttp. Connecting is Last.fm's desktop flow, which has no
 * redirect back: Tracks opens the approval page in a Custom Tab and asks Last.fm every few
 * seconds whether it has been approved, and again the moment you come back to the app.
 */
class LastFm internal constructor(private val context: Context, private val graph: AppGraph) {
    sealed interface Connection {
        data object NotConfigured : Connection
        data object Idle : Connection
        data object Waiting : Connection
        data class Connected(val username: String) : Connection
        data class Failed(val message: String) : Connection
    }

    sealed interface Import {
        data object Idle : Import
        data class Running(val added: Int) : Import
        data class Done(val added: Int) : Import
        data class Failed(val message: String) : Import
    }

    val isConfigured: Boolean = BuildConfig.LASTFM_API_KEY.isNotBlank() && BuildConfig.LASTFM_SECRET.isNotBlank()

    private val vault = SessionVault(context)
    private val client: LastFmClient? by lazy {
        if (!isConfigured) null
        else LastFmClient(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_SECRET, USER_AGENT)
    }

    private val _connection = MutableStateFlow(if (isConfigured) Connection.Idle else Connection.NotConfigured)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    init {
        // Read off the main thread; the account's name is all that's needed to show it.
        if (isConfigured) graph.scope.launch(Dispatchers.IO) {
            if (vault.username() == null) return@launch
            val session = vault.load()
            if (session != null) {
                _connection.compareAndSet(Connection.Idle, Connection.Connected(session.username))
            } else {
                // Restored from a backup onto a new phone: the Keystore key didn't come along.
                vault.clear()
                _connection.compareAndSet(Connection.Idle, Connection.Failed(RECONNECT))
            }
        }
    }

    private val _import = MutableStateFlow<Import>(Import.Idle)
    val import: StateFlow<Import> = _import.asStateFlow()

    /** The last thing that went wrong sending scrobbles, cleared when a drain succeeds. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var connecting: Job? = null
    private val poke = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val spotifyPrefs = context.getSharedPreferences("lastfm_spotify", Context.MODE_PRIVATE)
    private val _scrobbleSpotify = MutableStateFlow(BuildConfig.SPOTIFY && spotifyPrefs.getBoolean("scrobbleSpotify", false))

    /**
     * Whether plays from Spotify go to Last.fm too. Off by default, since Spotify scrobbles
     * them itself for anyone who linked it; only builds with Spotify offer it.
     */
    val scrobbleSpotify: StateFlow<Boolean> = _scrobbleSpotify.asStateFlow()

    fun setScrobbleSpotify(on: Boolean) {
        if (!BuildConfig.SPOTIFY) return
        _scrobbleSpotify.value = on
        spotifyPrefs.edit().putBoolean("scrobbleSpotify", on).apply()
        if (on) ScrobbleWorker.enqueue(context)
    }

    /** Plays still to go to Last.fm. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun owedCount(): Flow<Int> = scrobbleSpotify.flatMapLatest { spotify ->
        graph.history.database.plays().owedScrobbleCount(owedSince(), spotify)
    }

    /** Starts connecting: asks for a token and opens Last.fm's approval page. */
    fun connect(activity: Context) {
        val client = client ?: run { _connection.value = Connection.NotConfigured; return }
        connecting?.cancel()
        _connection.value = Connection.Waiting
        connecting = graph.scope.launch {
            try {
                val token = lastfmParseToken(send(client.authGetToken()))
                withContext(Dispatchers.Main) { open(activity, client.authorisationUrl(token)) }
                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                var blip: String? = null
                while (System.currentTimeMillis() < deadline) {
                    withTimeoutOrNull(POLL_MS) { poke.first() }
                    try {
                        val session = lastfmParseSession(send(client.authGetSession(token)))
                        vault.save(session)
                        graph.settings.update { it.copy(lastFmUser = session.username) }
                        _connection.value = Connection.Connected(session.username)
                        _problem.value = null
                        ScrobbleWorker.enqueue(context)
                        return@launch
                    } catch (error: LastFmException.NotAuthorised) {
                        blip = null // Normal until they press Allow.
                    } catch (error: LastFmException) {
                        if (!lastfmErrorIsRetryable(error)) throw error
                        blip = describe(error)
                    } catch (error: IOException) {
                        blip = describe(error)
                    }
                }
                _connection.value = Connection.Failed(blip ?: "Last.fm didn't hear back in time. Try again.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _connection.value = Connection.Failed(describe(error))
            }
        }
    }

    /** Asks Last.fm straight away, for when the person comes back from approving. */
    fun checkNow() {
        poke.tryEmit(Unit)
    }

    fun cancel() {
        connecting?.cancel()
        if (_connection.value is Connection.Waiting) _connection.value = Connection.Idle
    }

    fun disconnect() {
        connecting?.cancel()
        vault.clear()
        _connection.value = if (isConfigured) Connection.Idle else Connection.NotConfigured
        graph.scope.launch { graph.settings.update { it.copy(lastFmUser = null) } }
    }

    internal fun session(): LastFmSession? = vault.load()

    /** Tells Last.fm what's playing now. Best effort: a miss costs nothing. */
    suspend fun nowPlaying(title: String, artist: String, album: String?) {
        val client = client ?: return
        val session = session() ?: return
        runCatching { lastfmParseNowPlaying(send(client.updateNowPlaying(artist, title, album, session))) }
    }

    /** The drain the worker runs, wired to the history and to Last.fm. */
    internal fun drain(): ScrobbleDrain? {
        val client = client ?: return null
        val session = session() ?: return null
        val dao = graph.history.database.plays()
        val queue = object : ScrobbleQueue {
            override suspend fun owed(limit: Int) = dao.owedScrobbles(owedSince(), limit, scrobbleSpotify.value)
            override suspend fun markScrobbled(ids: List<String>, at: Long) = dao.markScrobbled(ids, at)
            override suspend fun markFailed(id: String, reason: String) = dao.markScrobbleFailed(id, reason)
            override suspend fun noteAttempt(ids: List<String>) = dao.noteScrobbleAttempt(ids)
        }
        return ScrobbleDrain(
            queue = queue,
            submit = { batch -> submit(client, session, batch) },
            isRetryable = { it is IOException || (it is LastFmException && lastfmErrorIsRetryable(it)) },
            isAccountProblem = { it is LastFmException && lastfmErrorIsAccountProblem(it) },
            describe = ::describe,
        )
    }

    internal fun noteDrain(result: DrainResult) {
        _problem.value = when (result) {
            is DrainResult.Done -> null
            is DrainResult.RetryLater -> result.reason
            is DrainResult.NeedsReconnect -> RECONNECT
        }
    }

    private suspend fun submit(client: LastFmClient, session: LastFmSession, batch: List<PlayEntity>): List<ScrobbleOutcome> {
        val scrobbles = batch.map { Scrobble(it.artistName, it.title, it.albumTitle, it.playedAt / 1000) }
        val submission = client.scrobble(scrobbles, session) ?: return emptyList()
        return lastfmParseScrobbles(send(submission.request), submission.sent)
    }

    /**
     * Brings the account's Last.fm history in, newest first, a page at a time. Songs Tracks
     * already kept (the ones it scrobbled itself, mostly) are skipped, and running it again
     * adds nothing twice.
     */
    fun startImport() {
        if (_import.value is Import.Running) return
        val client = client ?: return
        val session = session() ?: return
        _import.value = Import.Running(0)
        graph.scope.launch {
            try {
                val known = withContext(Dispatchers.IO) {
                    graph.history.database.plays().all()
                        .groupBy({ importKey(it.title, it.artistName) }, { it.playedAt / 1000 })
                }
                // Songs forgotten on another device stay out, as far back as they were forgotten.
                val forgotten = graph.history.forgottenSongs()
                var before: Long? = null
                var added = 0
                var page = 1L
                while (true) {
                    val result = lastfmParseRecentTracks(send(client.recentTracks(session, null, before, PAGE)))
                    if (result.scrobbles.isEmpty()) break
                    val plays = result.scrobbles.filter { track ->
                        known[importKey(track.title, track.artistName)].orEmpty()
                            .none { kotlin.math.abs(it - track.playedAt) < SAME_PLAY_SECONDS } &&
                            forgotten[uniffi.tracks_engine.songIdentity(track.title, track.artistName)]?.let { track.playedAt * 1000 <= it } != true
                    }.map { track ->
                        PlayEntity(
                            id = com.luke.tracks.data.PlayIds.lastFm(track.playedAt, track.title, track.artistName),
                            title = track.title,
                            artistName = track.artistName,
                            albumTitle = track.albumTitle,
                            artworkUri = track.artworkUrl,
                            playedAt = track.playedAt * 1000,
                            durationMs = null,
                            listenedMs = null,
                            kind = PlayKindValue.LastFm,
                            source = PlaySourceValue.Streaming,
                            appPackage = null,
                            appName = "Last.fm",
                            stationName = null,
                            mediaId = "",
                            scrobbledAt = track.playedAt * 1000,
                        )
                    }
                    graph.history.keepAll(plays)
                    added += plays.size
                    _import.value = Import.Running(added)
                    if (page >= result.totalPages) break
                    page++
                    before = result.scrobbles.minOf { it.playedAt } - 1
                    delay(250) // Last.fm asks for no more than a few requests a second.
                }
                _import.value = Import.Done(added)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _import.value = Import.Failed(describe(error))
            }
        }
    }

    private fun open(activity: Context, url: String) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF).build()
        if (activity !is Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { intent.launchUrl(activity, Uri.parse(url)) }.onFailure {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        const val RECONNECT = "Last.fm needs you to connect again"
        private val USER_AGENT = "Tracks/${BuildConfig.VERSION_NAME} (Android)"
        private const val TIMEOUT_MS = 5 * 60_000L
        private const val POLL_MS = 3_000L
        private const val PAGE = 200u
        private const val SAME_PLAY_SECONDS = 180L

        /** Last.fm takes scrobbles up to two weeks old. */
        fun owedSince(now: Long = System.currentTimeMillis()): Long = now - 14 * 24 * 3600_000L + 3600_000L

        private val http by lazy {
            com.luke.tracks.net.Http.api.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Sends one engine-built request. Never logs it: it can carry the session key. */
        suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(request.url)
            var type: String? = null
            request.headers.forEach {
                if (it.name.equals("Content-Type", true)) type = it.value
                builder.header(it.name, it.value)
            }
            if (request.headers.none { it.name.equals("User-Agent", true) }) builder.header("User-Agent", USER_AGENT)
            when (request.method) {
                HttpMethod.GET -> builder.get()
                HttpMethod.POST -> builder.post(
                    request.body.toRequestBody((type ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()),
                )
            }
            http.newCall(builder.build()).execute().use { response ->
                HttpResponse(response.code.toUShort(), response.body.bytes())
            }
        }

        /** Says what went wrong, plainly. */
        fun describe(error: Throwable): String = when (error) {
            is IOException -> "Couldn't reach Last.fm. Check your connection."
            is LastFmException.NotConfigured -> "Last.fm isn't set up in this build"
            is LastFmException.NotConnected, is LastFmException.NotAuthorised -> "Last.fm needs you to connect again"
            is LastFmException.Http -> if (error.status.toInt() >= 500) "Last.fm is having trouble. Tracks will try again." else "Last.fm said no (${error.status})"
            is LastFmException.Service -> error.detail.ifBlank { "Last.fm said no" }
            is LastFmException.MalformedResponse -> "Last.fm sent something Tracks couldn't read"
            else -> {
                Log.w("TracksLastFm", "Last.fm failed: ${error.javaClass.simpleName}")
                "Something went wrong with Last.fm"
            }
        }
    }
}

private val instances = WeakHashMap<AppGraph, LastFm>()

/** Last.fm for this process's history. */
val AppGraph.lastFm: LastFm
    get() = synchronized(instances) { instances.getOrPut(this) { LastFm(context, this) } }

/** Title and artist, trimmed and lowercased, to match an imported scrobble to a kept play. */
private fun importKey(title: String, artist: String): String =
    (title.trim() + "\u0000" + artist.trim()).lowercase(java.util.Locale.ROOT).replace(spaces, " ")

private val spaces = Regex("\\s+")

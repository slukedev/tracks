package com.luke.tracks.streaming

import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.RelayEvents
import com.luke.tracks.library.trackside.SpeakerEvents
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideEvent
import com.luke.tracks.library.trackside.TracksideJson
import com.luke.tracks.library.trackside.TracksideRelayRequest
import com.luke.tracks.library.trackside.TracksideRelaySession
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.library.trackside.TracksideSpeakerCommand
import com.luke.tracks.library.trackside.TracksideSpeakerPatch
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.library.trackside.TracksideSpeakerPlayback
import com.luke.tracks.library.trackside.TracksideSpeakerQueue
import com.luke.tracks.library.trackside.TracksideSpeakerTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/** What the relay needs of one server: its Trackside client in the app, a fake in tests. */
interface RelayClient {
    suspend fun start(request: TracksideRelayRequest): TracksideRelaySession
    suspend fun session(id: String): TracksideRelaySession
    suspend fun setTier(id: String, tier: String): TracksideRelaySession
    suspend fun end(id: String)
    suspend fun playback(speakerId: String, command: TracksideSpeakerPlayback): TracksideSpeakerCommand
    suspend fun patch(speakerId: String, patch: TracksideSpeakerPatch): TracksideSpeakerCommand
    suspend fun play(speakerId: String, request: TracksideSpeakerPlayRequest): TracksideSpeakerCommand
    suspend fun transfer(speakerId: String, to: String): TracksideSpeakerCommand
    suspend fun speaker(speakerId: String): TracksideSpeaker
    suspend fun queue(speakerId: String, limit: Int, history: Int): TracksideSpeakerQueue

    /** The playlist's address at the server in use, with a media token good for a while yet. */
    suspend fun streamUrl(hls: String, fresh: Boolean): String?

    /** How far the server's clock runs ahead of this phone's. */
    val clockSkewMs: Long
}

class TracksideRelayClient(private val client: TracksideClient) : RelayClient {
    override suspend fun start(request: TracksideRelayRequest) = client.startRelay(request)
    override suspend fun session(id: String) = client.relaySession(id)
    override suspend fun setTier(id: String, tier: String) = client.setRelayTier(id, tier)
    override suspend fun end(id: String) = client.endRelay(id)
    override suspend fun playback(speakerId: String, command: TracksideSpeakerPlayback) = client.speakerPlayback(speakerId, command)
    override suspend fun patch(speakerId: String, patch: TracksideSpeakerPatch) = client.patchSpeaker(speakerId, patch)
    override suspend fun play(speakerId: String, request: TracksideSpeakerPlayRequest) = client.playOnSpeaker(speakerId, request)
    override suspend fun transfer(speakerId: String, to: String) = client.transferSpeaker(speakerId, TracksideSpeakerTransfer(to))
    override suspend fun speaker(speakerId: String) = client.speaker(speakerId)
    override suspend fun queue(speakerId: String, limit: Int, history: Int) = client.speakerQueue(speakerId, limit = limit, history = history)
    override suspend fun streamUrl(hls: String, fresh: Boolean): String? {
        if (fresh) client.renewMediaToken() else client.freshMediaToken()
        return client.mediaUrl(hls)?.toString()
    }
    override val clockSkewMs: Long get() = client.clockSkewMs
}

/** A song in the relay's queue, as the players show it. */
data class RelayItem(
    /** The server's queue item id, for jumping to it; empty for one shown before the server answered. */
    val itemId: String,
    /** A service's song by ref, a library song by id, or the queue item itself. */
    val mediaId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val artworkId: String?,
    val durationMs: Long?,
    val serviceId: String?,
)

/** The generation the phone's player should be playing: its playlist, at the server in use. */
data class RelayStream(val url: String, val hls: String, val attempt: Int = 0)

/** What the phone's relay player is doing, as an immutable snapshot. */
data class RelayStatus(
    val serverId: String? = null,
    val phase: Phase = Phase.Idle,
    val sessionId: String? = null,
    val speakerId: String? = null,
    /** What you asked for, shown at once: the player follows it as soon as it has audio. */
    val wantsToPlay: Boolean = false,
    /** Play nothing when null: never what was buffered before a seek, a skip or a pause. */
    val stream: RelayStream? = null,
    val items: List<RelayItem> = emptyList(),
    val index: Int = 0,
    /** Where the item was at [positionAtMs] on the server's clock (MA's timeline, ahead of what's audible by [latencyMs]). */
    val positionMs: Long = 0,
    val positionAtMs: Long? = null,
    val latencyMs: Long = 4_000,
    val shuffle: Boolean = false,
    /** off, one, all. */
    val repeat: String = "off",
    val tier: String = TracksideRelaySession.Tier.LOSSLESS,
    /** Each item's service, by id, for its name and source. */
    val services: Map<String, String> = emptyMap(),
) {
    enum class Phase {
        Idle,
        /** Asked for; the server is joining the music to this phone. */
        Starting,
        Live,
        Paused,
        /** The server ended it (idle, restarted, revoked): playing again starts a new one. */
        Ended,
        Failed,
    }

    val current: RelayItem? get() = items.getOrNull(index)

    val isActive: Boolean get() = serverId != null && phase != Phase.Idle && phase != Phase.Failed
}

/**
 * Plays a queue with any streaming service's song in it on this phone, through the server's
 * relay (the wire contract's section 3). Every control is applied here at once and sent as
 * a command to the relay's speaker; the server's answer, its events and re-reads correct it.
 *
 * The player never plays stale audio: a seek, a skip, a jump, a pause or a tier change
 * retires the playlist it was on, and only a playlist the server names afterwards ([RelayStream])
 * is played. A natural change of song keeps the same playlist, gapless.
 *
 * State changes on one thread of [work], network on [io]; [status] is for the player and the UI.
 */
class RelayEngine(
    private val scope: CoroutineScope,
    private val clientFor: suspend (serverId: String) -> RelayClient?,
    /** The name the server's music system shows for this phone. */
    private val deviceName: () -> String,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val work: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val _status = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** A calm line for the player to show, such as "Music services aren't available right now." */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // Touched only on [work].
    private var session: TracksideRelaySession? = null
    private var speaker: TracksideSpeaker? = null
    private val retired = LinkedHashSet<String>()
    private var preferredTier = TracksideRelaySession.Tier.LOSSLESS
    private var cellularAllowed = false
    private var serviceNames = emptyMap<String, String>()
    /** Bumped by every command, so an answer to an older one doesn't undo a newer one's guess. */
    private var commands = 0L
    private var awaiting: Job? = null
    private var itemTimer: Job? = null
    private var tierJob: Job? = null

    @Volatile private var skewMs = 0L

    private suspend fun client(): RelayClient? = _status.value.serverId?.let { clientFor(it) }?.also { skewMs = it.clockSkewMs }

    /** The server's clock now, for working out where the item is. Safe from any thread. */
    fun serverNowMs(): Long = now().toEpochMilli() + skewMs

    // MARK: Asking

    /**
     * Plays [kept] here, showing [preview] at once. A session already going on the same server
     * is reused; one on another server ends.
     */
    fun play(serverId: String, kept: CatalogMedia.Kept, preview: List<RelayItem>, services: Map<String, String>) {
        scope.launch(work) {
            val token = ++commands
            val before = _status.value
            if (before.serverId != null && before.serverId != serverId) endQuietly()
            serviceNames = services
            retireStream()
            val start = kept.request.startIndex ?: 0
            _status.value = RelayStatus(
                serverId = serverId,
                phase = RelayStatus.Phase.Starting,
                wantsToPlay = true,
                items = preview,
                index = start.coerceIn(0, (preview.size - 1).coerceAtLeast(0)),
                positionMs = kept.request.positionMs ?: 0,
                positionAtMs = null,
                shuffle = kept.request.shuffle == true,
                repeat = kept.request.repeat ?: "off",
                tier = tier(),
                services = services,
                sessionId = session?.id,
                speakerId = session?.speakerId,
            )
            val client = clientFor(serverId)?.also { skewMs = it.clockSkewMs } ?: return@launch fail("Couldn't reach your server.")
            val live = session?.takeIf { !it.isEnded }
            try {
                if (live != null) {
                    val answer = withContext(io) { client.play(live.speakerId, kept.request) }
                    if (token == commands) apply(answer, client)
                } else {
                    val started = withContext(io) { client.start(TracksideRelayRequest(play = kept.request, name = deviceName(), tier = tier())) }
                    applySession(started, client)
                }
                if (_status.value.wantsToPlay && _status.value.stream == null) awaitStream()
                // Read back once the server has queued it, for when no events come (in the background).
                scope.launch(work) {
                    delay(SETTLE_READ_MS)
                    if (token != commands) return@launch
                    refreshQueue(client)
                    refreshSpeaker(client)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error.isRelayEnded() && live != null) {
                    // The session ended under us: a new one plays the same thing.
                    session = null
                    runCatching { applySession(withContext(io) { client.start(TracksideRelayRequest(play = kept.request, name = deviceName(), tier = tier())) }, client) }
                        .onFailure { fail(words(it)) }
                } else fail(words(error))
            }
        }
    }

    /** Adds songs after the one playing ([next]) or at the end, as the server's queue takes them. */
    fun enqueue(items: List<TracksideSpeakerPlayRequest.Item>, preview: List<RelayItem>, next: Boolean) {
        if (items.isEmpty()) return
        scope.launch(work) {
            val state = _status.value
            val speakerId = state.speakerId ?: return@launch
            val shown = state.items.toMutableList().apply { if (next) addAll((state.index + 1).coerceAtMost(size), preview) else addAll(preview) }
            _status.value = state.copy(items = shown)
            val client = client() ?: return@launch
            runCatching { withContext(io) { client.play(speakerId, TracksideSpeakerPlayRequest(items, mode = if (next) "next" else "add")) } }
                .onSuccess { apply(it, client, keepsStream = true) }
                .onFailure { say(words(it)) }
            refreshQueue(client)
        }
    }

    fun resume() {
        scope.launch(work) {
            val state = _status.value
            if (state.serverId == null) return@launch
            ++commands
            _status.value = state.copy(wantsToPlay = true, phase = if (state.phase == RelayStatus.Phase.Failed) RelayStatus.Phase.Starting else state.phase)
            val client = client() ?: return@launch fail("Couldn't reach your server.")
            val live = session?.takeIf { !it.isEnded }
            if (live == null) {
                // Ended (idle, or the server restarted): a new session, and the server's queue plays on from where it was.
                try {
                    val started = withContext(io) { client.start(TracksideRelayRequest(name = deviceName(), tier = tier())) }
                    applySession(started, client)
                    command(client, TracksideSpeakerPlayback("play"))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(words(error))
                }
                return@launch
            }
            // A paused session already names the next generation, which waits for the audio.
            offerStream(live, client)
            command(client, TracksideSpeakerPlayback("play"))
        }
    }

    /** Pauses here at once; the server's stream ends, and playing again starts a new one. */
    fun pause() {
        scope.launch(work) {
            val state = _status.value
            if (state.serverId == null || !state.wantsToPlay) return@launch
            ++commands
            retireStream()
            _status.value = _status.value.copy(wantsToPlay = false, positionMs = shownPosition(state), positionAtMs = null, phase = if (state.phase == RelayStatus.Phase.Live) RelayStatus.Phase.Paused else state.phase)
            val client = client() ?: return@launch
            if (session?.isEnded == false) command(client, TracksideSpeakerPlayback("pause"), changesStream = false)
        }
    }

    fun next() = move(TracksideSpeakerPlayback("next")) { it.copy(index = (it.index + 1).coerceAtMost((it.items.size - 1).coerceAtLeast(0)), positionMs = 0) }

    fun previous() = move(TracksideSpeakerPlayback("previous")) { state ->
        if (shownPosition(state) > RESTARTS_AFTER_MS) state.copy(positionMs = 0) else state.copy(index = (state.index - 1).coerceAtLeast(0), positionMs = 0)
    }

    fun jump(index: Int) = scope.launch(work) {
        val item = _status.value.items.getOrNull(index) ?: return@launch
        if (item.itemId.isEmpty()) return@launch
        moveNow(TracksideSpeakerPlayback("jump", itemId = item.itemId)) { it.copy(index = index, positionMs = 0) }
    }

    fun seek(positionMs: Long) = move(TracksideSpeakerPlayback("seek", positionMs = positionMs.coerceAtLeast(0))) { it.copy(positionMs = positionMs.coerceAtLeast(0)) }

    fun setShuffle(on: Boolean) = setting(TracksideSpeakerPatch(shuffle = on)) { it.copy(shuffle = on) }

    /** off, one or all. */
    fun setRepeat(repeat: String) = setting(TracksideSpeakerPatch(repeat = repeat)) { it.copy(repeat = repeat) }

    /** The tier to use from now on: cellular on a metered network, when the server can send it. */
    fun preferTier(cellular: Boolean, serverSendsCellular: Boolean) {
        scope.launch(work) {
            cellularAllowed = serverSendsCellular
            val wanted = if (cellular) TracksideRelaySession.Tier.CELLULAR else TracksideRelaySession.Tier.LOSSLESS
            if (wanted == preferredTier) return@launch
            preferredTier = wanted
            tierJob?.cancel()
            // A network that changes twice in a moment (Wi-Fi dropping, coming back) changes the tier once.
            tierJob = scope.launch(work) {
                delay(TIER_SETTLE_MS)
                applyTier()
            }
        }
    }

    /** Moves what plays here to the speaker [toSpeakerId]; the phone stops. True when the server took it. */
    suspend fun transfer(toSpeakerId: String): Boolean = withContext(work) {
        val speakerId = _status.value.speakerId ?: return@withContext false
        val client = client() ?: return@withContext false
        val ok = runCatching { withContext(io) { client.transfer(speakerId, toSpeakerId) } }.onFailure { say(words(it)) }.isSuccess
        if (ok) {
            retireStream()
            _status.value = _status.value.copy(wantsToPlay = false, phase = RelayStatus.Phase.Paused)
        }
        ok
    }

    /**
     * Takes a speaker's music to this phone: a session (reused or new) for [serverId], and the
     * speaker's queue moved to it. True when the server took it.
     */
    suspend fun takeFrom(serverId: String, speakerId: String, services: Map<String, String>): Boolean = withContext(work) {
        val client = clientFor(serverId) ?: return@withContext false
        if (_status.value.serverId != null && _status.value.serverId != serverId) endQuietly()
        serviceNames = services
        retireStream()
        _status.value = RelayStatus(serverId = serverId, phase = RelayStatus.Phase.Starting, wantsToPlay = true, tier = tier(), services = services)
        try {
            val live = session?.takeIf { !it.isEnded } ?: withContext(io) { client.start(TracksideRelayRequest(name = deviceName(), tier = tier())) }
            applySession(live, client)
            withContext(io) { client.transfer(speakerId, live.speakerId) }
            refreshSpeaker(client)
            refreshQueue(client)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(words(error))
            false
        }
    }

    /** Stops and ends the session: the queue went back to playing your library natively, or you signed out. */
    fun stop(message: String? = null) {
        scope.launch(work) {
            endQuietly()
            message?.let(::say)
        }
    }

    /** The server is gone or forgot this phone: stop without asking it anything. */
    fun forget(serverId: String, message: String?) {
        scope.launch(work) {
            if (_status.value.serverId != serverId) return@launch
            reset()
            message?.let(::say)
        }
    }

    // MARK: Hearing back

    /** One server event: the relay's own, and its speaker's. Anything else is ignored. */
    suspend fun handle(serverId: String, event: TracksideEvent) {
        if (event.type != RelayEvents.RELAY && event.type !in SpeakerEvents.all) return
        withContext(work) {
            if (_status.value.serverId != serverId) return@withContext
            val client = clientFor(serverId)
            when (event.type) {
                RelayEvents.RELAY -> decode(event.data, TracksideRelaySession.serializer())?.takeIf { it.id == session?.id }?.let { applySession(it, client) }
                SpeakerEvents.ADDED, SpeakerEvents.UPDATED -> decode(event.data, TracksideSpeaker.serializer())?.takeIf { it.id == _status.value.speakerId }?.let { applySpeaker(it, client) }
                SpeakerEvents.PROGRESS -> decode(event.data, SpeakerEvents.Progress.serializer())?.takeIf { it.id == _status.value.speakerId }?.let { progress ->
                    val current = speaker ?: return@let
                    if (progress.revision < current.revision) return@let
                    applySpeaker(current.copy(playback = (current.playback ?: TracksideSpeaker.Playback()).copy(state = progress.state, positionMs = progress.positionMs, positionAt = progress.positionAt)), client, moved = false)
                }
                SpeakerEvents.QUEUE -> decode(event.data, SpeakerEvents.Queue.serializer())?.takeIf { it.id == _status.value.speakerId }?.let { if (client != null) refreshQueue(client) }
                SpeakerEvents.REMOVED -> decode(event.data, SpeakerEvents.Removed.serializer())?.takeIf { it.id == _status.value.speakerId }?.let {
                    session = session?.copy(state = "ended")
                    retireStream()
                    _status.value = _status.value.copy(phase = RelayStatus.Phase.Ended, wantsToPlay = false)
                }
            }
        }
    }

    /** The app came back to the foreground, or events reconnected: what was missed isn't replayed, so read it again. */
    fun refresh() {
        scope.launch(work) {
            val id = session?.id ?: return@launch
            val client = client() ?: return@launch
            runCatching { withContext(io) { client.session(id) } }.onSuccess { applySession(it, client) }
            refreshSpeaker(client)
            refreshQueue(client)
        }
    }

    /**
     * The player couldn't load its playlist: a `404` for a generation that's over, a refused
     * media token, or no network. The session is read again and its playlist played afresh,
     * a few times, before giving up.
     */
    fun streamFailed(url: String, status: Int?) {
        scope.launch(work) {
            val stream = _status.value.stream?.takeIf { it.url == url } ?: return@launch
            val client = client() ?: return@launch
            val id = session?.id ?: return@launch
            val fresh = runCatching { withContext(io) { client.session(id) } }.getOrNull()
            if (fresh != null && fresh.hls != stream.hls) {
                retired += stream.hls
                applySession(fresh, client)
                return@launch
            }
            if (fresh?.isEnded == true) return@launch applySession(fresh, client)
            if (stream.attempt >= MAX_ATTEMPTS) {
                retireStream()
                _status.value = _status.value.copy(wantsToPlay = false, phase = RelayStatus.Phase.Failed)
                say(if (status == null) "Lost the connection to your server." else "Couldn't play that right now.")
                return@launch
            }
            if (status == null) delay(RETRY_MS * (stream.attempt + 1))
            val url2 = runCatching { withContext(io) { client.streamUrl(stream.hls, fresh = status == 401 || status == 403) } }.getOrNull() ?: return@launch
            if (_status.value.stream?.hls == stream.hls) _status.value = _status.value.copy(stream = RelayStream(url2, stream.hls, stream.attempt + 1))
        }
    }

    /** The player reached the end of its playlist while meant to play on: the next generation is due. */
    fun streamEnded(url: String) {
        scope.launch(work) {
            val stream = _status.value.stream?.takeIf { it.url == url } ?: return@launch
            retired += stream.hls
            _status.value = _status.value.copy(stream = null)
            if (_status.value.wantsToPlay) awaitStream()
        }
    }

    // MARK: Inside

    private fun move(playback: TracksideSpeakerPlayback, guess: (RelayStatus) -> RelayStatus) {
        scope.launch(work) { moveNow(playback, guess) }
    }

    private suspend fun moveNow(playback: TracksideSpeakerPlayback, guess: (RelayStatus) -> RelayStatus) {
        val state = _status.value
        if (state.serverId == null) return
        ++commands
        retireStream()
        _status.value = guess(_status.value).copy(positionAtMs = null)
        itemTimer?.cancel()
        val client = client() ?: return
        if (session?.isEnded != false) {
            // Nothing is listening for the command: start again, and let the server's queue move.
            if (state.wantsToPlay) resume()
            return
        }
        command(client, playback)
    }

    private fun setting(patch: TracksideSpeakerPatch, guess: (RelayStatus) -> RelayStatus) {
        scope.launch(work) {
            val speakerId = _status.value.speakerId ?: return@launch
            _status.value = guess(_status.value)
            val client = client() ?: return@launch
            runCatching { withContext(io) { client.patch(speakerId, patch) } }
                .onSuccess { apply(it, client, keepsStream = true) }
                .onFailure { say(words(it)); refreshSpeaker(client) }
        }
    }

    /** Sends a command to the relay's speaker; one that changes what's heard waits for a new playlist. */
    private suspend fun command(client: RelayClient, playback: TracksideSpeakerPlayback, changesStream: Boolean = true) {
        val speakerId = _status.value.speakerId ?: session?.speakerId ?: return
        val token = commands
        try {
            val answer = withContext(io) { client.playback(speakerId, playback) }
            if (token == commands) apply(answer, client, keepsStream = !changesStream)
            else answer.relay?.let { applySession(it, client) }
            if (changesStream && _status.value.wantsToPlay && _status.value.stream == null) awaitStream()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isRelayEnded()) {
                session = session?.copy(state = "ended")
                _status.value = _status.value.copy(phase = RelayStatus.Phase.Ended)
                if (_status.value.wantsToPlay) resume()
                return
            }
            say(words(error))
            refreshSpeaker(client)
            runCatching { withContext(io) { client.session(session?.id ?: return@withContext null) } }.getOrNull()?.let { applySession(it, client) }
        }
    }

    /** A command's answer: the server's expectation of the speaker, and for the relay, its session. */
    private suspend fun apply(answer: TracksideSpeakerCommand, client: RelayClient, keepsStream: Boolean = false) {
        answer.relay?.let { applySession(it, client) }
        applySpeaker(answer.speaker, client, moved = !keepsStream)
    }

    private suspend fun applySession(fresh: TracksideRelaySession, client: RelayClient?) {
        val old = session
        if (old != null && old.id == fresh.id && fresh.generation < old.generation && !fresh.isEnded) return
        session = fresh
        val state = _status.value
        if (fresh.isEnded) {
            retireStream()
            _status.value = _status.value.copy(phase = RelayStatus.Phase.Ended, wantsToPlay = false, sessionId = fresh.id, speakerId = fresh.speakerId)
            endedWords(fresh.endedReason)?.let(::say)
            return
        }
        _status.value = state.copy(
            sessionId = fresh.id,
            speakerId = fresh.speakerId,
            tier = fresh.tier,
            latencyMs = fresh.latencyMs,
            phase = when (fresh.stateValue) {
                TracksideRelaySession.State.Playing -> RelayStatus.Phase.Live
                TracksideRelaySession.State.Paused, TracksideRelaySession.State.Ready -> if (state.wantsToPlay) RelayStatus.Phase.Starting else RelayStatus.Phase.Paused
                else -> RelayStatus.Phase.Starting
            },
        )
        if (client != null) offerStream(fresh, client)
        if (fresh.tier != tier() && client != null) applyTier()
    }

    /** Plays the session's playlist, unless it's one this phone has already moved on from. */
    private suspend fun offerStream(fresh: TracksideRelaySession, client: RelayClient) {
        val hls = fresh.hls ?: return
        val state = _status.value
        if (!state.wantsToPlay || hls in retired || state.stream?.hls == hls) return
        val url = runCatching { withContext(io) { client.streamUrl(hls, fresh = false) } }.getOrNull() ?: return
        if (_status.value.wantsToPlay && hls !in retired) {
            awaiting?.cancel()
            _status.value = _status.value.copy(stream = RelayStream(url, hls))
        }
    }

    /** No new playlist came with the answer: read the session a few times until one does. */
    private fun awaitStream() {
        if (awaiting?.isActive == true) return
        awaiting = scope.launch(work) {
            for (wait in AWAIT_MS) {
                delay(wait)
                val state = _status.value
                if (!state.wantsToPlay || state.stream != null) return@launch
                val id = session?.id ?: return@launch
                val client = client() ?: return@launch
                runCatching { withContext(io) { client.session(id) } }.getOrNull()?.let { applySession(it, client) }
            }
            if (_status.value.wantsToPlay && _status.value.stream == null) {
                _status.value = _status.value.copy(wantsToPlay = false, phase = RelayStatus.Phase.Failed)
                say("Your server didn't start the music. Try again.")
            }
        }
    }

    private suspend fun applySpeaker(fresh: TracksideSpeaker, client: RelayClient?, moved: Boolean = false) {
        val old = speaker
        if (old != null && old.id == fresh.id && fresh.revision < old.revision) return
        speaker = fresh
        val playing = fresh.nowPlaying
        val state = _status.value
        val positionAt = fresh.playback?.positionAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val shown = state.copy(
            shuffle = fresh.playback?.shuffle ?: state.shuffle,
            repeat = fresh.playback?.repeat ?: state.repeat,
        )
        if (playing == null) {
            _status.value = shown
            return
        }
        val index = shown.items.indexOfFirst { it.itemId.isNotEmpty() && it.itemId == playing.itemId }
            .takeIf { it >= 0 } ?: shown.items.indexOfFirst { matches(it, playing) }.takeIf { it >= 0 }
        val changed = index != null && index != shown.index
        val apply = {
            val now = _status.value
            val at = index ?: now.index
            val items = if (index == null) replaceCurrent(now.items, now.index, item(playing)) else now.items
            _status.value = now.copy(
                items = items,
                index = at.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                positionMs = fresh.playback?.positionMs ?: 0,
                positionAtMs = positionAt?.takeIf { fresh.stateValue == TracksideSpeaker.State.Playing },
                shuffle = shown.shuffle,
                repeat = shown.repeat,
            )
        }
        itemTimer?.cancel()
        val client2 = client
        val serverNow = serverNowMs()
        val switchIn = if (positionAt != null && changed && !moved && state.stream != null) positionAt + state.latencyMs - serverNow else 0
        if (switchIn > 0) {
            // A song gave way on its own: the new one shows when it's heard, about the relay's latency later.
            _status.value = shown
            itemTimer = scope.launch(work) {
                delay(switchIn.coerceAtMost(state.latencyMs * 2))
                apply()
            }
        } else apply()
        if (index == null && client2 != null && playing.itemId != null) refreshQueue(client2)
        scheduleNextRead(client2)
    }

    /** In the background no events come: read the speaker again about when the song ends, for the notification. */
    private fun scheduleNextRead(client: RelayClient?) {
        if (client == null) return
        val state = _status.value
        val length = state.current?.durationMs ?: return
        val at = state.positionAtMs ?: return
        val serverNow = serverNowMs()
        val left = length - (state.positionMs + (serverNow - at)) + state.latencyMs
        if (left <= 0 || left > MAX_READ_AHEAD_MS) return
        val revision = speaker?.revision
        scope.launch(work) {
            delay(left + READ_SLACK_MS)
            if (speaker?.revision == revision && _status.value.wantsToPlay) refreshSpeaker(client)
        }
    }

    private suspend fun refreshSpeaker(client: RelayClient) {
        val id = _status.value.speakerId ?: return
        runCatching { withContext(io) { client.speaker(id) } }.getOrNull()?.let { applySpeaker(it, client, moved = true) }
    }

    private suspend fun refreshQueue(client: RelayClient) {
        val id = _status.value.speakerId ?: return
        val serverId = _status.value.serverId ?: return
        val queue = runCatching { withContext(io) { client.queue(id, QUEUE_LIMIT, QUEUE_HISTORY) } }.getOrNull() ?: return
        if (_status.value.speakerId != id) return
        val items = queue.items.map { item(serverId, it) }
        if (items.isEmpty()) return
        val current = queue.currentIndex?.let { index -> queue.items.indexOfFirst { it.index == index } }?.takeIf { it >= 0 } ?: 0
        _status.value = _status.value.copy(items = items, index = current, shuffle = queue.shuffle ?: _status.value.shuffle, repeat = queue.repeat ?: _status.value.repeat)
    }

    private suspend fun applyTier() {
        val fresh = session?.takeIf { !it.isEnded } ?: return
        val wanted = tier()
        if (fresh.tier == wanted) return
        val client = client() ?: return
        val answer = try {
            withContext(io) { client.setTier(fresh.id, wanted) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TracksideError.Server) {
            // A server without an encoder stays lossless; that's never worth a word.
            if (error.capability == "relay_cellular") cellularAllowed = false
            return
        } catch (_: Exception) {
            return
        }
        // While playing, a tier change starts a new generation: this one is done.
        if (answer.hls != _status.value.stream?.hls) retireStream()
        applySession(answer, client)
    }

    private fun tier() = if (preferredTier == TracksideRelaySession.Tier.CELLULAR && cellularAllowed) TracksideRelaySession.Tier.CELLULAR else TracksideRelaySession.Tier.LOSSLESS

    private fun retireStream() {
        awaiting?.cancel()
        _status.value.stream?.let { retired += it.hls }
        while (retired.size > 32) retired.remove(retired.first())
        if (_status.value.stream != null) _status.value = _status.value.copy(stream = null)
    }

    private suspend fun endQuietly() {
        val ending = session
        val client = client()
        reset()
        if (ending != null && !ending.isEnded && client != null) {
            scope.launch(io) { runCatching { client.end(ending.id) } }
        }
    }

    private fun reset() {
        awaiting?.cancel()
        itemTimer?.cancel()
        tierJob?.cancel()
        session = null
        speaker = null
        retired.clear()
        _status.value = RelayStatus()
    }

    private fun fail(message: String) {
        retireStream()
        _status.value = _status.value.copy(wantsToPlay = false, phase = RelayStatus.Phase.Failed)
        say(message)
    }

    private fun say(message: String) {
        _messages.tryEmit(message)
    }

    /** Where the item is now as shown, by the server's clock less what's not yet heard. */
    private fun shownPosition(state: RelayStatus): Long = RelayClock.position(state, serverNowMs())

    private fun <T> decode(data: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { TracksideJson.decodeFromString(serializer, data) }.getOrNull()

    private fun matches(item: RelayItem, playing: TracksideSpeaker.NowPlaying): Boolean {
        val serverId = _status.value.serverId ?: return false
        val id = playing.ref?.let { CatalogMedia.mediaId(serverId, it) } ?: playing.songId?.let { ServerLibrary.mediaId(serverId, it) } ?: return false
        return item.itemId.isEmpty() && item.mediaId == id
    }

    private fun replaceCurrent(items: List<RelayItem>, index: Int, item: RelayItem): List<RelayItem> =
        if (items.isEmpty()) listOf(item) else items.toMutableList().also { it[index.coerceIn(0, it.lastIndex)] = item }

    private fun item(playing: TracksideSpeaker.NowPlaying): RelayItem {
        val serverId = _status.value.serverId.orEmpty()
        return RelayItem(
            itemId = playing.itemId.orEmpty(),
            mediaId = playing.ref?.let { CatalogMedia.mediaId(serverId, it) } ?: playing.songId?.let { ServerLibrary.mediaId(serverId, it) } ?: "relay:${playing.itemId.orEmpty()}",
            title = playing.title,
            artist = playing.artist,
            album = playing.album,
            artworkId = playing.artworkId,
            durationMs = playing.durationMs,
            serviceId = playing.serviceId,
        )
    }

    private fun item(serverId: String, it: TracksideSpeakerQueue.Item) = RelayItem(
        itemId = it.itemId,
        mediaId = it.ref?.let { ref -> CatalogMedia.mediaId(serverId, ref) } ?: it.songId?.let { id -> ServerLibrary.mediaId(serverId, id) } ?: "relay:${it.itemId}",
        title = it.title,
        artist = it.artist,
        album = it.album,
        artworkId = it.artworkId,
        durationMs = it.durationMs,
        serviceId = it.serviceId,
    )

    private fun Throwable.isRelayEnded() = this is TracksideError.Server && reason == "relay_ended"

    private fun words(error: Throwable): String = when (error) {
        is TracksideError.CatalogUnavailable -> "Music services aren't available right now."
        is TracksideError.RateLimited -> "One moment. Try again shortly."
        is TracksideError.Forbidden -> "This phone can't play music services from your server."
        is TracksideError.NotFound -> "That isn't available anymore."
        is TracksideError.Server -> when {
            error.reason == "service_signed_out" -> serviceNames[error.serviceId]?.let { "Sign in to $it again on your server." } ?: "Sign in to that service again on your server."
            error.reason == "relay_limit" -> "Your server is playing music services on as many phones as it can. Try again later."
            error.reason == "not_playable" -> "That can't play right now."
            error.code == "unsupported" && error.capability == "relay" -> "Your server can't play music services on this phone yet."
            else -> "Couldn't play that right now."
        }
        is IOException, is TracksideError.Unproven -> "Couldn't reach your server."
        else -> if (error.javaClass.simpleName == "Unproven") "Couldn't reach your server." else "Couldn't play that right now."
    }

    private fun endedWords(reason: String?): String? = when (reason) {
        // Idle, closed and replaced are expected: playing again just starts once more.
        "assistant_unavailable" -> "Music services aren't available right now."
        "server_restart" -> "Your server restarted. Play again to carry on."
        "revoked" -> "Your server doesn't know this phone anymore."
        "failed" -> "The music stopped on your server."
        else -> null
    }

    companion object {
        const val RESTARTS_AFTER_MS = 3_000L
        const val TIER_SETTLE_MS = 2_000L
        const val MAX_ATTEMPTS = 3
        const val RETRY_MS = 1_000L
        val AWAIT_MS = listOf(1_500L, 2_000L, 3_000L, 4_000L)
        const val QUEUE_LIMIT = 100
        const val QUEUE_HISTORY = 20
        const val MAX_READ_AHEAD_MS = 20 * 60_000L
        const val READ_SLACK_MS = 1_500L
        const val SETTLE_READ_MS = 2_500L
    }
}

/** Where the relay's item is, as heard. */
object RelayClock {
    /**
     * [audibleAtMs] is the program date of what plays now (the server's clock), when the
     * player knows it; otherwise the server's clock less the relay's latency stands in.
     */
    fun position(state: RelayStatus, serverNowMs: Long, audibleAtMs: Long? = null): Long {
        val at = state.positionAtMs ?: return state.positionMs.coerceAtLeast(0)
        if (!state.wantsToPlay) return state.positionMs.coerceAtLeast(0)
        val heard = audibleAtMs ?: (serverNowMs - state.latencyMs)
        val position = state.positionMs + (heard - at)
        val length = state.current?.durationMs
        return position.coerceIn(0, length?.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
}

package com.luke.tracks.speakers

import com.luke.tracks.library.trackside.SpeakerEvents
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideEvent
import com.luke.tracks.library.trackside.TracksideJson
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.library.trackside.TracksideSpeakerCommand
import com.luke.tracks.library.trackside.TracksideSpeakerPatch
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.library.trackside.TracksideSpeakerPlayback
import com.luke.tracks.library.trackside.TracksideSpeakerQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.IOException
import java.time.Instant

/** A Trackside server, by its id in Tracks and its name. */
data class SpeakerServer(val id: String, val name: String)

/** One server's speakers, as shown: each with what it's been asked to do already applied. */
data class ServerSpeakers(
    val serverId: String,
    val serverName: String,
    val status: Status,
    /** Whether this phone's key may control them, or only see them. */
    val canControl: Boolean,
    val speakers: List<TracksideSpeaker>,
) {
    enum class Status {
        /** Asked for the first time. */
        Loading,
        Ok,
        /** The server is reaching its speakers again; the list is as last seen. */
        Connecting,
        /** The server can't reach its speakers. */
        Unavailable,
        /** Tracks can't reach the server. */
        Unreachable,
    }

    fun speaker(id: String) = speakers.firstOrNull { it.id == id }
}

data class SpeakersState(
    /** Only servers with speakers set up for you: none at all hides Devices' speakers. */
    val servers: List<ServerSpeakers> = emptyList(),
    /** The speaker the players show and control, chosen by casting to it or picking it. */
    val active: SpeakerKey? = null,
    /** Speakers your queue is starting on, until the server says it's playing or it fails. */
    val connecting: Set<SpeakerKey> = emptySet(),
) {
    fun speaker(key: SpeakerKey): TracksideSpeaker? = servers.firstOrNull { it.serverId == key.serverId }?.speaker(key.speakerId)

    fun server(serverId: String): ServerSpeakers? = servers.firstOrNull { it.serverId == serverId }

    val activeSpeaker: TracksideSpeaker? get() = active?.let(::speaker)

    /** The speaker the players stand in for: chosen, and with something on it. */
    val casting: TracksideSpeaker? get() = activeSpeaker?.takeIf { it.nowPlaying != null }
}

/** A calm line for the snackbar, such as "Kitchen didn't respond." */
data class SpeakerMessage(val text: String, val key: SpeakerKey? = null)

/** Where the phone's own player goes while its queue moves to a speaker. */
interface LocalHandoff {
    /** Pauses this phone for the speaker; true when it was playing. */
    fun pauseForSpeaker(): Boolean

    /** The speaker couldn't take it: play here again. */
    fun resumeHere()
}

/** Which speaker is chosen, kept across the app being closed by the system. */
interface ActiveSpeakerStore {
    fun load(): SpeakerKey?
    fun save(key: SpeakerKey?)
}

/**
 * Your servers' speakers: listed, controlled and kept up to date from each server's events.
 *
 * Every control answers at once: a command's effect shows before it's sent, the server's own
 * expectation (its `202`) replaces that guess, and the speaker's next update is the truth. A
 * command that fails, or that the server never settles by its `settle_by`, falls back to what
 * the speaker last said, with a quiet line saying so. Sliders send at most ten values a second,
 * always the latest. Nothing here polls: lists are read when a server's events connect (and
 * again after each reconnect), and when Devices opens after a while away.
 *
 * All state changes happen on one thread of [work], network calls on [io]; [state] is an
 * immutable snapshot for the UI to collect.
 */
class SpeakersRepository(
    private val scope: CoroutineScope,
    private val clientFor: suspend (serverId: String) -> SpeakerClient?,
    private val store: ActiveSpeakerStore,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val work: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** A server's streaming service by id, named as people know it ("Spotify"), for the lines a failed play says. */
    private val serviceName: (serverId: String, serviceId: String) -> String? = { _, _ -> null },
) {
    private val _state = MutableStateFlow(SpeakersState())
    val state: StateFlow<SpeakersState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<SpeakerMessage>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<SpeakerMessage> = _messages.asSharedFlow()

    /** Bumped when a speaker's queue changes, for an Up Next on screen to read it again. */
    private val _queueRevisions = MutableStateFlow<Map<SpeakerKey, Long>>(emptyMap())
    val queueRevisions: StateFlow<Map<SpeakerKey, Long>> = _queueRevisions.asStateFlow()

    // Touched only on [work].
    private val records = LinkedHashMap<String, Record>()
    private var names = emptyMap<String, String>()
    private var active: SpeakerKey? = store.load()
    private var tokens = 0L
    private val slides = HashMap<Pair<SpeakerKey, SpeakerControl>, Channel<Long>>()
    private val earlyOutcomes = LinkedHashMap<String, SpeakerEvents.Command>()
    private val live = HashSet<String>()

    /** Read from any thread, for the players' clocks. */
    private val skews = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private class Record(val serverId: String) {
        var status = ServerSpeakers.Status.Loading
        var canControl = false
        val confirmed = LinkedHashMap<String, TracksideSpeaker>()
        val pending = LinkedHashMap<Pair<String, SpeakerControl>, Pending>()
        var etag: String? = null
        var refreshedAt: Long? = null
    }

    private data class Pending(
        val token: Long,
        val speakerId: String,
        val change: SpeakerChange,
        val baseRevision: Long,
        val commandId: String? = null,
        val expected: TracksideSpeaker? = null,
        val settleBy: Instant? = null,
        val done: Boolean = false,
        /** For a queue moved here from the phone: play there again if the speaker couldn't take it. */
        val onFailure: (() -> Unit)? = null,
    )

    // MARK: Servers and events

    /** Keeps to the Trackside servers you have: a removed or signed-out one's speakers go with it. */
    fun watchServers(servers: Flow<List<SpeakerServer>>) {
        scope.launch {
            servers.collect { list ->
                withContext(work) {
                    names = list.associate { it.id to it.name }
                    records.keys.filterNot { it in names }.toList().forEach(::dropServer)
                    publish()
                }
            }
        }
    }

    /** A server's events connected (or reconnected): what it missed isn't replayed, so read the list again. */
    fun connected(serverId: String) {
        scope.launch(work) {
            live += serverId
            // A new connection may be a new server run, whose list an old ETag can't stand for.
            refreshNow(serverId, revalidate = false)
        }
    }

    /** The events stopped: the app went to the background, or the server can't be reached. */
    fun disconnected(serverId: String) {
        scope.launch(work) { live -= serverId }
    }

    /** The server forgot this phone: its speakers aren't yours to see until it's paired again. */
    fun signedOut(serverId: String) {
        scope.launch(work) {
            dropServer(serverId)
            publish()
        }
    }

    /** Reads every server's speakers again, unless its events are live and it was read lately. */
    fun refreshIfStale(maxAgeMs: Long = 15_000) {
        scope.launch(work) {
            val clock = now().toEpochMilli()
            names.keys.forEach { id ->
                val record = records[id]
                val fresh = record != null && id in live && record.refreshedAt?.let { clock - it < maxAgeMs } == true
                if (!fresh) scope.launch(work) { refreshNow(id) }
            }
        }
    }

    fun refresh(serverId: String) {
        scope.launch(work) { refreshNow(serverId) }
    }

    /** One server event; anything but a speaker event is ignored. */
    suspend fun handle(serverId: String, event: TracksideEvent) {
        if (event.type !in SpeakerEvents.all) return
        withContext(work) {
            val record = records[serverId] ?: return@withContext
            when (event.type) {
                SpeakerEvents.ADDED, SpeakerEvents.UPDATED -> decode(event, TracksideSpeaker.serializer())?.let { updated(record, it) }
                SpeakerEvents.REMOVED -> decode(event, SpeakerEvents.Removed.serializer())?.let { removed(record, it.id, said = true) }
                SpeakerEvents.PROGRESS -> decode(event, SpeakerEvents.Progress.serializer())?.let { progress(record, it) }
                SpeakerEvents.QUEUE -> decode(event, SpeakerEvents.Queue.serializer())?.let {
                    _queueRevisions.value = _queueRevisions.value + (SpeakerKey(serverId, it.id) to it.revision)
                }
                SpeakerEvents.COMMAND -> decode(event, SpeakerEvents.Command.serializer())?.let { outcome(record, it) }
                SpeakerEvents.STATE -> decode(event, SpeakerEvents.State.serializer())?.let { serverState(record, it.state) }
            }
            publish()
        }
    }

    private fun <T> decode(event: TracksideEvent, serializer: KSerializer<T>): T? =
        runCatching { TracksideJson.decodeFromString(serializer, event.data) }.getOrNull()

    private suspend fun refreshNow(serverId: String, revalidate: Boolean = true) {
        val client = clientFor(serverId) ?: return
        val record = records[serverId]
        val access = try {
            withContext(io) { client.access() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Only a server already showing speakers says it can't be reached; one never seen stays hidden.
            record?.let { it.status = ServerSpeakers.Status.Unreachable; publish() }
            return
        }
        if (access == TracksideClient.SpeakerAccess.None) {
            dropServer(serverId)
            publish()
            return
        }
        val target = records.getOrPut(serverId) { Record(serverId) }
        target.canControl = access == TracksideClient.SpeakerAccess.Control
        if (record == null) publish()
        val answer = try {
            withContext(io) { client.speakers(target.etag.takeIf { revalidate }) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: TracksideError.NotFound) {
            dropServer(serverId)
            publish()
            return
        } catch (_: Exception) {
            target.status = ServerSpeakers.Status.Unreachable
            publish()
            return
        }
        skews[serverId] = client.clockSkewMs
        target.refreshedAt = now().toEpochMilli()
        if (answer != null) {
            val (list, etag) = answer
            target.etag = etag
            // A fresh list is the truth whatever the revisions say: they only count within one server run.
            target.confirmed.clear()
            list.speakers.forEach { target.confirmed[it.id] = it }
            target.pending.keys.filter { it.first !in target.confirmed }.forEach { target.pending.remove(it) }
            target.status = status(list.state)
        } else if (target.status == ServerSpeakers.Status.Loading || target.status == ServerSpeakers.Status.Unreachable) {
            target.status = ServerSpeakers.Status.Ok
        }
        val chosen = active
        if (chosen != null && chosen.serverId == serverId && target.status == ServerSpeakers.Status.Ok && chosen.speakerId !in target.confirmed) {
            setActive(null)
        }
        publish()
    }

    private fun status(state: String) = when (state) {
        "ok" -> ServerSpeakers.Status.Ok
        "connecting" -> ServerSpeakers.Status.Connecting
        else -> ServerSpeakers.Status.Unavailable
    }

    private fun updated(record: Record, speaker: TracksideSpeaker) {
        val old = record.confirmed[speaker.id]
        if (old != null && speaker.revision < old.revision) return
        record.confirmed[speaker.id] = speaker
        // A command the server finished is shown by this update once it's newer than what it expected from.
        record.pending.entries.removeAll { (key, pending) -> key.first == speaker.id && pending.done && speaker.revision > pending.baseRevision }
        val key = SpeakerKey(record.serverId, speaker.id)
        if (active == key && old?.availabilityValue != TracksideSpeaker.Availability.Unavailable && speaker.availabilityValue == TracksideSpeaker.Availability.Unavailable) {
            say("${speaker.label} went offline.", key)
            setActive(null)
        }
    }

    private fun removed(record: Record, id: String, said: Boolean) {
        val old = record.confirmed.remove(id)
        record.pending.keys.filter { it.first == id }.forEach { record.pending.remove(it) }
        val key = SpeakerKey(record.serverId, id)
        if (active == key) {
            if (said) say("${old?.label ?: "The speaker"} isn't available anymore.", key)
            setActive(null)
        }
    }

    private fun progress(record: Record, progress: SpeakerEvents.Progress) {
        val speaker = record.confirmed[progress.id] ?: return
        if (progress.revision < speaker.revision) return
        val playback = (speaker.playback ?: TracksideSpeaker.Playback()).copy(state = progress.state, positionMs = progress.positionMs, positionAt = progress.positionAt)
        record.confirmed[progress.id] = speaker.copy(playback = playback)
        // A seek the server has acted on shows in where the speaker is now.
        record.pending.entries.removeAll { (key, pending) -> key.first == progress.id && key.second == SpeakerControl.Seek && pending.done }
    }

    private fun outcome(record: Record, event: SpeakerEvents.Command) {
        val entry = record.pending.entries.firstOrNull { it.value.commandId == event.commandId }
        if (entry == null) {
            // The event can beat the command's own answer here; keep it for when that arrives.
            earlyOutcomes[event.commandId] = event
            while (earlyOutcomes.size > 32) earlyOutcomes.remove(earlyOutcomes.keys.first())
            return
        }
        settleOutcome(record, entry.key, entry.value, event)
    }

    private fun settleOutcome(record: Record, key: Pair<String, SpeakerControl>, pending: Pending, event: SpeakerEvents.Command) {
        if (event.outcome == "failed") {
            record.pending.remove(key)
            pending.onFailure?.invoke()
            val name = record.confirmed[pending.speakerId]?.label ?: "The speaker"
            val error = event.error
            val service = error?.serviceId?.let { serviceName(record.serverId, it) }
            val line = error?.reason?.let { com.luke.tracks.streaming.RelayWords.serviceReason(it, service, error.holderName) } ?: failureText(name, error?.code)
            say(line, SpeakerKey(record.serverId, pending.speakerId))
            return
        }
        val confirmedRevision = record.confirmed[pending.speakerId]?.revision ?: 0
        if (confirmedRevision > pending.baseRevision) record.pending.remove(key) else record.pending[key] = pending.copy(done = true)
    }

    private fun serverState(record: Record, state: String) {
        val before = record.status
        record.status = status(state)
        when (record.status) {
            ServerSpeakers.Status.Ok -> if (before != ServerSpeakers.Status.Ok) scope.launch(work) { refreshNow(record.serverId) }
            ServerSpeakers.Status.Unavailable -> {
                record.confirmed.clear()
                record.pending.clear()
                if (active?.serverId == record.serverId) {
                    say("Speakers aren't available right now.", active)
                    setActive(null)
                }
            }
            else -> Unit
        }
    }

    private fun dropServer(serverId: String) {
        records.remove(serverId)
        skews.remove(serverId)
        if (active?.serverId == serverId) setActive(null)
    }

    // MARK: Choosing

    /** Shows and controls [key] in the players, without changing what it plays. */
    fun choose(key: SpeakerKey) {
        scope.launch(work) {
            setActive(key)
            publish()
        }
    }

    /** Back to this phone in the players; whatever is on the speaker keeps playing. */
    fun release() {
        scope.launch(work) {
            setActive(null)
            publish()
        }
    }

    private fun setActive(key: SpeakerKey?) {
        if (active == key) return
        active = key
        store.save(key)
    }

    // MARK: Commands

    fun play(key: SpeakerKey) = transport(key, "play")
    fun pause(key: SpeakerKey) = transport(key, "pause")
    fun stop(key: SpeakerKey) = transport(key, "stop")
    fun next(key: SpeakerKey) = transport(key, "next")
    fun previous(key: SpeakerKey) = transport(key, "previous")

    fun jump(key: SpeakerKey, itemId: String) =
        issue(key, SpeakerChange.Transport("jump")) { it.playback(key.speakerId, TracksideSpeakerPlayback("jump", itemId = itemId)) }

    private fun transport(key: SpeakerKey, action: String) =
        issue(key, SpeakerChange.Transport(action)) { it.playback(key.speakerId, TracksideSpeakerPlayback(action)) }

    /** The latest position wins; positions in between may never be sent. */
    fun seek(key: SpeakerKey, positionMs: Long) =
        issue(key, SpeakerChange.Seek(positionMs), coalesce = true) { it.playback(key.speakerId, TracksideSpeakerPlayback("seek", positionMs = positionMs)) }

    fun setVolume(key: SpeakerKey, volume: Int) {
        val value = volume.coerceIn(0, 100)
        issue(key, SpeakerChange.Volume(value), coalesce = true) { it.patch(key.speakerId, TracksideSpeakerPatch(volume = value)) }
    }

    fun setGroupVolume(key: SpeakerKey, volume: Int) {
        val value = volume.coerceIn(0, 100)
        issue(key, SpeakerChange.GroupVolume(value), coalesce = true) { it.patch(key.speakerId, TracksideSpeakerPatch(groupVolume = value)) }
    }

    fun setMuted(key: SpeakerKey, muted: Boolean) =
        issue(key, SpeakerChange.Muted(muted)) { it.patch(key.speakerId, TracksideSpeakerPatch(muted = muted)) }

    fun setPowered(key: SpeakerKey, powered: Boolean) =
        issue(key, SpeakerChange.Powered(powered)) { it.patch(key.speakerId, TracksideSpeakerPatch(powered = powered)) }

    fun setShuffle(key: SpeakerKey, on: Boolean) =
        issue(key, SpeakerChange.Shuffle(on)) { it.patch(key.speakerId, TracksideSpeakerPatch(shuffle = on)) }

    /** off, one or all. */
    fun setRepeat(key: SpeakerKey, repeat: String) =
        issue(key, SpeakerChange.Repeat(repeat)) { it.patch(key.speakerId, TracksideSpeakerPatch(repeat = repeat)) }

    /** Groups [memberIds] with [key] leading; empty ungroups everyone from it. */
    fun setMembers(key: SpeakerKey, memberIds: List<String>) =
        issue(key, SpeakerChange.Members(memberIds.filter { it != key.speakerId }.distinct())) { client ->
            client.members(key.speakerId, memberIds.filter { it != key.speakerId }.distinct())
        }

    fun leaveGroup(key: SpeakerKey) = issue(key, SpeakerChange.LeaveGroup) { it.leaveGroup(key.speakerId) }

    /**
     * Plays [request] on the speaker and makes it the one the players show. When the server
     * takes it, [handoff] pauses this phone; if it then fails, the phone plays again.
     */
    fun play(key: SpeakerKey, request: TracksideSpeakerPlayRequest, preview: TracksideSpeaker.NowPlaying?, handoff: LocalHandoff?) {
        scope.launch(work) {
            setActive(key)
            var paused = false
            issueNow(
                key,
                SpeakerChange.Play(preview, request.positionMs ?: 0, request.shuffle, request.repeat),
                coalesce = false,
                onAccepted = { paused = handoff?.pauseForSpeaker() == true },
                onFailure = {
                    // Back to the phone: it plays again, and the players show it.
                    if (paused) handoff?.resumeHere()
                    if (active == key) setActive(null)
                },
            ) { it.play(key.speakerId, request) }
        }
    }

    /** Up Next on the speaker, from the song on now. */
    suspend fun queue(key: SpeakerKey, limit: Int = 100): TracksideSpeakerQueue? {
        val client = clientFor(key.serverId) ?: return null
        return runCatching { withContext(io) { client.queue(key.speakerId, limit) } }.getOrNull()
    }

    /** The server's clock now, for working out where a speaker is in its song. */
    fun serverNow(serverId: String): Instant = now().plusMillis(skews[serverId] ?: 0)

    private fun issue(key: SpeakerKey, change: SpeakerChange, coalesce: Boolean = false, send: suspend (SpeakerClient) -> TracksideSpeakerCommand) {
        scope.launch(work) { issueNow(key, change, coalesce, send = send) }
    }

    private suspend fun issueNow(
        key: SpeakerKey,
        change: SpeakerChange,
        coalesce: Boolean,
        onAccepted: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
        send: suspend (SpeakerClient) -> TracksideSpeakerCommand,
    ) {
        val record = records[key.serverId] ?: return
        val speaker = record.confirmed[key.speakerId] ?: return
        val slot = key.speakerId to change.control
        val token = ++tokens
        // A newer command on the same control replaces the older one's expectation.
        record.pending.remove(slot)
        record.pending[slot] = Pending(token, key.speakerId, change, speaker.revision, onFailure = onFailure)
        publish()
        if (!coalesce) {
            scope.launch(work) { deliver(key, slot, token, onAccepted, send) }
            return
        }
        val lane = slides.getOrPut(key to change.control) {
            Channel<Long>(Channel.CONFLATED).also { channel ->
                scope.launch(work) {
                    for (latest in channel) {
                        val current = records[key.serverId]?.pending?.get(slot)?.takeIf { it.token == latest } ?: continue
                        deliver(key, slot, current.token, null, sendFor(current.change, key))
                        delay(SLIDER_INTERVAL_MS)
                    }
                }
            }
        }
        lane.trySend(token)
    }

    /** The request for a slider's value, made again from the change so only the latest is sent. */
    private fun sendFor(change: SpeakerChange, key: SpeakerKey): suspend (SpeakerClient) -> TracksideSpeakerCommand = when (change) {
        is SpeakerChange.Volume -> { c -> c.patch(key.speakerId, TracksideSpeakerPatch(volume = change.volume)) }
        is SpeakerChange.GroupVolume -> { c -> c.patch(key.speakerId, TracksideSpeakerPatch(groupVolume = change.volume)) }
        is SpeakerChange.Seek -> { c -> c.playback(key.speakerId, TracksideSpeakerPlayback("seek", positionMs = change.positionMs)) }
        else -> error("not a slider: $change")
    }

    private suspend fun deliver(
        key: SpeakerKey,
        slot: Pair<String, SpeakerControl>,
        token: Long,
        onAccepted: (() -> Unit)?,
        send: suspend (SpeakerClient) -> TracksideSpeakerCommand,
    ) {
        val client = clientFor(key.serverId)
        val answer = if (client == null) {
            Result.failure(IOException("no client"))
        } else {
            try {
                Result.success(withContext(io) { send(client) })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        val record = records[key.serverId] ?: return
        val pending = record.pending[slot]?.takeIf { it.token == token }
        answer.onFailure { error ->
            val name = record.confirmed[key.speakerId]?.label ?: "The speaker"
            if (pending != null) {
                record.pending.remove(slot)
                pending.onFailure?.invoke()
            }
            if (error is TracksideError.NotFound) removed(record, key.speakerId, said = false)
            // A failure overtaken by a newer value on the same control says nothing: that one decides.
            if (pending != null || error is TracksideError.NotFound) say(refusalText(name, error), key)
            publish()
            return
        }
        val command = answer.getOrThrow()
        onAccepted?.invoke()
        if (pending == null) return
        val settleBy = command.settleBy?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: serverNow(key.serverId).plusMillis(pending.change.control.settleMs)
        val accepted = pending.copy(commandId = command.commandId, expected = command.speaker, settleBy = settleBy)
        record.pending[slot] = accepted
        earlyOutcomes.remove(command.commandId)?.let { settleOutcome(record, slot, accepted, it) }
        publish()
        if (record.pending[slot]?.token == token) {
            scope.launch(work) { settleLater(key, slot, token, settleBy) }
        }
    }

    /** Past `settle_by` without an outcome: drop the expectation, read the speaker, and say so if it didn't happen. */
    private suspend fun settleLater(key: SpeakerKey, slot: Pair<String, SpeakerControl>, token: Long, settleBy: Instant) {
        val wait = settleBy.toEpochMilli() - serverNow(key.serverId).toEpochMilli()
        if (wait > 0) delay(wait)
        val record = records[key.serverId] ?: return
        val pending = record.pending[slot]?.takeIf { it.token == token } ?: return
        record.pending.remove(slot)
        publish()
        val client = clientFor(key.serverId) ?: return
        val fresh = try {
            withContext(io) { client.speaker(key.speakerId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: TracksideError.NotFound) {
            records[key.serverId]?.let { removed(it, key.speakerId, said = true) }
            publish()
            return
        } catch (_: Exception) {
            null
        }
        val now = records[key.serverId] ?: return
        if (fresh != null) updatedFromRead(now, fresh)
        val expected = pending.expected
        if (!pending.done && expected != null && (fresh == null || !pending.change.control.settled(expected, fresh))) {
            pending.onFailure?.invoke()
            say("${(fresh ?: expected).label} didn't respond.", key)
        }
        publish()
    }

    /** A speaker read on its own is newer than anything before it in this run. */
    private fun updatedFromRead(record: Record, speaker: TracksideSpeaker) {
        val old = record.confirmed[speaker.id]
        if (old == null || speaker.revision >= old.revision) record.confirmed[speaker.id] = speaker
    }

    // MARK: Showing

    private fun say(text: String, key: SpeakerKey?) {
        _messages.tryEmit(SpeakerMessage(text, key))
    }

    private fun publish() {
        val servers = records.values.filter { it.serverId in names }.map { record ->
            val nowServer = serverNow(record.serverId)
            ServerSpeakers(
                serverId = record.serverId,
                serverName = names[record.serverId].orEmpty(),
                status = record.status,
                canControl = record.canControl,
                speakers = SpeakerDisplay.apply(record.confirmed.values.toList(), record.pending.values.map { it.toShown() }, nowServer),
            )
        }
        val connecting = records.values.flatMap { record ->
            record.pending.values.filter { it.change is SpeakerChange.Play }.map { SpeakerKey(record.serverId, it.speakerId) }
        }.toSet()
        _state.value = SpeakersState(servers, active, connecting)
    }

    private fun Pending.toShown() = SpeakerDisplay.Shown(speakerId, change, expected)

    private fun failureText(name: String, code: String?) = when (code) {
        "speakers_unavailable" -> "Speakers aren't available right now."
        "timed_out" -> "$name didn't respond."
        "speaker_offline", "conflict" -> "$name is offline."
        else -> "$name couldn't do that."
    }

    private fun refusalText(name: String, error: Throwable) = when (error) {
        is TracksideError.SpeakersUnavailable -> "Speakers aren't available right now."
        is TracksideError.NotFound -> "$name isn't available anymore."
        is TracksideError.RateLimited -> "One moment. Try again shortly."
        is TracksideError.Server -> when {
            error.reason == "speaker_offline" -> "$name is offline."
            error.reason == "powered_off" -> "$name is off."
            error.reason == "not_playable" -> "That can't play on $name."
            error.code == "unsupported" -> "$name can't do that."
            else -> "$name couldn't do that."
        }
        is IOException, is TracksideError.Unproven, is com.luke.tracks.net.Unproven -> "Couldn't reach your server."
        else -> "$name couldn't do that."
    }

    companion object {
        /** Sliders send at most ten values a second, as the server asks. */
        const val SLIDER_INTERVAL_MS = 100L
    }
}

/** The name to show for a speaker. */
val TracksideSpeaker.label: String get() = name.ifBlank { "Speaker" }

/** How pending commands show on the speakers they change, and on the speakers a regrouping moves. */
object SpeakerDisplay {
    data class Shown(val speakerId: String, val change: SpeakerChange, val expected: TracksideSpeaker?)

    fun apply(confirmed: List<TracksideSpeaker>, pending: List<Shown>, nowServer: Instant): List<TracksideSpeaker> {
        if (pending.isEmpty()) return confirmed
        val byId = LinkedHashMap<String, TracksideSpeaker>()
        confirmed.forEach { byId[it.id] = it }
        pending.forEach { shown ->
            val base = byId[shown.speakerId] ?: return@forEach
            byId[shown.speakerId] = shown.expected?.let { shown.change.control.merge(it, base) } ?: shown.change.applyTo(base, nowServer)
        }
        // A regrouping moves the other speakers too: in with the leader, or out on their own.
        pending.filter { it.change.control == SpeakerControl.Group }.forEach { shown ->
            val leader = byId[shown.speakerId] ?: return@forEach
            val group = leader.group?.takeIf { leader.isLeader }
            val members = group?.memberIds.orEmpty().filter { it != leader.id }.toSet()
            byId.values.toList().forEach { other ->
                if (other.id == leader.id) return@forEach
                when {
                    other.id in members -> byId[other.id] = other.copy(
                        group = TracksideSpeaker.Group(leaderId = leader.id, memberIds = group!!.memberIds, role = "member"),
                        playback = leader.playback ?: other.playback,
                        nowPlaying = leader.nowPlaying ?: other.nowPlaying,
                    )
                    other.group?.leaderId == leader.id -> byId[other.id] = other.copy(group = null)
                }
            }
        }
        return byId.values.toList()
    }
}

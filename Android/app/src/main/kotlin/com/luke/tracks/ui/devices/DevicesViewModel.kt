package com.luke.tracks.ui.devices

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luke.tracks.AppGraph
import com.luke.tracks.graph
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.net.ServerLinks
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.speakers.CastQueue
import com.luke.tracks.speakers.PlayerHandoff
import com.luke.tracks.speakers.ServerSpeakers
import com.luke.tracks.speakers.SpeakerControls
import com.luke.tracks.speakers.SpeakerKey
import com.luke.tracks.speakers.SpeakerMessage
import com.luke.tracks.speakers.Speakers
import com.luke.tracks.speakers.SpeakersRepository
import com.luke.tracks.speakers.SpeakersState
import com.luke.tracks.speakers.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Devices as the sheet, the players and Settings show them. */
@Immutable
data class DevicesUi(
    /** False with no server offering speakers: only the system's outputs are shown. */
    val offered: Boolean = false,
    val sections: List<DeviceSection> = emptyList(),
    val active: SpeakerKey? = null,
    /** The speaker the players stand in for, when it has something on. */
    val casting: SpeakerUi? = null,
) {
    fun speaker(key: SpeakerKey): SpeakerUi? = sections.firstOrNull { it.serverId == key.serverId }?.let { section ->
        section.rows.firstOrNull { it.key == key } ?: section.all.firstOrNull { it.key == key }
    }
}

@Immutable
data class DeviceSection(
    val serverId: String,
    /** The server's name, shown only with more than one server. */
    val title: String?,
    val serverName: String,
    val status: ServerSpeakers.Status,
    /** Leaders and speakers on their own; members fold under their leader. */
    val rows: List<SpeakerUi>,
    /** Every speaker, members too, for grouping. */
    val all: List<SpeakerUi>,
)

@Immutable
data class SpeakerUi(
    val key: SpeakerKey,
    val speaker: TracksideSpeaker,
    val controls: SpeakerControls,
    val canControl: Boolean,
    val connecting: Boolean,
    val chosen: Boolean,
    /** The other speakers playing with it, by name. */
    val memberNames: List<String>,
    /** Its leader's name, for a member. */
    val leaderName: String?,
    val artwork: Uri?,
) {
    val name: String get() = speaker.label
    val availability: TracksideSpeaker.Availability get() = speaker.availabilityValue
    val isPlaying: Boolean get() = speaker.stateValue == TracksideSpeaker.State.Playing || speaker.stateValue == TracksideSpeaker.State.Buffering

    /** Whether tapping it can do anything: offline speakers can't, one that's off turns on to play. */
    val reachable: Boolean get() = availability != TracksideSpeaker.Availability.Unavailable

    /** Speakers it may be grouped with. */
    val groupable: List<String> get() = speaker.canGroupWith.orEmpty()
}

/**
 * The bridge between the speakers and their screens. The state is built off the main thread
 * and collected on it; every action returns at once, with the work done by the repository.
 */
class DevicesViewModel(private val graph: AppGraph, private val repository: SpeakersRepository?) : ViewModel() {
    private val local = MutableSharedFlow<SpeakerMessage>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val state: StateFlow<DevicesUi> = (repository?.state ?: flowOf(SpeakersState()))
        .map(::ui)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository?.state?.value?.let(::ui) ?: DevicesUi())

    val messages: Flow<SpeakerMessage> = merge(repository?.messages ?: emptyFlow(), local)

    /** While Devices is open its own snackbar says what happened; the screens behind stay quiet. */
    var sheetOpen by mutableStateOf(false)

    /** Devices opened: read the speakers again if it's been a while. */
    fun opened() {
        repository?.refreshIfStale()
    }

    /**
     * A tap on a speaker: the phone's queue moves there and the players follow it. With
     * nothing here it could play, the players just show what's on it.
     */
    fun castTo(key: SpeakerKey) {
        val repository = repository ?: return
        viewModelScope.launch {
            // Music from a service plays here through the relay: the server moves its queue to the speaker.
            val relay = com.luke.tracks.streaming.Streaming.of(graph)?.relay
            val relaying = relay?.status?.value?.takeIf { it.isActive && it.serverId == key.serverId }
            if (relay != null && relaying != null) {
                repository.choose(key)
                if (!relay.transfer(key.speakerId)) repository.release()
                return@launch
            }
            val player = graph.player
            val built = withContext(Dispatchers.Default) {
                val playerState = player.state.value
                CastQueue.request(playerState, player.position.value, key.serverId)?.let { it to CastQueue.preview(playerState, it) }
            }
            val speaker = repository.state.value.speaker(key)
            when {
                built != null -> repository.play(key, built.first, built.second, PlayerHandoff(player))
                speaker?.nowPlaying != null -> repository.choose(key)
                player.state.value.current != null -> local.tryEmit(SpeakerMessage("Nothing playing here is on ${repository.state.value.server(key.serverId)?.serverName ?: "this server"}.", key))
                else -> repository.choose(key)
            }
        }
    }

    /**
     * Back to this phone. When everything on the speaker is in your library here, it carries
     * on here from the same place and the speaker stops; otherwise the speaker keeps playing.
     */
    fun bringHere() {
        val repository = repository ?: return
        val key = repository.state.value.active ?: return
        viewModelScope.launch {
            val speaker = repository.state.value.speaker(key)
            val queue = if (speaker != null && speaker.nowPlaying?.origin == "queue") repository.queue(key, limit = 500) else null
            val moved = queue?.let {
                withContext(Dispatchers.Default) {
                    val songs = graph.library.snapshot.value.songs.associateBy { it.mediaId }
                    val found = it.items.map { item -> item.songId?.let { id -> songs[ServerLibrary.mediaId(key.serverId, id)] } }
                    if (found.isEmpty() || found.any { song -> song == null }) null else found.filterNotNull()
                }
            }
            // A queue with any service's song comes here through the relay, as the server moves it.
            val streaming = com.luke.tracks.streaming.Streaming.of(graph)
            val relayed = queue?.items?.any { it.ref != null } == true || speaker?.nowPlaying?.ref != null
            if (moved == null && relayed && speaker != null && streaming != null && streaming.repository.state.value.server(key.serverId)?.plays == true) {
                val names = streaming.repository.state.value.server(key.serverId)?.services.orEmpty().associate { it.id to it.name }
                repository.release()
                streaming.relay.takeFrom(key.serverId, key.speakerId, names)
                return@launch
            }
            if (moved == null || speaker == null) {
                repository.release()
                return@launch
            }
            val position = com.luke.tracks.speakers.SpeakerClock.position(speaker, repository.serverNow(key.serverId))
            val origin = speaker.context?.name?.let { QueueOrigin(it, QueueOrigin.Kind.Other) }
            graph.player.play(moved, startIndex = 0, origin = origin, shuffle = false)
            graph.player.seekTo(position)
            repository.stop(key)
            repository.release()
        }
    }

    fun release() = repository?.release()
    fun choose(key: SpeakerKey) = repository?.choose(key)
    fun play(key: SpeakerKey) = repository?.play(key)
    fun pause(key: SpeakerKey) = repository?.pause(key)
    fun next(key: SpeakerKey) = repository?.next(key)
    fun previous(key: SpeakerKey) = repository?.previous(key)
    fun seek(key: SpeakerKey, positionMs: Long) = repository?.seek(key, positionMs)
    fun setVolume(key: SpeakerKey, volume: Int) = repository?.setVolume(key, volume)
    fun setGroupVolume(key: SpeakerKey, volume: Int) = repository?.setGroupVolume(key, volume)
    fun setMuted(key: SpeakerKey, muted: Boolean) = repository?.setMuted(key, muted)
    fun setPowered(key: SpeakerKey, powered: Boolean) = repository?.setPowered(key, powered)
    fun setShuffle(key: SpeakerKey, on: Boolean) = repository?.setShuffle(key, on)
    fun setRepeat(key: SpeakerKey, repeat: String) = repository?.setRepeat(key, repeat)
    fun setMembers(key: SpeakerKey, memberIds: List<String>) = repository?.setMembers(key, memberIds)
    fun leaveGroup(key: SpeakerKey) = repository?.leaveGroup(key)

    /** The server's clock, for where a speaker is in its song. Safe from any thread. */
    fun serverNow(serverId: String): Instant = repository?.serverNow(serverId) ?: Instant.now()

    private fun ui(state: SpeakersState): DevicesUi {
        val many = state.servers.size > 1
        val sections = state.servers.map { server ->
            // The relay for this phone is "This phone", not a speaker of its own.
            val speakers = server.speakers.filterNot { it.relay?.thisDevice == true }
            val names = speakers.associate { it.id to it.label }
            val all = speakers.map { speaker ->
                val key = SpeakerKey(server.serverId, speaker.id)
                val group = speaker.group
                SpeakerUi(
                    key = key,
                    speaker = speaker,
                    controls = SpeakerControls.of(speaker),
                    canControl = server.canControl,
                    connecting = key in state.connecting,
                    chosen = key == state.active,
                    memberNames = if (speaker.isLeader) group?.memberIds.orEmpty().filter { it != speaker.id }.mapNotNull { names[it] } else emptyList(),
                    leaderName = if (speaker.isMember) group?.leaderId?.let { names[it] } else null,
                    artwork = speaker.nowPlaying?.artworkId?.takeIf { it.isNotEmpty() }?.let { ServerLinks.cover(server.serverId, it) },
                )
            }
            val ids = all.map { it.speaker.id }.toSet()
            DeviceSection(
                serverId = server.serverId,
                title = server.serverName.takeIf { many },
                serverName = server.serverName,
                status = server.status,
                rows = all.filterNot { it.speaker.isMember && it.speaker.group?.leaderId in ids },
                all = all,
            )
        }
        val casting = state.casting?.let { speaker ->
            sections.firstOrNull { it.serverId == state.active?.serverId }?.all?.firstOrNull { it.speaker.id == speaker.id }
        }
        return DevicesUi(offered = sections.isNotEmpty(), sections = sections, active = state.active, casting = casting)
    }
}

@Composable
fun devicesViewModel(): DevicesViewModel {
    val graph = LocalContext.current.graph
    return viewModel(key = "devices") { DevicesViewModel(graph, Speakers.of(graph)) }
}

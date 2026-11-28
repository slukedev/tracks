package com.luke.tracks.ui.tv.nowplaying

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RepeatChoice
import com.luke.tracks.speakers.ServerSpeakers
import com.luke.tracks.speakers.SpeakerKey
import com.luke.tracks.ui.devices.DeviceSection
import com.luke.tracks.ui.devices.DevicesUi
import com.luke.tracks.ui.devices.DevicesViewModel
import com.luke.tracks.ui.devices.SpeakerUi
import com.luke.tracks.ui.devices.icon
import com.luke.tracks.ui.devices.rememberSpeakerPosition
import com.luke.tracks.ui.tv.components.TvIconButton
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSidePanel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.tracks_engine.songIdentity

/** What the Play On panel can ask for, so screenshots drive it with plain values. */
interface TvDeviceActions {
    /** Back to this TV. */
    fun bringHere()
    fun castTo(key: SpeakerKey)
    fun choose(key: SpeakerKey)
    fun setVolume(key: SpeakerKey, volume: Int)
    fun setMembers(key: SpeakerKey, memberIds: List<String>)
    fun leaveGroup(key: SpeakerKey)
}

/** The phone's Devices model as the panel's actions: the same moves, the same server rules. */
class ModelDeviceActions(private val model: DevicesViewModel) : TvDeviceActions {
    override fun bringHere() = model.bringHere()
    override fun castTo(key: SpeakerKey) = model.castTo(key)
    override fun choose(key: SpeakerKey) { model.choose(key) }
    override fun setVolume(key: SpeakerKey, volume: Int) { model.setVolume(key, volume) }
    override fun setMembers(key: SpeakerKey, memberIds: List<String>) { model.setMembers(key, memberIds) }
    override fun leaveGroup(key: SpeakerKey) { model.leaveGroup(key) }
}

/**
 * Play On: this TV first, then each server's speakers. Selecting a speaker moves the music
 * there, as on the phone; the speaker playing gets its volume (Minus and Plus, a tenth at a
 * time) and, where it can, the speakers it plays with.
 */
@Composable
fun TvDevicesPanel(visible: Boolean, ui: DevicesUi, actions: TvDeviceActions, onDismiss: () -> Unit) {
    // The panel keeps showing what it opened with while it slides away.
    var last by remember { mutableStateOf(ui) }
    SideEffect { if (visible) last = ui }
    val shown = if (visible) ui else last
    TvSidePanel(visible = visible, onDismiss = onDismiss, title = "Play On", width = 460.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TvListRow(
                onClick = { if (shown.active != null) actions.bringHere() },
                headline = "This TV",
                selected = shown.active == null,
                dense = true,
                leading = { RowGlyph(Icons.Rounded.Tv) },
                trailing = if (shown.active == null) ({ RowGlyph(Icons.Rounded.Check) }) else null,
            )
            shown.sections.forEach { section ->
                key(section.serverId) {
                    section.title?.let { Heading(it) }
                    calmLine(section)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    section.rows.forEach { row ->
                        key(row.key.speakerId) { SpeakerRow(row, actions) }
                    }
                }
            }
            val chosen = shown.active?.let(shown::speaker)
            if (chosen != null && chosen.canControl) Chosen(chosen, shown, actions)
        }
    }
}

@Composable
private fun SpeakerRow(row: SpeakerUi, actions: TvDeviceActions) {
    TvListRow(
        onClick = { if (row.canControl && row.controls.play) actions.castTo(row.key) else actions.choose(row.key) },
        headline = if (row.memberNames.isEmpty()) row.name else (listOf(row.name) + row.memberNames).joinToString(" + "),
        supporting = subtitle(row),
        selected = row.chosen,
        enabled = row.reachable,
        dense = true,
        leading = { RowGlyph(row.speaker.icon()) },
        trailing = if (row.chosen && !row.connecting) ({ RowGlyph(Icons.Rounded.Check) }) else null,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The speaker playing: its volume, and who it plays with. */
@Composable
private fun Chosen(cast: SpeakerUi, ui: DevicesUi, actions: TvDeviceActions) {
    val speaker = cast.speaker
    if (cast.controls.volume) {
        Heading("Volume")
        // Moves at once on press; the speaker settles it.
        var volume by remember(cast.key) { mutableStateOf(speaker.volume ?: 0) }
        LaunchedEffect(speaker.volume) { speaker.volume?.let { volume = it } }
        fun step(by: Int) {
            volume = (volume + by).coerceIn(0, 100)
            actions.setVolume(cast.key, volume)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvIconButton(Icons.Rounded.Remove, "Quieter", onClick = { step(-VolumeStep) }, enabled = volume > 0)
            Text(
                "$volume%",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TvIconButton(Icons.Rounded.Add, "Louder", onClick = { step(VolumeStep) }, enabled = volume < 100)
        }
    }
    val section = ui.sections.firstOrNull { it.serverId == cast.key.serverId } ?: return
    val candidates = cast.groupable.mapNotNull { id -> section.all.firstOrNull { it.speaker.id == id } }
    if (!cast.controls.group || (candidates.isEmpty() && !speaker.isMember)) return
    Heading("Play With")
    if (speaker.isMember && cast.leaderName != null) {
        TvListRow(onClick = { actions.leaveGroup(cast.key) }, headline = "Play on Its Own", supporting = "With ${cast.leaderName}", dense = true)
    }
    val members = if (speaker.isLeader) speaker.group?.memberIds.orEmpty().filter { it != speaker.id } else emptyList()
    candidates.forEach { other ->
        key(other.key.speakerId) {
            val checked = other.speaker.id in members
            TvListRow(
                onClick = { actions.setMembers(cast.key, if (checked) members - other.speaker.id else members + other.speaker.id) },
                headline = other.name,
                selected = checked,
                enabled = other.reachable,
                dense = true,
                leading = { RowGlyph(other.speaker.icon()) },
                trailing = if (checked) ({ RowGlyph(Icons.Rounded.Check) }) else null,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RowGlyph(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
}

/** What a speaker is doing, in as few words as will do: the phone's words. */
private fun subtitle(row: SpeakerUi): String? {
    val speaker = row.speaker
    val playing = speaker.nowPlaying
    return when {
        row.connecting -> "Connecting"
        row.availability == TracksideSpeaker.Availability.Unavailable -> "Offline"
        row.availability == TracksideSpeaker.Availability.PoweredOff -> "Off"
        row.leaderName != null -> "With ${row.leaderName}"
        playing != null && speaker.stateValue != TracksideSpeaker.State.Idle ->
            listOfNotNull(playing.title.takeIf { it.isNotBlank() }, playing.artist).joinToString(" · ").ifBlank { null }
        else -> null
    }
}

private fun calmLine(section: DeviceSection): String? = when (section.status) {
    ServerSpeakers.Status.Loading, ServerSpeakers.Status.Connecting -> if (section.rows.isEmpty()) "Looking for Speakers" else null
    ServerSpeakers.Status.Unavailable -> "Speakers Aren't Available Right Now"
    ServerSpeakers.Status.Unreachable -> "Can't Reach ${section.serverName}"
    ServerSpeakers.Status.Ok -> if (section.rows.isEmpty()) "No Speakers Yet" else null
}

private const val VolumeStep = 5

/**
 * The speaker playing, as the TV's player shows it: its song, where it is, and its controls.
 * The player's own screen draws it, so a speaker looks and steers like this TV.
 */
@Composable
fun rememberSpeakerPlayer(cast: SpeakerUi, model: DevicesViewModel): SpeakerPlayer {
    val position by rememberSpeakerPosition(cast.speaker) { model.serverNow(cast.key.serverId) }
    val player = remember(cast.key) { SpeakerPlayer(cast.key, model::play, model::pause, model::next, model::previous, model::seek, model::setShuffle, model::setRepeat) }
    SideEffect { player.show(cast) }
    LaunchedEffect(player) { snapshotFlow { position }.collect(player::moveTo) }
    return player
}

/** A speaker standing in for [PlayerController]: transport goes to the speaker; what a speaker can't do does nothing. */
class SpeakerPlayer(
    private val key: SpeakerKey,
    private val onPlay: (SpeakerKey) -> Unit,
    private val onPause: (SpeakerKey) -> Unit,
    private val onNext: (SpeakerKey) -> Unit,
    private val onPrevious: (SpeakerKey) -> Unit,
    private val onSeek: (SpeakerKey, Long) -> Unit,
    private val onShuffle: (SpeakerKey, Boolean) -> Unit,
    private val onRepeat: (SpeakerKey, String) -> Unit,
) : PlayerController {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()

    fun show(cast: SpeakerUi) {
        val next = stateOf(cast)
        if (next != _state.value) _state.value = next
    }

    fun moveTo(positionMs: Long) {
        _position.value = positionMs
    }

    override fun playPause() = if (_state.value.isPlaying) onPause(key) else onPlay(key)
    override fun next() = onNext(key)
    override fun previous() = onPrevious(key)
    override fun seekTo(positionMs: Long) = onSeek(key, positionMs)
    override fun setShuffle(on: Boolean) = onShuffle(key, on)
    override fun setRepeat(choice: RepeatChoice) = onRepeat(key, when (choice) {
        RepeatChoice.Off -> "off"
        RepeatChoice.One -> "one"
        RepeatChoice.All -> "all"
    })

    override fun play(songs: List<PlayableSong>, startIndex: Int, origin: QueueOrigin?, shuffle: Boolean) = Unit
    override fun playNext(song: PlayableSong) = Unit
    override fun addToQueue(song: PlayableSong) = Unit
    override fun removeFromQueue(index: Int) = Unit
    override fun moveInQueue(from: Int, to: Int) = Unit
    override fun skipTo(index: Int) = Unit
    override fun setSleepTimer(minutes: Int?) = Unit
    override fun startRadio() = Unit
    override fun playMoreLikeThis() = Unit
    override fun playFromSearch(query: String) = Unit

    companion object {
        /** The speaker's song and playback as the player's state; null song when it plays nothing. */
        fun stateOf(cast: SpeakerUi): PlayerState {
            val speaker = cast.speaker
            val playing = speaker.nowPlaying ?: return PlayerState()
            val song = PlayableSong(
                mediaId = "speaker:${cast.key.serverId}:${cast.key.speakerId}:${playing.itemId.orEmpty()}",
                uri = Uri.EMPTY,
                title = playing.title,
                artistName = playing.artist.orEmpty(),
                albumTitle = playing.album,
                artworkUri = cast.artwork,
                durationMs = playing.durationMs,
                identity = songIdentity(playing.title, playing.artist.orEmpty()),
            )
            return PlayerState(
                current = song,
                isPlaying = speaker.stateValue == TracksideSpeaker.State.Playing,
                isBuffering = speaker.stateValue == TracksideSpeaker.State.Buffering,
                isLoading = speaker.stateValue == TracksideSpeaker.State.Buffering || cast.connecting,
                durationMs = playing.durationMs ?: 0,
                queue = listOf(song),
                shuffle = speaker.playback?.shuffle == true,
                repeat = when (speaker.playback?.repeat) {
                    "one" -> RepeatChoice.One
                    "all" -> RepeatChoice.All
                    else -> RepeatChoice.Off
                },
                origin = speaker.context?.name?.let { QueueOrigin(it, QueueOrigin.Kind.Other) },
            )
        }
    }
}

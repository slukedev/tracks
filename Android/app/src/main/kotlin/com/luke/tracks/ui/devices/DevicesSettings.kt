package com.luke.tracks.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.speakers.ServerSpeakers
import com.luke.tracks.speakers.SpeakerKey
import com.luke.tracks.ui.navigation.currentNavigator
import com.luke.tracks.ui.settings.Footnote
import com.luke.tracks.ui.settings.GroupTitle
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.Tone
import com.luke.tracks.ui.settings.custom
import com.luke.tracks.ui.settings.group
import com.luke.tracks.ui.settings.info
import com.luke.tracks.ui.settings.link
import com.luke.tracks.ui.settings.toggle

/** Settings › Devices: every speaker on your servers, each opening its own page. */
@Composable
fun DevicesSettingsPage() {
    val model = devicesViewModel()
    val ui by model.state.collectAsStateWithLifecycle()
    val navigator = currentNavigator()
    LaunchedEffect(model) { model.opened() }
    SettingsScaffold(title = "Devices") {
        if (!ui.offered) {
            item(key = "none") { Footnote("Speakers your server can play on show here.") }
        }
        ui.sections.forEach { section ->
            group(title = section.title ?: "Speakers", key = "server:${section.serverId}") {
                when (section.status) {
                    ServerSpeakers.Status.Unavailable -> info("Speakers aren't available right now")
                    ServerSpeakers.Status.Unreachable -> info("Can't reach ${section.serverName} right now")
                    else -> if (section.all.isEmpty()) info(if (section.status == ServerSpeakers.Status.Ok) "No speakers yet" else "Looking for speakers")
                }
                section.all.forEach { row ->
                    link(row.name, summary = status(row), icon = row.speaker.icon(), tone = if (row.chosen) Tone.Primary else Tone.Secondary) {
                        navigator.open(speakerPage(row.key))
                    }
                }
            }
        }
    }
}

/**
 * One speaker's page: what it is and what's on, and the same controls as the big player's
 * device area (power, volume, mute, who it plays with), each only where the speaker has it.
 */
@Composable
fun SpeakerSettingsPage(key: SpeakerKey) {
    val model = devicesViewModel()
    val ui by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.opened() }
    val row = ui.speaker(key) ?: ui.sections.firstOrNull { it.serverId == key.serverId }?.all?.firstOrNull { it.key == key }
    val speaker = row?.speaker
    SettingsScaffold(title = row?.name ?: "Speaker", subtitle = speaker?.let(::description)) {
        if (row == null || speaker == null) {
            item(key = "gone") { Footnote("This speaker isn't available.") }
            return@SettingsScaffold
        }
        val controls = row.controls
        val playing = speaker.nowPlaying
        group(key = "now") {
            if (playing != null) {
                info(playing.title.ifBlank { "Untitled" }, playing.artist)
            } else {
                info("Nothing playing")
            }
            if (row.canControl && controls.play) {
                link(
                    if (row.chosen) "Playing here" else "Play here",
                    summary = if (row.chosen) "Shown in the player" else "Moves what's playing on this phone",
                    icon = Icons.Rounded.Speaker,
                    tone = Tone.Primary,
                    enabled = row.reachable && !row.chosen,
                ) { model.castTo(key) }
            }
            if (row.canControl && controls.playPause && playing != null) {
                link(
                    if (row.isPlaying) "Pause" else "Play",
                    icon = if (row.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    tone = Tone.Secondary,
                ) { if (row.isPlaying) model.pause(key) else model.play(key) }
            }
        }
        if (row.canControl && (controls.power || controls.volume)) {
            group("Sound", key = "sound") {
                if (controls.power) {
                    toggle("Power", checked = speaker.powered == true, icon = Icons.Rounded.PowerSettingsNew, tone = Tone.Tertiary) {
                        model.setPowered(key, it)
                    }
                }
                if (controls.volume) {
                    custom {
                        SpeakerVolume(
                            name = row.name,
                            volume = speaker.volume ?: 0,
                            muted = speaker.muted.takeIf { controls.mute },
                            onVolume = { model.setVolume(key, it) },
                            onMuted = { model.setMuted(key, it) },
                            enabled = row.reachable,
                        )
                        val groupVolume = speaker.groupVolume
                        if (speaker.isLeader && groupVolume != null && row.memberNames.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("All speakers")
                            SpeakerVolume(
                                name = "All speakers",
                                volume = groupVolume,
                                muted = null,
                                onVolume = { model.setGroupVolume(key, it) },
                                onMuted = null,
                                enabled = row.reachable,
                            )
                        }
                    }
                }
            }
        }
        if (row.canControl && controls.group && row.groupable.isNotEmpty()) {
            item(key = "together") {
                Column {
                    GroupTitle("Play together")
                    GroupPicker(key, model)
                }
            }
        }
    }
}

private fun status(row: SpeakerUi): String {
    val playing = row.speaker.nowPlaying
    return when {
    row.availability == TracksideSpeaker.Availability.Unavailable -> "Offline"
    row.availability == TracksideSpeaker.Availability.PoweredOff -> "Off"
    row.leaderName != null -> "With ${row.leaderName}"
    row.memberNames.isNotEmpty() -> "With ${row.memberNames.joinToString(", ")}"
    row.chosen -> "In the player"
    playing != null && row.isPlaying -> listOfNotNull(playing.title, playing.artist).joinToString(" · ")
    else -> "Ready"
    }
}

private fun description(speaker: TracksideSpeaker): String? =
    listOfNotNull(speaker.manufacturer, speaker.model).joinToString(" ").ifBlank { null }

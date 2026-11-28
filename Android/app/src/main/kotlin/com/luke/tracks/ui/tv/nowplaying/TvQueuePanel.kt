package com.luke.tracks.ui.tv.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.RadioState
import com.luke.tracks.playback.artistLabel
import com.luke.tracks.playback.titleLabel
import com.luke.tracks.ui.nowplaying.SteeringNote
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSidePanel

/**
 * Up Next beside the player: the song on now, then the queue. Select a song to play it; hold
 * select to take it out. On Tracks Radio, a line says how it's steering.
 */
@Composable
fun TvQueuePanel(visible: Boolean, state: PlayerState, radio: RadioState?, player: PlayerController, onDismiss: () -> Unit) {
    TvSidePanel(visible = visible, onDismiss = onDismiss, title = "Up Next") {
        val base = state.index + 1
        val upNext = state.upNext
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            state.current?.let { song ->
                item(key = "now") {
                    TvListRow(
                        onClick = onDismiss,
                        headline = song.titleLabel,
                        supporting = song.artistLabel,
                        selected = true,
                        dense = true,
                        leading = { TvArtwork(song.artworkUri, song.albumTitle ?: song.title, Modifier.size(44.dp), sizePx = 96, shape = RoundedCornerShape(8.dp)) },
                        trailing = { Icon(Icons.Rounded.GraphicEq, contentDescription = "Now Playing", tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            if (upNext.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (radio != null) "Picking the next song…" else "Nothing after this song",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(upNext, key = { i, song -> "${song.mediaId}:${base + i}" }) { i, song ->
                TvListRow(
                    onClick = { player.skipTo(base + i) },
                    onLongClick = { player.removeFromQueue(base + i) },
                    headline = song.titleLabel,
                    supporting = song.artistLabel,
                    dense = true,
                    leading = { TvArtwork(song.artworkUri, song.albumTitle ?: song.title, Modifier.size(44.dp), sizePx = 96, shape = RoundedCornerShape(8.dp)) },
                )
            }
            if (radio != null) {
                item(key = "steering") { SteeringNote(radio.steering, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            }
        }
    }
}

/** How long until the music stops, or off. */
@Composable
fun TvSleepPanel(visible: Boolean, sleepAtMs: Long?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    TvSidePanel(visible = visible, onDismiss = onDismiss, title = "Sleep Timer", width = 360.dp) {
        SleepChoices.forEach { (minutes, label) ->
            TvListRow(
                onClick = { onPick(minutes) },
                headline = label,
                dense = true,
                leading = { Icon(Icons.Rounded.Bedtime, contentDescription = null) },
            )
        }
        if (sleepAtMs != null) {
            TvListRow(onClick = { onPick(null) }, headline = "Turn Off", dense = true, leading = { Icon(Icons.Rounded.TimerOff, contentDescription = null) })
        }
    }
}

private val SleepChoices = listOf(15 to "15 Minutes", 30 to "30 Minutes", 45 to "45 Minutes", 60 to "1 Hour")

package com.luke.tracks.ui.play.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.play.playsLabel
import kotlinx.coroutines.launch

/**
 * Everything you can do with a song, as a sheet with the song on top: what a long press opens
 * anywhere on the Play pages, and the overflow button on a row.
 */
@Composable
fun SongActionsSheet(
    song: SongRef,
    actions: PlayActions,
    onDismiss: () -> Unit,
    onHearLess: ((SongRef) -> Unit)? = null,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun run(block: () -> Unit) {
        block()
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(song.artworkUrl, song.coverSeed, Modifier.size(56.dp), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(song.artistName, song.plays?.let(::playsLabel)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (song.playable == null) {
                        Text(
                            "Not in your music on this phone",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.size(8.dp))
            if (song.playable != null) {
                Action(Icons.Rounded.PlayArrow, "Play") { run { actions.play(song) } }
                Action(Icons.AutoMirrored.Rounded.QueueMusic, "Play next") { run { actions.playNext(song) } }
                Action(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to queue") { run { actions.addToQueue(song) } }
            }
            Action(Icons.AutoMirrored.Rounded.OpenInNew, "Open in another app") { run { actions.openElsewhere(song) } }
            Action(Icons.Rounded.Person, "Go to artist") { run { actions.goToArtist(song.artistName) } }
            Action(Icons.Rounded.Share, "Share") { run { actions.share(song) } }
            Action(Icons.Rounded.RemoveCircleOutline, "Hear less of this", supporting = "Leaves your mixes and Tracks Radio") {
                run { onHearLess?.invoke(song) ?: actions.hearLess(song.identity) }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).pressable(onClick = onClick, pressedScale = 0.98f),
    )
}

/** Room for the floating mini player under the last row. */
val MiniPlayerClearance = 96.dp

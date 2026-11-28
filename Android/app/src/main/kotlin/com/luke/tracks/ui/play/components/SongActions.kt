package com.luke.tracks.ui.play.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.library.ActionSheet
import com.luke.tracks.ui.library.ActionSheetContent
import com.luke.tracks.ui.library.ActionSheetHeader
import com.luke.tracks.ui.library.SheetAction
import com.luke.tracks.ui.navigation.SongRoute

/**
 * A song's press-and-hold sheet on the Play pages, in the iPhone's order: Play, Play next and
 * Add to queue; Add to your music where its server can fetch it; Go to album, Go to artist and
 * Your stats; Open in another app for a song this phone can't play; Share; Hear less of this.
 */
@Composable
fun SongActionsSheet(
    song: SongRef,
    actions: PlayActions,
    links: GoTo,
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onHearLess: ((SongRef) -> Unit)? = null,
) {
    val (quick, more) = songSheetActions(song, actions, links, onPlay, onAdd, onHearLess)
    ActionSheet(quick, more, onDismiss) { SongSheetHeader(song) }
}

/** The sheet's contents without the sheet, for screenshots. */
@Composable
fun SongActionsPreview(song: SongRef, actions: PlayActions, links: GoTo, onAdd: (() -> Unit)? = null) {
    val (quick, more) = songSheetActions(song, actions, links, onAdd = onAdd)
    ActionSheetContent(quick, more, onAction = {}) { SongSheetHeader(song) }
}

@Composable
private fun SongSheetHeader(song: SongRef) {
    ActionSheetHeader(
        title = song.title,
        subtitle = listOfNotNull(song.artistName, song.albumTitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
        note = if (song.playable == null) "Not in your library" else null,
    ) {
        Artwork(song.artworkUrl, song.coverSeed, Modifier.size(64.dp), shape = RoundedCornerShape(16.dp))
    }
}

private fun songSheetActions(
    song: SongRef,
    actions: PlayActions,
    links: GoTo,
    onPlay: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onHearLess: ((SongRef) -> Unit)? = null,
): Pair<List<SheetAction>, List<SheetAction>> {
    val quick = if (song.playable == null) emptyList() else listOf(
        SheetAction("Play", Icons.Rounded.PlayArrow) { onPlay?.invoke() ?: actions.play(song) },
        SheetAction("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { actions.playNext(song) },
        SheetAction("Add to queue", Icons.AutoMirrored.Rounded.PlaylistAdd) { actions.addToQueue(song) },
    )
    val more = listOfNotNull(
        onAdd?.let { SheetAction("Add to library", Icons.Rounded.Add, run = it) },
        links.album?.let { SheetAction("Go to album", Icons.Rounded.Album) { actions.open(it) } },
        links.artist?.let { SheetAction("Go to artist", Icons.Rounded.Person) { actions.open(it) } },
        song.plays?.takeIf { it > 0 }?.let {
            SheetAction("Your stats", Icons.Rounded.BarChart) { actions.open(SongRoute(song.identity, song.title, song.artistName)) }
        },
        if (song.playable == null && actions.canHandOff) SheetAction("Open in another app", Icons.AutoMirrored.Rounded.OpenInNew) { actions.openElsewhere(song) } else null,
        if (actions.canHandOff) SheetAction("Share", Icons.Rounded.Share) { actions.share(song) } else null,
        SheetAction("Hear less of this", Icons.Rounded.RemoveCircleOutline, supporting = "Leaves your mixes and Tracks Radio") {
            onHearLess?.invoke(song) ?: actions.hearLess(song.identity)
        },
    )
    return quick to more
}

/** Room for the floating mini player under the last row. */
val MiniPlayerClearance: androidx.compose.ui.unit.Dp
    @Composable get() = com.luke.tracks.ui.navigation.bottomClearance

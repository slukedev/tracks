package com.luke.tracks.ui.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import kotlinx.coroutines.launch

/** What was pressed and held. */
sealed interface LibraryTarget {
    data class Song(val song: PlayableSong) : LibraryTarget
    data class Album(val album: LibraryAlbum) : LibraryTarget
    data class Artist(val artist: LibraryArtist, val songs: List<PlayableSong>) : LibraryTarget
}

/** Opens the action sheet for whatever was held; one per screen. */
@Stable
class LibraryActions {
    var target by mutableStateOf<LibraryTarget?>(null)
    fun open(target: LibraryTarget) { this.target = target }
}

val LocalLibraryActions = staticCompositionLocalOf { LibraryActions() }

/** The library's lookups as they are now. */
fun Context.libraryIndex(): LibraryIndex = graph.library.index.value

/** Provides [LibraryActions] to [content] and shows its sheet. */
@Composable
fun WithLibraryActions(content: @Composable () -> Unit) {
    val actions = remember { LibraryActions() }
    CompositionLocalProvider(LocalLibraryActions provides actions) {
        content()
        actions.target?.let { target -> LibraryActionSheet(target, onDismiss = { actions.target = null }) }
    }
}

/** One thing a press-and-hold sheet can do. */
data class SheetAction(val label: String, val icon: ImageVector, val supporting: String? = null, val run: () -> Unit)

@Composable
fun LibraryActionSheet(target: LibraryTarget, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val (quick, more) = remember(target) { actionsFor(target, context, context.graph.player, navigator) }
    ActionSheet(quick, more, onDismiss) { SheetHeader(target) }
}

@Composable
fun ActionSheetPreview(target: LibraryTarget, onAction: () -> Unit = {}) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val (quick, more) = remember(target) { actionsFor(target, context, context.graph.player, navigator) }
    ActionSheetContent(quick, more, onAction = { onAction() }) { SheetHeader(target) }
}

/**
 * What a press and hold opens: [header] on top, the three [quick] actions you reach for most
 * as a connected row of big tonal buttons, and the [more] below as one segmented list.
 */
@Composable
fun ActionSheet(quick: List<SheetAction>, more: List<SheetAction>, onDismiss: () -> Unit, header: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ActionSheetContent(quick, more, header = header, onAction = { action ->
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            action.run()
            scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
        })
    }
}

@Composable
fun ActionSheetContent(quick: List<SheetAction>, more: List<SheetAction>, onAction: (SheetAction) -> Unit, header: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
        header()
        Spacer(Modifier.height(16.dp))
        if (quick.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                quick.forEachIndexed { i, action ->
                    val shapes = when (i) {
                        0 -> ButtonShapes(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedLeadingButtonPressShape)
                        quick.lastIndex -> ButtonShapes(ButtonGroupDefaults.connectedTrailingButtonShape, ButtonGroupDefaults.connectedTrailingButtonPressShape)
                        else -> ButtonShapes(RoundedCornerShape(8.dp), ButtonGroupDefaults.connectedMiddleButtonPressShape)
                    }
                    FilledTonalButton(
                        onClick = { onAction(action) },
                        shapes = shapes,
                        contentPadding = ButtonDefaults.SmallContentPadding,
                        modifier = Modifier.weight(1f).heightIn(min = 76.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(action.icon, contentDescription = null)
                            Spacer(Modifier.height(6.dp))
                            Text(action.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            more.forEachIndexed { i, action ->
                SegmentedListItem(
                    onClick = { onAction(action) },
                    shapes = ListItemDefaults.segmentedShapes(i, more.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    leadingContent = { Icon(action.icon, contentDescription = null) },
                    supportingContent = action.supporting?.let { { Text(it) } },
                    content = { Text(action.label) },
                )
            }
        }
    }
}

/** The top of an action sheet: what was held, its cover and who it's by. */
@Composable
fun ActionSheetHeader(title: String, subtitle: String, note: String? = null, cover: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        cover()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
        }
    }
}

@Composable
private fun SheetHeader(target: LibraryTarget) {
    val cover = RoundedCornerShape(16.dp)
    when (target) {
        is LibraryTarget.Song -> ActionSheetHeader(target.song.title, listOfNotNull(target.song.artistName, target.song.albumTitle).joinToString(" · ")) {
            Artwork(url = target.song.artworkUri, seed = (target.song.albumTitle ?: target.song.title) + target.song.artistName, shape = cover, modifier = Modifier.size(64.dp))
        }
        is LibraryTarget.Album -> ActionSheetHeader(target.album.title, target.album.artistName) {
            Artwork(url = target.album.artworkUri, seed = target.album.title + target.album.artistName, shape = cover, modifier = Modifier.size(64.dp))
        }
        is LibraryTarget.Artist -> ActionSheetHeader(target.artist.name, "Artist") { ArtistAvatar(target.artist, 64.dp) }
    }
}

private fun actionsFor(target: LibraryTarget, context: Context, player: PlayerController, navigator: Navigator): Pair<List<SheetAction>, List<SheetAction>> {
    val index = context.libraryIndex()
    fun queueNext(songs: List<PlayableSong>) = songs.asReversed().forEach(player::playNext)
    fun queueLast(songs: List<PlayableSong>) = songs.forEach(player::addToQueue)
    return when (target) {
        is LibraryTarget.Song -> {
            val song = target.song
            val albumId = index.albumIdOf(song)
            val artistId = index.artistIdOf(song.artistName)
            listOf(
                SheetAction("Play", Icons.Rounded.PlayArrow) { player.play(listOf(song), origin = QueueOrigin(song.title, QueueOrigin.Kind.Library)) },
                SheetAction("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { player.playNext(song) },
                SheetAction("Add to queue", Icons.AutoMirrored.Rounded.PlaylistAdd) { player.addToQueue(song) },
            ) to listOfNotNull(
                albumId?.let { SheetAction("Go to album", Icons.Rounded.Album) { navigator.open(LibraryAlbumRoute(it)) } },
                artistId?.let { SheetAction("Go to artist", Icons.Rounded.Person) { navigator.open(LibraryArtistRoute(it)) } },
                SheetAction("Share", Icons.Rounded.Share) { share(context, "${song.title} by ${song.artistName}") },
            )
        }
        is LibraryTarget.Album -> {
            val album = target.album
            val origin = QueueOrigin(album.title, QueueOrigin.Kind.Album)
            val artistId = index.artistIdOf(album.artistName)
            listOf(
                SheetAction("Play", Icons.Rounded.PlayArrow) { player.play(album.songs, origin = origin) },
                SheetAction("Shuffle", Icons.Rounded.Shuffle) { player.play(album.songs, origin = origin, shuffle = true) },
                SheetAction("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { queueNext(album.songs) },
            ) to listOfNotNull(
                SheetAction("Add to queue", Icons.AutoMirrored.Rounded.PlaylistAdd) { queueLast(album.songs) },
                artistId?.let { SheetAction("Go to artist", Icons.Rounded.Person) { navigator.open(LibraryArtistRoute(it)) } },
                SheetAction("Share", Icons.Rounded.Share) { share(context, "${album.title} by ${album.artistName}") },
            )
        }
        is LibraryTarget.Artist -> {
            val origin = QueueOrigin(target.artist.name, QueueOrigin.Kind.Artist)
            val songs = target.songs
            listOf(
                SheetAction("Play", Icons.Rounded.PlayArrow) { player.play(songs, origin = origin) },
                SheetAction("Shuffle", Icons.Rounded.Shuffle) { player.play(songs, origin = origin, shuffle = true) },
                SheetAction("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { queueNext(songs) },
            ) to listOf(
                SheetAction("Add to queue", Icons.AutoMirrored.Rounded.PlaylistAdd) { queueLast(songs) },
                SheetAction("Share", Icons.Rounded.Share) { share(context, target.artist.name) },
            )
        }
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

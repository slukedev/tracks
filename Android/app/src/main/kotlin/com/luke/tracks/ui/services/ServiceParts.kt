package com.luke.tracks.ui.services

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.luke.tracks.graph
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.net.ServerLinks
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.streaming.CatalogMedia
import com.luke.tracks.streaming.Streaming
import com.luke.tracks.streaming.StreamingState
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.insights.share
import com.luke.tracks.ui.library.AlbumCell
import com.luke.tracks.ui.library.ArtistRow
import com.luke.tracks.ui.library.SongRow
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.ServiceItemRoute
import kotlinx.coroutines.flow.MutableStateFlow
import uniffi.tracks_engine.songIdentity

/** Your servers' streaming services, or null without Trackside. */
@Composable
fun rememberStreaming(): Streaming? {
    val graph = LocalContext.current.graph
    return remember(graph) { Streaming.of(graph) }
}

private val NoServices = MutableStateFlow(StreamingState())

@Composable
fun streamingState(): StreamingState {
    val streaming = rememberStreaming()
    val state by (streaming?.repository?.state ?: NoServices).collectAsState()
    return state
}

/** A service's song as Tracks plays it; your own copy instead where the library has the song it names. */
fun Context.playableFor(serverId: String, track: TracksideCatalogTrack): PlayableSong {
    val owned = track.songId?.let { id -> graph.library.index.value.song(ServerLibrary.mediaId(serverId, id)) }
    return owned ?: CatalogMedia.playable(serverId, track, songIdentity(track.title, track.artist))
}

/** Plays [tracks] from [start]: through the relay, unless every one of them is yours. */
fun Context.playTracks(serverId: String, tracks: List<TracksideCatalogTrack>, start: Int, origin: QueueOrigin?, shuffle: Boolean = false) {
    val playable = tracks.filter { it.canPlay }
    if (playable.isEmpty()) return
    val from = tracks.getOrNull(start)?.let { first -> playable.indexOfFirst { it.ref == first.ref } }?.coerceAtLeast(0) ?: 0
    graph.player.play(playable.map { playableFor(serverId, it) }, from, origin, shuffle)
}

fun cover(serverId: String, artworkId: String?, size: Int = 300): Uri? =
    artworkId?.takeIf { it.isNotEmpty() }?.let { ServerLinks.cover(serverId, it, size) }

/** "Album", "Artist", "Playlist", "Station". */
fun TracksideCatalogItem.kindLabel(): String = when (kindValue) {
    TracksideCatalogItem.Kind.Album -> "Album"
    TracksideCatalogItem.Kind.Artist -> "Artist"
    TracksideCatalogItem.Kind.Playlist -> "Playlist"
    TracksideCatalogItem.Kind.Radio -> "Station"
    TracksideCatalogItem.Kind.Unknown -> "Music"
}

fun TracksideCatalogItem.route(serverId: String) = ServiceItemRoute(serverId, ref, kind, name, artist ?: owner)

/**
 * A service's mark: its initial in a small round tile, in the theme's own colours. Tracks
 * ships no service logos, so the name is what tells them apart, and every service has one.
 */
@Composable
fun ServiceMark(service: TracksideCatalogService?, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val name = service?.name.orEmpty()
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.size(size).semantics { contentDescription = name },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
                style = if (size >= 28.dp) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.labelSmallEmphasized,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * A service's song as a library row: tap plays it among [siblings] (the list it's in), press
 * and hold for the rest. One the service says can't play is dimmed and plays nothing.
 */
@Composable
fun ServiceTrackRow(
    serverId: String,
    track: TracksideCatalogTrack,
    siblings: List<TracksideCatalogTrack>,
    origin: QueueOrigin?,
    currentMediaId: String?,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    showAlbum: Boolean = true,
    albumArtist: String? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val song = remember(serverId, track) { context.playableFor(serverId, track) }
    Box(modifier.alpha(if (track.canPlay) 1f else 0.5f)) {
        SongRow(
            song,
            isCurrent = song.mediaId == currentMediaId,
            onClick = {
                if (!track.canPlay) return@SongRow
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                context.playTracks(serverId, siblings, siblings.indexOf(track), origin)
            },
            onMore = { menu = true },
            trackNumber = trackNumber,
            showAlbum = showAlbum,
            albumArtist = albumArtist,
        )
        ServiceTrackMenu(serverId, track, song, expanded = menu, onDismiss = { menu = false })
    }
}

@Composable
fun ServiceTrackMenu(serverId: String, track: TracksideCatalogTrack, song: PlayableSong, expanded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val act: (() -> Unit) -> Unit = { block ->
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        onDismiss()
        block()
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        offset = DpOffset(72.dp, 0.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 260.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artworkUri, song.title, Modifier.size(40.dp), shape = RoundedCornerShape(8.dp), showInitials = false)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (track.canPlay) {
            Entry("Play", Icons.Rounded.PlayArrow) { act { context.graph.player.play(listOf(song), origin = QueueOrigin(track.title, QueueOrigin.Kind.Search)) } }
            Entry("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { act { context.graph.player.playNext(song) } }
            Entry("Add to queue", Icons.AutoMirrored.Rounded.PlaylistAdd) { act { context.graph.player.addToQueue(song) } }
        }
        track.albumRef?.let { ref ->
            Entry("Go to album", Icons.Rounded.Album) {
                act { navigator.open(ServiceItemRoute(serverId, ref, "album", track.album.orEmpty(), track.artist)) }
            }
        }
        track.artists?.firstOrNull { it.ref != null }?.let { artist ->
            Entry("Go to artist", Icons.Rounded.Mic) { act { navigator.open(ServiceItemRoute(serverId, artist.ref!!, "artist", artist.name, null)) } }
        }
        Entry("Share", Icons.Rounded.Share) { act { share(context, "${track.title} by ${track.artist}") } }
    }
}

@Composable
private fun Entry(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

/** An album or playlist as the library's own cell, for shelves. */
@Composable
fun ServiceItemCell(serverId: String, item: TracksideCatalogItem, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val album = remember(serverId, item) {
        LibraryAlbum(
            id = "service:${item.ref}",
            title = item.name,
            artistName = item.artist ?: item.owner ?: item.kindLabel(),
            artworkUri = cover(serverId, item.artworkId),
            year = item.year,
            songs = emptyList(),
        )
    }
    AlbumCell(album, onClick = { navigator.open(item.route(serverId)) }, onLongClick = null, modifier = modifier.width(148.dp))
}

/** An artist as the library's own row, their picture in their shape. */
@Composable
fun ServiceArtistRow(serverId: String, item: TracksideCatalogItem, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val artist = remember(serverId, item) {
        val picture = cover(serverId, item.artworkId)?.let { LibraryAlbum("picture:${item.ref}", "", item.name, it, null, emptyList()) }
        LibraryArtist("service:${item.ref}", item.name, listOfNotNull(picture), 0)
    }
    ArtistRow(artist, onClick = { navigator.open(item.route(serverId)) }, onLongClick = { navigator.open(item.route(serverId)) }, modifier = modifier.fillMaxWidth())
}

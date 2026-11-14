package com.luke.tracks.ui.insights

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.luke.tracks.AppGraph
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.Route
import com.luke.tracks.ui.navigation.SongRoute
import kotlinx.coroutines.launch

/** A song, artist or album something can be done with. */
sealed interface ItemTarget {
    val title: String
    val artworkUrl: String?

    data class Song(
        val identity: String,
        override val title: String,
        val artistName: String,
        val albumTitle: String?,
        override val artworkUrl: String? = null,
    ) : ItemTarget

    data class Artist(val identity: String, val name: String, override val artworkUrl: String? = null) : ItemTarget {
        override val title get() = name
    }

    data class Album(
        val identity: String,
        override val title: String,
        val artistName: String,
        override val artworkUrl: String? = null,
    ) : ItemTarget

    val subtitle: String?
        get() = when (this) {
            is Song -> artistName
            is Album -> artistName
            is Artist -> null
        }

    /** What Share sends: "Saltwater by Mara Solis". */
    val shareText: String
        get() = when (this) {
            is Song -> "$title by $artistName"
            is Album -> "$title by $artistName"
            is Artist -> name
        }

    val route: Route
        get() = when (this) {
            is Song -> SongRoute(identity, title, artistName)
            is Artist -> ArtistRoute(identity, name)
            is Album -> AlbumRoute(identity, title, artistName)
        }

    /** The shared element key for its cover, matching the detail page's header. */
    val coverKey: String
        get() = when (this) {
            is Song -> "cover:song:$identity"
            is Artist -> "cover:artist:$identity"
            is Album -> "cover:album:$identity"
        }
}

/** An extra entry for a menu, like "Delete from history" on a History row. */
data class ExtraAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Where the insight screens post short confirmations; a toast when no screen provides one. */
val LocalInsightsSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * A tappable item with the press-and-hold menu Google's apps have: a tick of haptics, then
 * Play, Play next, Add to queue, Go to artist or album, Share, Copy and Hear less, anchored at
 * the item. Shrinks a little under the finger.
 */
@Composable
fun ItemActionsBox(
    target: ItemTarget,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GroupOuter),
    extra: List<ExtraAction> = emptyList(),
    pressScale: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier
            .then(if (pressScale) Modifier.pressScale(interaction) else Modifier)
            .clip(shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
                onLongClickLabel = "More options",
            ),
    ) {
        content()
        ItemMenu(target, expanded = menuOpen, onDismiss = { menuOpen = false }, extra = extra)
    }
}

/** The press-and-hold menu for [target]. */
@Composable
fun ItemMenu(
    target: ItemTarget,
    expanded: Boolean,
    onDismiss: () -> Unit,
    extra: List<ExtraAction> = emptyList(),
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val snackbar = LocalInsightsSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var playable by remember(target) { mutableStateOf<List<PlayableSong>>(emptyList()) }
    var artistRoute by remember(target) { mutableStateOf<ArtistRoute?>(null) }
    var albumRoute by remember(target) { mutableStateOf<AlbumRoute?>(null) }

    LaunchedEffect(expanded, target) {
        if (!expanded) return@LaunchedEffect
        playable = playableSongs(graph, target)
        when (target) {
            is ItemTarget.Song -> {
                val lead = graph.engine.read { Identities.leadArtist(target.artistName, target.title) }
                artistRoute = ArtistRoute(lead.identity, lead.name)
                albumRoute = graph.engine.read {
                    Identities.album(target.albumTitle, target.artistName, target.title)
                }?.let { AlbumRoute(it, target.albumTitle!!, target.artistName) }
            }
            is ItemTarget.Album -> {
                val all = AllTime.songsOn(graph.engine, target.identity, target.title)
                val lead = all.firstOrNull()?.let { graph.engine.read { Identities.leadArtist(it.artistName, it.title) } }
                artistRoute = lead?.let { ArtistRoute(it.identity, it.name) }
            }
            is ItemTarget.Artist -> Unit
        }
    }

    fun tell(message: String) {
        if (snackbar != null) scope.launch { snackbar.showSnackbar(message) }
        else Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun act(block: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        onDismiss()
        block()
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        offset = DpOffset(16.dp, 0.dp),
    ) {
        MenuHeader(target)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        if (playable.isNotEmpty()) {
            val origin = QueueOrigin(
                target.title,
                when (target) {
                    is ItemTarget.Album -> QueueOrigin.Kind.Album
                    is ItemTarget.Artist -> QueueOrigin.Kind.Artist
                    is ItemTarget.Song -> QueueOrigin.Kind.Other
                },
            )
            MenuEntry("Play", Icons.Rounded.PlayArrow) { act { graph.player.play(playable, origin = origin) } }
            if (target is ItemTarget.Song) {
                MenuEntry("Play next", Icons.AutoMirrored.Rounded.QueueMusic) {
                    act { graph.player.playNext(playable.first()); tell("Playing ${target.title} next") }
                }
                MenuEntry("Add to queue", Icons.AutoMirrored.Rounded.PlaylistAdd) {
                    act { graph.player.addToQueue(playable.first()); tell("Added ${target.title} to the queue") }
                }
            } else {
                MenuEntry("Shuffle", Icons.Rounded.Shuffle) { act { graph.player.play(playable, origin = origin, shuffle = true) } }
            }
        }
        artistRoute?.let { route ->
            MenuEntry("Go to artist", Icons.Rounded.Mic) { act { navigator.open(route) } }
        }
        albumRoute?.let { route ->
            MenuEntry("Go to album", Icons.Rounded.Album) { act { navigator.open(route) } }
        }
        MenuEntry("Share", Icons.Rounded.Share) { act { share(context, target.shareText) } }
        MenuEntry("Copy", Icons.Rounded.ContentCopy) {
            act { copy(context, target.shareText); tell("Copied") }
        }
        if (target is ItemTarget.Song) {
            MenuEntry("Hear less of this", Icons.Rounded.RemoveCircleOutline) {
                act {
                    graph.scope.launch { graph.settings.update { it.copy(hiddenSongs = it.hiddenSongs + target.identity) } }
                    tell("You'll hear less of ${target.title} in mixes and radio")
                }
            }
        }
        extra.forEach { action ->
            MenuEntry(action.label, action.icon, destructive = action.destructive) {
                haptics.performHapticFeedback(if (action.destructive) HapticFeedbackType.Reject else HapticFeedbackType.ContextClick)
                onDismiss()
                action.onClick()
            }
        }
    }
}

@Composable
private fun MenuHeader(target: ItemTarget) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 260.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            target.artworkUrl, target.title,
            modifier = Modifier.size(40.dp),
            shape = if (target is ItemTarget.Artist) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(8.dp),
            showInitials = false,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(target.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            target.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MenuEntry(label: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = { Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

/** Songs from Your Music for [target], in order; empty when you have none of them. */
suspend fun playableSongs(graph: AppGraph, target: ItemTarget): List<PlayableSong> {
    val library = runCatching { graph.library }.getOrNull() ?: return emptyList()
    val identities = when (target) {
        is ItemTarget.Song -> listOf(target.identity)
        is ItemTarget.Artist -> AllTime.songsBy(graph.engine, target.identity, target.name).map { it.identity }
        is ItemTarget.Album -> AllTime.songsOn(graph.engine, target.identity, target.title).map { it.identity }
    }
    return runCatching { library.findAll(identities) }.getOrDefault(emptyList())
}

fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
}

/** Opens [target]'s page. */
fun Navigator.open(target: ItemTarget) = open(target.route)

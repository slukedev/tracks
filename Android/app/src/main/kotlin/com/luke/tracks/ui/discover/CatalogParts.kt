package com.luke.tracks.ui.discover

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.luke.tracks.discover.Availability
import com.luke.tracks.discover.Discovery
import com.luke.tracks.discover.LocalDiscovery
import com.luke.tracks.discover.PreviewState
import com.luke.tracks.discover.discovery
import com.luke.tracks.discover.previewPlayer
import com.luke.tracks.graph
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.insights.share
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.ui.library.ArtistAvatar
import com.luke.tracks.ui.library.pressScale
import com.luke.tracks.ui.navigation.CatalogAlbumRoute
import com.luke.tracks.ui.navigation.CatalogArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.play.components.openInAnotherApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import uniffi.tracks_engine.Ownership
import uniffi.tracks_engine.CatalogAlbum
import uniffi.tracks_engine.CatalogAlbumKind
import uniffi.tracks_engine.CatalogArtist
import uniffi.tracks_engine.CatalogSong

/** The app's [Discovery], or the one a screenshot provides. */
@Composable
fun rememberDiscovery(): Discovery {
    val provided = LocalDiscovery.current
    val context = LocalContext.current
    return provided ?: remember(context) { context.graph.discovery }
}

/** Previews: the app's player, or none in screenshots, which can still show one playing. */
data class Previews(val state: PreviewState, val toggle: (key: String, url: String) -> Unit)

val LocalPreviewOverride = staticCompositionLocalOf<PreviewState?> { null }

@Composable
fun rememberPreviews(): Previews {
    val override = LocalPreviewOverride.current
    val context = LocalContext.current
    if (override != null) return Previews(override) { _, _ -> }
    val player = remember(context) { context.previewPlayer }
    val state by player.state.collectAsState()
    return Previews(state, player::toggle)
}

/** Says something briefly: in the screen's snackbar when it has one, otherwise a toast. */
fun tell(context: Context, snackbar: SnackbarHostState?, scope: kotlinx.coroutines.CoroutineScope, message: String) {
    if (message.isBlank()) return
    if (snackbar != null) scope.launch { snackbar.showSnackbar(message) }
    else Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** A key that changes whenever what's yours or what's getting could have. */
@Composable
private fun availabilityKey(discovery: Discovery): Any {
    val sources by discovery.sources.collectAsState()
    val waiting by discovery.waiting.collectAsState()
    val context = LocalContext.current
    val library = remember(context) { (runCatching { context.graph.library }.getOrNull() as? com.luke.tracks.library.CombinedLibrary)?.index ?: MutableStateFlow(null) }
    val index by library.collectAsState()
    return Triple(sources, waiting, index?.snapshot?.songs?.size)
}

/** What can be done about [song], worked out as it comes on screen. */
@Composable
fun rememberAvailability(song: CatalogSong): Availability {
    val discovery = rememberDiscovery()
    val key = availabilityKey(discovery)
    val initial = remember(song.identity, discovery) { discovery.owned(song)?.let { Availability.Owned(it) } ?: Availability.Checking }
    val state by produceState(initial, song.identity, key) {
        value = runCatching { discovery.availability(song) }.getOrDefault(Availability.Unavailable)
    }
    return state
}

/**
 * A song from the catalog: tap plays it if it's yours, or its preview if not. The cover
 * shows the preview's progress. At the end, what can be done: get it, see it coming, or a
 * tick when it's yours. Press and hold for everything else.
 */
@Composable
fun CatalogSongRow(
    song: CatalogSong,
    modifier: Modifier = Modifier,
    showAlbum: Boolean = true,
    /** Off on the artist's own pages, where it goes without saying. */
    showArtist: Boolean = true,
    trackNumber: Int? = null,
) {
    val context = LocalContext.current
    val discovery = rememberDiscovery()
    val previews = rememberPreviews()
    val availability = rememberAvailability(song)
    val sources by discovery.sources.collectAsState()
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    var menu by remember { mutableStateOf(false) }
    val owned = availability as? Availability.Owned
    val key = "song:${song.id}"
    val previewing = previews.state.key == key
    val onTap = {
        when {
            owned != null -> {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                context.graph.player.play(listOf(owned.song), origin = QueueOrigin(song.title, QueueOrigin.Kind.Search))
            }
            song.previewUrl != null -> {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                previews.toggle(key, song.previewUrl!!)
            }
            else -> openInAnotherApp(context, song.title, song.artistName, song.albumTitle)
        }
    }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (trackNumber != null) 60.dp else 68.dp)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClick = onTap,
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    onLongClickLabel = "More options",
                )
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trackNumber != null) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (previewing) PreviewProgress(previews.state, Modifier.size(36.dp))
                    else Text("$trackNumber", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(Modifier.size(52.dp).pressScale(interaction), contentAlignment = Alignment.Center) {
                    Artwork(song.artworkUrl, (song.albumTitle ?: song.title) + song.artistName, Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp), showInitials = false)
                    if (previewing) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            PreviewProgress(previews.state, Modifier.size(36.dp), onDark = true)
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    color = if (previewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    song.artistName.takeIf { showArtist },
                    song.albumTitle?.takeIf { showAlbum && it.isNotBlank() },
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            AvailabilityAccessory(song, availability, canFetch = sources.canFetch)
        }
        CatalogSongMenu(song, availability, expanded = menu, onDismiss = { menu = false }, offset = DpOffset(72.dp, 0.dp))
    }
}

@Composable
private fun PreviewProgress(state: PreviewState, modifier: Modifier, onDark: Boolean = false) {
    val color = if (onDark) Color.White else MaterialTheme.colorScheme.primary
    Box(modifier.semantics { contentDescription = "Playing preview" }, contentAlignment = Alignment.Center) {
        if (state.isLoading) LoadingIndicator(Modifier.fillMaxSize(), color = color)
        else CircularWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxSize(), color = color, trackColor = color.copy(alpha = 0.25f))
        Icon(Icons.Rounded.Stop, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
    }
}

/**
 * The end of a song's row. With nothing configured that could get music, only a tick for
 * songs you have: nothing offers to download.
 */
@Composable
fun AvailabilityAccessory(song: CatalogSong, availability: Availability, canFetch: Boolean) {
    val context = LocalContext.current
    val discovery = rememberDiscovery()
    val snackbar = LocalInsightsSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var getting by remember(song.identity) { mutableStateOf(false) }
    val shown = when {
        getting -> Accessory.Getting
        availability is Availability.Owned -> Accessory.Owned
        !canFetch -> Accessory.None
        availability.canGet -> Accessory.Get
        availability == Availability.Waiting || availability == Availability.ComingFromLidarr -> Accessory.Coming
        else -> Accessory.None
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.9f)) togetherWith
                (fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.9f))
        },
        contentAlignment = Alignment.Center,
        label = "accessory",
    ) { state ->
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when (state) {
                Accessory.Owned -> Icon(Icons.Rounded.CheckCircle, "In your library", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Accessory.Get -> FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        getting = true
                        scope.launch {
                            val message = runCatching { discovery.get(song) }.getOrElse { "Couldn't get “${song.title}”" }
                            getting = false
                            tell(context, snackbar, scope, message)
                        }
                    },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(40.dp),
                ) { Icon(Icons.Rounded.Download, "Get ${song.title}", modifier = Modifier.size(20.dp)) }
                Accessory.Getting -> LoadingIndicator(Modifier.size(32.dp))
                Accessory.Coming -> Icon(Icons.Rounded.Schedule, "On its way", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                Accessory.None -> Spacer(Modifier.size(0.dp))
            }
        }
    }
}

private enum class Accessory { None, Owned, Get, Getting, Coming }

/** Everything that can be done with a catalog song, anchored at its row. */
@Composable
fun CatalogSongMenu(song: CatalogSong, availability: Availability, expanded: Boolean, onDismiss: () -> Unit, offset: DpOffset = DpOffset(16.dp, 0.dp)) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val discovery = rememberDiscovery()
    val previews = rememberPreviews()
    val sources by discovery.sources.collectAsState()
    val snackbar = LocalInsightsSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val act = menuAction(haptics, onDismiss)
    val owned = (availability as? Availability.Owned)?.song
    CatalogMenu(expanded, onDismiss, song.title, song.artistName, song.artworkUrl, offset) {
        if (owned != null) {
            MenuEntry("Play", Icons.Rounded.PlayArrow) { act { context.graph.player.play(listOf(owned)) } }
            MenuEntry("Play next", Icons.AutoMirrored.Rounded.QueueMusic) { act { context.graph.player.playNext(owned) } }
        } else {
            song.previewUrl?.let { url ->
                val playing = previews.state.key == "song:${song.id}"
                MenuEntry(if (playing) "Stop preview" else "Play preview", if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow) {
                    act { previews.toggle("song:${song.id}", url) }
                }
            }
            if (sources.canFetch && availability.canGet) {
                val label = if (availability == Availability.Requestable) "Ask Lidarr for the album" else "Get song"
                MenuEntry(label, Icons.Rounded.Download) {
                    act { scope.launch { tell(context, snackbar, scope, runCatching { discovery.get(song) }.getOrDefault("Couldn't get it")) } }
                }
            }
            MenuEntry("Open in another app", Icons.AutoMirrored.Rounded.OpenInNew) { act { openInAnotherApp(context, song.title, song.artistName, song.albumTitle) } }
        }
        song.albumId?.let { id ->
            MenuEntry("Go to album", Icons.Rounded.Album) {
                act { navigator.open(CatalogAlbumRoute(id.toLong(), song.albumTitle.orEmpty(), song.artistName)) }
            }
        }
        MenuEntry("Go to artist", Icons.Rounded.Mic) { act { navigator.open(CatalogArtistRoute(song.artistName, song.artistId?.toLong())) } }
        MenuEntry("Share", Icons.Rounded.Share) { act { share(context, "${song.title} by ${song.artistName}") } }
    }
}

/** An album's menu: open it, get it where something can, or hand it to another app. */
@Composable
fun CatalogAlbumMenu(album: CatalogAlbum, expanded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val discovery = rememberDiscovery()
    val sources by discovery.sources.collectAsState()
    val snackbar = LocalInsightsSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val act = menuAction(haptics, onDismiss)
    val holding = remember(album) { discovery.holding(album) }
    CatalogMenu(expanded, onDismiss, album.title, album.artistName, album.artworkUrl) {
        MenuEntry("Open album", Icons.Rounded.Album) { act { navigator.open(CatalogAlbumRoute(album.id.toLong(), album.title, album.artistName)) } }
        if (sources.canFetch && holding.ownership != Ownership.OWNED) {
            val label = when {
                sources.lidarr -> "Ask Lidarr for it"
                holding.ownership == Ownership.PARTLY_OWNED -> "Get the rest"
                else -> "Get album"
            }
            MenuEntry(label, Icons.Rounded.Download) {
                act { scope.launch { tell(context, snackbar, scope, runCatching { discovery.getAlbum(album) }.getOrDefault("Couldn't get it")) } }
            }
        }
        MenuEntry("Open in another app", Icons.AutoMirrored.Rounded.OpenInNew) { act { openInAnotherApp(context, album.title, album.artistName, album.title) } }
        MenuEntry("Go to artist", Icons.Rounded.Mic) { act { navigator.open(CatalogArtistRoute(album.artistName, album.artistId?.toLong())) } }
        MenuEntry("Share", Icons.Rounded.Share) { act { share(context, "${album.title} by ${album.artistName}") } }
    }
}

/** An artist's menu, with following them in Lidarr where there is one. */
@Composable
fun CatalogArtistMenu(artist: CatalogArtist, expanded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val discovery = rememberDiscovery()
    val sources by discovery.sources.collectAsState()
    val snackbar = LocalInsightsSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val act = menuAction(haptics, onDismiss)
    CatalogMenu(expanded, onDismiss, artist.name, artist.genre ?: "Artist", null) {
        MenuEntry("Go to artist", Icons.Rounded.Mic) { act { navigator.open(CatalogArtistRoute(artist.name, artist.id.toLong())) } }
        if (sources.lidarr) {
            MenuEntry("Follow in Lidarr", Icons.Rounded.PersonAdd) {
                act { scope.launch { tell(context, snackbar, scope, discovery.followArtist(artist.name).orEmpty()) } }
            }
        }
        MenuEntry("Share", Icons.Rounded.Share) { act { share(context, artist.name) } }
    }
}

/** Runs a menu entry: a tick, the menu closes, then the action. */
private fun menuAction(haptics: androidx.compose.ui.hapticfeedback.HapticFeedback, onDismiss: () -> Unit): (() -> Unit) -> Unit = { block ->
    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
    onDismiss()
    block()
}

@Composable
private fun CatalogMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String,
    artwork: String?,
    offset: DpOffset = DpOffset(16.dp, 0.dp),
    entries: @Composable () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        offset = offset,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 260.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork, title, Modifier.size(40.dp), shape = if (artwork == null) CircleShape else RoundedCornerShape(8.dp), showInitials = artwork == null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        entries()
    }
}

@Composable
private fun MenuEntry(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

/** What an album's caption says: how much of it you have, or its year and kind. */
fun albumCaption(album: CatalogAlbum, ownership: Ownership, ownedSongs: Int, showArtist: Boolean): String {
    val kind = when (album.kind) {
        CatalogAlbumKind.SINGLE -> "Single"
        CatalogAlbumKind.EP -> "EP"
        CatalogAlbumKind.COMPILATION -> "Compilation"
        CatalogAlbumKind.ALBUM -> null
    }
    return when (ownership) {
        Ownership.OWNED -> "In your library"
        Ownership.PARTLY_OWNED -> album.trackCount?.let { "$ownedSongs of $it songs" } ?: "Some songs"
        Ownership.MISSING -> listOfNotNull(if (showArtist) album.artistName else null, kind, album.year?.toString()).joinToString(" · ")
    }
}

/** An album from the catalog as a cover and two lines, for shelves. */
@Composable
fun CatalogAlbumCell(album: CatalogAlbum, modifier: Modifier = Modifier, showArtist: Boolean = true) {
    val navigator = LocalNavigator.current
    val discovery = rememberDiscovery()
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    var menu by remember { mutableStateOf(false) }
    val holding = remember(album, discovery) { discovery.holding(album) }
    Box(modifier.width(156.dp)) {
        Column(
            Modifier
                .pressScale(interaction)
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClick = { navigator.open(CatalogAlbumRoute(album.id.toLong(), album.title, album.artistName)) },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    onLongClickLabel = "More options",
                )
                .padding(6.dp),
        ) {
            Box {
                Artwork(album.artworkUrl, album.title + album.artistName, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                if (holding.ownership != Ownership.MISSING) {
                    OwnershipBadge(holding.ownership, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            Text(album.title, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                albumCaption(album, holding.ownership, holding.ownedSongs, showArtist),
                style = MaterialTheme.typography.bodySmall,
                color = if (holding.ownership == Ownership.MISSING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        CatalogAlbumMenu(album, expanded = menu, onDismiss = { menu = false })
    }
}

/** A small round mark on a cover: a tick for all of it, a half for some. */
@Composable
fun OwnershipBadge(ownership: Ownership, modifier: Modifier = Modifier) {
    val (icon, label) = when (ownership) {
        Ownership.OWNED -> Icons.Rounded.CheckCircle to "In your library"
        else -> Icons.Rounded.Contrast to "Some of it is in your library"
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.size(28.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        }
    }
}

/** A catalog artist as a round initial and a name, for shelves. */
@Composable
fun CatalogArtistCell(artist: CatalogArtist, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    var menu by remember { mutableStateOf(false) }
    Box(modifier.width(112.dp)) {
        Column(
            Modifier
                .pressScale(interaction)
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClick = { navigator.open(CatalogArtistRoute(artist.name, artist.id.toLong())) },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    onLongClickLabel = "More options",
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArtistAvatar(LibraryArtist(artist.name, artist.name, emptyList(), 0), 88.dp)
            Spacer(Modifier.padding(top = 8.dp))
            Text(artist.name, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist.genre?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        CatalogArtistMenu(artist, expanded = menu, onDismiss = { menu = false })
    }
}

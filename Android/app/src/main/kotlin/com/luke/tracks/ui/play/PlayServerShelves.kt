package com.luke.tracks.ui.play

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luke.tracks.discover.LocalServerPicks
import com.luke.tracks.discover.ServerAlbum
import com.luke.tracks.discover.ServerPicks
import com.luke.tracks.discover.ServerSong
import com.luke.tracks.discover.serverPicks
import com.luke.tracks.graph
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.discover.listPhrase
import com.luke.tracks.ui.discover.tell
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.library.LibraryTarget
import com.luke.tracks.ui.library.LocalLibraryActions
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.play.components.GoTo
import com.luke.tracks.ui.play.components.MenuItem
import com.luke.tracks.ui.play.components.SongActionsSheet
import com.luke.tracks.ui.play.components.SongRef
import com.luke.tracks.ui.play.components.goToActions
import com.luke.tracks.ui.play.components.rememberLibraryIndex
import com.luke.tracks.ui.play.components.rememberPlayActions
import com.luke.tracks.ui.play.components.songLinks
import com.luke.tracks.ui.play.components.pressable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uniffi.tracks_engine.ForYouShelf
import uniffi.tracks_engine.serverWanted

/** The app's [ServerPicks], or the one a screenshot provides. */
@Composable
fun rememberServerPicks(): ServerPicks {
    val provided = LocalServerPicks.current
    val context = LocalContext.current
    return provided ?: remember(context) { context.graph.serverPicks }
}

private const val ROWS = 4
private val RowHeight = 68.dp

/** A page of a song shelf: most of the width, so the next column peeks in. */
@Composable
private fun columnWidth(): Dp = (LocalConfiguration.current.screenWidthDp * 0.86f).dp

/**
 * One of the shelves a server fills for you, in columns of four: Suggested songs (songs by
 * artists new to you) or Picked for you (by the artists you play and ones like them, yours
 * and new together). It goes on as it's scrolled: the last column in view asks for more, as
 * fast as the server's budget allows.
 */
@Composable
fun ServerForYouShelf(server: SubsonicServer, shelf: ForYouShelf, reloadKey: Any?, modifier: Modifier = Modifier) {
    val picks = rememberServerPicks()
    val all by picks.forYou.collectAsState()
    val answers by picks.answers.collectAsState()
    val state = all[server.id]
    val songs = state?.shelves?.let { if (shelf == ForYouShelf.PICKS) it.picks else it.suggested }.orEmpty().map { ServerSong(server.id, it) }
    val from = state?.shelves?.from.orEmpty()
    val goesOn = state == null || state.isFilling || state.shelves.canGoOn
    val scope = rememberCoroutineScope()
    LaunchedEffect(server.id, reloadKey) { picks.load(server.id) }

    // Suggested songs needs artists to start from; without any, it isn't there.
    if (state != null && from.isEmpty()) return
    val title = if (shelf == ForYouShelf.PICKS) "Picked for you" else "Suggested songs"
    Column(modifier) {
        Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle(title)
                val subtitle = when {
                    from.isEmpty() -> null
                    shelf == ForYouShelf.PICKS -> "From ${listPhrase(from)}, and artists like them"
                    else -> "Artists new to you, like the ones you play"
                }
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            if (shelf == ForYouShelf.PICKS && songs.isNotEmpty()) PicksMenu(server, songs, onPickAgain = { scope.launch { picks.load(server.id, force = true) } })
        }
        Spacer(Modifier.height(8.dp))
        when {
            songs.isEmpty() && goesOn -> SongShelfLoading()
            songs.isEmpty() -> Text(
                if (server.isTrackside) "${server.name} didn't find anything to suggest."
                else "${server.name} didn't find anything to suggest. If it's Octo, it needs a Last.fm API key, set on its admin page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            else -> {
                val grid = rememberLazyGridState()
                // Only whole columns while more are coming, so the end is never a column of gaps.
                val shown = if (goesOn && songs.size >= ROWS) songs.take(songs.size / ROWS * ROWS) else songs
                val atEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - ROWS } }
                LaunchedEffect(atEnd, songs.size, goesOn, answers) {
                    if (atEnd && goesOn) picks.loadMore(server.id, shelf, serverWanted(songs.size.toUInt(), ROWS.toUInt()).toInt())
                }
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(ROWS),
                    state = grid,
                    flingBehavior = rememberSnapFlingBehavior(grid, SnapPosition.Start),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(RowHeight * ROWS),
                ) {
                    itemsIndexed(shown, key = { _, it -> it.song.id }) { index, song ->
                        ServerSongRow(song, shown, index, title, Modifier.width(columnWidth()).animateItem())
                    }
                }
            }
        }
    }
}

/** The shelf's shape while songs are found: rows where they'll be, no fake content. */
@Composable
private fun SongShelfLoading() {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier.height(RowHeight * ROWS).padding(start = 24.dp).semantics { contentDescription = "Loading" },
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        repeat(2) { column ->
            Column(Modifier.width(columnWidth() - 24.dp)) {
                repeat(ROWS) { row ->
                    Row(Modifier.height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = fill, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(52.dp)) {}
                        Spacer(Modifier.width(16.dp))
                        Column {
                            val widths = listOf(150.dp, 110.dp, 170.dp, 96.dp)
                            Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(widths[(row + column) % 4], 12.dp)) {}
                            Spacer(Modifier.height(8.dp))
                            Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(84.dp, 10.dp)) {}
                        }
                    }
                }
            }
        }
    }
}

/** Pick again, and Add all to your music where the server fetches. */
@Composable
private fun PicksMenu(server: SubsonicServer, songs: List<ServerSong>, onPickAgain: () -> Unit) {
    val picks = rememberServerPicks()
    val context = LocalContext.current
    val snackbar = LocalInsightsSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            MenuItem(Icons.Rounded.Refresh, "Pick again") { open = false; onPickAgain() }
            val toAdd = songs.filter { picks.canKeep(it.serverId) && context.graph.library.find(it.song.identity) == null }
            if (toAdd.isNotEmpty()) {
                MenuItem(Icons.Rounded.Add, "Add all to library") {
                    open = false
                    scope.launch {
                        toAdd.forEach { picks.keep(it) }
                        tell(context, snackbar, scope, if (toAdd.size == 1) "Adding 1 song to your library" else "Adding ${toAdd.size} songs to your library")
                    }
                }
            }
        }
    }
}

/**
 * A song a server found: tap plays it and the rest of its list, press and hold for its menu.
 * Where the server fetches (Octo), songs not in your music have an Add button.
 */
@Composable
fun ServerSongRow(song: ServerSong, queue: List<ServerSong>, index: Int, from: String, modifier: Modifier = Modifier) {
    val picks = rememberServerPicks()
    val context = LocalContext.current
    val graph = context.graph
    val navigator = LocalNavigator.current
    val actions = rememberPlayActions()
    val library = rememberLibraryIndex()
    val player by graph.player.state.collectAsState()
    val isCurrent = player.current?.identity == song.song.identity
    var menu by remember { mutableStateOf(false) }
    // The row's, so adding goes on after the sheet closes.
    val scope = rememberCoroutineScope()
    val ref = remember(song, library) { song.ref(picks) }
    val links = remember(ref, library) { songLinks(ref, library, found = song) }
    val play = {
        val songs = queue.mapNotNull { picks.playable(it) }
        val start = songs.indexOfFirst { it.identity == song.song.identity }.coerceAtLeast(0)
        if (songs.isNotEmpty()) graph.player.play(songs, start, QueueOrigin(from, QueueOrigin.Kind.Other))
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .goToActions(links, navigator)
            .pressable(onClick = play, onLongClick = { menu = true }, pressedScale = 0.98f)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(ref.artworkUrl, ref.coverSeed + ref.artistName, Modifier.size(52.dp), shape = RoundedCornerShape(12.dp), showInitials = false)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.song.title,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(song.song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AddFoundSong(song)
    }
    if (menu) {
        val add = rememberAdd(song, scope)
        SongActionsSheet(ref, actions, links, onDismiss = { menu = false }, onPlay = play, onAdd = add)
    }
}

private fun ServerSong.ref(picks: ServerPicks) =
    SongRef(song.identity, song.title, song.artist, song.album, picks.coverUrl(this), null, picks.playable(this))

/**
 * Has the server fetch [song] and says how it went, when it can and you don't have the song
 * or haven't asked for it already. Null otherwise.
 */
@Composable
private fun rememberAdd(song: ServerSong, scope: CoroutineScope): (() -> Unit)? {
    val picks = rememberServerPicks()
    val context = LocalContext.current
    val discovery = com.luke.tracks.ui.discover.rememberDiscovery()
    val waiting by discovery.waiting.collectAsState()
    val snackbar = LocalInsightsSnackbar.current
    val owned = context.graph.library.find(song.song.identity) != null
    if (owned || !picks.canKeep(song.serverId) || song.song.identity in waiting) return null
    return {
        scope.launch {
            val message = runCatching { picks.keep(song) }.getOrElse { "Couldn't add “${song.song.title}”" }
            tell(context, snackbar, scope, message)
        }
    }
}

/** Add to your music, for a song the server can fetch: then a clock while it's on its way. */
@Composable
private fun AddFoundSong(song: ServerSong) {
    val picks = rememberServerPicks()
    val context = LocalContext.current
    val library by context.graph.library.snapshot.collectAsState()
    val discovery = com.luke.tracks.ui.discover.rememberDiscovery()
    val waiting by discovery.waiting.collectAsState()
    val snackbar = LocalInsightsSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var adding by remember(song.song.identity) { mutableStateOf(false) }
    val owned = remember(library, song.song.identity) { context.graph.library.find(song.song.identity) != null }
    val state = when {
        owned || !picks.canKeep(song.serverId) -> 0
        adding -> 1
        song.song.identity in waiting -> 2
        else -> 3
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.9f)) togetherWith
                (fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.9f))
        },
        label = "add",
    ) { shown ->
        when (shown) {
            0 -> Spacer(Modifier.size(0.dp))
            1 -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
            2 -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Schedule, "On its way", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
            }
            else -> FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    adding = true
                    scope.launch {
                        val message = runCatching { picks.keep(song) }.getOrElse { "Couldn't add “${song.song.title}”" }
                        adding = false
                        tell(context, snackbar, scope, message)
                    }
                },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.Rounded.Add, "Add ${song.song.title} to library", modifier = Modifier.size(20.dp)) }
        }
    }
}

/** A server's own shelves: what it plays most and albums at random. */
@Composable
fun ServerAlbumShelves(server: SubsonicServer, modifier: Modifier = Modifier) {
    val picks = rememberServerPicks()
    val all by picks.albums.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(server.id) { picks.loadAlbums(server.id) }
    val shelves = all[server.id] ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(32.dp)) {
        if (shelves.mostPlayed.isNotEmpty()) AlbumShelf("Most played on ${server.name}", shelves.mostPlayed)
        if (shelves.random.isNotEmpty()) AlbumShelf("Something different", shelves.random) {
            IconButton(onClick = { scope.launch { picks.shuffleRandom(server.id) } }) {
                Icon(Icons.Rounded.Shuffle, contentDescription = "Show other albums")
            }
        }
    }
}

@Composable
private fun AlbumShelf(title: String, albums: List<ServerAlbum>, action: (@Composable () -> Unit)? = null) {
    val navigator = LocalNavigator.current
    val actions = LocalLibraryActions.current
    val index = rememberLibraryIndex()
    Column {
        Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, Modifier.weight(1f))
            action?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(albums, key = { it.libraryId }) { album ->
                Column(
                    Modifier
                        .animateItem()
                        .width(148.dp)
                        .goToActions(remember(album, index) { GoTo(artist = index.artistIdOf(album.artist)?.let(::LibraryArtistRoute)) }, navigator)
                        .pressable(
                            onClick = { navigator.open(LibraryAlbumRoute(album.libraryId)) },
                            onLongClick = {
                                index.album(album.libraryId)?.let { actions.open(LibraryTarget.Album(it)) }
                            },
                        ),
                ) {
                    Artwork(album.coverUrl, album.title + album.artist, Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(album.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Keep exploring, at the very end of Play: songs on your servers you've never played, woven
 * with new ones by artists new to you, for as long as you scroll.
 */
@Composable
fun KeepExploring(songs: List<ServerSong>, goesOn: Boolean, onEnd: suspend () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionTitle("Keep exploring")
        Spacer(Modifier.height(4.dp))
        songs.forEachIndexed { index, song ->
            ServerSongRow(song, songs, index, "Keep exploring", Modifier.padding(horizontal = 8.dp))
        }
        if (goesOn) {
            // Composed once the page reaches it: finds more, then again after each lot.
            LaunchedEffect(songs.size) { onEnd() }
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(40.dp))
            }
        }
    }
}

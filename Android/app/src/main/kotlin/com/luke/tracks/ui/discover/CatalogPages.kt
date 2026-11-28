package com.luke.tracks.ui.discover

import com.luke.tracks.sources.PlayResolver
import com.luke.tracks.system.isTv
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.discover.AlbumCatalog
import com.luke.tracks.discover.ArtistCatalog
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.insights.EmptyState
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.CatalogArtistRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.bottomClearance
import com.luke.tracks.ui.play.components.openInAnotherApp
import kotlinx.coroutines.launch
import uniffi.tracks_engine.CatalogSong
import uniffi.tracks_engine.Ownership
import uniffi.tracks_engine.CatalogAlbumKind
import uniffi.tracks_engine.albumOwnership
import uniffi.tracks_engine.foldedName

/** Where this artist is already: in your music, in your history, or neither. */
data class ArtistLinks(val libraryId: String? = null, val historyIdentity: String? = null)

@Composable
fun CatalogArtistScreen(name: String, catalogId: Long?) {
    var retry by remember { mutableIntStateOf(0) }
    val state = rememberArtistCatalog(name, catalogId, retry)
    val context = LocalContext.current
    val links by produceState(ArtistLinks(), name) {
        val graph = context.graph
        val index = (graph.library as? CombinedLibrary)?.index?.value ?: LibraryIndex.Empty
        val identity = foldedName(name)
        val played = runCatching { graph.engine.read { artist(identity) } }.getOrNull() != null
        value = ArtistLinks(index.artistIdOf(name), identity.takeIf { played })
    }
    CatalogArtistPage(name, state, links, onRetry = { retry++ })
}

@Composable
fun CatalogArtistPage(name: String, state: Remote<ArtistCatalog?>, links: ArtistLinks, onRetry: () -> Unit) {
    val navigator = LocalNavigator.current
    val discovery = rememberDiscovery()
    val sources by discovery.sources.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var allSongs by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val page = (state as? Remote.Loaded)?.value
    CompositionLocalProvider(LocalInsightsSnackbar provides snackbar) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomClearance)) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    subtitle = page?.artist?.genre?.let { { Text(it) } },
                    navigationIcon = { IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    actions = {
                        if (page != null) Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                            CatalogArtistMenu(page.artist, expanded = menu, onDismiss = { menu = false })
                        }
                    },
                    scrollBehavior = scroll,
                )
            },
        ) { padding ->
            when {
                state is Remote.Loading || state is Remote.Idle -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }
                state is Remote.Failed -> Box(Modifier.padding(padding)) {
                    EmptyState(Icons.Rounded.SearchOff, "Couldn't load $name", "Check your connection and try again.", action = "Try again", onAction = onRetry)
                }
                page == null -> Box(Modifier.padding(padding)) {
                    EmptyState(Icons.Rounded.SearchOff, "$name isn't in the catalog", "Try searching for them by another name.")
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomClearance),
                ) {
                    val actions = buildList {
                        links.libraryId?.let { id -> add(PageAction("Library", Icons.Rounded.LibraryMusic) { navigator.open(LibraryArtistRoute(id)) }) }
                        links.historyIdentity?.let { id -> add(PageAction("Your history", Icons.Rounded.History) { navigator.open(ArtistRoute(id, name)) }) }
                        if (sources.lidarr) add(PageAction("Follow", Icons.Rounded.PersonAdd) {
                            scope.launch { tell(context, snackbar, scope, discovery.followArtist(name).orEmpty()) }
                        })
                    }
                    if (actions.isNotEmpty()) item(key = "actions") { ConnectedActions(actions, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                    if (page.songs.isNotEmpty()) {
                        val songs = page.songs.distinctBy { it.identity }
                        item(key = "songs-h") {
                            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                GroupHeading("Top songs", Modifier.weight(1f))
                                if (songs.size > 5) TextButton(onClick = { allSongs = !allSongs }) { Text(if (allSongs) "Show less" else "Show all") }
                            }
                        }
                        val shown = if (allSongs) songs else songs.take(5)
                        items(shown, key = { "s${it.id}" }) { CatalogSongRow(it, Modifier.animateItem(), showArtist = false) }
                    }
                    val albums = page.albums.filter { it.kind == CatalogAlbumKind.ALBUM || it.kind == CatalogAlbumKind.COMPILATION }
                    val singles = page.albums - albums.toSet()
                    shelf("Albums", albums)
                    shelf("Singles and EPs", singles)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelf(title: String, albums: List<uniffi.tracks_engine.CatalogAlbum>) {
    if (albums.isEmpty()) return
    item(key = "$title-h") { GroupHeading(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) }
    item(key = title) {
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(albums, key = { it.id.toLong() }) { CatalogAlbumCell(it, showArtist = false) }
        }
    }
}

data class PageAction(val label: String, val icon: ImageVector, val primary: Boolean = false, val run: () -> Unit)

/** Up to three actions as a connected group: the first filled when it's the main one. */
@Composable
fun ConnectedActions(actions: List<PageAction>, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        actions.forEachIndexed { i, action ->
            val shapes = when {
                actions.size == 1 -> ButtonShapes(ButtonDefaults.shape, ButtonDefaults.pressedShape)
                i == 0 -> ButtonShapes(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedLeadingButtonPressShape)
                i == actions.lastIndex -> ButtonShapes(ButtonGroupDefaults.connectedTrailingButtonShape, ButtonGroupDefaults.connectedTrailingButtonPressShape)
                else -> ButtonShapes(RoundedCornerShape(8.dp), ButtonGroupDefaults.connectedMiddleButtonPressShape)
            }
            val content: @Composable () -> Unit = {
                Icon(action.icon, null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
                Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
                Text(action.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val click = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); action.run() }
            val sized = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight)
            if (action.primary) Button(onClick = click, shapes = shapes, contentPadding = ButtonDefaults.MediumContentPadding, modifier = sized) { content() }
            else FilledTonalButton(onClick = click, shapes = shapes, contentPadding = ButtonDefaults.MediumContentPadding, modifier = sized) { content() }
        }
    }
}

@Composable
fun CatalogAlbumScreen(catalogId: Long, title: String, artistName: String) {
    val discovery = rememberDiscovery()
    var retry by remember { mutableIntStateOf(0) }
    val state by produceState<Remote<AlbumCatalog?>>(Remote.Loading, catalogId, retry) {
        value = runCatching { discovery.album(catalogId.toULong()) }.map { Remote.Loaded(it) }.getOrDefault(Remote.Failed)
    }
    CatalogAlbumPage(title, artistName, state, onRetry = { retry++ })
}

@Composable
fun CatalogAlbumPage(title: String, artistName: String, state: Remote<AlbumCatalog?>, onRetry: () -> Unit) {
    val navigator = LocalNavigator.current
    val discovery = rememberDiscovery()
    val sources by discovery.sources.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var missingOnly by rememberSaveable { mutableStateOf(false) }
    var getting by remember { mutableStateOf(false) }
    // The song an album play is being looked for from, for its row's ring.
    var finding by remember { mutableStateOf<ULong?>(null) }
    val beyondLibrary = remember(discovery) { discovery.reachesBeyondLibrary() }
    CompositionLocalProvider(LocalInsightsSnackbar provides snackbar) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomClearance)) },
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = { IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
        ) { padding ->
            val page = (state as? Remote.Loaded)?.value
            when {
                state is Remote.Loading || state is Remote.Idle -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }
                state is Remote.Failed -> Box(Modifier.padding(padding)) {
                    EmptyState(Icons.Rounded.SearchOff, "Couldn't load $title", "Check your connection and try again.", action = "Try again", onAction = onRetry)
                }
                page == null -> Box(Modifier.padding(padding)) { EmptyState(Icons.Rounded.SearchOff, "$title isn't in the catalog", "It may have been taken down.") }
                else -> {
                    val owned = remember(page) { page.songs.associate { it.id to (discovery.owned(it) != null) } }
                    val ownedCount = owned.values.count { it }
                    val ownership = albumOwnership(ownedCount.toUInt(), page.songs.size.toUInt().takeIf { it > 0u })
                    val shown = if (missingOnly && ownership == Ownership.PARTLY_OWNED) page.songs.filter { owned[it.id] != true } else page.songs
                    // The album from a song, each song from wherever Play From finds it; ones nothing can play are left out.
                    val playFrom: (CatalogSong?) -> Unit = playFrom@{ from ->
                        if (finding != null || page.songs.isEmpty()) return@playFrom
                        val first = page.songs.indexOfFirst { it.id == from?.id }.coerceAtLeast(0)
                        finding = page.songs[first].id
                        scope.launch {
                            val found = runCatching { discovery.toPlayAll(page.songs, first) }.getOrDefault(emptyList())
                            finding = null
                            val songs = page.songs.zip(found).mapNotNull { (song, playable) -> playable?.let { song to it } }
                            if (songs.isEmpty()) {
                                tell(context, snackbar, scope, PlayResolver.cantPlay(page.songs[first].title))
                                return@launch
                            }
                            val start = songs.indexOfFirst { it.first.id == page.songs[first].id }.coerceAtLeast(0)
                            context.graph.player.play(songs.map { it.second }, start, QueueOrigin(page.album.title, QueueOrigin.Kind.Album))
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomClearance)) {
                        item(key = "hero") { AlbumHero(page, ownership, ownedCount, onArtist = { navigator.open(CatalogArtistRoute(page.album.artistName, page.album.artistId?.toLong())) }) }
                        item(key = "actions") {
                            val actions = buildList {
                                if (ownedCount > 0 || beyondLibrary) add(PageAction("Play", Icons.Rounded.PlayArrow, primary = ownedCount > 0) {
                                    playFrom(null)
                                })
                                if (sources.canFetch && ownership != Ownership.OWNED) {
                                    val label = when {
                                        getting -> "Asking"
                                        sources.lidarr -> "Ask Lidarr"
                                        ownership == Ownership.PARTLY_OWNED -> "Get the rest"
                                        else -> "Get album"
                                    }
                                    add(PageAction(label, Icons.Rounded.Download, primary = ownedCount == 0) {
                                        if (getting) return@PageAction
                                        getting = true
                                        scope.launch {
                                            val message = runCatching { discovery.getAlbum(page.album) }.getOrDefault("Couldn't get it")
                                            getting = false
                                            tell(context, snackbar, scope, message)
                                        }
                                    })
                                }
                                if (ownership != Ownership.OWNED && size < 2 && !context.isTv) add(PageAction("Open in", Icons.AutoMirrored.Rounded.OpenInNew) {
                                    openInAnotherApp(context, page.album.title, page.album.artistName, page.album.title)
                                })
                            }
                            ConnectedActions(actions, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        if (ownership == Ownership.PARTLY_OWNED) item(key = "filter") {
                            SongFilter(missingOnly, missing = page.songs.size - ownedCount, onChange = { missingOnly = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        itemsIndexed(shown, key = { _, s -> s.id.toLong() }) { i, song ->
                            CatalogSongRow(
                                song, Modifier.animateItem(), showAlbum = false, showArtist = !song.artistName.equals(page.album.artistName, ignoreCase = true),
                                trackNumber = song.trackNumber?.toInt() ?: (i + 1), onPlay = playFrom, pending = finding == song.id,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumHero(page: AlbumCatalog, ownership: Ownership, ownedCount: Int, onArtist: () -> Unit) {
    val album = page.album
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(album.artworkUrl, album.title + album.artistName, Modifier.size(240.dp), shape = RoundedCornerShape(28.dp))
        Spacer(Modifier.height(20.dp))
        Text(album.title, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onArtist) { Text(album.artistName, style = MaterialTheme.typography.titleMedium) }
        val kind = when (album.kind) {
            CatalogAlbumKind.SINGLE -> "Single"
            CatalogAlbumKind.EP -> "EP"
            CatalogAlbumKind.COMPILATION -> "Compilation"
            CatalogAlbumKind.ALBUM -> "Album"
        }
        val songs = page.songs.size.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" }
        Text(
            listOfNotNull(kind, album.year?.toString(), songs).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(ownership != Ownership.MISSING) {
            Text(
                if (ownership == Ownership.OWNED) "In your library" else "$ownedCount of ${page.songs.size} in your library",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** All songs, or only the missing ones: a connected pair. */
@Composable
private fun SongFilter(missingOnly: Boolean, missing: Int, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        listOf(false to "All songs", true to "Missing $missing").forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = missingOnly == value,
                onCheckedChange = { if (missingOnly != value) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onChange(value) } },
                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

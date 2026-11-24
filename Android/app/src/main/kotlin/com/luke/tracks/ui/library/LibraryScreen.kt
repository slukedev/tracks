package com.luke.tracks.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.library.LibrarySnapshot
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.musicPermission
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.discover.Remote
import com.luke.tracks.ui.discover.catalogSearchItems
import com.luke.tracks.ui.discover.rememberCatalogSearch
import androidx.compose.runtime.mutableIntStateOf
import com.luke.tracks.ui.library.servers.ServerList
import com.luke.tracks.ui.library.servers.rememberServerEditing
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SettingsRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryTab(val label: String, val icon: ImageVector) {
    Songs("Songs", Icons.Rounded.MusicNote),
    Albums("Albums", Icons.Rounded.Album),
    Artists("Artists", Icons.Rounded.Person),
    Servers("Servers", Icons.Rounded.Dns),
}

/** The Your Music tab. */
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val library = graph.library as? CombinedLibrary
    val snapshot by graph.library.snapshot.collectAsStateWithLifecycle()
    val servers by remember(library) { library?.servers?.entries ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }.collectAsStateWithLifecycle()
    val current by remember { graph.player.state.map { it.current?.mediaId } }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        haptics.performHapticFeedback(if (granted) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        scope.launch { library?.device?.refresh() }
    }
    // Back from the system's settings with access newly allowed.
    LifecycleResumeEffect(snapshot.hasPermission) {
        if (!snapshot.hasPermission) scope.launch { library?.device?.refresh() }
        onPauseOrDispose { }
    }

    LibraryContent(
        snapshot = snapshot,
        index = { library?.index?.value ?: LibraryIndex.Empty },
        servers = servers,
        currentMediaId = current,
        onAllowAccess = { permission.launch(musicPermission) },
    )
}

/** The tab's content from plain values, so it renders the same in screenshots. */
@Composable
fun LibraryContent(
    snapshot: LibrarySnapshot,
    index: () -> LibraryIndex,
    servers: List<ServerEntry>,
    currentMediaId: String?,
    onAllowAccess: () -> Unit,
    initialTab: LibraryTab = LibraryTab.Albums,
    initialQuery: String? = null,
) {
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var searching by rememberSaveable { mutableStateOf(initialQuery != null) }
    var query by rememberSaveable { mutableStateOf(initialQuery.orEmpty()) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val editing = rememberServerEditing()

    BackHandler(enabled = searching) { searching = false; query = "" }

    WithLibraryActions {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                if (searching) {
                    SearchTopBar(query, onQueryChange = { query = it }, onClose = { searching = false; query = "" })
                } else {
                    LargeFlexibleTopAppBar(
                        title = { Text("Your Music") },
                        actions = {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, contentDescription = "Search your music") }
                            IconButton(onClick = { navigator.open(SettingsRoute) }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                if (searching) {
                    SearchResults(query, index, currentMediaId)
                    return@Column
                }
                LibraryTabs(tab, onSelect = {
                    if (it != tab) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    tab = it
                })
                val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
                val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (slideInHorizontally(spatial) { if (forward) it / 6 else -it / 6 } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(spatial) { if (forward) -it / 6 else it / 6 } + fadeOut(fade))
                    },
                    label = "tab",
                    modifier = Modifier.fillMaxSize(),
                ) { shown ->
                    when {
                        shown == LibraryTab.Servers -> ServerList(
                            entries = servers,
                            onAdd = { editing.add() },
                            onEdit = { editing.edit(it) },
                            onRemove = { editing.remove(it) },
                            onTrust = { editing.trust(it) },
                            contentPadding = PaddingValues(bottom = MiniPlayerClearance),
                        )
                        snapshot.songs.isEmpty() && snapshot.isLoading -> Loading()
                        snapshot.songs.isEmpty() && !snapshot.hasPermission -> PermissionState(onAllowAccess, onAddServer = { tab = LibraryTab.Servers; editing.add() })
                        snapshot.songs.isEmpty() -> NoMusicState(onAddServer = { tab = LibraryTab.Servers; editing.add() })
                        else -> Column {
                            if (!snapshot.hasPermission) AccessBanner(onAllowAccess)
                            when (shown) {
                                LibraryTab.Songs -> SongsTab(snapshot.songs, currentMediaId)
                                LibraryTab.Albums -> AlbumsTab(snapshot.albums)
                                LibraryTab.Artists -> ArtistsTab(snapshot.artists, index)
                                LibraryTab.Servers -> Unit
                            }
                        }
                    }
                }
            }
        }
        editing.Sheets()
    }
}

/** Songs, Albums, Artists, Servers: a connected button group, the selected one filled. */
@Composable
private fun LibraryTabs(selected: LibraryTab, onSelect: (LibraryTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        LibraryTab.entries.forEachIndexed { i, item ->
            val checked = item == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(item) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    LibraryTab.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                AnimatedVisibility(visible = checked) {
                    Row {
                        Icon(item.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Text(item.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SongsTab(songs: List<PlayableSong>, currentMediaId: String?) {
    val player = LocalContext.current.graph.player
    val actions = LocalLibraryActions.current
    val origin = remember { QueueOrigin("Your Music", QueueOrigin.Kind.Library) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = MiniPlayerClearance)) {
        item(key = "play") {
            PlayShuffleButtons(
                onPlay = { player.play(songs, origin = origin) },
                onShuffle = { player.play(songs, origin = origin, shuffle = true) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            )
        }
        items(songs, key = { it.mediaId }) { song ->
            SongRow(
                song = song,
                isCurrent = song.mediaId == currentMediaId,
                onClick = { player.play(songs, songs.indexOf(song), origin) },
                onMore = { actions.open(LibraryTarget.Song(song)) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun AlbumsTab(albums: List<LibraryAlbum>) {
    val navigator = LocalNavigator.current
    val actions = LocalLibraryActions.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(156.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = MiniPlayerClearance),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCell(
                album = album,
                onClick = { navigator.open(LibraryAlbumRoute(album.id)) },
                onLongClick = { actions.open(LibraryTarget.Album(album)) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun ArtistsTab(artists: List<LibraryArtist>, index: () -> LibraryIndex) {
    val navigator = LocalNavigator.current
    val actions = LocalLibraryActions.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = MiniPlayerClearance)) {
        items(artists, key = { it.id }) { artist ->
            ArtistRow(
                artist = artist,
                onClick = { navigator.open(LibraryArtistRoute(artist.id)) },
                onLongClick = { actions.open(LibraryTarget.Artist(artist, index().songsBy(artist))) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text("Songs, albums and artists") },
                leadingIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

private data class Found(val songs: List<PlayableSong>, val albums: List<LibraryAlbum>, val artists: List<LibraryArtist>)

@Composable
private fun SearchResults(query: String, index: () -> LibraryIndex, currentMediaId: String?) {
    val navigator = LocalNavigator.current
    val actions = LocalLibraryActions.current
    val player = LocalContext.current.graph.player
    val found by produceState(Found(emptyList(), emptyList(), emptyList()), query) {
        value = withContext(Dispatchers.Default) {
            val lookups = index()
            Found(lookups.search(query), lookups.searchAlbums(query), lookups.searchArtists(query, 5))
        }
    }
    var retry by remember { mutableIntStateOf(0) }
    val catalog = rememberCatalogSearch(query, retry)
    if (query.isBlank()) {
        Message(Icons.Rounded.Search, "Search for music", "Yours first, then everything else.")
        return
    }
    val nothingHere = found.songs.isEmpty() && found.albums.isEmpty() && found.artists.isEmpty()
    if (nothingHere && (catalog is Remote.Idle || (catalog is Remote.Loaded && catalog.value.isEmpty))) {
        Message(Icons.Rounded.Search, "Nothing matches “$query”", "Try fewer words, or another spelling.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = MiniPlayerClearance)) {
        if (!nothingHere) item(key = "yours-h") {
            com.luke.tracks.ui.discover.GroupHeading("In your music", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        }
        if (found.artists.isNotEmpty()) {
            item(key = "artists-h") { SectionHeader("Artists") }
            items(found.artists, key = { "artist-" + it.id }) { artist ->
                ArtistRow(
                    artist, onClick = { navigator.open(LibraryArtistRoute(artist.id)) },
                    onLongClick = { actions.open(LibraryTarget.Artist(artist, index().songsBy(artist))) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (found.albums.isNotEmpty()) {
            item(key = "albums-h") { SectionHeader("Albums") }
            item(key = "albums") {
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    items(found.albums, key = { it.id }) { album ->
                        AlbumCell(
                            album, onClick = { navigator.open(LibraryAlbumRoute(album.id)) },
                            onLongClick = { actions.open(LibraryTarget.Album(album)) },
                            modifier = Modifier.width(148.dp).animateItem(),
                        )
                    }
                }
            }
        }
        if (found.songs.isNotEmpty()) {
            item(key = "songs-h") { SectionHeader("Songs") }
            items(found.songs, key = { "song-" + it.mediaId }) { song ->
                SongRow(
                    song, isCurrent = song.mediaId == currentMediaId,
                    onClick = { player.play(found.songs, found.songs.indexOf(song), QueueOrigin(query, QueueOrigin.Kind.Search)) },
                    onMore = { actions.open(LibraryTarget.Song(song)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        catalogSearchItems(catalog, edge = 20.dp, onRetry = { retry++ })
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().padding(bottom = MiniPlayerClearance), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(64.dp))
    }
}

/** A friendly empty state: a big expressive shape with an icon, a line and what to do. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = MiniPlayerClearance),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(132.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        actions()
    }
}

@Composable
private fun Message(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PermissionState(onAllow: () -> Unit, onAddServer: () -> Unit) {
    EmptyState(
        icon = Icons.Rounded.LibraryMusic,
        title = "Play the music on your phone",
        body = "Allow access to music files to play them in Tracks.",
    ) {
        Button(onClick = onAllow, modifier = Modifier.height(56.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
            Text("Allow access", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAddServer) { Text("Or add a music server") }
    }
}

@Composable
private fun NoMusicState(onAddServer: () -> Unit) {
    EmptyState(
        icon = Icons.Rounded.MusicNote,
        title = "No music here yet",
        body = "Music files on this phone show up here. You can also add a music server.",
    ) {
        OutlinedButton(onClick = onAddServer, modifier = Modifier.height(56.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
            Icon(Icons.Rounded.Dns, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add a server", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AccessBanner(onAllow: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "See the music on this phone too",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAllow) { Text("Allow") }
        }
    }
}

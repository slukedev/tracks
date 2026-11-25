package com.luke.tracks.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.sources.SourceFilterChip
import com.luke.tracks.ui.sources.rememberSources
import com.luke.tracks.ui.sources.sourceList
import com.luke.tracks.ui.sources.sourceSelection
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.unit.Dp
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
    Artists("Artists", Icons.Rounded.Person),
    Albums("Albums", Icons.Rounded.Album),
    Songs("Songs", Icons.Rounded.MusicNote),
}

/** The Library tab: every source's music as one library, filtered to one source when chosen. */
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val library = graph.library as? CombinedLibrary
    val snapshot by graph.library.snapshot.collectAsStateWithLifecycle()
    val current by remember { graph.player.state.map { it.current?.mediaId } }.collectAsStateWithLifecycle(null)
    val sources by sourceList()
    val selection by sourceSelection()
    val registry = rememberSources()
    val scope = rememberCoroutineScope()

    // Back from the system's settings with access newly allowed.
    LifecycleResumeEffect(snapshot.hasPermission) {
        if (!snapshot.hasPermission) scope.launch { library?.device?.refresh() }
        onPauseOrDispose { }
    }

    // Spotify's saved albums, playlists and liked songs come from Spotify itself.
    val spotify = com.luke.tracks.spotify.Spotify.integration
    if (spotify != null && selection == SourceSelection.Only(SourceId.Spotify)) {
        spotify.Library(onBack = { registry.select(SourceSelection.All) })
        return
    }
    // So do Apple Music's.
    val appleMusic = com.luke.tracks.applemusic.AppleMusic.service
    if (appleMusic != null && selection == SourceSelection.Only(SourceId.AppleMusic)) {
        com.luke.tracks.ui.applemusic.AppleMusicLibraryScreen(appleMusic, onBack = { registry.select(SourceSelection.All) })
        return
    }

    LibraryContent(
        snapshot = snapshot,
        index = { library?.index?.value ?: (graph.library.index.value) },
        sources = sources,
        selection = selection,
        onSelect = registry::select,
        sourceOf = registry::sourceOf,
        currentMediaId = current,
    )
}

/** The tab's content from plain values, so it renders the same in screenshots. */
@Composable
fun LibraryContent(
    snapshot: LibrarySnapshot,
    index: () -> LibraryIndex,
    sources: List<Source>,
    selection: SourceSelection,
    onSelect: (SourceSelection) -> Unit,
    sourceOf: (String) -> SourceId?,
    currentMediaId: String?,
    initialTab: LibraryTab = LibraryTab.Albums,
) {
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val shown = remember(snapshot, selection) { snapshot.only(selection, sourceOf) }

    WithLibraryActions {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Library") },
                    actions = {
                        IconButton(onClick = { navigator.open(SettingsRoute) }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                when {
                    snapshot.songs.isEmpty() && snapshot.isLoading -> Loading()
                    sources.isEmpty() && snapshot.songs.isEmpty() -> EmptyState(
                        icon = Icons.Rounded.LibraryMusic,
                        title = "No music yet",
                    ) {
                        Button(
                            onClick = { navigator.open(SettingsPageRoute("sources/add")) },
                            modifier = Modifier.heightIn(min = 56.dp),
                            contentPadding = PaddingValues(horizontal = 28.dp),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add source", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    else -> {
                        LibraryChips(tab, onSelect = {
                            if (it != tab) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            tab = it
                        }, sources = sources, selection = selection, onSource = onSelect)
                        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                        Crossfade(targetState = selection to tab, animationSpec = fade, label = "library", modifier = Modifier.fillMaxSize()) { (chosen, showing) ->
                            if (shown.songs.isEmpty()) {
                                val name = (chosen as? SourceSelection.Only)?.let { only -> sources.firstOrNull { it.id == only.source }?.name }
                                Message(Icons.Rounded.MusicNote, if (name != null) "Nothing from $name yet" else "No music yet", null)
                                return@Crossfade
                            }
                            when (showing) {
                                LibraryTab.Songs -> SongsTab(shown.songs, currentMediaId)
                                LibraryTab.Albums -> AlbumsTab(shown.albums)
                                LibraryTab.Artists -> ArtistsTab(shown.artists, index)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The library narrowed to one source's songs, with albums and artists that have some. */
fun LibrarySnapshot.only(selection: SourceSelection, sourceOf: (String) -> SourceId?): LibrarySnapshot {
    if (selection is SourceSelection.All) return this
    val keep = { song: PlayableSong -> selection.includes(sourceOf(song.mediaId)) }
    val albums = albums.mapNotNull { album -> album.songs.filter(keep).takeIf { it.isNotEmpty() }?.let { album.copy(songs = it) } }
    val kept = albums.map { it.id }.toSet()
    val artists = artists.mapNotNull { artist ->
        val theirs = artist.albums.filter { it.id in kept }.map { album -> albums.first { it.id == album.id } }
        if (theirs.isEmpty()) null else artist.copy(albums = theirs, songCount = theirs.sumOf { it.songs.size })
    }
    return copy(songs = songs.filter(keep), albums = albums, artists = artists)
}

/**
 * The source filter, then Artists, Albums and Songs as single-select chips. Scrolls sideways
 * when it doesn't fit (a long source name, the largest text).
 */
@Composable
private fun LibraryChips(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    sources: List<Source>,
    selection: SourceSelection,
    onSource: (SourceSelection) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sources.size >= 2) SourceFilterChip(sources, selection, onSource)
        LibraryTab.entries.forEach { item ->
            val on = item == selected
            FilterChip(
                selected = on,
                onClick = { onSelect(item) },
                label = { Text(item.label) },
                leadingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
}

@Composable
private fun SongsTab(songs: List<PlayableSong>, currentMediaId: String?) {
    val player = LocalContext.current.graph.player
    val actions = LocalLibraryActions.current
    val origin = remember { QueueOrigin("Library", QueueOrigin.Kind.Library) }
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

/** What a search found in your library. */
data class LibraryFound(val songs: List<PlayableSong>, val albums: List<LibraryAlbum>, val artists: List<LibraryArtist>) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()

    companion object {
        val None = LibraryFound(emptyList(), emptyList(), emptyList())
    }
}

/** Your library's songs, albums and artists for [query], off the main thread, kept to [keep]'s songs. */
@Composable
fun rememberLibraryFound(query: String, index: () -> LibraryIndex, keep: (PlayableSong) -> Boolean = { true }): LibraryFound {
    val found by produceState(LibraryFound.None, query, keep) {
        value = if (query.isBlank()) LibraryFound.None else withContext(Dispatchers.Default) {
            val lookups = index()
            val albums = lookups.searchAlbums(query).filter { album -> album.songs.any(keep) }
            val artists = lookups.searchArtists(query, 5).filter { artist -> artist.albums.any { album -> album.songs.any(keep) } }
            LibraryFound(lookups.search(query).filter(keep), albums, artists)
        }
    }
    return found
}

/** A search's library results as list items: artists, albums, then songs that play from here. */
fun LazyListScope.libraryFoundItems(
    found: LibraryFound,
    query: String,
    index: () -> LibraryIndex,
    currentMediaId: String?,
    edge: Dp = 0.dp,
    rowModifier: Modifier = Modifier,
) {
    if (found.artists.isNotEmpty()) {
        item(key = "lib-artists-h") { SectionHeader("Artists", Modifier.padding(horizontal = edge)) }
        items(found.artists, key = { "lib-artist-" + it.id }) { artist ->
            val navigator = LocalNavigator.current
            val actions = LocalLibraryActions.current
            ArtistRow(
                artist, onClick = { navigator.open(LibraryArtistRoute(artist.id)) },
                onLongClick = { actions.open(LibraryTarget.Artist(artist, index().songsBy(artist))) },
                modifier = Modifier.animateItem().then(rowModifier),
            )
        }
    }
    if (found.albums.isNotEmpty()) {
        item(key = "lib-albums-h") { SectionHeader("Albums", Modifier.padding(horizontal = edge)) }
        item(key = "lib-albums") {
            val navigator = LocalNavigator.current
            val actions = LocalLibraryActions.current
            LazyRow(rowModifier, contentPadding = PaddingValues(horizontal = 10.dp)) {
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
        item(key = "lib-songs-h") { SectionHeader("Songs", Modifier.padding(horizontal = edge)) }
        items(found.songs, key = { "lib-song-" + it.mediaId }) { song ->
            val actions = LocalLibraryActions.current
            val player = LocalContext.current.graph.player
            SongRow(
                song, isCurrent = song.mediaId == currentMediaId,
                onClick = { player.play(found.songs, found.songs.indexOf(song), QueueOrigin(query, QueueOrigin.Kind.Search)) },
                onMore = { actions.open(LibraryTarget.Song(song)) },
                modifier = Modifier.animateItem().then(rowModifier),
            )
        }
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
    body: String? = null,
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
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        actions()
    }
}

@Composable
private fun Message(icon: ImageVector, title: String, body: String?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}


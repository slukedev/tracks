package com.luke.tracks.ui.applemusic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.applemusic.AppleMusicCollection
import com.luke.tracks.applemusic.AppleMusicPage
import com.luke.tracks.applemusic.AppleMusicResults
import com.luke.tracks.applemusic.AppleMusicService
import com.luke.tracks.applemusic.AppleMusicSong
import com.luke.tracks.applemusic.toAppleMusicProblem
import com.luke.tracks.applemusic.shelfMessage
import com.luke.tracks.graph
import com.luke.tracks.net.Logs
import com.luke.tracks.ui.navigation.AppleMusicCollectionRoute
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import uniffi.tracks_engine.songIdentity

enum class AppleMusicShelf(val label: String) { Playlists("Playlists"), Albums("Albums"), Songs("Songs") }

/** A list loaded a page at a time, and what went wrong loading it. */
private class Paged<T> {
    val items = mutableStateListOf<T>()
    var next by mutableStateOf<Int?>(0)
    var loading by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
}

/**
 * Your Apple Music library, playlists, albums and songs, and search across it and the catalog.
 * No browse pages: a library and search are what a player needs.
 */
@Composable
fun AppleMusicLibraryScreen(service: AppleMusicService, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shelf by rememberSaveable { mutableStateOf(AppleMusicShelf.Playlists) }
    var query by rememberSaveable { mutableStateOf("") }
    var opened by remember { mutableStateOf<AppleMusicCollection?>(null) }

    val playlists = remember { Paged<AppleMusicCollection>() }
    val albums = remember { Paged<AppleMusicCollection>() }
    val songs = remember { Paged<AppleMusicSong>() }

    fun <T> load(paged: Paged<T>, fetch: suspend (Int) -> AppleMusicPage<T>) {
        val offset = paged.next ?: return
        if (paged.loading) return
        paged.loading = true
        paged.problem = null
        scope.launch {
            try {
                val page = fetch(offset)
                paged.items += page.items
                paged.next = page.nextOffset
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                paged.problem = error.toAppleMusicProblem().message
            } finally {
                paged.loading = false
            }
        }
    }
    val loadPlaylists = { load(playlists) { service.api.libraryPlaylists(it) } }
    val loadAlbums = { load(albums) { service.api.libraryAlbums(it) } }
    val loadSongs = { load(songs) { service.api.librarySongs(it) } }

    val play = { list: List<AppleMusicSong>, index: Int, origin: QueueOrigin, shuffle: Boolean ->
        val (queue, start) = playableQueue(list, index)
        if (queue.isNotEmpty()) context.graph.player.play(queue, start, origin, shuffle)
    }

    BackHandler(enabled = opened != null) { opened = null }

    AnimatedContent(opened, label = "apple-music-page") { collection ->
        if (collection != null) {
            CollectionPage(service, collection, onBack = { opened = null }, play = play)
            return@AnimatedContent
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Apple Music") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                SearchField(query, onChange = { query = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (query.isNotBlank()) {
                    SearchResults(service, query, open = { opened = it }, play = play)
                    return@Column
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppleMusicShelf.entries.forEach { option ->
                        FilterChip(selected = shelf == option, onClick = { shelf = option }, label = { Text(option.label) })
                    }
                }
                when (shelf) {
                    AppleMusicShelf.Playlists -> PagedList(playlists, loadPlaylists) { _, item -> CollectionRow(item) { opened = item } }
                    AppleMusicShelf.Albums -> PagedList(albums, loadAlbums) { _, item -> CollectionRow(item) { opened = item } }
                    AppleMusicShelf.Songs -> PagedList(songs, loadSongs) { index, song ->
                        SongRow(song) { play(songs.items.toList(), index, QueueOrigin("Songs", QueueOrigin.Kind.Library), false) }
                    }
                }
            }
        }
    }
}

/** An Apple Music album or playlist opened from Play. */
@Composable
fun AppleMusicCollectionScreen(route: AppleMusicCollectionRoute, onBack: () -> Unit) {
    val context = LocalContext.current
    val service = com.luke.tracks.applemusic.AppleMusic.service ?: return
    val collection = remember(route) {
        AppleMusicCollection(
            id = route.id,
            kind = AppleMusicCollection.Kind.entries.firstOrNull { it.name == route.kind } ?: AppleMusicCollection.Kind.Album,
            title = route.title,
            subtitle = route.subtitle,
            artworkTemplate = route.artworkTemplate,
        )
    }
    CollectionPage(service, collection, onBack) { list, index, origin, shuffle ->
        val (queue, start) = playableQueue(list, index)
        if (queue.isNotEmpty()) context.graph.player.play(queue, start, origin, shuffle)
    }
}

/** The route that opens [collection]. */
fun AppleMusicCollection.route() = AppleMusicCollectionRoute(id, kind.name, title, subtitle, artworkTemplate)

/** The songs that can play here, and where the one tapped landed among them. */
internal fun playableQueue(list: List<AppleMusicSong>, index: Int, identity: (String, String) -> String = ::songIdentity): Pair<List<PlayableSong>, Int> {
    val start = list.take(index).count { it.playable }
    return list.filter { it.playable }.map { it.toPlayableSong(identity) } to start
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search Apple Music") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") } }) else null,
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A list that asks for the next page as its end comes into view. */
@Composable
private fun <T> PagedList(paged: Paged<T>, loadMore: () -> Unit, row: @Composable (Int, T) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(paged) {
        if (paged.items.isEmpty()) loadMore()
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= paged.items.size - PREFETCH }
            .collect { loadMore() }
    }
    LazyColumn(state = list, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(paged.items) { index, item -> row(index, item) }
        status(paged.loading, paged.problem, empty = paged.items.isEmpty() && paged.next == null, retry = loadMore)
    }
}

private fun LazyListScope.status(loading: Boolean, problem: String?, empty: Boolean, retry: () -> Unit) {
    when {
        loading -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
        }
        problem != null -> item(key = "problem") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(problem, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = retry) { Text("Try again") }
            }
        }
        empty -> item(key = "empty") {
            Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
    }
}

@Composable
private fun SearchResults(
    service: AppleMusicService,
    query: String,
    open: (AppleMusicCollection) -> Unit,
    play: (List<AppleMusicSong>, Int, QueueOrigin, Boolean) -> Unit,
) {
    var mine by remember { mutableStateOf(AppleMusicResults()) }
    var catalog by remember { mutableStateOf(AppleMusicResults()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(query, attempt) {
        delay(SEARCH_PAUSE_MS)
        loading = true
        problem = null
        val term = query.trim()
        // Each side answers on its own; only both failing says so.
        val (library, store) = coroutineScope {
            val library = async { runCatching { service.api.searchLibrary(term) } }
            val store = async { runCatching { service.storefront?.let { service.api.searchCatalog(it, term) } ?: AppleMusicResults() } }
            library.await() to store.await()
        }
        library.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        store.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        mine = library.getOrDefault(AppleMusicResults())
        val known = mine.songs.mapNotNull { it.catalogId }.toSet()
        catalog = store.getOrDefault(AppleMusicResults()).let { found -> found.copy(songs = found.songs.filter { it.catalogId !in known }) }
        if (library.isFailure && store.isFailure) problem = "Can't search right now."
        loading = false
    }
    val nothing = mine.songs.isEmpty() && mine.albums.isEmpty() && mine.playlists.isEmpty() &&
        catalog.songs.isEmpty() && catalog.albums.isEmpty() && catalog.playlists.isEmpty()
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        results("mine", null, mine, query, open, play)
        results("catalog", "Not in your library", catalog, query, open, play)
        status(loading, problem, empty = !loading && problem == null && nothing, retry = { attempt++ })
    }
}

private fun LazyListScope.results(
    key: String,
    title: String?,
    results: AppleMusicResults,
    query: String,
    open: (AppleMusicCollection) -> Unit,
    play: (List<AppleMusicSong>, Int, QueueOrigin, Boolean) -> Unit,
) {
    val collections = results.albums + results.playlists
    if (results.songs.isEmpty() && collections.isEmpty()) return
    if (title != null) item(key = "$key-title") { Heading(title) }
    itemsIndexed(results.songs, key = { _, song -> "$key-s-${song.id}" }) { index, song ->
        SongRow(song) { play(results.songs, index, QueueOrigin(query, QueueOrigin.Kind.Search), false) }
    }
    itemsIndexed(collections, key = { _, item -> "$key-c-${item.kind}-${item.id}" }) { _, item -> CollectionRow(item) { open(item) } }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** An album or playlist: its cover, Play and Shuffle, and its songs. */
@Composable
private fun CollectionPage(
    service: AppleMusicService,
    collection: AppleMusicCollection,
    onBack: () -> Unit,
    play: (List<AppleMusicSong>, Int, QueueOrigin, Boolean) -> Unit,
) {
    var songs by remember(collection) { mutableStateOf<List<AppleMusicSong>?>(null) }
    var problem by remember(collection) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(collection, attempt) {
        problem = null
        try {
            songs = service.api.songs(collection, service.storefront)
            Logs.i("Apple Music: ${collection.kind.name} ${collection.id} has ${songs?.size} songs")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Logs.w("Apple Music: ${collection.kind.name} ${collection.id} didn't load", error)
            problem = error.toAppleMusicProblem().shelfMessage()
        }
    }
    val origin = QueueOrigin(collection.title, if (collection.isAlbum) QueueOrigin.Kind.Album else QueueOrigin.Kind.Playlist)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(collection.artworkUrl(), seed = collection.title, shape = MaterialTheme.shapes.large, modifier = Modifier.width(240.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(collection.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle(collection)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    val ready = songs.orEmpty()
                    val any = ready.any { it.playable }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play(ready, 0, origin, false) }, enabled = any) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play")
                        }
                        FilledTonalButton(onClick = { play(ready, 0, origin, true) }, enabled = any) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Shuffle")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            val list = songs
            if (list != null) {
                itemsIndexed(list, key = { index, song -> "$index:${song.id}" }) { index, song ->
                    SongRow(song) { play(list, index, origin, false) }
                }
            }
            status(loading = list == null && problem == null, problem = problem, empty = list?.isEmpty() == true, retry = { attempt++ })
        }
    }
}

private fun subtitle(item: AppleMusicCollection): String? =
    listOfNotNull(item.subtitle?.takeIf { it.isNotBlank() }, item.year?.toString()).joinToString(" · ").ifEmpty { null }

@Composable
private fun CollectionRow(item: AppleMusicCollection, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle(item)?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Artwork(item.artworkUrl(), seed = item.title, shape = MaterialTheme.shapes.small, modifier = Modifier.size(56.dp)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** A song; one that can't play here (an upload that isn't on Apple Music) is dimmed and does nothing. */
@Composable
private fun SongRow(song: AppleMusicSong, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(song.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(song.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Artwork(com.luke.tracks.applemusic.artwork(song.artworkTemplate, 120), seed = song.title, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.size(48.dp))
        },
        modifier = Modifier
            .then(if (song.playable) Modifier.clickable(onClick = onClick) else Modifier.alpha(UNAVAILABLE_ALPHA))
            .semantics(mergeDescendants = true) {
                contentDescription = "${song.title}, ${song.artistName}"
                if (!song.playable) stateDescription = "Unavailable"
            },
    )
}

private const val PREFETCH = 10
private const val SEARCH_PAUSE_MS = 300L
private const val UNAVAILABLE_ALPHA = 0.4f

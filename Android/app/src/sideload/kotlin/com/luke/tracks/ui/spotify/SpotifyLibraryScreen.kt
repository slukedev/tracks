package com.luke.tracks.ui.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.spotify.SpotifyService
import com.luke.tracks.spotify.SpotifyUris
import com.luke.tracks.spotify.toPlayableSong
import com.luke.tracks.spotify.toSpotifyProblem
import com.luke.tracks.ui.components.Artwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Spotify art is never cropped and keeps small corners: 4dp in rows, 8dp large. */
private val SmallCover = RoundedCornerShape(4.dp)
private val LargeCover = RoundedCornerShape(8.dp)

/** An album or playlist from Spotify, as a row and a page. */
data class SpotifyCollection(
    val uri: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val kind: QueueOrigin.Kind,
    /** The songs' addresses when they're already known (albums); playlists fetch them on opening. */
    val trackUris: List<String> = emptyList(),
)

enum class SpotifyShelf(val label: String) { Albums("Albums"), Playlists("Playlists"), Liked("Liked Songs") }

/** Something that can go wrong loading, and whether a page is on its way. */
private class Paged<T> {
    val items = mutableStateListOf<T>()
    var next by mutableStateOf<Int?>(0)
    var loading by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
}

/**
 * Spotify's saved albums, playlists and liked songs, and search. No editorial, no Home: a
 * library and search are what a player needs.
 */
@Composable
fun SpotifyLibraryScreen(service: SpotifyService, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shelf by rememberSaveable { mutableStateOf(SpotifyShelf.Albums) }
    var query by rememberSaveable { mutableStateOf("") }
    var opened by remember { mutableStateOf<SpotifyCollection?>(null) }

    val albums = remember { Paged<SpotifyCollection>() }
    val playlists = remember { Paged<SpotifyCollection>() }
    val liked = remember { Paged<PlayableSong>() }

    fun <T> load(paged: Paged<T>, fetch: suspend (Int) -> Pair<List<T>, Int?>) {
        val offset = paged.next ?: return
        if (paged.loading) return
        paged.loading = true
        paged.problem = null
        scope.launch {
            try {
                val (items, next) = fetch(offset)
                paged.items += items
                paged.next = next
            } catch (error: Exception) {
                paged.problem = error.toSpotifyProblem().message
            } finally {
                paged.loading = false
            }
        }
    }
    val loadAlbums = { load(albums) { offset -> service.savedAlbums(offset).let { page -> page.items.map { it.toCollection() } to page.nextOffset?.toInt() } } }
    val loadPlaylists = { load(playlists) { offset -> service.playlists(offset).let { page -> page.items.map { it.toCollection() } to page.nextOffset?.toInt() } } }
    val loadLiked = { load(liked) { offset -> service.likedSongs(offset).let { page -> page.items.map { it.toPlayableSong() } to page.nextOffset?.toInt() } } }

    val play = { songs: List<PlayableSong>, index: Int, origin: QueueOrigin, shuffle: Boolean ->
        context.graph.player.play(songs, index, origin, shuffle)
    }

    BackHandler(enabled = opened != null) { opened = null }

    AnimatedContent(opened, label = "spotify-page") { collection ->
        if (collection != null) {
            SpotifyCollectionPage(service, collection, onBack = { opened = null }, play = play)
            return@AnimatedContent
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Spotify") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    },
                    actions = { SpotifyMark(Modifier.padding(end = 16.dp).size(24.dp), contentDescription = "Spotify") },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                SearchField(query, onChange = { query = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (query.isNotBlank()) {
                    SpotifySearchResults(service, query, open = { opened = it }, play = play)
                    return@Column
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpotifyShelf.entries.forEach { option ->
                        FilterChip(selected = shelf == option, onClick = { shelf = option }, label = { Text(option.label) })
                    }
                }
                when (shelf) {
                    SpotifyShelf.Albums -> PagedList(albums, loadAlbums) { _, item -> CollectionRow(item) { opened = item } }
                    SpotifyShelf.Playlists -> PagedList(playlists, loadPlaylists) { _, item -> CollectionRow(item) { opened = item } }
                    SpotifyShelf.Liked -> PagedList(liked, loadLiked) { index, song ->
                        SongRow(song) { play(liked.items.toList(), index, QueueOrigin("Liked Songs", QueueOrigin.Kind.Playlist), false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search Spotify") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") } }) else null,
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
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
            Text(
                "Nothing here yet",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}

@Composable
private fun SpotifySearchResults(
    service: SpotifyService,
    query: String,
    open: (SpotifyCollection) -> Unit,
    play: (List<PlayableSong>, Int, QueueOrigin, Boolean) -> Unit,
) {
    var songs by remember { mutableStateOf(emptyList<PlayableSong>()) }
    var collections by remember { mutableStateOf(emptyList<SpotifyCollection>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(query, attempt) {
        delay(SEARCH_PAUSE_MS)
        loading = true
        problem = null
        try {
            val results = service.search(query.trim())
            songs = results.tracks.items.map { it.toPlayableSong() }
            collections = results.albums.items.map { it.toCollection() } + results.playlists.items.map { it.toCollection() }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            problem = error.toSpotifyProblem().message
        } finally {
            loading = false
        }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        if (songs.isNotEmpty()) item(key = "songs") { Heading("Songs") }
        itemsIndexed(songs, key = { _, song -> "s" + song.mediaId }) { index, song ->
            SongRow(song) { play(songs, index, QueueOrigin(query, QueueOrigin.Kind.Search), false) }
        }
        if (collections.isNotEmpty()) item(key = "albums") { Heading("Albums and Playlists") }
        itemsIndexed(collections, key = { _, item -> "c" + item.uri }) { _, item -> CollectionRow(item) { open(item) } }
        status(loading, problem, empty = !loading && problem == null && songs.isEmpty() && collections.isEmpty(), retry = { attempt++ })
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** An album or playlist page: its cover uncropped, Play and Shuffle, and Open in Spotify. */
@Composable
private fun SpotifyCollectionPage(
    service: SpotifyService,
    collection: SpotifyCollection,
    onBack: () -> Unit,
    play: (List<PlayableSong>, Int, QueueOrigin, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var songs by remember(collection.uri) { mutableStateOf<List<PlayableSong>?>(null) }
    var problem by remember(collection.uri) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(collection.uri, attempt) {
        problem = null
        try {
            val uris = collection.trackUris.ifEmpty {
                if (collection.kind == QueueOrigin.Kind.Album) service.album(collection.uri).trackUris else service.playlist(collection.uri).trackUris
            }
            songs = service.songs(uris)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            problem = error.toSpotifyProblem().message
        }
    }
    val origin = QueueOrigin(collection.title, collection.kind)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Open in Spotify") },
                                leadingIcon = { SpotifyMark(Modifier.size(20.dp)) },
                                onClick = {
                                    menu = false
                                    openInSpotify(context, collection.uri)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(collection.artworkUrl, seed = collection.title, shape = LargeCover, modifier = Modifier.width(240.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(collection.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        SpotifyMark(Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(collection.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                    val ready = songs.orEmpty()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play(ready, 0, origin, false) }, enabled = ready.isNotEmpty()) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play")
                        }
                        FilledTonalButton(onClick = { play(ready, 0, origin, true) }, enabled = ready.isNotEmpty()) {
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
                itemsIndexed(list, key = { index, song -> "$index:${song.mediaId}" }) { index, song ->
                    SongRow(song) { play(list, index, origin, false) }
                }
            }
            status(loading = list == null && problem == null, problem = problem, empty = list?.isEmpty() == true, retry = { attempt++ })
        }
    }
}

@Composable
private fun CollectionRow(item: SpotifyCollection, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { AttributedLine(item.subtitle) },
        leadingContent = { Artwork(item.artworkUrl, seed = item.title, shape = SmallCover, modifier = Modifier.size(56.dp)) },
        colors = ListItemDefaults.colors(),
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${item.title}, ${item.subtitle}, Spotify" },
    )
}

@Composable
private fun SongRow(song: PlayableSong, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { AttributedLine(song.artistName) },
        leadingContent = { Artwork(song.artworkUri?.toString(), seed = song.title, shape = SmallCover, modifier = Modifier.size(48.dp)) },
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${song.title}, ${song.artistName}, Spotify" },
    )
}

/** A row's second line, with Spotify's mark after it: every Spotify row carries it. */
@Composable
private fun AttributedLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        SpotifyMark(Modifier.size(12.dp))
    }
}

fun openInSpotify(context: Context, uri: String) {
    val page = SpotifyUris.webPage(uri) ?: return
    // The Spotify app takes its own address when it's installed; the web page otherwise.
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun uniffi.tracks_engine.SpotifyAlbum.toCollection() = SpotifyCollection(
    uri = uri,
    title = title,
    subtitle = listOfNotNull(artists.joinToString(", ") { it.name }.ifBlank { null }, releaseYear?.toString()).joinToString(" · "),
    artworkUrl = artworkUrl,
    kind = QueueOrigin.Kind.Album,
    trackUris = trackUris,
)

fun uniffi.tracks_engine.SpotifyPlaylist.toCollection() = SpotifyCollection(
    uri = uri,
    title = name,
    subtitle = listOfNotNull(owner, if (trackCount == 1u) "1 song" else "$trackCount songs").joinToString(" · "),
    artworkUrl = artworkUrl,
    kind = QueueOrigin.Kind.Playlist,
)

private const val PREFETCH = 10
private const val SEARCH_PAUSE_MS = 300L

package com.luke.tracks.ui.tv.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.library.LibraryFound
import com.luke.tracks.ui.library.rememberLibraryFound
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.search.RecentSearches
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvChip
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvIconButton
import com.luke.tracks.ui.tv.components.TvSectionTitle
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.detail.PendingPlay
import com.luke.tracks.ui.tv.detail.TvNote
import com.luke.tracks.ui.tv.detail.rememberPendingPlay
import com.luke.tracks.ui.tv.detail.TvRowScroll
import com.luke.tracks.ui.tv.detail.TvShelfScroll
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.ui.tv.theme.TvMotion
import kotlinx.coroutines.delay
import uniffi.tracks_engine.SearchResults
import androidx.compose.material3.MaterialTheme as M3Theme

private const val PauseMs = 250L
private const val MaxQuery = 200

/** Search on TV: your library and everything you've played, as you type. */
@Composable
fun TvSearchScreen() {
    val graph = LocalContext.current.graph
    val navigator = LocalTvNavigator.current
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { navigator.consumeSearchQuery()?.let { query = it } }
    // Asked for only after a short pause in typing, so each key doesn't start a search.
    var settled by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isBlank()) settled = "" else { delay(PauseMs); settled = query.trim() }
    }
    val revision by graph.engine.revision.collectAsState()
    var history by remember { mutableStateOf<SearchResults?>(null) }
    LaunchedEffect(settled, revision) {
        history = if (settled.isEmpty()) null else graph.engine.read { search(settled, 24u) }
    }
    val keepAll = remember { { _: PlayableSong -> true } }
    val library = rememberLibraryFound(settled, { graph.library.index.value }, keepAll)
    TvSearchContent(query, onQuery = { query = it.take(MaxQuery) }, settled = settled, library = library, history = history)
}

@Composable
fun TvSearchContent(query: String, onQuery: (String) -> Unit, settled: String, library: LibraryFound, history: SearchResults?) {
    val context = LocalContext.current
    val memory = rememberFocusMemory()
    val recents = remember(context) { RecentSearches(context) }
    memory.ClaimFocus(memory.requester("field"))
    val pending = rememberPendingPlay()
    val nothing = settled.isNotEmpty() && history != null && library.isEmpty &&
        history.songs.isEmpty() && history.artists.isEmpty() && history.albums.isEmpty()
    val opened = { recents.add(settled) }
    TvShelfScroll { LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom + 16.dp),
        verticalArrangement = Arrangement.spacedBy(TvLayout.SectionSpacing - 16.dp),
    ) {
        item(key = "field") { Field(query, onQuery, memory) }
        when {
            query.isBlank() -> if (recents.items.isNotEmpty()) item(key = "recents") {
                Column {
                    TvSectionTitle("Recent Searches", Modifier.padding(start = TvLayout.ContentStart))
                    TvRowScroll { LazyRow(
                        contentPadding = PaddingValues(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.focusRestorer(),
                    ) {
                        items(recents.items.toList(), key = { "recent:$it" }) { text ->
                            TvChip(text, selected = false, onClick = { onQuery(text) }, icon = Icons.Rounded.History, modifier = Modifier.rememberFocus(memory, "recent:$text"))
                        }
                    } }
                }
            }
            nothing -> item(key = "none") { TvNote("No Results", Modifier.padding(start = TvLayout.ContentStart)) }
            else -> results(library, history, memory, pending, opened)
        }
    } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(
    library: LibraryFound,
    history: SearchResults?,
    memory: FocusMemory,
    pending: PendingPlay,
    opened: () -> Unit,
) {
    if (library.songs.isNotEmpty()) item(key = "songs") {
        val songs = library.songs.take(24)
        TvRowScroll { TvShelf("Songs", songs, key = { "song:${it.mediaId}" }, focusMemory = memory) { song ->
            val key = "song:${song.mediaId}"
            TvCoverCard(
                song.artworkUri, (song.albumTitle ?: song.title) + song.artistName, song.title,
                onClick = { opened(); pending.play(key, songs, songs.indexOf(song), QueueOrigin("Search", QueueOrigin.Kind.Search)) },
                subtitle = song.artistName, pending = pending.isPending(key),
            )
        } }
    }
    if (library.albums.isNotEmpty()) item(key = "albums") {
        val navigator = LocalTvNavigator.current
        TvRowScroll { TvShelf("Albums", library.albums, key = { "album:${it.id}" }, focusMemory = memory) { album ->
            TvCoverCard(album.artworkUri, album.title + album.artistName, album.title, onClick = { opened(); navigator.open(LibraryAlbumRoute(album.id)) }, subtitle = album.artistName)
        } }
    }
    if (library.artists.isNotEmpty()) item(key = "artists") {
        val navigator = LocalTvNavigator.current
        TvRowScroll { TvShelf("Artists", library.artists, key = { "artist:${it.id}" }, focusMemory = memory) { artist ->
            TvCoverCard(artist.albums.firstNotNullOfOrNull { it.artworkUri }, artist.name, artist.name, onClick = { opened(); navigator.open(LibraryArtistRoute(artist.id)) }, width = 156.dp, circle = true)
        } }
    }
    val r = history ?: return
    // What you've played that matched: artists first, then songs, then albums, as one shelf.
    val played = r.artists.map { Played.Artist(it.identity, it.name, it.artworkUrl) } +
        r.songs.map { Played.Song(it.identity, it.title, it.artistName, it.albumTitle, it.artworkUrl) } +
        r.albums.map { Played.Album(it.identity, it.title, it.artistName, it.artworkUrl) }
    if (played.isNotEmpty()) item(key = "history") {
        val navigator = LocalTvNavigator.current
        TvRowScroll { TvShelf("In Your History", played, key = { it.key }, focusMemory = memory) { item ->
            when (item) {
                is Played.Artist -> TvCoverCard(item.art, item.name, item.name, onClick = { opened(); navigator.open(ArtistRoute(item.identity, item.name)) }, width = 156.dp, circle = true)
                is Played.Song -> TvCoverCard(item.art, (item.album ?: item.title) + item.artist, item.title, onClick = { opened(); navigator.open(SongRoute(item.identity, item.title, item.artist)) }, subtitle = item.artist)
                is Played.Album -> TvCoverCard(item.art, item.title + item.artist, item.title, onClick = { opened(); navigator.open(AlbumRoute(item.identity, item.title, item.artist)) }, subtitle = item.artist)
            }
        } }
    }
}

private sealed interface Played {
    val key: String

    data class Artist(val identity: String, val name: String, val art: String?) : Played {
        override val key get() = "played-artist:$identity"
    }

    data class Song(val identity: String, val title: String, val artist: String, val album: String?, val art: String?) : Played {
        override val key get() = "played-song:$identity"
    }

    data class Album(val identity: String, val title: String, val artist: String, val art: String?) : Played {
        override val key get() = "played-album:$identity"
    }
}

/**
 * The search field: a pill that takes focus like any control, opens the keyboard when
 * pressed rather than on focus, and lets Down leave for the results. The mic beside it asks
 * the system's voice search, where the TV has one.
 */
@Composable
private fun Field(query: String, onQuery: (String) -> Unit, memory: FocusMemory) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val voice = remember(context) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) onQuery(spoken.filterNot { it.isISOControl() })
    }
    Row(
        Modifier.fillMaxWidth().padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier
                .weight(1f)
                .rememberFocus(memory, "field")
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { keyboard?.show(); true }
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        else -> false
                    }
                }
                .semantics { contentDescription = "Search" },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .height(56.dp)
                        .background(if (focused) M3Theme.colorScheme.surfaceContainerHighest else M3Theme.colorScheme.surfaceContainerHigh, CircleShape)
                        .border(BorderStroke(if (focused) TvMotion.FocusBorderWidth else 0.dp, if (focused) scheme.primary else scheme.surface.copy(alpha = 0f)), CircleShape)
                        .padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Songs, Artists and Albums", style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant, maxLines = 1)
                        inner()
                    }
                }
            },
        )
        if (voice != null) {
            TvIconButton(Icons.Rounded.Mic, "Voice Search", { runCatching { listen.launch(voice) } }, Modifier.rememberFocus(memory, "mic"))
        }
    }
}

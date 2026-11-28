package com.luke.tracks.ui.tv.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.luke.tracks.ui.tv.components.TvShelfSkeleton
import com.luke.tracks.ui.tv.components.TvTextField
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.detail.PendingPlay
import com.luke.tracks.ui.tv.detail.TvNote
import com.luke.tracks.ui.tv.detail.rememberPendingPlay
import com.luke.tracks.ui.tv.detail.TvRowScroll
import com.luke.tracks.ui.tv.detail.TvShelfScroll
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout
import com.luke.tracks.sources.PlayFromOrder
import com.luke.tracks.sources.sources
import com.luke.tracks.streaming.SearchRanks
import com.luke.tracks.streaming.StreamingRepository
import com.luke.tracks.streaming.StreamingState
import com.luke.tracks.streaming.placeKind
import com.luke.tracks.ui.services.ServiceSearch
import com.luke.tracks.ui.services.ServiceSearcher
import com.luke.tracks.ui.services.rememberStreaming
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import uniffi.tracks_engine.SearchResults

private const val PauseMs = 250L
private const val MaxQuery = 200

/**
 * Search on TV: your library, your servers' streaming services and everything you've played,
 * as you type. A service Play From ranks above your own sources comes first.
 */
@Composable
fun TvSearchScreen(repository: StreamingRepository? = rememberStreaming()?.repository) {
    val graph = LocalContext.current.graph
    val navigator = LocalTvNavigator.current
    val field = rememberTextFieldState()
    // Keyed on the waiting query, so a search from outside also reaches a Search already showing.
    LaunchedEffect(navigator.pendingSearchQuery) { navigator.consumeSearchQuery()?.let(field::setTextAndPlaceCursorAtEnd) }
    val query = field.text.toString()
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
    LaunchedEffect(repository) { repository?.refreshIfStale() }
    val streaming by (repository?.state ?: NoStreaming).collectAsState()
    // Already settled above, so the services are asked at once; a newer query cancels the last.
    val queries = remember { MutableStateFlow(ServiceSearcher.Query("")) }
    LaunchedEffect(settled) { queries.value = ServiceSearcher.Query(settled) }
    val services by remember(repository) {
        ServiceSearcher(repository, owns = { graph.library.index.value.song(it) != null }, debounceMs = 0, types = TvServiceTypes).states(queries)
    }.collectAsState(ServiceSearch.Idle)
    // The places here, in the order Play From shows them: a stored place this TV doesn't have can't rank.
    val places by graph.sources.playFromChoices.collectAsState()
    val playFrom = remember(places) { PlayFromOrder(places.map { it.place }) }
    TvSearchContent(field, settled = settled, library = library, history = history, services = services, streaming = streaming, playFrom = playFrom)
}

private val NoStreaming = MutableStateFlow(StreamingState())

@Composable
fun TvSearchContent(
    field: TextFieldState,
    settled: String,
    library: LibraryFound,
    history: SearchResults?,
    services: ServiceSearch = ServiceSearch.Idle,
    streaming: StreamingState = StreamingState(),
    playFrom: PlayFromOrder = PlayFromOrder.Standard,
) {
    val context = LocalContext.current
    val query = field.text
    val onQuery: (String) -> Unit = { field.setTextAndPlaceCursorAtEnd(it) }
    val memory = rememberFocusMemory()
    val recents = remember(context) { RecentSearches(context) }
    memory.ClaimFocus(memory.requester("field"))
    val pending = rememberPendingPlay()
    val around = remember(services, playFrom) { services.shown?.aroundLibrary(playFrom) }
    // Where a search still on its way will answer: ahead of your library when a service it asks comes first.
    val servicesFirst = remember(streaming, playFrom) { SearchRanks(playFrom).let { ranks -> streaming.searchable.any { ranks.aboveLibrary(it.second.placeKind) } } }
    val servicesWaiting = services is ServiceSearch.Loading && services.previous == null
    val nothing = settled.isNotEmpty() && history != null && library.isEmpty &&
        history.songs.isEmpty() && history.artists.isEmpty() && history.albums.isEmpty() &&
        !servicesWaiting && around?.let { it.before.isEmpty() && it.after.isEmpty() } != false
    val opened = { recents.add(settled) }
    TvShelfScroll { LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom + 16.dp),
        verticalArrangement = Arrangement.spacedBy(TvLayout.SectionSpacing - 16.dp),
    ) {
        item(key = "field") { Field(field, memory) }
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
            else -> {
                if (servicesWaiting && servicesFirst) item(key = "services-loading", contentType = "shelf-loading") { TvShelfSkeleton(cards = 6) }
                around?.let { tvServiceSections(it.before, streaming, memory, pending, opened) }
                results(library, memory, pending, opened)
                around?.let { tvServiceSections(it.after, streaming, memory, pending, opened) }
                if (servicesWaiting && !servicesFirst) item(key = "services-loading", contentType = "shelf-loading") { TvShelfSkeleton(cards = 6) }
                history(history, memory, opened)
            }
        }
    } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(
    library: LibraryFound,
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
}

private fun androidx.compose.foundation.lazy.LazyListScope.history(history: SearchResults?, memory: FocusMemory, opened: () -> Unit) {
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
 * The search field, which opens the keyboard when pressed rather than on focus, and the mic
 * beside it, which asks the system's voice search where the TV has one.
 */
@Composable
private fun Field(field: TextFieldState, memory: FocusMemory) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val voice = remember(context) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) field.setTextAndPlaceCursorAtEnd(spoken.filterNot { it.isISOControl() }.take(MaxQuery))
    }
    Row(
        Modifier.fillMaxWidth().padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvTextField(
            state = field,
            label = "Search",
            placeholder = "Songs, Artists and Albums",
            leadingIcon = Icons.Rounded.Search,
            inputTransformation = InputTransformation.maxLengthTrim(MaxQuery),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) },
            modifier = Modifier.weight(1f).rememberFocus(memory, "field"),
        )
        if (voice != null) {
            TvIconButton(Icons.Rounded.Mic, "Voice Search", { runCatching { listen.launch(voice) } }, Modifier.rememberFocus(memory, "mic"))
        }
    }
}

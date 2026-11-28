package com.luke.tracks.ui.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.map
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.ui.insights.AlbumCell
import com.luke.tracks.ui.insights.ArtistCell
import com.luke.tracks.ui.insights.EmptyState
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.GroupGap
import com.luke.tracks.ui.insights.InsightRow
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.SectionHeader
import com.luke.tracks.ui.insights.bleed
import com.luke.tracks.ui.library.libraryFoundItems
import com.luke.tracks.ui.insights.rememberGraph
import com.luke.tracks.ui.insights.segmentedShape
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.services.serviceLibraryItems
import com.luke.tracks.ui.services.serviceSearchItems
import com.luke.tracks.ui.services.serviceSectionItems
import com.luke.tracks.sources.sources
import com.luke.tracks.ui.discover.NotYours
import com.luke.tracks.ui.discover.Remote
import com.luke.tracks.ui.discover.catalogSearchItems
import com.luke.tracks.ui.discover.rememberCatalogSearch
import kotlinx.coroutines.delay
import uniffi.tracks_engine.SearchResults

/** The last few searches, newest first, kept on the device. */
class RecentSearches(context: Context) {
    private val prefs = context.getSharedPreferences("insights_search", Context.MODE_PRIVATE)
    val items = mutableStateListOf<String>().apply {
        addAll(prefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() })
    }

    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        items.removeAll { it.equals(t, ignoreCase = true) }
        items.add(0, t)
        while (items.size > 8) items.removeAt(items.lastIndex)
        save()
    }

    fun remove(text: String) { items.remove(text); save() }
    fun clear() { items.clear(); save() }
    private fun save() = prefs.edit().putString("recent", items.joinToString("\n")).apply()
}

/** What a search looks through. */
enum class SearchScope(val label: String) { Everything("Everything"), Library("Library"), History("History") }

/**
 * The Search tab: your library, everything you've played and the catalog, as you type. Chips
 * narrow it to the library or the history, and, with two or more sources, to one source.
 */
@Composable
fun SearchScreen(initialQuery: String = "", initialScope: SearchScope = SearchScope.Everything) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val recents = remember { RecentSearches(context) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var scope by rememberSaveable { mutableStateOf(initialScope) }
    var onlySource by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyService by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    val revision by graph.engine.revision.collectAsState()
    val focus = remember { FocusRequester() }
    val registry = com.luke.tracks.ui.sources.rememberSources()
    val sources by com.luke.tracks.ui.sources.sourceList()
    val current by remember { graph.player.state.map { it.current?.mediaId } }.collectAsState(null)
    val atRoot = navigator.backStack.size <= 1
    // Your servers' streaming services: searched after the library, or alone when one is chosen.
    val streaming = com.luke.tracks.ui.services.streamingState()
    val serviceKey = com.luke.tracks.streaming.ServiceKey.parse(onlyService)?.takeIf { key -> streaming.services.any { it.first == key } }
    val serviceSearch = com.luke.tracks.ui.services.serviceSearchViewModel()
    val services by serviceSearch.state.collectAsState()
    val streamingRepository = com.luke.tracks.ui.services.rememberStreaming()?.repository
    LaunchedEffect(streamingRepository) { streamingRepository?.refreshIfStale() }
    LaunchedEffect(query, serviceKey, scope) {
        serviceSearch.search(if (scope == SearchScope.Everything) query else "", serviceKey)
    }

    // Instant, but not on every keystroke: wait for a short pause in typing.
    LaunchedEffect(query, revision) {
        val text = query.trim()
        if (text.isEmpty()) { results = null; return@LaunchedEffect }
        delay(150)
        results = graph.engine.read { search(text, 24u) }
    }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    // A search from Assistant or the system's search: shown as if typed.
    LaunchedEffect(navigator.pendingSearch) {
        navigator.pendingSearch?.let { query = it; navigator.pendingSearch = null }
    }

    fun remember(text: String = query) = recents.add(text)

    val keep: (com.luke.tracks.playback.PlayableSong) -> Boolean = remember(onlySource) {
        val wanted = onlySource?.let(com.luke.tracks.sources.SourceId::parse);
        { song -> wanted == null || registry.sourceOf(song.mediaId) == wanted }
    }
    val library = com.luke.tracks.ui.library.rememberLibraryFound(query.trim(), { graph.library.index.value }, keep)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding(),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { remember(it) },
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text("Songs, artists and albums") },
                        leadingIcon = {
                            if (atRoot) {
                                Icon(Icons.Rounded.Search, contentDescription = null)
                            } else {
                                IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                            }
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); query = "" }) {
                                    Icon(Icons.Rounded.Close, "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                ScopeChips(
                    scope = scope,
                    onScope = { scope = it },
                    sources = if (scope == SearchScope.History || sources.size < 2) emptyList() else sources,
                    onlySource = onlySource,
                    onSource = { onlySource = it; if (it != null) onlyService = null },
                    services = if (scope == SearchScope.Everything) streaming.services else emptyList(),
                    onlyService = serviceKey,
                    onService = { onlyService = it?.raw; if (it != null) onlySource = null },
                )
            }
        },
    ) { inner ->
        val r = results
        var retry by remember { mutableIntStateOf(0) }
        val catalog = rememberCatalogSearch(if (serviceKey == null) query else "", retry)
        val catalogEmpty = catalog is Remote.Idle || (catalog is Remote.Loaded && catalog.value.isEmpty)
        // With a service chosen, only it is searched: your library and history step aside.
        val historyShown = serviceKey == null && scope != SearchScope.Library && r != null && (r.songs.isNotEmpty() || r.artists.isNotEmpty() || r.albums.isNotEmpty())
        val libraryShown = serviceKey == null && scope != SearchScope.History && !library.isEmpty
        val servicesEmpty = services is com.luke.tracks.ui.services.ServiceSearch.Idle ||
            (services as? com.luke.tracks.ui.services.ServiceSearch.Loaded)?.results?.isEmpty == true
        val chosenService = serviceKey?.let(streaming::service)
        val savedOnService = com.luke.tracks.ui.services.rememberServiceLibrary(serviceKey.takeIf { query.isBlank() }, chosenService, retry)
        Box(Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize()) {
            when {
                query.isBlank() && serviceKey != null && savedOnService != null -> LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = com.luke.tracks.ui.navigation.bottomClearance),
                ) {
                    serviceLibraryItems(serviceKey, chosenService, savedOnService, onRetry = { retry++ })
                }
                query.isBlank() -> Recents(recents, onPick = { query = it })
                serviceKey != null -> com.luke.tracks.ui.library.WithLibraryActions {
                    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = com.luke.tracks.ui.navigation.bottomClearance)) {
                        serviceSearchItems(
                            services, streaming, serviceKey, query.trim(), current, edge = 20.dp, listPadding = 16.dp,
                            onRetry = { streamingRepository?.refreshIfStale(0); serviceSearch.retry() },
                        )
                        if (servicesEmpty && services !is com.luke.tracks.ui.services.ServiceSearch.Idle) item(key = "services-none") {
                            EmptyState(icon = Icons.Rounded.SearchOff, title = "No results on ${chosenService?.name ?: "this service"}")
                        }
                    }
                }
                r == null && library.isEmpty -> Spacer(Modifier.height(1.dp))
                !historyShown && !libraryShown && catalogEmpty && servicesEmpty -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = "No results",
                )
                else -> com.luke.tracks.ui.library.WithLibraryActions {
                    Results(
                        r = r.takeIf { historyShown },
                        library = library.takeIf { libraryShown },
                        query = query.trim(),
                        currentMediaId = current,
                        catalog = catalog,
                        services = services,
                        streaming = streaming,
                        onRetry = { retry++ },
                        onRetryServices = { streamingRepository?.refreshIfStale(0); serviceSearch.retry() },
                        onOpen = { remember() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeChips(
    scope: SearchScope,
    onScope: (SearchScope) -> Unit,
    sources: List<com.luke.tracks.sources.Source>,
    onlySource: String?,
    onSource: (String?) -> Unit,
    services: List<Pair<com.luke.tracks.streaming.ServiceKey, com.luke.tracks.library.trackside.TracksideCatalogService>> = emptyList(),
    onlyService: com.luke.tracks.streaming.ServiceKey? = null,
    onService: (com.luke.tracks.streaming.ServiceKey?) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchScope.entries.forEach { option ->
            val on = option == scope
            FilterChip(
                selected = on,
                onClick = { if (!on) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onScope(option) } },
                label = { Text(option.label) },
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
        sources.forEach { source ->
            val on = source.id.raw == onlySource
            FilterChip(
                selected = on,
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSource(if (on) null else source.id.raw) },
                label = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { com.luke.tracks.ui.sources.SourceMark(source, size = FilterChipDefaults.IconSize) },
            )
        }
        com.luke.tracks.ui.services.ServiceChips(services, onlyService, onService)
    }
}

@Composable
private fun Recents(recents: RecentSearches, onPick: (String) -> Unit) {
    // Nothing searched yet: the field says what to type, so the page stays empty.
    if (recents.items.isEmpty()) return
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = com.luke.tracks.ui.navigation.bottomClearance)) {
        item(key = "header") {
            SectionHeader("Recent searches", action = "Clear", onAction = { recents.clear() })
        }
        itemsIndexed(recents.items, key = { _, t -> t }) { i, text ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = segmentedShape(i, recents.items.size),
                modifier = Modifier.animateItem().padding(top = if (i == 0) 0.dp else GroupGap).fillMaxWidth(),
            ) {
                Row(
                    Modifier
                        .clickable(onClick = { onPick(text) })
                        .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { recents.remove(text) }) { Icon(Icons.Rounded.Close, "Remove $text", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun Results(
    r: SearchResults?,
    library: com.luke.tracks.ui.library.LibraryFound?,
    query: String,
    currentMediaId: String?,
    catalog: Remote<NotYours>,
    services: com.luke.tracks.ui.services.ServiceSearch,
    streaming: com.luke.tracks.streaming.StreamingState,
    onRetry: () -> Unit,
    onRetryServices: () -> Unit,
    onOpen: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val graph = rememberGraph()
    var allSongs by remember(r) { mutableStateOf(false) }
    val places by graph.sources.playFromChoices.collectAsState()
    val playFrom = remember(places) { com.luke.tracks.sources.PlayFromOrder(places.map { it.place }) }
    // A service Play From ranks above your own sources answers first; the rest follow your library.
    val above = remember(services, playFrom) { services.shown?.aroundLibrary(playFrom)?.before.orEmpty() }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = com.luke.tracks.ui.navigation.bottomClearance)) {
        serviceSectionItems(
            above, streaming, query, currentMediaId, edge = 20.dp, listPadding = 16.dp,
            loading = services is com.luke.tracks.ui.services.ServiceSearch.Loading,
        )
        if (library != null) {
            item(key = "library-h") {
                com.luke.tracks.ui.discover.GroupHeading("Library", Modifier.padding(start = 4.dp, end = 4.dp, top = if (above.isEmpty()) 4.dp else 20.dp))
            }
            libraryFoundItems(library, query, { graph.library.index.value }, currentMediaId, rowModifier = Modifier.bleed(16.dp))
        }
        // Songs you have are shown once, in your library, not again under a service.
        serviceSearchItems(services, streaming, null, query, currentMediaId, edge = 20.dp, listPadding = 16.dp, onRetry = onRetryServices, shownAbove = above)
        if (r == null) {
            catalogSearchItems(catalog, edge = 20.dp, onRetry = onRetry, listPadding = 16.dp)
            return@LazyColumn
        }
        if (library != null) {
            item(key = "history-h") { com.luke.tracks.ui.discover.GroupHeading("History", Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp)) }
        }
        if (r.artists.isNotEmpty()) {
            item(key = "artistsHeader", contentType = "header") { SectionHeader("Artists") }
            item(key = "artists", contentType = "shelf") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.fillMaxWidth().bleed(16.dp)) {
                    itemsIndexed(r.artists, key = { _, a -> a.identity }) { i, a ->
                        Box(Modifier.clickableCapture(onOpen)) {
                            ArtistCell(ItemTarget.Artist(a.identity, a.name, a.artworkUrl), Format.plays(a.plays.toLong()), index = i)
                        }
                    }
                }
            }
        }
        if (r.songs.isNotEmpty()) {
            item(key = "songsHeader", contentType = "header") {
                SectionHeader(
                    "Songs", Modifier.padding(top = 12.dp),
                    action = if (r.songs.size > 6) (if (allSongs) "Show less" else "Show all") else null,
                    onAction = { allSongs = !allSongs },
                )
            }
            val shown = if (allSongs) r.songs else r.songs.take(6)
            itemsIndexed(shown, key = { _, s -> "song-${s.identity}" }, contentType = { _, _ -> "row" }) { i, s ->
                val target = ItemTarget.Song(s.identity, s.title, s.artistName, s.albumTitle, s.artworkUrl)
                InsightRow(
                    target, i, shown.size,
                    modifier = Modifier.animateItem().padding(top = if (i == 0) 0.dp else GroupGap),
                    onClick = { onOpen(); navigator.open(target.route) },
                ) {
                    Text(Format.plays(s.plays.toLong()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (r.albums.isNotEmpty()) {
            item(key = "albumsHeader", contentType = "header") { SectionHeader("Albums", Modifier.padding(top = 12.dp)) }
            item(key = "albums", contentType = "shelf") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.fillMaxWidth().bleed(16.dp)) {
                    itemsIndexed(r.albums, key = { _, a -> a.identity }) { _, a ->
                        Box(Modifier.clickableCapture(onOpen)) {
                            AlbumCell(ItemTarget.Album(a.identity, a.title, a.artistName, a.artworkUrl), a.artistName)
                        }
                    }
                }
            }
        }
        catalogSearchItems(catalog, edge = 20.dp, onRetry = onRetry, listPadding = 16.dp)
    }
}

/** Notes a tap on a child (to keep the search in Recents) without taking the tap from it. */
private fun Modifier.clickableCapture(onTap: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.changedToUp() }) onTap()
        }
    }
}

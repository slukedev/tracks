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
import com.luke.tracks.ui.insights.rememberGraph
import com.luke.tracks.ui.insights.segmentedShape
import com.luke.tracks.ui.navigation.LocalNavigator
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

/**
 * Search everything you've played: results as you type, songs, artists and albums, with
 * your recent searches when the box is empty.
 */
@Composable
fun SearchScreen(initialQuery: String = "") {
    val graph = rememberGraph()
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val recents = remember { RecentSearches(context) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    val revision by graph.engine.revision.collectAsState()
    val focus = remember { FocusRequester() }

    // Instant, but not on every keystroke: wait for a short pause in typing.
    LaunchedEffect(query, revision) {
        val text = query.trim()
        if (text.isEmpty()) { results = null; return@LaunchedEffect }
        delay(150)
        results = graph.engine.read { search(text, 24u) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun remember(text: String = query) = recents.add(text)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { remember(it) },
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text("Songs, artists and albums") },
                        leadingIcon = {
                            IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
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
            }
        },
    ) { inner ->
        val r = results
        Box(Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize()) {
            when {
                query.isBlank() -> Recents(recents, onPick = { query = it })
                r == null -> Spacer(Modifier.height(1.dp))
                r.songs.isEmpty() && r.artists.isEmpty() && r.albums.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = "Nothing found",
                    message = "Nothing you've played matches \"${query.trim()}\". Check the spelling, or try fewer words.",
                )
                else -> Results(r, onOpen = { remember() })
            }
        }
    }
}

@Composable
private fun Recents(recents: RecentSearches, onPick: (String) -> Unit) {
    if (recents.items.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.Search,
            title = "Search your history",
            message = "Find any song, artist or album you've played, and see how often and since when.",
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp)) {
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
private fun Results(r: SearchResults, onOpen: () -> Unit) {
    val navigator = LocalNavigator.current
    var allSongs by remember(r) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp)) {
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

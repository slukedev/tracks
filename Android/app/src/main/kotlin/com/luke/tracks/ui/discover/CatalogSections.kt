package com.luke.tracks.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.luke.tracks.discover.ArtistCatalog
import com.luke.tracks.discover.Discovery
import com.luke.tracks.ui.insights.bleed
import com.luke.tracks.ui.navigation.CatalogArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import kotlinx.coroutines.delay
import uniffi.tracks_engine.AlbumOwnership
import uniffi.tracks_engine.CatalogAlbumKind
import uniffi.tracks_engine.CatalogResults

/** Loading something from the catalog, which is a network away. */
sealed interface Remote<out T> {
    data object Idle : Remote<Nothing>
    data object Loading : Remote<Nothing>
    data class Loaded<T>(val value: T) : Remote<T>
    data object Failed : Remote<Nothing>
}

/** The catalog's answer for a search, less what's already yours. */
@Immutable
data class NotYours(val results: CatalogResults) {
    val isEmpty get() = results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty()
}

/**
 * Searches the catalog as you type, after a pause a little longer than the library's: it's
 * a network away. [retry] bumps to ask again after a failure.
 */
@Composable
fun rememberCatalogSearch(query: String, retry: Int = 0): Remote<NotYours> {
    val discovery = rememberDiscovery()
    val state by produceState<Remote<NotYours>>(Remote.Idle, query, retry) {
        val text = query.trim()
        if (text.length < 2) { value = Remote.Idle; return@produceState }
        value = Remote.Loading
        delay(350)
        value = runCatching { discovery.search(text) }
            .map { found -> Remote.Loaded(NotYours(notYours(discovery, found))) }
            .getOrDefault(Remote.Failed)
    }
    return state
}

private fun notYours(discovery: Discovery, found: CatalogResults) = CatalogResults(
    songs = found.songs.filter { discovery.owned(it) == null }.distinctBy { it.identity }.take(12),
    albums = found.albums.filter { discovery.holding(it).ownership != AlbumOwnership.OWNED },
    artists = found.artists,
)

/**
 * The catalog's part of a search, under what you have: artists, albums and songs that aren't
 * in your music, each to preview, get where something can, or open in another app.
 */
fun LazyListScope.catalogSearchItems(state: Remote<NotYours>, edge: Dp, onRetry: () -> Unit, listPadding: Dp = 0.dp) {
    // Full width, whatever padding the list has: shelves scroll to the screen's edges.
    val wide = Modifier.bleed(listPadding)
    when (state) {
        Remote.Idle -> Unit
        Remote.Loading -> item(key = "catalog-loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp).animateItem(), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(40.dp))
            }
        }
        Remote.Failed -> item(key = "catalog-failed") {
            CatalogProblem("Couldn't search beyond your music", onRetry, wide.padding(horizontal = edge).animateItem())
        }
        is Remote.Loaded -> {
            val r = state.value.results
            if (state.value.isEmpty) return
            item(key = "catalog-header", contentType = "header") {
                GroupHeading("Not in your music", wide.padding(horizontal = edge).padding(top = 20.dp).animateItem())
            }
            if (r.artists.isNotEmpty()) {
                item(key = "catalog-artists-h", contentType = "subheader") { SubHeading("Artists", wide.padding(horizontal = edge).animateItem()) }
                item(key = "catalog-artists", contentType = "shelf") {
                    LazyRow(contentPadding = PaddingValues(horizontal = edge - 8.dp), modifier = wide.animateItem()) {
                        items(r.artists, key = { "a${it.id}" }) { CatalogArtistCell(it) }
                    }
                }
            }
            if (r.albums.isNotEmpty()) {
                item(key = "catalog-albums-h", contentType = "subheader") { SubHeading("Albums", wide.padding(horizontal = edge).animateItem()) }
                item(key = "catalog-albums", contentType = "shelf") {
                    LazyRow(contentPadding = PaddingValues(horizontal = edge - 6.dp), modifier = wide.animateItem()) {
                        items(r.albums, key = { "al${it.id}" }) { CatalogAlbumCell(it) }
                    }
                }
            }
            if (r.songs.isNotEmpty()) item(key = "catalog-songs-h", contentType = "subheader") {
                SubHeading("Songs", wide.padding(horizontal = edge).animateItem())
            }
            items(r.songs, key = { "catalog-song-${it.id}" }, contentType = { "catalog-song" }) { song ->
                CatalogSongRow(song, wide.padding(horizontal = edge - 16.dp).animateItem())
            }
        }
    }
}

/** A heading within a group, matching the library's "Artists", "Albums" and "Songs". */
@Composable
fun SubHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** A section's title, in the style of the library's own headings. */
@Composable
fun GroupHeading(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
fun CatalogProblem(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** An artist's catalog, for their page. Null while it loads; empty when there's nothing. */
@Composable
fun rememberArtistCatalog(name: String, catalogId: Long? = null, retry: Int = 0): Remote<ArtistCatalog?> {
    val discovery = rememberDiscovery()
    val state by produceState<Remote<ArtistCatalog?>>(Remote.Loading, name, catalogId, retry) {
        value = runCatching { discovery.artist(name, catalogId?.toULong()) }.map { Remote.Loaded(it) }.getOrDefault(Remote.Failed)
    }
    return state
}

/**
 * On an artist's page: their albums with how much of each you have, and the songs of theirs
 * you don't. Only where your server or Lidarr can get them; otherwise there's nothing here.
 */
@Composable
fun MoreFromArtist(name: String, modifier: Modifier = Modifier, edge: Dp = 16.dp) {
    val discovery = rememberDiscovery()
    val sources by discovery.sources.collectAsState()
    if (!sources.canFetch) return
    var retry by remember { mutableIntStateOf(0) }
    val state = rememberArtistCatalog(name, retry = retry)
    val navigator = LocalNavigator.current
    AnimatedVisibility(
        visible = state !is Remote.Loaded || state.value != null,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = edge, end = edge - 8.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                GroupHeading("More by $name", Modifier.weight(1f))
                if (state is Remote.Loaded && state.value != null) {
                    TextButton(onClick = { navigator.open(CatalogArtistRoute(name, state.value.artist.id.toLong())) }) { Text("See all") }
                }
            }
            when (state) {
                Remote.Idle, Remote.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
                Remote.Failed -> CatalogProblem("Couldn't load more by $name", { retry++ }, Modifier.padding(horizontal = edge))
                is Remote.Loaded -> state.value?.let { page ->
                    val albums = page.albums.filter { it.kind == CatalogAlbumKind.ALBUM }.ifEmpty { page.albums }.take(12)
                    if (albums.isNotEmpty()) {
                        LazyRow(contentPadding = PaddingValues(horizontal = edge - 6.dp)) {
                            items(albums, key = { it.id.toLong() }) { CatalogAlbumCell(it, showArtist = false) }
                        }
                    }
                    val missing = page.songs.filter { discovery.owned(it) == null }.distinctBy { it.identity }.take(5)
                    if (missing.isNotEmpty()) {
                        Text(
                            "Songs not in your music",
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = edge, top = 16.dp, bottom = 4.dp),
                        )
                        missing.forEach { CatalogSongRow(it, Modifier.padding(horizontal = edge - 16.dp), showArtist = false) }
                    }
                }
            }
        }
    }
}

/**
 * On one of your albums: how many of its songs you don't have, opening the catalog's album
 * to get them. Only where your server or Lidarr can.
 */
@Composable
fun MissingFromAlbum(title: String, artist: String, owned: Int, modifier: Modifier = Modifier) {
    val discovery = rememberDiscovery()
    val sources by discovery.sources.collectAsState()
    val navigator = LocalNavigator.current
    if (!sources.canFetch) return
    val album by produceState<uniffi.tracks_engine.CatalogAlbum?>(null, title, artist) {
        value = runCatching { discovery.findAlbum(title, artist) }.getOrNull()
    }
    val missing = album?.trackCount?.toInt()?.minus(owned) ?: 0
    AnimatedVisibility(
        visible = missing > 0,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        modifier = modifier,
    ) {
        val found = album ?: return@AnimatedVisibility
        Surface(
            onClick = { navigator.open(com.luke.tracks.ui.navigation.CatalogAlbumRoute(found.id.toLong(), found.title, found.artistName)) },
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (missing == 1) "1 song missing" else "$missing songs missing",
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "You have $owned of ${found.trackCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** "A", "A and B", "A, B and C". */
fun listPhrase(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

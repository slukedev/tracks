package com.luke.tracks.ui.play

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.discover.SuggestedArtist
import com.luke.tracks.discover.Suggestions
import com.luke.tracks.discover.catalogArtist
import com.luke.tracks.ui.discover.CatalogAlbumCell
import com.luke.tracks.ui.discover.CatalogArtistMenu
import com.luke.tracks.ui.discover.CatalogSongRow
import com.luke.tracks.ui.discover.Remote
import com.luke.tracks.ui.discover.rememberDiscovery
import com.luke.tracks.ui.navigation.CatalogArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.play.components.pressable
import kotlinx.coroutines.launch

/** Suggestions on Play, with a way to stop suggesting an artist that takes effect at once. */
@Stable
class PlaySuggestions(val state: Remote<Suggestions>, val notInterested: (String) -> Unit) {
    val value: Suggestions? get() = (state as? Remote.Loaded)?.value
}

/**
 * New music for Play: songs, artists and new albums you don't have, from your listening.
 * Asked for only once there's history to suggest from, and again when your top artists
 * change; the first frame shows what was found last, so coming back to Play never waits.
 */
@Composable
fun rememberPlaySuggestions(enabled: Boolean, topArtists: Set<String>): PlaySuggestions {
    val discovery = rememberDiscovery()
    val scope = rememberCoroutineScope()
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val initial = discovery.lastSuggestions?.let { Remote.Loaded(it) } ?: if (enabled) Remote.Loading else Remote.Idle
    val state by produceState<Remote<Suggestions>>(initial, enabled, topArtists) {
        if (!enabled) { value = Remote.Idle; return@produceState }
        // What's on screen stays while it's worked out again.
        if (value !is Remote.Loaded) value = Remote.Loading
        value = Remote.Loaded(discovery.suggestions())
    }
    val shown = remember(state, hidden) {
        (state as? Remote.Loaded)?.let { loaded -> Remote.Loaded(hidden.fold(loaded.value) { s, name -> s.without(name) }) } ?: state
    }
    return PlaySuggestions(shown) { name ->
        hidden = hidden + name
        scope.launch { discovery.notInterested(name) }
    }
}

/** How many suggested songs the shelf holds; Keep exploring carries on after them. */
const val SHELF_SONGS = 16
private const val ROWS = 4
private val RowHeight = 68.dp

/** A page of the shelf: most of the width, so the next column peeks in. */
@Composable
private fun columnWidth() = (LocalConfiguration.current.screenWidthDp * 0.86f).dp

/**
 * Suggested songs, straight after the crate: songs you don't have in columns of four, each
 * with its preview, Get where your server or Lidarr can, and Open in another app.
 */
@Composable
fun SuggestedSongsShelf(suggestions: PlaySuggestions, modifier: Modifier = Modifier) {
    val state = suggestions.state
    val songs = suggestions.value?.songs.orEmpty().take(SHELF_SONGS)
    if (state !is Remote.Loading && songs.isEmpty()) return
    Column(modifier) {
        SectionTitle("Suggested songs")
        Spacer(Modifier.height(4.dp))
        if (state is Remote.Loading) {
            SongShelfLoading()
            return@Column
        }
        val grid = rememberLazyGridState()
        LazyHorizontalGrid(
            rows = GridCells.Fixed(ROWS),
            state = grid,
            flingBehavior = rememberSnapFlingBehavior(grid, SnapPosition.Start),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(RowHeight * ROWS),
        ) {
            items(songs, key = { it.id.toLong() }) { song ->
                CatalogSongRow(song, Modifier.width(columnWidth()).animateItem())
            }
        }
    }
}

/** The shelf's shape while suggestions are worked out: rows where they'll be, no fake content. */
@Composable
private fun SongShelfLoading() {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier.height(RowHeight * ROWS).padding(start = 24.dp).semantics { contentDescription = "Loading suggested songs" },
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        repeat(2) {
            Column(Modifier.width(columnWidth() - 24.dp)) {
                repeat(ROWS) {
                    Row(Modifier.height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = fill, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(52.dp)) {}
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(140.dp, 12.dp)) {}
                            Spacer(Modifier.height(8.dp))
                            Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(92.dp, 10.dp)) {}
                        }
                    }
                }
            }
        }
    }
}

/** Suggested artists: people you don't play, like ones you do, each in an expressive shape. */
@Composable
fun SuggestedArtistsShelf(suggestions: PlaySuggestions, modifier: Modifier = Modifier) {
    val artists = suggestions.value?.artists.orEmpty()
    if (artists.isEmpty()) return
    Column(modifier) {
        SectionTitle("Suggested artists")
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(artists.size, key = { artists[it].name }) { index ->
                SuggestedArtistTile(artists[index], index, onNotInterested = { suggestions.notInterested(artists[index].name) }, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun SuggestedArtistTile(artist: SuggestedArtist, index: Int, onNotInterested: () -> Unit, modifier: Modifier) {
    val navigator = LocalNavigator.current
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(104.dp)
            .pressable(onClick = { navigator.open(CatalogArtistRoute(artist.name)) }, onLongClick = { menu = true }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ShapedArtistCover(artist.name, artist.pictureUrl, expressiveShapes[(index + 3) % expressiveShapes.size].toShape())
            CatalogArtistMenu(catalogArtist(artist.name, null), expanded = menu, onDismiss = { menu = false }, onNotInterested = onNotInterested)
        }
        Spacer(Modifier.height(8.dp))
        Text(artist.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        artist.like.firstOrNull()?.let {
            Text(
                "Like $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** New from your artists: albums and singles of the last six months you don't have. */
@Composable
fun NewReleasesShelf(suggestions: PlaySuggestions, modifier: Modifier = Modifier) {
    val releases = suggestions.value?.releases.orEmpty()
    if (releases.isEmpty()) return
    Column(modifier) {
        SectionTitle("New from your artists")
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp)) {
            items(releases, key = { it.album.id.toLong() }) { CatalogAlbumCell(it.album, Modifier.animateItem()) }
        }
    }
}

/** Keep exploring, at the very end: the suggested songs past the shelf, as a list. */
@Composable
fun KeepExploring(suggestions: PlaySuggestions, modifier: Modifier = Modifier) {
    val songs = suggestions.value?.songs.orEmpty().drop(SHELF_SONGS)
    if (songs.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        SectionTitle("Keep exploring")
        Spacer(Modifier.height(4.dp))
        songs.forEach { CatalogSongRow(it, Modifier.padding(horizontal = 8.dp)) }
    }
}

package com.luke.tracks.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.applemusic.AppleMusicCollection
import com.luke.tracks.applemusic.AppleMusicShelves
import com.luke.tracks.applemusic.AppleMusicShelvesState
import com.luke.tracks.ui.applemusic.route
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.play.components.pressable

/**
 * Apple Music's shelves on Play: Recently played on Apple Music, then its recommendations.
 * Shown while Apple Music is signed in; loading, a problem or nothing found each show on the
 * shelf, with Try again, rather than leaving it empty.
 */
fun LazyListScope.appleMusicShelves(state: AppleMusicShelvesState, shelves: AppleMusicShelves) {
    when (state) {
        AppleMusicShelvesState.Off -> Unit
        AppleMusicShelvesState.Loading -> item(key = "applemusic-loading", contentType = "applemusic") {
            Column(Modifier.animateItem()) {
                SectionTitle("From Apple Music")
                Spacer(Modifier.height(8.dp))
                CollectionShelfLoading()
            }
        }
        is AppleMusicShelvesState.Problem -> item(key = "applemusic-problem", contentType = "applemusic") {
            Column(Modifier.animateItem()) {
                SectionTitle("From Apple Music")
                ShelfMessage(state.message, onRetry = shelves::retry)
            }
        }
        is AppleMusicShelvesState.Ready -> {
            if (state.isEmpty) {
                item(key = "applemusic-empty", contentType = "applemusic") {
                    Column(Modifier.animateItem()) {
                        SectionTitle("From Apple Music")
                        ShelfMessage(
                            state.note ?: "Apple Music has nothing to recommend yet. Its picks show up here as you listen in Apple Music.",
                            onRetry = shelves::retry,
                        )
                    }
                }
                return
            }
            if (state.recent.isNotEmpty()) item(key = "applemusic-recent", contentType = "applemusic") {
                CollectionShelf("Recently played on Apple Music", state.recent, Modifier.animateItem())
            }
            state.picks.forEach { row ->
                item(key = "applemusic-pick-${row.id}", contentType = "applemusic") {
                    CollectionShelf(row.title, row.items, Modifier.animateItem())
                }
            }
            state.note?.let { note ->
                item(key = "applemusic-note", contentType = "applemusic") {
                    Column(Modifier.animateItem()) { ShelfMessage(note, onRetry = shelves::retry) }
                }
            }
        }
    }
}

/** A row of Apple Music albums and playlists: tap opens one. */
@Composable
private fun CollectionShelf(title: String, items: List<AppleMusicCollection>, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    Column(modifier) {
        SectionTitle(title)
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(items, key = { it.kind.name + ":" + it.id }) { item ->
                Column(
                    Modifier
                        .animateItem()
                        .width(148.dp)
                        .pressable(onClick = { navigator.open(item.route()) }),
                ) {
                    Artwork(item.artworkUrl(), item.title + item.subtitle.orEmpty(), Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(item.title, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Covers where they'll be while Apple Music answers, no fake content. */
@Composable
private fun CollectionShelfLoading() {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier.padding(start = 24.dp).semantics { contentDescription = "Loading" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) {
            Column(Modifier.width(148.dp)) {
                Surface(color = fill, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(148.dp)) {}
                Spacer(Modifier.height(10.dp))
                Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(110.dp, 12.dp)) {}
                Spacer(Modifier.height(8.dp))
                Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(84.dp, 10.dp)) {}
            }
        }
    }
}

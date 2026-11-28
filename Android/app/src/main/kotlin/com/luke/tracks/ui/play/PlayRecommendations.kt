package com.luke.tracks.ui.play

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.library.trackside.TracksideCatalogEntry
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.streaming.RecommendationShelf
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.play.components.pressable
import com.luke.tracks.ui.services.ServiceTrackMenu
import com.luke.tracks.ui.services.byline
import com.luke.tracks.ui.services.cover
import com.luke.tracks.ui.services.name
import com.luke.tracks.ui.services.playPick
import com.luke.tracks.ui.services.playableFor
import com.luke.tracks.ui.services.route

private val CardWidth = 148.dp
private val CardShape = RoundedCornerShape(20.dp)

/**
 * One of a service's rows of picks, in the album shelf's cards: a song or station plays at
 * once, the rest open their page. [showsService] names the service when there's more than one.
 */
@Composable
fun RecommendationShelfRow(row: RecommendationShelf, showsService: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle(row.shelf.title)
        if (showsService) {
            Text(
                row.service.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(row.shelf.items, key = { it.ref }) { entry ->
                PickCard(row, entry, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun PickCard(row: RecommendationShelf, entry: TracksideCatalogEntry, modifier: Modifier) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val item = (entry as? TracksideCatalogEntry.Item)?.item
    val open: () -> Unit = { item?.let { navigator.open(it.route(row.serverId)) } }
    Column(
        modifier
            .width(CardWidth)
            .pressable(
                onClick = {
                    if (context.playPick(row, entry)) haptics.performHapticFeedback(HapticFeedbackType.Confirm) else open()
                },
                onLongClick = {
                    if (entry is TracksideCatalogEntry.Track) {
                        menu = true
                    } else {
                        open()
                    }
                },
            ),
    ) {
        val name = entry.name()
        Box {
            Artwork(
                cover(row.serverId, entry.artworkId),
                name + entry.byline(),
                Modifier.fillMaxWidth(),
                shape = if (item?.kindValue == TracksideCatalogItem.Kind.Artist) CircleShape else CardShape,
            )
            if (entry is TracksideCatalogEntry.Track) {
                val song = remember(row.serverId, entry) { context.playableFor(row.serverId, entry.track) }
                ServiceTrackMenu(row.serverId, entry.track, song, expanded = menu, onDismiss = { menu = false })
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(entry.byline(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row of picks on its way: the shelf's shape, no fake covers, so nothing moves when it arrives. */
@Composable
fun RecommendationShelfLoading(modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.semantics { contentDescription = "Loading" }) {
        // titleLargeEmphasized's line, as SectionTitle sets it.
        Box(Modifier.padding(start = 24.dp).height(28.dp).padding(bottom = 4.dp), contentAlignment = Alignment.CenterStart) {
            Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(160.dp, 18.dp)) {}
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState(), enabled = false).padding(start = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(4) { card ->
                Column(Modifier.width(CardWidth)) {
                    Surface(color = fill, shape = CardShape, modifier = Modifier.size(CardWidth)) {}
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                        Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(listOf(112.dp, 92.dp, 124.dp, 100.dp)[card], 12.dp)) {}
                    }
                    Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                        Surface(color = fill, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(72.dp, 10.dp)) {}
                    }
                }
            }
        }
    }
}

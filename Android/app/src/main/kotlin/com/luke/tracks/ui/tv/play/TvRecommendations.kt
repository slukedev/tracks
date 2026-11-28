package com.luke.tracks.ui.tv.play

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.luke.tracks.library.trackside.TracksideCatalogEntry
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.streaming.RecommendationShelf
import com.luke.tracks.streaming.ServerRecommendations
import com.luke.tracks.ui.services.byline
import com.luke.tracks.ui.services.cover
import com.luke.tracks.ui.services.name
import com.luke.tracks.ui.services.playPick
import com.luke.tracks.ui.services.route
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.TvShelfSkeleton
import com.luke.tracks.ui.tv.detail.PendingPlay
import com.luke.tracks.ui.tv.detail.TvRowScroll
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator

/** A server's rows of its services' picks, or one shelf's place while they come. */
internal fun LazyListScope.tvRecommendationItems(
    serverId: String,
    recs: ServerRecommendations?,
    namesServices: Boolean,
    memory: FocusMemory,
    pending: PendingPlay,
) {
    when (recs) {
        null -> Unit
        ServerRecommendations.Loading -> item(key = "recs-loading-$serverId", contentType = "shelf-loading") { TvShelfSkeleton(cards = 6) }
        is ServerRecommendations.Ready -> recs.shelves.forEach { row ->
            item(key = "recs-${row.key}", contentType = "shelf") { PicksShelf(row, namesServices, memory, pending) }
        }
    }
}

/** Songs and stations play from the card, with its ring until they do; the rest open their page. */
@Composable
private fun PicksShelf(row: RecommendationShelf, namesServices: Boolean, memory: FocusMemory, pending: PendingPlay) {
    val context = LocalContext.current
    val navigator = LocalTvNavigator.current
    val title = if (namesServices) "${row.shelf.title} · ${row.service.name}" else row.shelf.title
    TvRowScroll {
        TvShelf(title, row.shelf.items, key = { "recs-${row.key}:${it.ref}" }, focusMemory = memory) { entry ->
            val key = "recs-${row.key}:${entry.ref}"
            val item = (entry as? TracksideCatalogEntry.Item)?.item
            val artist = item?.kindValue == TracksideCatalogItem.Kind.Artist
            TvCoverCard(
                cover(row.serverId, entry.artworkId),
                entry.name() + entry.byline(),
                entry.name(),
                onClick = {
                    if (item != null && item.kindValue != TracksideCatalogItem.Kind.Radio) {
                        navigator.open(item.route(row.serverId))
                    } else {
                        pending.run(key) { context.playPick(row, entry) }
                    }
                },
                subtitle = entry.byline().takeUnless { artist },
                circle = artist,
                pending = pending.isPending(key),
            )
        }
    }
}

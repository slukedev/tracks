package com.luke.tracks.ui.tv.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.graph
import com.luke.tracks.ui.history.HistoryItem
import com.luke.tracks.ui.history.withDays
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSkeletonBlock
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.detail.RowArt
import com.luke.tracks.ui.tv.detail.RowArtPx
import com.luke.tracks.ui.tv.detail.TvPulse
import com.luke.tracks.ui.tv.detail.TvRowsSkeleton
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout
import kotlinx.coroutines.flow.Flow
import uniffi.tracks_engine.songIdentity

/** Wide enough for a long title and the time, narrow enough to read across from the sofa. */
private val ListWidth = 720.dp

/** History on TV: every play, newest first, under a heading for each day, a page at a time. */
@Composable
fun TvHistoryScreen() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val count by graph.history.count.collectAsStateWithLifecycle(initialValue = -1)
    val pages = remember(graph) { withDays(graph.history.paged()).cachedIn(scope) }
    TvHistoryContent(count, pages)
}

/** [count] is -1 until the history has been counted. */
@Composable
fun TvHistoryContent(count: Int, pages: Flow<PagingData<HistoryItem>>) {
    val memory = rememberFocusMemory()
    val items = pages.collectAsLazyPagingItems()
    when {
        count == 0 -> {
            val radio = remember { FocusRequester() }
            val player = LocalContext.current.graph.player
            memory.ClaimFocus(radio)
            TvEmptyState("No Plays Yet", icon = Icons.Rounded.History, actionLabel = "Start Tracks Radio", onAction = { player.startRadio() }, focusRequester = radio)
        }
        count < 0 || items.itemCount == 0 -> {
            memory.ClaimFocus(null)
            HistorySkeleton()
        }
        else -> Plays(items, memory)
    }
}

@Composable
private fun Plays(items: LazyPagingItems<HistoryItem>, memory: FocusMemory) {
    val navigator = LocalTvNavigator.current
    val firstPlay = (0 until minOf(items.itemCount, 3)).firstNotNullOfOrNull { (items.peek(it) as? HistoryItem.Played)?.play?.id }
    memory.ClaimFocus(firstPlay?.let(memory::requester))
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = TvLayout.ContentStart - 16.dp, end = TvLayout.SafeEnd, top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (index in 0 until items.itemCount) {
                when (val peek = items.peek(index)) {
                    is HistoryItem.Day -> item(key = "day-${peek.date}", contentType = "day") { DayHeading(peek, first = index == 0) }
                    is HistoryItem.Played -> item(key = peek.play.id, contentType = "play") {
                        // Reading the item, not peeking, is what asks for the next page.
                        val play = (items[index] as? HistoryItem.Played)?.play ?: peek.play
                        PlayRow(play, Modifier.widthIn(max = ListWidth).rememberFocus(memory, play.id)) {
                            navigator.open(SongRoute(songIdentity(play.title, play.artistName), play.title, play.artistName))
                        }
                    }
                    null -> item(contentType = "placeholder") { Spacer(Modifier.height(64.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DayHeading(day: HistoryItem.Day, first: Boolean) {
    Text(
        Format.dayHeader(day.date),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = if (first) 4.dp else 24.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun PlayRow(play: PlayEntity, modifier: Modifier, onClick: () -> Unit) {
    TvListRow(
        onClick = onClick,
        headline = play.title.ifBlank { "Untitled" },
        supporting = play.artistName,
        dense = true,
        modifier = modifier,
        leading = { TvArtwork(play.artworkUri, (play.albumTitle ?: play.title) + play.artistName, Modifier.size(RowArt), sizePx = RowArtPx, showInitials = false) },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.time(play.playedAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                kindLabel(play.kind)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
            }
        },
    )
}

/** Plays that came from elsewhere say so; ones played here, on demand or on the radio, don't. */
private fun kindLabel(kind: PlayKindValue): String? = when (kind) {
    PlayKindValue.Imported, PlayKindValue.LastFm -> "Imported"
    PlayKindValue.Radio, PlayKindValue.OnDemand -> null
}

@Composable
private fun HistorySkeleton() {
    TvPulse(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = TvLayout.ContentStart - 16.dp, top = TvLayout.SafeTop).widthIn(max = ListWidth).fillMaxWidth()) {
            TvSkeletonBlock(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp).size(110.dp, 20.dp), RoundedCornerShape(6.dp))
            TvRowsSkeleton(rows = 7)
        }
    }
}

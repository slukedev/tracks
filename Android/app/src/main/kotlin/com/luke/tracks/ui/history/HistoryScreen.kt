package com.luke.tracks.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.insertSeparators
import androidx.paging.map
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.toZoned
import com.luke.tracks.ui.insights.EmptyState
import com.luke.tracks.ui.insights.ExtraAction
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.GroupGap
import com.luke.tracks.ui.insights.GroupInner
import com.luke.tracks.ui.insights.GroupOuter
import com.luke.tracks.ui.insights.InsightRow
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.insights.rememberGraph
import com.luke.tracks.ui.insights.segmentedShape
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.navigation.SettingsPageRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.tracks_engine.songIdentity
import java.time.LocalDate

private val BottomRoom: androidx.compose.ui.unit.Dp
    @Composable get() = com.luke.tracks.ui.navigation.bottomClearance

/** The filters above the history. */
enum class HistoryFilter(val label: String, val kind: PlayKindValue?) {
    All("All", null),
    Tracks("Tracks", PlayKindValue.OnDemand),
    LastFm("Last.fm", PlayKindValue.LastFm),
}

/** A row of the history list: a day's heading or a play. */
sealed interface HistoryItem {
    data class Day(val date: LocalDate) : HistoryItem
    data class Played(val play: PlayEntity, val date: LocalDate) : HistoryItem
}

/** Plays newest first, with a heading at the start of each day. */
fun withDays(pages: Flow<PagingData<PlayEntity>>): Flow<PagingData<HistoryItem>> = pages.map { data ->
    data.map { HistoryItem.Played(it, it.playedAt.toZoned().toLocalDate()) as HistoryItem }
        .insertSeparators { before, after ->
            val a = after as? HistoryItem.Played ?: return@insertSeparators null
            val b = before as? HistoryItem.Played
            if (b == null || b.date != a.date) HistoryItem.Day(a.date) else null
        }
}

/**
 * History: every play, newest first, under sticky day headings. Swipe a play away or press
 * and hold to delete it, with Undo.
 */
@Composable
fun HistoryScreen(modeSwitch: @Composable () -> Unit = {}) {
    val graph = rememberGraph()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.All) }
    val count by graph.history.count.collectAsStateWithLifecycle(initialValue = -1)
    val pages = remember(filter) { withDays(graph.history.paged(filter.kind)).cachedIn(scope) }
    val items = pages.collectAsLazyPagingItems()
    val list = rememberLazyListState()

    fun delete(play: PlayEntity) {
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        scope.launch {
            graph.history.remove(listOf(play.id))
            val result = snackbar.showSnackbar(
                message = "Removed ${play.title}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                graph.history.keepAll(listOf(play))
            }
        }
    }

    CompositionLocalProvider(LocalInsightsSnackbar provides snackbar) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = BottomRoom)) },
            topBar = {
                HistoryBar(modeSwitch, scroll)
            },
        ) { inner ->
            if (count == 0) {
                Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Rounded.History,
                        title = "No plays yet",
                        action = "Import from Last.fm",
                        onAction = { navigator.open(SettingsPageRoute("lastfm")) },
                    )
                }
                return@Scaffold
            }
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(top = inner.calculateTopPadding(), bottom = BottomRoom),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "filters", contentType = "filters") {
                    Filters(filter, onSelect = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        filter = it
                    })
                }
                if (items.itemCount == 0 && count > 0) {
                    item(key = "none") {
                        Text(
                            "No ${filter.label.lowercase().replace("last.fm", "Last.fm")} plays yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                        )
                    }
                }
                for (index in 0 until items.itemCount) {
                    when (val peek = items.peek(index)) {
                        is HistoryItem.Day -> stickyHeader(key = "day-${peek.date}", contentType = "day") {
                            DayHeader(peek.date)
                        }
                        is HistoryItem.Played -> {
                            val first = index == 0 || items.peekOrNull(index - 1) is HistoryItem.Day
                            val last = index == items.itemCount - 1 || items.peekOrNull(index + 1) is HistoryItem.Day
                            item(key = peek.play.id, contentType = "play") {
                                val item = items[index] as? HistoryItem.Played ?: peek
                                PlayRow(
                                    item.play,
                                    first = first,
                                    last = last,
                                    onDelete = { delete(item.play) },
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 16.dp)
                                        .padding(top = if (first) 0.dp else GroupGap),
                                )
                            }
                        }
                        null -> item(contentType = "placeholder") { Spacer(Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }
}

private fun <T : Any> androidx.paging.compose.LazyPagingItems<T>.peekOrNull(index: Int): T? =
    if (index in 0 until itemCount) peek(index) else null

@Composable
private fun Filters(selected: HistoryFilter, onSelect: (HistoryFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryFilter.entries.forEach { option ->
            val on = option == selected
            FilterChip(
                selected = on,
                onClick = { if (!on) onSelect(option) },
                label = { Text(option.label) },
                leadingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Text(
            Format.dayHeader(date),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun PlayRow(play: PlayEntity, first: Boolean, last: Boolean, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState()
    val index = if (first) 0 else 1
    val count = when {
        first && last -> 1
        last -> 2
        else -> 3
    }
    val shape = segmentedShape(index, count, GroupOuter, GroupInner)
    val target = remember(play.id) {
        ItemTarget.Song(songIdentity(play.title, play.artistName), play.title, play.artistName, play.albumTitle, play.artworkUri)
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            val active = state.targetValue == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer,
                        shape,
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Rounded.Delete, "Delete",
                    tint = if (active) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        InsightRow(
            target = target,
            index = index,
            count = count,
            shared = false,
            extra = listOf(ExtraAction("Delete from history", Icons.Rounded.Delete, destructive = true, onClick = onDelete)),
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Format.time(play.playedAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                KindBadge(play)
            }
        }
    }
    // A tick as the swipe passes the point where letting go deletes.
    val armed = state.targetValue == SwipeToDismissBoxValue.EndToStart
    androidx.compose.runtime.LaunchedEffect(armed) {
        if (armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
}

/** A small tag on plays that came from elsewhere: Imported or Last.fm. Plays from Tracks have none. */
@Composable
fun KindBadge(play: PlayEntity) {
    val scheme = MaterialTheme.colorScheme
    val (label, icon: ImageVector, colors) = when (play.kind) {
        PlayKindValue.Imported -> Triple("Imported", Icons.Rounded.FileDownload, scheme.surfaceContainerHighest to scheme.onSurfaceVariant)
        PlayKindValue.LastFm -> Triple("Last.fm", Icons.Rounded.Cloud, scheme.errorContainer to scheme.onErrorContainer)
        PlayKindValue.Radio, PlayKindValue.OnDemand -> return
    }
    Row(
        Modifier.background(colors.first, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.second, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.second, maxLines = 1)
    }
}

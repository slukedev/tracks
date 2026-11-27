package com.luke.tracks.ui.tv.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.PeriodKind
import com.luke.tracks.graph
import com.luke.tracks.ui.insights.AxisKind
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.InsightBarChart
import com.luke.tracks.ui.insights.ListeningClock
import com.luke.tracks.ui.insights.WeekHeatMap
import com.luke.tracks.ui.insights.engineState
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.summary.Highlight
import com.luke.tracks.ui.summary.HighlightSymbol
import com.luke.tracks.ui.summary.SummaryData
import com.luke.tracks.ui.summary.loadSummary
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvCard
import com.luke.tracks.ui.tv.components.TvChip
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvIconButton
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.TvShelfSkeleton
import com.luke.tracks.ui.tv.components.TvSkeletonBlock
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.detail.TvNote
import com.luke.tracks.ui.tv.detail.TvPulse
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout
import androidx.compose.material3.MaterialTheme as M3Theme

/** "Week", "Month", "Year", "All Time". */
fun tvPeriodName(kind: PeriodKind): String = when (kind) {
    PeriodKind.Week -> "Week"
    PeriodKind.Month -> "Month"
    PeriodKind.Year -> "Year"
    PeriodKind.AllTime -> "All Time"
}

/**
 * The period picker every insights page shares: Week, Month, Year and All Time, then Earlier
 * and Later to step through them. Up from the page comes back to the chosen period.
 */
@Composable
fun TvPeriodRow(
    kind: PeriodKind,
    onKind: (PeriodKind) -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    label: String?,
    memory: FocusMemory,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd).focusRestorer(memory.requester("period:${kind.name}")),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeriodKind.entries.forEach { option ->
            TvChip(tvPeriodName(option), selected = option == kind, onClick = { onKind(option) }, modifier = Modifier.rememberFocus(memory, "period:${option.name}"))
        }
        if (kind != PeriodKind.AllTime) {
            Spacer(Modifier.width(12.dp))
            TvIconButton(Icons.Rounded.ChevronLeft, "Earlier", onBack, Modifier.rememberFocus(memory, "earlier"), enabled = canGoBack)
            TvIconButton(Icons.Rounded.ChevronRight, "Later", onForward, Modifier.rememberFocus(memory, "later"), enabled = canGoForward)
        }
        if (label != null) {
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Summary on TV: how much you listened in a period, against last time, a bar for each day,
 * then what it was: top songs, artists and albums, highlights and when you listen.
 */
@Composable
fun TvSummaryScreen() {
    var kind by rememberSaveable { mutableStateOf(PeriodKind.Month) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val data by engineState(kind, offset) { engine -> loadSummary(engine, kind, offset) }
    TvSummaryContent(data, kind, onKind = { kind = it; offset = 0 }, onBack = { offset++ }, onForward = { if (offset > 0) offset-- })
}

@Composable
fun TvSummaryContent(data: SummaryData?, kind: PeriodKind, onKind: (PeriodKind) -> Unit, onBack: () -> Unit, onForward: () -> Unit) {
    val memory = rememberFocusMemory()
    val navigator = LocalTvNavigator.current
    when {
        data == null -> {
            memory.ClaimFocus(null)
            SummarySkeleton()
            return
        }
        data.historyEmpty -> {
            val radio = remember { FocusRequester() }
            val player = LocalContext.current.graph.player
            memory.ClaimFocus(radio)
            TvEmptyState("No Listening Yet", icon = Icons.Rounded.Headphones, actionLabel = "Start Tracks Radio", onAction = { player.startRadio() }, focusRequester = radio)
            return
        }
    }
    val d = data!!
    memory.ClaimFocus(memory.requester("period:${kind.name}"))
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().focusRestorer(),
        contentPadding = PaddingValues(top = TvLayout.SafeTop, bottom = TvLayout.SafeBottom + 16.dp),
        verticalArrangement = Arrangement.spacedBy(TvLayout.SectionSpacing - 16.dp),
    ) {
        item(key = "period") {
            TvPeriodRow(
                kind, onKind, canGoBack = d.canGoBack, canGoForward = d.offset > 0, onBack = onBack, onForward = onForward,
                label = Format.periodLabel(d.period).takeIf { kind != PeriodKind.AllTime }, memory = memory,
            )
        }
        item(key = "hero") { Hero(d) }
        if (d.totals.plays == 0uL) {
            item(key = "quiet") { TvNote(if (d.isCurrent) "Nothing played yet." else "Nothing played then.", Modifier.padding(start = TvLayout.ContentStart)) }
            return@LazyColumn
        }
        val songs = d.songs.rows
        if (songs.isNotEmpty()) item(key = "songs") {
            TvShelf("Top Songs", songs, key = { "song:${it.song.identity}" }, focusMemory = memory) { row ->
                val s = row.song
                TvCoverCard(s.artworkUrl, (s.albumTitle ?: s.title) + s.artistName, s.title, onClick = { navigator.open(SongRoute(s.identity, s.title, s.artistName)) }, subtitle = s.artistName)
            }
        }
        if (d.artists.rows.isNotEmpty()) item(key = "artists") {
            TvShelf("Top Artists", d.artists.rows, key = { "artist:${it.artist.identity}" }, focusMemory = memory) { row ->
                val a = row.artist
                TvCoverCard(a.artworkUrl, a.name, a.name, onClick = { navigator.open(ArtistRoute(a.identity, a.name)) }, subtitle = Format.plays(a.plays.toLong()), width = 156.dp, circle = true)
            }
        }
        if (d.albums.rows.isNotEmpty()) item(key = "albums") {
            TvShelf("Top Albums", d.albums.rows, key = { "album:${it.album.identity}" }, focusMemory = memory) { row ->
                val a = row.album
                TvCoverCard(a.artworkUrl, a.title + a.artistName, a.title, onClick = { navigator.open(AlbumRoute(a.identity, a.title, a.artistName)) }, subtitle = a.artistName)
            }
        }
        if (d.highlights.isNotEmpty()) item(key = "highlights") {
            TvShelf("Highlights", d.highlights.take(8), key = { "highlight:${it.id}" }, focusMemory = memory) { highlight -> HighlightCard(highlight) }
        }
        item(key = "rhythm") { Rhythm(d, memory) }
    }
}

@Composable
private fun Hero(d: SummaryData) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.width(300.dp)) {
            val big = MaterialTheme.typography.displayLarge
            Text(
                Format.heroDuration(
                    d.totals.listenMs,
                    SpanStyle(fontSize = big.fontSize, fontWeight = FontWeight.SemiBold, color = scheme.onSurface),
                    SpanStyle(fontSize = MaterialTheme.typography.headlineSmall.fontSize, color = scheme.onSurfaceVariant),
                ),
                style = big,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = Format.spokenDuration(d.totals.listenMs) + " of listening" },
            )
            d.trendPercent?.let { percent ->
                val up = percent >= 0
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        if (up) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown, null,
                        tint = if (up) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (up) "+$percent%" else "$percent%") + " from " + Format.previousPhrase(d.period.kind, d.isCurrent),
                        style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Figure(d.averageLabel, Format.compactDuration(d.averageMs))
                Figure("Plays", Format.number(d.totals.plays))
                Figure("Streak", Format.days(d.streak.current.toLong()))
            }
        }
        Spacer(Modifier.width(36.dp))
        if (d.bars.isNotEmpty()) {
            InsightBarChart(
                points = d.bars,
                axis = AxisKind.Minutes,
                average = d.averageMinutes,
                height = 176.dp,
                summary = "Listening over ${Format.periodLabel(d.period)}. ${d.averageLabel} ${Format.spokenDuration(d.averageMs)}.",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun HighlightCard(highlight: Highlight) {
    val scheme = M3Theme.colorScheme
    TvCard(onClick = {}, title = highlight.category.title, width = 300.dp, aspectRatio = 2f) {
        Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(scheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(symbolIcon(highlight.symbol), null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(highlight.text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun symbolIcon(symbol: HighlightSymbol): ImageVector = when (symbol) {
    HighlightSymbol.Trophy -> Icons.Rounded.EmojiEvents
    HighlightSymbol.Flame -> Icons.Rounded.LocalFireDepartment
    HighlightSymbol.Repeat -> Icons.Rounded.Repeat
    HighlightSymbol.Star -> Icons.Rounded.Star
    HighlightSymbol.Mic -> Icons.Rounded.Mic
    HighlightSymbol.Sparkles -> Icons.Rounded.AutoAwesome
    HighlightSymbol.Moon -> Icons.Rounded.NightsStay
    HighlightSymbol.Sunrise -> Icons.Rounded.WbTwilight
    HighlightSymbol.Clock -> Icons.Rounded.Schedule
    HighlightSymbol.Sun -> Icons.Rounded.WbSunny
    HighlightSymbol.Calendar -> Icons.Rounded.CalendarMonth
    HighlightSymbol.Replay -> Icons.Rounded.Replay
}

/** When you listen: the week by the hour and the clock, each a card you can rest on. */
@Composable
private fun Rhythm(d: SummaryData, memory: FocusMemory) {
    val hours = d.activity.hours.map { it.toLong() }
    val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
    Column {
        com.luke.tracks.ui.tv.components.TvSectionTitle("When You Listen", Modifier.padding(start = TvLayout.ContentStart))
        Row(
            Modifier.padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = 12.dp, bottom = 16.dp).focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(TvLayout.ShelfSpacing),
        ) {
            TvCard(onClick = {}, title = "Your Week by the Hour", width = 520.dp, aspectRatio = 2.3f, modifier = Modifier.rememberFocus(memory, "rhythm:week")) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) { WeekHeatMap(d.activity.heat.map { it.toLong() }) }
            }
            TvCard(onClick = {}, title = "Most Around ${Format.hour(peak)}", width = 226.dp, modifier = Modifier.rememberFocus(memory, "rhythm:clock")) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { ListeningClock(hours) }
            }
        }
    }
}

/** The period row, the big number beside its bars, and a shelf, while the first read runs. */
@Composable
private fun SummarySkeleton() {
    Column(Modifier.fillMaxSize().padding(top = TvLayout.SafeTop), verticalArrangement = Arrangement.spacedBy(TvLayout.SectionSpacing - 16.dp)) {
        TvPulse {
            Column(Modifier.padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(80.dp, 88.dp, 72.dp, 100.dp).forEach { TvSkeletonBlock(Modifier.width(it).height(40.dp), CircleShape) }
                }
                Row(Modifier.padding(top = 32.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.width(300.dp)) {
                        TvSkeletonBlock(Modifier.width(240.dp).height(52.dp), RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(16.dp))
                        TvSkeletonBlock(Modifier.width(200.dp).height(14.dp), RoundedCornerShape(6.dp))
                        Spacer(Modifier.height(28.dp))
                        TvSkeletonBlock(Modifier.width(260.dp).height(36.dp), RoundedCornerShape(8.dp))
                    }
                    Spacer(Modifier.width(36.dp))
                    TvSkeletonBlock(Modifier.weight(1f).height(176.dp), RoundedCornerShape(12.dp))
                }
            }
        }
        TvShelfSkeleton(cards = 5)
    }
}

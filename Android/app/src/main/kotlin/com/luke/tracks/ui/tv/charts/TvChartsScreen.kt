package com.luke.tracks.ui.tv.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.BarChart
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.PeriodKind
import com.luke.tracks.graph
import com.luke.tracks.ui.charts.ChartEntry
import com.luke.tracks.ui.charts.ChartKind
import com.luke.tracks.ui.charts.ChartsData
import com.luke.tracks.ui.charts.SourceChoice
import com.luke.tracks.ui.charts.loadCharts
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.engineState
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSectionTitle
import com.luke.tracks.ui.tv.components.TvSkeletonBlock
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.detail.TvNote
import com.luke.tracks.ui.tv.detail.TvPulse
import com.luke.tracks.ui.tv.detail.TvRowsSkeleton
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.summary.TvPeriodRow
import com.luke.tracks.ui.tv.theme.TvLayout

private const val Ranks = 25
private val ColumnGap = 24.dp

/** Charts on TV: your top songs, artists and albums for a period, side by side, with how each moved. */
@Composable
fun TvChartsScreen() {
    var period by rememberSaveable { mutableStateOf(PeriodKind.Week) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val songs by engineState(period, offset, ChartKind.Songs) { loadCharts(it, ChartKind.Songs, period, offset, SourceChoice.All) }
    val artists by engineState(period, offset, ChartKind.Artists) { loadCharts(it, ChartKind.Artists, period, offset, SourceChoice.All) }
    val albums by engineState(period, offset, ChartKind.Albums) { loadCharts(it, ChartKind.Albums, period, offset, SourceChoice.All) }
    TvChartsContent(
        charts = listOfNotNull(songs, artists, albums).takeIf { it.size == 3 },
        period = period,
        onPeriod = { period = it; offset = 0 },
        onBack = { offset++ },
        onForward = { if (offset > 0) offset-- },
    )
}

/** [charts] is Songs, Artists and Albums for one period, or null while they're first read. */
@Composable
fun TvChartsContent(charts: List<ChartsData>?, period: PeriodKind, onPeriod: (PeriodKind) -> Unit, onBack: () -> Unit, onForward: () -> Unit) {
    val memory = rememberFocusMemory()
    if (charts == null) {
        memory.ClaimFocus(null)
        ChartsSkeleton()
        return
    }
    val first = charts.first()
    if (first.historyEmpty) {
        val radio = remember { FocusRequester() }
        val player = LocalContext.current.graph.player
        memory.ClaimFocus(radio)
        TvEmptyState("No Listening Yet", icon = Icons.Rounded.BarChart, actionLabel = "Start Tracks Radio", onAction = { player.startRadio() }, focusRequester = radio)
        return
    }
    memory.ClaimFocus(memory.requester(charts.firstNotNullOfOrNull { c -> c.entries.firstOrNull()?.let { "${c.kindName()}:${it.target.key}" } } ?: "period:${period.name}"))
    Column(Modifier.fillMaxSize().padding(top = TvLayout.SafeTop)) {
        TvPeriodRow(
            period, onPeriod, canGoBack = first.canGoBack, canGoForward = first.offset > 0, onBack = onBack, onForward = onForward,
            label = Format.periodLabel(first.period).takeIf { period != PeriodKind.AllTime }, memory = memory,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (charts.all { it.entries.isEmpty() }) {
            TvNote(if (first.offset == 0) "Nothing played yet." else "Nothing played then.", Modifier.padding(start = TvLayout.ContentStart, top = 24.dp))
            return@Column
        }
        Row(Modifier.fillMaxSize().padding(start = TvLayout.ContentStart - 16.dp, end = TvLayout.SafeEnd - 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(ColumnGap - 16.dp)) {
            listOf("Songs", "Artists", "Albums").zip(charts).forEach { (title, chart) ->
                ChartColumn(title, chart, memory, Modifier.weight(1f))
            }
        }
    }
}

private fun ChartsData.kindName(): String = when (entries.firstOrNull()?.target) {
    is ItemTarget.Artist -> "artists"
    is ItemTarget.Album -> "albums"
    else -> "songs"
}

private val ItemTarget.key: String
    get() = when (this) {
        is ItemTarget.Song -> identity
        is ItemTarget.Artist -> identity
        is ItemTarget.Album -> identity
    }

@Composable
private fun ChartColumn(title: String, chart: ChartsData, memory: FocusMemory, modifier: Modifier) {
    val navigator = LocalTvNavigator.current
    val prefix = title.lowercase()
    LazyColumn(
        modifier.fillMaxHeight().focusRestorer(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = TvLayout.SafeBottom + 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "title") { TvSectionTitle(title, Modifier.padding(start = 4.dp, bottom = 8.dp)) }
        if (chart.entries.isEmpty()) item(key = "none") { TvNote("Nothing played.", Modifier.padding(start = 4.dp)) }
        items(chart.entries.take(Ranks), key = { "$prefix:${it.target.key}" }) { entry ->
            TvListRow(
                onClick = { navigator.open(entry.target.route) },
                headline = entry.target.title,
                supporting = entry.subtitle,
                dense = true,
                modifier = Modifier.rememberFocus(memory, "$prefix:${entry.target.key}"),
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            entry.rank.toString(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(24.dp),
                        )
                        TvArtwork(
                            entry.target.artworkUrl, entry.target.title, Modifier.size(40.dp), sizePx = 80,
                            shape = if (entry.target is ItemTarget.Artist) CircleShape else RoundedCornerShape(8.dp), showInitials = false,
                        )
                    }
                },
                trailing = { Movement(entry, chart.hasPrevious) },
            )
        }
    }
}

/** Up, down, or new since the period before; nothing when it held its place. */
@Composable
private fun Movement(entry: ChartEntry, hasPrevious: Boolean) {
    if (!hasPrevious) return
    val previous = entry.previousRank
    val scheme = MaterialTheme.colorScheme
    when {
        previous == null -> Text("New", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
        previous > entry.rank -> Icon(Icons.Rounded.ArrowDropUp, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(24.dp).semantics { contentDescription = "Up from ${previous}" })
        previous < entry.rank -> Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp).semantics { contentDescription = "Down from ${previous}" })
    }
}

@Composable
private fun ChartsSkeleton() {
    TvPulse(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = TvLayout.SafeTop + 4.dp)) {
            Row(Modifier.padding(start = TvLayout.ContentStart), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(80.dp, 88.dp, 72.dp, 100.dp).forEach { TvSkeletonBlock(Modifier.width(it).height(40.dp), CircleShape) }
            }
            Row(Modifier.padding(start = TvLayout.ContentStart - 16.dp, end = TvLayout.SafeEnd - 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(ColumnGap - 16.dp)) {
                repeat(3) {
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        TvSkeletonBlock(Modifier.padding(start = 4.dp).width(90.dp).height(20.dp), RoundedCornerShape(6.dp))
                        Spacer(Modifier.height(16.dp))
                        TvRowsSkeleton(rows = 6)
                    }
                }
            }
        }
    }
}

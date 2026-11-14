package com.luke.tracks.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luke.tracks.data.toZoned
import com.luke.tracks.engine.Engine
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.insights.ArtistAvatar
import com.luke.tracks.ui.insights.ArtistCell
import com.luke.tracks.ui.insights.AxisKind
import com.luke.tracks.ui.insights.BarPoint
import com.luke.tracks.ui.insights.CardLabel
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.GroupGap
import com.luke.tracks.ui.insights.InsightBarChart
import com.luke.tracks.ui.insights.InsightCard
import com.luke.tracks.ui.insights.InsightsLoading
import com.luke.tracks.ui.insights.ItemMenu
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.ListeningClock
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.insights.SectionHeader
import com.luke.tracks.ui.insights.bleed
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.sharedElement
import uniffi.tracks_engine.Scope
import uniffi.tracks_engine.SourceFilter
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

internal val BottomRoom: androidx.compose.ui.unit.Dp
    @androidx.compose.runtime.Composable get() = com.luke.tracks.ui.navigation.bottomClearance

/**
 * A detail page's frame: a quiet bar with Back and the item's menu, whose title fades in once
 * the hero header scrolls away.
 */
@Composable
fun DetailScaffold(
    title: String,
    target: ItemTarget,
    loading: Boolean,
    content: LazyListScope.() -> Unit,
) {
    val navigator = LocalNavigator.current
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    CompositionLocalProvider(LocalInsightsSnackbar provides snackbar) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = BottomRoom)) },
            topBar = {
                TopAppBar(
                    title = {
                        AnimatedVisibility(
                            scrolled,
                            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                        ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, "More options")
                            }
                            ItemMenu(target, expanded = menuOpen, onDismiss = { menuOpen = false })
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            },
        ) { inner ->
            if (loading) {
                Box(Modifier.padding(inner)) { InsightsLoading() }
            } else {
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding(), bottom = BottomRoom),
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            }
        }
    }
}

/** The big picture, the name and a line under it. */
@Composable
fun DetailHero(
    target: ItemTarget,
    shape: Shape,
    title: String,
    lines: List<String>,
    onSubtitle: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val cover = Modifier.size(232.dp).sharedElement(target.coverKey)
        if (target !is ItemTarget.Album) {
            ArtistAvatar(target.artworkUrl, target.title, shape, cover, initialsSize = 72.sp)
        } else {
            Artwork(target.artworkUrl, target.title, cover, shape = shape)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.displaySmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            if (onSubtitle != null) {
                androidx.compose.material3.TextButton(onClick = onSubtitle) {
                    Text(subtitle, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text(subtitle, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        lines.forEach {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Four figures in a segmented 2 by 2 grid. */
@Composable
fun FigureGrid(figures: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val rows = figures.chunked(2)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        rows.forEachIndexed { r, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(GroupGap)) {
                pair.forEachIndexed { c, (label, value) ->
                    val big = 24.dp
                    val small = 6.dp
                    val top = r == 0
                    val bottom = r == rows.lastIndex
                    val start = c == 0
                    val end = c == pair.lastIndex
                    val shape = androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = if (top && start) big else small,
                        topEnd = if (top && end) big else small,
                        bottomStart = if (bottom && start) big else small,
                        bottomEnd = if (bottom && end) big else small,
                    )
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape, modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(value, style = MaterialTheme.typography.headlineSmallEmphasized, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Plays per month since it was first heard: the last two years at most. */
suspend fun monthlyTimeline(engine: Engine, scope: Scope, firstMs: Long, now: ZonedDateTime = ZonedDateTime.now()): List<BarPoint> {
    val zone = now.zone
    val firstMonth = firstMs.toZoned(zone).toLocalDate().withDayOfMonth(1)
    val thisMonth = now.toLocalDate().withDayOfMonth(1)
    val start = maxOf(firstMonth, thisMonth.minusMonths(23))
    val months = (ChronoUnit.MONTHS.between(start, thisMonth) + 1).toInt().coerceAtLeast(6)
    val begin = thisMonth.minusMonths((months - 1).toLong())
    val boundaries = (0..months).map { begin.plusMonths(it.toLong()).atStartOfDay(zone) }
    val buckets = engine.read { timeline(boundaries.map { it.toInstant().toEpochMilli() }, SourceFilter.ALL, scope) }
    val locale = Locale.getDefault()
    return buckets.mapIndexed { i, b ->
        val month = boundaries[i]
        val label = when {
            months <= 12 -> month.month.getDisplayName(TextStyle.NARROW, locale)
            i % 3 == 0 -> month.format(DateTimeFormatter.ofPattern("MMM", locale))
            else -> null
        }
        BarPoint(
            b.plays.toFloat(), label,
            month.format(DateTimeFormatter.ofPattern("MMM yyyy", locale)) + " · " + Format.plays(b.plays.toLong()),
        )
    }
}

fun LazyListScope.timelineSection(points: List<BarPoint>, name: String) {
    if (points.none { it.value > 0f }) return
    item(key = "timeline", contentType = "card") {
        InsightCard(Modifier.padding(top = 12.dp)) {
            CardLabel("Plays over time", Icons.Rounded.BarChart)
            Spacer(Modifier.height(12.dp))
            InsightBarChart(points, axis = AxisKind.Count, summary = "Plays of $name by month.")
        }
    }
}

fun LazyListScope.clockSection(hours: List<Long>) {
    if (hours.sum() < 3) return
    val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
    item(key = "clock", contentType = "card") {
        InsightCard(Modifier.padding(top = 12.dp)) {
            CardLabel("When you play it", Icons.Rounded.Schedule)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ListeningClock(hours, Modifier.fillMaxWidth(0.8f)) }
            Spacer(Modifier.height(8.dp))
            Text(
                "Mostly around ${Format.hour(peak)}.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A shelf of artists: credits on a song, or who an artist works with. */
fun LazyListScope.artistShelf(key: String, title: String, artists: List<ItemTarget.Artist>, captions: Map<String, String> = emptyMap()) {
    if (artists.isEmpty()) return
    item(key = "$key-header", contentType = "header") { SectionHeader(title, Modifier.padding(top = 20.dp)) }
    item(key = key, contentType = "shelf") {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth().bleed(16.dp),
        ) {
            itemsIndexed(artists, key = { _, a -> a.identity }) { i, artist ->
                ArtistCell(artist, captions[artist.identity], index = i)
            }
        }
    }
}

/** "Mar 3, 2025". */
fun dateOf(ms: Long): String = Format.shortDate(ms)

/** The page's dates, for "first heard" and "last heard". */
fun relativeDay(ms: Long): String {
    val day = ms.toZoned(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dateOf(ms)
    }
}

package com.luke.tracks.ui.charts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luke.tracks.data.PeriodKind
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.insights.ArtistAvatar
import com.luke.tracks.ui.insights.ConnectedChoice
import com.luke.tracks.ui.insights.CountUpText
import com.luke.tracks.ui.insights.EmptyState
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.GroupGap
import com.luke.tracks.ui.insights.InsightCard
import com.luke.tracks.ui.insights.InsightRow
import com.luke.tracks.ui.insights.InsightsLoading
import com.luke.tracks.ui.insights.ItemActionsBox
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.insights.MovementBadge
import com.luke.tracks.ui.insights.PlayShuffleButtons
import com.luke.tracks.ui.insights.PlaysAndMovement
import com.luke.tracks.ui.insights.engineState
import com.luke.tracks.ui.insights.rememberGraph
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.summary.periodName

private val BottomRoom: androidx.compose.ui.unit.Dp
    @Composable get() = com.luke.tracks.ui.navigation.bottomClearance

/**
 * Charts: your top songs, artists and albums for any week, month or year, stepping back
 * through the past, with how far each moved.
 */
@Composable
fun ChartsScreen() {
    var kind by rememberSaveable { mutableStateOf(ChartKind.Songs) }
    var period by rememberSaveable { mutableStateOf(PeriodKind.Week) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf(SourceChoice.All) }
    var forward by remember { mutableStateOf(false) }
    val data by engineState(kind, period, offset, source) { loadCharts(it, kind, period, offset, source) }
    val navigator = LocalNavigator.current
    val graph = rememberGraph()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    var filterOpen by remember { mutableStateOf(false) }

    // Songs from Your Music for Play and Shuffle; nothing when none are playable.
    var playable by remember { mutableStateOf<List<PlayableSong>>(emptyList()) }
    LaunchedEffect(data, kind) {
        val entries = data?.entries.orEmpty()
        playable = if (kind != ChartKind.Songs) emptyList() else runCatching {
            graph.library.findAll(entries.map { (it.target as ItemTarget.Song).identity })
        }.getOrDefault(emptyList())
    }

    CompositionLocalProvider(LocalInsightsSnackbar provides snackbar) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = BottomRoom)) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Charts") },
                    subtitle = if (source != SourceChoice.All) ({ Text(source.label) }) else null,
                    actions = {
                        Box {
                            IconButton(onClick = { filterOpen = true }) { Icon(Icons.Rounded.FilterList, "Which plays count") }
                            DropdownMenu(
                                expanded = filterOpen,
                                onDismissRequest = { filterOpen = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                SourceChoice.entries.forEach { choice ->
                                    DropdownMenuItem(
                                        text = { Text(choice.label) },
                                        trailingIcon = { if (choice == source) Icon(Icons.Rounded.Check, null) },
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            source = choice
                                            filterOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { navigator.open(SearchRoute) }) { Icon(Icons.Rounded.Search, "Search") }
                    },
                    scrollBehavior = scroll,
                )
            },
        ) { inner ->
            val current = data
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 4.dp, bottom = BottomRoom),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (current?.historyEmpty == true) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Rounded.BarChart,
                            title = "No charts yet",
                            message = "Your top songs, artists and albums show up here once you've played a few songs in Tracks or imported your Last.fm history.",
                            action = "Import from Last.fm",
                            onAction = { navigator.open(SettingsPageRoute("lastfm")) },
                        )
                    }
                    return@LazyColumn
                }
                item(key = "kind", contentType = "choice") {
                    ConnectedChoice(ChartKind.entries, kind, { kind = it }, { it.label })
                }
                item(key = "period", contentType = "choice") {
                    ConnectedChoice(
                        PeriodKind.entries, period, { period = it; offset = 0 }, ::periodName,
                        Modifier.padding(top = 8.dp),
                    )
                }
                if (current == null) {
                    item(key = "loading") { InsightsLoading() }
                    return@LazyColumn
                }
                item(key = "stepper", contentType = "stepper") {
                    Stepper(
                        current,
                        forward,
                        onBack = { forward = false; offset++ },
                        onForward = { forward = true; if (offset > 0) offset-- },
                    )
                }
                item(key = "figures", contentType = "figures") { Figures(current) }
                if (playable.isNotEmpty()) {
                    item(key = "play", contentType = "play") {
                        PlayShuffleButtons(
                            playable,
                            QueueOrigin("Top songs, ${Format.periodTitle(current.period, current.offset).lowercase()}", QueueOrigin.Kind.Playlist),
                            Modifier.padding(top = 16.dp),
                        )
                    }
                }
                if (current.entries.isEmpty()) {
                    item(key = "quiet") {
                        Text(
                            "Nothing played in this stretch.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 32.dp),
                        )
                    }
                    return@LazyColumn
                }
                val top = current.entries.first()
                item(key = "top-${top.target.coverKey}", contentType = "top") {
                    NumberOne(top, current.hasPrevious, Modifier.animateItem().padding(top = 20.dp, bottom = 12.dp))
                }
                val rest = current.entries.drop(1)
                itemsIndexed(rest, key = { _, e -> e.target.coverKey }, contentType = { _, _ -> "row" }) { index, entry ->
                    InsightRow(
                        target = entry.target,
                        index = index,
                        count = rest.size,
                        rank = entry.rank.toInt(),
                        subtitle = entry.subtitle,
                        modifier = Modifier.animateItem().padding(top = if (index == 0) 0.dp else GroupGap),
                    ) { PlaysAndMovement(entry.plays, entry.rank, entry.previousRank, current.hasPrevious) }
                }
            }
        }
    }
}

@Composable
private fun Stepper(data: ChartsData, forward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = data.period to data.offset,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(spatial) { it / 4 * dir } + fadeIn(fade)) togetherWith
                    (slideOutHorizontally(spatial) { -it / 4 * dir } + fadeOut(fade))
            },
            modifier = Modifier.weight(1f),
            label = "stepper",
        ) { (period, offset) ->
            Column {
                Text(
                    Format.periodTitle(period, offset),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Format.periodDates(period),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (data.period.kind != PeriodKind.AllTime) {
            FilledTonalIconButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onBack() },
                enabled = data.canGoBack,
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Rounded.ChevronLeft, "Earlier") }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onForward() },
                enabled = data.offset > 0,
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Rounded.ChevronRight, "Later") }
        }
    }
}

@Composable
private fun Figures(data: ChartsData) {
    val scheme = MaterialTheme.colorScheme
    InsightCard {
        Row(Modifier.fillMaxWidth()) {
            Figure("Plays", Modifier.weight(1f)) {
                CountUpText(data.totals.plays.toLong(), MaterialTheme.typography.headlineMediumEmphasized, color = scheme.onSurface)
            }
            Figure("Listening", Modifier.weight(1.2f)) {
                Text(Format.compactDuration(data.totals.listenMs), style = MaterialTheme.typography.headlineMediumEmphasized, color = scheme.onSurface, maxLines = 1)
            }
            Figure("New to you", Modifier.weight(1f)) {
                CountUpText(data.totals.newSongs.toLong(), MaterialTheme.typography.headlineMediumEmphasized, color = scheme.onSurface)
            }
        }
        val before = data.previous?.listenMs
        if (before != null && before >= 60_000 && data.period.kind != PeriodKind.AllTime) {
            val percent = Math.round((data.totals.listenMs - before).toDouble() / before * 100).toInt()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (percent >= 0) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                    null,
                    tint = if (percent >= 0) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (percent >= 0) "$percent% more" else "${-percent}% less") + " listening than " +
                        Format.previousPhrase(data.period.kind, data.offset == 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Figure(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        value()
    }
}

/** The number one, given a card of its own: a big cover in an expressive shape. */
@Composable
private fun NumberOne(entry: ChartEntry, hasPrevious: Boolean, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(32.dp)
    ItemActionsBox(entry.target, onClick = { navigator.open(entry.target.route) }, modifier = modifier.fillMaxWidth(), shape = shape) {
        Surface(color = scheme.primaryContainer, shape = shape, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val coverModifier = Modifier.size(128.dp).sharedElement(entry.target.coverKey)
                if (entry.target is ItemTarget.Artist) {
                    ArtistAvatar(entry.target.artworkUrl, entry.target.title, MaterialShapes.Cookie9Sided.toShape(), coverModifier, initialsSize = 40.sp)
                } else {
                    Artwork(entry.target.artworkUrl, entry.target.title, coverModifier, shape = RoundedCornerShape(24.dp))
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("No. 1", style = MaterialTheme.typography.labelLargeEmphasized, color = scheme.onPrimaryContainer)
                        Spacer(Modifier.width(8.dp))
                        MovementBadge(entry.rank, entry.previousRank, hasPrevious)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entry.target.title,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        color = scheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = scheme.onPrimaryContainer.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(Format.plays(entry.plays), style = MaterialTheme.typography.titleSmall, color = scheme.onPrimaryContainer)
                }
            }
        }
    }
}

package com.luke.tracks.ui.summary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.data.PeriodKind
import com.luke.tracks.ui.insights.AxisKind
import com.luke.tracks.ui.insights.CardLabel
import com.luke.tracks.ui.insights.ConnectedChoice
import com.luke.tracks.ui.insights.EmptyState
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.GroupGap
import com.luke.tracks.ui.insights.InsightBarChart
import com.luke.tracks.ui.insights.InsightCard
import com.luke.tracks.ui.insights.InsightRow
import com.luke.tracks.ui.insights.InsightsLoading
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.ArtistCell
import com.luke.tracks.ui.insights.AlbumCell
import com.luke.tracks.ui.insights.ListeningClock
import com.luke.tracks.ui.insights.LocalInsightsSnackbar
import com.luke.tracks.ui.insights.PlaysAndMovement
import com.luke.tracks.ui.insights.SectionHeader
import com.luke.tracks.ui.insights.ShapeIcon
import com.luke.tracks.ui.insights.Share
import com.luke.tracks.ui.insights.ShareBar
import com.luke.tracks.ui.insights.WeekHeatMap
import com.luke.tracks.ui.insights.bleed
import com.luke.tracks.ui.insights.WeekdayChart
import com.luke.tracks.ui.insights.engineState
import com.luke.tracks.ui.insights.rememberCountUp
import com.luke.tracks.ui.insights.segmentedShape
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.SearchRoute
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.SettingsRoute
import com.luke.tracks.ui.navigation.Tab

/** Bottom room for the mini player floating over the navigation bar. */
private val BottomRoom: androidx.compose.ui.unit.Dp
    @Composable get() = com.luke.tracks.ui.navigation.bottomClearance

/**
 * Summary: how much you listened, compared with last time, and what it was. A big rounded
 * number up top, a bar for every day, then the top songs, artists and albums, highlights and
 * when you listen.
 */
@Composable
fun SummaryScreen() {
    var kind by rememberSaveable { mutableStateOf(PeriodKind.Month) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val data by engineState(kind, offset) { engine -> loadSummary(engine, kind, offset) }
    val navigator = LocalNavigator.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    CompositionLocalProvider(LocalInsightsSnackbar provides snackbar) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = BottomRoom)) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Summary") },
                    actions = {
                        IconButton(onClick = { navigator.open(SettingsRoute) }) { Icon(Icons.Rounded.Settings, "Settings") }
                    },
                    scrollBehavior = scroll,
                )
            },
        ) { inner ->
            val current = data
            when {
                current == null -> Box(Modifier.padding(inner)) { InsightsLoading() }
                current.historyEmpty -> Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Rounded.Headphones,
                        title = "No listening yet",
                        action = "Import from Last.fm",
                        onAction = { navigator.open(SettingsPageRoute("lastfm")) },
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 4.dp, bottom = BottomRoom),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "period", contentType = "period") {
                        ConnectedChoice(
                            options = PeriodKind.entries,
                            selected = kind,
                            onSelect = { kind = it; offset = 0 },
                            label = ::periodName,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    item(key = "hero", contentType = "hero") {
                        ListeningHero(
                            current,
                            onBack = { offset++ },
                            onForward = { if (offset > 0) offset-- },
                        )
                    }
                    item(key = "tiles", contentType = "tiles") { Tiles(current, Modifier.padding(top = 12.dp)) }
                    summaryLists(current, onSeeCharts = { navigator.open(com.luke.tracks.ui.navigation.ChartsRoute) })
                }
            }
        }
    }
}

fun periodName(kind: PeriodKind): String = when (kind) {
    PeriodKind.Week -> "Week"
    PeriodKind.Month -> "Month"
    PeriodKind.Year -> "Year"
    PeriodKind.AllTime -> "All time"
}

@Composable
private fun ListeningHero(data: SummaryData, onBack: () -> Unit, onForward: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    InsightCard(color = scheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            CardLabel("Listening", Icons.Rounded.Headphones, Modifier.weight(1f))
            if (data.period.kind != PeriodKind.AllTime) {
                FilledTonalIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onBack() },
                    enabled = data.canGoBack,
                    modifier = Modifier.size(36.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(Icons.Rounded.ChevronLeft, "Earlier") }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onForward() },
                    enabled = data.offset > 0,
                    modifier = Modifier.size(36.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(Icons.Rounded.ChevronRight, "Later") }
            }
        }
        Spacer(Modifier.height(4.dp))
        val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = Format.periodLabel(data.period),
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "period",
        ) { label ->
            Text(label, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        val listen = rememberCountUp(data.totals.listenMs)
        val big = MaterialTheme.typography.displayLargeEmphasized
        Text(
            Format.heroDuration(
                listen,
                SpanStyle(fontSize = big.fontSize, fontWeight = big.fontWeight, color = scheme.onSurface),
                SpanStyle(fontSize = MaterialTheme.typography.headlineMedium.fontSize, color = scheme.onSurfaceVariant),
            ),
            style = big,
            modifier = Modifier.semantics { contentDescription = Format.spokenDuration(data.totals.listenMs) + " of listening" },
            maxLines = 1,
        )
        data.trendPercent?.let { percent ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val up = percent >= 0
                Icon(
                    if (up) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                    null,
                    tint = if (up) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (up) "+$percent%" else "$percent%") + " from " + Format.previousPhrase(data.period.kind, data.isCurrent),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (data.bars.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            InsightBarChart(
                points = data.bars,
                axis = AxisKind.Minutes,
                average = data.averageMinutes,
                summary = "Listening over ${Format.periodLabel(data.period)}. ${data.averageLabel} ${Format.spokenDuration(data.averageMs)}.",
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            HeroFigure(data.averageLabel, Format.compactDuration(data.averageMs), Modifier.weight(1.3f))
            HeroFigure("Plays", Format.number(data.totals.plays), Modifier.weight(1f))
            HeroFigure("Active days", Format.number(data.activity.daysWithPlays), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Tiles(data: SummaryData, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "Artists", Icons.Rounded.Mic, data.totals.artists.toLong(), null,
                scheme.primaryContainer, scheme.onPrimaryContainer, MaterialShapes.Cookie9Sided, Modifier.weight(1f),
            )
            StatTile(
                "Different songs", Icons.Rounded.MusicNote, data.totals.songs.toLong(), null,
                scheme.secondaryContainer, scheme.onSecondaryContainer, MaterialShapes.Clover4Leaf, Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val newArtists = data.totals.newArtists.toLong()
            StatTile(
                "New to you", Icons.Rounded.AutoAwesome, data.totals.newSongs.toLong(),
                if (newArtists == 1L) "1 new artist" else "${Format.number(newArtists)} new artists",
                scheme.tertiaryContainer, scheme.onTertiaryContainer, MaterialShapes.Sunny, Modifier.weight(1f),
            )
            StatTile(
                "Streak", Icons.Rounded.LocalFireDepartment, data.streak.current.toLong(),
                "Best: ${Format.days(data.streak.longest.toLong())}",
                scheme.errorContainer, scheme.onErrorContainer, MaterialShapes.SoftBurst, Modifier.weight(1f),
                unit = if (data.streak.current == 1u) "day" else "days",
            )
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    icon: ImageVector,
    value: Long,
    caption: String?,
    container: Color,
    content: Color,
    shape: androidx.graphics.shapes.RoundedPolygon,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    InsightCard(modifier.semantics(mergeDescendants = true) {}, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(icon, shape = shape.toShape(), container = container, content = content, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Format.number(rememberCountUp(value)),
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (unit != null) {
                Spacer(Modifier.width(6.dp))
                Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Text(
            caption ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun LazyListScope.summaryLists(data: SummaryData, onSeeCharts: () -> Unit) {
    if (data.totals.plays == 0uL) {
        item(key = "quiet", contentType = "quiet") {
            Text(
                if (data.isCurrent) "Nothing played yet this ${periodName(data.period.kind).lowercase()}. Your top songs and highlights show up as you listen."
                else "Nothing played in this stretch.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp),
            )
        }
        return
    }

    if (data.highlights.isNotEmpty()) {
        item(key = "highlightsHeader", contentType = "header") { SectionHeader("Highlights", Modifier.padding(top = 20.dp)) }
        val shown = data.highlights.take(6)
        itemsIndexed(shown, key = { _, h -> "highlight-${h.id}" }, contentType = { _, _ -> "highlight" }) { index, highlight ->
            HighlightRow(highlight, index, shown.size, Modifier.animateItem().padding(top = if (index == 0) 0.dp else GroupGap))
        }
    }

    if (data.songs.rows.isNotEmpty()) {
        item(key = "songsHeader", contentType = "header") {
            SectionHeader("Top songs", Modifier.padding(top = 20.dp), action = "See all", onAction = onSeeCharts)
        }
        val rows = data.songs.rows
        itemsIndexed(rows, key = { _, r -> "song-${r.song.identity}" }, contentType = { _, _ -> "row" }) { index, row ->
            val song = row.song
            InsightRow(
                target = ItemTarget.Song(song.identity, song.title, song.artistName, song.albumTitle, song.artworkUrl),
                index = index,
                count = rows.size,
                rank = row.rank.toInt(),
                modifier = Modifier.animateItem().padding(top = if (index == 0) 0.dp else GroupGap),
            ) { PlaysAndMovement(song.plays.toLong(), row.rank, row.previousRank, data.songs.hasPrevious) }
        }
    }

    if (data.artists.rows.isNotEmpty()) {
        item(key = "artistsHeader", contentType = "header") {
            SectionHeader("Top artists", Modifier.padding(top = 20.dp), action = "See all", onAction = onSeeCharts)
        }
        item(key = "artists", contentType = "shelf") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.fillMaxWidth().bleed(16.dp),
            ) {
                itemsIndexed(data.artists.rows, key = { _, r -> r.artist.identity }) { index, row ->
                    ArtistCell(
                        ItemTarget.Artist(row.artist.identity, row.artist.name, row.artist.artworkUrl),
                        Format.plays(row.artist.plays.toLong()),
                        index = index,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (data.albums.rows.isNotEmpty()) {
        item(key = "albumsHeader", contentType = "header") {
            SectionHeader("Top albums", Modifier.padding(top = 20.dp), action = "See all", onAction = onSeeCharts)
        }
        item(key = "albums", contentType = "shelf") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.fillMaxWidth().bleed(16.dp),
            ) {
                items(data.albums.rows, key = { it.album.identity }) { row ->
                    AlbumCell(
                        ItemTarget.Album(row.album.identity, row.album.title, row.album.artistName, row.album.artworkUrl),
                        row.album.artistName,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    item(key = "rhythmHeader", contentType = "header") { SectionHeader("When you listen", Modifier.padding(top = 20.dp)) }
    item(key = "rhythm", contentType = "rhythm") { Rhythm(data) }
    item(key = "sources", contentType = "sources") { Sources(data, Modifier.padding(top = 12.dp)) }
}

@Composable
private fun HighlightRow(highlight: Highlight, index: Int, count: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (highlight.category) {
        HighlightCategory.Achievement -> scheme.errorContainer to scheme.onErrorContainer
        HighlightCategory.Favourites -> scheme.primaryContainer to scheme.onPrimaryContainer
        HighlightCategory.Discovery -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        HighlightCategory.Rhythm -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    val tint = when (highlight.category) {
        HighlightCategory.Achievement -> scheme.error
        HighlightCategory.Favourites -> scheme.primary
        HighlightCategory.Discovery -> scheme.tertiary
        HighlightCategory.Rhythm -> scheme.secondary
    }
    val shapes = listOf(MaterialShapes.Cookie7Sided, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf, MaterialShapes.Cookie9Sided, MaterialShapes.Pentagon, MaterialShapes.Flower)
    Surface(
        color = scheme.surfaceContainer,
        shape = segmentedShape(index, count),
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                symbolIcon(highlight.symbol),
                shape = shapes[index % shapes.size].toShape(),
                container = container,
                content = content,
                size = 44.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(highlight.category.title, style = MaterialTheme.typography.labelLargeEmphasized, color = tint)
                Spacer(Modifier.height(2.dp))
                Text(highlight.text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            }
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

@Composable
private fun Rhythm(data: SummaryData) {
    val hours = data.activity.hours.map { it.toLong() }
    val weekdays = data.activity.weekdays.map { it.toLong() }
    val peakHour = hours.indices.maxByOrNull { hours[it] } ?: 0
    val peakDay = weekdays.indices.maxByOrNull { weekdays[it] } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InsightCard {
            CardLabel("Time of day", Icons.Rounded.Schedule)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ListeningClock(hours, Modifier.fillMaxWidth(0.86f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "You listen most around ${Format.hour(peakHour)}.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InsightCard {
            CardLabel("Day of the week", Icons.Rounded.CalendarMonth)
            Spacer(Modifier.height(20.dp))
            WeekdayChart(weekdays)
            Spacer(Modifier.height(16.dp))
            Text(
                "${Format.weekdayName(peakDay)} is your biggest day.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InsightCard {
            CardLabel("Your week by the hour", Icons.Rounded.GridView)
            Spacer(Modifier.height(20.dp))
            WeekHeatMap(data.activity.heat.map { it.toLong() })
        }
    }
}

@Composable
private fun Sources(data: SummaryData, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val a = data.activity
    val shares = listOf(
        Share("Tracks", a.onDemand.toLong(), scheme.primary),
        Share("Imported", a.imported.toLong(), scheme.secondary),
        Share("Last.fm", a.lastFm.toLong(), scheme.outline),
    )
    if (shares.count { it.value > 0 } < 2) return
    InsightCard(modifier) {
        CardLabel("How you listened", Icons.Rounded.Headphones)
        Spacer(Modifier.height(20.dp))
        ShareBar(shares)
    }
}

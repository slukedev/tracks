package com.luke.tracks.ui.charts

import com.luke.tracks.data.CalendarPeriod
import com.luke.tracks.data.PeriodKind
import com.luke.tracks.engine.Engine
import com.luke.tracks.ui.insights.AllTime
import com.luke.tracks.ui.insights.ItemTarget
import uniffi.tracks_engine.PeriodTotals
import uniffi.tracks_engine.SourceFilter
import java.time.ZonedDateTime

enum class ChartKind(val label: String) { Songs("Songs"), Artists("Artists"), Albums("Albums") }

/** Which plays count: everything, streaming apps, or your own music. */
enum class SourceChoice(val label: String, val filter: SourceFilter) {
    All("All", SourceFilter.ALL),
    Streaming("Streaming", SourceFilter.APPLE_MUSIC),
    YourMusic("Your music", SourceFilter.YOUR_MUSIC),
}

/** One line of a chart. */
data class ChartEntry(
    val target: ItemTarget,
    val subtitle: String?,
    val plays: Long,
    val listenMs: Long,
    val rank: UInt,
    val previousRank: UInt?,
)

data class ChartsData(
    val period: CalendarPeriod,
    val offset: Int,
    val entries: List<ChartEntry>,
    val hasPrevious: Boolean,
    val totals: PeriodTotals,
    val previous: PeriodTotals?,
    val canGoBack: Boolean,
    val historyEmpty: Boolean,
)

suspend fun loadCharts(
    engine: Engine,
    kind: ChartKind,
    periodKind: PeriodKind,
    offset: Int,
    source: SourceChoice,
    now: ZonedDateTime = ZonedDateTime.now(),
): ChartsData {
    val period = CalendarPeriod.back(periodKind, offset, now)
    val first = AllTime.firstPlayMs(engine)
    val previous = period.previous
    val filter = source.filter
    return engine.read {
        val limit = 100u
        val (entries, hasPrevious) = when (kind) {
            ChartKind.Songs -> songChart(period.engine, previous?.engine, filter, limit).let { chart ->
                chart.rows.map {
                    val s = it.song
                    ChartEntry(
                        ItemTarget.Song(s.identity, s.title, s.artistName, s.albumTitle, s.artworkUrl),
                        s.artistName, s.plays.toLong(), s.listenMs, it.rank, it.previousRank,
                    )
                } to chart.hasPrevious
            }
            ChartKind.Artists -> artistChart(period.engine, previous?.engine, filter, limit).let { chart ->
                chart.rows.map {
                    val a = it.artist
                    ChartEntry(
                        ItemTarget.Artist(a.identity, a.name, a.artworkUrl),
                        if (a.songCount == 1uL) "1 song" else "${a.songCount} songs",
                        a.plays.toLong(), a.listenMs, it.rank, it.previousRank,
                    )
                } to chart.hasPrevious
            }
            ChartKind.Albums -> albumChart(period.engine, previous?.engine, filter, limit).let { chart ->
                chart.rows.map {
                    val a = it.album
                    ChartEntry(
                        ItemTarget.Album(a.identity, a.title, a.artistName, a.artworkUrl),
                        a.artistName, a.plays.toLong(), a.listenMs, it.rank, it.previousRank,
                    )
                } to chart.hasPrevious
            }
        }
        val compare = if (offset == 0) period.previousToDate(now) else previous
        ChartsData(
            period = period,
            offset = offset,
            entries = entries,
            hasPrevious = hasPrevious,
            totals = totals(period.engine, filter),
            previous = compare?.let { totals(it.engine, filter) },
            canGoBack = periodKind != PeriodKind.AllTime && first != null && period.start != null &&
                first < period.start.toInstant().toEpochMilli(),
            historyEmpty = first == null,
        )
    }
}

package com.luke.tracks.ui.summary

import com.luke.tracks.data.CalendarPeriod
import com.luke.tracks.data.PeriodKind
import com.luke.tracks.data.toZoned
import com.luke.tracks.engine.Engine
import com.luke.tracks.ui.insights.AllTime
import com.luke.tracks.ui.insights.BarPoint
import com.luke.tracks.ui.insights.Format
import uniffi.tracks_engine.Activity
import uniffi.tracks_engine.AlbumChart
import uniffi.tracks_engine.ArtistChart
import uniffi.tracks_engine.Period
import uniffi.tracks_engine.PeriodTotals
import uniffi.tracks_engine.Scope
import uniffi.tracks_engine.SongChart
import uniffi.tracks_engine.SourceFilter
import uniffi.tracks_engine.Streak
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Everything Summary shows for one period, read off the main thread in one go. */
data class SummaryData(
    val period: CalendarPeriod,
    val offset: Int,
    val isCurrent: Boolean,
    val totals: PeriodTotals,
    val previous: PeriodTotals?,
    val bars: List<BarPoint>,
    /** Average minutes per bar, for the dashed line. */
    val averageMinutes: Float?,
    /** Average listening per day (per month for a year). */
    val averageMs: Long,
    val averageLabel: String,
    val songs: SongChart,
    val artists: ArtistChart,
    val albums: AlbumChart,
    val activity: Activity,
    val streak: Streak,
    val highlights: List<Highlight>,
    val canGoBack: Boolean,
    val historyEmpty: Boolean,
) {
    /** Percent change in listening against the same point last period, when that means something. */
    val trendPercent: Int?
        get() {
            val before = previous?.listenMs ?: return null
            if (before < 60_000 || period.kind == PeriodKind.AllTime) return null
            return (((totals.listenMs - before).toDouble() / before) * 100).let { Math.round(it).toInt() }
        }
}

suspend fun loadSummary(
    engine: Engine,
    kind: PeriodKind,
    offset: Int,
    now: ZonedDateTime = ZonedDateTime.now(),
): SummaryData {
    val period = CalendarPeriod.back(kind, offset, now)
    val isCurrent = offset == 0
    val first = AllTime.firstPlayMs(engine)
    val previousToDate = if (isCurrent) period.previousToDate(now) else period.previous
    val nowMs = now.toInstant().toEpochMilli()

    val boundaries: List<ZonedDateTime> = when (kind) {
        PeriodKind.AllTime -> allTimeBoundaries(first, now)
        else -> period.buckets(now.zone)
    }
    return engine.read {
        val totals = totals(period.engine, SourceFilter.ALL)
        val previous = previousToDate?.let { totals(it.engine, SourceFilter.ALL) }
        val timeline = if (boundaries.size >= 2) {
            timeline(boundaries.map { it.toInstant().toEpochMilli() }, SourceFilter.ALL, Scope.All)
        } else emptyList()
        val fullPrevious = period.previous?.engine
        val songs = songChart(period.engine, fullPrevious, SourceFilter.ALL, 5u)
        val artists = artistChart(period.engine, fullPrevious, SourceFilter.ALL, 12u)
        val albums = albumChart(period.engine, fullPrevious, SourceFilter.ALL, 12u)
        val activity = activity(period.engine, SourceFilter.ALL, Scope.All)
        val streak = streak(nowMs)

        // Bars, and the average over the part of the period that has happened.
        val bars = timeline.mapIndexed { i, bucket ->
            val start = boundaries[i]
            BarPoint(
                value = bucket.listenMs / 60_000f,
                label = barLabel(kind, start, i, timeline.size),
                detail = barDetail(kind, start) + " · " + Format.compactDuration(bucket.listenMs),
            )
        }
        val elapsed = boundaries.dropLast(1).count { !it.isAfter(now) }.coerceAtLeast(1)
        val averageMs = if (kind == PeriodKind.AllTime) {
            val days = first?.let { ChronoUnit.DAYS.between(it.toZoned(now.zone).toLocalDate(), now.toLocalDate()) + 1 } ?: 1
            totals.listenMs / days.coerceAtLeast(1)
        } else totals.listenMs / elapsed
        val averageMinutes = if (bars.isEmpty()) null else totals.listenMs / 60_000f / (if (kind == PeriodKind.AllTime) bars.size else elapsed)

        // What Highlights need beyond the totals: the period's own plays, for "on repeat" and
        // milestones. Not for all time, which would mean reading the whole history.
        val plays = if (period.start != null) {
            val end = period.end!!.toInstant().toEpochMilli()
            playsSince(period.start.toInstant().toEpochMilli()).filter { it.playedAtMs < end }
        } else emptyList()
        val before = period.start?.let { totals(Period(null, it.toInstant().toEpochMilli()), SourceFilter.ALL).plays } ?: 0uL

        val highlights = Highlights.build(
            HighlightInputs(
                kind = kind,
                isCurrent = isCurrent,
                periodStartMs = period.start?.toInstant()?.toEpochMilli(),
                firstPlayMs = first,
                totals = totals,
                playsBefore = before.toLong(),
                plays = plays,
                songs = songs,
                artists = artists,
                activity = activity,
                streak = streak,
            ),
        )

        SummaryData(
            period = period,
            offset = offset,
            isCurrent = isCurrent,
            totals = totals,
            previous = previous,
            bars = bars,
            averageMinutes = averageMinutes,
            averageMs = averageMs,
            averageLabel = if (kind == PeriodKind.Year) "Monthly average" else "Daily average",
            songs = songs,
            artists = artists,
            albums = albums,
            activity = activity,
            streak = streak,
            highlights = highlights,
            canGoBack = kind != PeriodKind.AllTime && first != null && period.start != null &&
                first < period.start.toInstant().toEpochMilli(),
            historyEmpty = first == null,
        )
    }
}

/** Months since the first play, or years when that's more than three years. */
private fun allTimeBoundaries(firstMs: Long?, now: ZonedDateTime): List<ZonedDateTime> {
    if (firstMs == null) return emptyList()
    val firstMonth = firstMs.toZoned(now.zone).toLocalDate().withDayOfMonth(1)
    val months = ChronoUnit.MONTHS.between(firstMonth, now.toLocalDate().withDayOfMonth(1)) + 1
    val out = mutableListOf<ZonedDateTime>()
    if (months > 36) {
        var cursor = firstMonth.withDayOfYear(1).atStartOfDay(now.zone)
        while (!cursor.isAfter(now)) { out += cursor; cursor = cursor.plusYears(1) }
    } else {
        var cursor = firstMonth.atStartOfDay(now.zone)
        while (!cursor.isAfter(now)) { out += cursor; cursor = cursor.plusMonths(1) }
    }
    out += out.last().let { if (months > 36) it.plusYears(1) else it.plusMonths(1) }
    return out
}

private fun barLabel(kind: PeriodKind, start: ZonedDateTime, index: Int, count: Int): String? {
    val locale = Locale.getDefault()
    return when (kind) {
        PeriodKind.Week -> start.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        PeriodKind.Month -> if (index % 7 == 0) Format.monthDay(start) else null
        PeriodKind.Year -> start.month.getDisplayName(TextStyle.NARROW, locale)
        PeriodKind.AllTime -> when {
            count <= 12 -> start.month.getDisplayName(TextStyle.NARROW, locale)
            start.monthValue == 1 || index == 0 -> if (count > 36) start.year.toString() else start.format(DateTimeFormatter.ofPattern("MMM yy", locale))
            else -> null
        }
    }
}

private fun barDetail(kind: PeriodKind, start: ZonedDateTime): String {
    val locale = Locale.getDefault()
    return when (kind) {
        PeriodKind.Week, PeriodKind.Month -> start.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
        PeriodKind.Year -> start.month.getDisplayName(TextStyle.FULL, locale)
        PeriodKind.AllTime -> start.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))
    }
}

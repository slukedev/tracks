package com.luke.tracks.data

import uniffi.tracks_engine.Period
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** The spans Summary and Charts look at. */
enum class PeriodKind { Week, Month, Year, AllTime }

/**
 * A period on the person's own calendar (their week start, their time zone), as Unix
 * milliseconds for the engine: the host works out the calendar, the engine counts within it.
 */
data class CalendarPeriod(
    val kind: PeriodKind,
    val start: ZonedDateTime?,
    val end: ZonedDateTime?,
) {
    val engine: Period get() = Period(start?.toInstant()?.toEpochMilli(), end?.toInstant()?.toEpochMilli())

    /** The period before this one, for "compared with last week". */
    val previous: CalendarPeriod?
        get() = when (kind) {
            PeriodKind.AllTime -> null
            PeriodKind.Week -> CalendarPeriod(kind, start!!.minusWeeks(1), start)
            PeriodKind.Month -> CalendarPeriod(kind, start!!.minusMonths(1), start)
            PeriodKind.Year -> CalendarPeriod(kind, start!!.minusYears(1), start)
        }

    /** The same stretch of the previous period, up to the same point, for a fair comparison. */
    fun previousToDate(now: ZonedDateTime): CalendarPeriod? {
        val prev = previous ?: return null
        val elapsed = java.time.Duration.between(start, now.coerceAtMost(end!!))
        return CalendarPeriod(kind, prev.start, prev.start!!.plus(elapsed))
    }

    fun next(): CalendarPeriod? = when (kind) {
        PeriodKind.AllTime -> null
        PeriodKind.Week -> CalendarPeriod(kind, end, end!!.plusWeeks(1))
        PeriodKind.Month -> CalendarPeriod(kind, end, end!!.plusMonths(1))
        PeriodKind.Year -> CalendarPeriod(kind, end, end!!.plusYears(1))
    }

    fun contains(now: ZonedDateTime): Boolean =
        start == null || (!now.isBefore(start) && now.isBefore(end))

    /** Day boundaries (or month boundaries for a year) across the period, for bar charts. */
    fun buckets(zone: ZoneId = ZoneId.systemDefault()): List<ZonedDateTime> {
        val s = start ?: return emptyList()
        val e = end!!
        val out = mutableListOf<ZonedDateTime>()
        var cursor = s
        while (cursor.isBefore(e)) {
            out += cursor
            cursor = if (kind == PeriodKind.Year) cursor.plusMonths(1) else cursor.plusDays(1)
        }
        out += e
        return out
    }

    companion object {
        fun current(kind: PeriodKind, now: ZonedDateTime = ZonedDateTime.now(), locale: Locale = Locale.getDefault()): CalendarPeriod {
            val today = now.toLocalDate()
            val zone = now.zone
            fun at(date: LocalDate) = date.atStartOfDay(zone)
            return when (kind) {
                PeriodKind.AllTime -> CalendarPeriod(kind, null, null)
                PeriodKind.Week -> {
                    val first: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
                    val start = today.with(TemporalAdjusters.previousOrSame(first))
                    CalendarPeriod(kind, at(start), at(start.plusWeeks(1)))
                }
                PeriodKind.Month -> {
                    val start = today.withDayOfMonth(1)
                    CalendarPeriod(kind, at(start), at(start.plusMonths(1)))
                }
                PeriodKind.Year -> {
                    val start = today.withDayOfYear(1)
                    CalendarPeriod(kind, at(start), at(start.plusYears(1)))
                }
            }
        }

        /** The period [offset] steps back from the current one (0 is now). */
        fun back(kind: PeriodKind, offset: Int, now: ZonedDateTime = ZonedDateTime.now()): CalendarPeriod {
            var p = current(kind, now)
            repeat(offset) { p = p.previous ?: return p }
            return p
        }
    }
}

fun Long.toZoned(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    Instant.ofEpochMilli(this).atZone(zone)

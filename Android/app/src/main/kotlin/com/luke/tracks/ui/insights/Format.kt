package com.luke.tracks.ui.insights

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.luke.tracks.data.CalendarPeriod
import com.luke.tracks.data.PeriodKind
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Plain, friendly figures, as the iOS app writes them. */
object Format {
    private val locale: Locale get() = Locale.getDefault()

    fun number(value: Long): String = NumberFormat.getIntegerInstance(locale).format(value)
    fun number(value: ULong): String = number(value.toLong())
    fun number(value: UInt): String = number(value.toLong())

    fun plays(count: Long): String = if (count == 1L) "1 play" else "${number(count)} plays"
    fun songs(count: Long): String = if (count == 1L) "1 song" else "${number(count)} songs"
    fun days(count: Long): String = if (count == 1L) "1 day" else "${number(count)} days"
    fun times(count: Long): String = if (count == 1L) "once" else "${number(count)} times"

    /** "1h 41m", "42m", "3h". */
    fun compactDuration(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${number(h)}h"
            else -> "${number(h)}h ${m}m"
        }
    }

    /** "15 hr 13 min", spoken in full for accessibility. */
    fun spokenDuration(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        val hours = if (h == 1L) "1 hour" else "${number(h)} hours"
        val mins = if (m == 1L) "1 minute" else "$m minutes"
        return when {
            h == 0L -> mins
            m == 0L -> hours
            else -> "$hours $mins"
        }
    }

    /** "15 hr 13 min" with the numbers in [numberStyle] and units in [unitStyle]. */
    fun heroDuration(ms: Long, numberStyle: SpanStyle, unitStyle: SpanStyle): AnnotatedString {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        return buildAnnotatedString {
            if (h > 0) {
                withStyle(numberStyle) { append(number(h)) }
                withStyle(unitStyle) { append(" hr ") }
            }
            withStyle(numberStyle) { append(m.toString()) }
            withStyle(unitStyle) { append(" min") }
        }
    }

    fun hour(hour: Int): String = when (hour) {
        0 -> "12 AM"
        12 -> "12 PM"
        in 1..11 -> "$hour AM"
        else -> "${hour - 12} PM"
    }

    /** Monday first in most places: Sunday-first index (0..6) to its name. */
    fun weekdayName(sundayFirst: Int, style: TextStyle = TextStyle.FULL): String =
        java.time.DayOfWeek.SUNDAY.plus(sundayFirst.toLong()).getDisplayName(style, locale)

    fun shortDate(ms: Long): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

    fun monthDay(date: ZonedDateTime): String =
        date.format(DateTimeFormatter.ofPattern("MMM d", locale))

    /** "This week", "Last week", "November 2026", "2026", "All time". */
    fun periodTitle(period: CalendarPeriod, offset: Int): String = when (period.kind) {
        PeriodKind.AllTime -> "All time"
        PeriodKind.Week -> when (offset) {
            0 -> "This week"
            1 -> "Last week"
            else -> weekRange(period)
        }
        PeriodKind.Month -> when (offset) {
            0 -> "This month"
            1 -> "Last month"
            else -> monthName(period)
        }
        PeriodKind.Year -> when (offset) {
            0 -> "This year"
            1 -> "Last year"
            else -> period.start!!.year.toString()
        }
    }

    /** The dates behind a period title: "Nov 8 – 14", "November 2026", "2026". */
    fun periodDates(period: CalendarPeriod): String = when (period.kind) {
        PeriodKind.AllTime -> "Everything you've played"
        PeriodKind.Week -> weekRange(period) + ", " + period.start!!.year
        PeriodKind.Month -> monthName(period)
        PeriodKind.Year -> period.start!!.year.toString()
    }

    /** What Summary's hero card shows for its period. */
    fun periodLabel(period: CalendarPeriod): String = when (period.kind) {
        PeriodKind.AllTime -> "All time"
        PeriodKind.Week -> weekRange(period)
        PeriodKind.Month -> monthName(period)
        PeriodKind.Year -> period.start!!.year.toString()
    }

    private fun monthName(period: CalendarPeriod): String =
        period.start!!.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

    private fun weekRange(period: CalendarPeriod): String {
        val start = period.start!!
        val last = period.end!!.minusDays(1)
        return if (start.month == last.month) {
            "${monthDay(start)} – ${last.dayOfMonth}"
        } else {
            "${monthDay(start)} – ${monthDay(last)}"
        }
    }

    /** "this time last week", for comparisons. */
    fun previousPhrase(kind: PeriodKind, isCurrent: Boolean): String = when (kind) {
        PeriodKind.Week -> if (isCurrent) "this time last week" else "the week before"
        PeriodKind.Month -> if (isCurrent) "this time last month" else "the month before"
        PeriodKind.Year -> if (isCurrent) "this time last year" else "the year before"
        PeriodKind.AllTime -> ""
    }

    /** "Today", "Yesterday", "Tuesday", "Mon, Nov 3", "Nov 3, 2025". */
    fun dayHeader(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }

    fun time(ms: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))
}

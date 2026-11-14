package com.luke.tracks.ui.summary

import com.luke.tracks.data.PeriodKind
import com.luke.tracks.data.toZoned
import com.luke.tracks.ui.insights.Format
import uniffi.tracks_engine.Activity
import uniffi.tracks_engine.ArtistChart
import uniffi.tracks_engine.HistoryPlay
import uniffi.tracks_engine.PeriodTotals
import uniffi.tracks_engine.SongChart
import uniffi.tracks_engine.Streak
import java.time.LocalDate

/** The kind of thing a highlight is about, for its heading and colour. */
enum class HighlightCategory(val title: String) {
    Achievement("Achievement"),
    Favourites("Favorites"),
    Discovery("Discovery"),
    Rhythm("Rhythm"),
    Radio("Radio"),
}

/** Which picture goes with a highlight. */
enum class HighlightSymbol { Trophy, Flame, Repeat, Star, Mic, Sparkles, Moon, Sunrise, Clock, Sun, Calendar, Radio, Replay }

/** One short written highlight. */
data class Highlight(
    val id: String,
    val category: HighlightCategory,
    val symbol: HighlightSymbol,
    val text: String,
)

data class HighlightInputs(
    val kind: PeriodKind,
    val isCurrent: Boolean,
    val periodStartMs: Long?,
    val firstPlayMs: Long?,
    val totals: PeriodTotals,
    /** Plays in the whole history before the period starts. */
    val playsBefore: Long,
    /** The period's plays, oldest first; empty for all time. */
    val plays: List<HistoryPlay>,
    val songs: SongChart,
    val artists: ArtistChart,
    val activity: Activity,
    val streak: Streak,
)

/**
 * The iOS app's highlight rules (`StatsCalculator+Insights`), ported to what the shared
 * engine can tell us, plus a couple of Android's own. Each has a threshold, so a habit isn't
 * announced from three songs.
 */
object Highlights {
    val milestones = listOf(100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L, 25_000L, 50_000L, 100_000L)

    fun build(input: HighlightInputs): List<Highlight> {
        val total = input.totals.plays.toLong()
        if (total < 3) return emptyList()
        val out = mutableListOf<Highlight>()
        val hasHistoryBefore = input.firstPlayMs != null && input.periodStartMs != null && input.firstPlayMs < input.periodStartMs

        // The largest milestone passed inside this period, and the day it was passed.
        if (input.plays.isNotEmpty()) {
            val after = input.playsBefore + input.plays.size
            milestones.lastOrNull { it > input.playsBefore && it <= after }?.let { m ->
                val play = input.plays[(m - input.playsBefore - 1).toInt()]
                out += Highlight(
                    "milestone", HighlightCategory.Achievement, HighlightSymbol.Trophy,
                    "You passed ${Format.number(m)} songs on ${Format.shortDate(play.playedAtMs)}.",
                )
            }
        }

        // The longest run yet, while it's still going.
        val streak = input.streak
        if (input.isCurrent && streak.current >= 7u && streak.current >= streak.longest) {
            out += Highlight(
                "streak", HighlightCategory.Achievement, HighlightSymbol.Flame,
                "Your longest streak yet: ${Format.days(streak.current.toLong())} in a row.",
            )
        }

        // On repeat: one song, many times, in a single day.
        var onRepeatSong: String? = null
        if (input.plays.isNotEmpty()) {
            val counts = HashMap<Pair<LocalDate, String>, Int>()
            val latest = HashMap<Pair<LocalDate, String>, HistoryPlay>()
            input.plays.forEach { play ->
                val key = play.playedAtMs.toZoned().toLocalDate() to play.identity
                counts[key] = (counts[key] ?: 0) + 1
                latest[key] = play
            }
            val top = counts.entries.sortedWith(compareByDescending<Map.Entry<Pair<LocalDate, String>, Int>> { it.value }.thenBy { it.key.first }).firstOrNull()
            if (top != null && top.value >= 3) {
                val play = latest.getValue(top.key)
                onRepeatSong = play.identity
                out += Highlight(
                    "onRepeat", HighlightCategory.Favourites, HighlightSymbol.Repeat,
                    "On repeat: you played ${play.title} by ${play.artistName} ${Format.times(top.value.toLong())} in one day.",
                )
            }
        }

        // A new favourite: first heard this period and already near the top.
        if (hasHistoryBefore) {
            input.artists.rows.take(3).firstOrNull { it.artist.firstPlayedMs >= input.periodStartMs!! && it.artist.plays >= 5uL }?.let {
                out += Highlight(
                    "newFavourite", HighlightCategory.Discovery, HighlightSymbol.Star,
                    "New favorite: ${it.artist.name}, first heard on ${Format.shortDate(it.artist.firstPlayedMs)}, is already number ${it.rank}.",
                )
            }
        }

        val topArtist = input.artists.rows.firstOrNull()?.artist
        if (input.totals.artists >= 3uL && topArtist != null && topArtist.plays >= 5uL) {
            val share = topArtist.plays.toDouble() / total
            if (share >= 0.15) {
                out += Highlight(
                    "topArtistShare", HighlightCategory.Favourites, HighlightSymbol.Mic,
                    "${topArtist.name} made up ${percent(share)}% of everything you played.",
                )
            }
        }

        if (hasHistoryBefore && input.totals.newArtists >= 3uL) {
            val n = input.totals.newArtists.toLong()
            out += Highlight(
                "newArtists", HighlightCategory.Discovery, HighlightSymbol.Sparkles,
                "You discovered ${Format.number(n)} new ${if (n == 1L) "artist" else "artists"}.",
            )
        }

        if (hasHistoryBefore && input.totals.songs >= 10uL) {
            val share = input.totals.newSongs.toDouble() / input.totals.songs.toDouble()
            if (share >= 0.5) {
                out += Highlight(
                    "mostlyNew", HighlightCategory.Discovery, HighlightSymbol.Sparkles,
                    "${percent(share)}% of the songs you played were new to you.",
                )
            }
        }

        // Rhythm: night owl or early bird, else the busiest three hours.
        val hours = input.activity.hours.map { it.toLong() }
        val hourTotal = hours.sum().coerceAtLeast(1)
        var persona = false
        if (total >= 20) {
            val night = listOf(22, 23, 0, 1, 2, 3).sumOf { hours[it] }.toDouble() / hourTotal
            val morning = listOf(5, 6, 7, 8).sumOf { hours[it] }.toDouble() / hourTotal
            if (night >= 0.35) {
                persona = true
                out += Highlight("persona", HighlightCategory.Rhythm, HighlightSymbol.Moon, "Night owl: ${percent(night)}% of your listening happens after 10 PM.")
            } else if (morning >= 0.3) {
                persona = true
                out += Highlight("persona", HighlightCategory.Rhythm, HighlightSymbol.Sunrise, "Early bird: ${percent(morning)}% of your listening happens before 9 AM.")
            }
        }
        if (!persona) {
            val parts = (0 until 8).map { part -> part to (0 until 3).sumOf { hours[part * 3 + it] } }.filter { it.second > 0 }
            val top = parts.maxByOrNull { it.second }
            if (parts.size > 1 && top != null && top.second >= 3) {
                out += Highlight(
                    "busiestHour", HighlightCategory.Rhythm, HighlightSymbol.Clock,
                    "You listen most around ${Format.hour(top.first * 3)}: ${Format.songs(top.second)}.",
                )
            }
        }

        val weekdays = input.activity.weekdays.map { it.toLong() }
        val days = input.activity.daysWithPlays.toLong()
        if (input.kind != PeriodKind.Week && total >= 20 && days >= 7) {
            val weekend = (weekdays[0] + weekdays[6]).toDouble() / weekdays.sum().coerceAtLeast(1)
            if (weekend >= 0.5) {
                out += Highlight("weekend", HighlightCategory.Rhythm, HighlightSymbol.Sun, "Weekends are for music: ${percent(weekend)}% of your listening falls on them.")
            }
        }
        if (input.kind != PeriodKind.Week && days > 1) {
            val top = weekdays.indices.maxByOrNull { weekdays[it] }
            if (top != null && weekdays[top] * 2 > weekdays.sum()) {
                out += Highlight(
                    "busiestDay", HighlightCategory.Rhythm, HighlightSymbol.Calendar,
                    "${Format.weekdayName(top)} is your biggest day, with ${Format.songs(weekdays[top])}.",
                )
            }
        }

        val radio = input.activity.radio.toLong()
        if (total >= 10 && radio * 2 > total) {
            out += Highlight(
                "radio", HighlightCategory.Radio, HighlightSymbol.Radio,
                "Radio played ${percent(radio.toDouble() / total)}% of your songs.",
            )
        }

        input.songs.rows.firstOrNull()?.song?.let { top ->
            if (top.plays > 1uL && top.identity != onRepeatSong) {
                out += Highlight(
                    "repeatedSong", HighlightCategory.Favourites, HighlightSymbol.Replay,
                    "Your most played song is ${top.title} by ${top.artistName}, ${Format.times(top.plays.toLong())}.",
                )
            }
        }
        return out
    }

    private fun percent(share: Double): Int = Math.round(share * 100).toInt()
}

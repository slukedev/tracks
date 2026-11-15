package com.luke.tracks.playback.radio

import uniffi.tracks_engine.RadioDiscovery
import uniffi.tracks_engine.RadioReason
import uniffi.tracks_engine.RadioSteering
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** What Tracks Radio says about itself, in the iPhone's words. */
object RadioWords {
    /** Why the song came up, in a few words, under it. */
    fun line(reason: RadioReason, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String = when (reason) {
        RadioReason.NewFind -> "New to you"
        RadioReason.NewFromYourArtist -> "New to you, by an artist you play"
        is RadioReason.NewFindLike -> "New to you, like ${reason.artist}"
        RadioReason.OnTheRoad -> "Played on your drives"
        RadioReason.AroundNow -> "Often played around now"
        is RadioReason.LeaningInto -> "Leaning into ${reason.genre}"
        is RadioReason.OldFavorite -> "Not played since ${month(reason.lastHeardMs, nowMs, zone)}"
        RadioReason.MostPlayed -> "One of your most played"
        is RadioReason.MoreOf -> "More ${reason.genre}, since you let one play"
        is RadioReason.MoreLike -> "More like ${reason.artist}"
        RadioReason.Probing -> "Trying a few directions to start"
        is RadioReason.GoesWith -> "Because you played “${reason.title}”"
    }

    /** "March", or "March 2024" for a month in another year. */
    private fun month(ms: Long, nowMs: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(ms).atZone(zone)
        val name = date.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        return if (date.year == Instant.ofEpochMilli(nowMs).atZone(zone).year) name else "$name ${date.year}"
    }

    /** A turn away after a run of skips, said at once. A turn toward is quieter: Up next says it. */
    val RadioSteering.isTurningAway: Boolean
        get() = when (this) {
            is RadioSteering.AwayFromGenre, is RadioSteering.AwayFromDecade, RadioSteering.FewerNewFinds,
            RadioSteering.MoreNewFinds, RadioSteering.TryingSomethingElse -> true
            is RadioSteering.TowardGenre, is RadioSteering.TowardArtist, is RadioSteering.SettledOn -> false
        }

    /** A few words, for the confirmation shown as it turns. */
    fun headline(steering: RadioSteering): String = when (steering) {
        is RadioSteering.AwayFromGenre -> "Less ${steering.genre} for now"
        is RadioSteering.AwayFromDecade -> "Less from the ${decade(steering.year)} for now"
        RadioSteering.FewerNewFinds -> "More songs you know"
        RadioSteering.MoreNewFinds -> "Trying new finds"
        RadioSteering.TryingSomethingElse -> "Trying something different"
        is RadioSteering.TowardGenre -> "More ${steering.genre}"
        is RadioSteering.TowardArtist -> "More like ${steering.artist}"
        is RadioSteering.SettledOn -> "Settled on ${steering.genre}"
    }

    /** What it's doing and why, in a sentence, under Up next. */
    fun line(steering: RadioSteering): String = when (steering) {
        is RadioSteering.AwayFromGenre ->
            "You skipped a few ${steering.genre} songs in a row, so it's playing other things for a while."
        is RadioSteering.AwayFromDecade ->
            "You skipped a few songs from the ${decade(steering.year)} in a row, so it's playing other years for a while."
        RadioSteering.FewerNewFinds -> "You skipped a few new finds in a row, so it's playing more songs you know."
        RadioSteering.MoreNewFinds -> "You skipped a few songs you know in a row, so it's trying more new finds."
        RadioSteering.TryingSomethingElse ->
            "Nothing's landing, so it's trying genres it hasn't played yet. Let one play and it'll follow."
        is RadioSteering.TowardGenre -> "You let a ${steering.genre} song play, so more like it are coming."
        is RadioSteering.TowardArtist -> "More like ${steering.artist} are coming."
        is RadioSteering.SettledOn ->
            "${steering.genre} landed best of the first few songs, so more of it is coming, with something different now and then."
    }

    /** "’80s", or "2010s" for a year in this century, as people say them. */
    fun decade(year: Int): String {
        val decade = year / 10 * 10
        return if (decade < 2000) "’${(decade % 100).toString().padStart(2, '0')}s" else "${decade}s"
    }

    /** Up next's line while no steering has anything to say. */
    const val PICKS_AS_IT_GOES = "Tracks Radio picks each song as the one before starts."

    fun title(discovery: RadioDiscovery): String = when (discovery) {
        RadioDiscovery.FAMILIAR -> "Familiar"
        RadioDiscovery.BALANCED -> "Balanced"
        RadioDiscovery.ADVENTUROUS -> "Adventurous"
    }

    fun summary(discovery: RadioDiscovery): String = when (discovery) {
        RadioDiscovery.FAMILIAR -> "Mostly songs you love"
        RadioDiscovery.BALANCED -> "Songs you love and new finds"
        RadioDiscovery.ADVENTUROUS -> "Lots of new finds"
    }

    fun detail(discovery: RadioDiscovery): String = when (discovery) {
        RadioDiscovery.FAMILIAR -> "Mostly songs you love, with a new find now and then."
        RadioDiscovery.BALANCED -> "Songs you love, and about one new find in four."
        RadioDiscovery.ADVENTUROUS -> "Half new finds, from artists like yours."
    }

    /** "Hip-Hop and R&B", or "Hip-Hop, R&B and more". */
    fun genreList(genres: Set<String>): String {
        val sorted = genres.sorted()
        return when (sorted.size) {
            0 -> "Everything"
            1 -> sorted[0]
            2 -> "${sorted[0]} and ${sorted[1]}"
            else -> "${sorted[0]}, ${sorted[1]} and more"
        }
    }

    /** "Songs you love and new finds, leaning into Hip-Hop and R&B", for the tuner's header. */
    fun summary(discovery: RadioDiscovery, genres: Set<String>): String =
        if (genres.isEmpty()) summary(discovery) else "${summary(discovery)}, leaning into ${genreList(genres)}"

    /** "Balanced · Hip-Hop", short enough for one line. */
    fun shortSummary(discovery: RadioDiscovery, genres: Set<String>): String =
        if (genres.isEmpty()) title(discovery) else "${title(discovery)} · ${genreList(genres)}"
}

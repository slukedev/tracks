package com.luke.tracks.ui.play

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import uniffi.tracks_engine.DayPart
import uniffi.tracks_engine.Mix
import uniffi.tracks_engine.MixKind
import uniffi.tracks_engine.MixSong
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The mix's name, as the iOS app names it. */
val MixKind.title: String
    get() = when (this) {
        is MixKind.RightNow -> when (part) {
            DayPart.MORNING -> "Morning Mix"
            DayPart.AFTERNOON -> "Afternoon Mix"
            DayPart.EVENING -> "Evening Mix"
            DayPart.NIGHT -> "Late Night Mix"
        }
        MixKind.OnRepeat -> "On Repeat"
        MixKind.AllTimeFavorites -> "All-Time Favorites"
        MixKind.DeepCuts -> "Deep Cuts"
        MixKind.NewFinds -> "New Finds"
        MixKind.RadioFinds -> "Radio Finds"
        MixKind.Rediscover -> "Rediscover"
        is MixKind.Throwback -> when (monthsAgo.toInt()) {
            12 -> "A Year Ago"
            6 -> "Six Months Ago"
            else -> "Three Months Ago"
        }
    }

/** One line on why these songs: the mix's reason for being. */
val MixKind.reason: String
    get() = when (this) {
        is MixKind.RightNow -> {
            val day = if (isWeekend) "weekend" else "weekday"
            when (part) {
                DayPart.MORNING -> "What you play most on $day mornings"
                DayPart.AFTERNOON -> "What you play most on $day afternoons"
                DayPart.EVENING -> "What you play most on $day evenings"
                DayPart.NIGHT -> if (isWeekend) "What you play most late on weekends" else "What you play most late on weeknights"
            }
        }
        MixKind.OnRepeat -> "Your most played this month"
        MixKind.AllTimeFavorites -> "Your most played ever, the ones still in rotation first"
        MixKind.DeepCuts -> "Songs you've barely played by the artists you play most"
        MixKind.NewFinds -> "New to you this month, and played again"
        MixKind.RadioFinds -> "Heard on the radio, never picked yourself"
        MixKind.Rediscover -> "Old favorites you haven't played lately"
        is MixKind.Throwback -> "What you played the week of ${format(aroundMs, "MMM d, yyyy")}"
    }

/** Short enough to sit under a tile on the shelf. */
val MixKind.tileLine: String
    get() = when (this) {
        is MixKind.RightNow -> {
            val day = if (isWeekend) "Weekend" else "Weekday"
            when (part) {
                DayPart.MORNING -> "$day mornings"
                DayPart.AFTERNOON -> "$day afternoons"
                DayPart.EVENING -> "$day evenings"
                DayPart.NIGHT -> if (isWeekend) "Late weekends" else "Late weeknights"
            }
        }
        MixKind.OnRepeat -> "Most played lately"
        MixKind.AllTimeFavorites -> "Most played ever"
        MixKind.DeepCuts -> "Barely played"
        MixKind.NewFinds -> "New this month"
        MixKind.RadioFinds -> "Heard on the radio"
        MixKind.Rediscover -> "Old favorites"
        is MixKind.Throwback -> format(aroundMs, "MMMM yyyy")
    }

/** "Weekday evening", for the moment a time-of-day mix belongs to. */
fun DayPart.moment(isWeekend: Boolean): String = when (this) {
    DayPart.MORNING -> if (isWeekend) "Weekend morning" else "Weekday morning"
    DayPart.AFTERNOON -> if (isWeekend) "Weekend afternoon" else "Weekday afternoon"
    DayPart.EVENING -> if (isWeekend) "Weekend evening" else "Weekday evening"
    DayPart.NIGHT -> if (isWeekend) "Weekend night" else "Weeknight"
}

val MixKind.icon: ImageVector
    get() = when (this) {
        is MixKind.RightNow -> when (part) {
            DayPart.MORNING -> Icons.Rounded.Coffee
            DayPart.AFTERNOON -> Icons.Rounded.WbSunny
            DayPart.EVENING -> Icons.Rounded.WbTwilight
            DayPart.NIGHT -> Icons.Rounded.Bedtime
        }
        MixKind.OnRepeat -> Icons.Rounded.Repeat
        MixKind.AllTimeFavorites -> Icons.Rounded.Favorite
        MixKind.DeepCuts -> Icons.Rounded.Layers
        MixKind.NewFinds -> Icons.Rounded.AutoAwesome
        MixKind.RadioFinds -> Icons.Rounded.Radio
        MixKind.Rediscover -> Icons.Rounded.History
        is MixKind.Throwback -> Icons.Rounded.CalendarMonth
    }

/** Each mix's own color, which seeds its card and page so the crate isn't one tone. */
val MixKind.hue: Color
    get() = when (this) {
        is MixKind.RightNow -> when (part) {
            DayPart.MORNING -> Color(0xFFF2A33A)
            DayPart.AFTERNOON -> Color(0xFF22A699)
            DayPart.EVENING -> Color(0xFF7A5AF8)
            DayPart.NIGHT -> Color(0xFF3A4FC4)
        }
        MixKind.OnRepeat -> Color(0xFFE8475F)
        MixKind.AllTimeFavorites -> Color(0xFFD9467F)
        MixKind.DeepCuts -> Color(0xFF167A86)
        MixKind.NewFinds -> Color(0xFF3FA34D)
        MixKind.RadioFinds -> Color(0xFFE5732C)
        MixKind.Rediscover -> Color(0xFF3D7BE0)
        is MixKind.Throwback -> Color(0xFFB0794A)
    }

/** Tracks Radio's own color: Tracks' red. */
val RadioHue = Color(0xFFE83A5F)

/** One cover in a mix's art: its address, and the name a stand-in is drawn from. */
data class MixCover(val url: String?, val seed: String)

/** Up to four covers from different albums, those with art first. */
val Mix.covers: List<MixCover>
    get() {
        val seen = HashSet<String>()
        val withArt = ArrayList<MixCover>()
        val without = ArrayList<MixCover>()
        for (song in songs) {
            val key = (song.albumTitle ?: song.identity).lowercase()
            if (!seen.add(key)) continue
            val cover = MixCover(song.artworkUrl?.takeIf { it.isNotBlank() }, song.albumTitle ?: song.title)
            if (cover.url == null) without += cover else withArt += cover
            if (withArt.size == 4) break
        }
        return (withArt + without).take(4)
    }

/** "12 plays", "1 play". */
fun playsLabel(plays: Long): String = if (plays == 1L) "1 play" else "$plays plays"

val MixSong.playsLabel: String get() = playsLabel(plays.toLong())

private fun format(ms: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

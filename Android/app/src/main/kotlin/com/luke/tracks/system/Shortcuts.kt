package com.luke.tracks.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.luke.tracks.AppGraph
import com.luke.tracks.MainActivity
import com.luke.tracks.R
import kotlinx.coroutines.flow.first
import uniffi.tracks_engine.DayPart
import uniffi.tracks_engine.Mix
import uniffi.tracks_engine.MixKind

/**
 * Long-press the Tracks icon: Tracks Radio, Search and Summary (static, in shortcuts.xml),
 * and your two best mixes right now, which change with the time of day and what you play.
 */
object Shortcuts {
    private const val PREFIX = "mix:"

    suspend fun update(context: Context, graph: AppGraph) {
        val hidden = graph.settings.settings.first().hiddenSongs.toList()
        val set = runCatching { graph.engine.read { mixes(System.currentTimeMillis(), hidden) } }.getOrNull() ?: return
        val top = (listOfNotNull(set.rightNow) + set.mixes).filter { it.songs.size >= 5 }.distinctBy { it.id }.take(2)
        val wanted = top.map { PREFIX + it.id }.toSet()
        runCatching {
            val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(PREFIX) && it !in wanted }
            if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
            top.forEachIndexed { rank, mix -> ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context, mix, rank)) }
        }
    }

    private fun shortcut(context: Context, mix: Mix, rank: Int): ShortcutInfoCompat {
        val (short, long) = names(mix.kind)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tracks://mix/${mix.id}"), context, MainActivity::class.java)
        return ShortcutInfoCompat.Builder(context, PREFIX + mix.id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_mix))
            .setIntent(intent)
            .setRank(rank)
            .setLongLived(true)
            .build()
    }

    /** Tells the launcher a shortcut was used, so it can offer it at the right moments. */
    fun reportUsed(context: Context, link: Uri) {
        val id = when (link.host) {
            "mix" -> link.lastPathSegment?.let { PREFIX + it }
            "radio", "search", "summary" -> link.host
            else -> null
        } ?: return
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, id) }
    }

    /** A short and a long name, as the launcher shows them. */
    fun names(kind: MixKind): Pair<String, String> = when (kind) {
        is MixKind.RightNow -> when (kind.part) {
            DayPart.MORNING -> "Morning" to "Your morning mix"
            DayPart.AFTERNOON -> "Afternoon" to "Your afternoon mix"
            DayPart.EVENING -> "Evening" to "Your evening mix"
            DayPart.NIGHT -> "Late night" to "Your late night mix"
        }
        MixKind.OnRepeat -> "On Repeat" to "Play On Repeat"
        MixKind.NewFinds -> "New Finds" to "Play New Finds"
        MixKind.RadioFinds -> "Radio Finds" to "Play Radio Finds"
        MixKind.Rediscover -> "Rediscover" to "Play Rediscover"
        is MixKind.Throwback -> "Throwback" to "A year ago this week"
        MixKind.AllTimeFavorites -> "Favorites" to "Play All-Time Favorites"
        MixKind.DeepCuts -> "Deep Cuts" to "Play Deep Cuts"
    }
}

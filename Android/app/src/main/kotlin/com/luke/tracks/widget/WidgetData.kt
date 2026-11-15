package com.luke.tracks.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.luke.tracks.AppGraph
import com.luke.tracks.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.tracks_engine.Scope
import uniffi.tracks_engine.SourceFilter
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** The last seven days, today last. */
data class ListeningData(
    val days: List<Day>,
    val streak: Int,
    val todayMs: Long,
    val weekMs: Long,
) {
    data class Day(val label: String, val listenMs: Long, val isToday: Boolean)

    companion object {
        val sample = ListeningData(
            days = listOf(52, 96, 71, 118, 64, 131, 84).mapIndexed { i, m ->
                val date = LocalDate.now().minusDays((6 - i).toLong())
                Day(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), m * 60_000L, i == 6)
            },
            streak = 12,
            todayMs = 84 * 60_000L,
            weekMs = 616 * 60_000L,
        )
    }
}

data class LastPlayedData(
    val title: String,
    val artist: String,
    val playedAt: Long,
    val cover: Bitmap?,
    val station: String?,
)

/** Reads what the widgets show, from the engine and Room, off the main thread. */
object WidgetData {
    suspend fun listening(graph: AppGraph, now: Long = System.currentTimeMillis()): ListeningData = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val bounds = (dates + today.plusDays(1)).map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
        val buckets = runCatching { graph.engine.read { timeline(bounds, SourceFilter.ALL, Scope.All) } }.getOrDefault(emptyList())
        val streak = runCatching { graph.engine.read { streak(now) }.current.toInt() }.getOrDefault(0)
        val days = dates.mapIndexed { i, date ->
            ListeningData.Day(
                label = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                listenMs = buckets.getOrNull(i)?.listenMs ?: 0,
                isToday = i == dates.lastIndex,
            )
        }
        ListeningData(days, streak, days.last().listenMs, days.sumOf { it.listenMs })
    }

    suspend fun lastPlayed(context: Context, graph: AppGraph): LastPlayedData? = withContext(Dispatchers.IO) {
        val play = graph.history.last() ?: return@withContext null
        LastPlayedData(play.title, play.artistName, play.playedAt, cover(context, play.artworkUri), play.stationName)
    }

    /** A small cover, decoded here because a widget can only show a bitmap. */
    private suspend fun cover(context: Context, uri: String?): Bitmap? {
        if (uri.isNullOrBlank()) return null
        return runCatching {
            if (uri.startsWith("file://") || uri.startsWith("content://")) {
                // Sized down to about 300 pixels: a full cover can be thousands across, past what a
                // widget may carry, and some phones' launchers drop the widget over it.
                val address = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(address)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 300) sample *= 2
                context.contentResolver.openInputStream(address)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } else {
                val request = ImageRequest.Builder(context).data(uri).size(300).allowHardware(false).build()
                (ImageLoader(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            }
        }.getOrNull()
    }

    fun open(context: Context, link: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** "1h 24m", "35m", "0m". */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}

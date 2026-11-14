package com.luke.tracks.data

import android.content.Context
import android.content.Intent
import android.os.Process
import uniffi.tracks_engine.PlayKind
import uniffi.tracks_engine.demoPlays
import java.util.TimeZone

/**
 * Invented listening history from the shared engine (the same generator the Swift app's
 * screenshots use), kept in its own database so the real history is never touched.
 */
object SampleData {
    /** Ten months of plays ending now. */
    fun plays(nowMs: Long = System.currentTimeMillis(), days: Int = 300): List<PlayEntity> =
        demoPlays(nowMs, TimeZone.getDefault().id, days.toUInt(), 2026uL).mapIndexed { index, play ->
            PlayEntity(
                id = "sample-$index",
                title = play.title,
                artistName = play.artistName,
                albumTitle = play.albumTitle,
                artworkUri = null,
                playedAt = play.playedAtMs,
                durationMs = (play.lengthSeconds * 1000).toLong(),
                listenedMs = (play.lengthSeconds * 1000).toLong(),
                // Android keeps only what Tracks plays and what Last.fm imports, so the generator's
                // radio plays become plays in Tracks.
                kind = when (play.kind) {
                    PlayKind.RADIO, PlayKind.ON_DEMAND -> PlayKindValue.OnDemand
                    PlayKind.IMPORTED -> PlayKindValue.Imported
                    PlayKind.LAST_FM -> PlayKindValue.LastFm
                },
                source = if (play.kind == PlayKind.LAST_FM) PlaySourceValue.Streaming else PlaySourceValue.YourMusic,
                appPackage = null,
                appName = null,
                stationName = null,
                mediaId = "",
                scrobbledAt = if (play.isScrobbled) play.playedAtMs + 60_000 else null,
            )
        }

    /** Fills [history] with sample plays when it's empty. */
    suspend fun fillIfEmpty(history: HistoryRepository) {
        if (history.last() == null) history.replaceAll(plays())
    }

    /** Turns sample data on or off, which restarts the app into the other history. */
    fun switch(context: Context, on: Boolean) {
        context.getSharedPreferences("launch", Context.MODE_PRIVATE).edit().putBoolean("sampleData", on).commit()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        Process.killProcess(Process.myPid())
    }
}

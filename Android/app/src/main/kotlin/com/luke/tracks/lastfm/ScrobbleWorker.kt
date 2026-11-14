package com.luke.tracks.lastfm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.luke.tracks.graph
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Sends what's owed to Last.fm when there's a connection: after every song kept, and a few
 * times a day for anything a failure left behind. Failures that aren't the songs' fault back
 * off exponentially.
 */
class ScrobbleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        // Sample plays are pretend and never go to a real account.
        if (graph.sampleData) return Result.success()
        if (!graph.settings.settings.first().scrobbleEnabled) return Result.success()
        val lastFm = graph.lastFm
        val drain = lastFm.drain() ?: return Result.success()
        val result = drain.run()
        val alreadySaid = lastFm.problem.value == LastFm.RECONNECT
        lastFm.noteDrain(result)
        if (result is DrainResult.NeedsReconnect && !alreadySaid) {
            com.luke.tracks.system.Notifications.lastFmNeedsReconnect(applicationContext)
        }
        return when (result) {
            is DrainResult.Done -> Result.success()
            is DrainResult.RetryLater -> if (runAttemptCount < 8) Result.retry() else Result.success()
            is DrainResult.NeedsReconnect -> Result.success()
        }
    }

    companion object {
        private const val NOW = "scrobble"
        private const val PERIODIC = "scrobble-periodic"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Sends soon, after whatever drain is already queued. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScrobbleWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }

        /** A catch-up every six hours, for anything left owed. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScrobbleWorker>(6, TimeUnit.HOURS)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}

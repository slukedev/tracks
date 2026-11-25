package com.luke.tracks.sync

import android.content.Context
import android.os.Build
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
import com.luke.tracks.AppGraph
import com.luke.tracks.graph
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.CloudUsage
import com.luke.tracks.library.trackside.TracksideCloud
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.net.Logs
import com.luke.tracks.net.Unproven
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A Trackside server sync can use. */
data class SyncServer(val id: String, val name: String)

/** Where sync stands, for the Sync page. */
data class SyncStatus(
    /** The chosen server's id, or null when sync is off. */
    val serverId: String? = null,
    val syncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    /** What went wrong and what to do, in a sentence. */
    val problem: String? = null,
    val usage: CloudUsage? = null,
    /** The server was paired again: nothing goes to it until you say so ([CloudSync.confirm]). */
    val needsConfirmation: Boolean = false,
)

/**
 * Sync on Android: off, or Trackside Cloud on one of your Trackside servers (there's no
 * iCloud here). It syncs when the app starts, a little after anything changes, when the
 * server says something changed (its `cloud` event), and in the background every few hours
 * on any network, backing off when the server can't be reached.
 */
class CloudSync(private val graph: AppGraph, private val servers: ServerLibrary) {
    private val dao = graph.history.database.cloud()
    private val runLock = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** The Trackside servers sync can use. */
    val choices = servers.entries.map { list -> list.filter { it.server.isTrackside }.map { SyncServer(it.server.id, it.server.name) } }

    enum class Outcome { Done, RetryLater, Stopped }

    @OptIn(FlowPreview::class)
    fun start() {
        if (graph.sampleData) return
        graph.scope.launch {
            val server = dao.state(SERVER)
            _status.update { it.copy(serverId = server, lastSyncedAt = dao.state(CloudSyncEngine.LAST_SYNCED)?.toLongOrNull()) }
            if (server == null) return@launch
            schedule(graph.context)
            // In the foreground at launch: what changed elsewhere, straight away.
            run()
        }
        graph.scope.launch {
            merge(
                graph.history.written.map { },
                graph.history.removed.map { },
                graph.settings.settings.map { SyncedSettings.read(it) to it.likedSongs }.distinctUntilChanged().drop(1).map { },
            ).debounce(10_000).collect {
                if (_status.value.serverId != null) enqueue(graph.context)
            }
        }
    }

    /** Turns sync off (null), or on with a server. A new server starts with everything here going up. */
    suspend fun choose(serverId: String?) {
        val previous = dao.state(SERVER)
        if (previous == serverId) return
        dao.setState(SERVER, serverId)
        _status.update { SyncStatus(serverId = serverId) }
        if (serverId == null) {
            runCatching { WorkManager.getInstance(graph.context).cancelUniqueWork(PERIODIC) }
            return
        }
        startOver(serverId)
    }

    /** Syncs with the server again after it was paired again, sending everything here as for a new one. */
    suspend fun confirm() {
        val serverId = dao.state(SERVER) ?: return
        _status.update { SyncStatus(serverId = serverId) }
        startOver(serverId)
    }

    private suspend fun startOver(serverId: String) {
        dao.clearStates(CloudSyncEngine.STATE_PREFIX)
        dao.markAllPending()
        graph.history.database.plays().markAllCloudPending()
        servers.syncBinding(serverId)?.let { dao.setState(BOUND, it) }
        schedule(graph.context)
        enqueue(graph.context)
    }

    /** Syncs now, in the background, when there's a network. */
    fun syncNow() = enqueue(graph.context)

    /** The server said [entity] changed (null: anything may have): pull it now, while the app is open. */
    fun pull(entity: String?) {
        graph.scope.launch { run(entity?.let(::setOf)) }
    }

    /** One sync with the chosen server: push, then pull. */
    suspend fun run(entities: Set<String>? = null): Outcome = runLock.withLock {
        if (graph.sampleData) return Outcome.Done
        val serverId = dao.state(SERVER) ?: return Outcome.Done
        val entry = servers.entries.value.firstOrNull { it.server.id == serverId }
        val name = entry?.server?.name ?: "your server"
        val client = servers.tracksideClient(serverId)
        if (client == null) {
            fail("Sign in to $name again to keep syncing.")
            return Outcome.Stopped
        }
        // Paired again, so its address or key changed: what sync knew is forgotten, and nothing
        // goes up until you confirm it's the server you meant.
        val binding = servers.syncBinding(serverId)
        when (val bound = dao.state(BOUND)) {
            null -> binding?.let { dao.setState(BOUND, it) }
            binding -> Unit
            else -> {
                if (dao.state(UNCONFIRMED) != binding) {
                    dao.clearStates(CloudSyncEngine.STATE_PREFIX)
                    dao.setState(UNCONFIRMED, binding)
                }
                _status.update { it.copy(syncing = false, needsConfirmation = true, problem = "$name was paired again. Tap to keep syncing.") }
                return Outcome.Stopped
            }
        }
        _status.update { it.copy(syncing = true) }
        val identity = graph.deviceIdentity
        val engine = CloudSyncEngine(
            api = TracksideCloud(client),
            history = graph.history,
            preferences = AppSyncedPreferences(graph.settings),
            deviceId = identity.id(),
            predecessors = identity.predecessors(),
            deviceName = deviceName(graph.context),
        )
        return try {
            engine.sync(entities)
            val usage = runCatching { TracksideCloud(client).usage() }.getOrNull()
            _status.update {
                it.copy(syncing = false, problem = null, needsConfirmation = false, lastSyncedAt = System.currentTimeMillis(), usage = usage ?: it.usage)
            }
            Outcome.Done
        } catch (error: CloudSyncError) {
            fail(error.message.orEmpty())
            Outcome.Stopped
        } catch (_: TracksideError.Unauthenticated) {
            fail("Pair $name again to keep syncing.")
            Outcome.Stopped
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Logs.w("Cloud sync didn't finish", error)
            val transient = error is IOException || error is Unproven || error is TracksideError.Unproven ||
                error is TracksideError.RateLimited || error is TracksideError.Unavailable ||
                (error is TracksideError.Server && error.status >= 500) || error is TracksideError.Http
            fail(if (transient) "Couldn't reach $name. Tracks will try again." else error.message ?: "Sync didn't finish. Tracks will try again.")
            Outcome.RetryLater
        }
    }

    private fun fail(problem: String) = _status.update { it.copy(syncing = false, problem = problem) }

    private fun deviceName(context: Context): String =
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    companion object {
        const val SERVER = "sync.server"
        /** [ServerLibrary.syncBinding] of the server as sync last knew it. */
        private const val BOUND = "sync.bound"
        private const val UNCONFIRMED = "sync.unconfirmed"
        private const val NOW = "cloud-sync"
        private const val PERIODIC = "cloud-sync-periodic"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request) }
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request) }
        }

        private val installed = java.util.WeakHashMap<AppGraph, CloudSync>()

        fun install(graph: AppGraph, servers: ServerLibrary): CloudSync =
            synchronized(installed) { installed.getOrPut(graph) { CloudSync(graph, servers) } }

        fun of(graph: AppGraph): CloudSync? = synchronized(installed) { installed[graph] }
    }
}

/** Syncs in the background; a server out of reach is tried again later, backing off. */
class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = CloudSync.of(applicationContext.graph) ?: return Result.success()
        return when (sync.run()) {
            CloudSync.Outcome.RetryLater -> if (runAttemptCount < 10) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}

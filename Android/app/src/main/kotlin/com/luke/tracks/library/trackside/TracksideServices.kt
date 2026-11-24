package com.luke.tracks.library.trackside

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.luke.tracks.AppGraph
import com.luke.tracks.graph
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.sync.CloudSync
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** A `trackside://pair` link opened from outside the app or scanned, waiting to be confirmed. */
object PairingLinks {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** Keeps [link] if it's a pairing link; true when it is. */
    fun offer(link: String?): Boolean {
        if (link == null || !PairingPayload.isLink(link)) return false
        _pending.value = link
        return true
    }

    fun clear() {
        _pending.value = null
    }
}

/**
 * What runs alongside your Trackside servers while Tracks is: the listen outbox, now playing,
 * and while the app is in the foreground, each server's live events.
 */
class TracksideServices(private val graph: AppGraph, private val servers: ServerLibrary) {
    val outbox = ListenOutbox(
        dao = graph.history.database.listens(),
        client = servers::tracksideClient,
        isTrackside = { id -> servers.entries.value.any { it.server.id == id && it.server.isTrackside } },
    )
    private val live = java.util.concurrent.ConcurrentHashMap<String, Job>()

    @OptIn(FlowPreview::class)
    fun start() {
        if (graph.sampleData) return
        graph.scope.launch {
            graph.history.written.collect { plays ->
                outbox.record(plays)
                if (plays.any { ServerLibrary.parseMediaId(it.mediaId) != null }) enqueue(graph.context)
            }
        }
        enqueue(graph.context)
        graph.scope.launch {
            delay(1_000)
            reportNowPlaying()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = connectAll()
            override fun onStop(owner: LifecycleOwner) = disconnectAll()
        })
        graph.scope.launch {
            servers.entries.map { list -> list.filter { it.server.isTrackside }.map { it.server } }.distinctUntilChanged().collect {
                if (live.isNotEmpty() || isForeground()) connectAll()
            }
        }
    }

    private fun isForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    /** What's playing, told to its server: playing, paused, or stopped when something else starts. */
    @OptIn(FlowPreview::class)
    private suspend fun reportNowPlaying() {
        var last: Pair<String, String>? = null
        graph.player.state
            .map { state -> ServerLibrary.parseMediaId(state.current?.mediaId.orEmpty()) to state.isPlaying }
            .distinctUntilChanged()
            .debounce(1_000)
            .collect { (song, playing) ->
                val previous = last
                if (previous != null && previous != song) send(previous, 0, "stopped")
                last = song
                if (song != null) send(song, graph.player.position.value, if (playing) "playing" else "paused")
            }
    }

    private suspend fun send(song: Pair<String, String>, positionMs: Long, state: String) {
        val (serverId, songId) = song
        if (servers.entries.value.none { it.server.id == serverId && it.server.isTrackside }) return
        val client = servers.tracksideClient(serverId) ?: return
        runCatching { client.nowPlaying(songId, positionMs, state) }
    }

    @Synchronized
    private fun connectAll() {
        val trackside = servers.entries.value.filter { it.server.isTrackside }.map { it.server.id }.toSet()
        live.keys.filterNot { it in trackside }.forEach { live.remove(it)?.cancel() }
        trackside.filterNot { live[it]?.isActive == true }.forEach { id -> live[id] = graph.scope.launch { listen(id) } }
    }

    @Synchronized
    private fun disconnectAll() {
        live.values.forEach { it.cancel() }
        live.clear()
    }

    private suspend fun listen(serverId: String) {
        val client = servers.tracksideClient(serverId) ?: return
        val server = { servers.entries.value.firstOrNull { it.server.id == serverId }?.server }
        val cloud = CloudSync.of(graph)
        val cloudHere = { cloud?.status?.value?.serverId == serverId }
        var librarySync: Job? = null
        fun syncLibrarySoon() {
            librarySync?.cancel()
            librarySync = graph.scope.launch {
                delay(2_000)
                server()?.let { servers.sync(it) }
            }
        }
        val ending = TracksideEvents(client).run(
            onConnected = {
                // Missed events aren't replayed: ask for the addresses (only if they changed),
                // the library's changes, and the cloud's.
                servers.refreshAddresses()
                syncLibrarySoon()
                if (cloudHere()) cloud?.pull(null)
            },
            onEvent = { event ->
                when (event.type) {
                    "changed", "resync" -> syncLibrarySoon()
                    "server_urls" -> servers.refreshAddresses()
                    "cloud" -> if (cloudHere()) cloud?.pull(event.cloudEntity)
                }
            },
        )
        // Signed out: the library's sync finds out and asks to pair again.
        if (ending == TracksideEvents.Ending.SignedOut) server()?.let { servers.sync(it) }
    }

    companion object {
        private const val SEND = "trackside-listens"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ListenWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(SEND, ExistingWorkPolicy.KEEP, request) }
        }

        private val installed = java.util.WeakHashMap<AppGraph, TracksideServices>()

        fun install(graph: AppGraph, servers: ServerLibrary): TracksideServices =
            synchronized(installed) { installed.getOrPut(graph) { TracksideServices(graph, servers) } }

        fun of(graph: AppGraph): TracksideServices? = synchronized(installed) { installed[graph] }
    }
}

/** Sends the listen outbox when there's a network, trying again later while a server can't be reached. */
class ListenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = TracksideServices.of(applicationContext.graph) ?: return Result.success()
        return when (services.outbox.drain()) {
            ListenOutbox.Outcome.Done -> Result.success()
            ListenOutbox.Outcome.RetryLater -> if (runAttemptCount < 12) Result.retry() else Result.success()
        }
    }
}

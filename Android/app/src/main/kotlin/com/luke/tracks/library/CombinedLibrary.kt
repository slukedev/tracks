package com.luke.tracks.library

import android.content.Context
import com.luke.tracks.engine.Engine
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Your Music: the phone's files, your servers, and in sample data mode a sample library,
 * merged into one [LibrarySnapshot] with lookups by identity for the history, mixes and radio.
 */
class CombinedLibrary(
    context: Context,
    private val scope: CoroutineScope,
    val device: DeviceLibrary,
    val servers: ServerLibrary,
    private val engine: Engine,
    private val sampleData: Boolean,
    /** False on TV: the device's files and servers are never read there. */
    private val localSources: Boolean = true,
) : MusicLibrary {
    private val sampleTracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    private val sampleFolder = File(context.cacheDir, "sample-music")
    private val _index = MutableStateFlow(LibraryIndex.Empty)

    /** The snapshot with its lookups; replaced whole whenever a source changes. */
    override val index: StateFlow<LibraryIndex> = _index.asStateFlow()

    private val _snapshot = MutableStateFlow(LibraryIndex.Empty.snapshot)
    override val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        if (localSources) {
            if (!sampleData) device.start()
            servers.start()
        }
        if (sampleData) {
            // The sample history fills in the background at launch; build once it's there.
            scope.launch {
                engine.revision.filter { it > 0 }.debounce(300).collect {
                    if (sampleTracks.value.isEmpty()) sampleTracks.value = SampleLibrary(engine, sampleFolder).tracks()
                }
            }
        }
        scope.launch {
            val none = flowOf(emptyList<LibraryTrack>())
            val off = flowOf(false)
            combine(
                if (localSources) device.tracks else none,
                if (localSources) device.hasPermission else off,
                if (localSources) device.loading else off,
                if (localSources) servers.tracks else none,
                if (localSources) servers.loading else off,
            ) { local, allowed, localLoading, remote, remoteLoading ->
                Sources(local, allowed, localLoading && !sampleData, remote, remoteLoading)
            }.combine(sampleTracks) { sources, samples -> sources to samples }
                .conflate()
                .collect { (sources, samples) ->
                    val built = withContext(Dispatchers.Default) {
                        LibraryIndex.build(
                            tracks = samples + sources.local + sources.remote,
                            hasPermission = sampleData || sources.allowed,
                            isLoading = sources.localLoading || sources.remoteLoading,
                        )
                    }
                    _index.value = built
                    _snapshot.value = built.snapshot
                }
        }
    }

    private data class Sources(
        val local: List<LibraryTrack>,
        val allowed: Boolean,
        val localLoading: Boolean,
        val remote: List<LibraryTrack>,
        val remoteLoading: Boolean,
    )

    override suspend fun refresh() {
        if (!localSources) return
        if (!sampleData) device.refresh()
        servers.syncAll()
    }

    override fun find(identity: String): PlayableSong? = _index.value.find(identity)

    /** The song a player or browse tree id names. */
    fun song(mediaId: String): PlayableSong? = _index.value.song(mediaId)

    override suspend fun search(text: String): List<PlayableSong> =
        withContext(Dispatchers.Default) { _index.value.search(text) }

    /** Waits for the first full read, for a cold start from Bluetooth or Android Auto. */
    suspend fun ready(): LibraryIndex = index.map { it }.first { !it.snapshot.isLoading }
}

package com.luke.tracks.engine

import android.util.Log
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.tracks_engine.HistoryIndex
import uniffi.tracks_engine.Period
import uniffi.tracks_engine.Play
import uniffi.tracks_engine.PlayKind
import uniffi.tracks_engine.PlaySource
import java.util.TimeZone

/**
 * The shared Rust engine, as the app sees it: every chart, total, timeline, search and mix,
 * read off the main thread.
 *
 * [revision] ticks after every write, so screens re-read when the history changes.
 */
class Engine(
    path: String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val index: HistoryIndex = try {
        if (path == null) HistoryIndex.inMemory() else HistoryIndex.open(path)
    } catch (error: Exception) {
        Log.e("Tracks", "Engine index failed to open; using memory", error)
        HistoryIndex.inMemory()
    }

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    init {
        runCatching { index.setTimeZone(TimeZone.getDefault().id) }
    }

    /** Follows the system time zone when it changes. */
    fun timeZoneChanged() {
        runCatching { index.setTimeZone(TimeZone.getDefault().id) }
        _revision.value++
    }

    suspend fun <T> read(block: HistoryIndex.() -> T): T = withContext(io) { index.block() }

    suspend fun upsert(plays: List<PlayEntity>) = withContext(io) {
        index.upsert(plays.map { it.toEnginePlay() }, null)
        _revision.value++
    }

    suspend fun remove(ids: List<String>) = withContext(io) {
        index.remove(ids, null)
        _revision.value++
    }

    /** Brings the index to exactly [plays], writing only what changed. */
    suspend fun sync(plays: List<PlayEntity>) = withContext(io) {
        val engine = plays.map { it.toEnginePlay() }
        if (index.needsRebuild()) index.replaceAll(engine, null) else index.sync(engine, null)
        _revision.value++
    }

    suspend fun optimize() = withContext(io) { runCatching { index.optimize() } }

    companion object {
        val allTime = Period(null, null)
    }
}

fun PlayEntity.toEnginePlay(): Play = Play(
    id = id,
    catalogId = mediaId,
    songKey = "",
    title = title,
    artistName = artistName,
    albumTitle = albumTitle,
    artworkUrl = artworkUri,
    playedAtMs = playedAt,
    kind = when (kind) {
        PlayKindValue.Radio -> PlayKind.RADIO
        PlayKindValue.OnDemand -> PlayKind.ON_DEMAND
        PlayKindValue.Imported -> PlayKind.IMPORTED
        PlayKindValue.LastFm -> PlayKind.LAST_FM
    },
    source = when (source) {
        PlaySourceValue.Streaming -> PlaySource.AppleMusic
        PlaySourceValue.YourMusic -> PlaySource.YourMusic
        PlaySourceValue.Spotify -> PlaySource.Spotify
        PlaySourceValue.Other -> PlaySource.Unknown(com.luke.tracks.sync.CloudPlays.source(this))
    },
    stationName = stationName,
)

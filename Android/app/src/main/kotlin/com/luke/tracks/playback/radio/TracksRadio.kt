package com.luke.tracks.playback.radio

import android.util.Log
import com.luke.tracks.AppGraph
import com.luke.tracks.engine.Engine
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.RadioListen
import com.luke.tracks.playback.RadioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.tracks_engine.Listen
import uniffi.tracks_engine.RadioCandidate
import uniffi.tracks_engine.RadioStation
import uniffi.tracks_engine.SourceFilter
import uniffi.tracks_engine.foldedName
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Tracks Radio on Android: every song in your music, weighted by how much and how lately you
 * play it, with the songs you've never played as its new finds. The engine's `RadioStation`
 * does the picking (the same picker the iPhone uses), keeps artists apart and learns from skips.
 */
class TracksRadio(private val graph: AppGraph) : RadioSource {
    private val lock = Mutex()
    private var station: RadioStation? = null
    private var songs: Map<String, PlayableSong> = emptyMap()

    override suspend fun start(): List<PlayableSong> = lock.withLock {
        val settings = graph.settings.settings.first()
        val library = graph.library.snapshot.value.songs
        val hidden = settings.hiddenSongs
        // One playable copy per song: the phone's own file wins over a server's.
        val byIdentity = LinkedHashMap<String, PlayableSong>()
        for (song in library) if (song.identity !in hidden) byIdentity.putIfAbsent(song.identity, song)
        if (byIdentity.isEmpty()) return@withLock emptyList()

        val candidates = withContext(Dispatchers.Default) { candidates(byIdentity.values.toList()) }
        station?.close()
        station = RadioStation(candidates, settings.radioAdventure.toDouble().coerceIn(0.0, 1.0), Random.nextLong().toULong())
        songs = byIdentity
        pick(6)
    }

    override suspend fun more(count: Int): List<PlayableSong> = lock.withLock {
        if (station == null) return@withLock emptyList()
        pick(count)
    }

    override fun note(song: PlayableSong, listen: RadioListen) {
        val engineListen = when (listen) {
            RadioListen.Skipped -> Listen.SKIPPED
            RadioListen.MovedOn -> Listen.MOVED_ON
            RadioListen.Finished -> Listen.FINISHED
        }
        runCatching { station?.note(song.identity, engineListen) }
    }

    private fun pick(count: Int): List<PlayableSong> {
        val ids = runCatching { station?.nextBatch(count.toUInt()).orEmpty() }
            .onFailure { Log.w("Tracks", "Radio pick failed", it) }
            .getOrDefault(emptyList())
        return ids.mapNotNull(songs::get)
    }

    /**
     * Your songs as the station sees them: a song you play a lot and lately weighs most, one
     * you played once long ago still has a chance, and one you've never played is a new find.
     */
    private suspend fun candidates(library: List<PlayableSong>): List<RadioCandidate> {
        val now = System.currentTimeMillis()
        val stats = graph.engine.read {
            songChart(Engine.allTime, null, SourceFilter.ALL, 20_000u).rows.associate { it.song.identity to it.song }
        }
        return library.map { song ->
            val played = stats[song.identity]
            val artistKey = runCatching { foldedName(song.artistName) }.getOrDefault(song.artistName.lowercase())
            if (played == null) {
                RadioCandidate(song.identity, artistKey, 1.0, isNew = true)
            } else {
                val daysSince = (now - played.lastPlayedMs).coerceAtLeast(0) / 86_400_000.0
                // Plays count on a log scale, and fade over about two months without a listen,
                // but never below a third: an old love still turns up.
                val recency = 0.33 + 0.67 * exp(-daysSince / 60.0)
                val weight = (1.0 + ln(1.0 + played.plays.toDouble())) * recency
                RadioCandidate(song.identity, artistKey, weight, isNew = false)
            }
        }
    }
}

package com.luke.tracks.ui.play

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.AppGraph
import com.luke.tracks.engine.Engine
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.ui.insights.Identities
import uniffi.tracks_engine.ArtistStats
import uniffi.tracks_engine.Mix
import uniffi.tracks_engine.MixSet
import uniffi.tracks_engine.Period
import uniffi.tracks_engine.SourceFilter

/** An album you played lately, for Recently played. */
@Immutable
data class RecentAlbum(val identity: String, val title: String, val artistName: String, val artworkUrl: String?)

/** Everything the Play tab shows from your history, read from the engine in one go. */
@Immutable
data class PlayFeed(
    val mixes: MixSet,
    /** Songs you can play here, by identity, for the mixes on the page. */
    val playable: Map<String, PlayableSong>,
    val recentlyPlayed: List<RecentAlbum>,
    val topArtists: List<ArtistStats>,
    val hasHistory: Boolean,
) {
    /** The crate: this hour's mix first, then the rest of today. */
    val crate: List<Mix> get() = listOfNotNull(mixes.rightNow) + mixes.otherTimes

    fun mix(id: String): Mix? =
        (listOfNotNull(mixes.rightNow) + mixes.otherTimes + mixes.mixes).firstOrNull { it.id == id }

    fun playable(mix: Mix): List<PlayableSong> = mix.songs.mapNotNull { playable[it.identity] }
}

private const val DAY = 86_400_000L

/**
 * The Play tab's data, re-read whenever the history, the library or the songs you asked to
 * hear less of change. Null until the first read finishes.
 */
@Composable
fun rememberPlayFeed(): State<PlayFeed?> {
    val graph = LocalContext.current.graph
    val revision by graph.engine.revision.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(null)
    val library by graph.library.snapshot.collectAsStateWithLifecycle()
    val hidden = settings?.hiddenSongs
    return produceState<PlayFeed?>(null, revision, hidden, library) {
        if (hidden == null) return@produceState
        value = runCatching { loadFeed(graph, hidden) }.getOrNull() ?: value
    }
}

suspend fun loadFeed(graph: AppGraph, hidden: Set<String>, nowMs: Long = System.currentTimeMillis()): PlayFeed =
    graph.engine.read {
        val set = mixes(nowMs, hidden.toList())
        val recent = Period(nowMs - 90 * DAY, null)
        val artists = artistChart(recent, null, SourceFilter.ALL, 12u).rows.map { it.artist }
            .ifEmpty { artistChart(Engine.allTime, null, SourceFilter.ALL, 12u).rows.map { it.artist } }
        // The albums of the latest plays, newest first, each once.
        val albums = LinkedHashMap<String, RecentAlbum>()
        for (play in recentPlays(null, 400u)) {
            if (albums.size == 12) break
            val identity = Identities.album(play.albumTitle, play.artistName, play.title) ?: continue
            if (identity !in albums) albums[identity] = RecentAlbum(identity, play.albumTitle!!, play.artistName, play.artworkUrl)
        }
        val identities = HashSet<String>()
        (listOfNotNull(set.rightNow) + set.otherTimes + set.mixes).forEach { mix -> mix.songs.forEach { identities += it.identity } }
        val playable = HashMap<String, PlayableSong>()
        for (identity in identities) graph.library.find(identity)?.let { playable[identity] = it }
        PlayFeed(
            mixes = set,
            playable = playable,
            recentlyPlayed = albums.values.toList(),
            topArtists = artists,
            hasHistory = playCount() > 0u,
        )
    }

package com.luke.tracks.ui.play

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.AppGraph
import com.luke.tracks.engine.Engine
import com.luke.tracks.graph
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.net.Logs
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import uniffi.tracks_engine.ArtistStats
import uniffi.tracks_engine.ShuffleItem
import uniffi.tracks_engine.dailySeed
import uniffi.tracks_engine.foldedName
import uniffi.tracks_engine.freshShuffle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import uniffi.tracks_engine.Mix
import uniffi.tracks_engine.MixSet
import uniffi.tracks_engine.Period
import uniffi.tracks_engine.SourceFilter

/** Everything the Play tab shows from your history and your music, read in one go. */
@Immutable
data class PlayFeed(
    val mixes: MixSet,
    /** Songs you can play here, by identity, for the mixes on the page. */
    val playable: Map<String, PlayableSong>,
    /** Your albums, most lately heard first. */
    val recentlyPlayed: List<LibraryAlbum>,
    val topArtists: List<ArtistStats>,
    /** Your songs you've never played. */
    val waiting: List<PlayableSong> = emptyList(),
    val hasHistory: Boolean,
) {
    /** The mixes for their shelf: ones with at least five songs you can play here. */
    val shelfMixes: List<Mix> get() = mixes.mixes.filter { playable(it).size >= 5 }

    /** The crate: this hour's mix first, then the rest of today. */
    val crate: List<Mix> get() = listOfNotNull(mixes.rightNow) + mixes.otherTimes

    fun mix(id: String): Mix? =
        (listOfNotNull(mixes.rightNow) + mixes.otherTimes + mixes.mixes).firstOrNull { it.id == id }

    fun playable(mix: Mix): List<PlayableSong> = mix.songs.mapNotNull { playable[it.identity] }

    companion object {
        /** Your listening couldn't be read: nothing from it, and no "No mixes yet" either. */
        val Unread = PlayFeed(MixSet(null, emptyList(), emptyList()), emptyMap(), emptyList(), emptyList(), hasHistory = true)
    }

    /** The feed kept to songs that pass [keep]: one source's, when Play is filtered. */
    fun only(keep: (PlayableSong) -> Boolean): PlayFeed = copy(
        playable = playable.filterValues(keep),
        recentlyPlayed = recentlyPlayed.mapNotNull { album -> album.songs.filter(keep).takeIf { it.isNotEmpty() }?.let { album.copy(songs = it) } },
        waiting = waiting.filter(keep),
    )
}

private const val DAY = 86_400_000L

/**
 * The Play tab's data, re-read whenever the history, the library or the songs you asked to
 * hear less of change. Null until the first read finishes. Each read finishes before the next
 * starts, so a history that keeps changing (a sync bringing plays in) can't keep the page
 * waiting, and a read that fails leaves the page with what it had, or an empty feed.
 */
@Composable
fun rememberPlayFeed(): State<PlayFeed?> {
    val graph = LocalContext.current.graph
    val clock = com.luke.tracks.ui.components.LocalClock.current
    val feed = remember(graph, clock) { playFeed(graph, clock) }
    return feed.collectAsStateWithLifecycle(null)
}

fun playFeed(graph: AppGraph, clock: () -> Long): Flow<PlayFeed> = flow {
    var last: PlayFeed? = null
    combine(graph.engine.revision, graph.settings.settings.map { it.hiddenSongs }.distinctUntilChanged(), graph.library.snapshot) { _, hidden, _ -> hidden }
        .conflate()
        .collect { hidden ->
            val started = System.currentTimeMillis()
            val read = try {
                loadFeed(graph, hidden, clock())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logs.w("Play: couldn't read your listening", error)
                null
            }
            if (read != null && last == null) {
                Logs.i("Play: read your listening in ${System.currentTimeMillis() - started} ms: ${read.shelfMixes.size} mixes, ${read.recentlyPlayed.size} albums lately" + if (read.hasHistory) "" else ", no plays yet")
            }
            val next = read ?: last ?: PlayFeed.Unread
            last = next
            emit(next)
        }
}

suspend fun loadFeed(graph: AppGraph, hidden: Set<String>, nowMs: Long = System.currentTimeMillis()): PlayFeed =
    graph.engine.read {
        val set = mixes(nowMs, hidden.toList())
        val recent = Period(nowMs - 90 * DAY, null)
        val artists = artistChart(recent, null, SourceFilter.ALL, 12u).rows.map { it.artist }
            .ifEmpty { artistChart(Engine.allTime, null, SourceFilter.ALL, 12u).rows.map { it.artist } }
        val library = graph.library.snapshot.value
        // Your albums by when you last heard any song on them, latest first.
        val lastHeard = HashMap<String, Long>()
        for (play in recentPlays(null, 2000u)) lastHeard.putIfAbsent(play.identity, play.playedAtMs)
        val recentAlbums = library.albums
            .mapNotNull { album -> album.songs.mapNotNull { lastHeard[it.identity] }.maxOrNull()?.let { album to it } }
            .sortedByDescending { it.second }
            .take(15)
            .map { it.first }
        // Your songs you've never played, a fresh order each day.
        val unplayed = library.songs.filter { song(it.identity) == null }.distinctBy { it.identity }
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
        val order = freshShuffle(
            unplayed.map { ShuffleItem(it.identity, foldedName(it.artistName), false) },
            dailySeed(today.year.toShort(), today.monthValue.toByte(), today.dayOfMonth.toByte(), "waiting"),
        )
        val byIdentity = unplayed.associateBy { it.identity }
        val identities = HashSet<String>()
        (listOfNotNull(set.rightNow) + set.otherTimes + set.mixes).forEach { mix -> mix.songs.forEach { identities += it.identity } }
        val playable = HashMap<String, PlayableSong>()
        for (identity in identities) graph.library.find(identity)?.let { playable[identity] = it }
        val yours = library.artists.map { foldedName(it.name) }.toSet()
        PlayFeed(
            mixes = set,
            playable = playable,
            recentlyPlayed = recentAlbums,
            // The artists you play most lately who are in your music.
            topArtists = artists.filter { foldedName(it.name) in yours },
            waiting = order.take(15).mapNotNull { byIdentity[it] },
            hasHistory = playCount() > 0u,
        )
    }

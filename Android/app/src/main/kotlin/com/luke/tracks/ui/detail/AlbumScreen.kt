package com.luke.tracks.ui.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luke.tracks.engine.Engine
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.insights.AllTime
import com.luke.tracks.ui.insights.BarPoint
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.GroupGap
import com.luke.tracks.ui.insights.Identities
import com.luke.tracks.ui.insights.InsightRow
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.PlayShuffleButtons
import com.luke.tracks.ui.insights.SectionHeader
import com.luke.tracks.ui.insights.engineState
import com.luke.tracks.ui.insights.rememberGraph
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import uniffi.tracks_engine.AlbumStats
import uniffi.tracks_engine.Credit
import uniffi.tracks_engine.Scope
import uniffi.tracks_engine.SongStats
import uniffi.tracks_engine.SourceFilter

data class AlbumDetail(
    val stats: AlbumStats,
    val rank: UInt,
    val songs: List<SongStats>,
    val lead: Credit?,
    val firstMs: Long?,
    val lastMs: Long?,
    val timeline: List<BarPoint>,
    val hours: List<Long>,
)

suspend fun loadAlbum(engine: Engine, identity: String, title: String): AlbumDetail? {
    val ranked = engine.read { album(identity) } ?: return null
    val songs = AllTime.songsOn(engine, identity, title)
    val first = songs.minOfOrNull { it.firstPlayedMs }
    val timeline = first?.let { monthlyTimeline(engine, Scope.Album(identity), it) }.orEmpty()
    return engine.read {
        AlbumDetail(
            stats = ranked.album,
            rank = ranked.rank,
            songs = songs,
            lead = songs.firstOrNull()?.let { Identities.leadArtist(it.artistName, it.title) },
            firstMs = first,
            lastMs = songs.maxOfOrNull { it.lastPlayedMs },
            timeline = timeline,
            hours = activity(Engine.allTime, SourceFilter.ALL, Scope.Album(identity)).hours.map { it.toLong() },
        )
    }
}

/** An album: its songs as you play them, plays over time, and when you heard it first. */
@Composable
fun AlbumScreen(identity: String, title: String, artistName: String) {
    val graph = rememberGraph()
    val navigator = LocalNavigator.current
    val detail by engineState(identity) { loadAlbum(it, identity, title) }
    val target = ItemTarget.Album(identity, title, artistName, detail?.stats?.artworkUrl)
    var playable by remember { mutableStateOf<List<PlayableSong>>(emptyList()) }
    LaunchedEffect(detail) {
        playable = detail?.songs?.let { songs -> runCatching { graph.library.findAll(songs.map { it.identity }) }.getOrNull() }.orEmpty()
    }

    DetailScaffold(title, target, loading = detail == null) {
        val d = detail ?: return@DetailScaffold
        item(key = "hero", contentType = "hero") {
            DetailHero(
                target, RoundedCornerShape(36.dp), d.stats.title,
                subtitle = d.stats.artistName,
                onSubtitle = d.lead?.let { lead -> { navigator.open(ArtistRoute(lead.identity, lead.name)) } },
                lines = listOf("No. ${d.rank} of all time"),
            )
        }
        if (playable.isNotEmpty()) {
            item(key = "play", contentType = "play") {
                PlayShuffleButtons(playable, QueueOrigin(d.stats.title, QueueOrigin.Kind.Album), Modifier.padding(bottom = 16.dp))
            }
        }
        item(key = "figures", contentType = "figures") {
            FigureGrid(
                listOf(
                    "Plays" to Format.number(d.stats.plays),
                    "Listening" to Format.compactDuration(d.stats.listenMs),
                    "First heard" to (d.firstMs?.let(::dateOf) ?: "Not yet"),
                    "Last heard" to (d.lastMs?.let(::relativeDay) ?: "Not yet"),
                ),
            )
        }
        timelineSection(d.timeline, d.stats.title)
        if (d.songs.isNotEmpty()) {
            item(key = "songsHeader", contentType = "header") {
                SectionHeader(if (d.songs.size == 1) "Song you played" else "Songs you played", Modifier.padding(top = 20.dp))
            }
            itemsIndexed(d.songs, key = { _, s -> "song-${s.identity}" }, contentType = { _, _ -> "row" }) { i, song ->
                InsightRow(
                    ItemTarget.Song(song.identity, song.title, song.artistName, song.albumTitle, song.artworkUrl),
                    index = i,
                    count = d.songs.size,
                    rank = i + 1,
                    subtitle = song.artistName,
                    modifier = Modifier.animateItem().padding(top = if (i == 0) 0.dp else GroupGap),
                ) { PlaysLabel(song.plays.toLong()) }
            }
        }
        clockSection(d.hours)
    }
}

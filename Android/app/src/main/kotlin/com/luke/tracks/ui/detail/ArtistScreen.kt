package com.luke.tracks.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luke.tracks.engine.Engine
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.insights.AlbumCell
import com.luke.tracks.ui.insights.AllTime
import com.luke.tracks.ui.insights.BarPoint
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.GroupGap
import com.luke.tracks.ui.insights.Identities
import com.luke.tracks.ui.insights.InsightRow
import com.luke.tracks.ui.insights.ItemTarget
import com.luke.tracks.ui.insights.PlayShuffleButtons
import com.luke.tracks.ui.insights.SectionHeader
import com.luke.tracks.ui.insights.bleed
import com.luke.tracks.ui.insights.engineState
import com.luke.tracks.ui.insights.rememberGraph
import uniffi.tracks_engine.AlbumStats
import uniffi.tracks_engine.ArtistStats
import uniffi.tracks_engine.Scope
import uniffi.tracks_engine.SongStats
import uniffi.tracks_engine.SourceFilter

data class ArtistDetail(
    val stats: ArtistStats,
    val rank: UInt,
    val sharePercent: Int,
    val timeline: List<BarPoint>,
    val songs: List<SongStats>,
    val albums: List<AlbumStats>,
    val collaborators: List<ItemTarget.Artist>,
    val hours: List<Long>,
)

suspend fun loadArtist(engine: Engine, identity: String): ArtistDetail? {
    val ranked = engine.read { artist(identity) } ?: return null
    val stats = ranked.artist
    val songs = AllTime.songsBy(engine, identity, stats.name)
    val albums = AllTime.albumsBy(engine, identity)
    val timeline = monthlyTimeline(engine, Scope.Artist(identity), stats.firstPlayedMs)
    return engine.read {
        val total = totals(Engine.allTime, SourceFilter.ALL).listenMs.coerceAtLeast(1)
        val collaborators = songs.flatMap { Identities.credits(it.artistName, it.title) }
            .filter { it.identity != identity }
            .groupingBy { it.identity to it.name }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { ItemTarget.Artist(it.key.first, it.key.second) }
            .distinctBy { it.identity }
            .take(12)
        ArtistDetail(
            stats = stats,
            rank = ranked.rank,
            sharePercent = Math.round(stats.listenMs * 100.0 / total).toInt(),
            timeline = timeline,
            songs = songs,
            albums = albums,
            collaborators = collaborators,
            hours = activity(Engine.allTime, SourceFilter.ALL, Scope.Artist(identity)).hours.map { it.toLong() },
        )
    }
}

/** An artist: how much and since when, plays over time, their songs and albums, and who they play with. */
@Composable
fun ArtistScreen(identity: String, name: String) {
    val graph = rememberGraph()
    val detail by engineState(identity) { loadArtist(it, identity) }
    val target = ItemTarget.Artist(identity, name, detail?.stats?.artworkUrl)
    var showAll by rememberSaveable { mutableStateOf(false) }
    var playable by remember { mutableStateOf<List<PlayableSong>>(emptyList()) }
    LaunchedEffect(detail) {
        playable = detail?.songs?.let { songs -> runCatching { graph.library.findAll(songs.map { it.identity }) }.getOrNull() }.orEmpty()
    }

    DetailScaffold(name, target, loading = detail == null) {
        val d = detail ?: return@DetailScaffold
        item(key = "hero", contentType = "hero") {
            DetailHero(
                target, MaterialShapes.Cookie9Sided.toShape(), d.stats.name,
                lines = listOf(
                    buildList {
                        add("No. ${d.rank} of all time")
                        if (d.sharePercent >= 1) add("${d.sharePercent}% of your listening")
                    }.joinToString(" · "),
                ),
            )
        }
        if (playable.isNotEmpty()) {
            item(key = "play", contentType = "play") {
                PlayShuffleButtons(playable, QueueOrigin(d.stats.name, QueueOrigin.Kind.Artist), Modifier.padding(bottom = 16.dp))
            }
        }
        item(key = "figures", contentType = "figures") {
            FigureGrid(
                listOf(
                    "Plays" to Format.number(d.stats.plays),
                    "Listening" to Format.compactDuration(d.stats.listenMs),
                    "First heard" to dateOf(d.stats.firstPlayedMs),
                    "Last heard" to relativeDay(d.stats.lastPlayedMs),
                ),
            )
        }
        timelineSection(d.timeline, d.stats.name)

        if (d.songs.isNotEmpty()) {
            item(key = "songsHeader", contentType = "header") {
                SectionHeader(
                    if (d.songs.size == 1) "Song" else "Top songs",
                    Modifier.padding(top = 20.dp),
                    action = if (d.songs.size > 5) (if (showAll) "Show less" else "Show all ${d.songs.size}") else null,
                    onAction = { showAll = !showAll },
                )
            }
            val shown = if (showAll) d.songs else d.songs.take(5)
            itemsIndexed(shown, key = { _, s -> "song-${s.identity}" }, contentType = { _, _ -> "row" }) { i, song ->
                InsightRow(
                    ItemTarget.Song(song.identity, song.title, song.artistName, song.albumTitle, song.artworkUrl),
                    index = i,
                    count = shown.size,
                    rank = i + 1,
                    subtitle = song.albumTitle ?: song.artistName,
                    modifier = Modifier.animateItem().padding(top = if (i == 0) 0.dp else GroupGap),
                ) { PlaysLabel(song.plays.toLong()) }
            }
        }

        if (d.albums.isNotEmpty()) {
            item(key = "albumsHeader", contentType = "header") { SectionHeader("Albums", Modifier.padding(top = 20.dp)) }
            item(key = "albums", contentType = "shelf") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.fillMaxWidth().bleed(16.dp),
                ) {
                    items(d.albums, key = { it.identity }) { album ->
                        AlbumCell(ItemTarget.Album(album.identity, album.title, album.artistName, album.artworkUrl), Format.plays(album.plays.toLong()))
                    }
                }
            }
        }
        // Theirs you don't have, where your server or Lidarr can get it.
        item(key = "more", contentType = "more") {
            com.luke.tracks.ui.discover.MoreFromArtist(d.stats.name, Modifier.bleed(16.dp), edge = 20.dp)
        }
        artistShelf("with", "Plays with", d.collaborators)
        clockSection(d.hours)
    }
}

@Composable
fun PlaysLabel(plays: Long) {
    androidx.compose.material3.Text(
        Format.plays(plays),
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

package com.luke.tracks.ui.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
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
import com.luke.tracks.ui.insights.AlbumCell
import com.luke.tracks.ui.insights.BarPoint
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.Identities
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

data class SongDetail(
    val stats: SongStats,
    val rank: UInt?,
    val credits: List<Credit>,
    val album: AlbumStats?,
    val timeline: List<BarPoint>,
    val hours: List<Long>,
)

suspend fun loadSong(engine: Engine, identity: String): SongDetail? {
    val stats = engine.read { song(identity) } ?: return null
    val timeline = monthlyTimeline(engine, Scope.Song(identity), stats.firstPlayedMs)
    return engine.read {
        val albumId = Identities.album(stats.albumTitle, stats.artistName, stats.title)
        SongDetail(
            stats = stats,
            rank = songRank(identity),
            credits = Identities.credits(stats.artistName, stats.title),
            album = albumId?.let { album(it)?.album },
            timeline = timeline,
            hours = activity(Engine.allTime, SourceFilter.ALL, Scope.Song(identity)).hours.map { it.toLong() },
        )
    }
}

/** A song: how often, since when, who's on it, the album it's from and when you play it. */
@Composable
fun SongScreen(identity: String, title: String, artistName: String) {
    val graph = rememberGraph()
    val navigator = LocalNavigator.current
    val detail by engineState(identity) { loadSong(it, identity) }
    val target = ItemTarget.Song(identity, title, artistName, detail?.stats?.albumTitle, detail?.stats?.artworkUrl)
    var playable by remember { mutableStateOf<List<PlayableSong>>(emptyList()) }
    LaunchedEffect(identity) {
        playable = runCatching { listOfNotNull(graph.library.find(identity)) }.getOrDefault(emptyList())
    }

    DetailScaffold(title, target, loading = detail == null) {
        val d = detail ?: return@DetailScaffold
        val lead = d.credits.firstOrNull()
        item(key = "hero", contentType = "hero") {
            DetailHero(
                target, MaterialShapes.Cookie4Sided.toShape(), d.stats.title,
                subtitle = d.stats.artistName,
                onSubtitle = lead?.let { { navigator.open(ArtistRoute(it.identity, it.name)) } },
                lines = listOfNotNull(d.rank?.let { "No. $it of all time" }),
            )
        }
        if (playable.isNotEmpty()) {
            item(key = "play", contentType = "play") {
                PlayShuffleButtons(playable, QueueOrigin(d.stats.title, QueueOrigin.Kind.Other), Modifier.padding(bottom = 16.dp))
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
        timelineSection(d.timeline, d.stats.title)
        if (d.credits.size > 1) {
            artistShelf("credits", "Artists", d.credits.map { ItemTarget.Artist(it.identity, it.name) })
        }
        d.album?.let { album ->
            item(key = "albumHeader", contentType = "header") { SectionHeader("From the album", Modifier.padding(top = 20.dp)) }
            item(key = "album", contentType = "album") {
                AlbumCell(
                    ItemTarget.Album(album.identity, album.title, album.artistName, album.artworkUrl),
                    "${album.artistName} · ${Format.plays(album.plays.toLong())}",
                )
            }
        }
        clockSection(d.hours)
    }
}

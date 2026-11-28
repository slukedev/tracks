package com.luke.tracks.ui.tv.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.detail.dateOf
import com.luke.tracks.ui.detail.loadAlbum
import com.luke.tracks.ui.detail.loadArtist
import com.luke.tracks.ui.detail.loadSong
import com.luke.tracks.ui.detail.relativeDay
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.insights.Identities
import com.luke.tracks.ui.insights.engineState
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSectionTitle
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import uniffi.tracks_engine.SongStats

/*
 * The pages for what the history knows: an album, an artist or a song you've played, with how
 * much and since when, and what of it plays here.
 */

@Composable
fun TvAlbumScreen(identity: String, title: String, artistName: String) {
    val memory = rememberFocusMemory()
    val detail by engineState(identity) { loadAlbum(it, identity, title) }
    val index by rememberLibraryIndex()
    val d = detail
    if (d == null) {
        memory.ClaimFocus(null)
        TvDetailSkeleton()
        return
    }
    val navigator = LocalTvNavigator.current
    val playable = remember(d, index) { d.songs.playableIn(index) }
    StatsPage(
        memory = memory,
        art = { TvArtwork(d.stats.artworkUrl, d.stats.title + d.stats.artistName, Modifier.fillMaxWidth(), sizePx = TvArtwork.HeroPx, shape = RoundedCornerShape(16.dp)) },
        title = d.stats.title,
        subtitle = d.stats.artistName,
        facts = listOf("No. ${d.rank} of All Time", Format.plays(d.stats.plays.toLong()), Format.compactDuration(d.stats.listenMs)).joinToString(" · "),
        playable = playable,
        origin = QueueOrigin(d.stats.title, QueueOrigin.Kind.Album),
        extra = d.lead?.let { lead -> listOf(Triple("Artist", Icons.Rounded.Person) { navigator.open(ArtistRoute(lead.identity, lead.name)) }) }.orEmpty(),
        songsTitle = if (d.songs.size == 1) "Song You Played" else "Songs You Played",
        songs = d.songs,
        index = index,
    )
}

@Composable
fun TvArtistScreen(identity: String, name: String) {
    val memory = rememberFocusMemory()
    val detail by engineState(identity) { loadArtist(it, identity) }
    val index by rememberLibraryIndex()
    val d = detail
    if (d == null) {
        memory.ClaimFocus(null)
        TvDetailSkeleton(circle = true)
        return
    }
    val navigator = LocalTvNavigator.current
    val playable = remember(d, index) { d.songs.playableIn(index) }
    StatsPage(
        memory = memory,
        art = { TvArtwork(d.stats.artworkUrl, d.stats.name, Modifier.fillMaxWidth(), sizePx = TvArtwork.HeroPx, shape = CircleShape, showInitials = false) },
        title = d.stats.name,
        subtitle = null,
        facts = buildList {
            add("No. ${d.rank} of All Time")
            add(Format.plays(d.stats.plays.toLong()))
            if (d.sharePercent >= 1) add("${d.sharePercent}% of Your Listening")
        }.joinToString(" · "),
        playable = playable,
        origin = QueueOrigin(d.stats.name, QueueOrigin.Kind.Artist),
        extra = emptyList(),
        songsTitle = "Top Songs",
        songs = d.songs.take(20),
        index = index,
        after = {
            if (d.albums.isNotEmpty()) item(key = "albums") {
                TvShelf(
                    "Albums", d.albums, key = { "album:${it.identity}" }, focusMemory = memory,
                    contentPadding = PaddingValues(start = RowInset), modifier = Modifier.padding(top = 24.dp),
                ) { album ->
                    TvCoverCard(
                        album.artworkUrl, album.title + album.artistName, album.title,
                        onClick = { navigator.open(AlbumRoute(album.identity, album.title, album.artistName)) },
                        subtitle = Format.plays(album.plays.toLong()), width = 160.dp,
                    )
                }
            }
        },
    )
}

@Composable
fun TvSongScreen(identity: String, title: String, artistName: String) {
    val memory = rememberFocusMemory()
    val detail by engineState(identity) { loadSong(it, identity) }
    val index by rememberLibraryIndex()
    val d = detail
    if (d == null) {
        memory.ClaimFocus(null)
        TvDetailSkeleton()
        return
    }
    val navigator = LocalTvNavigator.current
    val here = remember(d, index) { index.find(identity) }
    val albumIdentity = remember(d) { Identities.album(d.stats.albumTitle, d.stats.artistName, d.stats.title) }
    val lead = d.credits.firstOrNull()
    StatsPage(
        memory = memory,
        art = { TvArtwork(here?.artworkUri ?: d.stats.artworkUrl, (d.stats.albumTitle ?: title) + artistName, Modifier.fillMaxWidth(), sizePx = TvArtwork.HeroPx, shape = RoundedCornerShape(16.dp)) },
        title = d.stats.title,
        subtitle = d.stats.artistName,
        facts = listOfNotNull(
            d.rank?.let { "No. $it of All Time" },
            Format.plays(d.stats.plays.toLong()),
            "First Heard ${dateOf(d.stats.firstPlayedMs)}",
            "Last Heard ${relativeDay(d.stats.lastPlayedMs)}",
        ).joinToString(" · "),
        playable = listOfNotNull(here),
        origin = null,
        extra = listOfNotNull(
            lead?.let { Triple("Artist", Icons.Rounded.Person) { navigator.open(ArtistRoute(it.identity, it.name)) } },
            d.album?.let { album -> Triple("Album", Icons.Rounded.Album) { navigator.open(AlbumRoute(albumIdentity ?: album.identity, album.title, album.artistName)) } },
        ),
        songsTitle = null,
        songs = emptyList(),
        index = index,
    )
}

private fun List<SongStats>.playableIn(index: LibraryIndex): List<PlayableSong> = mapNotNull { index.find(it.identity) }

/**
 * The page all three share: art, heading and actions, then the songs you've played, each
 * playing from there when it plays here and opening its own page otherwise.
 */
@Composable
private fun StatsPage(
    memory: FocusMemory,
    art: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    facts: String,
    playable: List<PlayableSong>,
    origin: QueueOrigin?,
    extra: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>,
    songsTitle: String?,
    songs: List<SongStats>,
    index: LibraryIndex,
    after: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
) {
    val navigator = LocalTvNavigator.current
    val pending = rememberPendingPlay()
    val playing by rememberCurrentMediaId()
    val first = when {
        playable.isNotEmpty() -> "play"
        extra.isNotEmpty() -> "extra:0"
        songs.isNotEmpty() -> "song:${songs.first().identity}"
        else -> null
    }
    memory.ClaimFocus(first?.let(memory::requester))
    TvDetailLayout(art = art) {
        item(key = "heading") { TvDetailHeading(title, subtitle, facts) }
        if (playable.isNotEmpty() || extra.isNotEmpty()) item(key = "actions") {
            Row(Modifier.padding(start = RowInset, top = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (playable.isNotEmpty()) {
                    TvButton(
                        "Play", { pending.play("play", playable, 0, origin ?: QueueOrigin(title, QueueOrigin.Kind.Other)) },
                        Modifier.rememberFocus(memory, "play"), icon = Icons.Rounded.PlayArrow, style = TvButtonStyle.Primary, pending = pending.isPending("play"),
                    )
                    if (playable.size > 1) TvButton(
                        "Shuffle", { pending.play("shuffle", playable, 0, origin ?: QueueOrigin(title, QueueOrigin.Kind.Other), shuffle = true) },
                        Modifier.rememberFocus(memory, "shuffle"), icon = Icons.Rounded.Shuffle, pending = pending.isPending("shuffle"),
                    )
                }
                extra.forEachIndexed { i, (label, icon, go) ->
                    TvButton(label, go, Modifier.rememberFocus(memory, "extra:$i"), icon = icon)
                }
            }
        }
        if (songsTitle != null && songs.isNotEmpty()) {
            item(key = "songsTitle") { TvSectionTitle(songsTitle, Modifier.padding(start = RowInset, top = 8.dp, bottom = 4.dp)) }
            itemsIndexed(songs, key = { _, s -> "song:${s.identity}" }) { i, song ->
                val here = index.find(song.identity)
                TvListRow(
                    onClick = {
                        if (here != null) pending.play("song:${song.identity}", playable, playable.indexOf(here), origin ?: QueueOrigin(title, QueueOrigin.Kind.Other))
                        else navigator.open(SongRoute(song.identity, song.title, song.artistName))
                    },
                    headline = song.title,
                    supporting = song.artistName,
                    dense = true,
                    selected = here != null && here.mediaId == playing,
                    modifier = Modifier.rememberFocus(memory, "song:${song.identity}"),
                    leading = {
                        Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp, 24.dp))
                    },
                    trailing = { Text(Format.plays(song.plays.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
        after()
    }
}

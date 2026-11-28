package com.luke.tracks.ui.tv.search

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.streaming.ServiceSection
import com.luke.tracks.streaming.StreamingState
import com.luke.tracks.ui.services.cover
import com.luke.tracks.ui.services.playStation
import com.luke.tracks.ui.services.playTracks
import com.luke.tracks.ui.services.route
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.detail.PendingPlay
import com.luke.tracks.ui.tv.detail.TvRowScroll
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout

/** What a TV search asks the services for: stations too, which TV plays from their card. */
internal val TvServiceTypes = listOf("track", "album", "artist", "playlist", "radio")

/**
 * Each service's part of a search as shelves: its songs and stations play from the card, its
 * albums, artists and playlists open their page. A kind it has nothing of is left out.
 */
internal fun LazyListScope.tvServiceSections(
    sections: List<ServiceSection>,
    streaming: StreamingState,
    memory: FocusMemory,
    pending: PendingPlay,
    opened: () -> Unit,
) {
    val many = streaming.servers.size > 1
    sections.forEach { section ->
        val serverId = section.key.serverId
        val prefix = "svc:${section.key.raw}"
        val place = section.service.name + (streaming.server(serverId)?.serverName?.takeIf { many }?.let { " on $it" } ?: "")
        val songs = section.tracks.filter { it.canPlay }
        if (songs.isNotEmpty()) item(key = "$prefix:songs", contentType = "shelf") {
            val context = LocalContext.current
            TvRowScroll { TvShelf("Songs · $place", songs, key = { "$prefix:song:${it.ref}" }, focusMemory = memory) { track ->
                val key = "$prefix:song:${track.ref}"
                TvCoverCard(
                    cover(serverId, track.artworkId), (track.album ?: track.title) + track.artist, track.title,
                    onClick = {
                        opened()
                        pending.run(key) { context.playTracks(serverId, songs, songs.indexOf(track), QueueOrigin("Search", QueueOrigin.Kind.Search)) }
                    },
                    subtitle = track.artist, pending = pending.isPending(key),
                )
            } }
        }
        if (section.albums.isNotEmpty()) item(key = "$prefix:albums", contentType = "shelf") {
            ItemShelf("Albums · $place", serverId, prefix, section.albums, memory, opened) { it.artist ?: it.year?.toString() }
        }
        if (section.artists.isNotEmpty()) item(key = "$prefix:artists", contentType = "shelf") {
            ItemShelf("Artists · $place", serverId, prefix, section.artists, memory, opened) { null }
        }
        if (section.playlists.isNotEmpty()) item(key = "$prefix:playlists", contentType = "shelf") {
            ItemShelf("Playlists · $place", serverId, prefix, section.playlists, memory, opened) { it.owner }
        }
        if (section.stations.isNotEmpty()) item(key = "$prefix:stations", contentType = "shelf") {
            val context = LocalContext.current
            TvRowScroll { TvShelf("Stations · $place", section.stations, key = { "$prefix:station:${it.ref}" }, focusMemory = memory) { station ->
                val key = "$prefix:station:${station.ref}"
                TvCoverCard(
                    cover(serverId, station.artworkId), station.name, station.name,
                    onClick = { opened(); pending.run(key) { playStation(context, serverId, station) } },
                    pending = pending.isPending(key),
                )
            } }
        }
    }
}

/** Albums, artists or playlists, each opening its page. */
@Composable
private fun ItemShelf(
    title: String,
    serverId: String,
    prefix: String,
    items: List<TracksideCatalogItem>,
    memory: FocusMemory,
    opened: () -> Unit,
    subtitle: (TracksideCatalogItem) -> String?,
) {
    val navigator = LocalTvNavigator.current
    TvRowScroll { TvShelf(title, items, key = { "$prefix:${it.kind}:${it.ref}" }, focusMemory = memory) { item ->
        val artist = item.kindValue == TracksideCatalogItem.Kind.Artist
        TvCoverCard(
            cover(serverId, item.artworkId), item.name + item.artist.orEmpty(), item.name,
            onClick = { opened(); navigator.open(item.route(serverId)) },
            subtitle = subtitle(item),
            width = if (artist) 156.dp else TvLayout.CardWidth,
            circle = artist,
        )
    } }
}

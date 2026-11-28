package com.luke.tracks.ui.tv.detail

import androidx.compose.material.icons.rounded.Radio
import com.luke.tracks.streaming.ServiceBrand
import com.luke.tracks.streaming.ServiceKey
import com.luke.tracks.ui.services.startRadioFrom
import com.luke.tracks.ui.services.streamingState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogPage
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.navigation.ServiceItemRoute
import com.luke.tracks.ui.services.cover
import com.luke.tracks.ui.services.kindLabel
import com.luke.tracks.ui.services.playStation
import com.luke.tracks.ui.services.playableFor
import com.luke.tracks.ui.services.rememberStreaming
import com.luke.tracks.ui.services.route
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import kotlinx.coroutines.CancellationException

/** What a streaming service's page holds while it loads, once it has, or why it couldn't. */
private sealed interface ItemPage {
    data object Loading : ItemPage
    data class Loaded(val page: TracksideCatalogPage) : ItemPage
    data class Failed(val title: String, val canRetry: Boolean) : ItemPage
}

/**
 * An album, playlist, artist or station on one of your server's streaming services, laid out
 * as the library's pages are: its art, Play and Shuffle, then its songs. A station just plays.
 */
@Composable
fun TvServiceItemScreen(route: ServiceItemRoute) {
    val repository = rememberStreaming()?.repository
    TvServiceItemScreen(route, repository?.let { { it.item(route.serverId, route.ref, limit = PAGE) } })
}

/** The page with [load] reading it, off the main thread; null when there's no server to ask. */
@Composable
internal fun TvServiceItemScreen(route: ServiceItemRoute, load: (suspend () -> TracksideCatalogPage)?) {
    val memory = rememberFocusMemory()
    var retry by remember { mutableIntStateOf(0) }
    var page by remember(route) { mutableStateOf<ItemPage>(ItemPage.Loading) }
    LaunchedEffect(route, retry) {
        page = ItemPage.Loading
        page = if (load == null) ItemPage.Failed("Couldn't Load ${route.name}", canRetry = false) else try {
            ItemPage.Loaded(load())
        } catch (error: CancellationException) {
            throw error
        } catch (error: TracksideError.NotFound) {
            ItemPage.Failed("${route.name} Isn't Available", canRetry = false)
        } catch (_: Exception) {
            ItemPage.Failed("Couldn't Load ${route.name}", canRetry = true)
        }
    }
    when (val shown = page) {
        ItemPage.Loading -> {
            memory.ClaimFocus(null)
            TvDetailSkeleton(circle = route.kind == "artist")
        }
        is ItemPage.Failed -> {
            val action = remember { FocusRequester() }
            val navigator = LocalTvNavigator.current
            memory.ClaimFocus(action)
            TvEmptyState(
                shown.title,
                actionLabel = if (shown.canRetry) "Try Again" else "Back",
                onAction = { if (shown.canRetry) retry++ else navigator.back(railFocused = false) },
                focusRequester = action,
            )
        }
        is ItemPage.Loaded -> {
            val item = shown.page.item
            if (item?.kindValue == TracksideCatalogItem.Kind.Radio) StationPage(route.serverId, item, memory)
            else ItemSongsPage(route, shown.page, memory)
        }
    }
}

@Composable
private fun ItemSongsPage(route: ServiceItemRoute, page: TracksideCatalogPage, memory: FocusMemory) {
    val context = LocalContext.current
    val navigator = LocalTvNavigator.current
    val item = page.item
    val track = page.track
    val kind = item?.kindValue
    val name = item?.name ?: track?.title ?: route.name
    val tracks = page.tracks.ifEmpty { page.topTracks }.ifEmpty { listOfNotNull(track) }.filter { it.canPlay }
    val songs = remember(route.serverId, tracks) { tracks.map { context.playableFor(route.serverId, it) } }
    val origin = remember(name, kind) {
        QueueOrigin(
            name,
            when (kind) {
                TracksideCatalogItem.Kind.Playlist -> QueueOrigin.Kind.Playlist
                TracksideCatalogItem.Kind.Artist -> QueueOrigin.Kind.Artist
                else -> QueueOrigin.Kind.Album
            },
        )
    }
    val artist = kind == TracksideCatalogItem.Kind.Artist
    val streaming = streamingState()
    val serviceId = item?.serviceId ?: track?.serviceId.orEmpty()
    val service = streaming.service(ServiceKey(route.serverId, serviceId))
    // A radio from the page's first song: the service's own where it can make one, else Tracks Radio.
    val radio = tracks.firstOrNull()?.let { seed -> Triple("Radio", Icons.Rounded.Radio, { context.startRadioFrom(route.serverId, seed) }) }
    SongsPage(
        memory = memory,
        art = {
            TvArtwork(
                cover(route.serverId, item?.artworkId ?: track?.artworkId, TvArtwork.HeroPx),
                name + (item?.artist ?: track?.artist).orEmpty(),
                Modifier.fillMaxWidth(),
                sizePx = TvArtwork.HeroPx,
                shape = if (artist) CircleShape else RoundedCornerShape(16.dp),
                showInitials = !artist,
            )
        },
        title = name,
        subtitle = item?.artist ?: item?.owner ?: track?.artist,
        facts = listOfNotNull(
            item?.kindLabel()?.takeUnless { artist },
            item?.year?.toString(),
            (item?.trackCount ?: songs.size.takeIf { it > 0 })?.let { Format.songs(it.toLong()) }.takeUnless { artist },
            service?.let { ServiceBrand.quality(it) },
        ).joinToString(" · "),
        songs = songs,
        origin = origin,
        numbered = kind == TracksideCatalogItem.Kind.Album,
        extra = listOfNotNull(radio),
        before = {
            if (page.albums.isNotEmpty()) item(key = "albums") {
                TvShelf(
                    "Albums", page.albums, key = { "album:${it.ref}" }, focusMemory = memory,
                    contentPadding = PaddingValues(start = RowInset), modifier = Modifier.padding(bottom = 8.dp),
                ) { album ->
                    TvCoverCard(
                        cover(route.serverId, album.artworkId), album.name + album.artist.orEmpty(), album.name,
                        onClick = { navigator.open(album.route(route.serverId)) },
                        subtitle = album.year?.toString(), width = 160.dp,
                    )
                }
            }
        },
        songsTitle = if (artist) "Top Songs" else null,
    )
}

/** A station: its art and name, and Play. */
@Composable
private fun StationPage(serverId: String, station: TracksideCatalogItem, memory: FocusMemory) {
    val context = LocalContext.current
    val pending = rememberPendingPlay()
    memory.ClaimFocus(memory.requester("play"))
    TvDetailLayout(
        art = { TvArtwork(cover(serverId, station.artworkId, TvArtwork.HeroPx), station.name, Modifier.fillMaxWidth(), sizePx = TvArtwork.HeroPx, shape = RoundedCornerShape(16.dp)) },
    ) {
        item(key = "heading") { TvDetailHeading(station.name, null, station.kindLabel()) }
        item(key = "actions") {
            Row(Modifier.padding(start = RowInset, top = 20.dp, bottom = 16.dp)) {
                TvButton(
                    "Play", { pending.run("play") { playStation(context, serverId, station) } },
                    Modifier.rememberFocus(memory, "play"), icon = Icons.Rounded.PlayArrow, style = TvButtonStyle.Primary, pending = pending.isPending("play"),
                )
            }
        }
    }
}

/** As many of a playlist's songs as a TV page lists at once. */
private const val PAGE = 100

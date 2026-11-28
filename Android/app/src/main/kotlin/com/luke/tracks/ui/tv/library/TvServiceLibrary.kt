package com.luke.tracks.ui.tv.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luke.tracks.library.trackside.CatalogCapability
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.streaming.ServiceBrand
import com.luke.tracks.streaming.ServiceKey
import com.luke.tracks.streaming.StreamingState
import com.luke.tracks.ui.services.ServiceLibrary
import com.luke.tracks.ui.services.cover
import com.luke.tracks.ui.services.playTracks
import com.luke.tracks.ui.services.route
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.TvShelfSkeleton
import com.luke.tracks.ui.tv.detail.TvRowScroll
import com.luke.tracks.ui.tv.detail.TvShelfScroll
import com.luke.tracks.ui.tv.detail.rememberPendingPlay
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout
import kotlinx.coroutines.CancellationException

/** A service whose saved library has a tab of its own in Library. */
data class TvServiceTab(val key: ServiceKey, val service: TracksideCatalogService, val name: String) {
    val id: String get() = "svc:${key.raw}"
}

/**
 * The services Library shows a tab for: each one keeping a library, on a server this TV plays
 * through, signed out too (its tab says what to do). Two of a kind are told apart by server.
 */
fun StreamingState.libraryTabs(): List<TvServiceTab> {
    val found = servers.filter { it.plays }.flatMap { server ->
        server.services.filter { it.can(CatalogCapability.LIBRARY) }.map { server to it }
    }
    val kinds = found.groupingBy { ServiceBrand.name(it.second) }.eachCount()
    return found.map { (server, service) ->
        val name = ServiceBrand.name(service)
        TvServiceTab(ServiceKey(server.serverId, service.id), service, if ((kinds[name] ?: 0) > 1) "$name on ${server.serverName}" else name)
    }
}

/** What a service tab holds as it loads. */
sealed interface TvServiceLibraryState {
    data object Loading : TvServiceLibraryState
    data class Loaded(val library: ServiceLibrary.Loaded) : TvServiceLibraryState
    data class Failed(val title: String, val canRetry: Boolean) : TvServiceLibraryState
}

/**
 * What you saved on one service: playlists, albums, artists and songs, a shelf each. Songs
 * play from their card among the others; the rest open their page. [load] reads it off the
 * main thread; null when there's nothing to ask.
 */
@Composable
fun TvServiceLibrary(tab: TvServiceTab, memory: FocusMemory, load: (suspend () -> ServiceLibrary.Loaded)?) {
    var retry by remember { mutableIntStateOf(0) }
    var state by remember(tab.id) { mutableStateOf<TvServiceLibraryState>(TvServiceLibraryState.Loading) }
    val signedIn = tab.service.isSignedIn
    LaunchedEffect(tab.id, signedIn, retry) {
        state = when {
            !signedIn -> TvServiceLibraryState.Failed("Sign In to ${tab.name} on Your Server", canRetry = false)
            load == null -> TvServiceLibraryState.Failed("Couldn't Load ${tab.name}", canRetry = false)
            else -> {
                state = TvServiceLibraryState.Loading
                try {
                    TvServiceLibraryState.Loaded(load())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: TracksideError.Server) {
                    if (error.reason == "service_signed_out") TvServiceLibraryState.Failed("Sign In to ${tab.name} on Your Server", canRetry = true)
                    else TvServiceLibraryState.Failed("Couldn't Load ${tab.name}", canRetry = true)
                } catch (_: Exception) {
                    TvServiceLibraryState.Failed("Couldn't Load ${tab.name}", canRetry = true)
                }
            }
        }
    }
    TvServiceLibraryContent(tab, state, memory, onRetry = { retry++ })
}

/** The tab from plain values, for screenshots. */
@Composable
fun TvServiceLibraryContent(tab: TvServiceTab, state: TvServiceLibraryState, memory: FocusMemory, onRetry: () -> Unit) {
    when (state) {
        TvServiceLibraryState.Loading -> TvShelfScroll {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 20.dp), userScrollEnabled = false) {
                items(2) { TvShelfSkeleton() }
            }
        }
        is TvServiceLibraryState.Failed -> TvEmptyState(
            state.title,
            icon = Icons.Rounded.LibraryMusic,
            actionLabel = if (state.canRetry) "Try Again" else null,
            onAction = onRetry,
        )
        is TvServiceLibraryState.Loaded -> {
            val library = state.library
            if (library.isEmpty) {
                TvEmptyState("Nothing Saved on ${tab.name}", icon = Icons.Rounded.LibraryMusic)
            } else {
                Shelves(tab, library, memory)
            }
        }
    }
}

@Composable
private fun Shelves(tab: TvServiceTab, library: ServiceLibrary.Loaded, memory: FocusMemory) {
    val context = LocalContext.current
    val pending = rememberPendingPlay()
    val serverId = tab.key.serverId
    val prefix = tab.id
    val songs = library.songs.filter { it.canPlay }
    TvShelfScroll {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = TvLayout.SafeBottom + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (library.playlists.isNotEmpty()) item(key = "$prefix:playlists", contentType = "shelf") {
                ItemShelf("Playlists", serverId, prefix, library.playlists, memory) { it.owner }
            }
            if (library.albums.isNotEmpty()) item(key = "$prefix:albums", contentType = "shelf") {
                ItemShelf("Albums", serverId, prefix, library.albums, memory) { it.artist ?: it.year?.toString() }
            }
            if (library.artists.isNotEmpty()) item(key = "$prefix:artists", contentType = "shelf") {
                ItemShelf("Artists", serverId, prefix, library.artists, memory) { null }
            }
            if (songs.isNotEmpty()) item(key = "$prefix:songs", contentType = "shelf") {
                val origin = remember(tab.name) { QueueOrigin("Saved on ${tab.name}", QueueOrigin.Kind.Library) }
                TvRowScroll {
                    TvShelf("Songs", songs, key = { "$prefix:song:${it.ref}" }, focusMemory = memory) { track ->
                        val key = "$prefix:song:${track.ref}"
                        TvCoverCard(
                            cover(serverId, track.artworkId), (track.album ?: track.title) + track.artist, track.title,
                            onClick = { pending.run(key) { context.playTracks(serverId, songs, songs.indexOf(track), origin) } },
                            subtitle = track.artist,
                            pending = pending.isPending(key),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemShelf(
    title: String,
    serverId: String,
    prefix: String,
    items: List<TracksideCatalogItem>,
    memory: FocusMemory,
    subtitle: (TracksideCatalogItem) -> String?,
) {
    val navigator = LocalTvNavigator.current
    TvRowScroll {
        TvShelf(title, items, key = { "$prefix:${it.kind}:${it.ref}" }, focusMemory = memory) { item ->
            val artist = item.kindValue == TracksideCatalogItem.Kind.Artist
            TvCoverCard(
                cover(serverId, item.artworkId), item.name + item.artist.orEmpty(), item.name,
                onClick = { navigator.open(item.route(serverId)) },
                subtitle = subtitle(item),
                width = if (artist) 156.dp else TvLayout.CardWidth,
                circle = artist,
            )
        }
    }
}

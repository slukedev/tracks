package com.luke.tracks.ui.services

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.graph
import com.luke.tracks.library.trackside.CatalogCapability
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogPage
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.streaming.CatalogMedia
import com.luke.tracks.streaming.ServiceKey
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.discover.CatalogProblem
import com.luke.tracks.ui.discover.ConnectedActions
import com.luke.tracks.ui.discover.PageAction
import com.luke.tracks.ui.discover.SubHeading
import com.luke.tracks.ui.insights.EmptyState
import com.luke.tracks.ui.library.PlayShuffleButtons
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.ServiceItemRoute
import com.luke.tracks.ui.navigation.bottomClearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import uniffi.tracks_engine.songIdentity

/** A page from a service, as it loads: its first page, or why it couldn't. */
private sealed interface PageState {
    data object Loading : PageState
    data class Loaded(val page: TracksideCatalogPage, val tracks: List<TracksideCatalogTrack>, val cursor: String?) : PageState
    data class Failed(val title: String, val message: String, val canRetry: Boolean) : PageState
}

/** What a refusal means here, in words: gone, signed out, or not reachable now. */
private fun refusal(error: Throwable, name: String, service: TracksideCatalogService?): PageState.Failed = when {
    error is TracksideError.NotFound -> PageState.Failed("$name isn't available", "${service?.name ?: "The service"} doesn't have it anymore.", canRetry = false)
    error is TracksideError.Server && error.reason == "service_signed_out" ->
        PageState.Failed("Sign in again", "Sign in to ${service?.name ?: "this service"} again on your server, then try again.", canRetry = true)
    error is TracksideError.CatalogUnavailable -> PageState.Failed("Music services aren't available", "Your server can't reach them right now.", canRetry = true)
    else -> PageState.Failed("Couldn't load $name", "Check your connection and try again.", canRetry = true)
}

/**
 * An album, artist, playlist or station on a service, laid out as the library's own pages
 * are: its cover, Play and Shuffle, then its songs. A long playlist loads more as you reach
 * its end. Artwork comes through your server, as every cover does.
 */
@Composable
fun ServiceItemScreen(route: ServiceItemRoute) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val streaming = rememberStreaming()
    val state = streamingState()
    val current by remember { context.graph.player.state.map { it.current?.mediaId } }.collectAsState(null)
    var retry by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<PageState>(PageState.Loading) }
    var loadingMore by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    LaunchedEffect(route, retry) {
        page = PageState.Loading
        val repository = streaming?.repository
        page = if (repository == null) PageState.Failed("Couldn't load ${route.name}", "Music services need a Trackside server.", canRetry = false) else try {
            val loaded = repository.item(route.serverId, route.ref, limit = PAGE)
            PageState.Loaded(loaded, loaded.tracks.ifEmpty { loaded.topTracks }, loaded.nextCursor)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val serviceId = (error as? TracksideError.Server)?.serviceId
            refusal(error, route.name, serviceId?.let { state.service(ServiceKey(route.serverId, it)) })
        }
    }
    // Near the end of what's loaded: the next page of a playlist.
    LaunchedEffect(list, page) {
        val loaded = page as? PageState.Loaded ?: return@LaunchedEffect
        val cursor = loaded.cursor ?: return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= list.layoutInfo.totalItemsCount - AHEAD }
            .collect {
                if (loadingMore) return@collect
                loadingMore = true
                val more = runCatching { streaming?.repository?.item(route.serverId, route.ref, cursor = cursor, limit = PAGE) }.getOrNull()
                loadingMore = false
                if (more != null) page = loaded.copy(tracks = (loaded.tracks + more.tracks).distinctBy { it.ref }, cursor = more.nextCursor)
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        when (val shown = page) {
            PageState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }
            is PageState.Failed -> Box(Modifier.padding(padding)) {
                EmptyState(Icons.Rounded.SearchOff, shown.title, shown.message, action = if (shown.canRetry) "Try again" else null, onAction = { retry++ })
            }
            is PageState.Loaded -> {
                val item = shown.page.item
                val track = shown.page.track
                val serviceId = item?.serviceId ?: track?.serviceId.orEmpty()
                val service = state.service(ServiceKey(route.serverId, serviceId))
                val name = item?.name ?: track?.title ?: route.name
                val origin = QueueOrigin(name, if (item?.kindValue == TracksideCatalogItem.Kind.Playlist) QueueOrigin.Kind.Playlist else if (item?.kindValue == TracksideCatalogItem.Kind.Artist) QueueOrigin.Kind.Artist else QueueOrigin.Kind.Album)
                val tracks = shown.tracks.ifEmpty { listOfNotNull(track) }
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomClearance)) {
                    item(key = "hero") { Hero(route, item, track, service) }
                    item(key = "actions") {
                        when {
                            item?.kindValue == TracksideCatalogItem.Kind.Radio -> ConnectedActions(
                                listOf(PageAction("Play station", Icons.Rounded.PlayArrow, primary = true) { playStation(context, route.serverId, item) }),
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            tracks.any { it.canPlay } -> PlayShuffleButtons(
                                onPlay = { context.playTracks(route.serverId, tracks, 0, origin) },
                                onShuffle = { context.playTracks(route.serverId, tracks, 0, origin, shuffle = true) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (item?.kindValue == TracksideCatalogItem.Kind.Artist && tracks.isNotEmpty()) {
                        item(key = "top-h") { SubHeading("Top songs", Modifier.padding(horizontal = 20.dp)) }
                    }
                    val album = item?.kindValue == TracksideCatalogItem.Kind.Album
                    itemsIndexed(tracks, key = { _, t -> t.ref }, contentType = { _, _ -> "service-song" }) { i, t ->
                        ServiceTrackRow(
                            route.serverId, t, siblings = tracks, origin = origin, currentMediaId = current,
                            modifier = Modifier.animateItem(),
                            trackNumber = if (album) t.trackNumber ?: (i + 1) else null,
                            showAlbum = !album,
                            albumArtist = item?.artist.takeIf { album },
                        )
                    }
                    if (loadingMore) item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
                    }
                    if (shown.page.albums.isNotEmpty()) {
                        item(key = "albums-h") { SubHeading("Albums", Modifier.padding(horizontal = 20.dp)) }
                        item(key = "albums") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                                items(shown.page.albums, key = { it.ref }) { ServiceItemCell(route.serverId, it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(route: ServiceItemRoute, item: TracksideCatalogItem?, track: TracksideCatalogTrack?, service: TracksideCatalogService?) {
    val navigator = LocalNavigator.current
    val artist = item?.kindValue == TracksideCatalogItem.Kind.Artist
    val name = item?.name ?: track?.title ?: route.name
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(
            cover(route.serverId, item?.artworkId ?: track?.artworkId, 600),
            name,
            Modifier.size(if (artist) 200.dp else 240.dp),
            // Artists in an expressive shape of their own, as in the library.
            shape = if (artist) MaterialShapes.Cookie9Sided.toShape() else RoundedCornerShape(28.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        val by = item?.artists?.firstOrNull { it.ref != null }
        when {
            by != null -> TextButton(onClick = { navigator.open(ServiceItemRoute(route.serverId, by.ref!!, "artist", by.name, null)) }) {
                Text(by.name, style = MaterialTheme.typography.titleMedium)
            }
            !item?.artist.isNullOrBlank() || !item?.owner.isNullOrBlank() -> Text(
                (item?.artist ?: item?.owner).orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        val details = listOfNotNull(
            item?.kindLabel(),
            item?.year?.toString(),
            item?.trackCount?.let { if (it == 1) "1 song" else "$it songs" },
        ).joinToString(" · ")
        if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(service != null, enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ServiceMark(service, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(service?.name, service?.quality?.let(::qualityLabel)).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** lossless, high or standard, as the service plays through your server. */
fun qualityLabel(quality: String): String? = when (quality) {
    "lossless" -> "Lossless"
    "high" -> "High quality"
    "standard" -> null
    else -> null
}

internal fun playStation(context: android.content.Context, serverId: String, station: TracksideCatalogItem) {
    val song = com.luke.tracks.playback.PlayableSong(
        mediaId = CatalogMedia.mediaId(serverId, station.ref),
        uri = android.net.Uri.parse("tracks-catalog://$serverId/${station.ref}"),
        title = station.name,
        artistName = "",
        albumTitle = null,
        artworkUri = cover(serverId, station.artworkId),
        durationMs = null,
        identity = songIdentity(station.name, ""),
    )
    context.graph.player.play(listOf(song), 0, QueueOrigin(station.name, QueueOrigin.Kind.Radio))
}

/**
 * What you saved on a service: its playlists first, then albums and artists, newest first.
 * Shown in Search when a service is chosen and nothing's typed.
 */
fun LazyListScope.serviceLibraryItems(key: ServiceKey, service: TracksideCatalogService?, library: ServiceLibrary, onRetry: () -> Unit) {
    when (library) {
        ServiceLibrary.Loading -> item(key = "svc-lib-loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(48.dp)) }
        }
        is ServiceLibrary.Failed -> item(key = "svc-lib-failed") { CatalogProblem(library.message, onRetry, Modifier.padding(horizontal = 16.dp)) }
        is ServiceLibrary.Loaded -> {
            if (library.isEmpty) {
                item(key = "svc-lib-empty") {
                    EmptyState(Icons.Rounded.SearchOff, "Nothing saved yet", "Playlists and albums you save on ${service?.name ?: "the service"} show here.")
                }
                return
            }
            listOf("Playlists" to library.playlists, "Albums" to library.albums).forEach { (title, items) ->
                if (items.isEmpty()) return@forEach
                item(key = "svc-lib-$title-h") { SubHeading(title, Modifier.padding(horizontal = 20.dp).animateItem()) }
                item(key = "svc-lib-$title") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.animateItem()) {
                        items(items, key = { it.ref }) { ServiceItemCell(key.serverId, it) }
                    }
                }
            }
            if (library.artists.isNotEmpty()) {
                item(key = "svc-lib-artists-h") { SubHeading("Artists", Modifier.padding(horizontal = 20.dp).animateItem()) }
                items(library.artists, key = { "svc-lib-artist-${it.ref}" }) { ServiceArtistRow(key.serverId, it, Modifier.animateItem()) }
            }
        }
    }
}

/** A service's saved library, as it loads. */
sealed interface ServiceLibrary {
    data object Loading : ServiceLibrary
    data class Loaded(val playlists: List<TracksideCatalogItem>, val albums: List<TracksideCatalogItem>, val artists: List<TracksideCatalogItem>) : ServiceLibrary {
        val isEmpty: Boolean get() = playlists.isEmpty() && albums.isEmpty() && artists.isEmpty()
    }
    data class Failed(val message: String) : ServiceLibrary
}

/** Loads what you saved on [key]'s service, when it keeps a library; off the main thread, cancelled with the screen. */
@Composable
fun rememberServiceLibrary(key: ServiceKey?, service: TracksideCatalogService?, retry: Int): ServiceLibrary? {
    val streaming = rememberStreaming()
    var library by remember(key) { mutableStateOf<ServiceLibrary?>(null) }
    LaunchedEffect(key, service?.isSignedIn, retry) {
        val repository = streaming?.repository
        if (key == null || service == null || repository == null || !service.can(CatalogCapability.LIBRARY)) {
            library = null
            return@LaunchedEffect
        }
        if (!service.isSignedIn) {
            library = ServiceLibrary.Failed("Sign in to ${service.name} again on your server.")
            return@LaunchedEffect
        }
        library = ServiceLibrary.Loading
        library = try {
            ServiceLibrary.Loaded(
                playlists = repository.library(key, "playlist").items,
                albums = runCatching { repository.library(key, "album").items }.getOrDefault(emptyList()),
                artists = runCatching { repository.library(key, "artist").items }.getOrDefault(emptyList()),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ServiceLibrary.Failed(refusal(error, service.name, service).message)
        }
    }
    return library
}

private const val PAGE = 100
private const val AHEAD = 8

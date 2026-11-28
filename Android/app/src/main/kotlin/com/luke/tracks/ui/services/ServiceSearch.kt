package com.luke.tracks.ui.services

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luke.tracks.AppGraph
import com.luke.tracks.graph
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.streaming.ServiceKey
import com.luke.tracks.streaming.ServiceResults
import com.luke.tracks.streaming.ServiceResultsMerge
import com.luke.tracks.streaming.Streaming
import com.luke.tracks.streaming.StreamingServer
import com.luke.tracks.streaming.StreamingState
import com.luke.tracks.ui.discover.CatalogProblem
import com.luke.tracks.ui.discover.GroupHeading
import com.luke.tracks.ui.discover.SubHeading
import com.luke.tracks.ui.insights.bleed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import java.io.IOException

/** A search on the services as it goes: nothing asked, asking (with the last answer kept on screen), answered, or why not. */
@Immutable
sealed interface ServiceSearch {
    data object Idle : ServiceSearch
    data class Loading(val previous: ServiceResults?) : ServiceSearch
    data class Loaded(val results: ServiceResults) : ServiceSearch
    data class Failed(val message: String) : ServiceSearch

    val shown: ServiceResults? get() = when (this) {
        is Loaded -> results
        is Loading -> previous
        else -> null
    }
}

/**
 * Searches the services as you type: the field answers at once, the network is asked after a
 * pause, and a newer query cancels the older one's request. Merging with the library happens
 * off the main thread; the screen collects an immutable [ServiceSearch].
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ServiceSearchViewModel(private val graph: AppGraph, private val streaming: Streaming?) : ViewModel() {
    private data class Query(val text: String, val only: ServiceKey?)

    private val query = MutableStateFlow(Query("", null))
    private val retries = MutableStateFlow(0)

    /** The last answer, kept on screen while the next is on its way. */
    @Volatile private var last: ServiceResults? = null

    val state: StateFlow<ServiceSearch> = combine(query, retries) { q, retry -> q to retry }
        .distinctUntilChanged()
        .debounce { (q, _) -> if (q.text.length < MIN_LENGTH) 0 else DEBOUNCE_MS }
        .transformLatest { (q, _) ->
            val repository = streaming?.repository
            if (repository == null || q.text.length < MIN_LENGTH || !repository.state.value.offered) {
                last = null
                emit(ServiceSearch.Idle)
                return@transformLatest
            }
            emit(ServiceSearch.Loading(last))
            val answers = try {
                repository.search(q.text, q.only)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit(ServiceSearch.Failed(words(error)))
                return@transformLatest
            }
            val index = graph.library.index.value
            val merged = withContext(Dispatchers.Default) {
                ServiceResultsMerge.merge(answers, owned = { server, song -> index.song(ServerLibrary.mediaId(server, song)) != null }, libraryMediaId = ServerLibrary::mediaId)
            }
            last = merged
            emit(ServiceSearch.Loaded(merged))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServiceSearch.Idle)

    fun search(text: String, only: ServiceKey?) {
        query.value = Query(text.trim(), only)
    }

    fun retry() {
        retries.value++
    }

    private fun words(error: Throwable) = when (error) {
        is TracksideError.CatalogUnavailable -> "Music services aren't available right now."
        is TracksideError.RateLimited -> "Searching a lot just now. Try again in a moment."
        is IOException, is TracksideError.Unproven -> "Couldn't reach your server."
        else -> "Couldn't search your music services."
    }

    companion object {
        const val MIN_LENGTH = 2
        const val DEBOUNCE_MS = 300L
    }
}

@Composable
fun serviceSearchViewModel(): ServiceSearchViewModel {
    val graph = LocalContext.current.graph
    return viewModel(key = "service-search") { ServiceSearchViewModel(graph, Streaming.of(graph)) }
}

/** The services' chips, after the sources': one per service, Apple Music too where the server has it. */
@Composable
fun ServiceChips(services: List<Pair<ServiceKey, TracksideCatalogService>>, chosen: ServiceKey?, onChoose: (ServiceKey?) -> Unit) {
    val haptics = LocalHapticFeedback.current
    services.forEach { (key, service) ->
        val on = key == chosen
        FilterChip(
            selected = on,
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onChoose(if (on) null else key) },
            label = { Text(service.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                if (service.isSignedIn) ServiceMark(service, size = FilterChipDefaults.IconSize)
                else Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(FilterChipDefaults.IconSize), tint = MaterialTheme.colorScheme.error)
            },
            modifier = Modifier.semantics { if (!service.isSignedIn) stateDescription = "Signed out" },
        )
    }
}

/**
 * The services' part of a search, after your library: each service with its songs (less the
 * ones that are yours, which the library shows), albums, artists and playlists. A search
 * on its way keeps the last answer on screen, with a quiet indicator.
 */
fun LazyListScope.serviceSearchItems(
    state: ServiceSearch,
    streaming: StreamingState,
    chosen: ServiceKey?,
    query: String,
    currentMediaId: String?,
    edge: Dp,
    listPadding: Dp,
    onRetry: () -> Unit,
) {
    val wide = Modifier.bleed(listPadding)
    // A chosen service that's signed out can't be searched: say how to fix it, and nothing else.
    val signedOut = chosen?.let(streaming::service)?.takeIf { !it.isSignedIn }
    if (signedOut != null) {
        item(key = "services-signed-out") {
            CatalogProblem("Sign in to ${signedOut.name} again on your server.", onRetry, wide.padding(horizontal = edge).animateItem())
        }
        return
    }
    if (streaming.servers.any { it.status == StreamingServer.Status.Unavailable } && streaming.servers.none { it.status == StreamingServer.Status.Ok }) {
        item(key = "services-unavailable") {
            CatalogProblem("Music services aren't available right now.", onRetry, wide.padding(horizontal = edge).animateItem())
        }
        return
    }
    when (state) {
        ServiceSearch.Idle -> return
        is ServiceSearch.Failed -> {
            item(key = "services-failed") { CatalogProblem(state.message, onRetry, wide.padding(horizontal = edge).animateItem()) }
            return
        }
        is ServiceSearch.Loading -> if (state.previous == null) {
            item(key = "services-loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp).animateItem(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(40.dp)) }
            }
            return
        }
        is ServiceSearch.Loaded -> Unit
    }
    val results = state.shown ?: return
    val many = streaming.servers.size > 1
    results.sections.forEach { section ->
        val serverId = section.key.serverId
        val prefix = "svc-${section.key.raw}"
        item(key = "$prefix-h", contentType = "header") {
            Row(wide.padding(horizontal = edge).padding(top = 20.dp).animateItem(), verticalAlignment = Alignment.CenterVertically) {
                ServiceMark(section.service, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                GroupHeading(
                    section.service.name,
                    Modifier.weight(1f),
                    subtitle = streaming.server(serverId)?.serverName?.takeIf { many }?.let { "On $it" },
                )
                AnimatedVisibility(state is ServiceSearch.Loading, enter = fadeIn(), exit = fadeOut()) {
                    LoadingIndicator(Modifier.size(28.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        if (section.tracks.isNotEmpty()) {
            item(key = "$prefix-songs-h", contentType = "subheader") { SubHeading("Songs", wide.padding(horizontal = edge).animateItem()) }
            val shown = section.tracks.take(SONGS)
            items(shown, key = { "$prefix-song-${it.ref}" }, contentType = { "service-song" }) { track ->
                ServiceTrackRow(
                    serverId, track, siblings = shown, origin = QueueOrigin(query, QueueOrigin.Kind.Search), currentMediaId = currentMediaId,
                    modifier = wide.padding(horizontal = edge - 16.dp).animateItem(),
                )
            }
        }
        if (section.artists.isNotEmpty()) {
            item(key = "$prefix-artists-h", contentType = "subheader") { SubHeading("Artists", wide.padding(horizontal = edge).animateItem()) }
            items(section.artists.take(ARTISTS), key = { "$prefix-artist-${it.ref}" }, contentType = { "service-artist" }) { artist ->
                ServiceArtistRow(serverId, artist, wide.padding(horizontal = edge - 16.dp).animateItem())
            }
        }
        if (section.albums.isNotEmpty()) {
            item(key = "$prefix-albums-h", contentType = "subheader") { SubHeading("Albums", wide.padding(horizontal = edge).animateItem()) }
            item(key = "$prefix-albums", contentType = "shelf") {
                LazyRow(contentPadding = PaddingValues(horizontal = edge - 6.dp), modifier = wide.animateItem()) {
                    items(section.albums, key = { it.ref }) { ServiceItemCell(serverId, it) }
                }
            }
        }
        if (section.playlists.isNotEmpty()) {
            item(key = "$prefix-playlists-h", contentType = "subheader") { SubHeading("Playlists", wide.padding(horizontal = edge).animateItem()) }
            item(key = "$prefix-playlists", contentType = "shelf") {
                LazyRow(contentPadding = PaddingValues(horizontal = edge - 6.dp), modifier = wide.animateItem()) {
                    items(section.playlists, key = { it.ref }) { ServiceItemCell(serverId, it) }
                }
            }
        }
    }
    if (results.missing.isNotEmpty()) {
        item(key = "services-missing") {
            Text(
                "${com.luke.tracks.ui.discover.listPhrase(results.missing)} didn't answer in time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = wide.padding(horizontal = edge).padding(top = 12.dp).animateItem(),
            )
        }
    }
}

private const val SONGS = 6
private const val ARTISTS = 3

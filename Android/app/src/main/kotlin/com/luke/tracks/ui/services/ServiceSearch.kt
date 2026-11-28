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
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.streaming.ServiceKey
import com.luke.tracks.streaming.ServiceResults
import com.luke.tracks.streaming.ServiceResultsMerge
import com.luke.tracks.streaming.ServiceSection
import com.luke.tracks.streaming.Streaming
import com.luke.tracks.streaming.StreamingRepository
import com.luke.tracks.streaming.StreamingServer
import com.luke.tracks.streaming.StreamingState
import com.luke.tracks.ui.discover.CatalogProblem
import com.luke.tracks.ui.discover.GroupHeading
import com.luke.tracks.ui.discover.SubHeading
import com.luke.tracks.ui.insights.bleed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
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
 * Searches the services as a query changes: asked after [debounceMs], a newer query cancelling
 * the older one's request, and asked again when the services that can search change (they load
 * after the app starts, so an early query isn't left unanswered). Nothing is asked without
 * services, or for fewer than [MIN_LENGTH] characters. Merging with the library happens off the
 * main thread.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ServiceSearcher(
    private val repository: StreamingRepository?,
    /** Whether a song, by its media id, is in your library. */
    private val owns: (mediaId: String) -> Boolean,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val types: List<String> = TracksideClient.DEFAULT_CATALOG_TYPES,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    data class Query(val text: String, val only: ServiceKey? = null)

    fun states(queries: Flow<Query>, retries: Flow<Int> = flowOf(0)): Flow<ServiceSearch> {
        // The last answer, kept on screen while the next is on its way; only touched in transformLatest.
        var last: ServiceResults? = null
        val searchable = repository?.state?.map { state -> state.servers.filter { it.status != StreamingServer.Status.Unavailable }.flatMap { it.searchableKeys() } }
            ?.distinctUntilChanged() ?: flowOf(emptyList())
        return combine(queries, retries, searchable) { q, retry, keys -> Triple(q, retry, keys) }
            .distinctUntilChanged()
            .debounce { (q, _, _) -> if (q.text.length < MIN_LENGTH) 0 else debounceMs }
            .transformLatest { (q, _, _) ->
                if (repository == null || q.text.length < MIN_LENGTH || !repository.state.value.offered) {
                    last = null
                    emit(ServiceSearch.Idle)
                    return@transformLatest
                }
                emit(ServiceSearch.Loading(last))
                val answers = try {
                    repository.search(q.text, q.only, types = types)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    emit(ServiceSearch.Failed(words(error)))
                    return@transformLatest
                }
                val merged = ServiceResultsMerge.merge(answers, owned = { server, song -> owns(ServerLibrary.mediaId(server, song)) }, libraryMediaId = ServerLibrary::mediaId)
                last = merged
                emit(ServiceSearch.Loaded(merged))
            }
            .flowOn(work)
    }

    private fun StreamingServer.searchableKeys() = services.filter { it.searches }.map { ServiceKey(serverId, it.id) }

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

/** The phone's search of the services, kept across the screen's configuration changes. */
class ServiceSearchViewModel(graph: AppGraph, streaming: Streaming?) : ViewModel() {
    private val query = MutableStateFlow(ServiceSearcher.Query(""))
    private val retries = MutableStateFlow(0)

    val state: StateFlow<ServiceSearch> = ServiceSearcher(streaming?.repository, owns = { graph.library.index.value.song(it) != null })
        .states(query, retries)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServiceSearch.Idle)

    fun search(text: String, only: ServiceKey?) {
        query.value = ServiceSearcher.Query(text.trim(), only)
    }

    fun retry() {
        retries.value++
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
 * The services' part of a search: each service with its songs (less the ones that are yours,
 * which the library shows), albums, artists and playlists, less the sections in [shownAbove]
 * (those Play From ranks above your library, shown there with [serviceSectionItems]). A search
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
    shownAbove: List<ServiceSection> = emptyList(),
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
    val above = shownAbove.mapTo(HashSet()) { it.key }
    serviceSectionItems(results.sections.filter { it.key !in above }, streaming, query, currentMediaId, edge, listPadding, loading = state is ServiceSearch.Loading)
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

/** Services' sections of a search, each under its service's name; [loading] while a newer search is on its way. */
fun LazyListScope.serviceSectionItems(
    sections: List<ServiceSection>,
    streaming: StreamingState,
    query: String,
    currentMediaId: String?,
    edge: Dp,
    listPadding: Dp,
    loading: Boolean,
) {
    val wide = Modifier.bleed(listPadding)
    val many = streaming.servers.size > 1
    sections.forEach { section ->
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
                AnimatedVisibility(loading, enter = fadeIn(), exit = fadeOut()) {
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
}

private const val SONGS = 6
private const val ARTISTS = 3

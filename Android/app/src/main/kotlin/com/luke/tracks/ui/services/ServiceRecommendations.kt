package com.luke.tracks.ui.services

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.trackside.TracksideCatalogEntry
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.streaming.CatalogRecommendations
import com.luke.tracks.streaming.RecommendationRows
import com.luke.tracks.streaming.RecommendationShelf
import com.luke.tracks.streaming.ServerRecommendations
import kotlinx.coroutines.flow.MutableStateFlow
import uniffi.tracks_engine.foldedName

/** The app's services' picks; screenshots provide their own. */
val LocalCatalogRecommendations = staticCompositionLocalOf<CatalogRecommendations?> { null }

private val NoRecommendations = MutableStateFlow<Map<String, ServerRecommendations>>(emptyMap())

/**
 * Play's rows of the services' own picks, by server, in the order Play lists its servers:
 * only the servers [selection] includes, less what [recent] (Recently Played) already shows.
 * Each time Play shows, rows older than the loader's limit are asked for again.
 */
@Composable
fun rememberRecommendationRows(recent: List<LibraryAlbum>, selection: SourceSelection): Map<String, ServerRecommendations> {
    val provided = LocalCatalogRecommendations.current
    val loader = provided ?: rememberStreaming()?.recommendations
    LaunchedEffect(loader) { loader?.want() }
    val all by (loader?.state ?: NoRecommendations).collectAsState()
    val recentKeys = remember(recent) { recent.map { albumKey(it.title, it.artistName) }.toSet() }
    return remember(all, recentKeys, selection) {
        all.filterKeys { selection.includes(SourceId.Server(it)) }.mapNotNull { (id, recs) ->
            when (recs) {
                ServerRecommendations.Loading -> id to recs
                is ServerRecommendations.Ready -> RecommendationRows.shown(recs.shelves, recentKeys, ::albumKey)
                    .takeIf { it.isNotEmpty() }?.let { id to ServerRecommendations.Ready(it) }
            }
        }.toMap()
    }
}

private fun albumKey(title: String, artist: String) = foldedName(title) + "\u001f" + foldedName(artist)

/** Whether any of [rows] come from more than one service, so each row says whose it is. */
fun Map<String, ServerRecommendations>.fromSeveralServices(): Boolean =
    values.filterIsInstance<ServerRecommendations.Ready>().flatMap { it.shelves }.map { it.service.name }.distinct().size > 1

/** What a pick's card says under its name. */
fun TracksideCatalogEntry.byline(): String = when (this) {
    is TracksideCatalogEntry.Track -> track.artist
    is TracksideCatalogEntry.Item -> when (item.kindValue) {
        TracksideCatalogItem.Kind.Album -> item.artist ?: item.kindLabel()
        TracksideCatalogItem.Kind.Playlist -> item.owner ?: item.kindLabel()
        else -> item.kindLabel()
    }
}

fun TracksideCatalogEntry.name(): String = when (this) {
    is TracksideCatalogEntry.Track -> track.title
    is TracksideCatalogEntry.Item -> item.name
}

/**
 * Starts a pick that plays at once: a song (with the row's other songs after it) or a
 * station. False for one that opens instead.
 */
fun Context.playPick(row: RecommendationShelf, entry: TracksideCatalogEntry): Boolean = when {
    entry is TracksideCatalogEntry.Track -> {
        val tracks = row.shelf.items.filterIsInstance<TracksideCatalogEntry.Track>().map { it.track }
        playTracks(row.serverId, tracks, tracks.indexOf(entry.track), QueueOrigin(row.shelf.title, QueueOrigin.Kind.Other))
        true
    }
    entry is TracksideCatalogEntry.Item && entry.item.kindValue == TracksideCatalogItem.Kind.Radio -> {
        playStation(this, row.serverId, entry.item)
        true
    }
    else -> false
}

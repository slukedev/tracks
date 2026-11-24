package com.luke.tracks.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The five places in the navigation bar, rail or drawer. */
enum class Tab { Play, Library, Summary, History, Search }

/** History's two views of the same record. */
enum class HistoryMode { Plays, Charts }

@Serializable sealed interface Route : NavKey

@Serializable data object PlayRoute : Route
@Serializable data object SummaryRoute : Route
/** History in Charts mode; not a tab of its own. */
@Serializable data object ChartsRoute : Route
@Serializable data object HistoryRoute : Route
@Serializable data object LibraryRoute : Route

@Serializable data class ArtistRoute(val identity: String, val name: String) : Route
@Serializable data class AlbumRoute(val identity: String, val title: String, val artistName: String) : Route
@Serializable data class SongRoute(val identity: String, val title: String, val artistName: String) : Route
@Serializable data class MixRoute(val mixId: String) : Route
@Serializable data object SearchRoute : Route
/** Spotify's saved music, in builds that have it. */
@Serializable data object SpotifyLibraryRoute : Route
@Serializable data object SettingsRoute : Route
/**
 * A page inside Settings, by name: "sources", "source/<id>" (one source, by its stored id),
 * "sync", "lastfm", "appearance", "about" and the rest.
 */
@Serializable data class SettingsPageRoute(val page: String) : Route
/** A library album or artist (from Your Music, by its library id). */
@Serializable data class LibraryAlbumRoute(val albumId: String) : Route
@Serializable data class LibraryArtistRoute(val artistId: String) : Route
/**
 * An album or artist on one of your servers that isn't in your music, looked up there: by
 * the server's id where it's known, else from one of their songs or by name.
 */
@Serializable data class ServerAlbumRoute(val serverId: String, val title: String, val artistName: String, val albumId: String? = null, val songId: String? = null) : Route
@Serializable data class ServerArtistRoute(val serverId: String, val name: String, val artistId: String? = null, val songId: String? = null) : Route
/** An artist or album from the catalog, which you may not have: by name, and its catalog id. */
@Serializable data class CatalogArtistRoute(val name: String, val catalogId: Long? = null) : Route
@Serializable data class CatalogAlbumRoute(val catalogId: Long, val title: String, val artistName: String) : Route

fun Tab.root(): Route = when (this) {
    Tab.Play -> PlayRoute
    Tab.Library -> LibraryRoute
    Tab.Summary -> SummaryRoute
    Tab.History -> HistoryRoute
    Tab.Search -> SearchRoute
}

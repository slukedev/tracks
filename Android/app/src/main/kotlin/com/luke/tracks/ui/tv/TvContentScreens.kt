package com.luke.tracks.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.luke.tracks.ui.insights.Identities
import com.luke.tracks.ui.navigation.CatalogAlbumRoute
import com.luke.tracks.ui.tv.navigation.TvScreens
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.CatalogArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.MixRoute
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.tv.charts.TvChartsScreen
import com.luke.tracks.ui.tv.detail.TvAlbumScreen
import com.luke.tracks.ui.tv.detail.TvArtistScreen
import com.luke.tracks.ui.tv.detail.TvLibraryAlbumScreen
import com.luke.tracks.ui.tv.detail.TvLibraryArtistScreen
import com.luke.tracks.ui.tv.detail.TvSongScreen
import com.luke.tracks.ui.tv.history.TvHistoryScreen
import com.luke.tracks.ui.tv.library.TvLibraryScreen
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.play.TvMixScreen
import com.luke.tracks.ui.tv.play.TvPlayScreen
import com.luke.tracks.ui.tv.search.TvSearchScreen
import com.luke.tracks.ui.tv.summary.TvSummaryScreen
import uniffi.tracks_engine.foldedName

/**
 * The content screens (Play, Library, Summary, Charts, History, Search and their detail pages)
 * for the shell's [TvScreens] slots. [install] points each slot at its screen; call it once
 * before the shell is first composed.
 */
object TvContentScreens {
    fun install() {
        TvScreens.play = play
        TvScreens.library = library
        TvScreens.summary = summary
        TvScreens.charts = charts
        TvScreens.history = history
        TvScreens.search = search
        TvScreens.album = album
        TvScreens.artist = artist
        TvScreens.song = song
        TvScreens.mix = mix
        TvScreens.libraryAlbum = libraryAlbum
        TvScreens.libraryArtist = libraryArtist
        TvScreens.catalogAlbum = catalogAlbum
        TvScreens.catalogArtist = catalogArtist
    }

    val play: @Composable () -> Unit = { TvPlayScreen() }
    val library: @Composable () -> Unit = { TvLibraryScreen() }
    val summary: @Composable () -> Unit = { TvSummaryScreen() }
    val charts: @Composable () -> Unit = { TvChartsScreen() }
    val history: @Composable () -> Unit = { TvHistoryScreen() }
    val search: @Composable () -> Unit = { TvSearchScreen() }
    val album: @Composable (AlbumRoute) -> Unit = { TvAlbumScreen(it.identity, it.title, it.artistName) }
    val artist: @Composable (ArtistRoute) -> Unit = { TvArtistScreen(it.identity, it.name) }
    val song: @Composable (SongRoute) -> Unit = { TvSongScreen(it.identity, it.title, it.artistName) }
    val mix: @Composable (MixRoute) -> Unit = { TvMixScreen(it.mixId) }
    val libraryAlbum: @Composable (LibraryAlbumRoute) -> Unit = { TvLibraryAlbumScreen(it.albumId) }
    val libraryArtist: @Composable (LibraryArtistRoute) -> Unit = { TvLibraryArtistScreen(it.artistId) }

    /** An album named from outside the library: what the history knows of it. */
    val catalogAlbum: @Composable (CatalogAlbumRoute) -> Unit = { route ->
        val identity = remember(route) { Identities.album(route.title, route.artistName, route.title) }
        if (identity != null) TvAlbumScreen(identity, route.title, route.artistName)
    }

    /** An artist named from outside the library: what the history knows of them. */
    val catalogArtist: @Composable (CatalogArtistRoute) -> Unit = { route -> TvArtistScreen(remember(route) { foldedName(route.name) }, route.name) }
}

/** Where the content screens hand off to screens they don't own. */
object TvContentHooks {
    /** Library's Link action, before this TV is linked to Trackside: the link screen. */
    var openLink: (TvNavigator) -> Unit = { it.open(SettingsPageRoute("trackside")) }
}

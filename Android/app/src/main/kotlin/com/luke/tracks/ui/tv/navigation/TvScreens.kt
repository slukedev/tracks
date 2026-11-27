package com.luke.tracks.ui.tv.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.luke.tracks.ui.insights.Identities
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.CatalogAlbumRoute
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
import com.luke.tracks.ui.tv.nowplaying.TvNowPlayingScreen
import com.luke.tracks.ui.tv.onboarding.TvOnboardingGate
import com.luke.tracks.ui.tv.play.TvMixScreen
import com.luke.tracks.ui.tv.play.TvPlayScreen
import com.luke.tracks.ui.tv.search.TvSearchScreen
import com.luke.tracks.ui.tv.settings.TvSettingsScreen
import com.luke.tracks.ui.tv.summary.TvSummaryScreen
import uniffi.tracks_engine.foldedName

/** The TV screens the shell shows, one slot per destination and route. Tests may swap a slot for a fake. */
object TvScreens {
    var play: @Composable () -> Unit = { TvPlayScreen() }
    var library: @Composable () -> Unit = { TvLibraryScreen() }
    var summary: @Composable () -> Unit = { TvSummaryScreen() }
    var charts: @Composable () -> Unit = { TvChartsScreen() }
    var history: @Composable () -> Unit = { TvHistoryScreen() }
    var search: @Composable () -> Unit = { TvSearchScreen() }
    var settings: @Composable () -> Unit = { TvSettingsScreen() }
    var settingsPage: @Composable (SettingsPageRoute) -> Unit = { TvSettingsScreen(initial = it.page) }

    var album: @Composable (AlbumRoute) -> Unit = { TvAlbumScreen(it.identity, it.title, it.artistName) }
    var artist: @Composable (ArtistRoute) -> Unit = { TvArtistScreen(it.identity, it.name) }
    var song: @Composable (SongRoute) -> Unit = { TvSongScreen(it.identity, it.title, it.artistName) }
    var mix: @Composable (MixRoute) -> Unit = { TvMixScreen(it.mixId) }
    var libraryAlbum: @Composable (LibraryAlbumRoute) -> Unit = { TvLibraryAlbumScreen(it.albumId) }
    var libraryArtist: @Composable (LibraryArtistRoute) -> Unit = { TvLibraryArtistScreen(it.artistId) }

    /** An album named from outside the library: what the history knows of it. */
    var catalogAlbum: @Composable (CatalogAlbumRoute) -> Unit = { route ->
        val identity = remember(route) { Identities.album(route.title, route.artistName, route.title) }
        if (identity != null) TvAlbumScreen(identity, route.title, route.artistName)
    }

    /** An artist named from outside the library: what the history knows of them. */
    var catalogArtist: @Composable (CatalogArtistRoute) -> Unit = { route -> TvArtistScreen(remember(route) { foldedName(route.name) }, route.name) }

    /** The full-screen player. The shell animates it in and keeps focus inside it. */
    var nowPlaying: @Composable (onClose: () -> Unit) -> Unit = { TvNowPlayingScreen(it) }

    /** Wraps the whole shell, for first-run onboarding. */
    var gate: @Composable (content: @Composable () -> Unit) -> Unit = { TvOnboardingGate(it) }
}

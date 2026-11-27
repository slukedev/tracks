package com.luke.tracks.ui.tv.navigation

import androidx.compose.runtime.Composable
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.CatalogAlbumRoute
import com.luke.tracks.ui.navigation.CatalogArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.MixRoute
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.tv.components.TvEmptyState

/**
 * The TV screens the shell shows, one slot per destination and route. Each screen package
 * points its own slot at its screen. Tests may swap a slot for a fake.
 */
object TvScreens {
    var play: @Composable () -> Unit = { Unfilled("Play") }
    var library: @Composable () -> Unit = { Unfilled("Library") }
    var summary: @Composable () -> Unit = { Unfilled("Summary") }
    var charts: @Composable () -> Unit = { Unfilled("Charts") }
    var history: @Composable () -> Unit = { Unfilled("History") }
    var search: @Composable () -> Unit = { Unfilled("Search") }
    var settings: @Composable () -> Unit = { com.luke.tracks.ui.tv.settings.TvSettingsScreen() }
    var settingsPage: @Composable (SettingsPageRoute) -> Unit = { com.luke.tracks.ui.tv.settings.TvSettingsScreen(initial = it.page) }

    var album: @Composable (AlbumRoute) -> Unit = { Unfilled(it.title) }
    var artist: @Composable (ArtistRoute) -> Unit = { Unfilled(it.name) }
    var song: @Composable (SongRoute) -> Unit = { Unfilled(it.title) }
    var mix: @Composable (MixRoute) -> Unit = { Unfilled("Mix") }
    var libraryAlbum: @Composable (LibraryAlbumRoute) -> Unit = { Unfilled("Album") }
    var libraryArtist: @Composable (LibraryArtistRoute) -> Unit = { Unfilled("Artist") }
    var catalogAlbum: @Composable (CatalogAlbumRoute) -> Unit = { Unfilled(it.title) }
    var catalogArtist: @Composable (CatalogArtistRoute) -> Unit = { Unfilled(it.name) }

    /** The full-screen player. The shell animates it in and keeps focus inside it. */
    var nowPlaying: @Composable (onClose: () -> Unit) -> Unit = { com.luke.tracks.ui.tv.nowplaying.TvNowPlayingScreen(it) }

    /** Wraps the whole shell, for first-run onboarding. */
    var gate: @Composable (content: @Composable () -> Unit) -> Unit = { com.luke.tracks.ui.tv.onboarding.TvOnboardingGate(it) }
}

/** A screen not built yet: its name, centred, so the shell can be driven end to end. */
@Composable
private fun Unfilled(name: String) {
    TvEmptyState(title = name)
}

package com.luke.tracks.play

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.library.FakeLibrary
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.playback.IdlePlayer
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.sources.FakeSources
import com.luke.tracks.sources.PlayFromChoice
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.streaming.CatalogMedia
import com.luke.tracks.streaming.ServiceKey
import com.luke.tracks.streaming.ServiceStanding
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.nowplaying.NowPlayingScreen
import com.luke.tracks.ui.services.LocalServiceOf
import com.luke.tracks.ui.services.ServiceLibrary
import com.luke.tracks.ui.services.serviceLibraryItems
import com.luke.tracks.ui.sources.AddOption
import com.luke.tracks.ui.sources.LocalSources
import com.luke.tracks.ui.sources.SourcesPageContent
import com.luke.tracks.sources.SourceKind
import org.junit.Test

/** Spotify on the phone: where a song plays from, Play From with a service signed out, and what you saved. */
class ServiceScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private val spotify = TracksideCatalogService(
        "b1", "Spotify", logoKey = "spotify", capabilities = listOf("search", "library", "similar"),
        account = TracksideCatalogService.Account("ok"), quality = "lossless",
    )

    /** A Spotify song through the relay: Now Playing names Spotify, with its mark and quality, not the server. */
    @Test fun nowPlayingFromSpotify() {
        withSampleHistory()
        val song = FakeLibrary.index.snapshot.songs[3].let { it.copy(mediaId = CatalogMedia.mediaId("home", "mc1.sp"), artworkUri = null) }
        graph.player = IdlePlayer(
            PlayerState(current = song, isPlaying = true, positionMs = 61_000, durationMs = 201_000, queue = listOf(song), origin = QueueOrigin("Discover Weekly", QueueOrigin.Kind.Playlist), keepProgress = 0.3f),
        )
        snap("nowplaying-spotify", settleMs = 2500) {
            CompositionLocalProvider(
                LocalNavigator provides Navigator(),
                LocalSources provides FakeSources.registry(FakeSources.home),
                LocalServiceOf provides { id -> spotify.takeIf { CatalogMedia.isCatalog(id) } },
            ) { NowPlayingScreen(onClose = {}) }
        }
    }

    /** Spotify signed out on the server: it keeps its place, dimmed and unnumbered, with a word on why. */
    @Test fun playFromSignedOut() = snap("sources-play-from-signed-out") {
        CompositionLocalProvider(LocalNavigator provides Navigator()) {
            SourcesPageContent(
                sources = listOf(FakeSources.device, FakeSources.home),
                playFrom = listOf(FakeSources.device, FakeSources.home).map { PlayFromChoice(PlayFromPlace.From(it.id), it.name) } + listOf(
                    PlayFromChoice(PlayFromPlace.Service("spotify"), "Spotify", ServiceStanding.SignedOut),
                    PlayFromChoice(PlayFromPlace.Service(PlayFromPlace.APPLE_MUSIC), "Apple Music"),
                    PlayFromChoice(PlayFromPlace.Get, "Get"),
                ),
                options = listOf(AddOption("server", "Server", SourceKind.Server) {}),
                onOpen = {},
                onRemove = {},
                onReorder = {},
                get = true,
            )
        }
    }

    /** What you saved on Spotify, songs too, as Search shows it with Spotify chosen. */
    @Test fun savedOnSpotify() = snap("service-library-songs") {
        val tracks = listOf(
            TracksideCatalogTrack("mc1.t1", "b1", "Saltwater", "Mara Solis", album = "Glasshouse Weather", durationMs = 214_000),
            TracksideCatalogTrack("mc1.t2", "b1", "Manhattan Skyline at Four in the Morning, Extended Version", "The Long Names Society", album = "Night Shift", durationMs = 402_000),
            TracksideCatalogTrack("mc1.t3", "b1", "Maps", "Nova Harbor", durationMs = 188_000),
            TracksideCatalogTrack("mc1.t4", "b1", "Unplayable Here", "Kestrel Park", playable = false),
        )
        val playlists = listOf(
            TracksideCatalogItem("playlist", "mc1.p1", "b1", "Discover Weekly", owner = "Spotify"),
            TracksideCatalogItem("playlist", "mc1.p2", "b1", "Liked Songs", owner = "You"),
        )
        val albums = listOf(TracksideCatalogItem("album", "mc1.a1", "b1", "Glasshouse Weather", artist = "Mara Solis", year = 2024))
        CompositionLocalProvider(LocalNavigator provides Navigator()) {
            LazyColumn {
                serviceLibraryItems(ServiceKey("home", "b1"), spotify, ServiceLibrary.Loaded(playlists, albums, emptyList(), tracks), onRetry = {}, currentMediaId = CatalogMedia.mediaId("home", "mc1.t1"))
            }
        }
    }
}

package com.luke.tracks.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.requestFocus
import com.luke.tracks.library.FakeLibrary
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.library.trackside.TracksideCatalogService
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.speakers.ServerSpeakers
import com.luke.tracks.speakers.SpeakerControls
import com.luke.tracks.speakers.SpeakerKey
import com.luke.tracks.streaming.CatalogMedia
import com.luke.tracks.streaming.ServiceKey
import com.luke.tracks.streaming.ServiceStanding
import com.luke.tracks.ui.devices.DeviceSection
import com.luke.tracks.ui.devices.DevicesUi
import com.luke.tracks.ui.devices.SpeakerUi
import com.luke.tracks.ui.services.ServiceLibrary
import com.luke.tracks.ui.tv.components.LocalTvFocusStore
import com.luke.tracks.ui.tv.components.TvFocusStore
import com.luke.tracks.ui.tv.library.TvLibraryContent
import com.luke.tracks.ui.tv.library.TvServiceTab
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.nowplaying.SongMarks
import com.luke.tracks.ui.tv.nowplaying.SpeakerPlayer
import com.luke.tracks.ui.tv.nowplaying.TvDeviceActions
import com.luke.tracks.ui.tv.nowplaying.TvDevicesPanel
import com.luke.tracks.ui.tv.nowplaying.TvNowPlaying
import com.luke.tracks.ui.tv.settings.TvSettingsScreen
import com.luke.tracks.ui.tv.settings.TvSourcesContent
import org.junit.Test

/** Spotify on Google TV: the service under the song, its library tab, Play On, and Play From with it signed out. */
class TvServiceScreenshots(dark: Boolean) : TvScreenshotTest(dark) {
    private val spotify = TracksideCatalogService(
        "b1", "Spotify", logoKey = "spotify", capabilities = listOf("search", "library", "similar"),
        account = TracksideCatalogService.Account("ok"), quality = "high",
    )

    @Composable
    private fun Claimed(destination: TvDestination = TvDestination.Library, content: @Composable () -> Unit) {
        val store = remember { TvFocusStore().apply { requestClaim("") } }
        CompositionLocalProvider(LocalTvFocusStore provides store, LocalTvNavigator provides remember { TvNavigator(destination) }) { content() }
    }

    private object NoActions : TvDeviceActions {
        override fun bringHere() = Unit
        override fun castTo(key: SpeakerKey) = Unit
        override fun choose(key: SpeakerKey) = Unit
        override fun setVolume(key: SpeakerKey, volume: Int) = Unit
        override fun setMembers(key: SpeakerKey, memberIds: List<String>) = Unit
        override fun leaveGroup(key: SpeakerKey) = Unit
    }

    private fun speaker(id: String, name: String, state: String = "idle", volume: Int? = 40, availability: String = "available", group: TracksideSpeaker.Group? = null, with: List<String>? = null, playing: String? = null) = TracksideSpeaker(
        id = id, name = name, kind = "speaker", availability = availability,
        capabilities = listOf("play", "pause", "next_previous", "seek", "volume", "group", "shuffle", "repeat"),
        volume = volume, group = group, canGroupWith = with,
        playback = TracksideSpeaker.Playback(state = state, positionMs = 61_000),
        nowPlaying = playing?.let { TracksideSpeaker.NowPlaying(title = it, artist = "Mara Solis", album = "Glasshouse Weather", durationMs = 214_000, itemId = "q1", origin = "queue") },
    )

    private fun ui(speaker: TracksideSpeaker, chosen: Boolean, members: List<String> = emptyList()) = SpeakerUi(
        key = SpeakerKey("home", speaker.id), speaker = speaker, controls = SpeakerControls.of(speaker), canControl = true,
        connecting = false, chosen = chosen, memberNames = members, leaderName = null, artwork = null,
    )

    private val kitchen = speaker("k1", "Kitchen", state = "playing", playing = "Saltwater", with = listOf("l1", "b1"), group = TracksideSpeaker.Group("k1", listOf("k1", "l1"), "leader"))
    private val lounge = speaker("l1", "Lounge", group = TracksideSpeaker.Group("k1", listOf("k1", "l1"), "member"))
    private val bedroom = speaker("b1", "Bedroom")
    private val porch = speaker("p1", "Porch", availability = "unavailable")

    private fun devices(chosen: Boolean): DevicesUi {
        val all = listOf(ui(kitchen, chosen, if (chosen) listOf("Lounge") else emptyList()), ui(lounge, false), ui(bedroom, false), ui(porch, false))
        return DevicesUi(
            offered = true,
            sections = listOf(DeviceSection("home", null, "Home", ServerSpeakers.Status.Ok, all.filter { it.speaker.id != "l1" }, all)),
            active = if (chosen) SpeakerKey("home", "k1") else null,
            casting = all.first().takeIf { chosen },
        )
    }

    private val song = TvLinkDemo.songs[0].copy(mediaId = CatalogMedia.mediaId("home", "mc1.sp"))

    /** A Spotify song: Spotify and its quality under the song, and Play On among the actions. */
    @Test fun nowPlayingFromSpotify() = snap("nowplaying-spotify") {
        Claimed(TvDestination.Play) {
            val state = playerState(song, queue = listOf(song)).copy(origin = QueueOrigin("Discover Weekly", QueueOrigin.Kind.Playlist))
            val player = remember { FakePlayer(state, 74_000) }
            TvNowPlaying(state, player.position, player, null, SongMarks(false, false), onLike = {}, onHearLess = { _, _ -> }, onClose = {}, service = spotify, devices = devices(false), deviceActions = NoActions)
        }
    }

    /** Play On open over the player: this TV chosen, the server's speakers, one offline. */
    @Test fun playOn() = snap("play-on") {
        Claimed(TvDestination.Play) {
            val state = playerState(song, queue = listOf(song))
            val player = remember { FakePlayer(state, 74_000) }
            Box(Modifier.fillMaxSize()) {
                TvNowPlaying(state, player.position, player, null, SongMarks(false, false), onLike = {}, onHearLess = { _, _ -> }, onClose = {}, service = spotify)
                TvDevicesPanel(visible = true, ui = devices(false), actions = NoActions, onDismiss = {})
            }
        }
    }

    /** Playing on the Kitchen speaker, grouped with the Lounge: the player shows and steers it, with its volume and group in Play On. */
    @Test fun playingOnASpeaker() = snap("play-on-speaker") {
        Claimed(TvDestination.Play) {
            val ui = devices(true)
            val cast = ui.casting!!
            val state = SpeakerPlayer.stateOf(cast)
            val player = remember { FakePlayer(state, 61_000) }
            Box(Modifier.fillMaxSize()) {
                TvNowPlaying(state, player.position, player, null, SongMarks(false, false), onLike = {}, onHearLess = { _, _ -> }, onClose = {}, devices = ui, deviceActions = NoActions, castingTo = cast)
                TvDevicesPanel(visible = true, ui = ui, actions = NoActions, onDismiss = {})
            }
        }
    }

    private val tab = TvServiceTab(ServiceKey("home", "b1"), spotify, "Spotify")

    private val saved = ServiceLibrary.Loaded(
        playlists = listOf(
            TracksideCatalogItem("playlist", "mc1.p1", "b1", "Discover Weekly", owner = "Spotify"),
            TracksideCatalogItem("playlist", "mc1.p2", "b1", "Release Radar", owner = "Spotify"),
            TracksideCatalogItem("playlist", "mc1.p3", "b1", "Liked Songs", owner = "You"),
        ),
        albums = listOf(
            TracksideCatalogItem("album", "mc1.a1", "b1", "Glasshouse Weather", artist = "Mara Solis"),
            TracksideCatalogItem("album", "mc1.a2", "b1", "Manifesto for Quiet Rooms and Other Small Victories", artist = "The Long Names Society"),
        ),
        artists = listOf(TracksideCatalogItem("artist", "mc1.r1", "b1", "Mara Solis"), TracksideCatalogItem("artist", "mc1.r2", "b1", "Nova Harbor")),
        songs = listOf(
            TracksideCatalogTrack("mc1.t1", "b1", "Saltwater", "Mara Solis"),
            TracksideCatalogTrack("mc1.t2", "b1", "Maps", "Nova Harbor"),
        ),
    )

    /** Library with your music's tabs, then Spotify's, opened on what you saved there. */
    @Test fun libraryServiceTab() = snap("library-spotify", settleMs = 2500) {
        Claimed {
            TvLibraryContent(FakeLibrary.index.snapshot, linked = true, onLink = {}, services = listOf(tab), loadService = { saved }, initialService = tab.id)
        }
    }

    /** Nothing saved on Spotify yet, and no music of your own: Spotify's tab alone. */
    @Test fun libraryServiceEmpty() = snap("library-spotify-empty", settleMs = 2500) {
        Claimed {
            TvLibraryContent(com.luke.tracks.library.LibrarySnapshot(), linked = true, onLink = {}, services = listOf(tab), loadService = { ServiceLibrary.Loaded(emptyList(), emptyList(), emptyList()) })
        }
    }

    /** Spotify signed out on the server: its tab says what to do. */
    @Test fun libraryServiceSignedOut() = snap("library-spotify-signed-out", settleMs = 2500) {
        val out = tab.copy(service = spotify.copy(account = TracksideCatalogService.Account("signed_out")))
        Claimed { TvLibraryContent(FakeLibrary.index.snapshot, linked = true, onLink = {}, services = listOf(out), loadService = { saved }, initialService = out.id) }
    }

    /** Play From with Spotify signed out: dimmed, unnumbered, its word under the name; selected, it says what to do. */
    @Test fun playFromSignedOut() {
        val names = mapOf(
            PlayFromPlace.Service("spotify") to "Spotify",
            PlayFromPlace.Service(PlayFromPlace.APPLE_MUSIC) to "Apple Music",
            PlayFromPlace.Get to "Get",
        )
        show {
            var order by remember { mutableStateOf(listOf(PlayFromPlace.From(TvSourcesDemo.living.id) as PlayFromPlace) + names.keys) }
            Claimed(TvDestination.Settings) {
                TvSettingsScreen {
                    TvSourcesContent(
                        sources = listOf(TvSourcesDemo.living),
                        selection = SourceSelection.All,
                        playFrom = order,
                        onSelect = {},
                        onReorder = { order = it },
                        onRefresh = {},
                        onUnlink = {},
                        placeNames = names,
                        placeStandings = mapOf(PlayFromPlace.Service("spotify") to ServiceStanding.SignedOut),
                        get = true,
                    )
                }
            }
        }
        capture("settings-sources-signed-out")
        compose.onNode(hasStateDescription("2 of 4, Signed Out")).requestFocus()
        settle(600)
        press(listOf(Key.DirectionCenter))
        settle(800)
        capture("settings-sources-signed-out-help")
    }
}

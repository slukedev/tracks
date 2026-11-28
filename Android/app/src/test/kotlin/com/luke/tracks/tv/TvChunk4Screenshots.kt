package com.luke.tracks.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RadioState
import com.luke.tracks.playback.RepeatChoice
import com.luke.tracks.ui.tv.components.LocalTvFocusStore
import com.luke.tracks.ui.tv.components.TvFocusStore
import com.luke.tracks.ui.tv.link.ChooseServerContent
import com.luke.tracks.ui.tv.link.LinkCodeContent
import com.luke.tracks.ui.tv.link.LinkPoll
import com.luke.tracks.ui.tv.link.LinkProblem
import com.luke.tracks.ui.tv.link.LinkStep
import com.luke.tracks.ui.tv.link.LocalLinkDirectory
import com.luke.tracks.ui.tv.link.LocalTvLinker
import com.luke.tracks.ui.tv.link.TypeAddressContent
import com.luke.tracks.ui.tv.nowplaying.NowPlayingIdle
import com.luke.tracks.ui.tv.nowplaying.SongMarks
import com.luke.tracks.ui.tv.nowplaying.TvNowPlaying
import com.luke.tracks.ui.tv.nowplaying.TvQueuePanel
import com.luke.tracks.ui.tv.onboarding.TvOnboarding
import com.luke.tracks.ui.tv.onboarding.TvOnboardingStep
import com.luke.tracks.ui.tv.settings.HiddenSong
import com.luke.tracks.ui.tv.settings.SyncSwitch
import com.luke.tracks.ui.tv.settings.TvAppearanceContent
import com.luke.tracks.ui.tv.settings.TvHiddenSongsContent
import com.luke.tracks.ui.tv.settings.TvSettingsScreen
import com.luke.tracks.ui.tv.settings.TvSourcesContent
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.SourceSelection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.requestFocus
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.navigation.TvDestination
import com.luke.tracks.ui.tv.navigation.TvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout
import org.junit.Test
import uniffi.tracks_engine.RadioReason

/** Now Playing, Settings, linking and the first run, light and dark. */
class TvChunk4Screenshots(dark: Boolean) : TvScreenshotTest(dark) {
    /** Hands focus to whatever claims it first, as the shell does on opening a page. */
    @Composable
    private fun Claimed(linker: FakeLinker = FakeLinker(), servers: List<com.luke.tracks.ui.tv.link.LinkServer> = listOf(TvLinkDemo.server, TvLinkDemo.other), content: @Composable () -> Unit) {
        val store = remember { TvFocusStore().apply { requestClaim("") } }
        CompositionLocalProvider(
            LocalTvFocusStore provides store,
            LocalTvLinker provides linker,
            LocalLinkDirectory provides FakeDirectory(servers),
            LocalTvNavigator provides remember { TvNavigator(TvDestination.Settings) },
        ) { content() }
    }

    @Composable
    private fun Player(state: com.luke.tracks.playback.PlayerState, radio: RadioState? = null, marks: SongMarks = SongMarks(false, false), position: Long = 74_000) {
        val player = remember { FakePlayer(state, position) }
        TvNowPlaying(state, player.position, player, radio, marks, onLike = {}, onHearLess = { _, _ -> }, onClose = {})
    }

    // MARK: Now Playing

    @Test fun nowPlaying() {
        snap("nowplaying-playing") { Claimed { Player(playerState()) } }
    }

    @Test fun nowPlayingPaused() {
        snap("nowplaying-paused") { Claimed { Player(playerState(playing = false).copy(shuffle = true, repeat = RepeatChoice.One), marks = SongMarks(liked = true, hidden = false)) } }
    }

    @Test fun nowPlayingLongTitles() {
        val long = TvLinkDemo.songs[1].copy(
            title = "A Remarkably Long Song Title That Keeps Going Well Past the Edge (Extended Live Version)",
            artistName = "The Artist With an Equally Long Name and Their Orchestra",
            albumTitle = "Live at the Very Long Venue Name, Recorded Over Three Nights in the Spring",
        )
        snap("nowplaying-long") { Claimed { Player(playerState(long, queue = listOf(long)).copy(origin = QueueOrigin("A Very Long Playlist Name for Late Evenings", QueueOrigin.Kind.Playlist))) } }
    }

    @Test fun nowPlayingRadio() {
        val song = TvLinkDemo.songs[2]
        val radio = RadioState(picks = mapOf(song.mediaId to RadioReason.MostPlayed))
        snap("nowplaying-radio") { Claimed { Player(playerState(song, origin = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio)), radio = radio) } }
    }

    @Test fun nowPlayingResting() {
        show { Claimed { Player(playerState()) } }
        settle(NowPlayingIdle.IdleAfterMs + 1_500)
        capture("nowplaying-idle")
    }

    @Test fun nowPlayingQueue() {
        val state = playerState(TvLinkDemo.songs[1])
        snap("nowplaying-queue") {
            Claimed {
                Box(Modifier.fillMaxSize()) {
                    Player(state)
                    TvQueuePanel(visible = true, state = state, radio = null, player = remember { FakePlayer(state) }, onDismiss = {})
                }
            }
        }
    }

    @Test fun nowPlayingQueueEnd() {
        val song = TvLinkDemo.songs[0]
        val state = playerState(song, queue = listOf(song))
        snap("nowplaying-queue-end") {
            Claimed {
                Box(Modifier.fillMaxSize()) {
                    Player(state)
                    TvQueuePanel(visible = true, state = state, radio = null, player = remember { FakePlayer(state) }, onDismiss = {})
                }
            }
        }
    }

    @Test fun nowPlayingSeeking() {
        show { Claimed { Player(playerState(playing = false)) } }
        press(listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionDown, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight))
        capture("nowplaying-secondary-focus")
    }

    // MARK: Settings

    @Test fun settingsSourcesNoServer() {
        snap("settings-sources-none") { Claimed { TvSettingsScreen("sources") } }
    }

    @Test fun settingsAppearance() {
        snap("settings-appearance") { Claimed { TvSettingsScreen("appearance") } }
    }

    @Test fun settingsRadio() {
        withSampleHistory()
        snap("settings-radio", settleMs = 2_500) { Claimed { TvSettingsScreen("radio") } }
    }

    @Test fun settingsHidden() {
        snap("settings-hidden") { Claimed { TvSettingsScreen("hidden") } }
    }

    @Test fun settingsAbout() {
        snap("settings-about") { Claimed { TvSettingsScreen("about") } }
    }

    @Test fun settingsLinkCode() {
        show { Claimed { TvSettingsScreen("sources") } }
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        settle(1_000)
        capture("settings-sources-code")
    }

    @Test fun settingsIntoThePage() {
        show { Claimed { TvSettingsScreen("appearance") } }
        press(listOf(Key.DirectionRight, Key.DirectionDown))
        capture("settings-appearance-focused")
    }

    /** The page on its own, where the shell puts it. */
    @Composable
    private fun Pane(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().padding(start = TvLayout.ContentStart + 296.dp, end = TvLayout.SafeEnd)) { content() }
    }

    @Composable
    private fun Sources(
        sources: List<com.luke.tracks.sources.Source> = TvSourcesDemo.servers,
        selection: SourceSelection = SourceSelection.All,
        sync: SyncSwitch? = null,
    ) {
        var order by remember { mutableStateOf(sources.map { PlayFromPlace.From(it.id) as PlayFromPlace }) }
        var chosen by remember { mutableStateOf(selection) }
        // In the whole screen, as it's seen: the list stays put while the page scrolls.
        Claimed {
            TvSettingsScreen {
                TvSourcesContent(
                    sources = sources,
                    selection = chosen,
                    playFrom = order,
                    onSelect = { chosen = it },
                    onReorder = { order = it },
                    onRefresh = {},
                    onUnlink = {},
                    syncFor = { sync },
                )
            }
        }
    }

    private fun focus(matcher: SemanticsMatcher) {
        compose.onNode(matcher).requestFocus()
        settle(600)
    }

    @Test fun sourcesInSettings() {
        val registry = com.luke.tracks.sources.FakeSources.registry(*TvSourcesDemo.servers.toTypedArray(), name = "tv-sources-shot")
        registry.select(SourceSelection.Only(TvSourcesDemo.den.id))
        snap("settings-sources") {
            CompositionLocalProvider(com.luke.tracks.ui.sources.LocalSources provides registry) { Claimed { TvSettingsScreen() } }
        }
    }

    @Test fun sourcesManyScrolled() {
        val many = TvSourcesDemo.servers + (1..6).map { i ->
            com.luke.tracks.sources.Source(
                com.luke.tracks.sources.SourceId.Server("extra-$i"), "Server $i",
                com.luke.tracks.sources.SourceKind.Server, com.luke.tracks.sources.SourceStatus.Connected, songCount = i * 1_000,
            )
        }
        show { Sources(many) }
        focus(hasStateDescription("8 of 9"))
        press(listOf(Key.DirectionCenter, Key.DirectionDown))
        capture("settings-sources-many")
    }

    @Test fun sourcesOne() = snap("settings-sources-one") { Sources(listOf(TvSourcesDemo.living)) }

    @Test fun sourcesSeveral() = snap("settings-sources-several") { Sources() }

    @Test fun sourcesOnlyOne() {
        show { Sources(selection = SourceSelection.Only(TvSourcesDemo.den.id)) }
        focus(hasText("Den") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        capture("settings-sources-only-one")
    }

    @Test fun sourcesLifted() {
        show { Sources() }
        focus(hasStateDescription("1 of 3"))
        press(listOf(Key.DirectionCenter, Key.DirectionDown))
        capture("settings-sources-lifted")
    }

    @Test fun sourcesServer() {
        show { Sources(sync = SyncSwitch(on = true, line = "Up to date", problem = false)) }
        focus(hasText("Living Room Server") and hasText("12,480 songs"))
        press(Key.DirectionCenter)
        capture("settings-sources-server")
    }

    @Test fun sourcesServerOffline() {
        show { Sources(sync = SyncSwitch(on = false, line = null, problem = false)) }
        focus(hasText("Attic NAS") and hasText("Offline"))
        press(Key.DirectionCenter)
        capture("settings-sources-server-offline")
    }

    @Test fun sourcesServerSignedOut() {
        val signedOut = TvSourcesDemo.living.copy(status = com.luke.tracks.sources.SourceStatus.SignIn, songCount = null)
        show { Sources(listOf(signedOut), sync = SyncSwitch(on = false, line = "Paired again. Select to keep syncing.", problem = true)) }
        focus(hasText("Living Room Server") and hasText("Signed Out"))
        press(Key.DirectionCenter)
        capture("settings-sources-server-signedout")
    }

    @Test fun sourcesUnlink() {
        show { Sources(listOf(TvSourcesDemo.living)) }
        focus(hasText("Living Room Server") and hasText("12,480 songs"))
        press(listOf(Key.DirectionCenter, Key.DirectionDown, Key.DirectionCenter))
        capture("settings-sources-unlink")
    }

    @Test fun appearanceCustom() {
        snap("settings-appearance-custom") {
            Claimed { Pane { TvAppearanceContent(AppSettings(colors = ColorChoice.Custom, seedColor = 0xFF0288D1.toInt(), pureBlack = true)) {} } }
        }
    }

    @Test fun hiddenSongs() {
        val songs = TvLinkDemo.songs.map { s -> HiddenSong(s.identity, s.title, s.artistName, null) } +
            HiddenSong("long", "A Song Whose Title Is Far Too Long to Fit on One Line of the List", "Somebody", null)
        snap("settings-hidden-songs") { Claimed { Pane { TvHiddenSongsContent(songs) {} } } }
    }

    @Test fun hiddenLoading() {
        snap("settings-hidden-loading") { Claimed { Pane { TvHiddenSongsContent(null) {} } } }
    }

    // MARK: Linking

    @Composable
    private fun Framed(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().padding(start = 96.dp, top = 120.dp)) { Box(Modifier.width(620.dp)) { content() } }
    }

    @Test fun linkLooking() {
        snap("link-looking") { Claimed { Framed { ChooseServerContent(emptyList(), remember { FocusRequester() }, {}, {}) } } }
    }

    @Test fun linkServers() {
        snap("link-servers") { Claimed { Framed { ChooseServerContent(listOf(TvLinkDemo.server, TvLinkDemo.other), remember { FocusRequester() }, {}, {}) } } }
    }

    @Test fun linkAddressProblem() {
        snap("link-address") { Claimed { Framed { TypeAddressContent(rememberTextFieldState("music.example"), false, LinkProblem.NotTrackside, remember { FocusRequester() }, {}, {}) } } }
    }

    @Test fun linkCode() {
        snap("link-code") { Claimed { Framed { LinkCodeContent(LinkStep.Showing(TvLinkDemo.offer()), TvLinkDemo.server.name, remember { FocusRequester() }, {}, {}) } } }
    }

    @Test fun linkStarting() {
        snap("link-starting") { Claimed { Framed { LinkCodeContent(LinkStep.Starting, TvLinkDemo.server.name, remember { FocusRequester() }, {}, {}) } } }
    }

    @Test fun linkNeedsUpdate() {
        snap("link-needs-update") { Claimed { Framed { LinkCodeContent(LinkStep.Failed(LinkProblem.NeedsUpdate), TvLinkDemo.server.name, remember { FocusRequester() }, {}, {}) } } }
    }

    @Test fun linkOffline() {
        snap("link-offline") { Claimed { Framed { LinkCodeContent(LinkStep.Failed(LinkProblem.Offline), TvLinkDemo.server.name, remember { FocusRequester() }, {}, {}) } } }
    }

    @Test fun linkLinked() {
        snap("link-linked") { Claimed { Framed { LinkCodeContent(LinkStep.Linked("Living Room Server"), TvLinkDemo.server.name, remember { FocusRequester() }, {}, {}) } } }
    }

    // MARK: First run

    @Test fun onboardingWelcome() {
        snap("onboarding-welcome") { Claimed { TvOnboarding(linkedTo = null, onDone = {}) } }
    }

    @Test fun onboardingLink() {
        snap("onboarding-link") { Claimed { TvOnboarding(linkedTo = null, onDone = {}, start = TvOnboardingStep.Link) } }
    }

    @Test fun onboardingLinkCode() {
        show { Claimed(linker = FakeLinker(polls = listOf(LinkPoll.Pending))) { TvOnboarding(linkedTo = null, onDone = {}, start = TvOnboardingStep.Link) } }
        press(Key.DirectionCenter)
        settle(1_000)
        capture("onboarding-link-code")
    }

    @Test fun onboardingLinked() {
        snap("onboarding-linked") { Claimed { TvOnboarding(linkedTo = "Living Room Server", onDone = {}, start = TvOnboardingStep.Link) } }
    }

    @Test fun onboardingReady() {
        snap("onboarding-ready") { Claimed { TvOnboarding(linkedTo = null, onDone = {}, start = TvOnboardingStep.Ready) } }
    }
}

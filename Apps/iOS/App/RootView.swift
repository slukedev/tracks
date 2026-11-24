import SwiftUI
import UIKit
import MusicKit
import TracksCore
import TracksMusic

struct RootView: View {
    @Bindable var model: AppModel
    @AppStorage(PlayPreferences.songDestinationKey) private var songDestination = SongDestination.tracks
    @State private var summaryPath = LaunchScene.route.map { NavigationPath([$0]) } ?? NavigationPath()
    /// So a link inside a row, like an artist's name on a collaboration, can push.
    @State private var historyPath = NavigationPath()
    @State private var libraryPath = NavigationPath()
    /// Where the app opens also decides where Play sits: first when it's where Tracks opens,
    /// last otherwise, so the opening tab is always far left.
    @AppStorage(OpeningTab.storageKey) private var openingTab: OpeningTab = .standard
    @State private var handoff = PlayerHandoff.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var wasInBackground = false
    /// Made once: a new one on each redraw, as a tab switch makes, would redraw every song
    /// menu that reads it, since it can't be compared.
    @State private var showSongInfo: ShowSongInfoAction
    /// The tab bar's selection, kept in step with `model.selectedTab` by `TabMemory`. Bound to
    /// the model directly, every tab switch redrew this view and with it every tab's stack.
    @State private var selection: AppTab

    init(model: AppModel) {
        self.model = model
        _showSongInfo = State(initialValue: ShowSongInfoAction { [model] in model.songInfo.show($0) })
        // Not `model.selectedTab`: read here, in the parent's update, it would redraw the parent.
        _selection = State(initialValue: AppModel.launchTab)
        Self.tintSelectedTab()
    }

    /// The selected tab in the accent's pink, on every tab, Search included. Over a dark page
    /// the bar turns dark, and there the pink is a pale shade of itself: over a deep red cover's
    /// page the accent's own dark pink measured 2.67:1, this one 4.5:1 on the bar's red lozenge.
    /// Set on the bar alone, since a `tint` on the tab view also tints every toolbar, which
    /// stay monochrome.
    private static func tintSelectedTab() {
        let accent = UIColor(named: "AccentColor") ?? .systemPink
        let paleAccent = UIColor(red: 1, green: 0.88, blue: 0.92, alpha: 1)
        UITabBar.appearance().tintColor = UIColor { traits in
            traits.userInterfaceStyle == .dark ? paleAccent : accent.resolvedColor(with: traits)
        }
    }

    var body: some View {
        if let store = model.store, let capture = model.capture, let playback = model.playback {
            @Bindable var player = model.player
            let navigator = model.playNavigator
            // A stable identity per tab, so moving Play between the ends keeps each tab's
            // state, and any sheet open on it, rather than rebuilding it.
            TabView(selection: $selection) {
                ForEach(tabOrder, id: \.self) { tab in
                    Tab(tab.title, systemImage: tab.symbol, value: tab) {
                        stack(for: tab, navigator: navigator)
                    }
                }
                // At the bar's far end, on its own, as in Music.
                Tab(value: AppTab.search, role: .search) {
                    stack(for: .search, navigator: navigator)
                }
            }
            .tabBarMinimizeBehavior(.onScrollDown)
            .tabViewBottomAccessory(isEnabled: player.hasQueue || model.controlledDevice != nil) {
                // Another device's song while it's the one being controlled, as Spotify
                // Connect shows it; otherwise this iPhone's.
                if let device = model.controlledDevice {
                    RemoteMiniPlayer(device: device)
                } else {
                    MiniPlayer(open: openNowPlaying, onNavigate: go(to:))
                }
            }
            .fullScreenCover(isPresented: $handoff.isPresented) {
                NowPlayingView(onNavigate: go(to:))
                    // Clear, so the tabs show above the player as it's pulled down.
                    .presentationBackground(.clear)
                    .songInfoSheet(model) { route in
                        handoff.isPresented = false
                        go(to: .play(route))
                    }
            }
            #if DEBUG
            .overlay {
                if let style = BackdropLab.requested {
                    BackdropLab(style: style).ignoresSafeArea()
                }
            }
            #endif
            // The song a shake finds plays in Now Playing, so you see what it is.
            .shakeToPlay(model.player, onPlay: openNowPlaying)
            // Songs being added to a playlist, from a menu anywhere.
            .sheet(item: Bindable(model.yourMusic.playlists).picking) { PlaylistPickerSheet(pick: $0) }
            // Get Info, from any song's menu. Now Playing shows its own over itself.
            .songInfoSheet(model, isActive: !handoff.isPresented) { go(to: .play($0)) }
            // Under Now Playing while it's open; it shows its own.
            .playerFeedback(isActive: !handoff.isPresented)
            // Someone else's SharePlay, joined from Messages.
            .sharePlayGuestPage(closing: $handoff.isPresented)
            .opensDeepLinks(in: model)
            .onboarding(model)
            .modifier(TabMemory(model: model, selection: $selection))
            // A song from a widget. Summary is the tab whose stack can be pushed from here.
            // Initial too, for a link that launched the app.
            .onChange(of: model.pendingRoute, initial: true) { _, route in
                guard let route else { return }
                model.selectedTab = .summary
                summaryPath = NavigationPath([route])
                model.pendingRoute = nil
            }
            // The history and the mixes follow the store in AppModel, for the whole process:
            // Tracks also plays with no window, for CarPlay and Siri.
            .onChange(of: model.playFeed.builtRevision) {
                // Sample data's own music is made from the sample history, now it's read.
                model.prepareYourMusic()
                LaunchScene.startDemoPlayback(model)
                LaunchScene.openMix(model)
                #if DEBUG
                Task { await CarPlayImages.writeSampleSheet(model) }
                #endif
            }
            .task { await model.playFeed.followSubscription() }
            #if DEBUG
            .onChange(of: model.library.isLoaded, initial: true) { _, isLoaded in
                if isLoaded, let subject = SongInfoSamples.requested(in: model.library.history) {
                    model.songInfo.show(subject)
                }
            }
            #endif
            .onChange(of: player.hasQueue) { _, hasQueue in
                if hasQueue, LaunchScene.opensNowPlaying { openNowPlaying() }
            }
            .modelContainer(store.container)
            .environment(model)
            .environment(capture)
            .environment(playback)
            .environment(model.player)
            .environment(model.playFeed)
            .environment(model.discovery)
            .environment(model.yourMusic)
            .environment(model.lidarr)
            .environment(model.downloadedSongs)
            .environment(model.playNavigator)
            // A song's Play button plays here, where the play is kept, unless Settings sends
            // songs to Apple Music.
            .environment(\.playSongs, songDestination == .tracks ? PlaySongsAction { items, title in
                player.play(.history(items.map(HistorySong.init)), from: .songs(title))
            } : nil)
            .environment(\.showSongInfo, showSongInfo)
            .task { await model.startCapture() }
            // Lidarr's actions show wherever music does, whatever the source.
            .task { await model.lidarr.check() }
            // Your other devices nearby, and what's playing here for them.
            .task {
                guard !model.isDemoLaunch else { return }
                model.nearby.start()
                model.followSpeakers()
            }
            .onPlayingChange(of: player) { model.tellNearby() }
            // A song picked up from another of your devices through Handoff.
            .onContinueUserActivity(NowPlayingHandoff.activityType) { model.continueHandoff($0) }
            // Playlists merged with Apple Music, once your servers have had a moment to answer.
            .task {
                guard !model.isDemoLaunch else { return }
                try? await Task.sleep(for: .seconds(5))
                await model.yourMusic.playlistMerge.mergeIfOn()
            }
            .onChange(of: model.musicSource, initial: true) { old, source in
                // Pages pushed for one source mean nothing in the other.
                if old != source {
                    model.playNavigator.path = NavigationPath()
                    model.search.path = NavigationPath()
                    libraryPath = NavigationPath()
                }
                // Read once whatever the filter: Sources and Library list this device's songs.
                model.prepareYourMusic()
            }
            // A source added or gone: Play stays on one that has music, and Shortcuts offer
            // the sources there are.
            .onChange(of: model.playableSources) {
                model.followSources()
                if !model.isDemoLaunch { TracksShortcuts.updateAppShortcutParameters() }
            }
            .onChange(of: scenePhase) { _, phase in
                // Coming back is when to send what's still owed and fold in what synced.
                // Launch is covered by `startCapture`.
                if phase == .background {
                    wasInBackground = true
                } else if phase == .active, wasInBackground, !model.isDemoLaunch {
                    wasInBackground = false
                    Task {
                        await PlayerQuiet.settled()
                        await capture.catchUp()
                    }
                    Task {
                        await PlayerQuiet.settled()
                        await model.yourMusic.playlistMerge.mergeIfOn()
                    }
                    model.nearby.resume()
                    // Songs dropped into Files while Tracks was away.
                    Task { await model.yourMusic.scan() }
                }
            }
        } else {
            ContentUnavailableView {
                Label("Tracks Can't Open Your History", systemImage: "exclamationmark.triangle")
            } description: {
                Text("Close Tracks and open it again. If that doesn’t help, restart your iPhone.")
            }
        }
    }

    /// The tab Tracks opens to is far left: Play and Library first when it opens to Play,
    /// last otherwise.
    private var tabOrder: [AppTab] {
        openingTab == .play ? [.play, .library, .summary, .history] : [.summary, .history, .play, .library]
    }

    @ViewBuilder
    private func stack(for tab: AppTab, navigator: PlayNavigator) -> some View {
        switch tab {
        case .summary:
            NavigationStack(path: $summaryPath) {
                SummaryScreen()
                    .tracksDestinations()
                    .playDestinations()
            }
            .environment(\.openPlayRoute, OpenPlayRouteAction(stack: "summary") { summaryPath.append($0) })
        case .play:
            @Bindable var navigator = navigator
            NavigationStack(path: $navigator.path) {
                Group {
                    switch model.musicSource {
                    case .yourMusic: YourMusicScreen()
                    case .appleMusic: AppleMusicPlayScreen()
                    case .spotify:
                        #if SPOTIFY
                        SpotifyPlayScreen()
                        #else
                        AppleMusicPlayScreen()
                        #endif
                    }
                }
                .sourceFilter()
                .playDestinations()
                .tracksDestinations()
            }
            .environment(\.openPlayRoute, OpenPlayRouteAction(stack: "play") { navigator.show($0) })
        case .library:
            NavigationStack(path: $libraryPath) {
                LibraryScreen()
                    .playDestinations()
                    .tracksDestinations()
            }
            .environment(\.openPlayRoute, OpenPlayRouteAction(stack: "library") { libraryPath.append($0) })
        case .history:
            NavigationStack(path: $historyPath) {
                HistoryTab()
                    .tracksDestinations()
                    .playDestinations()
            }
            .environment(\.openPlayRoute, OpenPlayRouteAction(stack: "history") { historyPath.append($0) })
        case .search:
            @Bindable var search = model.search
            NavigationStack(path: $search.path) {
                SearchPage()
                    .playDestinations()
                    .tracksDestinations()
            }
            .environment(\.openPlayRoute, OpenPlayRouteAction(stack: "search") { search.path.append($0) })
        }
    }

    /// Without the system's animation: Now Playing brings itself up from the mini player
    /// (see PlayerHandoff). Tapped while it's still closing, it comes back.
    private func openNowPlaying() {
        handoff.open()
    }

    /// After Now Playing closes: onto the Play tab's stack, or Summary's for your stats.
    private func go(to destination: NowPlayingDestination) {
        switch destination {
        case .play(let route):
            model.selectedTab = .play
            model.playNavigator.show(route)
        case .stats(let route):
            model.selectedTab = .summary
            summaryPath.append(route)
        }
    }
}

/// Keeps the open tab for the scene, and the tab bar's selection in step with the model's.
/// Its own view, so a tab switch (read here, and written to scene storage) redraws only this,
/// not the root and with it every tab's stack.
private struct TabMemory: ViewModifier {
    let model: AppModel
    @Binding var selection: AppTab
    /// The tab open when iOS last saved the scene. iOS keeps it when it ends Tracks in the
    /// background and drops it when someone swipes Tracks away, so a quiet relaunch comes
    /// back where you were and a fresh start opens where Settings says.
    @SceneStorage("selectedTab") private var savedTab = ""

    func body(content: Content) -> some View {
        content
            .onAppear(perform: restoreTab)
            .onChange(of: model.selectedTab, initial: true) { old, tab in
                if old != tab { savedTab = tab.rawValue }
                if selection != tab { selection = tab }
            }
            .onChange(of: selection) { _, tab in
                if model.selectedTab != tab { model.selectedTab = tab }
            }
    }

    /// Back on the saved tab, unless the launch itself asked for a place.
    private func restoreTab() {
        guard let tab = AppTab(launchName: savedTab), model.pendingRoute == nil,
              LaunchScene.tab == nil, LaunchScene.route == nil,
              !LaunchScene.opensSearch, LaunchScene.searchText == nil
        else { return }
        if savedTab == AppTab.chartsName { model.historyMode = .charts }
        model.selectedTab = tab
    }
}

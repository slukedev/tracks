import SwiftUI
import TracksCore
#if DEBUG
import MusicKit
#endif

/// The main window: the sidebar of places, the page chosen, and the song playing at the foot
/// of it, with Up Next and the song's history in a popover rising from it.
struct MacRootView: View {
    @Bindable var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @AppStorage(OpeningTab.storageKey) private var openingTab: OpeningTab = .standard
    @AppStorage(PlayPreferences.songDestinationKey) private var songDestination = SongDestination.tracks
    /// The panel and the full player: the window's, not the app's.
    @State private var playerWindow = PlayerWindowState()
    @State private var history = NavigationHistory()
    /// Tracks Radio's tuning, presented by the window from outside the full player and the
    /// bar's popover, whose looks mustn't reach it.
    @State private var showsTuner = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isOnScreen) private var isOnScreen
    #if DEBUG
    @Environment(\.openSettings) private var openSettings
    #endif

    var body: some View {
        if let store = model.store, let capture = model.capture, let playback = model.playback {
            let navigator = model.playNavigator
            @Bindable var playerWindow = playerWindow
            let player = model.player
            NavigationSplitView {
                MacSidebar(
                    model: model,
                    opening: openingTab,
                    library: SidebarItem.library(for: model.musicSource, hasLidarr: model.lidarr.isSetUp),
                    libraryTitle: "Library"
                )
                .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 280)
            } detail: {
                MacDetailStack(navigator: navigator) {
                    MacDetailPage(model: model)
                }
            }
            // Under the full player, the page and its bar stop ticking.
            .environment(\.isOnScreen, isOnScreen && !isShowingFullPlayer)
            .environment(playerWindow)
            .modifier(FollowsSidebar(model: model, history: history, openingTab: openingTab))
            // Siri's search too, which arrives without the window's help.
            .onChange(of: model.search.wantsFocus) { _, wants in
                if wants { playerWindow.showsFullPlayer = false }
            }
            // Initial too, for a window the request itself had to open.
            .onChange(of: model.pendingRoute, initial: true) { _, route in
                guard let route else { return }
                model.pendingRoute = nil
                // The song Tracks is playing opens as it's playing, as Music's mini player
                // opens Now Playing: the menu bar's song asks for it this way.
                if case .song(let identity) = route, player.hasQueue, identity == player.current?.songIdentity {
                    playerWindow.showsFullPlayer = true
                    return
                }
                navigator.path = NavigationPath([route])
            }
            .onChange(of: model.musicSource) { old, source in
                follow(sourceChangeFrom: old, to: source)
            }
            .onChange(of: player.hasQueue) { _, hasQueue in
                if !hasQueue { playerWindow.showsPanel = false }
            }
            .onChange(of: model.controlledDevice?.id) { old, new in
                if let old, new == nil { controlEnded(on: old) }
            }
            // The popover is the bar's, and the full player covers the bar. It opens again on
            // the song's details, the cover face up.
            .onChange(of: isShowingFullPlayer) { _, isShowing in
                if isShowing {
                    playerWindow.showsPanel = false
                } else {
                    playerWindow.fullPlayerShowsUpNext = false
                    playerWindow.fullPlayerShowsHistory = false
                }
            }
            // The full player takes the whole window: the toolbar, the keyboard and VoiceOver
            // leave the page behind it until it closes.
            .toolbarVisibility(isShowingFullPlayer ? .hidden : .automatic, for: .windowToolbar)
            .accessibilityHidden(isShowingFullPlayer)
            .overlay {
                if isShowingFullPlayer {
                    Group {
                        if let device = model.controlledDevice {
                            RemoteFullPlayer(device: device) { playerWindow.showsFullPlayer = false }
                        } else {
                            FullPlayer(window: playerWindow) { playerWindow.showsFullPlayer = false }
                        }
                    }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 1.02)))
                }
            }
            #if DEBUG
            .overlay {
                if let style = BackdropLab.requested {
                    BackdropLab(style: style).ignoresSafeArea()
                }
            }
            #endif
            .animation(reduceMotion ? .easeOut(duration: 0.2) : PlayMotion.panel, value: isShowingFullPlayer)
            .playerFeedback()
            // Songs being added to a playlist, from a menu anywhere.
            .sheet(item: Bindable(model.yourMusic.playlists).picking) { PlaylistPickerSheet(pick: $0) }
            .sheet(isPresented: $showsTuner) { RadioTunerSheet() }
            .environment(\.openRadioTuner, OpenRadioTunerAction {
                // The popover it was asked from goes first, so the sheet isn't left under it.
                guard playerWindow.showsPanel else {
                    showsTuner = true
                    return
                }
                playerWindow.showsPanel = false
                Task { showsTuner = true }
            })
            .playerCommands(
                window: playerWindow,
                isShowingFullPlayer: isShowingFullPlayer,
                history: history,
                navigator: navigator,
                goToCurrentSong: goToCurrentSong,
                beginSearch: beginSearch,
                open: open
            )
            .opensDeepLinks(in: model)
            .onboarding(model)
            .modelContainer(store.container)
            .environment(capture)
            .environment(playback)
            .playEnvironment(model, playsSongsInTracks: songDestination == .tracks)
            .onAppear { MacAppDelegate.openWindow = openWindow }
            .task { await model.playFeed.followSubscription() }
            .task { await model.lidarr.check() }
            // Read once whatever the filter: Sources and Library list this Mac's songs.
            .task { model.prepareYourMusic() }
            // A source added or gone: Listen Now stays on one that has music.
            .onChange(of: model.playableSources) {
                model.followSources()
                if !model.isDemoLaunch { TracksShortcuts.updateAppShortcutParameters() }
            }
            .modifier(FeedBuilt(model: model))
            .onPlayingChange(of: model.player) { model.tellNearby() }
            // A song picked up from another of your devices through Handoff.
            .onContinueUserActivity(NowPlayingHandoff.activityType) { model.continueHandoff($0) }
            #if DEBUG
            .onChange(of: player.hasQueue) { _, hasQueue in
                if hasQueue, LaunchScene.opensStage { openWindow(id: StageWindow.id) }
            }
            .task {
                if UserDefaults.standard.bool(forKey: "TracksMenuBarPreview") {
                    openWindow(id: "menubar-preview")
                }
                // A launch from a script isn't activated, so screenshots show a greyed-out window.
                // Only the deprecated form still takes focus from another app on macOS 27.
                if LaunchScene.activates {
                    NSApp.activate(ignoringOtherApps: true)
                }
                CollectionScreenshotSetup.apply()
                if UserDefaults.standard.bool(forKey: "TracksMiniPlayer") {
                    // Once the main window is up, as someone would open it.
                    try? await Task.sleep(for: .seconds(2))
                    openWindow(id: MiniPlayerWindow.id)
                }
                if LaunchScene.opensSettings {
                    MenuBarFooter.bringSettingsForward(openSettings: openSettings)
                }
            }
            .onChange(of: model.library.isLoaded, initial: true) { _, isLoaded in
                if isLoaded, let subject = SongInfoSamples.requested(in: model.library.history) {
                    SongInfoWindow.show(subject, model: model, openWindow: openWindow)
                }
            }
            #endif
        } else {
            ContentUnavailableView {
                Label("Tracks Can't Open Your History", systemImage: "exclamationmark.triangle")
            } description: {
                Text("Quit Tracks and open it again. If that doesn’t help, restart your Mac.")
            }
        }
    }

    /// Pages pushed for one source mean nothing in the other. A library row the other source
    /// also has (Albums, Songs) stays chosen and shows that source's; one it doesn't have
    /// (Downloads, Lidarr) goes back to Listen Now.
    private func follow(sourceChangeFrom old: MusicSource, to source: MusicSource) {
        guard old != source else { return }
        history.reset()
        model.playNavigator.path = NavigationPath()
        if let selection = model.sidebarSelection,
           SidebarItem.library(for: old, hasLidarr: true).contains(selection),
           !SidebarItem.library(for: source, hasLidarr: model.lidarr.isSetUp).contains(selection) {
            model.sidebarSelection = .listenNow
        }
        model.prepareYourMusic()
    }

    private var isShowingFullPlayer: Bool {
        playerWindow.showsFullPlayer && (model.player.hasQueue || model.controlledDevice != nil)
    }

    private func controlEnded(on id: String) {
        guard playerWindow.showsFullPlayer else { return }
        let device = model.nearby.devices.first { $0.id == id }
        if device != nil, model.player.hasQueue || model.arrivingFrom == id { return }
        playerWindow.showsFullPlayer = false
        if device == nil, let name = model.nearby.known.first(where: { $0.id == id })?.name {
            model.player.confirm(String(localized: "Lost Connection to \(name)"))
        }
    }

    /// The Search page with the cursor in its field, out of the full player.
    private func beginSearch() {
        playerWindow.showsFullPlayer = false
        model.beginSearch()
    }

    /// A page from the menu bar, over whatever the window shows.
    private func open(_ route: PlayRoute) {
        playerWindow.showsFullPlayer = false
        model.playNavigator.show(route)
    }

    /// The playing song's album, or its stats when there's no album to show.
    private func goToCurrentSong() {
        guard let track = model.player.current else { return }
        playerWindow.showsFullPlayer = false
        let navigator = model.playNavigator
        if let local = track.local {
            navigator.show(.localAlbum(local.albumKey))
        } else if let song = track.song {
            Task {
                if let album = await SongLinks.album(of: song) {
                    navigator.show(.album(album))
                } else {
                    navigator.show(.stats(.song(track.songIdentity)))
                }
            }
        } else {
            navigator.show(.stats(.song(track.songIdentity)))
        }
    }
}

/// The pages pushed over the sidebar's page. Its own body, for the same reason as
/// ``MacDetailPage``: a binding to the path made by the window's root reads it there.
private struct MacDetailStack<Root: View>: View {
    @Bindable var navigator: PlayNavigator
    @ViewBuilder let root: Root

    var body: some View {
        NavigationStack(path: $navigator.path) {
            root
                .pageChrome(isRoot: true)
                .tracksDestinations()
                .playDestinations()
        }
    }
}

/// The page for the sidebar row, in a body of its own: read by the window's root, a click in
/// the sidebar would rebuild the whole window around the page, its menus and toolbar too.
private struct MacDetailPage: View {
    let model: AppModel

    var body: some View {
        let selection = model.sidebarSelection ?? SidebarItem.opening
        switch selection {
        case .search: SearchPage()
        case .listenNow: ListenNowPage()
        case .radio: RadioPage()
        case .summary: MacSummaryView()
        case .history: MacHistoryView()
        case .topSongs: TopChartView(kind: .songs)
        case .topArtists: TopChartView(kind: .artists)
        case .topAlbums: TopChartView(kind: .albums)
        case .recentlyAdded, .playlists, .albums, .artists, .songs, .downloads, .lidarr:
            LibraryPage(item: selection, source: model.musicSource)
        }
    }
}

/// A new place in the sidebar starts afresh: nothing pushed, nothing ahead. Back and Forward
/// follow the pages pushed. Its own body, for the same reason as ``MacDetailPage``.
private struct FollowsSidebar: ViewModifier {
    let model: AppModel
    let history: NavigationHistory
    let openingTab: OpeningTab
    /// The sidebar row when macOS last saved the window, so a window it restores at launch
    /// opens on the same page, as Music's does. A new window opens where Settings says.
    @SceneStorage("sidebarSelection") private var savedSelection = ""

    func body(content: Content) -> some View {
        content
            .onAppear(perform: restoreSelection)
            .onChange(of: model.sidebarSelection) { _, selection in
                savedSelection = selection?.rawValue ?? ""
                history.reset()
                if !model.playNavigator.path.isEmpty { model.playNavigator.path = NavigationPath() }
                // Clicking Search puts the cursor in its field, as Podcasts does. Arrowing
                // through the sidebar past it doesn't, or the next arrow would type.
                if selection == .search, NSApp.currentEvent?.type != .keyDown {
                    model.search.wantsFocus = true
                }
            }
            .onChange(of: model.playNavigator.path) { old, new in
                history.pathChanged(from: old, to: new)
            }
    }

    /// The saved row, if it's still in the sidebar (Lidarr may be gone, or the source
    /// changed) and the launch didn't ask for a page of its own.
    private func restoreSelection() {
        guard let item = SidebarItem(rawValue: savedSelection), item != model.sidebarSelection,
              LaunchScene.sidebar == nil, LaunchScene.searchText == nil, model.pendingRoute == nil
        else { return }
        let shown = [.search] + SidebarItem.leading(opening: openingTab)
            + SidebarItem.library(for: model.musicSource, hasLidarr: model.lidarr.isSetUp)
        if shown.contains(item) { model.sidebarSelection = item }
    }
}

/// Places, in the order Open To gives them, then the library of the music Play is on, as
/// Music's sidebar ends with its library. ⌘1 onwards (`TracksCommands`) follow the same order.
private struct MacSidebar: View {
    /// The model rather than a binding made by the window's root, which would read the
    /// selection there and rebuild the whole window on every click.
    @Bindable var model: AppModel
    let opening: OpeningTab
    let library: [SidebarItem]
    let libraryTitle: LocalizedStringKey

    var body: some View {
        List(selection: $model.sidebarSelection) {
            Section { rows([.search]) }
            let leading = SidebarItem.leading(opening: opening)
            let playing = leading.filter { $0 == .listenNow || $0 == .radio }
            let listening = leading.filter { $0 != .listenNow && $0 != .radio }
            if opening == .play {
                Section { rows(playing) }
                Section("Your Listening") { rows(listening) }
            } else {
                Section { rows(listening) }
                Section("Play") { rows(playing) }
            }
            Section(libraryTitle) { rows(library) }
        }
        // Arrowing onto Search leaves its field alone so the next arrow moves on; Return
        // goes into it, as Return opens what's selected elsewhere.
        .onKeyPress(.return) {
            guard model.sidebarSelection == .search else { return .ignored }
            model.search.wantsFocus = true
            return .handled
        }
        .safeAreaBar(edge: .bottom) {
            if let capture = model.capture, capture.isPaused {
                HistoryPausedFooter(capture: capture)
            }
        }
    }

    private func rows(_ items: [SidebarItem]) -> some View {
        ForEach(items) { item in
            Label(item.title, systemImage: item.symbol)
                .tag(item)
        }
    }
}

/// The one status the sidebar keeps at its foot: history paused, which otherwise only the
/// menu bar would say, with the way back.
private struct HistoryPausedFooter: View {
    let capture: CaptureService

    var body: some View {
        HStack(spacing: Design.Spacing.small) {
            Label("History Paused", systemImage: "record.circle")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.orange)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Resume") { Task { await capture.resume() } }
                .controlSize(.small)
                .buttonBorderShape(.capsule)
                .help("Resume History: keep what you play again")
                .accessibilityLabel("Resume History")
        }
        .padding(.horizontal, Design.Spacing.large)
        .padding(.vertical, Design.Spacing.medium)
    }
}

/// Follows the feed's rebuilds, which come with every play, in a body of its own: read by the
/// window's root, each would redraw the whole window.
private struct FeedBuilt: ViewModifier {
    let model: AppModel

    func body(content: Content) -> some View {
        content.onChange(of: model.playFeed.builtRevision) {
            model.prepareYourMusic()
            LaunchScene.startDemoPlayback(model)
            LaunchScene.openMix(model)
        }
    }
}

extension View {
    /// Everything a page that plays music reads: the player and what it plays from, and where
    /// its pages push.
    func playEnvironment(_ model: AppModel, playsSongsInTracks: Bool) -> some View {
        modifier(PlayEnvironment(model: model, playsSongsInTracks: playsSongsInTracks))
    }
}

private struct PlayEnvironment: ViewModifier {
    let model: AppModel
    let playsSongsInTracks: Bool

    func body(content: Content) -> some View {
        let player = model.player
        let navigator = model.playNavigator
        content
            .environment(model)
            .environment(player)
            .environment(model.playFeed)
            .environment(model.discovery)
            .environment(model.yourMusic)
            .environment(model.lidarr)
            .environment(navigator)
            .environment(\.openPlayRoute, OpenPlayRouteAction(stack: "main") { navigator.show($0) })
            // A song's Play button plays in Tracks, where every play is kept, unless Settings
            // sends songs to Music.
            .environment(\.playSongs, playsSongsInTracks ? PlaySongsAction { items, title in
                player.play(.history(items.map(HistorySong.init)), from: .songs(title))
            } : nil)
    }
}

#if DEBUG
@MainActor
private let previewModel: AppModel = {
    UserDefaults.standard.setVolatileDomain(["TracksDemoData": true], forName: UserDefaults.argumentDomain)
    return AppModel()
}()

private struct SidebarPreview: View {
    var opening: OpeningTab = .standard
    var source: MusicSource = .appleMusic

    var body: some View {
        let model = previewModel
        NavigationSplitView {
            MacSidebar(
                model: model,
                opening: opening,
                library: SidebarItem.library(for: source, hasLidarr: true),
                libraryTitle: "Library"
            )
            .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 280)
        } detail: {
            Text("Page")
                .font(.pageTitle)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(Design.Margin.page)
        }
        .frame(width: 820, height: 560)
    }
}

#Preview("Sidebar, light") { SidebarPreview() }
#Preview("Sidebar, dark") { SidebarPreview().preferredColorScheme(.dark) }
#Preview("Sidebar, Summary first, Your Music") { SidebarPreview(opening: .summary, source: .yourMusic) }

/// The whole window on sample data, opened on a page as a launch argument would open it. The
/// settings go in a volatile domain, so nothing reaches the real app's defaults.
private struct WindowPreview: View {
    enum Size {
        case small, large

        var frame: CGSize {
            switch self {
            case .small: CGSize(width: 820, height: 560)
            case .large: CGSize(width: 1600, height: 1000)
            }
        }
    }

    let size: Size
    @State private var model: AppModel

    init(
        _ page: SidebarItem = .listenNow,
        opening: OpeningTab = .standard,
        size: Size = .small,
        route: (@MainActor @Sendable (AppModel) -> PlayRoute?)? = nil
    ) {
        let defaults: [String: Any] = [
            "TracksDemoData": true,
            "TracksDemoLibrary": true,
            "TracksSidebar": page.rawValue,
            OpeningTab.storageKey: opening.rawValue,
        ]
        UserDefaults.standard.setVolatileDomain(defaults, forName: UserDefaults.argumentDomain)
        let model = AppModel()
        if let destination = route?(model) { model.playNavigator.show(destination) }
        self.size = size
        _model = State(initialValue: model)
    }

    var body: some View {
        MacRootView(model: model)
            .frame(width: size.frame.width, height: size.frame.height)
    }
}

private let whiteAlbum: @MainActor @Sendable (AppModel) -> PlayRoute? = { _ in
    LibraryDemo.items(Album.self)?.first { $0.id.rawValue == "l.demo-album-white" }.map(PlayRoute.album)
}

private let sampleArtist: @MainActor @Sendable (AppModel) -> PlayRoute? = { model in
    model.yourMusic.index.artists.first.map { .localArtist($0.id) }
        ?? LibraryDemo.items(Artist.self)?.first.map(PlayRoute.artist)
}

#Preview("Window, Play first") { WindowPreview() }
#Preview("Window, Play first, dark, large") { WindowPreview(size: .large).preferredColorScheme(.dark) }
#Preview("Window, Summary first") { WindowPreview(.summary, opening: .summary) }
#Preview("Window, Summary first, dark, large") { WindowPreview(.summary, opening: .summary, size: .large).preferredColorScheme(.dark) }
#Preview("Window, Top Songs") { WindowPreview(.topSongs) }
#Preview("Window, History, large") { WindowPreview(.history, size: .large) }
#Preview("Window, Search") { WindowPreview(.search) }
#Preview("Window, Search, dark, large") { WindowPreview(.search, size: .large).preferredColorScheme(.dark) }
#Preview("Window, Radio") { WindowPreview(.radio) }
#Preview("Window, album") { WindowPreview(route: whiteAlbum) }
#Preview("Window, album, dark, large") { WindowPreview(size: .large, route: whiteAlbum).preferredColorScheme(.dark) }
#Preview("Window, artist") { WindowPreview(route: sampleArtist) }
#Preview("Window, mood") { WindowPreview(route: { _ in .mood(.chill) }) }
#Preview("Window, genre, dark, large") { WindowPreview(size: .large) { _ in MusicGenre.all.first.map(PlayRoute.genre) }.preferredColorScheme(.dark) }
#endif

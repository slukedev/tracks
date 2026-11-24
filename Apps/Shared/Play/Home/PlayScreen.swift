import SwiftUI
import MusicKit
import TracksCore

/// The Play tab: start something in one tap, and have Tracks keep every song of it.
///
/// Music's Home, rebuilt around the history. The top is what you usually play at this hour;
/// below it, Apple Music's Recently Played, mixes made from your own listening, radio, your
/// library, and Apple's own picks.
struct PlayScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @Environment(PlayFeed.self) private var feed
    @AppStorage(PlayPreferences.layoutKey) private var storedLayout = ""
    @AppStorage(PlayPreferences.tracksRadioKey) private var isRadioOn = true
    @AppStorage(PlayPreferences.allowsExplicitKey) private var allowsExplicit = true
    @Environment(\.openPlayRoute) private var openPlayRoute
    @State private var showsSubscriptionOffer = false
    @State private var showsEditor = LaunchScene.opensEditPlay

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: Design.Margin.section) {
                    // Access and subscription come first whatever the layout: nothing else
                    // on the page can play until they're sorted.
                    if let blocker {
                        statusCard(blocker)
                            .padding(.horizontal, Design.Margin.page)
                    }

                    ForEach(layout.visible) { section in
                        self.section(section)
                            .id(section.rawValue)
                    }

                    if feed.appleMusicState == .failed {
                        UnreachableNote { await reload() }
                            .padding(.horizontal, Design.Margin.page)
                    }

                    // Past everything else, suggestions for as long as you scroll, straight
                    // after Apple Music's picks. Edit Play is in Settings.
                    if blocker == nil {
                        KeepExploringSection(followsShelf: layout.isVisible(.suggestedSongs))
                            .id("keepExploring")
                    }
                }
                .padding(.top, Self.topPadding)
                .padding(.bottom, Design.Margin.section)
            }
            .onChange(of: feed.hasBuilt) {
                if let anchor = LaunchScene.scrollAnchor {
                    proxy.scrollTo(anchor, anchor: .top)
                }
            }
        }
        #if os(macOS)
        // No title: the sidebar names the page, and the crate's glow meets the top edge.
        .headerUnderBar()
        #else
        // On the Mac, Settings is the app menu's and Your Devices the window's.
        .tabRoot("Play", opensDevicesAtLaunch: true, opensSettingsAtLaunch: LaunchScene.opensPlaySettings)
        #endif
        .sheet(isPresented: $showsEditor) {
            PlayLayoutEditor()
        }
        .refreshCommand()
        .refreshable { await reload() }
        .task(id: model.musicAuthorization) {
            await feed.loadAppleMusic()
        }
        .task(id: DiscoverKey(artists: Set(feed.topArtists), authorization: model.musicAuthorization, allowsExplicit: allowsExplicit)) {
            await feed.loadFromYourArtists()
        }
        .onChange(of: allowsExplicit) {
            // Shelves already loaded hold the other versions. The shelves from your artists
            // reload through the task above, whose id includes the setting.
            Task { await feed.loadAppleMusic(force: true) }
        }
        .musicSubscriptionOffer(isPresented: $showsSubscriptionOffer, options: .init(messageIdentifier: .playMusic))
    }

    private var layout: PlayLayout { PlayLayout(stored: storedLayout) }

    private func reload() async {
        await feed.loadAppleMusic(force: true)
        await feed.loadFromYourArtists(force: true)
    }

    #if os(macOS)
    private static let topPadding = Design.Spacing.xLarge
    private static let hasSidebarLibrary = true
    #else
    private static let topPadding = Design.Spacing.xSmall
    private static let hasSidebarLibrary = false
    #endif

    // MARK: - Sections

    @ViewBuilder
    private func section(_ section: PlaySection) -> some View {
        switch section {
        case .suggestedSongs:
            if blocker == nil {
                // Tracks Radio leads the crate; with the crate hidden, it leads these instead.
                SuggestedSongsSection(includesRadio: !layout.isVisible(.forYou))
            }
        case .suggestedArtists:
            if blocker == nil {
                SuggestedArtistsShelf()
            }
        case .forYou:
            forYou
        case .recentlyPlayed:
            if !feed.recentlyPlayed.isEmpty {
                Shelf(title: String(localized: "Recently Played"), items: feed.recentlyPlayed) { item in
                    FeedTile(item: item)
                }
            } else if feed.appleMusicState == .loading {
                PlaceholderShelf(title: String(localized: "Recently Played"))
            }
        case .mixes:
            mixesSection
        case .yourArtists:
            if !feed.favoriteArtists.isEmpty {
                Shelf(title: String(localized: "Your Artists"), items: feed.favoriteArtists) { artist in
                    FavoriteArtistTile(artist: artist)
                }
            }
        case .moods:
            MoodShelf()
        case .genres:
            GenreShelf()
        case .newReleases:
            if !feed.newReleases.isEmpty {
                Shelf(title: String(localized: "New from Your Artists"), items: feed.newReleases, open: openPlayRoute.canPush ? { openPlayRoute(.newReleases) } : nil) { item in
                    FeedTile(item: item)
                }
            }
        case .charts:
            if !feed.charts.isEmpty {
                Shelf(title: String(localized: "Top Charts"), items: feed.charts) { item in
                    FeedTile(item: item)
                }
            }
        case .radio:
            if !radio.isEmpty {
                Shelf(title: String(localized: "Radio"), items: radio) { item in
                    FeedTile(item: item)
                }
            } else if feed.appleMusicState == .loading {
                PlaceholderShelf(title: String(localized: "Radio"))
            }
        case .library:
            // The Mac's library is in the sidebar.
            #if os(iOS)
            if showsLibrary, !Self.hasSidebarLibrary {
                LibraryOverview()
                    .padding(.horizontal, Design.Margin.page)
            }
            #endif
        case .appleMusic:
            ForEach(feed.recommendations.prefix(PlayFeed.pickRows)) { recommendation in
                Shelf(title: recommendation.title, items: recommendation.items) { item in
                    FeedTile(item: item)
                }
            }
        }
    }

    // MARK: - For You

    /// What stands in the way of playing anything, if something does.
    private enum Blocker { case access, subscription }

    private var blocker: Blocker? {
        if !model.isShowingSampleData, model.musicAuthorization != .authorized { return .access }
        if feed.cannotPlayCatalog { return .subscription }
        return nil
    }

    @ViewBuilder
    private func statusCard(_ blocker: Blocker) -> some View {
        switch blocker {
        case .access:
            PlayAccessCard()
        case .subscription:
            PlayStateCard(
                symbol: "music.note",
                title: String(localized: "Playing Needs Apple Music"),
                message: String(localized: "Tracks plays songs from Apple Music and keeps every one you hear. Your history and stats work without it.")
            ) {
                if feed.subscription?.canBecomeSubscriber == true {
                    Button("Try Apple Music") { showsSubscriptionOffer = true }
                        .buttonStyle(.glassProminent)
                }
            }
        }
    }

    @ViewBuilder
    private var forYou: some View {
        let cards = crateCards
        if blocker != nil {
            // Nothing to offer until it can play.
        } else if isCrateLoading {
            // The crate's shape until the history is read, so it opens with all its records
            // rather than Tracks Radio alone and then the mixes arriving around it.
            CratePlaceholder()
        } else if !cards.isEmpty {
            // Tracks Radio in front, this hour's mix beside it. With no history yet, Tracks
            // Radio alone: the same page, with the mixes joining it as you listen.
            ForYouCrate(cards: cards, leadID: cards.contains { $0.id == "station" } ? "station" : leadMix?.id)
        }
    }

    /// Whether For You is still waiting on the history to show its crate.
    private var isCrateLoading: Bool {
        !(feed.hasBuilt && model.library.isLoaded)
    }

    /// The crate: Tracks Radio in the middle, starting in front, with this hour's mix, Discover
    /// and the rest of the day's mixes either side of it.
    private var crateCards: [ForYouCard] {
        var cards: [ForYouCard] = []
        if let lead = leadMix { cards.append(.mix(lead)) }
        if !feed.discover.isEmpty { cards.append(.discover(feed.discover)) }
        cards += feed.mixes.otherTimes.map(ForYouCard.mix)
        if isRadioOn { cards.insert(.station, at: cards.count / 2) }
        return cards
    }

    /// This hour's mix, or the first mix there is when this hour has no habit yet.
    private var leadMix: Mix? {
        feed.mixes.rightNow ?? feed.mixes.mixes.first
    }

    // MARK: - Mixes

    @ViewBuilder
    private var mixesSection: some View {
        // The mix leading For You isn't repeated here, unless For You is hidden.
        let leading = layout.isVisible(.forYou) && feed.mixes.rightNow == nil ? leadMix?.id : nil
        let shelf = feed.mixes.mixes.filter { $0.id != leading }
        if !shelf.isEmpty {
            Shelf(title: String(localized: "Made from Your Listening"), items: shelf) { mix in
                MixTile(mix: mix)
            }
        }
    }

    /// Apple's live stations only. Personal stations from the history still play from Recently Played.
    private var radio: [FeedItem] { feed.liveStations }

    private var showsLibrary: Bool {
        !model.isShowingSampleData && model.musicAuthorization == .authorized
    }
}

/// What Discover is looked up from. A change to any of it looks again.
private struct DiscoverKey: Equatable {
    /// A set: top artists trading places isn't a reason to look again.
    let artists: Set<String>
    let authorization: MusicAuthorization.Status
    let allowsExplicit: Bool
}

/// Asks for Apple Music access, in place, saying what it's for.
struct PlayAccessCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var isRequesting = false

    var body: some View {
        PlayStateCard(
            symbol: model.musicAuthorization == .notDetermined ? "play.circle" : "exclamationmark.triangle.fill",
            title: model.musicAuthorization == .notDetermined
                ? String(localized: "Play Apple Music in Tracks")
                : String(localized: "Apple Music Access Is Off"),
            message: model.musicAuthorization == .notDetermined
                ? String(localized: "Play your library, radio and mixes made from your listening. Tracks keeps every song you hear, even in the background.")
                : String(localized: "Turn on Media & Apple Music for Tracks in Settings to play music here.")
        ) {
            if model.musicAuthorization == .notDetermined {
                Button("Allow Apple Music Access") {
                    isRequesting = true
                    Task {
                        await model.requestMusicAccess()
                        isRequesting = false
                    }
                }
                .buttonStyle(.glassProminent)
                .disabled(isRequesting)
            } else {
                Button("Open Settings") {
                    if let url = SystemSettingsLink.musicAccess { openURL(url) }
                }
                .buttonStyle(.glass)
            }
        }
        .onAppear(perform: model.refreshMusicAuthorization)
    }
}

/// A card for a state the page is in rather than for content: no access, no subscription.
struct PlayStateCard<Actions: View>: View {
    let symbol: String
    let title: String
    let message: String
    @ViewBuilder var actions: Actions

    var body: some View {
        Card(padding: Design.Spacing.xLarge) {
            VStack(alignment: .leading, spacing: Design.Spacing.small) {
                Image(systemName: symbol)
                    .font(.title2)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                actions
                    .buttonBorderShape(.capsule)
                    .controlSize(.large)
                    .padding(.top, Design.Spacing.small)
            }
        }
    }
}

/// Apple Music out of reach: what happened, and Try Again. Pulling down tries again too on
/// iPhone, but nothing on the page says so, so the button is there on both.
private struct UnreachableNote: View {
    let retry: () async -> Void
    @State private var isRetrying = false

    var body: some View {
        AdaptiveStack(verticalAlignment: .center, spacing: Design.Spacing.medium) {
            Label("Couldn't reach Apple Music.", systemImage: "wifi.exclamationmark")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button("Try Again") {
                isRetrying = true
                Task {
                    await retry()
                    isRetrying = false
                }
            }
            .buttonStyle(.glass)
            .disabled(isRetrying)
        }
    }
}

/// A shelf's shape while Apple Music answers: its title, and blank tiles the size of the
/// real ones, so the covers arrive in place.
private struct PlaceholderShelf: View {
    let title: String
    @ScaledMetric(relativeTo: .subheadline) private var width = Design.Shelf.tile

    var body: some View {
        let side = min(width, Design.Shelf.maximumTile)
        VStack(alignment: .leading, spacing: Design.Margin.header) {
            Text(title)
                .font(.sectionTitle)
                .padding(.horizontal, Design.Margin.page)
            // In a scroll view like a real shelf's. A plain row of tiles is wider than the
            // screen and can't shrink, so it pushed the whole page wider until it loaded.
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: Design.Shelf.spacing) {
                    ForEach(0..<6, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: Design.Shelf.captionGap) {
                            RoundedRectangle(cornerRadius: CoverImage.radius(for: side), style: .continuous)
                                .fill(Color.placeholderFill)
                                .frame(width: side, height: side)
                            VStack(alignment: .leading, spacing: Design.Shelf.lineGap) {
                                Text(verbatim: "Placeholder")
                                Text(verbatim: "Line")
                            }
                            .font(.subheadline)
                            .redacted(reason: .placeholder)
                        }
                    }
                }
            }
            .contentMargins(.horizontal, Design.Margin.page, for: .scrollContent)
            .scrollDisabled(true)
            .scrollIndicators(.hidden)
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Loading \(title)"))
    }
}

/// A row that darkens under a finger, as list rows do.
struct RowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? AnyShapeStyle(.fill.tertiary) : AnyShapeStyle(.clear))
    }
}

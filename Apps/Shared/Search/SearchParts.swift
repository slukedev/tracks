import SwiftUI
import TracksCore

/// Recent searches as chips to search again, with Clear.
struct RecentSearchesSection: View {
    let recents: [String]
    let clear: () -> Void
    let search: (String) -> Void
    var remove: ((String) -> Void)?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        // Clear goes under the title at the largest sizes, so the title keeps whole words.
        let header = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
        VStack(alignment: .leading, spacing: 10) {
            header {
                SearchSectionTitle("Recently Searched")
                Spacer(minLength: 0)
                Button("Clear") { withAnimation(Design.Motion.row) { clear() } }
                    .buttonStyle(.plain)
                    .font(.subheadline)
                    .foregroundStyle(.tint)
            }
            .padding(.horizontal, Design.Margin.page)
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(recents, id: \.self) { recent in
                        SearchChip(title: recent, systemImage: "clock.arrow.circlepath") {
                            search(recent)
                        }
                        .contextMenu {
                            if let remove {
                                Button("Remove from Recent Searches", systemImage: "minus.circle") {
                                    withAnimation(Design.Motion.row) { remove(recent) }
                                }
                            }
                        }
                    }
                }
            }
            .contentMargins(.horizontal, Design.Margin.page, for: .scrollContent)
            .scrollIndicators(.hidden)
        }
    }
}

/// A search to run again or try, as a chip.
struct SearchChip: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .frame(minHeight: 34)
                .background(.quaternary, in: .capsule)
                // A full-height target around the chip.
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.pressable)
    }
}

/// The moods and genres, as tiles to open, for before a search: Music's Browse Categories,
/// made of the rooms Play already has.
struct BrowseSections: View {
    /// They play from the history through Apple Music, so they need it allowed, or sample
    /// data, which has its own.
    static func isOffered(_ model: AppModel) -> Bool {
        model.musicSource == .appleMusic && (model.musicAuthorization == .authorized || model.isShowingSampleData)
    }

    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @Environment(PlayFeed.self) private var feed
    /// Nil until they're worked out, which walks the history.
    @State private var genres: [GenreTileItem]?
    @State private var moodCovers: RoomCovers?
    @ScaledMetric(relativeTo: .headline) private var tileWidth = Self.tileWidth
    @ScaledMetric(relativeTo: .headline) private var tileHeight = CategoryCardMetrics.gridHeight

    var body: some View {
        VStack(alignment: .leading, spacing: Design.Margin.section) {
            section("Browse by Mood") {
                ForEach(Mood.allCases) { mood in
                    MoodTile(mood: mood, covers: MoodShelf.art(moodCovers?.byMood[mood]), placement: .grid)
                }
            }
            if let genres {
                if !genres.isEmpty {
                    section("Browse by Genre") {
                        ForEach(genres) { item in
                            GenreTile(item: item, placement: .grid)
                        }
                    }
                }
            } else {
                // The genres' place, held while they're worked out.
                section("Browse by Genre") {
                    ForEach(0..<(model.musicSource == .appleMusic ? MusicGenre.all.count : 4), id: \.self) { _ in
                        RoundedRectangle(cornerRadius: CategoryCardMetrics.radius, style: .continuous)
                            .fill(Color.placeholderFill)
                            .frame(height: min(tileHeight, CategoryCardMetrics.gridHeight * 1.6))
                    }
                }
                .accessibilityHidden(true)
            }
        }
        .task(id: model.library.revision) {
            let (history, signals) = (model.library.history, player.signals)
            let found = await OffMainActor.run { RoomCovers(history: history, signals: signals, limit: 3) }
            guard !Task.isCancelled else { return }
            moodCovers = found
        }
        .task(id: GenresKey(source: model.musicSource, revision: model.library.revision, songs: model.yourMusic.index.tracks.count)) {
            guard model.library.isLoaded else { return }
            genres = await GenreShelfItems.load(model: model, facts: feed.facts)
        }
    }

    private func section(_ title: LocalizedStringKey, @ViewBuilder tiles: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: Design.Spacing.medium) {
            SearchSectionTitle(title)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: min(tileWidth, Self.tileWidth * 2.2)), spacing: Design.Spacing.medium)], spacing: Design.Spacing.medium) {
                tiles()
            }
        }
        .padding(.horizontal, Design.Margin.page)
    }

    #if os(macOS)
    private static let tileWidth: CGFloat = 170
    #else
    private static let tileWidth: CGFloat = 150
    #endif
}

private struct GenresKey: Equatable {
    let source: MusicSource
    let revision: Int
    let songs: Int
}

/// A search section's title.
struct SearchSectionTitle: View {
    let title: LocalizedStringKey

    init(_ title: LocalizedStringKey) {
        self.title = title
    }

    var body: some View {
        Text(title)
            .font(.sectionTitle)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The top result and the songs: side by side on the Mac, as Music lays them out, or one above
/// the other where the window's too narrow for both, and always on iPhone.
struct SearchLead<SongsTitle: View, Songs: View>: View {
    let top: SearchTopResult?
    var topTitle: LocalizedStringKey = "Top Result"
    let hasSongs: Bool
    @ViewBuilder var songsTitle: SongsTitle
    @ViewBuilder var songs: Songs
    #if os(macOS)
    /// Whether the window has room for the two side by side. Decided from the width rather
    /// than by `ViewThatFits`, which would lay out every song row twice on each update, the
    /// playing one's AppKit waveform included.
    @State private var isWide = true
    #endif

    var body: some View {
        Group {
            #if os(macOS)
            if isWide {
                HStack(alignment: .top, spacing: Self.gap) {
                    if top != nil {
                        topBlock
                            .frame(width: Self.cardWidth)
                    }
                    if hasSongs {
                        songsBlock
                            .frame(minWidth: Self.songsWidth, maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                stacked
            }
            #else
            stacked
            #endif
        }
        #if os(macOS)
        // A yes or no, never a size, so the choice can't feed back into the width it reads.
        .onGeometryChange(for: Bool.self) { $0.size.width >= Self.cardWidth + Self.gap + Self.songsWidth } action: { isWide = $0 }
        #endif
        .padding(.horizontal, Design.Margin.page)
    }

    private var stacked: some View {
        VStack(alignment: .leading, spacing: Design.Margin.section) {
            topBlock
                #if os(macOS)
                .frame(maxWidth: Self.cardWidth)
                #endif
            if hasSongs { songsBlock }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var topBlock: some View {
        if let top {
            VStack(alignment: .leading, spacing: 10) {
                SearchSectionTitle(topTitle)
                TopResultCard(result: top)
            }
        }
    }

    private var songsBlock: some View {
        VStack(alignment: .leading, spacing: Self.titleSpacing) {
            songsTitle
            songs
        }
    }

    private static var cardWidth: CGFloat { 340 }
    #if os(macOS)
    private static var gap: CGFloat { 28 }
    private static var songsWidth: CGFloat { 420 }
    private static var titleSpacing: CGFloat { 10 }
    #else
    private static var titleSpacing: CGFloat { 6 }
    #endif
}

/// Search's songs, in rows: one column on iPhone; on the Mac, as many as fit, as Music lays
/// them out.
struct SearchSongGrid<Item: Identifiable, Row: View>: View {
    let items: [Item]
    @ViewBuilder var row: (Item) -> Row

    var body: some View {
        LazyVGrid(columns: Self.columns, alignment: .leading, spacing: 0) {
            ForEach(items) { item in
                row(item)
            }
        }
    }

    #if os(macOS)
    private static var columns: [GridItem] { [GridItem(.adaptive(minimum: 260), spacing: 28)] }
    #else
    private static var columns: [GridItem] { [GridItem(.flexible())] }
    #endif
}

/// The best match for a search, whatever it's from: what it's called, what it is, its
/// picture, and what opening and playing it do.
struct SearchTopResult {
    let title: String
    /// "Artist", "Album · Mara Solis", "Song · Mara Solis".
    let kind: String
    let cover: CoverArt?
    /// For an artist: a round picture, or their initials without one.
    var isArtist = false
    let open: () -> Void
    /// Nil when nothing of it can play right now.
    let play: (() -> Void)?
    /// A song's history key, so the card knows when it's the one playing.
    var songIdentity: String?
}

/// The best match, large: its picture, what it is, and Play, as Music's Top Result is.
struct TopResultCard: View {
    let result: SearchTopResult
    @Environment(PlayerModel.self) private var player
    @State private var tint: Color?

    var body: some View {
        Button(action: result.open) {
            VStack(alignment: .leading, spacing: 14) {
                picture
                    .shadow(color: .black.opacity(0.25), radius: 10, y: 5)
                VStack(alignment: .leading, spacing: 2) {
                    Text(result.title)
                        .font(.title2.bold())
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Text(result.kind)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.75))
                        .lineLimit(1)
                }
                // Clear of Play in the corner, however large the text.
                .padding(.trailing, 60)
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 64)
            }
            .padding(18)
            .frame(maxWidth: .infinity, minHeight: 240, alignment: .topLeading)
            .background {
                CoverStage(tint: tint, deepens: true)
                    .clipShape(.rect(cornerRadius: 18, style: .continuous))
            }
            .overlay(alignment: .bottomTrailing) {
                if let play = result.play {
                    let pauses = isCurrent && player.isPlaying
                    // The song playing pauses and goes on from here, rather than starting over.
                    CoverPlayButton(
                        title: pauses ? "Pause" : "Play",
                        systemImage: pauses ? "pause.fill" : "play.fill",
                        action: isCurrent ? player.togglePlayPause : play
                    )
                    .padding(Design.Spacing.large)
                    // Reached as the card's Play action with VoiceOver.
                    .accessibilityHidden(true)
                }
            }
            .contentShape(.rect(cornerRadius: 18))
        }
        .buttonStyle(.pressable)
        .coverTint(of: result.cover ?? .url(nil, seed: result.title), into: $tint)
        .accessibilityElement(children: .combine)
        .accessibilityActions {
            if let play = result.play {
                if isCurrent {
                    Button(player.isPlaying ? "Pause" : "Play", action: player.togglePlayPause)
                } else {
                    Button("Play", action: play)
                }
            }
        }
    }

    private var isCurrent: Bool {
        guard let identity = result.songIdentity else { return false }
        return player.current?.songIdentity == identity
    }

    @ViewBuilder
    private var picture: some View {
        if result.isArtist {
            ArtistPicture(cover: result.cover, name: result.title, size: 112, onStage: true)
        } else {
            CoverImage(cover: result.cover ?? .url(nil, seed: result.title), size: 112)
        }
    }
}

/// No results, with the other places the same search could look, a click away.
struct SearchNoResults: View {
    let query: String
    @Environment(AppModel.self) private var model

    var body: some View {
        let session = model.search
        let others = SearchScope.scopes(for: model.musicSource).filter { $0 != session.scope }
        ContentUnavailableView {
            Label("No Results", systemImage: "magnifyingglass")
        } description: {
            Text("Nothing in \(Text(session.scope.title(for: model.musicSource))) matches “\(query.trimmingCharacters(in: .whitespacesAndNewlines))”.")
        } actions: {
            ForEach(others) { scope in
                Button {
                    session.scope = scope
                } label: {
                    Text("Search \(Text(scope.title(for: model.musicSource)))")
                }
            }
        }
    }
}

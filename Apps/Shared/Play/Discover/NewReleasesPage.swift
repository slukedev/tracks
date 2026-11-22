import SwiftUI
import MusicKit
import TracksCore

/// What your artists have put out lately, and what they have coming.
///
/// The newest leads, large, with how much you play whoever made it. What's coming follows,
/// then everything else by when it came out. A release you haven't heard any of carries a
/// dot, as an episode you haven't played does in Podcasts, so a glance says what's waiting.
struct NewReleasesPage: View {
    @Environment(Discovery.self) private var discovery
    @Environment(AppModel.self) private var model
    @State private var filter = Filter.all
    @State private var yours = YourReleases()

    enum Filter: String, CaseIterable, Identifiable {
        case all, albums, singles
        var id: String { rawValue }

        var title: LocalizedStringKey {
            switch self {
            case .all: "All"
            case .albums: "Albums"
            case .singles: "Singles & EPs"
            }
        }

        func includes(_ release: Discovery.Release) -> Bool {
            switch self {
            case .all: true
            case .albums: release.kind == .album
            case .singles: release.kind != .album
            }
        }
    }

    var body: some View {
        let releases = discovery.releases.filter(filter.includes)
        let upcoming = releases.filter(\.isUpcoming).sorted { ($0.date ?? .distantFuture) < ($1.date ?? .distantFuture) }
        let out = releases.filter { !$0.isUpcoming }.sorted { ($0.date ?? .distantPast) > ($1.date ?? .distantPast) }
        let featured = out.first
        ScrollView {
            VStack(alignment: .leading, spacing: Design.Margin.section) {
                if let featured {
                    FeaturedRelease(release: featured, plays: yours.plays(of: featured), isHeard: yours.hasHeard(featured))
                }

                if !upcoming.isEmpty {
                    VStack(alignment: .leading, spacing: Design.Margin.header) {
                        header(String(localized: "Coming Soon"), detail: "Add them now, and they'll be in your library the day they're out.")
                            .padding(.horizontal, Design.Margin.page)
                        ScrollView(.horizontal) {
                            LazyHStack(alignment: .top, spacing: PlayMetrics.shelfSpacing) {
                                ForEach(upcoming) { release in
                                    ReleaseTile(release: release, side: Design.Shelf.tile)
                                }
                            }
                            .scrollTargetLayout()
                        }
                        .contentMargins(.horizontal, Design.Margin.page, for: .scrollContent)
                        .scrollTargetBehavior(.viewAligned)
                        .scrollIndicators(.hidden)
                    }
                }

                ForEach(Period.allCases) { period in
                    let items = out.dropFirst().filter { period.contains($0.date) }
                    if !items.isEmpty {
                        VStack(alignment: .leading, spacing: Design.Margin.header) {
                            header(period.title, detail: nil)
                            LazyVGrid(columns: Self.columns, alignment: .leading, spacing: Design.Spacing.xxLarge) {
                                ForEach(items) { release in
                                    ReleaseTile(release: release, isUnheard: !yours.hasHeard(release))
                                }
                            }
                        }
                        .padding(.horizontal, Design.Margin.page)
                    }
                }

                if discovery.releasesState == .loading || discovery.releasesState == .idle, releases.isEmpty {
                    LazyVGrid(columns: Self.columns, alignment: .leading, spacing: Design.Spacing.xxLarge) {
                        ForEach(0..<8, id: \.self) { _ in
                            VStack(alignment: .leading, spacing: Design.Shelf.captionGap) {
                                RoundedRectangle(cornerRadius: CoverImage.radius(for: Design.Shelf.tile), style: .continuous)
                                    .fill(Color.placeholderFill)
                                    .aspectRatio(1, contentMode: .fit)
                                Text(verbatim: "Release Title\nArtist")
                                    .font(.subheadline)
                                    .redacted(reason: .placeholder)
                            }
                        }
                    }
                    .padding(.horizontal, Design.Margin.page)
                    .accessibilityHidden(true)
                }
            }
            .padding(.top, Design.Spacing.small)
            .padding(.bottom, Design.Margin.section)
            .animation(Design.Motion.row, value: filter)
        }
        .overlay {
            if discovery.releasesState == .loaded || discovery.releasesState == .failed, releases.isEmpty {
                ContentUnavailableView(
                    discovery.releasesState == .failed ? "Couldn't Reach Apple Music" : "Nothing New Lately",
                    systemImage: discovery.releasesState == .failed ? "wifi.exclamationmark" : "calendar",
                    description: Text(discovery.releasesState == .failed
                        ? "Check your connection, then try again."
                        : filter == .all
                            ? "Your artists haven't put anything out in the last six months."
                            : "Nothing of this kind from your artists in the last six months.")
                )
            }
        }
        .navigationTitle("New from Your Artists")
        #if os(macOS)
        .toolbar {
            ToolbarItem(placement: .principal) {
                filterPicker
                    .fixedSize()
            }
        }
        #else
        .toolbarTitleDisplayMode(.inline)
        // On glass over the scroll edge, rather than scrolling away with the releases.
        .safeAreaBar(edge: .top) {
            filterPicker
                .padding(.horizontal, Design.Margin.page)
                .padding(.bottom, Design.Spacing.small)
        }
        #endif
        .task { await discovery.loadReleases() }
        .task(id: model.library.revision) {
            let history = model.library.history
            let found = await OffMainActor.run { YourReleases(history: history) }
            guard !Task.isCancelled else { return }
            yours = found
        }
        .refreshCommand()
        .refreshable { await discovery.loadReleases(force: true) }
    }

    private var filterPicker: some View {
        Picker("Show", selection: $filter) {
            ForEach(Filter.allCases) { Text($0.title).tag($0) }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .sensoryFeedback(Design.Haptic.choice, trigger: filter)
    }

    private func header(_ title: String, detail: LocalizedStringKey?) -> some View {
        VStack(alignment: .leading, spacing: Design.Spacing.xxSmall) {
            ShelfHeader(title: title)
            if let detail {
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// As many as fit, filling the width, from the left.
    private static var columns: [GridItem] {
        #if os(macOS)
        [GridItem(.adaptive(minimum: Design.Shelf.tile - 16), spacing: Design.Shelf.spacing, alignment: .top)]
        #else
        [GridItem(.adaptive(minimum: 150), spacing: Design.Shelf.spacing, alignment: .top)]
        #endif
    }

    enum Period: CaseIterable, Identifiable {
        case thisWeek, thisMonth, earlier
        var id: Self { self }

        var title: String {
            switch self {
            case .thisWeek: String(localized: "This Week")
            case .thisMonth: String(localized: "This Month")
            case .earlier: String(localized: "Earlier")
            }
        }

        func contains(_ date: Date?) -> Bool {
            guard let date else { return self == .earlier }
            let age = Date.now.timeIntervalSince(date)
            switch self {
            case .thisWeek: return age < 7 * 86_400
            case .thisMonth: return age >= 7 * 86_400 && age < 31 * 86_400
            case .earlier: return age >= 31 * 86_400
            }
        }
    }
}

/// What the history knows about new releases: which albums you've heard some of, and how
/// often you play each artist.
nonisolated struct YourReleases: Sendable {
    private var albums: Set<String> = []
    private var artists: [String: Int] = [:]

    init() {}

    init(history: ListeningHistory) {
        for capture in history.captures {
            if let album = capture.albumIdentity { albums.insert(album) }
            for identity in capture.creditIdentities { artists[identity, default: 0] += 1 }
        }
    }

    func hasHeard(_ release: Discovery.Release) -> Bool {
        let title = StatsCalculator.folded(Self.plainTitle(release.album.title))
        return albums.contains { $0.hasPrefix(title + "\u{1F}") }
    }

    /// Plays of whoever made it: the first of its artists you play.
    func plays(of release: Discovery.Release) -> Int {
        let names = ArtistCredits.parse(artist: release.album.artistName).map(\.identity)
        return ([StatsCalculator.folded(release.album.artistName)] + names).lazy.compactMap { artists[$0] }.first ?? 0
    }

    /// The title as the history keeps it, without Apple Music's " - Single".
    private static func plainTitle(_ title: String) -> String {
        for suffix in [" - Single", " - EP"] where title.hasSuffix(suffix) {
            return String(title.dropLast(suffix.count))
        }
        return title
    }
}

/// The newest release, large, on a glow of its cover's colour: what it is, when it came out,
/// how much you play whoever made it, and Play.
private struct FeaturedRelease: View {
    let release: Discovery.Release
    let plays: Int
    let isHeard: Bool
    @Environment(PlayerModel.self) private var player
    @Environment(\.openPlayRoute) private var openRoute
    @State private var glow: Color?
    @Environment(\.colorScheme) private var colorScheme

    private var cover: CoverArt {
        release.album.artwork.map(CoverArt.artwork) ?? .url(nil, seed: release.album.title)
    }

    var body: some View {
        let layout = Self.isStacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: 18)) : AnyLayout(HStackLayout(alignment: .bottom, spacing: 28))
        layout {
            Button { openRoute(.album(release.album)) } label: {
                CoverImage(cover: cover, size: Self.side)
                    .albumZoomSource(album: release.album)
                    .shadow(color: .black.opacity(0.3), radius: 18, y: 10)
            }
            .buttonStyle(.pressable)
            .accessibilityLabel(release.album.title)

            VStack(alignment: .leading, spacing: Design.Spacing.xSmall) {
                Text(eyebrow)
                    .eyebrowStyle()
                Text(CollectionKind.of(release.album).title)
                    .font(.collectionTitle)
                    .lineLimit(2)
                Text(release.album.artistName)
                    .font(.title3.weight(.medium))
                    .foregroundStyle(.tint)
                    .lineLimit(1)
                if let line = relationship {
                    Label(line, systemImage: "clock.arrow.circlepath")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.top, Design.Spacing.xSmall)
                }
                GlassEffectContainer(spacing: Design.Control.spacing) {
                    HStack(spacing: Design.Control.spacing) {
                        PlayButton {
                            player.play(.album(release.album), from: PlayContext(kind: .album, title: release.album.title))
                        }
                        GlassCircleButton(title: "Add to Library", systemImage: "plus") {
                            player.addToLibrary(release.album)
                        }
                        GlassCircleMenu(title: "More") {
                            GetWithLidarrButton(album: release.album.title, artist: release.album.artistName)
                            FeedItemMenu(item: FeedItem(album: release.album))
                        }
                    }
                }
                .padding(.top, Design.Spacing.medium)
            }
        }
        .padding(.horizontal, Design.Margin.page)
        .padding(.top, Design.Spacing.medium)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(alignment: .top) {
            // A colour already known is there from the first frame, rather than fading in.
            CoverGlow(color: glow ?? CoverTint.knownGlow(for: cover), overscroll: 160)
                .padding(.bottom, -40)
        }
        .task(id: release.id) {
            guard let found = await CoverTint.glow(for: cover), !Task.isCancelled else { return }
            withAnimation(Design.Motion.tint) { glow = found }
        }
    }

    #if os(macOS)
    private static let side: CGFloat = 220
    private static let isStacked = false
    #else
    private static let side: CGFloat = 200
    private static let isStacked = true
    #endif

    /// "NEW ALBUM · SEPTEMBER 24".
    private var eyebrow: String {
        let kind = switch release.kind {
        case .album: String(localized: "New Album")
        case .ep: String(localized: "New EP")
        case .single: String(localized: "New Single")
        }
        guard let date = release.date else { return kind.uppercased() }
        let when = Calendar.current.isDateInToday(date) ? String(localized: "Out Today") : date.formatted(.dateTime.month(.wide).day())
        return "\(kind) · \(when)".uppercased()
    }

    /// Why it's here: how much you play them, and whether you've heard it yet.
    private var relationship: String? {
        guard plays > 0 else { return nil }
        let played = String(AttributedString(localized: "You've played them ^[\(plays) time](inflect: true)").characters)
        return isHeard ? String(localized: "\(played), and you've started this one") : played
    }
}

/// A release: its cover, name and artist, and what it is and when. Upcoming ones carry the
/// day they're out on the cover.
struct ReleaseTile: View {
    let release: Discovery.Release
    /// A fixed side on a shelf; nil fills the grid's column.
    var side: CGFloat?
    /// Nothing of it heard yet: a dot before the title, as Podcasts marks an episode.
    var isUnheard = false
    @State private var isHovered = false
    @Environment(PlayerModel.self) private var player

    var body: some View {
        NavigationLink(value: PlayRoute.album(release.album)) {
            VStack(alignment: .leading, spacing: 6) {
                cover
                    .albumZoomSource(album: release.album)
                    .overlay(alignment: .topLeading) {
                        if release.isUpcoming, let date = release.date {
                            Text(date.formatted(.dateTime.month(.abbreviated).day()))
                                .font(.caption.weight(.bold))
                                .padding(.horizontal, Design.Spacing.small)
                                .padding(.vertical, Design.Spacing.xSmall)
                                .glassEffect(.regular, in: .capsule)
                                .padding(Design.Spacing.small)
                        }
                    }
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 5) {
                        if isUnheard {
                            Circle()
                                .fill(.tint)
                                .frame(width: 7, height: 7)
                                .accessibilityLabel("New to You")
                        }
                        Text(CollectionKind.of(release.album).title)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                    }
                    Text(release.album.artistName)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(release.isUpcoming ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                        .lineLimit(1)
                }
            }
            .frame(width: side, alignment: .leading)
            .contentShape(.rect)
            .onHover { hovering in
                withAnimation(Design.Motion.hover) { isHovered = hovering }
            }
            .accessibilityElement(children: .combine)
            // Play shows only under the pointer, so VoiceOver has it as an action.
            .accessibilityActions {
                if !release.isUpcoming {
                    Button("Play", action: playAlbum)
                }
            }
        }
        .buttonStyle(.pressable)
        .contextMenu {
            GetWithLidarrButton(album: release.album.title, artist: release.album.artistName)
            if !release.isUpcoming {
                FeedItemMenu(item: FeedItem(album: release.album))
            }
        }
    }

    @ViewBuilder
    private var cover: some View {
        let art = release.album.artwork.map(CoverArt.artwork) ?? .url(nil, seed: release.album.title)
        Group {
            if let side {
                CoverImage(cover: art, size: side)
            } else {
                Color.clear
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        GeometryReader { proxy in
                            CoverImage(cover: art, size: proxy.size.width)
                        }
                    }
            }
        }
        .shadow(color: .black.opacity(0.12), radius: 6, y: 3)
        // The pointer brings Play up over the cover, as Music's do; VoiceOver has it as an action.
        .coverPlayButton(isVisible: isHovered && !release.isUpcoming, action: playAlbum)
    }

    private func playAlbum() {
        player.play(.album(release.album), from: PlayContext(kind: .album, title: release.album.title))
    }

    /// "Single · Sep 20", or "Album · in 12 days" for one not out yet.
    private var detail: String {
        let kind = switch release.kind {
        case .album: String(localized: "Album")
        case .ep: String(localized: "EP")
        case .single: String(localized: "Single")
        }
        guard let date = release.date else { return kind }
        if release.isUpcoming {
            let days = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: .now), to: Calendar.current.startOfDay(for: date)).day ?? 0
            let when = days <= 1
                ? String(localized: "tomorrow")
                : String(AttributedString(localized: "in ^[\(days) day](inflect: true)").characters)
            return "\(kind) · \(when)"
        }
        return "\(kind) · \(date.formatted(.dateTime.month(.abbreviated).day()))"
    }
}

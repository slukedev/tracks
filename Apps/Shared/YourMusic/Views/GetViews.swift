import SwiftUI
import TracksCore

// The pieces every page that offers music you don't own is made of, on iPhone and Mac:
// the Get button, the mark for an album you own part or all of, album tiles and song rows
// with Get, Get More itself, an album's missing songs, and what's on its way. Each shows
// only while something can fetch (see ``GetMore/canGet``).

// MARK: - Get

/// Get, for something you don't own, then how it's coming: a turning ring once it's asked
/// for, a ring that fills as it comes in, a tick when it's here, and Try Again if it couldn't
/// be got. Its place keeps one size throughout, so nothing beside it moves.
struct GetButton: View {
    let state: GetState
    var title: LocalizedStringKey = "Get"
    /// What it gets, for VoiceOver and the pointer's help: "Get Night Drive".
    let subject: String
    /// Off in rows whose own ring already shows the song coming in.
    var showsProgress = true
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var taps = 0

    var body: some View {
        ZStack {
            switch state {
            case .idle:
                Button(action: tap) {
                    Text(title)
                        .fontWeight(.semibold)
                        .lineLimit(1)
                }
                .buttonStyle(.glass)
                .controlSize(.small)
                .help(Text("Get \(subject)"))
                .accessibilityLabel(Text("Get \(subject)"))
                .transition(change)
            case .queued where showsProgress:
                TurningRing(diameter: 18, lineWidth: 2.5, color: .accentColor)
                    .accessibilityElement()
                    .accessibilityLabel(Text("Getting \(subject)"))
                    .transition(change)
            case .progress(let fraction) where showsProgress:
                GetRing(fraction: fraction)
                    .accessibilityElement()
                    .accessibilityLabel(Text("Getting \(subject)"))
                    .accessibilityValue(Text(fraction, format: .percent.precision(.fractionLength(0))))
                    .transition(change)
            case .done where showsProgress:
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .accessibilityLabel(Text("\(subject) Is in Your Music"))
                    .transition(change)
            case .failed(let reason):
                Button(action: tap) {
                    Label("Try Again", systemImage: "arrow.clockwise")
                        .labelStyle(.iconOnly)
                        .fontWeight(.semibold)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .controlSize(.small)
                .help(reason)
                .accessibilityHint(reason)
                .transition(change)
            case .queued, .progress, .done, .owned:
                EmptyView()
            }
        }
        .frame(minWidth: Self.width, minHeight: Self.height)
        .animation(reduceMotion ? Animation.easeOut(duration: 0.15) : PlayMotion.value, value: phase)
        #if os(iOS)
        .sensoryFeedback(.impact(weight: .light), trigger: taps)
        #endif
    }

    #if os(macOS)
    static let width: CGFloat = 52
    static let height: CGFloat = 28
    #else
    static let width: CGFloat = 56
    static let height: CGFloat = 44
    #endif

    private func tap() {
        taps += 1
        action()
    }

    /// Changes between states cross-fade, a little scale with them; filling doesn't.
    private var change: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.88))
    }

    private var phase: Int {
        switch state {
        case .idle: 0
        case .queued: 1
        case .progress: 2
        case .done: 3
        case .failed: 4
        case .owned: 5
        }
    }
}

/// How far along something is: a ring that fills, a quarter of a second behind each step so
/// it reads as one motion rather than jumps.
private struct GetRing: View {
    let fraction: Double

    var body: some View {
        ZStack {
            Circle().stroke(.quaternary, lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: max(0.03, fraction))
                .stroke(.tint, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 18, height: 18)
        .animation(.linear(duration: 0.25), value: fraction)
    }
}

// MARK: - Marks

/// An album you own all or part of, marked on its cover. Nothing on one you have none of:
/// its Get button says that.
struct OwnershipBadge: View {
    let state: AlbumHoldings.State

    var body: some View {
        if let symbol {
            Image(systemName: symbol)
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                // A scrim rather than glass: legible on any cover, light or dark.
                .background(.black.opacity(0.55), in: .circle)
                .accessibilityHidden(true)
        }
    }

    private var symbol: String? {
        switch state {
        case .owned: "checkmark"
        case .partlyOwned: "circle.lefthalf.filled"
        case .missing: nil
        }
    }

    /// For the item that carries the mark.
    static func spoken(_ state: AlbumHoldings.State) -> String {
        switch state {
        case .owned: String(localized: "In Your Music")
        case .partlyOwned: String(localized: "Partly in Your Music")
        case .missing: String(localized: "Not in Your Music")
        }
    }
}

// MARK: - Albums and songs

/// An album you could get, on a shelf: its cover, marked if you own part of it, and Get
/// under it. Opens its page where there is one.
struct GetAlbumTile: View {
    let held: GetMore.HeldAlbum
    /// Under the title: its artist, unless given.
    var subtitle: String?

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    var body: some View {
        let getMore = music.getMore
        let album = held.album
        let state = getMore.state(ofAlbum: album)
        VStack(alignment: .leading, spacing: 6) {
            if let route = route(state: state) {
                #if os(macOS)
                // The library's tile: it brightens under the pointer, with its menu on it.
                LibraryCoverTile(
                    title: album.title,
                    subtitle: subtitle ?? album.artist,
                    route: route,
                    side: LibraryCoverTile<EmptyView, EmptyView>.shelfSide
                ) { side in
                    cover(state: state, side: side ?? LibraryCoverTile<EmptyView, EmptyView>.shelfSide)
                } menu: {
                    GetAlbumMenu(album: album, route: route)
                }
                .accessibilityValue(Text(OwnershipBadge.spoken(state)))
                #else
                NavigationLink(value: route) { label(state: state) }
                    .buttonStyle(.pressable)
                #endif
            } else {
                label(state: state)
            }
            GetButton(state: getMore.state(of: album), subject: album.title) { get() }
                // Leading, under the title, as the tile's text is.
                .frame(width: GetButton.width, alignment: .leading)
        }
        .contextMenu {
            GetAlbumMenu(album: album, route: route(state: state))
        }
    }

    private func label(state: AlbumHoldings.State) -> some View {
        TileLabel(title: held.album.title, subtitle: subtitle ?? held.album.artist) { side in
            cover(state: state, side: side)
        }
        .accessibilityValue(Text(OwnershipBadge.spoken(state)))
    }

    private func cover(state: AlbumHoldings.State, side: CGFloat) -> some View {
        CoverImage(cover: music.cover(of: held.album), size: side)
            .overlay(alignment: .bottomTrailing) {
                OwnershipBadge(state: state)
                    .padding(6)
            }
    }

    /// Your copy where you own part of it, else the server's page for it.
    private func route(state: AlbumHoldings.State) -> PlayRoute? {
        if state != .missing, let mine = music.getMore.ownedSongs(of: held.album).first {
            return .localAlbum(mine.albumKey)
        }
        if let serverID = held.album.serverID, let albumID = held.album.albumID {
            return .serverAlbum(serverID: serverID, albumID: albumID)
        }
        return nil
    }

    private func get() {
        Task {
            if let problem = await music.getMore.get(held.album) { player.confirm(problem) }
        }
    }
}

/// An album's menu, wherever it can be got from.
struct GetAlbumMenu: View {
    let album: GetMore.Album
    var route: PlayRoute?

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player
    @Environment(\.openPlayRoute) private var openPlayRoute

    var body: some View {
        let getMore = music.getMore
        switch getMore.state(of: album) {
        case .idle:
            if getMore.state(ofAlbum: album) == .partlyOwned {
                Button("Get the Rest", systemImage: "arrow.down.circle") { get() }
            } else {
                Button("Get Album", systemImage: "arrow.down.circle") { get() }
            }
        case .failed:
            Button("Try Again", systemImage: "arrow.clockwise") { get() }
        case .queued, .progress:
            if openPlayRoute.canPush {
                Button("Show Downloads", systemImage: "arrow.down.circle") { openPlayRoute(.yourMusic(.downloads)) }
            }
        case .done, .owned:
            EmptyView()
        }
        if let route, openPlayRoute.canPush {
            Button("Go to Album", systemImage: "square.stack") { openPlayRoute(route) }
        }
    }

    private func get() {
        Task {
            if let problem = await music.getMore.get(album) { player.confirm(problem) }
        }
    }
}

/// A song you could get, in a list: it plays from the server that found it, and Get keeps it.
struct GetSongRow: View {
    let track: LocalTrack
    /// The list it plays on from.
    let queue: [LocalTrack]
    let context: PlayContext
    var showsAlbum = false

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    var body: some View {
        HStack(spacing: 4) {
            Button {
                player.play(.local(queue, startingAt: queue.firstIndex(of: track) ?? 0), from: context)
            } label: {
                TrackRow(
                    title: track.title,
                    subtitle: showsAlbum ? track.album : track.artist,
                    cover: .url(music.artworkURL(track.artwork)?.absoluteString, seed: track.album ?? track.title),
                    isCurrent: player.current?.local?.id == track.id,
                    isPlayable: music.isPlayable(track)
                )
            }
            .buttonStyle(.plain)
            if music.getMore.offersGet(track) {
                GetButton(state: music.getMore.state(ofSong: track.identity, track: track), subject: track.title) {
                    Task {
                        if let problem = await music.getMore.get(track) { player.confirm(problem) }
                    }
                }
            }
        }
        .contextMenu { LocalTrackMenu(track: track) }
    }
}

/// A song a Trackside server could get for you. Where the server can, it plays without being
/// kept; only Get keeps it.
struct GetOfferRow: View {
    let offer: GetMore.Offer
    var showsAlbum = false
    /// Its quality as well, where a search shows more than one copy of a song.
    var showsQuality = false

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    var body: some View {
        let candidate = offer.candidate
        HStack(spacing: 4) {
            if let track = music.servers.unownedTrack(for: offer) {
                Button {
                    player.play(.local([track], startingAt: 0), from: .songs(candidate.title))
                } label: {
                    row(isCurrent: player.current?.local?.id == track.id)
                }
                .buttonStyle(.plain)
            } else {
                row(isCurrent: false)
                    .accessibilityElement(children: .combine)
            }
            GetButton(state: music.getMore.state(of: offer), subject: candidate.title) {
                Task {
                    if let problem = await music.getMore.get(offer) { player.confirm(problem) }
                }
            }
        }
    }

    private func row(isCurrent: Bool) -> some View {
        TrackRow(
            title: offer.candidate.title,
            subtitle: subtitle,
            cover: .url(nil, seed: offer.candidate.album ?? offer.candidate.title),
            isCurrent: isCurrent
        )
    }

    /// "Artist · Album · Lossless", as much of it as the row is for.
    private var subtitle: String {
        let candidate = offer.candidate
        let quality: String? = switch candidate.quality {
        case .lossless: String(localized: "Lossless")
        case .high: String(localized: "High Quality")
        case .standard, .unknown, nil: nil
        }
        let parts: [String?] = [
            showsAlbum && candidate.album != nil ? nil : candidate.artist,
            showsAlbum || showsQuality ? candidate.album : nil,
            showsQuality ? quality : nil,
        ]
        return parts.compactMap(\.self).joined(separator: " · ")
    }
}

/// A song in a Get More list: one a server found and plays, or one it could get.
enum GetSongItem: Identifiable {
    case found(LocalTrack)
    case offer(GetMore.Offer)

    var id: String {
        switch self {
        case .found(let track): track.id
        case .offer(let offer): offer.id
        }
    }
}

// MARK: - Downloads

/// Downloads, from wherever music is got: a turning ring beside it while anything's on its way.
struct DownloadsLink: View {
    @Environment(YourMusic.self) private var music
    @Environment(\.openPlayRoute) private var openPlayRoute

    var body: some View {
        let isGetting = music.getMore.isGettingAnything || !music.downloads.downloading.isEmpty
        if openPlayRoute.canPush {
            Button {
                openPlayRoute(.yourMusic(.downloads))
            } label: {
                HStack(spacing: 6) {
                    if isGetting {
                        TurningRing(diameter: 12, lineWidth: 1.8, color: .accentColor)
                            .transition(.opacity)
                    }
                    Text("Downloads")
                }
            }
            .buttonStyle(.plain)
            .foregroundStyle(.tint)
            .animation(.easeOut(duration: 0.2), value: isGetting)
            .accessibilityValue(isGetting ? Text("Getting Music") : Text(verbatim: ""))
        }
    }
}

/// Something your servers or Lidarr are fetching, on the Downloads page.
struct IncomingRow: View {
    let item: GetMore.Incoming

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.isAlbum ? "square.stack" : "music.note")
                .font(.body)
                .foregroundStyle(.secondary)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .lineLimit(1)
                if let artist = item.artist, !artist.isEmpty {
                    Text(artist)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            GetButton(state: item.state, subject: item.title) {
                Task {
                    if let problem = await music.getMore.retry(item) { player.confirm(problem) }
                }
            }
        }
    }
}

// MARK: - Get More

/// What you could get, laid out for a page: albums on a shelf, marked by what you own of
/// them, then songs, in columns of four that scroll sideways, or as a list.
struct GetMoreBlock: View {
    let albums: [GetMore.HeldAlbum]
    let songs: [LocalTrack]
    /// Songs a Trackside server could get, after the ones that play.
    var offers: [GetMore.Offer] = []
    let context: PlayContext
    var songLayout: SongLayout = .columns
    /// The first suggestions are still being found: the songs' places are held.
    var isLoading = false

    enum SongLayout { case columns, list }

    @ScaledMetric(relativeTo: .body) private var rowHeight: CGFloat = 64
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if albums.isEmpty {
                ShelfHeader(title: String(localized: "Get More")) { DownloadsLink() }
                    .padding(.horizontal, Design.Margin.page)
            } else {
                Shelf(title: String(localized: "Get More"), items: albums) {
                    DownloadsLink()
                } tile: { held in
                    GetAlbumTile(held: held)
                }
            }
            if !items.isEmpty {
                switch songLayout {
                case .columns: songColumns
                case .list: songList
                }
            } else if isLoading {
                loadingColumns
            }
        }
        .animation(reduceMotion ? nil : PlayMotion.row, value: albums.map(\.id) + items.map(\.id))
    }

    private var items: [GetSongItem] {
        songs.map(GetSongItem.found) + offers.map(GetSongItem.offer)
    }

    @ViewBuilder
    private func row(_ item: GetSongItem, showsAlbum: Bool = false) -> some View {
        switch item {
        case .found(let track): GetSongRow(track: track, queue: songs, context: context, showsAlbum: showsAlbum)
        case .offer(let offer): GetOfferRow(offer: offer, showsAlbum: showsAlbum)
        }
    }

    private var songColumns: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: Design.Shelf.spacing) {
                ForEach(SuggestionShelfPaging.columns(of: items, goesOn: false)) { column in
                    VStack(spacing: 0) {
                        ForEach(column.items) { item in
                            row(item)
                                .frame(height: rowHeight)
                            if item.id != column.items.last?.id {
                                Divider().padding(.leading, 60)
                            }
                        }
                    }
                    .containerRelativeFrame(.horizontal, alignment: .leading) { length, _ in
                        SuggestionShelfPaging.columnWidth(in: length)
                    }
                }
            }
            .scrollTargetLayout()
        }
        .contentMargins(.horizontal, Design.Margin.page, for: .scrollContent)
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
    }

    private var songList: some View {
        let shown = Array(items.prefix(ArtistSongsSection<EmptyView, EmptyView>.visibleRows))
        return VStack(spacing: 0) {
            ForEach(shown) { item in
                row(item, showsAlbum: true)
                if item.id != shown.last?.id {
                    Divider().padding(.leading, 60)
                }
            }
        }
        .padding(.horizontal, Design.Margin.page)
    }

    private var loadingColumns: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: Design.Shelf.spacing) {
                ForEach(0..<SuggestionShelfPaging.loadingColumns, id: \.self) { column in
                    VStack(spacing: 0) {
                        ForEach(0..<SuggestionShelfPaging.rowsPerColumn, id: \.self) { row in
                            LoadingRow(width: [0.62, 0.48, 0.7, 0.4][(row + column) % 4])
                                .frame(height: rowHeight)
                        }
                    }
                    .containerRelativeFrame(.horizontal, alignment: .leading) { length, _ in
                        SuggestionShelfPaging.columnWidth(in: length)
                    }
                }
            }
        }
        .contentMargins(.horizontal, Design.Margin.page, for: .scrollContent)
        .scrollDisabled(true)
        .scrollIndicators(.hidden)
        .accessibilityElement()
        .accessibilityLabel(Text("Loading"))
        .transition(.opacity)
    }
}

/// Get More on Your Music: songs and albums you don't own, picked from what you play. Only
/// while something can fetch, and only once there's anything to show or it's on its way.
struct GetMoreSection: View {
    @Environment(YourMusic.self) private var music

    var body: some View {
        let getMore = music.getMore
        if getMore.canGet {
            let picks = getMore.suggestions
            let albums = picks.compactMap { pick -> GetMore.HeldAlbum? in
                if case .album(let held) = pick.item { return held }
                return nil
            }
            let songs = picks.compactMap { pick -> LocalTrack? in
                if case .song(let track) = pick.item { return track }
                return nil
            }
            let offers = picks.compactMap { pick -> GetMore.Offer? in
                if case .offer(let offer) = pick.item { return offer }
                return nil
            }
            let isEmpty = albums.isEmpty && songs.isEmpty && offers.isEmpty
            if !isEmpty || getMore.isLoadingSuggestions {
                GetMoreBlock(
                    albums: albums,
                    songs: songs,
                    offers: offers,
                    context: .songs(String(localized: "Get More")),
                    isLoading: getMore.isLoadingSuggestions && isEmpty
                )
                .transition(.opacity)
            }
        }
    }
}

/// An artist's music you don't own yet, on their page: their albums marked partly owned or
/// missing, with Get, and their songs you don't have.
struct ArtistGetSection: View {
    let artist: String
    let gets: GetMore.ArtistGets?

    @Environment(YourMusic.self) private var music

    var body: some View {
        if music.getMore.canGet, let gets, !gets.isEmpty {
            GetMoreBlock(
                albums: gets.albums,
                songs: gets.songs,
                offers: gets.offers,
                context: PlayContext(kind: .artist, title: artist),
                songLayout: .list
            )
            .transition(.opacity)
        }
    }
}

/// The same, as a card of rows, for pages laid out in cards: an artist's stats.
struct ArtistGetCard: View {
    let artist: String
    let gets: GetMore.ArtistGets?

    @Environment(YourMusic.self) private var music

    var body: some View {
        if music.getMore.canGet, let gets, !gets.isEmpty {
            let songs = Array(gets.songs.prefix(5))
            let offers = Array(gets.offers.prefix(5 - songs.count))
            let albums = Array(gets.albums.prefix(6))
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Get More") { DownloadsLink() }
                Card {
                    VStack(spacing: 0) {
                        ForEach(albums) { held in
                            GetAlbumRow(held: held)
                            if held.id != albums.last?.id || !songs.isEmpty || !offers.isEmpty {
                                Divider().padding(.leading, 56)
                            }
                        }
                        ForEach(songs) { track in
                            GetSongRow(track: track, queue: gets.songs, context: PlayContext(kind: .artist, title: artist), showsAlbum: true)
                            if track.id != songs.last?.id || !offers.isEmpty {
                                Divider().padding(.leading, 60)
                            }
                        }
                        ForEach(offers) { offer in
                            GetOfferRow(offer: offer, showsAlbum: true)
                            if offer.id != offers.last?.id {
                                Divider().padding(.leading, 60)
                            }
                        }
                    }
                }
            }
            .transition(.opacity)
        }
    }
}

/// An album you could get, as a row: its cover, marked if you own part of it, and Get.
struct GetAlbumRow: View {
    let held: GetMore.HeldAlbum

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    var body: some View {
        let album = held.album
        let state = music.getMore.state(ofAlbum: album)
        HStack(spacing: 12) {
            CoverImage(cover: music.cover(of: album), size: 44)
                .overlay(alignment: .bottomTrailing) {
                    OwnershipBadge(state: state)
                        .scaleEffect(0.75, anchor: .bottomTrailing)
                        .padding(2)
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(album.title)
                    .lineLimit(1)
                if let year = album.year {
                    Text(String(year))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityValue(Text(OwnershipBadge.spoken(state)))
            Spacer(minLength: 8)
            GetButton(state: music.getMore.state(of: album), subject: album.title) {
                Task {
                    if let problem = await music.getMore.get(album) { player.confirm(problem) }
                }
            }
        }
        .padding(.vertical, 6)
        .contentShape(.rect)
        .contextMenu { GetAlbumMenu(album: album) }
    }
}

extension View {
    /// Looks up an artist's music you don't own for ``ArtistGetSection``, while something
    /// can fetch, and again as what's in your music changes.
    func loadsGets(forArtist name: String, into gets: Binding<GetMore.ArtistGets?>) -> some View {
        modifier(ArtistGetsLoader(name: name, gets: gets))
    }
}

private struct ArtistGetsLoader: ViewModifier {
    let name: String
    @Binding var gets: GetMore.ArtistGets?
    @Environment(YourMusic.self) private var music
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.task(id: "\(name).\(music.getMore.canGet).\(music.indexRevision)") {
            guard music.getMore.canGet, !name.trimmingCharacters(in: .whitespaces).isEmpty else {
                gets = nil
                return
            }
            let found = await music.getMore.gets(forArtist: name)
            guard !Task.isCancelled else { return }
            withAnimation(reduceMotion ? nil : PlayMotion.panel) { gets = found }
        }
    }
}

// MARK: - Missing songs

/// "Missing", over the songs of an album you own part of that you don't have, with Get All.
struct MissingSongsHeader: View {
    let gaps: GetMore.AlbumGaps

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Text("Missing")
                .font(.sectionTitle)
                .foregroundStyle(.primary)
                .textCase(nil)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            GetButton(state: music.getMore.state(of: gaps.album), title: "Get All", subject: gaps.album.title) {
                let found = gaps.missing.compactMap(\.found)
                let offers = gaps.missing.compactMap(\.offer)
                Task {
                    if let problem = await music.getMore.get(gaps.album, only: found.isEmpty ? nil : found, offers: offers) {
                        player.confirm(problem)
                    }
                }
            }
            .fixedSize()
        }
    }
}

/// One song of an album you own part of that you don't have: dimmed, as Music dims what
/// isn't in your library, with Get where a server found it or could get it.
struct MissingSongRow: View {
    let song: GetMore.MissingSong
    let album: GetMore.Album

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    var body: some View {
        HStack(spacing: 4) {
            TrackRow(
                title: song.title,
                subtitle: StatsCalculator.folded(song.artist) == StatsCalculator.folded(album.artist) ? nil : song.artist,
                number: song.number,
                isPlayable: false
            )
            .accessibilityElement(children: .combine)
            .accessibilityValue(Text(OwnershipBadge.spoken(.missing)))
            if song.canGet {
                GetButton(state: music.getMore.state(of: song), subject: song.title) {
                    Task {
                        if let problem = await music.getMore.get(song) { player.confirm(problem) }
                    }
                }
            }
        }
    }
}

// MARK: - Covers

extension YourMusic {
    /// An album's cover: its server's, else the one Lidarr knows.
    func cover(of album: GetMore.Album) -> CoverArt {
        if let artwork = album.artwork, let url = artworkURL(artwork) {
            return .url(url.absoluteString, seed: album.title)
        }
        return .url(album.coverURL, seed: album.title)
    }
}

extension GetMore.Album {
    /// An album a server found, as its shelf has it.
    init?(_ album: ServerDiscovery.Album) {
        guard case .serverAlbum(let serverID, let albumID) = album.route else { return nil }
        self.init(title: album.title, artist: album.artist, serverID: serverID, albumID: albumID, artwork: album.artwork)
    }
}

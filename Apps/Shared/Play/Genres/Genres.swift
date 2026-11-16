import SwiftUI
import MusicKit
import TracksCore

extension MusicGenre {
    /// The genre's name in the person's language.
    var title: String {
        switch id {
        case "pop": String(localized: "Pop")
        case "hip-hop": String(localized: "Hip-Hop")
        case "r-b": String(localized: "R&B")
        case "rock": String(localized: "Rock")
        case "alternative": String(localized: "Alternative")
        case "electronic": String(localized: "Electronic")
        case "country": String(localized: "Country")
        case "latin": String(localized: "Latin")
        case "k-pop": String(localized: "K-Pop")
        case "metal": String(localized: "Metal")
        case "jazz": String(localized: "Jazz")
        case "singer-songwriter": String(localized: "Singer/Songwriter")
        case "classical": String(localized: "Classical")
        case "afrobeats": String(localized: "Afrobeats")
        case "reggae": String(localized: "Reggae")
        case "blues": String(localized: "Blues")
        case "soundtrack": String(localized: "Soundtrack")
        case "christian": String(localized: "Christian")
        default: name
        }
    }

    var symbol: String {
        switch id {
        case "pop": "star.fill"
        case "hip-hop": "music.mic"
        case "r-b": "heart.fill"
        case "rock": "guitars.fill"
        case "alternative": "bolt.horizontal.fill"
        case "electronic": "waveform"
        case "country": "hat.widebrim.fill"
        case "latin": "flame.fill"
        case "k-pop": "sparkle"
        case "metal": "bolt.fill"
        case "jazz": "music.quarternote.3"
        case "singer-songwriter": "guitars"
        case "classical": "pianokeys"
        case "afrobeats": "figure.dance"
        case "reggae": "leaf.fill"
        case "blues": "cloud.rain.fill"
        case "soundtrack": "film.fill"
        case "christian": "hands.and.sparkles.fill"
        default: "music.note"
        }
    }

    /// The genre's own colour, rich enough for white type, one no other genre shares.
    var color: Color {
        switch id {
        case "pop": Color(red: 0.92, green: 0.22, blue: 0.52)
        case "hip-hop": Color(red: 0.72, green: 0.45, blue: 0.05)
        case "r-b": Color(red: 0.55, green: 0.18, blue: 0.54)
        case "rock": Color(red: 0.78, green: 0.13, blue: 0.14)
        case "alternative": Color(red: 0.08, green: 0.5, blue: 0.46)
        case "electronic": Color(red: 0.12, green: 0.4, blue: 0.95)
        case "country": Color(red: 0.62, green: 0.36, blue: 0.17)
        case "latin": Color(red: 0.94, green: 0.35, blue: 0.2)
        case "k-pop": Color(red: 0.58, green: 0.33, blue: 0.92)
        case "metal": Color(red: 0.22, green: 0.22, blue: 0.26)
        case "jazz": Color(red: 0.1, green: 0.2, blue: 0.46)
        case "singer-songwriter": Color(red: 0.38, green: 0.48, blue: 0.24)
        case "classical": Color(red: 0.5, green: 0.1, blue: 0.2)
        case "afrobeats": Color(red: 0.12, green: 0.55, blue: 0.24)
        case "reggae": Color(red: 0.8, green: 0.25, blue: 0.08)
        case "blues": Color(red: 0.2, green: 0.36, blue: 0.62)
        case "soundtrack": Color(red: 0.32, green: 0.35, blue: 0.43)
        case "christian": Color(red: 0.16, green: 0.55, blue: 0.8)
        default: .gray
        }
    }

    var context: PlayContext { PlayContext(kind: .endless, title: title) }
}

/// A genre on Play's shelf: it, and the covers of what you play most in it.
struct GenreTileItem: Identifiable {
    let genre: MusicGenre
    let covers: [CoverArt]
    var id: String { genre.id }
}

/// The genres for Play's shelf, from the music playing: every genre with Apple Music, the ones
/// you play first; with your own music, only the ones in it, the most played first.
@MainActor
enum GenreShelfItems {
    static func load(model: AppModel, facts: [String: SongFacts]) async -> [GenreTileItem] {
        let history = model.library.history
        if model.musicSource == .yourMusic {
            let music = model.yourMusic
            let playable = music.playableTracks
            let groups = await OffMainActor.run { LocalGenres.group(playable, history: history, facts: facts) }
            return groups.map { group in
                GenreTileItem(
                    genre: group.genre,
                    covers: group.tracks.prefix(3).map { .url(music.artworkURL($0.artwork)?.absoluteString, seed: $0.album ?? $0.title) }
                )
            }
        }
        let signals = model.player.signals
        let profiles = await OffMainActor.run { MusicGenre.profiles(in: history, signals: signals) }
        return profiles.map { profile in
            GenreTileItem(
                genre: profile.genre,
                covers: profile.songs.prefix(3).map { .url($0.artworkURL, seed: $0.albumTitle ?? $0.title) }
            )
        }
    }
}

/// Your own music, by genre: the songs in each, the most played first.
nonisolated enum LocalGenres {
    struct Group: Sendable {
        let genre: MusicGenre
        let tracks: [LocalTrack]
        let plays: Int
    }

    static func group(_ tracks: [LocalTrack], history: ListeningHistory, facts: [String: SongFacts]) -> [Group] {
        var byGenre: [String: [LocalTrack]] = [:]
        var byTag: [String: String?] = [:]
        var seen = Set<String>()
        for track in tracks where seen.insert(track.identity).inserted {
            guard let tag = track.genre ?? history.songMetadata[track.identity]?.genre else { continue }
            let id: String?
            if let known = byTag[tag] {
                id = known
            } else {
                id = MusicGenre.of(genre: tag)?.id
                byTag[tag] = id
            }
            if let id { byGenre[id, default: []].append(track) }
        }
        return MusicGenre.all.compactMap { genre -> Group? in
            guard let tracks = byGenre[genre.id] else { return nil }
            let sorted = tracks.sorted { (facts[$0.identity]?.plays ?? 0, $1.title) > (facts[$1.identity]?.plays ?? 0, $0.title) }
            return Group(genre: genre, tracks: sorted, plays: tracks.reduce(0) { $0 + (facts[$1.identity]?.plays ?? 0) })
        }
        .sorted { $0.plays == $1.plays ? $0.tracks.count > $1.tracks.count : $0.plays > $1.plays }
    }
}

// MARK: - On Play

/// Genres, on Play: every genre in a row to scroll through, the ones you play first, each
/// with the covers of what you play in it.
struct GenreShelf: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayFeed.self) private var feed
    /// Nil until worked out: the shelf holds its shape meanwhile.
    @State private var items: [GenreTileItem]?
    /// When the genres were last worked out. One more song rarely reorders them, and working
    /// them out goes through the whole history, so while music plays they're looked at again
    /// only every few minutes.
    @State private var loaded: (key: GenreKey, at: ContinuousClock.Instant)?
    @ScaledMetric(relativeTo: .headline) private var height: CGFloat = MoodShelf.height

    var body: some View {
        let height = min(height, 130)
        Group {
            if let items {
                if items.isEmpty {
                    // Nothing to show, but still here, so the shelf looks again when your
                    // music changes.
                    Color.clear.frame(height: 0)
                } else {
                    Shelf(title: String(localized: "Genres"), items: items) { EmptyView() } tile: { item in
                        GenreTile(item: item, height: height)
                    }
                }
            } else {
                placeholder(height: height)
            }
        }
        .task(id: key) {
            let key = key
            // Only the history moving on waits; another source or library looks at once.
            if let loaded, loaded.key.source == key.source, loaded.key.songs == key.songs {
                let due = loaded.at + Self.refreshInterval
                if due > .now {
                    try? await Task.sleep(until: due)
                    guard !Task.isCancelled else { return }
                }
            }
            let found = await GenreShelfItems.load(model: model, facts: feed.facts)
            guard !Task.isCancelled else { return }
            items = found
            loaded = (key, .now)
        }
    }

    private static let refreshInterval: Duration = .seconds(300)

    private var key: GenreKey {
        GenreKey(source: model.musicSource, revision: model.library.revision, songs: model.yourMusic.index.tracks.count)
    }

    /// The shelf's shape while the genres are worked out: its name, and plain tiles.
    private func placeholder(height: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ShelfHeader(title: String(localized: "Genres")) { EmptyView() }
                .padding(.horizontal, PlayMetrics.margin)
            ScrollView(.horizontal) {
                HStack(spacing: PlayMetrics.shelfSpacing) {
                    ForEach(0..<6, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(Color.placeholderFill)
                            .frame(width: height * 1.75, height: height)
                    }
                }
            }
            .contentMargins(.horizontal, PlayMetrics.margin, for: .scrollContent)
            .scrollDisabled(true)
            .scrollIndicators(.hidden)
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Loading Genres"))
    }
}

/// What the genre shelves are worked out from: a change to any works them out again.
private struct GenreKey: Equatable {
    let source: MusicSource
    let revision: Int
    let songs: Int
}

/// A genre's tile: its page's colours and its name in its page's type, with a record sliding
/// out at the trailing edge, its label the cover of what you play most in it, or the genre's
/// symbol on its colour. Moods show a symbol, a feeling; genres show a record, the music.
struct GenreTile: View {
    let item: GenreTileItem
    let height: CGFloat
    /// Fills its grid cell's width, rather than the shelf's fixed shape.
    var fillsWidth = false

    var body: some View {
        let style = item.genre.style
        NavigationLink(value: PlayRoute.genre(item.genre)) {
            ZStack(alignment: .bottomLeading) {
                RoomTileField(style: style)
                RecordDisc(label: item.covers.first, symbol: item.genre.symbol, labelColor: style.base.mix(with: .white, by: 0.22))
                    .frame(width: height * 0.92, height: height * 0.92)
                    .offset(x: height * 0.24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    .accessibilityHidden(true)
                Text(item.genre.title)
                    .font(style.title.font(size: MoodTile.nameSize))
                    .tracking(style.title.tracking * 0.5)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .shadow(color: .black.opacity(0.25), radius: 6, y: 1)
                    .padding(12)
            }
            .frame(width: fillsWidth ? nil : height * 1.75, height: height)
            .frame(maxWidth: fillsWidth ? .infinity : nil)
            .clipShape(.rect(cornerRadius: 16, style: .continuous))
            .contentShape(.rect(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(item.genre.title)
    }
}

/// A record: black vinyl with a few grooves catching the light, and its label, a cover or a
/// symbol on a colour.
struct RecordDisc: View {
    let label: CoverArt?
    let symbol: String
    let labelColor: Color

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            let labelSide = side * 0.42
            ZStack {
                Circle().fill(Color(white: 0.07).gradient)
                ForEach([0.92, 0.8, 0.68, 0.56], id: \.self) { scale in
                    Circle()
                        .strokeBorder(.white.opacity(0.07), lineWidth: 0.75)
                        .frame(width: side * scale, height: side * scale)
                }
                Circle()
                    .fill(.white.opacity(0.08))
                    .frame(width: side, height: side)
                    .mask {
                        AngularGradient(colors: [.clear, .white, .clear, .clear, .white, .clear], center: .center)
                    }
                Group {
                    if let label {
                        CoverImage(cover: label, size: labelSide, isBare: true)
                    } else {
                        ZStack {
                            labelColor
                            Image(systemName: symbol)
                                .font(.system(size: labelSide * 0.42, weight: .bold))
                                .foregroundStyle(.white.opacity(0.85))
                        }
                    }
                }
                .frame(width: labelSide, height: labelSide)
                .clipShape(.circle)
                Circle()
                    .fill(Color(white: 0.07))
                    .frame(width: side * 0.04, height: side * 0.04)
            }
            .frame(width: side, height: side)
            .shadow(color: .black.opacity(0.3), radius: 6, x: -2, y: 2)
        }
    }
}

// MARK: - Playing

/// Starting a genre's station, from its page: your songs in it and new finds from its charts,
/// picked live. With your own music, your songs in it, and the ones you've never played.
@MainActor
enum GenrePlayback {
    /// - Parameter charts: the genre's chart songs, where they're already loaded.
    static func start(_ genre: MusicGenre, model: AppModel, charts: [Song]? = nil) async {
        await start(genre, library: model.library, yourMusic: model.yourMusic, player: model.player, charts: charts)
    }

    static func followRestarts(library: Library, yourMusic: YourMusic, player: PlayerModel) {
        player.restartGenre = { [weak library, weak yourMusic, weak player] genre, first in
            guard let library, let yourMusic, let player else { return }
            await start(genre, library: library, yourMusic: yourMusic, player: player, startingWith: first)
        }
    }

    private static func start(
        _ genre: MusicGenre,
        library: Library,
        yourMusic: YourMusic,
        player: PlayerModel,
        charts: [Song]? = nil,
        startingWith first: MixSong? = nil
    ) async {
        let (history, signals, isDemo) = (library.history, player.signals, player.isDemo)
        if MusicSource.current == .yourMusic {
            let tracks = yourMusic.playableTracks
            let mix = await OffMainActor.run {
                LiveMix.genre(genre, from: tracks, history: history, signals: signals, seed: .random(in: 0...UInt64.max))
            }
            guard !mix.isEmpty else {
                player.problem = .failed(String(localized: "None of your music is \(genre.title) yet."))
                return
            }
            await player.startLive(mix, from: genre.context, startingWith: first)
            return
        }
        let songs = if let charts { charts } else { await GenreCatalog.load(genre, isDemo: isDemo).songs }
        let newFinds = songs.map(MixSong.init(catalog:))
        let mix = await OffMainActor.run {
            LiveMix.genre(genre, from: history, signals: signals, newFinds: newFinds, seed: .random(in: 0...UInt64.max))
                .playable(isDemo: isDemo)
        }
        guard !mix.isEmpty else {
            player.problem = .failed(String(localized: "Nothing to play for \(genre.title) yet."))
            return
        }
        await player.startLive(mix, from: genre.context, startingWith: first)
    }
}

/// Apple Music's charts for a genre: its most played songs, albums and playlists.
enum GenreCatalog {
    struct Found {
        var songs: [Song]
        var albums: [FeedItem]
        var playlists: [FeedItem]

        static let empty = Found(songs: [], albums: [], playlists: [])
    }

    @MainActor
    static func load(_ genre: MusicGenre, isDemo: Bool) async -> Found {
        #if DEBUG
        if isDemo {
            let offset = 2_000 + (MusicGenre.all.firstIndex(of: genre) ?? 0) * 40
            return Found(songs: DemoCatalog.songs(count: 25, offset: offset, artists: nil), albums: [], playlists: [])
        }
        #endif
        guard !isDemo, MusicAuthorization.currentStatus == .authorized else { return .empty }
        let charts = await charts(for: genre)
        let allowsExplicit = PlayPreferences.allowsExplicit
        return Found(
            songs: PlayPreferences.versions(of: charts.songs, allowsExplicit: allowsExplicit),
            albums: PlayPreferences.versions(of: charts.albums, allowsExplicit: allowsExplicit).map(FeedItem.init(album:)),
            playlists: charts.playlists.map(FeedItem.init(playlist:))
        )
    }

    @concurrent
    nonisolated private static func charts(for genre: MusicGenre) async -> (songs: [Song], albums: [Album], playlists: [Playlist]) {
        let lookup = MusicCatalogResourceRequest<Genre>(matching: \.id, equalTo: MusicItemID(genre.appleMusicID))
        guard let found = try? await lookup.response().items.first else { return ([], [], []) }
        var request = MusicCatalogChartsRequest(genre: found, kinds: [.mostPlayed], types: [Song.self, Album.self, Playlist.self])
        request.limit = 25
        guard let response = try? await request.response() else { return ([], [], []) }
        return (
            Array(response.songCharts.first?.items ?? []),
            Array(response.albumCharts.first?.items ?? []),
            Array(response.playlistCharts.first?.items ?? [])
        )
    }
}

import Foundation
import TracksEngine

/// Your library's songs, for asking what you own of an album: the shared engine's rules, so
/// every platform marks the same albums owned, partly owned or missing. A song counts as yours
/// under its history identity or a looser name that sets aside a featured artist or a
/// remaster tag. Building one reads every song: do it off the main actor, once per change.
public final class OwnedMusic: Sendable {
    private let library: OwnedLibrary

    public init(songs: [(title: String, artist: String)]) {
        library = OwnedLibrary(songs: songs.map { TracksEngine.SongName(title: $0.title, artist: $0.artist) })
    }

    /// Nothing owned yet, until the library has been read.
    public static let empty = OwnedMusic(songs: [])

    public var songCount: Int { Int(library.songCount()) }

    public func owns(title: String, artist: String) -> Bool {
        library.owns(title: title, artist: artist)
    }

    /// An album's songs, in album order, judged against the library.
    public func album(_ tracks: [(title: String, artist: String)]) -> AlbumHoldings {
        AlbumHoldings(library.classifyAlbum(tracks: tracks.map { TracksEngine.SongName(title: $0.title, artist: $0.artist) }))
    }
}

/// What you own of an album.
public struct AlbumHoldings: Sendable, Equatable {
    public enum State: Sendable, Equatable {
        case owned, partlyOwned, missing
    }

    public let state: State
    /// The positions in the album's tracklist of the songs you don't own.
    public let missing: [Int]
    public let ownedCount: Int

    public init(state: State, missing: [Int], ownedCount: Int) {
        self.state = state
        self.missing = missing
        self.ownedCount = ownedCount
    }

    init(_ result: TracksEngine.AlbumOwnership) {
        self.init(state: State(result.ownership), missing: result.missing.map { Int($0) }, ownedCount: Int(result.ownedCount))
    }

    /// From 0 to 1; 0 for an album with no songs.
    public var ownedShare: Double {
        let total = ownedCount + missing.count
        return total == 0 ? 0 : Double(ownedCount) / Double(total)
    }

    /// From counts alone, for an album whose tracklist hasn't been fetched: how many of its
    /// songs you have, and how many it has where that's known.
    public static func state(owned: Int, total: Int?) -> State {
        State(TracksEngine.ownershipOfCounts(owned: UInt32(clamping: owned), total: total.map { UInt32(clamping: $0) }))
    }
}

extension AlbumHoldings.State {
    init(_ ownership: TracksEngine.Ownership) {
        switch ownership {
        case .owned: self = .owned
        case .partlyOwned: self = .partlyOwned
        case .missing: self = .missing
        }
    }
}

/// Get More's order: what you don't own, ranked by what you play. The shared engine's rules,
/// so Android picks the same. See `tracks-library` in the engine for them.
public enum GetMoreRanking {
    public struct Candidate: Sendable, Equatable {
        public let id: String
        public let isAlbum: Bool
        public let artist: String
        /// From 0 to 1. Anything at 1 is left out unless it's been asked for.
        public let ownedShare: Double
        /// Asked for already: kept in its place, so its progress stays where it was.
        public let isRequested: Bool

        public init(id: String, isAlbum: Bool, artist: String, ownedShare: Double = 0, isRequested: Bool = false) {
            self.id = id
            self.isAlbum = isAlbum
            self.artist = artist
            self.ownedShare = ownedShare
            self.isRequested = isRequested
        }
    }

    /// An artist you play.
    public struct Taste: Sendable, Equatable {
        public let name: String
        public let plays: Int
        public let lastPlayed: Date?

        public init(name: String, plays: Int, lastPlayed: Date? = nil) {
            self.name = name
            self.plays = plays
            self.lastPlayed = lastPlayed
        }
    }

    /// An artist like one you play. Closest first for each.
    public struct Likeness: Sendable, Equatable {
        public let of: String
        public let name: String

        public init(of: String, name: String) {
            self.of = of
            self.name = name
        }
    }

    public enum Reason: Sendable, Equatable {
        case yourArtist(String)
        case likeYourArtist(String)
    }

    public struct Pick: Sendable, Equatable {
        public let id: String
        public let reason: Reason
    }

    /// Call off the main actor for long lists: it folds every name.
    public static func rank(
        _ candidates: [Candidate],
        taste: [Taste],
        similar: [Likeness] = [],
        limit: Int = 24,
        perArtist: Int = 3,
        now: Date = .now,
        halfLifeDays: Double = 90
    ) -> [Pick] {
        let picks = TracksEngine.rankGetMore(
            candidates: candidates.map {
                TracksEngine.GetCandidate(id: $0.id, kind: $0.isAlbum ? .album : .song, artist: $0.artist, ownedShare: $0.ownedShare, isRequested: $0.isRequested)
            },
            taste: taste.map {
                TracksEngine.ArtistTaste(name: $0.name, plays: UInt32(clamping: $0.plays), lastPlayedMs: $0.lastPlayed.map(Self.milliseconds))
            },
            similar: similar.map { TracksEngine.SimilarArtistLink(of: $0.of, name: $0.name) },
            rules: TracksEngine.GetMoreRules(
                limit: UInt32(clamping: limit),
                perArtist: UInt32(clamping: perArtist),
                nowMs: milliseconds(now),
                halfLifeDays: halfLifeDays
            )
        )
        return picks.map { pick in
            switch pick.reason {
            case .yourArtist(let artist): Pick(id: pick.id, reason: .yourArtist(artist))
            case .likeYourArtist(let artist): Pick(id: pick.id, reason: .likeYourArtist(artist))
            }
        }
    }

    private static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1_000).rounded())
    }
}

/// How music you don't own can be got: from a server that finds or gets songs it doesn't
/// have, or from Lidarr. With neither, nothing offers to get anything.
public struct FetchAbility: Sendable, Equatable {
    /// A server you can reach that finds songs it doesn't have.
    public var serverFinds: Bool
    /// Lidarr, connected.
    public var lidarr: Bool

    public init(serverFinds: Bool = false, lidarr: Bool = false) {
        self.serverFinds = serverFinds
        self.lidarr = lidarr
    }

    public var canGet: Bool { TracksEngine.canGet(sources: sources) }

    /// From the server that found it, or else its album from Lidarr.
    public func route(forSongFoundOnServer found: Bool, hasAlbum: Bool) -> FetchRoute? {
        TracksEngine.songGetRoute(foundOnServer: found, hasAlbum: hasAlbum, sources: sources).map(FetchRoute.init)
    }

    /// From Lidarr, which files the whole album, or else song by song from a server that has it.
    public func route(forAlbumFoundOnServer found: Bool) -> FetchRoute? {
        TracksEngine.albumGetRoute(foundOnServer: found, sources: sources).map(FetchRoute.init)
    }

    /// Whether a search answer shows its server finds songs it doesn't have: most of it, and
    /// at least a few songs, from outside the server's synced library.
    public static func searchShowsFinds(returned: Int, outsideLibrary: Int) -> Bool {
        TracksEngine.searchShowsFinds(returned: UInt32(clamping: returned), outsideLibrary: UInt32(clamping: outsideLibrary))
    }

    private var sources: TracksEngine.FetchSources {
        TracksEngine.FetchSources(serverFinds: serverFinds, lidarr: lidarr)
    }
}

public enum FetchRoute: Sendable, Equatable {
    /// Ask the server to keep it, which has it fetch the file.
    case server
    /// Ask Lidarr for its album.
    case lidarr

    init(_ route: TracksEngine.GetRoute) {
        switch route {
        case .server: self = .server
        case .lidarr: self = .lidarr
        }
    }
}

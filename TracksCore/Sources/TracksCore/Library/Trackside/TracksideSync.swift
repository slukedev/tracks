import Foundation

/// A server artist's id there and their picture's cover id, filed by folded name.
public struct ServerArtistRef: Codable, Sendable, Hashable {
    public let id: String
    public let cover: String?

    public init(id: String, cover: String?) {
        self.id = id
        self.cover = cover
    }
}

/// Tracks' copy of a Trackside server's library: its songs as your own, its artists, and the
/// cursor the next sync continues from.
public struct TracksideMirror: Sendable, Equatable {
    /// The Tracks server entry the songs are filed under.
    public let serverID: String
    public private(set) var songs: [String: LocalTrack]
    /// By folded name, as artist pages and circles look them up.
    public private(set) var artists: [String: ServerArtistRef]
    /// Nil until a snapshot has completed.
    public var cursor: String?
    /// Each artist's folded name, so a delete, which only gives the id, finds them.
    private var artistNames: [String: String]

    public init(serverID: String, tracks: [LocalTrack] = [], artists: [String: ServerArtistRef] = [:], cursor: String? = nil) {
        self.serverID = serverID
        var songs: [String: LocalTrack] = [:]
        for track in tracks {
            if case .server(_, let songID) = track.origin { songs[songID] = track }
        }
        self.songs = songs
        self.artists = artists
        self.cursor = cursor
        artistNames = Dictionary(artists.map { ($0.value.id, $0.key) }, uniquingKeysWith: { first, _ in first })
    }

    /// The songs in a steady order, for saving and for comparing.
    public var tracks: [LocalTrack] {
        songs.values.sorted { $0.id < $1.id }
    }

    /// Applies one page, snapshot or delta. Within a page each id comes at most once, so
    /// the order things are applied in doesn't matter.
    public mutating func apply(_ page: TracksideSyncPage) {
        for song in page.songs ?? [] { upsert(song) }
        for artist in page.artists ?? [] { upsert(artist) }
        for song in page.upserts?.songs ?? [] { upsert(song) }
        for artist in page.upserts?.artists ?? [] { upsert(artist) }
        for id in page.deletes?.songs ?? [] { songs[id] = nil }
        for id in page.deletes?.artists ?? [] { removeArtist(id) }
    }

    private mutating func upsert(_ song: TracksideSong) {
        // A missing file can't be played, so it's left out until it comes back.
        songs[song.id] = song.missing == true ? nil : song.track(on: serverID)
    }

    private mutating func upsert(_ artist: TracksideArtist) {
        removeArtist(artist.id)
        let name = StatsCalculator.folded(artist.name)
        artists[name] = ServerArtistRef(id: artist.id, cover: artist.artworkID.flatMap { $0.isEmpty ? nil : $0 })
        artistNames[artist.id] = name
    }

    private mutating func removeArtist(_ id: String) {
        guard let name = artistNames.removeValue(forKey: id) else { return }
        if artists[name]?.id == id { artists[name] = nil }
    }
}

/// Where sync pages come from: a ``TracksideClient``, or a stand-in in tests.
public protocol TracksideSyncSource: Sendable {
    func sync(cursor: String?, limit: Int) async throws -> TracksideSyncPage
}

extension TracksideClient: TracksideSyncSource {}

public enum TracksideSync {
    /// Brings a mirror up to date: the changes since its cursor, or a whole snapshot when it
    /// has none or the server can't continue from it.
    ///
    /// The mirror passed in is never changed. When the sync fails partway it's still the one
    /// to keep, and the next sync starts again from its cursor; a snapshot after a reset
    /// replaces it only once the snapshot is complete.
    public static func run(_ mirror: TracksideMirror, from source: some TracksideSyncSource, pageSize: Int = 2_000) async throws -> TracksideMirror {
        guard let cursor = mirror.cursor else {
            return try await snapshot(for: mirror.serverID, from: source, pageSize: pageSize)
        }
        do {
            return try await changes(to: mirror, after: cursor, from: source, pageSize: pageSize)
        } catch TracksideError.syncReset {
            return try await snapshot(for: mirror.serverID, from: source, pageSize: pageSize)
        }
    }

    /// Far more than a library could need, so a server answering in circles can't keep a
    /// sync going forever.
    static let pageLimit = 10_000

    static func snapshot(for serverID: String, from source: some TracksideSyncSource, pageSize: Int) async throws -> TracksideMirror {
        var fresh = TracksideMirror(serverID: serverID)
        var cursor: String?
        for _ in 0..<pageLimit {
            let page = try await source.sync(cursor: cursor, limit: pageSize)
            guard page.mode == .snapshot else { throw TracksideError.notTrackside }
            fresh.apply(page)
            guard let next = page.nextCursor else { throw TracksideError.notTrackside }
            if page.complete == true {
                fresh.cursor = next
                return fresh
            }
            cursor = next
        }
        throw TracksideError.notTrackside
    }

    static func changes(to mirror: TracksideMirror, after cursor: String, from source: some TracksideSyncSource, pageSize: Int) async throws -> TracksideMirror {
        var updated = mirror
        var cursor = cursor
        for _ in 0..<pageLimit {
            let page = try await source.sync(cursor: cursor, limit: pageSize)
            // A server that answers a delta cursor with a snapshot has started over.
            guard page.mode == .delta else { throw TracksideError.syncReset }
            updated.apply(page)
            if let next = page.nextCursor { cursor = next }
            updated.cursor = cursor
            if page.hasMore != true { return updated }
        }
        throw TracksideError.notTrackside
    }
}

/// Where a mirror's sync cursor is kept between launches, beside the songs it belongs to.
public enum TracksideCursorFile {
    struct Saved: Codable {
        let cursor: String
    }

    public static func load(from file: URL) -> String? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return (try? JSONDecoder().decode(Saved.self, from: data))?.cursor
    }

    /// Nil removes it, so the next sync takes a snapshot.
    public static func save(_ cursor: String?, to file: URL) throws {
        guard let cursor else {
            try? FileManager.default.removeItem(at: file)
            return
        }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(Saved(cursor: cursor)).write(to: file, options: .atomic)
    }
}

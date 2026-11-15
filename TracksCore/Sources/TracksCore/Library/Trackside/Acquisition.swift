import Foundation

/// A song the server could get, from a search for one it doesn't have.
public struct TracksideCandidate: Codable, Sendable, Hashable {
    public enum Quality: String, TracksideOpenEnum {
        case lossless, high, standard, unknown
    }

    public enum Match: String, TracksideOpenEnum {
        case exact, close, unknown
    }

    /// Passed back to ask for this one. Stays the same for the same song across searches.
    public var token: String
    public var title: String
    public var artist: String
    public var album: String?
    public var durationMS: Int?
    public var quality: Quality?
    public var format: TracksideFormat?
    public var size: Int?
    /// Can be played while the server is still getting it.
    public var instant: Bool?
    public var match: Match?

    enum CodingKeys: String, CodingKey {
        case token, title, artist, album, quality, format, size, instant, match
        case durationMS = "duration_ms"
    }
}

/// A song asked of the server, and how getting it is going.
public struct TracksideAcquisition: Codable, Sendable, Hashable, Identifiable {
    public enum State: String, TracksideOpenEnum {
        case queued, searching, downloading, verifying, importing, done, failed, cancelled, unknown
    }

    public enum Failure: String, TracksideOpenEnum {
        case noMatch = "no_match"
        case allCandidatesFailed = "all_candidates_failed"
        case exhausted
        case providerUnavailable = "provider_unavailable"
        case insufficientSpace = "insufficient_space"
        case rejectedFile = "rejected_file"
        case quotaExceeded = "quota_exceeded"
        case disabled
        case unknown
    }

    public struct Progress: Codable, Sendable, Hashable {
        public var bytes: Int
        public var total: Int?

        /// From 0 to 1, where the size is known.
        public var fraction: Double? {
            guard let total, total > 0 else { return nil }
            return min(1, max(0, Double(bytes) / Double(total)))
        }
    }

    /// Where to play it from before it's in the library, both relative to the server.
    public struct Stream: Codable, Sendable, Hashable {
        public var hls: String?
        public var progressive: String?
    }

    public var id: String
    public var state: State
    public var title: String
    public var artist: String
    public var album: String?
    public var progress: Progress?
    public var quality: TracksideCandidate.Quality?
    /// The song's id in the library, final once it's `done`.
    public var songID: String?
    /// It was in the library already.
    public var existing: Bool?
    public var failure: Failure?
    public var stream: Stream?
    public var createdAt: Date?
    public var updatedAt: Date?

    /// The few steps a person sees, rather than every one the server goes through.
    public var phase: AcquisitionPhase {
        switch state {
        case .queued, .unknown: .waiting
        case .searching: .searching
        case .downloading: .getting(progress?.fraction)
        case .verifying, .importing: .adding
        case .done: .added
        case .failed: .couldNotGet(failure ?? .unknown)
        case .cancelled: .cancelled
        }
    }

    public var isFinished: Bool {
        [.done, .failed, .cancelled].contains(state)
    }

    enum CodingKeys: String, CodingKey {
        case id, state, title, artist, album, progress, quality, existing, failure, stream
        case songID = "song_id"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

public enum AcquisitionPhase: Sendable, Hashable {
    case waiting
    case searching
    /// With how far through it is, where that's known.
    case getting(Double?)
    case adding
    case added
    case couldNotGet(TracksideAcquisition.Failure)
    case cancelled
}

/// `POST /api/v1/acquisitions/search`, and the pages after it.
public struct TracksideAcquisitionSearch: Codable, Sendable, Hashable {
    public var searchID: String
    /// More candidates may come from ``TracksideAcquisitions/moreResults(of:)`` until this is true.
    public var complete: Bool
    /// The same song in the library already, so it can be played rather than got.
    public var inLibrary: [TracksideSong]?
    public var candidates: [TracksideCandidate]

    enum CodingKeys: String, CodingKey {
        case complete, candidates
        case searchID = "search_id"
        case inLibrary = "in_library"
    }
}

struct TracksideAcquisitionSearchRequest: Codable, Sendable {
    var query: String?
    var title: String?
    var artist: String?
    var album: String?
    var durationMS: Int?

    enum CodingKeys: String, CodingKey {
        case query, title, artist, album
        case durationMS = "duration_ms"
    }
}

/// What to ask the server to get.
public struct TracksideAcquisitionRequest: Codable, Sendable, Hashable {
    public var title: String
    public var artist: String
    public var album: String?
    public var durationMS: Int?
    /// A candidate's token, tried first.
    public var candidate: String?
    /// Live versions, remixes and the like are fine too.
    public var allowVersions: Bool
    /// Start playing as soon as bytes arrive, where the candidate allows it.
    public var instant: Bool

    public init(title: String, artist: String, album: String? = nil, duration: TimeInterval? = nil, candidate: String? = nil, allowVersions: Bool = false, instant: Bool = false) {
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMS = duration.map { Int(($0 * 1_000).rounded()) }
        self.candidate = candidate
        self.allowVersions = allowVersions
        self.instant = instant
    }

    enum CodingKeys: String, CodingKey {
        case title, artist, album, candidate, instant
        case durationMS = "duration_ms"
        case allowVersions = "allow_versions"
    }
}

/// A song the server doesn't have, to play without it being kept: a candidate a search
/// found, or a song by name.
public struct TracksideExternalPlayRequest: Codable, Sendable, Hashable {
    public var candidate: String?
    public var title: String?
    public var artist: String?
    public var album: String?
    public var durationMS: Int?

    public init(candidate: String) {
        self.candidate = candidate
    }

    public init(title: String, artist: String, album: String? = nil, duration: TimeInterval? = nil) {
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMS = duration.map { Int(($0 * 1_000).rounded()) }
    }

    enum CodingKeys: String, CodingKey {
        case candidate, title, artist, album
        case durationMS = "duration_ms"
    }
}

/// Where to play a song the server doesn't have, or the library's copy when it has it after all.
public struct TracksideExternalPlay: Codable, Sendable, Hashable {
    public var title: String
    public var artist: String
    public var durationMS: Int?
    public var songID: String?
    public var stream: TracksideAcquisition.Stream?

    enum CodingKeys: String, CodingKey {
        case title, artist, stream
        case durationMS = "duration_ms"
        case songID = "song_id"
    }
}

struct TracksideAcquisitionList: Codable, Sendable {
    let acquisitions: [TracksideAcquisition]
    let nextCursor: String?

    enum CodingKeys: String, CodingKey {
        case acquisitions
        case nextCursor = "next_cursor"
    }
}

/// Asking a Trackside server for songs it doesn't have yet.
///
/// Only some servers offer this, and only to some of their users: `/me` says so with
/// `features.acquisition`. Without it, ``isAvailable`` is false and nothing here asks the
/// server anything, so Tracks never shows a way in.
public struct TracksideAcquisitions: Sendable {
    public let isAvailable: Bool
    /// The server can play a song while it's still getting it.
    public let offersInstantPlay: Bool
    /// The server can play a song it doesn't have without keeping it.
    public let playsWithoutKeeping: Bool
    private let client: TracksideClient

    public init(client: TracksideClient, features: TracksideMe.Features?) {
        self.client = client
        isAvailable = features?.acquisition != nil
        offersInstantPlay = features?.acquisition?.instantPlay == true
        playsWithoutKeeping = isAvailable && features?.acquisition?.externalPlay == true
    }

    public init(client: TracksideClient, me: TracksideMe) {
        self.init(client: client, features: me.features)
    }

    public func search(_ query: String) async throws -> TracksideAcquisitionSearch {
        try await search(TracksideAcquisitionSearchRequest(query: query))
    }

    public func search(title: String, artist: String, album: String? = nil, duration: TimeInterval? = nil) async throws -> TracksideAcquisitionSearch {
        try await search(TracksideAcquisitionSearchRequest(title: title, artist: artist, album: album, durationMS: duration.map { Int(($0 * 1_000).rounded()) }))
    }

    private func search(_ request: TracksideAcquisitionSearchRequest) async throws -> TracksideAcquisitionSearch {
        try checkAvailable()
        return try await client.send("acquisitions/search", method: "POST", body: request)
    }

    /// The same search with what's been found since, while it isn't complete.
    public func moreResults(of search: TracksideAcquisitionSearch) async throws -> TracksideAcquisitionSearch {
        try checkAvailable()
        return try await client.get("acquisitions/searches/\(search.searchID)")
    }

    /// Asks the server to get a song. Already in the library, it answers `done` at once.
    /// - Parameter key: the same for a retry of the same request, so it's only asked once.
    public func request(_ request: TracksideAcquisitionRequest, key: UUID = UUID()) async throws -> TracksideAcquisition {
        try checkAvailable()
        return try await client.send("acquisitions", method: "POST", body: request, headers: ["Idempotency-Key": key.uuidString])
    }

    public func status(of id: String) async throws -> TracksideAcquisition {
        try checkAvailable()
        return try await client.get("acquisitions/\(id)")
    }

    /// This user's requests, still going or finished.
    public func list(finished: Bool, limit: Int = 100, cursor: String? = nil) async throws -> TracksidePage<TracksideAcquisition> {
        try checkAvailable()
        var query = ["state": finished ? "finished" : "active", "limit": String(limit)]
        query["cursor"] = cursor
        let list: TracksideAcquisitionList = try await client.get("acquisitions", query)
        return TracksidePage(items: list.acquisitions, nextCursor: list.nextCursor)
    }

    public func cancel(_ id: String) async throws {
        try checkAvailable()
        try await client.perform("acquisitions/\(id)", method: "DELETE")
    }

    /// Where to play a song still being got, once the server has started sending it.
    public func streamURL(for acquisition: TracksideAcquisition) -> URL? {
        guard isAvailable, let path = acquisition.stream?.hls ?? acquisition.stream?.progressive else { return nil }
        return client.mediaURL(path)
    }

    /// Plays a song the server doesn't have without it being kept: nothing is asked for, and
    /// nothing reaches the library.
    public func play(_ request: TracksideExternalPlayRequest) async throws -> TracksideExternalPlay {
        guard playsWithoutKeeping else { throw TracksideError.featureUnavailable }
        return try await client.send("external/play", method: "POST", body: request)
    }

    /// Where to play it from, with the media token.
    public func streamURL(for play: TracksideExternalPlay) -> URL? {
        guard playsWithoutKeeping, let path = play.stream?.hls ?? play.stream?.progressive else { return nil }
        return client.mediaURL(path)
    }

    private func checkAvailable() throws {
        guard isAvailable else { throw TracksideError.featureUnavailable }
    }
}

/// The latest word on each request, from answers and events arriving in any order.
public struct AcquisitionStatuses: Sendable, Equatable {
    public private(set) var byID: [String: TracksideAcquisition] = [:]

    public init() {}

    /// Keeps the newer of what's known and what arrived: an event that was overtaken by a
    /// later answer doesn't put a request back a step.
    public mutating func update(_ acquisition: TracksideAcquisition) {
        if let known = byID[acquisition.id], let knownAt = known.updatedAt, let arrivedAt = acquisition.updatedAt, arrivedAt < knownAt { return }
        byID[acquisition.id] = acquisition
    }

    public subscript(id: String) -> TracksideAcquisition? { byID[id] }
}

/// A song a Trackside server doesn't have, as the player queues it: under the server, with an
/// id no library song has, which says how to ask for it to be played without keeping it.
public enum UnownedSong {
    static let prefix = "unowned:"

    public static func track(_ candidate: TracksideCandidate, on serverID: String) -> LocalTrack {
        track(TracksideExternalPlayRequest(candidate: candidate.token), title: candidate.title, artist: candidate.artist, album: candidate.album, duration: candidate.durationMS.map { TimeInterval($0) / 1_000 }, on: serverID)
    }

    public static func track(title: String, artist: String, album: String? = nil, duration: TimeInterval? = nil, on serverID: String) -> LocalTrack {
        track(TracksideExternalPlayRequest(title: title, artist: artist, album: album, duration: duration), title: title, artist: artist, album: album, duration: duration, on: serverID)
    }

    private static func track(_ request: TracksideExternalPlayRequest, title: String, artist: String, album: String?, duration: TimeInterval?, on serverID: String) -> LocalTrack {
        let encoded = (try? JSONEncoder().encode(request)).map { $0.base64EncodedString() } ?? ""
        return LocalTrack(origin: .server(serverID: serverID, songID: prefix + encoded), title: title, artist: artist, album: album, duration: duration)
    }

    /// What to ask the server, for a song it doesn't have. Nil for one it does.
    public static func request(for track: LocalTrack) -> TracksideExternalPlayRequest? {
        guard case .server(_, let songID) = track.origin, songID.hasPrefix(prefix),
              let data = Data(base64Encoded: String(songID.dropFirst(prefix.count)))
        else { return nil }
        return try? JSONDecoder().decode(TracksideExternalPlayRequest.self, from: data)
    }

    public static func isUnowned(_ track: LocalTrack) -> Bool {
        guard case .server(_, let songID) = track.origin else { return false }
        return songID.hasPrefix(prefix)
    }

    /// How Get asks a server for a song it found. A song a Trackside server doesn't have is
    /// requested, where the server gets songs for you, and can't be got where it doesn't; any
    /// other is kept.
    public static func getRoute(for track: LocalTrack, serverGetsSongs: Bool) -> GetRoute {
        guard isUnowned(track) else { return .keep }
        guard serverGetsSongs, let asked = request(for: track) else { return .unavailable }
        return .request(asked)
    }

    public enum GetRoute: Sendable, Equatable {
        case keep
        case request(TracksideExternalPlayRequest)
        case unavailable
    }

    /// Where the player gets it from: the server's stream of it, kept nowhere, or the
    /// library's copy when the server has it after all. Never a request to get it.
    public static func stream(_ track: LocalTrack, from acquisitions: TracksideAcquisitions, client: TracksideClient, maxBitRate: Int?, network: MusicNetwork) async throws -> MusicServerStream? {
        guard let request = request(for: track) else { return nil }
        let play = try await acquisitions.play(request)
        if let songID = play.songID {
            return try await client.stream(songID: songID, maxBitRate: maxBitRate, network: network)
        }
        guard let url = acquisitions.streamURL(for: play) else { return nil }
        return MusicServerStream(url: url, delivered: nil, isTranscoded: true)
    }
}

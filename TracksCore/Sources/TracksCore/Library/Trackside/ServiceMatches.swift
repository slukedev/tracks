import Foundation

// Finding a music service's own copy of a song, so Play From can play it from the service when
// the service comes first. One search per song, or one batch match per queue where the server
// has it, with the answers kept so a song isn't asked for again each time it plays.

extension TracksideCatalogService {
    /// The service's kind, as Play From keeps it: its logo key ("spotify"), or its id where
    /// it has none.
    public var placeKind: String {
        if let logoKey, !logoKey.trimmingCharacters(in: .whitespaces).isEmpty { return logoKey }
        return id
    }
}

/// A song to find on a service: what it's called, and the identity Tracks keeps it by.
public struct WantedSong: Sendable, Hashable {
    public var title: String
    public var artist: String
    public var album: String?
    public var duration: TimeInterval?
    /// ``SongIdentity/key(title:artistName:)``, as the server's `song_key` makes it too.
    public var identity: String

    public init(title: String, artist: String, album: String? = nil, duration: TimeInterval? = nil) {
        self.title = title
        self.artist = artist
        self.album = album
        self.duration = duration
        self.identity = SongIdentity.key(title: title, artistName: artist)
    }

    /// Whether a service's track is this song: the same identity, and playable there.
    public func isMatched(by track: TracksideCatalogTrack, on serviceID: String) -> Bool {
        guard track.serviceID == serviceID, track.isPlayable else { return false }
        return (track.songKey ?? SongIdentity.key(title: track.title, artistName: track.artistLine)) == identity
    }
}

/// `POST /catalog/match`: many songs matched to the services' in one request.
public struct TracksideCatalogMatchRequest: Codable, Sendable, Hashable {
    public struct Item: Codable, Sendable, Hashable {
        /// The caller's own, unique in the request.
        public var id: String
        public var title: String
        public var artist: String
        public var album: String?
        public var durationMS: Int?
        public var isrc: String?

        public init(id: String, title: String, artist: String, album: String? = nil, durationMS: Int? = nil, isrc: String? = nil) {
            self.id = id
            self.title = title
            self.artist = artist
            self.album = album
            self.durationMS = durationMS
            self.isrc = isrc
        }

        enum CodingKeys: String, CodingKey {
            case id, title, artist, album, isrc
            case durationMS = "duration_ms"
        }
    }

    /// At most this many in one request.
    public static let itemLimit = 50

    public var items: [Item]
    public var services: [String]?

    public init(items: [Item], services: [String]? = nil) {
        self.items = items
        self.services = services
    }
}

/// What `POST /catalog/match` found for each song asked for.
public struct TracksideCatalogMatch: Decodable, Sendable, Hashable {
    public struct Match: Decodable, Sendable, Hashable {
        public enum State: String, TracksideOpenEnum {
            case found, failed, deferred, unknown
            /// The service has no such song.
            case notFound = "none"
            case timedOut = "timed_out"
            case signedOut = "signed_out"
        }

        public var serviceID: String
        public var state: State
        public var track: TracksideCatalogTrack?

        enum CodingKeys: String, CodingKey {
            case state, track
            case serviceID = "service_id"
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            serviceID = ((try? container.decodeIfPresent(String.self, forKey: .serviceID)) ?? "").lowercased()
            state = (try? container.decodeIfPresent(State.self, forKey: .state)) ?? .unknown
            track = try? container.decodeIfPresent(TracksideCatalogTrack.self, forKey: .track)
        }
    }

    public struct SongResult: Decodable, Sendable, Hashable {
        public var id: String
        public var matches: [Match]

        enum CodingKeys: String, CodingKey {
            case id, matches
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decode(String.self, forKey: .id)
            matches = (try? container.decodeIfPresent([TracksideLenientElement<Match>].self, forKey: .matches))?.compactMap(\.value) ?? []
        }
    }

    public var isComplete: Bool
    public var results: [SongResult]
    /// Songs left `deferred` can be asked for again after this long.
    public var retryAfter: TimeInterval?

    enum CodingKeys: String, CodingKey {
        case results
        case isComplete = "complete"
        case retryAfter = "retry_after_s"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        isComplete = (try? container.decodeIfPresent(Bool.self, forKey: .isComplete)) ?? true
        results = (try? container.decodeIfPresent([TracksideLenientElement<SongResult>].self, forKey: .results))?.compactMap(\.value) ?? []
        retryAfter = (try? container.decodeIfPresent(Double.self, forKey: .retryAfter))
    }
}

extension TracksideClient {
    /// Matches songs to the services' own, all at once. Counts as one search.
    public func matchCatalog(_ request: TracksideCatalogMatchRequest) async throws -> TracksideCatalogMatch {
        try await send("catalog/match", method: "POST", body: request)
    }
}

/// The service tracks found for songs, kept in memory and in a small file: refs and titles
/// only, never audio. A song found is kept a week, one the service didn't have a day; a
/// search that failed isn't kept, so the service is asked again next time.
public actor ServiceMatches {
    /// A kept answer: `track` is nil when the service has no such song.
    public struct Answer: Codable, Sendable, Hashable {
        public var track: TracksideCatalogTrack?
        public var at: Date
    }

    public static let foundKept: TimeInterval = 7 * 24 * 60 * 60
    public static let missingKept: TimeInterval = 24 * 60 * 60
    static let limit = 2_000

    private let file: URL?
    private let now: @Sendable () -> Date
    private var answers: [String: Answer] = [:]
    private var isLoaded: Bool

    public init(file: URL?, now: @escaping @Sendable () -> Date = { .now }) {
        self.file = file
        self.now = now
        isLoaded = file == nil
    }

    /// The answer kept from before, without asking: nil when there's none, or it's too old.
    public func known(_ wanted: WantedSong, serverID: String, serviceID: String) -> Answer? {
        load()
        guard let answer = answers[Self.key(wanted, serverID: serverID, serviceID: serviceID)] else { return nil }
        let age = now().timeIntervalSince(answer.at)
        return age < (answer.track == nil ? Self.missingKept : Self.foundKept) ? answer : nil
    }

    /// Keeps what a search or match found for songs: each one's track, or nil for none.
    public func keep(_ found: [(wanted: WantedSong, track: TracksideCatalogTrack?)], serverID: String, serviceID: String) {
        guard !found.isEmpty else { return }
        load()
        let at = now()
        for (wanted, track) in found {
            answers[Self.key(wanted, serverID: serverID, serviceID: serviceID)] = Answer(track: track, at: at)
        }
        if answers.count > Self.limit {
            // The oldest go first.
            for (key, _) in answers.sorted(by: { $0.value.at < $1.value.at }).prefix(answers.count - Self.limit) {
                answers[key] = nil
            }
        }
        save()
    }

    private static func key(_ wanted: WantedSong, serverID: String, serviceID: String) -> String {
        "\(serverID) \(serviceID) \(wanted.identity)"
    }

    private func load() {
        guard !isLoaded else { return }
        isLoaded = true
        guard let file, let data = try? Data(contentsOf: file),
              let read = try? JSONDecoder().decode([String: Answer].self, from: data)
        else { return }
        let at = now()
        for (key, answer) in read where at.timeIntervalSince(answer.at) < (answer.track == nil ? Self.missingKept : Self.foundKept) {
            if answers[key] == nil { answers[key] = answer }
        }
    }

    private func save() {
        guard let file, let data = try? JSONEncoder().encode(answers) else { return }
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: file, options: .atomic)
    }
}

/// Looks songs up on one service of one server, for Play From.
public enum ServiceLookup {
    /// How long one search is given before its song is passed over for now.
    public static let searchTimeout: Duration = .milliseconds(2_500)
    /// The most songs looked up one search each, for a server without batch matching: the
    /// server allows 30 searches a minute.
    public static let searchLimit = 12
    /// The most batch requests for one queue, each counting as one search.
    public static let batchLimit = 2

    /// What asking found for one song.
    enum Looked: Sendable {
        case found(TracksideCatalogTrack)
        /// The service answered: it has no such song.
        case missing
        /// No answer: failed, too slow, or not asked. Not kept.
        case unknown

        var track: TracksideCatalogTrack? {
            if case .found(let track) = self { return track }
            return nil
        }
    }

    /// Each song's track on the service, in the order given, nil where it has none or it
    /// wasn't found in time. Kept answers are used first; the first song is asked for before
    /// the rest, and when the service doesn't have it nothing more is asked.
    /// - Parameter batches: the server matches many songs in one request.
    @concurrent
    public static func find(
        _ songs: [WantedSong],
        serverID: String,
        serviceID: String,
        client: any CatalogService,
        matches: ServiceMatches,
        batches: Bool
    ) async -> [TracksideCatalogTrack?] {
        var found = [TracksideCatalogTrack?](repeating: nil, count: songs.count)
        var unknown: [Int] = []
        var firstOf: [String: Int] = [:]
        var repeats: [Int: Int] = [:]
        for (index, song) in songs.enumerated() {
            if let first = firstOf[song.identity] {
                repeats[index] = first
                continue
            }
            firstOf[song.identity] = index
            if let kept = await matches.known(song, serverID: serverID, serviceID: serviceID) {
                found[index] = kept.track
            } else {
                unknown.append(index)
            }
        }
        // The first song decides whether the rest are worth asking for.
        let needsFirst = unknown.first == 0
        var asked: [(index: Int, looked: Looked)] = []
        if batches {
            let limit = batchLimit * TracksideCatalogMatchRequest.itemLimit
            for start in stride(from: 0, to: min(unknown.count, limit), by: TracksideCatalogMatchRequest.itemLimit) {
                let indexes = Array(unknown[start..<min(unknown.count, start + TracksideCatalogMatchRequest.itemLimit)])
                guard let looked = await match(indexes.map { songs[$0] }, serviceID: serviceID, client: client) else { break }
                asked += zip(indexes, looked).map { (index: $0, looked: $1) }
                if start == 0, needsFirst, looked.first?.track == nil { break }
            }
        } else if let first = unknown.first {
            let head = await search(songs[first], serviceID: serviceID, client: client)
            asked.append((index: first, looked: head))
            if !needsFirst || head.track != nil {
                let rest = Array(unknown.dropFirst().prefix(searchLimit - 1))
                let looked = await searchAll(rest.map { songs[$0] }, serviceID: serviceID, client: client)
                asked += zip(rest, looked).map { (index: $0, looked: $1) }
            }
        }
        var kept: [(wanted: WantedSong, track: TracksideCatalogTrack?)] = []
        for (index, looked) in asked {
            switch looked {
            case .found(let track):
                found[index] = track
                kept.append((wanted: songs[index], track: track))
            case .missing:
                kept.append((wanted: songs[index], track: nil))
            case .unknown:
                break
            }
        }
        await matches.keep(kept, serverID: serverID, serviceID: serviceID)
        for (index, first) in repeats { found[index] = found[first] }
        return found
    }

    /// One batch, a song's answer each. Nil when the request failed.
    private static func match(_ songs: [WantedSong], serviceID: String, client: any CatalogService) async -> [Looked]? {
        let items = songs.enumerated().map { offset, song in
            TracksideCatalogMatchRequest.Item(id: String(offset), title: song.title, artist: song.artist, album: song.album, durationMS: song.duration.map { Int($0 * 1_000) })
        }
        guard let answer = try? await client.matchCatalog(TracksideCatalogMatchRequest(items: items, services: [serviceID])) else { return nil }
        var looked = [Looked](repeating: .unknown, count: songs.count)
        for result in answer.results {
            guard let offset = Int(result.id), songs.indices.contains(offset),
                  let match = result.matches.first(where: { $0.serviceID == serviceID })
            else { continue }
            switch match.state {
            case .found:
                if let track = match.track, songs[offset].isMatched(by: track, on: serviceID) {
                    looked[offset] = .found(track)
                } else {
                    looked[offset] = .missing
                }
            case .notFound:
                looked[offset] = .missing
            case .failed, .deferred, .unknown, .timedOut, .signedOut:
                break
            }
        }
        return looked
    }

    /// Searches four at a time, each given ``searchTimeout``.
    private static func searchAll(_ songs: [WantedSong], serviceID: String, client: any CatalogService) async -> [Looked] {
        await withTaskGroup(of: (Int, Looked).self) { group in
            var looked = [Looked](repeating: .unknown, count: songs.count)
            var next = 0
            while next < min(4, songs.count) {
                let offset = next
                group.addTask { (offset, await search(songs[offset], serviceID: serviceID, client: client)) }
                next += 1
            }
            while let (offset, answer) = await group.next() {
                looked[offset] = answer
                if next < songs.count {
                    let offset = next
                    group.addTask { (offset, await search(songs[offset], serviceID: serviceID, client: client)) }
                    next += 1
                }
            }
            return looked
        }
    }

    /// One song's search, given ``searchTimeout``.
    private static func search(_ song: WantedSong, serviceID: String, client: any CatalogService) async -> Looked {
        await withTaskGroup(of: Looked.self) { group in
            group.addTask {
                guard let found = try? await client.searchCatalog("\(song.title) \(song.artist)", types: [.track], services: [serviceID], limit: 8) else { return .unknown }
                return found.tracks.first { song.isMatched(by: $0, on: serviceID) }.map(Looked.found) ?? .missing
            }
            group.addTask {
                try? await Task.sleep(for: searchTimeout)
                return .unknown
            }
            let first = await group.next() ?? .unknown
            group.cancelAll()
            return first
        }
    }
}

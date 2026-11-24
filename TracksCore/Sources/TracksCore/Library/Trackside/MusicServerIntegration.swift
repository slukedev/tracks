import Foundation
import CryptoKit

// What the app needs to tell a Trackside entry from a Subsonic one and treat each the way it
// expects, kept here so it can be tested without the app.

// MARK: - Which kind of server

public enum MusicServerDetection {
    public enum Result: Sendable, Equatable {
        case trackside(TracksideCapabilities)
        /// Anything else that answered, which is asked as a Subsonic server.
        case subsonic
    }

    /// What's at an address: Trackside says so in its capabilities, and every other answer,
    /// a 404 included, means a Subsonic server is tried there as before.
    /// - Throws: when nothing could be reached there.
    public static func detect(at base: URL, transport: any TracksideTransport = URLSessionTracksideTransport()) async throws -> Result {
        if let capabilities = try await TracksideClient.detect(at: base, transport: transport) {
            return .trackside(capabilities)
        }
        return .subsonic
    }
}

// MARK: - Where secrets are kept

/// The keychain accounts an entry's secrets are kept under, in the service Tracks has always
/// used for server passwords.
public enum ServerCredential {
    /// The entry's own id, for a Subsonic password and a Trackside device key alike: moving an
    /// entry from one kind to the other replaces its secret rather than leaving one behind.
    public static func account(for server: SubsonicServer) -> String {
        server.id
    }

    /// Trackside's media token, kept so covers and songs load before the server has been
    /// asked for a new one.
    public static func mediaTokenAccount(for server: SubsonicServer) -> String {
        server.id + ".media-token"
    }
}

// MARK: - Cover addresses

/// How a server's cover addresses are kept on disk: by a name that leaves out what signs in,
/// and for Trackside, which address the server was reached at, so a cover fetched at home is
/// still there away from it.
public enum ServerCoverAddress {
    /// A Subsonic `getCoverArt` or a Trackside `art` address.
    public static func isServerCover(_ url: URL) -> Bool {
        isSubsonicCover(url) || isTracksideArt(url)
    }

    public static func isSubsonicCover(_ url: URL) -> Bool {
        url.path(percentEncoded: false).hasSuffix("/rest/getCoverArt")
    }

    public static func isTracksideArt(_ url: URL) -> Bool {
        url.path(percentEncoded: false).contains("/api/v1/art/")
    }

    /// Query items that sign a request rather than say what's asked for.
    static let signing: Set<String> = ["u", "t", "s", "p", "mt", "apiKey"]

    /// The file name a cover is kept under: the address without its sign-in, hashed. For
    /// Trackside the address is also cut down to the cover's own path, since artwork ids are
    /// the same at every address the server has.
    public static func savedName(for url: URL) -> String {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let kept = components?.queryItems?
            .filter { !signing.contains($0.name) }
            .sorted { $0.name < $1.name }
        components?.queryItems = kept
        let stable: String
        if isTracksideArt(url), let components, let range = components.percentEncodedPath.range(of: "/api/v1/art/") {
            let query = components.percentEncodedQuery.map { "?" + $0 } ?? ""
            stable = "trackside:" + components.percentEncodedPath[range.lowerBound...] + query
        } else {
            stable = components?.url?.absoluteString ?? url.path()
        }
        return SHA256.hash(data: Data(stable.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Moving an entry to Trackside

/// Matching the songs kept from an entry's old server, in downloads and playlists, to the
/// same songs on the Trackside server that took its place. Song ids differ between servers;
/// what Tracks knows of each song doesn't.
public enum ServerSongMatching {
    public struct Result: Sendable, Equatable {
        /// The new copy of each old song, by the old one's id.
        public var matched: [String: LocalTrack] = [:]
        /// Old songs with no copy that's surely the same, which stay as they are.
        public var unmatched: [LocalTrack] = []
    }

    /// How far apart two copies' lengths can be and still be one recording.
    public static let durationTolerance: TimeInterval = 2

    /// Each old song from `serverID` that isn't on the server now, matched by its identity
    /// (Trackside's `song_key`), its album and its length. Only a match that leaves no doubt
    /// is taken: the same song on the same album, or the only copy of the song there is at
    /// that length.
    public static func match(_ old: [LocalTrack], against mirror: [LocalTrack], serverID: String) -> Result {
        let present = Set(mirror.map(\.id))
        let byIdentity = Dictionary(grouping: mirror, by: \.identity)
        var result = Result()
        var seen = Set<String>()
        for track in old {
            guard case .server(let owner, _) = track.origin, owner == serverID,
                  !present.contains(track.id), seen.insert(track.id).inserted
            else { continue }
            let candidates = byIdentity[track.identity] ?? []
            if let found = best(for: track, in: candidates) {
                result.matched[track.id] = found
            } else {
                result.unmatched.append(track)
            }
        }
        return result
    }

    private static func best(for track: LocalTrack, in candidates: [LocalTrack]) -> LocalTrack? {
        let sameLength = candidates.filter { closeInLength(track, $0) }
        let sameAlbum = candidates.filter { $0.albumKey == track.albumKey }
        if let both = sameAlbum.first(where: { closeInLength(track, $0) }) { return both }
        if sameAlbum.count == 1, track.duration == nil || sameAlbum[0].duration == nil { return sameAlbum[0] }
        if candidates.count == 1, sameLength.count == 1 { return sameLength[0] }
        return nil
    }

    private static func closeInLength(_ a: LocalTrack, _ b: LocalTrack) -> Bool {
        guard let first = a.duration, let second = b.duration else { return false }
        return abs(first - second) <= durationTolerance
    }
}

// MARK: - Asking for songs the server doesn't have

/// What the "get this song" part of search shows for one Trackside server: songs it could
/// get, and how each one asked for is going.
///
/// Without `features.acquisition` in the server's `/me` it shows nothing, whatever it's
/// given, so nothing in Tracks hints the feature exists.
public struct SongRequestBoard: Sendable, Equatable {
    public let isOffered: Bool
    public private(set) var query = ""
    public private(set) var candidates: [TracksideCandidate] = []
    /// Requests made, by the candidate they were for.
    public private(set) var requests: [String: String] = [:]
    public private(set) var statuses = AcquisitionStatuses()

    public init(features: TracksideMe.Features?) {
        isOffered = features?.acquisition != nil
    }

    /// The candidates a search found, without the ones that turned out to be in the library.
    public mutating func show(_ search: TracksideAcquisitionSearch, for query: String) {
        guard isOffered else { return }
        self.query = query
        candidates = Self.offered(search)
    }

    /// A search's candidates less the songs it found in the library.
    public static func offered(_ search: TracksideAcquisitionSearch) -> [TracksideCandidate] {
        let owned = Set((search.inLibrary ?? []).compactMap(\.songKey))
        return search.candidates.filter { candidate in
            !owned.contains(SongIdentity.key(title: candidate.title, artistName: candidate.artist))
        }
    }

    public mutating func clear() {
        query = ""
        candidates = []
    }

    public mutating func requested(_ acquisition: TracksideAcquisition, for token: String) {
        guard isOffered else { return }
        requests[token] = acquisition.id
        statuses.update(acquisition)
    }

    /// An answer or an event about a request.
    public mutating func update(_ acquisition: TracksideAcquisition) {
        guard isOffered else { return }
        statuses.update(acquisition)
    }

    /// How a request is going, by its id.
    public func status(of id: String) -> TracksideAcquisition? {
        statuses[id]
    }

    /// How a candidate's request is going, or nil before it's asked for.
    public func request(for token: String) -> TracksideAcquisition? {
        requests[token].flatMap { statuses[$0] }
    }

    /// The candidates to show: none unless the server offers them.
    public var shown: [TracksideCandidate] {
        isOffered ? candidates : []
    }
}

/// How a request reads in a row: a word or two, as Music says "Downloading" or "Waiting".
public enum AcquisitionWords {
    public static func status(_ phase: AcquisitionPhase) -> String {
        switch phase {
        case .waiting: String(localized: "Waiting…")
        case .searching: String(localized: "Looking for It…")
        case .getting(let fraction):
            if let fraction {
                String(localized: "Getting It · \(fraction.formatted(.percent.precision(.fractionLength(0))))")
            } else {
                String(localized: "Getting It…")
            }
        case .adding: String(localized: "Adding…")
        case .added: String(localized: "Added")
        case .couldNotGet(let failure): reason(failure)
        case .cancelled: String(localized: "Cancelled")
        }
    }

    /// Why a song couldn't be got, in plain words.
    public static func reason(_ failure: TracksideAcquisition.Failure) -> String {
        switch failure {
        case .noMatch, .allCandidatesFailed, .exhausted, .rejectedFile:
            String(localized: "Couldn’t Find a Good Copy")
        case .insufficientSpace:
            String(localized: "Your Server Is Out of Space")
        case .quotaExceeded:
            String(localized: "Limit Reached for Today")
        case .providerUnavailable, .disabled, .unknown:
            String(localized: "Couldn’t Get It")
        }
    }
}

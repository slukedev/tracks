import Foundation
import CryptoKit

/// What a server entry talks to: a server that speaks the Subsonic API, or Trackside, which
/// has its own.
public enum ServerKind: String, Codable, Sendable, Hashable {
    case subsonic
    case trackside
}

/// A music server you've connected. Most speak the Subsonic API, the standard self-hosted
/// music servers share: Navidrome, Gonic, Airsonic, Ampache, LMS and others. OpenSubsonic,
/// its open extension, is spoken by most of them today. Trackside speaks its own.
///
/// The entry's ``id`` is what its songs are filed under (`server:<id>:<song>`), so it stays
/// the same when the entry moves to another kind of server.
public struct SubsonicServer: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public var name: String
    /// Where requests go. For Trackside, the address that answered last, used until the next
    /// race picks one of ``candidateURLs``.
    public var url: URL
    /// Empty when the server is signed in to with an OpenSubsonic API key instead.
    public var username: String
    /// Kept so entries round-trip with older builds, which signed cover addresses with it.
    /// Nothing is signed with it now: every request, covers included, gets a salt of its own.
    public var salt: String
    public var kind: ServerKind
    /// How a Subsonic server is signed in to. Nil reads as a token made from the password.
    public var authMethod: SubsonicAuthMethod?
    /// Trackside's own id for itself, the same at every address it has.
    public var serverUUID: String?
    /// Every address Trackside can be reached at, as it last said.
    public var candidateURLs: [TracksideServerURL]?
    /// This device as Trackside knows it.
    public var deviceID: String?
    /// The name Trackside gave this device when it was paired.
    public var deviceName: String?

    public init(
        id: String = UUID().uuidString,
        name: String,
        url: URL,
        username: String,
        salt: String = SubsonicServer.makeSalt(),
        kind: ServerKind = .subsonic,
        serverUUID: String? = nil,
        candidateURLs: [TracksideServerURL]? = nil,
        deviceID: String? = nil,
        deviceName: String? = nil,
        authMethod: SubsonicAuthMethod? = nil,
        urlsETag: String? = nil
    ) {
        self.id = id
        self.name = name
        self.url = url
        self.username = username
        self.salt = salt
        self.kind = kind
        self.serverUUID = serverUUID
        self.candidateURLs = candidateURLs
        self.deviceID = deviceID
        self.deviceName = deviceName
        self.authMethod = authMethod
        self.urlsETag = urlsETag
    }

    /// For Trackside: the ETag of the address list last read from `/server/urls`, so the
    /// next ask is answered with a 304 when nothing changed.
    public var urlsETag: String?

    /// For a Subsonic server: the networks it was added or used on at home
    /// (``NetworkIdentity``), the only ones where its plain HTTP address gets the
    /// credentials while it has an https one (``SubsonicCredentialRule``).
    public var homeNetworks: [String]?

    enum CodingKeys: String, CodingKey {
        case id, name, url, username, salt, kind, serverUUID, candidateURLs, deviceID, deviceName, authMethod, urlsETag, homeNetworks
    }

    /// Entries saved before there was more than one kind are Subsonic servers.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        url = try container.decode(URL.self, forKey: .url)
        username = try container.decode(String.self, forKey: .username)
        salt = try container.decode(String.self, forKey: .salt)
        kind = try container.decodeIfPresent(ServerKind.self, forKey: .kind) ?? .subsonic
        serverUUID = try container.decodeIfPresent(String.self, forKey: .serverUUID)
        candidateURLs = try container.decodeIfPresent([TracksideServerURL].self, forKey: .candidateURLs)
        deviceID = try container.decodeIfPresent(String.self, forKey: .deviceID)
        deviceName = try container.decodeIfPresent(String.self, forKey: .deviceName)
        authMethod = try? container.decodeIfPresent(SubsonicAuthMethod.self, forKey: .authMethod)
        urlsETag = try container.decodeIfPresent(String.self, forKey: .urlsETag)
        homeNetworks = try? container.decodeIfPresent([String].self, forKey: .homeNetworks)
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(url, forKey: .url)
        try container.encode(username, forKey: .username)
        try container.encode(salt, forKey: .salt)
        try container.encode(kind, forKey: .kind)
        try container.encodeIfPresent(serverUUID, forKey: .serverUUID)
        try container.encodeIfPresent(candidateURLs, forKey: .candidateURLs)
        try container.encodeIfPresent(deviceID, forKey: .deviceID)
        try container.encodeIfPresent(deviceName, forKey: .deviceName)
        try container.encodeIfPresent(authMethod, forKey: .authMethod)
        try container.encodeIfPresent(urlsETag, forKey: .urlsETag)
        try container.encodeIfPresent(homeNetworks, forKey: .homeNetworks)
    }

    public var isTrackside: Bool { kind == .trackside }

    /// Signed in to with an OpenSubsonic API key rather than a username and password.
    public var usesAPIKey: Bool { authMethod == .apiKey }

    /// Every address the server can be reached at: the ones it was given, or its one.
    public var addresses: [TracksideServerURL] {
        let known = candidateURLs.flatMap { $0.isEmpty ? nil : $0 } ?? [TracksideServerURL(url: url, kind: NearbyAddress.isNearby(url) ? .lan : .public)]
        return TracksideServerURLs.trusted(known)
    }

    public static func makeSalt() -> String {
        String((0..<12).map { _ in "abcdefghijklmnopqrstuvwxyz0123456789".randomElement()! })
    }

    /// An address as someone types it: "192.168.1.20:4533" gains http, "music.example.com"
    /// gains https, a trailing slash goes. See ``ServerAddress``.
    public static func address(from text: String) -> URL? {
        ServerAddress.url(from: text)
    }
}

/// How a Subsonic server is signed in to.
public enum SubsonicAuthMethod: String, Codable, Sendable, Hashable {
    /// `t` and `s`: md5 of the password and a salt made for each request.
    case token
    /// OpenSubsonic's `apiKey`, where the server offers `apiKeyAuthentication`. Revocable on
    /// the server, and the password never leaves the person's head.
    case apiKey
}

/// Why a server said no.
public enum SubsonicError: Error, Equatable, Sendable {
    /// Username, password or API key wrong (Subsonic codes 40, 41 and 44).
    case wrongCredentials
    /// The server doesn't take this way of signing in (code 42), such as an API key on a
    /// server without OpenSubsonic's `apiKeyAuthentication`, or a password on one that wants
    /// a key.
    case authMethodUnsupported
    /// A Wi-Fi network's sign-in page answered instead of the server.
    case captivePortal
    /// The server is too old for what was asked (codes 20 and 30).
    case incompatible
    /// Not found (code 70).
    case notFound
    case server(code: Int, message: String)
    /// Not a Subsonic answer at all: the wrong address, most likely.
    case notSubsonic
    case http(status: Int)
}

/// Sends a request and hands back the body. URLSession in the app; a stub in the tests.
public protocol SubsonicTransport: Sendable {
    func data(for url: URL) async throws -> (Data, Int)
    /// The same, giving up after `timeout`, for probing an address.
    func data(for url: URL, timeout: TimeInterval) async throws -> (Data, Int)
}

extension SubsonicTransport {
    public func data(for url: URL, timeout: TimeInterval) async throws -> (Data, Int) {
        try await data(for: url)
    }
}

public struct URLSessionTransport: SubsonicTransport {
    public init() {}

    public func data(for url: URL) async throws -> (Data, Int) {
        try await data(for: url, timeout: 15)
    }

    /// A redirect to anywhere but the server itself isn't followed: what signs the request
    /// stays with the server, and a Wi-Fi network sending every request to its sign-in page
    /// is told apart from a server that's down.
    public func data(for url: URL, timeout: TimeInterval) async throws -> (Data, Int) {
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        let (data, response) = try await ServerSessions.api.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 200
        if (300..<400).contains(status) { throw SubsonicError.captivePortal }
        return (data, status)
    }
}

/// Talks to one Subsonic server.
public struct SubsonicClient: Sendable {
    public let server: SubsonicServer
    /// The password, kept to sign each request with a fresh salt, so only its salted hash
    /// leaves the device. Or the API key, for a server signed in to with one.
    private let password: String
    private let transport: any SubsonicTransport
    /// The server's addresses and the one in use. Nil asks ``SubsonicServer/url`` alone.
    public let endpoints: SubsonicEndpoints?

    /// The API version asked for. 1.16.1 is the last Subsonic release; OpenSubsonic servers
    /// answer to it too.
    static let apiVersion = "1.16.1"

    public init(server: SubsonicServer, password: String, transport: any SubsonicTransport = URLSessionTransport(), endpoints: SubsonicEndpoints? = nil) {
        self.server = server
        self.password = password
        self.transport = transport
        self.endpoints = endpoints
    }

    /// Where requests go now.
    public var baseURL: URL { endpoints?.baseURL ?? server.url }

    /// Query items that sign a request, which nothing else may carry.
    static let signingItems: Set<String> = ["u", "t", "s", "p", "apiKey"]

    /// md5(password + salt), in lower-case hex, as the API asks.
    static func token(password: String, salt: String) -> String {
        Insecure.MD5.hash(data: Data((password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Addresses

    /// An endpoint's address with the credentials every request carries, signed with a salt
    /// of its own. With one salt for good, the token in any address that reached a log
    /// would sign every request after it.
    public func url(_ endpoint: String, _ parameters: [String: String] = [:], json: Bool = true) -> URL {
        // An address that can't be signed goes unsigned: the server refuses it, rather than
        // Tracks stopping.
        signedURL(endpoint, parameters, json: json) ?? baseURL.appending(path: "rest/\(endpoint)")
    }

    /// The credentials one request carries: a fresh salt and its token, or the API key.
    func signingItems() -> [URLQueryItem] {
        switch server.authMethod ?? .token {
        case .apiKey:
            return [URLQueryItem(name: "apiKey", value: password)]
        case .token:
            let salt = SubsonicServer.makeSalt()
            return [
                URLQueryItem(name: "u", value: server.username),
                URLQueryItem(name: "t", value: Self.token(password: password, salt: salt)),
                URLQueryItem(name: "s", value: salt),
            ]
        }
    }

    private func signedURL(_ endpoint: String, _ parameters: [String: String], json: Bool) -> URL? {
        unsignedURL(endpoint, parameters, json: json).flatMap(authorize)
    }

    /// Whether a request to `url` may carry the credentials now. See
    /// ``SubsonicCredentialRule``.
    public func allowsCredentials(to url: URL) -> Bool {
        if let endpoints { return endpoints.allowsCredentials(to: url) }
        let hasHTTPS = server.addresses.contains { $0.url.scheme?.lowercased() == "https" }
        let isHome = SubsonicCredentialRule.isHome(CurrentNetwork.shared.identity, of: server.homeNetworks ?? [])
        return SubsonicCredentialRule.allows(url, usesAPIKey: server.usesAPIKey, hasHTTPS: hasHTTPS, onHomeNetwork: isHome)
    }

    /// An endpoint's address without anything that signs in, as covers are handed around:
    /// to image caches, Now Playing and the rest of the system. It's signed only as it's
    /// fetched (``authorize(_:)``).
    func unsignedURL(_ endpoint: String, _ parameters: [String: String], json: Bool, base: URL? = nil) -> URL? {
        guard var components = URLComponents(url: (base ?? baseURL).appending(path: "rest/\(endpoint)"), resolvingAgainstBaseURL: false) else { return nil }
        var items = [
            URLQueryItem(name: "v", value: Self.apiVersion),
            URLQueryItem(name: "c", value: "Tracks"),
        ]
        if json { items.append(URLQueryItem(name: "f", value: "json")) }
        items += parameters.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        components.queryItems = items
        return Self.encodingPlus(components)
    }

    /// The address signed for one request, with a salt of its own, if it's one of this
    /// server's API addresses. Nil for anywhere else, which never sees the credentials.
    public func authorize(_ url: URL) -> URL? {
        guard isOwn(url), allowsCredentials(to: url), var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let kept = (components.queryItems ?? []).filter { !Self.signingItems.contains($0.name) }
        components.queryItems = signingItems() + kept
        return Self.encodingPlus(components)
    }

    /// Whether an address is this server's API, at any of its addresses.
    public func isOwn(_ url: URL) -> Bool {
        let scheme = url.scheme?.lowercased()
        let path = url.path(percentEncoded: false)
        return ([baseURL] + server.addresses.map(\.url)).contains { base in
            let basePath = base.path(percentEncoded: false).hasSuffix("/") ? String(base.path(percentEncoded: false).dropLast()) : base.path(percentEncoded: false)
            return base.scheme?.lowercased() == scheme
                && TracksideEndpointProof.authority(of: base) == TracksideEndpointProof.authority(of: url)
                && path.hasPrefix(basePath + "/rest/")
        }
    }

    /// URLComponents leaves "+" alone, and servers read it as a space: "luke+music@example.com"
    /// would sign in as someone else, and "Florence + the Machine" wouldn't be found.
    private static func encodingPlus(_ components: URLComponents) -> URL? {
        var components = components
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url
    }

    /// The song as the server stores it, or re-encoded to a bit rate for a slow connection.
    /// - Parameter maxBitRate: kilobits a second, or nil for the original file.
    public func streamURL(songID: String, maxBitRate: Int? = nil) -> URL {
        if let maxBitRate {
            return url("stream", ["id": songID, "maxBitRate": String(maxBitRate), "format": "mp3"], json: false)
        }
        return url("stream", ["id": songID, "format": "raw"], json: false)
    }

    /// The original file, for keeping.
    public func downloadURL(songID: String) -> URL {
        url("download", ["id": songID], json: false)
    }

    /// Without credentials, so the address is the same every time, the image stays cached,
    /// and an address that reaches a cache, a log or the system signs nothing in. It's
    /// signed as it's fetched (``authorize(_:)``).
    public func coverArtURL(id: String, size: Int? = nil) -> URL {
        let parameters = size.map { ["id": id, "size": String($0)] } ?? ["id": id]
        return unsignedURL("getCoverArt", parameters, json: false) ?? baseURL.appending(path: "rest/getCoverArt")
    }

    // MARK: - Asking

    /// Checks the address and credentials.
    public func ping() async throws {
        _ = try await call("ping")
    }

    /// The OpenSubsonic extensions the server offers, by name. Asked without signing in, as
    /// the spec allows; empty for a server that isn't OpenSubsonic.
    public func openSubsonicExtensions() async throws -> Set<String> {
        guard let url = unsignedURL("getOpenSubsonicExtensions", [:], json: true) else { return [] }
        let (data, status) = try await transport.data(for: url)
        guard let body = try? Self.decode(data, status: status) else { return [] }
        return Set((body.openSubsonicExtensions ?? []).map(\.name))
    }

    /// Whether an address answers as a Subsonic server, asked without credentials so a
    /// stranger's network that answers for it learns nothing.
    public static func answersAsSubsonic(_ base: URL, timeout: Duration, transport: any SubsonicTransport) async -> Bool {
        guard var components = URLComponents(url: base.appending(path: "rest/ping"), resolvingAgainstBaseURL: false) else { return false }
        components.queryItems = [URLQueryItem(name: "v", value: apiVersion), URLQueryItem(name: "c", value: "Tracks"), URLQueryItem(name: "f", value: "json")]
        guard let url = components.url else { return false }
        let seconds = TimeInterval(timeout.components.seconds) + TimeInterval(timeout.components.attoseconds) / 1e18
        guard let (data, _) = try? await transport.data(for: url, timeout: seconds) else { return false }
        return (try? JSONDecoder().decode(SubsonicResponse.self, from: data)) != nil
    }

    /// Every artist on the server.
    public func artists() async throws -> [SubsonicArtist] {
        let body = try await call("getArtists")
        return (body.artists?.index ?? []).flatMap { $0.artist ?? [] }
    }

    public func artist(id: String) async throws -> (artist: SubsonicArtist, albums: [SubsonicAlbum]) {
        let body = try await call("getArtist", ["id": id])
        guard let artist = body.artist else { throw SubsonicError.notFound }
        return (artist.artist, artist.album ?? [])
    }

    public func album(id: String) async throws -> (album: SubsonicAlbum, songs: [SubsonicSong]) {
        let body = try await call("getAlbum", ["id": id])
        guard let album = body.album else { throw SubsonicError.notFound }
        return (album.album, album.song ?? [])
    }

    public enum AlbumListType: String, Sendable, CaseIterable {
        case newest, recent, frequent, random, highest, alphabeticalByName, starred
    }

    public func albums(_ type: AlbumListType, size: Int = 30, offset: Int = 0) async throws -> [SubsonicAlbum] {
        let body = try await call("getAlbumList2", ["type": type.rawValue, "size": String(size), "offset": String(offset)])
        return body.albumList2?.album ?? []
    }

    /// Artists, albums and songs matching a query. An empty query matches everything, which
    /// is how a whole library is paged through.
    public func search(_ query: String, artists: Int = 10, albums: Int = 20, songs: Int = 40, songOffset: Int = 0) async throws -> SubsonicSearchResult {
        let body = try await call("search3", [
            "query": query,
            "artistCount": String(artists),
            "albumCount": String(albums),
            "songCount": String(songs),
            "songOffset": String(songOffset),
        ])
        let result = body.searchResult3
        return SubsonicSearchResult(artists: result?.artist ?? [], albums: result?.album ?? [], songs: result?.song ?? [])
    }

    /// Songs like an artist's, or like a song, from across the server. Needs the server to
    /// know about similar artists, which most get from Last.fm.
    public func similarSongs(to id: String, count: Int = 50) async throws -> [SubsonicSong] {
        let body = try await call("getSimilarSongs2", ["id": id, "count": String(count)])
        return body.similarSongs2?.song ?? []
    }

    public func topSongs(artist name: String, count: Int = 20) async throws -> [SubsonicSong] {
        let body = try await call("getTopSongs", ["artist": name, "count": String(count)])
        return body.topSongs?.song ?? []
    }

    /// Artists like this one that the server has.
    public func similarArtists(to id: String, count: Int = 20) async throws -> [SubsonicArtist] {
        // Some servers cut the list before leaving out artists they don't have: ask for more,
        // and keep the first that are there.
        let body = try await call("getArtistInfo2", ["id": id, "count": String(min(count * 3, 100))])
        return Array((body.artistInfo2?.similarArtist ?? []).filter { !$0.id.isEmpty }.prefix(count))
    }

    public func randomSongs(count: Int = 30, genre: String? = nil) async throws -> [SubsonicSong] {
        var parameters = ["size": String(count)]
        if let genre { parameters["genre"] = genre }
        let body = try await call("getRandomSongs", parameters)
        return body.randomSongs?.song ?? []
    }

    /// Tells the server a song played, for its own counts and anything it passes plays on to.
    /// - Parameter submission: false while it starts ("now playing"), true once it counts.
    public func scrobble(songID: String, at date: Date = .now, submission: Bool) async throws {
        _ = try await call(retrying: false, "scrobble", [
            "id": songID,
            "time": String(Int(date.timeIntervalSince1970 * 1_000)),
            "submission": submission ? "true" : "false",
        ])
    }

    public func star(songID: String) async throws {
        _ = try await call(retrying: false, "star", ["id": songID])
    }

    public func unstar(songID: String) async throws {
        _ = try await call(retrying: false, "unstar", ["id": songID])
    }

    /// An album's animated cover, the square loop Apple Music plays in place of it, where the
    /// server can find one: the `getAnimatedArt` extension some Subsonic servers have, asked by
    /// name so it serves any album, not only the server's own. Nil when the album has none;
    /// throws when the server has no such thing, or has it turned off.
    public func animatedCover(artist: String, album: String) async throws -> URL? {
        let body = try await call("getAnimatedArt", ["artist": artist, "album": album])
        return Self.squareLoop(in: body, server: server.url)
    }

    /// The square loop's address in an answer: Apple's own, or one on the server when it
    /// relays them, which it gives from its root.
    static func squareLoop(in body: SubsonicResponse.Body, server: URL) -> URL? {
        guard let video = body.animatedArt?.square?.video.trimmingCharacters(in: .whitespaces), !video.isEmpty else { return nil }
        if let url = URL(string: video), url.scheme == "https" || url.scheme == "http" { return url }
        let path = video.hasPrefix("/") ? String(video.dropFirst()) : video
        return server.appending(path: path)
    }

    /// How a read is retried when the network or the server stumbles.
    static let readRetry = RetryPolicy(attempts: 3, firstDelay: .milliseconds(500), longestDelay: .seconds(4))

    /// Asks the server, each try signed afresh. Reads are tried again after a moment when the
    /// network or the server stumbles; writes (`retrying: false`) are sent once, since the
    /// server may have taken one whose answer was lost.
    private func call(retrying: Bool = true, _ endpoint: String, _ parameters: [String: String] = [:]) async throws -> SubsonicResponse.Body {
        let policy = retrying ? Self.readRetry : .none
        return try await policy.run(isTransient: Self.isRetryable) {
            try await callOnce(endpoint, parameters)
        }
    }

    private func callOnce(_ endpoint: String, _ parameters: [String: String]) async throws -> SubsonicResponse.Body {
        guard let url = signedURL(endpoint, parameters, json: true) else { throw URLError(.badURL) }
        do {
            let (data, status) = try await transport.data(for: url)
            let body = try Self.decode(data, status: status)
            endpoints?.recordSuccess()
            return body
        } catch let error as URLError where Self.isUnreachable(error) {
            endpoints?.recordFailure()
            throw error
        }
    }

    static func isRetryable(_ error: any Error) -> Bool {
        switch error {
        case let error as URLError: [.timedOut, .networkConnectionLost].contains(error.code)
        case SubsonicError.http(let status): [502, 503, 504].contains(status)
        default: false
        }
    }

    static func isUnreachable(_ error: URLError) -> Bool {
        [.timedOut, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost, .dnsLookupFailed, .notConnectedToInternet, .secureConnectionFailed].contains(error.code)
    }

    static func decode(_ data: Data, status: Int) throws -> SubsonicResponse.Body {
        guard let response = try? JSONDecoder().decode(SubsonicResponse.self, from: data) else {
            throw (200..<300).contains(status) ? SubsonicError.notSubsonic : SubsonicError.http(status: status)
        }
        let body = response.body
        guard body.status == "ok" else {
            let code = body.error?.code ?? 0
            switch code {
            case 40, 41, 44: throw SubsonicError.wrongCredentials
            case 42, 43: throw SubsonicError.authMethodUnsupported
            case 20, 30: throw SubsonicError.incompatible
            case 70: throw SubsonicError.notFound
            default: throw SubsonicError.server(code: code, message: body.error?.message ?? "")
            }
        }
        return body
    }
}

// MARK: - The whole library

/// Every song on a Subsonic server, as one sync reads it.
public struct SubsonicCatalog: Sendable, Equatable {
    public var songs: [SubsonicSong]
    /// False when the walk stopped at its limit, so songs past it weren't read.
    public var isComplete: Bool
}

extension SubsonicClient {
    /// Every song on the server: paged through by an empty search, or album by album on a
    /// server that won't search for nothing. Each song once, in the server's order.
    ///
    /// Paging ends at an empty page or one with nothing new, never at a short one: a server
    /// that sends fewer than asked for has more behind it, and one that ignores the offset
    /// repeats itself. A server that's busy, starting up or out of reach throws rather than
    /// answering with part of the library, so the songs already kept stay.
    public func catalog(pageSize: Int = 500, limit: Int = 60_000) async throws -> SubsonicCatalog {
        let pageSize = max(1, pageSize)
        do {
            let searched = try await catalogBySearch(pageSize: pageSize, limit: limit)
            if !searched.songs.isEmpty { return searched }
        } catch let error where Self.isTransient(error) {
            throw error
        } catch {
            // Refused an empty search: older servers do. Walked album by album instead.
        }
        return try await catalogByAlbum(pageSize: pageSize, limit: limit)
    }

    /// Whether a failure says nothing about the library, only that it couldn't be read now.
    public static func isTransient(_ error: any Error) -> Bool {
        switch error {
        case is URLError, is CancellationError: true
        case SubsonicError.http(let status): status == 408 || status == 429 || status >= 500
        default: false
        }
    }

    private func catalogBySearch(pageSize: Int, limit: Int) async throws -> SubsonicCatalog {
        var songs: [SubsonicSong] = []
        var seen: Set<String> = []
        var offset = 0
        while true {
            try Task.checkCancellation()
            let page = try await search("", artists: 0, albums: 0, songs: pageSize, songOffset: offset).songs
            let fresh = page.filter { $0.isExternal != true && seen.insert($0.id).inserted }
            guard !page.isEmpty, !fresh.isEmpty else { break }
            songs += fresh
            if songs.count >= limit { return SubsonicCatalog(songs: Array(songs.prefix(limit)), isComplete: false) }
            offset += page.count
        }
        return SubsonicCatalog(songs: songs, isComplete: true)
    }

    private func catalogByAlbum(pageSize: Int, limit: Int) async throws -> SubsonicCatalog {
        var albums: [SubsonicAlbum] = []
        var seenAlbums: Set<String> = []
        while true {
            try Task.checkCancellation()
            let page = try await self.albums(.alphabeticalByName, size: pageSize, offset: albums.count)
            let fresh = page.filter { seenAlbums.insert($0.id).inserted }
            guard !fresh.isEmpty else { break }
            albums += fresh
        }
        var songs: [SubsonicSong] = []
        var seen: Set<String> = []
        // A few albums at a time: a home server answers one listener, not a crowd.
        let concurrent = 8
        try await withThrowingTaskGroup(of: (Int, [SubsonicSong]).self) { group in
            var next = 0
            var found: [Int: [SubsonicSong]] = [:]
            var written = 0
            func ask(_ index: Int) {
                let id = albums[index].id
                group.addTask {
                    do {
                        return (index, try await self.album(id: id).songs)
                    } catch let error where Self.isTransient(error) {
                        throw error
                    } catch {
                        // Gone since the list was read, or one the server can't open.
                        return (index, [])
                    }
                }
            }
            while next < min(concurrent, albums.count) {
                ask(next)
                next += 1
            }
            for try await (index, albumSongs) in group {
                found[index] = albumSongs
                // In the albums' order, whichever answered first.
                while let ready = found.removeValue(forKey: written) {
                    songs += ready.filter { seen.insert($0.id).inserted }
                    written += 1
                }
                if songs.count >= limit {
                    group.cancelAll()
                    break
                }
                if next < albums.count {
                    ask(next)
                    next += 1
                }
            }
        }
        if songs.count >= limit { return SubsonicCatalog(songs: Array(songs.prefix(limit)), isComplete: false) }
        return SubsonicCatalog(songs: songs, isComplete: true)
    }
}

// MARK: - What the server sends

public struct SubsonicArtist: Codable, Sendable, Hashable, Identifiable {
    @Lenient public var id: String
    public var name: String
    public var albumCount: Int?
    public var coverArt: String?
    public var artistImageUrl: String?

    public init(id: String, name: String, albumCount: Int? = nil, coverArt: String? = nil) {
        self._id = Lenient(wrappedValue: id)
        self.name = name
        self.albumCount = albumCount
        self.coverArt = coverArt
    }
}

public struct SubsonicAlbum: Codable, Sendable, Hashable, Identifiable {
    @Lenient public var id: String
    public var name: String
    public var artist: String?
    @LenientOptional public var artistId: String?
    public var coverArt: String?
    public var songCount: Int?
    public var duration: Int?
    public var year: Int?
    public var genre: String?
    public var created: String?

    public init(id: String, name: String, artist: String? = nil, artistId: String? = nil, coverArt: String? = nil, songCount: Int? = nil, year: Int? = nil, genre: String? = nil) {
        self._id = Lenient(wrappedValue: id)
        self.name = name
        self.artist = artist
        self._artistId = LenientOptional(wrappedValue: artistId)
        self.coverArt = coverArt
        self.songCount = songCount
        self.year = year
        self.genre = genre
    }
}

public struct SubsonicSong: Codable, Sendable, Hashable, Identifiable {
    @Lenient public var id: String
    public var title: String
    public var artist: String?
    public var album: String?
    @LenientOptional public var albumId: String?
    @LenientOptional public var artistId: String?
    public var track: Int?
    public var discNumber: Int?
    public var year: Int?
    public var genre: String?
    public var duration: Int?
    public var suffix: String?
    public var contentType: String?
    public var bitRate: Int?
    public var size: Int?
    public var coverArt: String?
    /// OpenSubsonic: the file's own sample rate and bit depth.
    public var samplingRate: Int?
    public var bitDepth: Int?
    /// OpenSubsonic: the album's artist, where the server knows it.
    public var displayAlbumArtist: String?
    /// A song the server found for you elsewhere rather than one in its library.
    public var isExternal: Bool?
    /// When the server added the song, as ISO 8601: "2024-03-01T18:22:10.000Z".
    public var created: String?

    /// When the server added the song, where it says.
    public var createdDate: Date? {
        guard let created else { return nil }
        if let date = try? Date(created, strategy: .iso8601) { return date }
        // Without fractional seconds, or without a zone, as some servers send it.
        let withFraction = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
        if let date = try? Date(created, strategy: withFraction) { return date }
        return try? Date(created + "Z", strategy: .iso8601)
    }

    public init(id: String, title: String, artist: String? = nil, album: String? = nil, albumId: String? = nil, track: Int? = nil, duration: Int? = nil, suffix: String? = nil, coverArt: String? = nil) {
        self._id = Lenient(wrappedValue: id)
        self.title = title
        self.artist = artist
        self.album = album
        self._albumId = LenientOptional(wrappedValue: albumId)
        self._artistId = LenientOptional(wrappedValue: nil)
        self.track = track
        self.duration = duration
        self.suffix = suffix
        self.coverArt = coverArt
    }

    /// The song as one of your own, on this server.
    public func track(on serverID: String, addedAt: Date = .now) -> LocalTrack {
        LocalTrack(
            origin: .server(serverID: serverID, songID: id),
            title: title,
            artist: artist ?? "",
            albumArtist: displayAlbumArtist,
            album: album,
            trackNumber: track,
            discNumber: discNumber,
            year: year,
            genre: genre,
            duration: duration.map(TimeInterval.init),
            format: suffix.map {
                AudioFormat(codec: AudioFormat.codec(forExtension: $0), sampleRate: samplingRate, bitDepth: bitDepth, bitRate: bitRate)
            },
            artwork: coverArt.map { .server(serverID: serverID, coverID: $0) },
            addedAt: addedAt
        )
    }
}

public struct SubsonicSearchResult: Sendable, Equatable {
    public var artists: [SubsonicArtist]
    public var albums: [SubsonicAlbum]
    public var songs: [SubsonicSong]
}

/// The envelope every answer comes in: `{"subsonic-response": {...}}`.
struct SubsonicResponse: Decodable, Sendable {
    let body: Body

    enum CodingKeys: String, CodingKey {
        case body = "subsonic-response"
    }

    struct Body: Decodable, Sendable {
        let status: String
        let error: ErrorBody?
        let artists: ArtistsIndex?
        let artist: ArtistWithAlbums?
        let album: AlbumWithSongs?
        let albumList2: AlbumList?
        let searchResult3: SearchResult?
        let similarSongs2: SongList?
        let topSongs: SongList?
        let randomSongs: SongList?
        let artistInfo2: ArtistInfo?
        /// For getAnimatedArt.
        let animatedArt: AnimatedArt?
        /// For getOpenSubsonicExtensions.
        let openSubsonicExtensions: [Extension]?
    }

    struct Extension: Decodable {
        let name: String
    }

    struct AnimatedArt: Decodable {
        let square: Loop?
        let tall: Loop?

        struct Loop: Decodable {
            let video: String
        }
    }

    struct ErrorBody: Decodable {
        let code: Int
        let message: String?
    }

    struct ArtistsIndex: Decodable {
        let index: [Index]?
        struct Index: Decodable { let artist: [SubsonicArtist]? }
    }

    struct ArtistWithAlbums: Decodable {
        let artist: SubsonicArtist
        let album: [SubsonicAlbum]?

        init(from decoder: any Decoder) throws {
            artist = try SubsonicArtist(from: decoder)
            album = try decoder.container(keyedBy: Keys.self).decodeIfPresent([SubsonicAlbum].self, forKey: .album)
        }

        enum Keys: String, CodingKey { case album }
    }

    struct AlbumWithSongs: Decodable {
        let album: SubsonicAlbum
        let song: [SubsonicSong]?

        init(from decoder: any Decoder) throws {
            album = try SubsonicAlbum(from: decoder)
            song = try decoder.container(keyedBy: Keys.self).decodeIfPresent([SubsonicSong].self, forKey: .song)
        }

        enum Keys: String, CodingKey { case song }
    }

    struct AlbumList: Decodable { let album: [SubsonicAlbum]? }
    struct SongList: Decodable { let song: [SubsonicSong]? }
    struct SearchResult: Decodable {
        let artist: [SubsonicArtist]?
        let album: [SubsonicAlbum]?
        let song: [SubsonicSong]?
    }
    struct ArtistInfo: Decodable { let similarArtist: [SubsonicArtist]? }
}

/// An id some servers send as a number and others as a string.
@propertyWrapper
public struct Lenient: Codable, Sendable, Hashable {
    public var wrappedValue: String

    public init(wrappedValue: String) {
        self.wrappedValue = wrappedValue
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let text = try? container.decode(String.self) {
            wrappedValue = text
        } else {
            wrappedValue = String(try container.decode(Int.self))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(wrappedValue)
    }
}

/// ``Lenient``, for an id that may be missing.
@propertyWrapper
public struct LenientOptional: Codable, Sendable, Hashable {
    public var wrappedValue: String?

    public init(wrappedValue: String?) {
        self.wrappedValue = wrappedValue
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            wrappedValue = nil
        } else if let text = try? container.decode(String.self) {
            wrappedValue = text
        } else {
            wrappedValue = (try? container.decode(Int.self)).map(String.init)
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(wrappedValue)
    }
}

extension KeyedDecodingContainer {
    /// A missing optional id decodes as nil rather than failing the whole item.
    public func decode(_ type: LenientOptional.Type, forKey key: Key) throws -> LenientOptional {
        try decodeIfPresent(type, forKey: key) ?? LenientOptional(wrappedValue: nil)
    }
}

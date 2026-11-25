import Foundation

/// Why Trackside said no, from the stable code in its error body.
public enum TracksideError: Error, Equatable, Sendable {
    /// This device's key was refused: revoked, or never valid.
    case unauthenticated
    /// The `mt` on a media address ran out, or its device was revoked.
    case mediaTokenExpired
    case forbidden
    /// Unknown, or not visible to this user, which the server doesn't tell apart.
    case notFound
    case conflict(reason: String?)
    /// The sync cursor is too old to continue from; a snapshot starts over.
    case syncReset
    /// A song offered for getting isn't known any more; search again.
    case candidateExpired
    case preconditionFailed
    case invalidState
    case rateLimited(retryAfter: TimeInterval?)
    case quotaExceeded(resetsAt: Date?)
    /// The song's library is offline, the transcoder is full or the server is starting or
    /// stopping. Worth trying again after `retryAfter`.
    case unavailable(code: String, retryAfter: TimeInterval?)
    /// The server's disk is full: it plays, but takes nothing new.
    case insufficientStorage
    case server(status: Int, code: String, message: String)
    /// What this user's server doesn't offer, so nothing was asked.
    case featureUnavailable
    /// Not an answer Tracks can read: not Trackside, most likely.
    case notTrackside
    case http(status: Int)
    /// None of the server's addresses proved it is the server, so the device key wasn't sent.
    case unproven
    /// Plain HTTP to an address that isn't nearby: a password, code or key isn't sent there.
    case insecureAddress
    /// The server takes this device's key only over https, and none of its https addresses
    /// answered. Nothing to sign in to again: an https address that works is enough.
    case httpsRequired
}

/// Sends a request and hands back the body and response. URLSession in the app; a stub in tests.
public protocol TracksideTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

/// Talks to one Trackside server, with this device's key, at whichever of its addresses
/// ``ServerEndpoints`` picked.
public struct TracksideClient: Sendable {
    public let serverID: String
    public let endpoints: ServerEndpoints
    public let mediaToken: TracksideMediaToken?
    /// Sent only in the `Authorization` header, never in an address.
    private let apiKey: String
    private let transport: any TracksideTransport

    public init(serverID: String, endpoints: ServerEndpoints, apiKey: String, mediaToken: TracksideMediaToken?, transport: any TracksideTransport = URLSessionTracksideTransport()) {
        self.serverID = serverID
        self.endpoints = endpoints
        self.apiKey = apiKey
        self.mediaToken = mediaToken
        self.transport = transport
    }

    /// The same client with a renewed media token.
    public func with(mediaToken: TracksideMediaToken?) -> TracksideClient {
        TracksideClient(serverID: serverID, endpoints: endpoints, apiKey: apiKey, mediaToken: mediaToken, transport: transport)
    }

    // MARK: - Before signing in

    /// What the server at an address is, without signing in.
    /// - Returns: nil when something answered that isn't Trackside, such as a Subsonic server.
    /// - Throws: when nothing could be reached there.
    public static func detect(at base: URL, transport: any TracksideTransport = URLSessionTracksideTransport()) async throws -> TracksideCapabilities? {
        do {
            let capabilities = try await capabilities(at: base, transport: transport)
            return capabilities.isTrackside ? capabilities : nil
        } catch is TracksideError {
            return nil
        }
    }

    public static func capabilities(at base: URL, transport: any TracksideTransport = URLSessionTracksideTransport()) async throws -> TracksideCapabilities {
        try decode(try await transport.send(try request(base, "capabilities")))
    }

    /// - Parameter query: a challenge from ``TracksideEndpointProof/query(key:nonce:for:)``.
    public static func ping(at base: URL, query: [URLQueryItem] = [], timeout: Duration = .seconds(2), transport: any TracksideTransport = URLSessionTracksideTransport()) async throws -> TracksidePing {
        var request = try request(base, "ping", Dictionary(query.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { _, last in last }))
        request.timeoutInterval = TimeInterval(timeout.components.seconds) + TimeInterval(timeout.components.attoseconds) / 1e18
        return try decode(try await transport.send(request))
    }

    /// Signs in with a username and password, making this device one of the user's.
    /// - Parameter appDeviceID: the id Trackside Cloud knows this device by, sent as the
    ///   device's `client_device_id` whatever `device` says, so the key made speaks for it.
    public static func login(at base: URL, username: String, password: String, device: TracksideDeviceInfo, appDeviceID: String = DeviceIdentity.current, transport: any TracksideTransport = URLSessionTracksideTransport()) async throws -> TracksidePairResult {
        try requireSafe(base)
        let body = try TracksideJSON.encoder.encode(TracksideLoginRequest(username: username, password: password, device: device.speaking(for: appDeviceID)))
        return try decode(try await transport.send(try request(base, "auth/login", method: "POST", body: body)))
    }

    /// Pairs with a code: the eight characters typed from the dashboard, or the secret in a
    /// QR code's ``PairingPayload``.
    public static func claim(at base: URL, code: String, device: TracksideDeviceInfo, appDeviceID: String = DeviceIdentity.current, transport: any TracksideTransport = URLSessionTracksideTransport()) async throws -> TracksidePairResult {
        try requireSafe(base)
        let body = try TracksideJSON.encoder.encode(TracksideClaimRequest(code: code, device: device.speaking(for: appDeviceID)))
        return try decode(try await transport.send(try request(base, "pair/claim", method: "POST", body: body)))
    }

    /// Asks to be paired from the dashboard: someone signed in there enters the code shown.
    public static func requestPairing(at base: URL, device: TracksideDeviceInfo, appDeviceID: String = DeviceIdentity.current, transport: any TracksideTransport = URLSessionTracksideTransport()) async throws -> TracksidePairRequest {
        try requireSafe(base)
        let body = try TracksideJSON.encoder.encode(TracksidePairRequestBody(device: device.speaking(for: appDeviceID)))
        return try decode(try await transport.send(try request(base, "pair/requests", method: "POST", body: body)))
    }

    public static func pollPairing(_ pairing: TracksidePairRequest, at base: URL, transport: any TracksideTransport = URLSessionTracksideTransport()) async throws -> TracksidePairPoll {
        try requireSafe(base)
        return try decode(try await transport.send(try request(base, "pair/requests/\(pairing.id)", authorization: "Pair \(pairing.pollSecret)")))
    }

    private static func requireSafe(_ base: URL) throws {
        guard TracksideEndpointProof.allowsCredentials(to: base) else { throw TracksideError.insecureAddress }
    }

    // MARK: - The user

    public func me() async throws -> TracksideMe {
        try await get("me")
    }

    /// Revokes this device's key on the server, as removing the server from Tracks does.
    public func revokeThisDevice() async throws {
        guard case .device(let id, _) = endpoints.proofKey else { return }
        try await perform("me/devices/\(id)", method: "DELETE")
    }

    public func renewMediaToken() async throws -> TracksideMediaToken {
        try await send("media-token", method: "POST")
    }

    // MARK: - The server's addresses

    /// The server's addresses, asked of an address that has proven it is the server, unless
    /// they're still the ones `etag` stands for.
    /// - Throws: ``TracksideError/notTrackside`` when the list is another server's.
    public func serverURLs(ifNoneMatch etag: String?) async throws -> TracksideServerURLsAnswer {
        let response = try await exchange { base in
            var request = try Self.request(base, "server/urls", authorization: bearer)
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
            return request
        }
        if response.1.statusCode == 304 { return .unchanged }
        let urls: TracksideServerURLs = try Self.decode(response)
        guard urls.serverID.caseInsensitiveCompare(endpoints.serverUUID) == .orderedSame else { throw TracksideError.notTrackside }
        return .changed(urls, etag: response.1.value(forHTTPHeaderField: "ETag") ?? urls.etag)
    }

    // MARK: - Browsing

    public func song(id: String) async throws -> TracksideSong {
        try await get("songs/\(id)")
    }

    /// Songs by id, in the order asked, without the ones the server doesn't have.
    public func songs(ids: [String]) async throws -> [TracksideSong] {
        var songs: [TracksideSong] = []
        for start in stride(from: 0, to: ids.count, by: 500) {
            let batch = ids[start..<min(start + 500, ids.count)]
            let list: TracksideSongList = try await get("songs", ["ids": batch.joined(separator: ",")])
            songs += list.songs
        }
        return songs
    }

    public func albumPage(id: String) async throws -> TracksideAlbumPage {
        try await get("albums/\(id)")
    }

    public enum AlbumSort: String, Sendable, CaseIterable {
        case newest, recent, frequent, random, alphabetical
        case alphabeticalByArtist = "alphabetical_by_artist"
        case starred, year, highest
    }

    public func albums(sortedBy sort: AlbumSort, limit: Int = 100, cursor: String? = nil, genre: String? = nil) async throws -> TracksidePage<TracksideAlbum> {
        var query = ["sort": sort.rawValue, "limit": String(limit)]
        query["cursor"] = cursor
        query["genre"] = genre
        let list: TracksideAlbumList = try await get("albums", query)
        return TracksidePage(items: list.albums, nextCursor: list.nextCursor)
    }

    public func artists(limit: Int = 100, cursor: String? = nil) async throws -> TracksidePage<TracksideArtist> {
        var query = ["limit": String(limit)]
        query["cursor"] = cursor
        let list: TracksideArtistList = try await get("artists", query)
        return TracksidePage(items: list.artists, nextCursor: list.nextCursor)
    }

    public func artistPage(id: String) async throws -> TracksideArtistPage {
        try await get("artists/\(id)")
    }

    public func topSongs(artistID: String, count: Int = 20) async throws -> [TracksideSong] {
        let list: TracksideSongList = try await get("artists/\(artistID)/top-songs", ["count": String(count)])
        return list.songs
    }

    /// - Parameter inLibrary: only artists the server has, chosen before the list is cut to
    ///   `count`. A server from before it ignores it, so callers still leave out the others.
    public func similarArtists(toArtist artistID: String, count: Int = 15, inLibrary: Bool = false) async throws -> [TracksideSimilarArtist] {
        var query = ["count": String(count)]
        if inLibrary { query["in_library"] = "true" }
        let list: TracksideSimilarArtists = try await get("artists/\(artistID)/similar", query)
        return list.artists
    }

    public func similarSongs(toSong songID: String, count: Int = 20) async throws -> [TracksideSong] {
        let list: TracksideSongList = try await get("songs/\(songID)/similar", ["count": String(count)])
        return list.songs
    }

    public func random(count: Int = 40, genre: String? = nil) async throws -> [TracksideSong] {
        var query = ["count": String(count)]
        query["genre"] = genre
        let list: TracksideSongList = try await get("songs/random", query)
        return list.songs
    }

    // MARK: Shelves

    /// Songs like the ones you play that you haven't lately: the server's picks for you.
    public func picks(count: Int = 40, timeZone: TimeZone = .current) async throws -> TracksideSuggestedSongs {
        try await get("me/picks", ["count": String(count), "tz": timeZone.identifier])
    }

    /// One of Tracks' mixes made by the server from your listens there, by the id the apps
    /// use: "onRepeat", "rediscover", "newFinds" and the rest.
    public func mix(_ id: String, timeZone: TimeZone = .current) async throws -> [TracksideSong] {
        let list: TracksideSongList = try await get("me/mixes/\(id)", ["tz": timeZone.identifier])
        return list.songs
    }

    /// Songs by the artists you play and ones like them that you've never played.
    public func getMore(count: Int = 24) async throws -> TracksideSuggestedSongs {
        try await get("me/get-more", ["count": String(count)])
    }

    /// The most played songs on the server.
    public func mostPlayed(count: Int = 50) async throws -> [TracksideSong] {
        let list: TracksideSongList = try await get("songs/top", ["count": String(count)])
        return list.songs
    }

    public func genres() async throws -> [TracksideGenre] {
        let list: TracksideGenres = try await get("genres")
        return list.genres
    }

    /// Nil when the song has none.
    public func lyrics(songID: String) async throws -> TracksideLyrics? {
        do {
            return try await get("songs/\(songID)/lyrics")
        } catch TracksideError.notFound {
            return nil
        }
    }

    public enum SearchType: String, Sendable, CaseIterable {
        case song, album, artist
    }

    /// The library only: songs the server doesn't have are never in it.
    public func searchLibrary(_ query: String, types: Set<SearchType> = Set(SearchType.allCases), limit: Int = 20, cursor: String? = nil) async throws -> TracksideSearchResults {
        var parameters = [
            "q": query,
            "types": SearchType.allCases.filter(types.contains).map(\.rawValue).joined(separator: ","),
            "limit": String(limit),
        ]
        parameters["cursor"] = cursor
        return try await get("search", parameters)
    }

    // MARK: - Sync

    /// A page of the library's changes after `cursor`, or of a snapshot without one.
    /// - Throws: ``TracksideError/syncReset`` when the cursor is too old to continue from.
    public func sync(cursor: String?, limit: Int = 2_000) async throws -> TracksideSyncPage {
        var query = ["limit": String(limit)]
        query["cursor"] = cursor
        return try await get("sync", query)
    }

    // MARK: - Playing

    public func decide(_ playback: TracksidePlaybackRequest) async throws -> TracksidePlaybackDecision {
        try await send("playback/decisions", method: "POST", body: playback)
    }

    /// Asks for songs about to play to be made ready, so they start without a wait and run on
    /// from the one before without a gap. At most five at a time.
    public func prepare(songIDs: [String], profile: String) async throws -> [TracksidePreparedJob] {
        let response: TracksidePrepareResponse = try await send("transcodes/prepare", method: "POST", body: TracksidePrepareRequest(songs: Array(songIDs.prefix(5)), profile: profile))
        return response.jobs
    }

    /// The original file, or the copy made for `profile` once it's ready.
    public func streamURL(songID: String, profile: String? = nil) -> URL {
        media("songs/\(songID)/stream", profile.map { ["profile": $0] } ?? [:])
    }

    /// Without `size`, the picture as stored. Without the media token too: the address is
    /// handed to caches and the system, and gets the token only as it's fetched
    /// (``authorize(_:)``).
    public func artworkURL(id: String, size: Int? = 600) -> URL {
        let base = endpoints.baseURL
        return (try? Self.url(base, "art/\(id)", size.map { ["size": String($0)] } ?? [:])) ?? base
    }

    /// A cover's address at the address in use, with the media token, for one fetch. A cover
    /// address made at another of the server's addresses is moved to the one in use, so a
    /// cover kept at home still loads away. Nil for an address that isn't the server's art.
    public func authorize(_ url: URL) -> URL? {
        let base = endpoints.baseURL
        if Self.isSameOrigin(url, base) { return withMediaToken(url) }
        guard ServerCoverAddress.isTracksideArt(url),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let range = components.percentEncodedPath.range(of: "/api/v1/art/")
        else { return nil }
        let query = (components.queryItems ?? []).filter { $0.name != "mt" }
        var reference = URLComponents()
        reference.percentEncodedPath = String(components.percentEncodedPath[range.lowerBound...])
        reference.queryItems = query.isEmpty ? nil : query
        guard let relative = reference.string, let moved = Self.resolve(relative, against: base) else { return nil }
        return withMediaToken(moved)
    }

    /// A media address the server gave, such as a playback decision's, resolved against the
    /// address in use and carrying the media token.
    public func mediaURL(_ reference: String) -> URL? {
        Self.resolve(reference, against: endpoints.baseURL).map(withMediaToken)
    }

    /// The song for keeping, as a background download asks for it: the key goes in the
    /// header, since a background session keeps its requests on disk.
    public func downloadRequest(songID: String, profile: String? = nil) async throws -> URLRequest {
        var request = try Self.request(try await endpoints.provenBaseURL(), "songs/\(songID)/download", profile.map { ["profile": $0] } ?? [:], authorization: bearer)
        request.setValue(nil, forHTTPHeaderField: "Accept")
        // A transcode for keeping is made before it's sent, which can take a while.
        request.timeoutInterval = 300
        return request
    }

    /// The profile for a bit rate from the Streaming and Download Quality settings: nil for
    /// the original.
    public static func profile(forBitRate bitRate: Int?) -> String? {
        guard let bitRate else { return nil }
        return bitRate >= 256 ? "aac-256" : "aac-128"
    }

    // MARK: - Listens and stars

    /// - Returns: each listen's id and whether the server took it.
    public func submit(listens: [ServerListen], at clientTime: Date = .now) async throws -> [String: ServerListenStatus] {
        let body = TracksideListensRequest(clientTime: clientTime, listens: listens.map {
            .init(clientListenID: $0.id, songID: $0.songID, startedAt: $0.startedAt, playedMS: $0.playedMS, title: $0.title, artist: $0.artist, album: $0.album, durationMS: $0.durationMS)
        })
        let response: TracksideListensResponse = try await send("listens", method: "POST", body: body)
        return Dictionary(response.results.map { ($0.clientListenID, $0.status) }, uniquingKeysWith: { _, last in last })
    }

    public enum PlayingState: String, Sendable {
        case playing, paused, stopped
    }

    public func reportPlaying(songID: String, position: TimeInterval, state: PlayingState) async throws {
        try await perform("now-playing", method: "POST", body: TracksideNowPlayingRequest(songID: songID, positionMS: Int(position * 1_000), state: state.rawValue))
    }

    /// Favourites only: starring never asks the server to get anything.
    public func star(_ kind: TracksideItemKind, id: String, at date: Date = .now) async throws {
        try await perform("me/stars/\(kind.rawValue)/\(id)", method: "PUT", body: TracksideStarRequest(at: date))
    }

    public func unstar(_ kind: TracksideItemKind, id: String) async throws {
        try await perform("me/stars/\(kind.rawValue)/\(id)", method: "DELETE")
    }

    /// - Parameter rating: 1 to 5, or nil to clear it.
    public func rate(_ kind: TracksideItemKind, id: String, rating: Int?) async throws {
        if let rating {
            try await perform("me/ratings/\(kind.rawValue)/\(id)", method: "PUT", body: TracksideRatingRequest(rating: min(max(rating, 1), 5)))
        } else {
            try await perform("me/ratings/\(kind.rawValue)/\(id)", method: "DELETE")
        }
    }

    // MARK: - Requests

    private var bearer: String { "Bearer \(apiKey)" }

    /// The events stream's request, picking up after the last event seen.
    func eventsRequest(lastEventID: String?) async throws -> URLRequest {
        var request = try Self.request(try await endpoints.provenBaseURL(), "events", authorization: bearer)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue(lastEventID, forHTTPHeaderField: "Last-Event-ID")
        // The server says something at least every 20 seconds while it's connected.
        request.timeoutInterval = 60
        return request
    }

    static func url(_ base: URL, _ path: String, _ query: [String: String] = [:]) throws -> URL {
        guard var components = URLComponents(url: base.appending(path: "api/v1/\(path)"), resolvingAgainstBaseURL: false) else { throw URLError(.badURL) }
        if !query.isEmpty {
            components.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
            // URLComponents leaves "+" alone, and servers read it as a space.
            components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        }
        guard let url = components.url else { throw URLError(.badURL) }
        return url
    }

    static func request(_ base: URL, _ path: String, _ query: [String: String] = [:], method: String = "GET", body: Data? = nil, authorization: String? = nil) throws -> URLRequest {
        var request = URLRequest(url: try url(base, path, query))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(authorization, forHTTPHeaderField: "Authorization")
        // Tracks speaks with its key alone. A cookie would be refused over plain HTTP away from
        // home (`https_required`), and one the server clears must never ask for a password.
        request.httpShouldHandleCookies = false
        if let body {
            request.httpBody = body
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        request.timeoutInterval = 20
        return request
    }

    /// How a read that can safely be asked again is retried when the network or the server
    /// stumbles. Writes are never retried here: the server may have taken them.
    static let readRetry = RetryPolicy(attempts: 3, firstDelay: .milliseconds(500), longestDelay: .seconds(4))

    func get<Value: Decodable>(_ path: String, _ query: [String: String] = [:]) async throws -> Value {
        var attempt = 1
        while true {
            do {
                return try Self.decode(try await exchange { try Self.request($0, path, query, authorization: bearer) })
            } catch let error where attempt < Self.readRetry.attempts && Self.isRetryable(error) {
                try await Task.sleep(for: Self.readRetry.delay(after: attempt, jitter: Double.random(in: 0...1)))
                attempt += 1
            }
        }
    }

    func send<Value: Decodable>(_ path: String, method: String, headers: [String: String] = [:]) async throws -> Value {
        try Self.decode(try await exchange { base in
            var request = try Self.request(base, path, method: method, authorization: bearer)
            for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
            return request
        })
    }

    func send<Body: Encodable, Value: Decodable>(_ path: String, method: String, body: Body, headers: [String: String] = [:]) async throws -> Value {
        let data = try TracksideJSON.encoder.encode(body)
        return try Self.decode(try await exchange { base in
            var request = try Self.request(base, path, method: method, body: data, authorization: bearer)
            for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
            return request
        })
    }

    /// For requests whose answer is only whether the server took them.
    func perform(_ path: String, method: String) async throws {
        try Self.check(try await exchange { try Self.request($0, path, method: method, authorization: bearer) })
    }

    func perform<Body: Encodable>(_ path: String, method: String, body: Body) async throws {
        let data = try TracksideJSON.encoder.encode(body)
        try Self.check(try await exchange { try Self.request($0, path, method: method, body: data, authorization: bearer) })
    }

    /// Whether a failed read is worth asking again in a moment.
    static func isRetryable(_ error: any Error) -> Bool {
        switch error {
        case let error as URLError:
            [.timedOut, .networkConnectionLost].contains(error.code)
        case TracksideError.unavailable(_, let retryAfter):
            (retryAfter ?? 1) <= 5
        case TracksideError.http(let status):
            [502, 503, 504].contains(status)
        default:
            false
        }
    }

    /// Builds a request for the proven address and sends it. When the server answers that it
    /// takes the key only over https, Tracks moves to an https address that proves itself and
    /// asks there once more, never asking for the password again: the ones the refusal names
    /// first, then the ones `/server/urls` last gave.
    func exchange(_ build: (URL) throws -> URLRequest) async throws -> (Data, HTTPURLResponse) {
        let response = try await exchange(try build(try await endpoints.provenBaseURL()))
        guard let named = Self.httpsRequired(response) else { return response }
        if !named.isEmpty { await endpoints.prefer(named) }
        guard await endpoints.requireHTTPS() != nil else { throw TracksideError.httpsRequired }
        return try await exchange(try build(try await endpoints.provenBaseURL()))
    }

    /// The https addresses a `403 https_required` names (possibly none), or nil for any
    /// other answer.
    static func httpsRequired(_ response: (Data, HTTPURLResponse)) -> [TracksideServerURL]? {
        TracksideErrorEnvelope.httpsRequired(status: response.1.statusCode, body: response.0)
    }

    /// Sends a request, and tells the endpoints whether the server could be reached, so a
    /// network change nobody announced still finds a better address.
    private func exchange(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let response = try await transport.send(request)
            await endpoints.recordSuccess()
            return response
        } catch let error as URLError where Self.isUnreachable(error) {
            await endpoints.recordFailure()
            throw error
        }
    }

    static func isUnreachable(_ error: URLError) -> Bool {
        switch error.code {
        case .timedOut, .cannotFindHost, .cannotConnectToHost, .networkConnectionLost, .dnsLookupFailed, .notConnectedToInternet, .secureConnectionFailed:
            true
        default:
            false
        }
    }

    /// An address that can't be built goes to the server's root, which refuses it, without
    /// the token.
    private func media(_ path: String, _ query: [String: String]) -> URL {
        let base = endpoints.baseURL
        return (try? Self.url(base, path, query)).map(withMediaToken) ?? base
    }

    /// Only an address at the server in use carries the token: one the server hands out for
    /// somewhere else, such as a cover from the web, never sees it.
    private func withMediaToken(_ url: URL) -> URL {
        guard let mediaToken, Self.isSameOrigin(url, endpoints.baseURL),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return url }
        var items = (components.queryItems ?? []).filter { $0.name != "mt" }
        items.append(URLQueryItem(name: "mt", value: mediaToken.token))
        components.queryItems = items
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url ?? url
    }

    static func isSameOrigin(_ url: URL, _ base: URL) -> Bool {
        url.scheme?.lowercased() == base.scheme?.lowercased()
            && TracksideEndpointProof.authority(of: url) == TracksideEndpointProof.authority(of: base)
    }

    /// Resolves a server's path against the address in use. The server's paths start at
    /// `/api/v1`; a server under a base path such as `/music` may or may not include it, and
    /// either way the answer lands under the base.
    static func resolve(_ reference: String, against base: URL) -> URL? {
        if let absolute = URL(string: reference), absolute.scheme != nil { return absolute }
        guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false),
              let relative = URLComponents(string: reference)
        else { return nil }
        let basePath = components.percentEncodedPath.hasSuffix("/") ? String(components.percentEncodedPath.dropLast()) : components.percentEncodedPath
        let path = relative.percentEncodedPath.hasPrefix("/") ? relative.percentEncodedPath : "/" + relative.percentEncodedPath
        components.percentEncodedPath = !basePath.isEmpty && path.hasPrefix(basePath + "/") ? path : basePath + path
        components.percentEncodedQuery = relative.percentEncodedQuery
        return components.url
    }

    static func check(_ response: (Data, HTTPURLResponse)) throws {
        let (data, http) = response
        guard !(200..<300).contains(http.statusCode) else { return }
        throw error(from: data, response: http)
    }

    static func decode<Value: Decodable>(_ response: (Data, HTTPURLResponse)) throws -> Value {
        try check(response)
        do {
            return try TracksideJSON.decoder.decode(Value.self, from: response.0)
        } catch {
            throw TracksideError.notTrackside
        }
    }

    static func error(from data: Data, response: HTTPURLResponse) -> TracksideError {
        let status = response.statusCode
        guard let body = try? TracksideJSON.decoder.decode(TracksideErrorEnvelope.self, from: data).error else {
            return .http(status: status)
        }
        let retryAfter = response.value(forHTTPHeaderField: "Retry-After").flatMap { TimeInterval($0.trimmingCharacters(in: .whitespaces)) }
        switch body.code {
        case "unauthenticated": return .unauthenticated
        case "media_token_expired": return .mediaTokenExpired
        case "forbidden", "csrf", "setup_required": return .forbidden
        case "not_found": return .notFound
        case "conflict", "content_changed": return .conflict(reason: body.details?.reason ?? (body.code == "content_changed" ? body.code : nil))
        case "sync_reset": return .syncReset
        case "candidate_expired": return .candidateExpired
        case "precondition_failed": return .preconditionFailed
        case "invalid_state": return .invalidState
        case "rate_limited": return .rateLimited(retryAfter: retryAfter)
        case "quota_exceeded": return .quotaExceeded(resetsAt: body.details?.resetsAt)
        case "library_unavailable", "transcoder_busy", "unavailable": return .unavailable(code: body.code, retryAfter: retryAfter)
        case "insufficient_storage": return .insufficientStorage
        case "https_required": return .httpsRequired
        default: return .server(status: status, code: body.code, message: body.message ?? "")
        }
    }
}

// MARK: - As any music server

extension TracksideClient: MusicServerClient {
    public func ping() async throws {
        _ = try await me()
    }

    public func search(_ query: String, artists: Int, albums: Int, songs: Int, after: MusicSearchPosition?) async throws -> MusicSearchResult {
        if case .cursor(let cursor) = after {
            let found = try await searchLibrary(query, types: [.song], limit: songs, cursor: cursor)
            return MusicSearchResult(songs: (found.songs?.items ?? []).map(\.subsonicSong), next: found.songs?.nextCursor.map(MusicSearchPosition.cursor))
        }
        var types: Set<SearchType> = []
        if artists > 0 { types.insert(.artist) }
        if albums > 0 { types.insert(.album) }
        if songs > 0 { types.insert(.song) }
        guard !types.isEmpty else { return MusicSearchResult() }
        let found = try await searchLibrary(query, types: types, limit: max(artists, albums, songs))
        return MusicSearchResult(
            artists: (found.artists?.items ?? []).prefix(artists).map(\.subsonicArtist),
            albums: (found.albums?.items ?? []).prefix(albums).map(\.subsonicAlbum),
            songs: (found.songs?.items ?? []).prefix(songs).map(\.subsonicSong),
            next: songs > 0 ? found.songs?.nextCursor.map(MusicSearchPosition.cursor) : nil
        )
    }

    public func album(id: String) async throws -> (album: SubsonicAlbum, songs: [SubsonicSong]) {
        let page = try await albumPage(id: id)
        return (page.album.subsonicAlbum, page.songs.map(\.subsonicSong))
    }

    public func artist(id: String) async throws -> (artist: SubsonicArtist, albums: [SubsonicAlbum]) {
        let page = try await artistPage(id: id)
        return (page.artist.subsonicArtist, (page.albums ?? []).map(\.subsonicAlbum))
    }

    public func topSongs(artist name: String, artistID: String?, count: Int) async throws -> [SubsonicSong] {
        var id = artistID
        if id == nil {
            let wanted = StatsCalculator.folded(name)
            let found = try await searchLibrary(name, types: [.artist], limit: 10)
            id = found.artists?.items.first { ($0.identity ?? StatsCalculator.folded($0.name)) == wanted }?.id
        }
        guard let id else { return [] }
        return try await topSongs(artistID: id, count: count).map(\.subsonicSong)
    }

    public func similarSongs(to songID: String, count: Int) async throws -> [SubsonicSong] {
        try await similarSongs(toSong: songID, count: count).map(\.subsonicSong)
    }

    public func similarArtists(to artistID: String, count: Int) async throws -> [SubsonicArtist] {
        let found = try await similarArtists(toArtist: artistID, count: min(count * 3, 100), inLibrary: true)
        return Array(found.compactMap { $0.artist?.subsonicArtist }.prefix(count))
    }

    public func serverPicks(count: Int) async throws -> [SubsonicSong]? {
        do {
            return try await picks(count: count).songs.filter { $0.missing != true }.map(\.subsonicSong)
        } catch TracksideError.notFound, TracksideError.http(status: 404) {
            // A server from before picks: the walk from similar songs stands in.
            return nil
        }
    }

    public func randomSongs(count: Int, genre: String?) async throws -> [SubsonicSong] {
        try await random(count: count, genre: genre).map(\.subsonicSong)
    }

    public func albumList(_ list: MusicAlbumList, count: Int) async throws -> [SubsonicAlbum] {
        let sort: AlbumSort = switch list {
        case .newest: .newest
        case .recent: .recent
        case .frequent: .frequent
        case .random: .random
        case .highest: .highest
        case .alphabeticalByName: .alphabetical
        case .starred: .starred
        }
        return try await albums(sortedBy: sort, limit: count).items.map(\.subsonicAlbum)
    }

    public func stream(songID: String, maxBitRate: Int?, network: MusicNetwork) async throws -> MusicServerStream {
        let decision = try await decide(.tracks(songID: songID, maxBitRate: maxBitRate, network: network))
        guard let url = mediaURL(decision.url) else { throw TracksideError.notTrackside }
        return MusicServerStream(url: url, delivered: decision.delivered?.audioFormat, isTranscoded: decision.mode != .direct)
    }

    public func downloadRequest(songID: String, maxBitRate: Int?) async throws -> URLRequest {
        try await downloadRequest(songID: songID, profile: Self.profile(forBitRate: maxBitRate))
    }

    public func star(songID: String) async throws {
        try await star(.song, id: songID)
    }

    public func unstar(songID: String) async throws {
        try await unstar(.song, id: songID)
    }

    public func nowPlaying(songID: String) async throws {
        try await reportPlaying(songID: songID, position: 0, state: .playing)
    }

    public func submitListens(_ listens: [ServerListen]) async throws -> [String: ServerListenStatus] {
        try await submit(listens: listens)
    }
}

extension TracksidePlaybackRequest {
    /// What AVPlayer plays as it is, and the most a stream should take at this quality.
    public static func tracks(songID: String, maxBitRate: Int?, network: MusicNetwork) -> TracksidePlaybackRequest {
        TracksidePlaybackRequest(
            songID: songID,
            network: Network(rawValue: network.rawValue) ?? .unknown,
            client: Client(
                direct: [
                    Direct(container: "flac", codecs: ["flac"]),
                    Direct(container: "m4a", codecs: ["aac", "alac"]),
                    Direct(container: "mp4", codecs: ["aac", "alac"]),
                    Direct(container: "mp3", codecs: ["mp3"]),
                    Direct(container: "wav", codecs: ["pcm"]),
                    Direct(container: "aiff", codecs: ["pcm"]),
                ],
                hls: true,
                maxBitrateKbps: maxBitRate,
                preferredProfile: TracksideClient.profile(forBitRate: maxBitRate),
                // A song played at a higher bit rate than asked beats one that doesn't play.
                allowOriginalFallback: true
            )
        )
    }
}

extension TracksidePlaybackDecision.Delivered {
    public var audioFormat: AudioFormat? {
        guard let name = codec.map({ $0.lowercased().hasPrefix("pcm") ? (container ?? $0) : $0 }) ?? container else { return nil }
        return AudioFormat(codec: AudioFormat.codec(forExtension: name), sampleRate: sampleRate, bitDepth: bitDepth, bitRate: bitrateKbps)
    }
}

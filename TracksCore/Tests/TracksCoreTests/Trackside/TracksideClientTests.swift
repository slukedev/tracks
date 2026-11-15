import Testing
import Foundation
import Synchronization
@testable import TracksCore

@Suite("Trackside client")
struct TracksideClientTests {
    typealias T = TracksideTesting

    @Test("the device key goes in the header, never in an address")
    func bearer() async throws {
        let stub = StubTracksideTransport(body: APIExamples.me)
        _ = try await T.client(stub).me()
        let request = try #require(stub.requests.first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer tsk_9pQsecret")
        #expect(request.url?.absoluteString == "http://192.168.1.20:4747/api/v1/me")
        #expect(!(request.url?.absoluteString.contains("tsk_") ?? true))
    }

    @Test("media addresses carry the media token and not the key; a download carries the key in its header")
    func mediaAddresses() async throws {
        let client = T.client(StubTracksideTransport(body: "{}"))
        let stream = client.streamURL(songID: "s7m2")
        #expect(stream.path() == "/api/v1/songs/s7m2/stream")
        #expect(T.query(stream) == ["mt": "eyJ1Ijoi"])
        let art = client.artworkURL(id: "c9d0", size: 600)
        #expect(T.query(art) == ["mt": "eyJ1Ijoi", "size": "600"])
        #expect(client.artworkURL(id: "c9d0", size: 600) == art)

        let download = try await client.downloadRequest(songID: "s7m2", profile: "aac-256")
        #expect(download.value(forHTTPHeaderField: "Authorization") == "Bearer tsk_9pQsecret")
        let url = try #require(download.url)
        #expect(T.query(url) == ["profile": "aac-256"])
    }

    @Test("a server's paths land under the address in use, with or without its base path", arguments: [
        ("http://192.168.1.20:4747", "/api/v1/songs/s1/stream?profile=aac-256", "http://192.168.1.20:4747/api/v1/songs/s1/stream?profile=aac-256"),
        ("https://box.example.com/music", "/api/v1/transcodes/j1/index.m3u8", "https://box.example.com/music/api/v1/transcodes/j1/index.m3u8"),
        ("https://box.example.com/music/", "/music/api/v1/transcodes/j1/index.m3u8", "https://box.example.com/music/api/v1/transcodes/j1/index.m3u8"),
        ("http://[fd00::20]:4747", "/api/v1/acquisitions/q8/stream", "http://[fd00::20]:4747/api/v1/acquisitions/q8/stream"),
        ("http://192.168.1.20:4747", "https://cdn.example.com/a.m3u8", "https://cdn.example.com/a.m3u8"),
    ])
    func resolve(base: String, reference: String, expected: String) {
        #expect(TracksideClient.resolve(reference, against: URL(string: base)!)?.absoluteString == expected)
    }

    @Test("a decided stream gets the media token and says what it delivers")
    func decidedStream() async throws {
        let stub = StubTracksideTransport(body: APIExamples.decision)
        let stream = try await T.client(stub).stream(songID: "s7m2", maxBitRate: 256, network: .cellular)
        #expect(stream.url.absoluteString == "http://192.168.1.20:4747/api/v1/songs/s7m2.../stream?profile=aac-256&mt=eyJ1Ijoi")
        #expect(stream.delivered?.codec == "AAC")
        #expect(stream.isTranscoded == true)

        let request = try #require(stub.requests.first)
        #expect(request.httpMethod == "POST")
        let sent = try TracksideJSON.decoder.decode(TracksidePlaybackRequest.self, from: try #require(request.httpBody))
        #expect(sent.network == .cellular)
        #expect(sent.client.maxBitrateKbps == 256)
        #expect(sent.client.preferredProfile == "aac-256")
        #expect(sent.client.direct.contains(TracksidePlaybackRequest.Direct(container: "flac", codecs: ["flac"])))
    }

    @Test("a bit rate picks an AAC profile, and none means the original", arguments: [
        (nil, nil), (320, "aac-256"), (256, "aac-256"), (128, "aac-128"), (64, "aac-128"),
    ] as [(Int?, String?)])
    func profiles(bitRate: Int?, profile: String?) {
        #expect(TracksideClient.profile(forBitRate: bitRate) == profile)
    }

    @Test("refusals are told apart by their code", arguments: [
        (401, "unauthenticated", [:], TracksideError.unauthenticated),
        (401, "media_token_expired", [:], .mediaTokenExpired),
        (404, "not_found", [:], .notFound),
        (410, "sync_reset", [:], .syncReset),
        (410, "candidate_expired", [:], .candidateExpired),
        (412, "precondition_failed", [:], .preconditionFailed),
        (429, "rate_limited", ["Retry-After": "7"], .rateLimited(retryAfter: 7)),
        (503, "library_unavailable", ["Retry-After": "30"], .unavailable(code: "library_unavailable", retryAfter: 30)),
        (507, "insufficient_storage", [:], .insufficientStorage),
        (500, "internal", [:], .server(status: 500, code: "internal", message: "Boom")),
    ] as [(Int, String, [String: String], TracksideError)])
    func errors(status: Int, code: String, headers: [String: String], expected: TracksideError) async {
        let body = #"{"error": {"code": "\#(code)", "message": "Boom", "request_id": "01JD"}}"#
        let client = T.client(StubTracksideTransport(status: status, body: body, headers: headers))
        await #expect(throws: expected) { try await client.me() }
    }

    @Test("a quota refusal says when it resets, and a conflict says why")
    func errorDetails() async {
        let quota = #"{"error": {"code": "quota_exceeded", "message": "", "details": {"resets_at": "2026-11-15T00:00:00.000Z"}}}"#
        await #expect(throws: TracksideError.quotaExceeded(resetsAt: T.date("2026-11-15T00:00:00.000Z"))) {
            try await T.client(StubTracksideTransport(status: 429, body: quota)).me()
        }
        let conflict = #"{"error": {"code": "conflict", "message": "", "details": {"reason": "not_ready"}}}"#
        await #expect(throws: TracksideError.conflict(reason: "not_ready")) {
            try await T.client(StubTracksideTransport(status: 409, body: conflict)).me()
        }
    }

    @Test("a page that isn't Trackside is told apart from a refusal")
    func notTrackside() async {
        await #expect(throws: TracksideError.notTrackside) {
            try await T.client(StubTracksideTransport(body: "<html>Hello</html>")).me()
        }
        await #expect(throws: TracksideError.http(status: 502)) {
            try await T.client(StubTracksideTransport(status: 502, body: "Bad Gateway")).me()
        }
    }

    @Test("Trackside is told from other servers before signing in, and an unreachable address isn't taken for one")
    func detect() async throws {
        let found = try await TracksideClient.detect(at: T.home, transport: StubTracksideTransport(body: APIExamples.capabilities))
        #expect(found?.serverID == T.serverUUID)
        let navidrome = StubTracksideTransport(status: 404, body: "404 page not found")
        #expect(try await TracksideClient.detect(at: T.home, transport: navidrome) == nil)
        let other = StubTracksideTransport(body: #"{"product": "someone-else"}"#)
        #expect(try await TracksideClient.detect(at: T.home, transport: other) == nil)
        let offline = StubTracksideTransport { _ in throw URLError(.cannotConnectToHost) }
        await #expect(throws: URLError.self) { try await TracksideClient.detect(at: T.home, transport: offline) }
    }

    @Test("signing in and pairing send this device, without any key")
    func signIn() async throws {
        let device = TracksideDeviceInfo(clientDeviceID: "8C2E", name: "Luke's iPhone", platform: "ios", appVersion: "3.2")
        let stub = StubTracksideTransport(status: 201, body: APIExamples.pairResult)
        let result = try await TracksideClient.claim(at: T.home, code: "Q7RM-4X2K", device: device, transport: stub)
        #expect(result.apiKey == "tsk_9pQ...")
        let request = try #require(stub.requests.first)
        #expect(request.url?.path() == "/api/v1/pair/claim")
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
        let body = try JSONSerialization.jsonObject(with: try #require(request.httpBody)) as? [String: Any]
        #expect(body?["code"] as? String == "Q7RM-4X2K")
        #expect((body?["device"] as? [String: Any])?["client_device_id"] as? String == "8C2E")

        let pairing = try TracksideTesting.decode(TracksidePairRequest.self, APIExamples.pairRequest)
        let poll = StubTracksideTransport(body: #"{"state": "pending"}"#)
        #expect(try await TracksideClient.pollPairing(pairing, at: T.home, transport: poll).state == .pending)
        #expect(poll.requests.first?.value(forHTTPHeaderField: "Authorization") == "Pair Zx81...")
    }

    @Test("a search through any server: library songs, and a cursor for the next page")
    func search() async throws {
        let body = #"{"songs": {"items": [\#(APIExamples.song)], "next_cursor": "p2"}, "albums": {"items": [\#(APIExamples.album)]}, "artists": {"items": []}}"#
        let stub = StubTracksideTransport(body: body)
        let client: any MusicServerClient = T.client(stub)
        let first = try await client.search("halo", artists: 6, albums: 12, songs: 30, after: nil)
        #expect(first.songs.map(\.title) == ["Halo"])
        #expect(first.albums.map(\.name) == ["I Am... Sasha Fierce"])
        #expect(first.next == .cursor("p2"))
        #expect(T.query(try #require(stub.requests[0].url)) == ["q": "halo", "types": "song,album,artist", "limit": "30"])

        _ = try await client.search("halo", artists: 0, albums: 0, songs: 30, after: first.next)
        #expect(T.query(try #require(stub.requests[1].url)) == ["q": "halo", "types": "song", "limit": "30", "cursor": "p2"])
    }

    @Test("top songs by an artist found by name when the id isn't known")
    func topSongsByName() async throws {
        let stub = StubTracksideTransport { request in
            if request.url?.path() == "/api/v1/search" {
                return (200, #"{"artists": {"items": [{"id": "r2", "name": "Beyoncé Tribute"}, \#(APIExamples.artist)]}}"#, [:])
            }
            return (200, #"{"songs": [\#(APIExamples.song)]}"#, [:])
        }
        let songs = try await T.client(stub).topSongs(artist: "Beyonce", artistID: nil, count: 20)
        #expect(songs.count == 1)
        #expect(stub.requests.last?.url?.path() == "/api/v1/artists/r1q8z0c3m5n7b9v2x4w6/top-songs")
    }

    @Test("listens are sent with their play ids, and each one's answer comes back")
    func listens() async throws {
        let stub = StubTracksideTransport(body: #"{"results": [{"client_listen_id": "A", "status": "accepted"}, {"client_listen_id": "B", "status": "duplicate"}, {"client_listen_id": "C", "status": "rejected", "reason": "future"}]}"#)
        let started = T.date("2026-11-13T21:10:02.000Z")
        let listens = ["A", "B", "C"].map { ServerListen(id: $0, songID: "s7m2", startedAt: started, played: 254, title: "Halo", artist: "Beyoncé", duration: 261.64) }
        let results = try await T.client(stub).submit(listens: listens, at: T.date("2026-11-14T09:30:00.000Z"))
        #expect(results == ["A": .accepted, "B": .duplicate, "C": .rejected])
        let body = try JSONSerialization.jsonObject(with: try #require(stub.requests.first?.httpBody)) as? [String: Any]
        #expect(body?["client_time"] as? String == "2026-11-14T09:30:00.000Z")
        let first = (body?["listens"] as? [[String: Any]])?.first
        #expect(first?["client_listen_id"] as? String == "A")
        #expect(first?["played_ms"] as? Int == 254_000)
        #expect(first?["duration_ms"] as? Int == 261_640)
        #expect(first?["started_at"] as? String == "2026-11-13T21:10:02.000Z")
    }

    @Test("two requests in a row that can't reach the server pick another address")
    func failuresRace() async throws {
        let lan = TracksideServerURL(url: T.home, kind: .lan)
        let tailnet = TracksideServerURL(url: URL(string: "http://box.tailnet.ts.net:4747")!, kind: .tailnet)
        let lanUp = Mutex(true)
        let endpoints = ServerEndpoints(serverUUID: T.serverUUID, candidates: [lan, tailnet], current: T.home, proofKey: T.deviceKey) { candidate, _, query in
            if candidate == lan, !lanUp.withLock({ $0 }) { throw URLError(.cannotConnectToHost) }
            return T.proven(candidate.url, query)
        }
        #expect(await endpoints.race() == lan)
        lanUp.withLock { $0 = false }
        let stub = StubTracksideTransport { request in
            guard request.url?.host() == "box.tailnet.ts.net" else { throw URLError(.timedOut) }
            return (200, APIExamples.me, [:])
        }
        let client = TracksideClient(serverID: "home", endpoints: endpoints, apiKey: T.apiKey, mediaToken: nil, transport: stub)
        await #expect(throws: URLError.self) { try await client.me() }
        #expect(endpoints.baseURL == T.home)
        await #expect(throws: URLError.self) { try await client.me() }
        // The race runs in the background, so the failing request isn't held up by it.
        let deadline = ContinuousClock.now + .seconds(3)
        while endpoints.baseURL == T.home, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        #expect(endpoints.baseURL == tailnet.url)
        _ = try await client.me()
    }
}

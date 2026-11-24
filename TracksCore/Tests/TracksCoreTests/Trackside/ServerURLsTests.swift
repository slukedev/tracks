import Foundation
import Testing
@testable import TracksCore

@Suite("A Trackside server's addresses")
struct ServerURLsTests {
    static let home = URL(string: "http://192.168.1.20:4747")!
    static let away = URL(string: "https://music.example.com")!

    static let httpsRequired = """
    {"error": {"code": "https_required", "message": "https only", "details": {"https_urls": [{"url": "https://music.example.com", "kind": "public", "tls": true}]}}}
    """

    static func endpoints(_ candidates: [TracksideServerURL], current: URL = home) -> ServerEndpoints {
        ServerEndpoints(serverUUID: TracksideTesting.serverUUID, candidates: candidates, current: current, proofKey: TracksideTesting.deviceKey) { candidate, _, query in
            TracksideTesting.proven(candidate.url, query)
        }
    }

    @Test("only addresses the key may go to are kept, each once")
    func trusted() throws {
        let urls = [
            TracksideServerURL(url: Self.home, kind: .lan),
            TracksideServerURL(url: try #require(URL(string: "http://music.example.com:4747")), kind: .public),
            TracksideServerURL(url: Self.away, kind: .public, tls: true),
            TracksideServerURL(url: try #require(URL(string: "HTTPS://MUSIC.example.com:443")), kind: .public),
            TracksideServerURL(url: try #require(URL(string: "ftp://192.168.1.20")), kind: .lan),
        ]
        #expect(TracksideServerURLs.trusted(urls).map(\.url) == [Self.home, Self.away])
    }

    @Test("the list, its event and the https refusal read from the server's fixtures")
    func fixture() throws {
        let list = try TracksideJSON.decoder.decode(TracksideServerURLs.self, from: TracksideFixtures.data("server-urls.json"))
        #expect(list.serverID == TracksideTesting.serverUUID)
        #expect(list.urls.map(\.kind) == [.lan, .lan, .public])
        #expect(TracksideServerURLs.trusted(list.urls).count == 3)
        let event = try TracksideFixtures.data("server-urls-event.json")
        // The event's ETag is the one the list comes with, so a device that has it asks nothing.
        #expect(TracksideEvent(ServerSentEvent(id: "4", event: "server_urls", data: String(decoding: event, as: UTF8.self))) == .serverURLs(etag: list.etag))
        #expect(list.etag == "\"a746df40293cc8d2b0f1b1ea782c9d87\"")
        let refusal = try TracksideFixtures.data("error-https-required.json")
        let response = try #require(HTTPURLResponse(url: Self.home, statusCode: 403, httpVersion: nil, headerFields: nil))
        #expect(TracksideClient.httpsRequired((refusal, response))?.map(\.url) == [Self.away, URL(string: "https://home.tail4a2c.ts.net")!])
        #expect(TracksideClient.error(from: refusal, response: response) == .httpsRequired)
    }

    @Test("a conditional ask sends the ETag, and a 304 means nothing changed")
    func conditional() async throws {
        let list = #"{"server_id": "\#(TracksideTesting.serverUUID)", "version": "u-7", "urls": [{"url": "https://music.example.com", "kind": "public", "tls": true}]}"#
        let transport = StubTracksideTransport { request in
            request.value(forHTTPHeaderField: "If-None-Match") == "\"u-7\"" ? (304, "", [:]) : (200, list, ["ETag": "\"u-7\""])
        }
        let client = TracksideTesting.client(transport)
        let first = try await client.serverURLs(ifNoneMatch: nil)
        guard case .changed(let urls, let etag) = first else {
            Issue.record("expected a list")
            return
        }
        #expect(etag == "\"u-7\"")
        #expect(urls.urls.map(\.url) == [Self.away])
        #expect(try await client.serverURLs(ifNoneMatch: "\"u-7\"") == .unchanged)
        #expect(transport.requests.last?.url?.path() == "/api/v1/server/urls")
        #expect(transport.requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer \(TracksideTesting.apiKey)" })
    }

    @Test("another server's list is never taken")
    func otherServer() async throws {
        let list = #"{"server_id": "someone-else", "urls": [{"url": "https://evil.example.com", "kind": "public"}]}"#
        let client = TracksideTesting.client(StubTracksideTransport(body: list))
        await #expect(throws: TracksideError.notTrackside) { try await client.serverURLs(ifNoneMatch: nil) }
    }

    @Test("https_required moves to a proven https address and asks again, without a new password")
    func httpsRequiredSwitches() async throws {
        let endpoints = Self.endpoints([TracksideServerURL(url: Self.home, kind: .lan), TracksideServerURL(url: Self.away, kind: .public, tls: true)])
        let transport = StubTracksideTransport { request in
            request.url?.scheme == "http" ? (403, Self.httpsRequired, [:]) : (200, #"{"genres": []}"#, [:])
        }
        let client = TracksideClient(serverID: "home", endpoints: endpoints, apiKey: TracksideTesting.apiKey, mediaToken: nil, transport: transport)
        #expect(try await client.genres().isEmpty)
        #expect(await endpoints.requiresHTTPS)
        #expect(endpoints.baseURL == Self.away)
        #expect(transport.requests.map { $0.url?.scheme } == ["http", "https"])
        #expect(!transport.requests.contains { $0.url?.path().contains("login") == true })
    }

    @Test("without an ETag header, the list's version stands for it")
    func versionAsETag() async throws {
        let list = #"{"server_id": "\#(TracksideTesting.serverUUID)", "version": "u-7", "urls": [{"url": "https://music.example.com", "kind": "public", "tls": true}]}"#
        let client = TracksideTesting.client(StubTracksideTransport(body: list))
        guard case .changed(_, let etag) = try await client.serverURLs(ifNoneMatch: nil) else {
            Issue.record("expected a list")
            return
        }
        #expect(etag == "\"u-7\"")
    }

    @Test("the https addresses a refusal names go first and take what it says of them")
    func httpsRequiredPrefersNamed() async throws {
        let cached = TracksideServerURL(url: Self.away, kind: .unknown, tls: true, fingerprint: "old")
        let endpoints = Self.endpoints([TracksideServerURL(url: Self.home, kind: .lan), cached])
        await endpoints.prefer([TracksideServerURL(url: Self.away, kind: .public, tls: true)])
        #expect(await endpoints.candidates.map(\.url) == [Self.away, Self.home])
        #expect(await endpoints.candidates.first?.kind == .public)
        #expect(await endpoints.candidates.first?.fingerprint == nil)
    }

    @Test("an https address the refusal names is learned, proven and used")
    func httpsRequiredLearns() async throws {
        let endpoints = Self.endpoints([TracksideServerURL(url: Self.home, kind: .lan)])
        let transport = StubTracksideTransport { request in
            request.url?.scheme == "http" ? (403, Self.httpsRequired, [:]) : (200, #"{"genres": []}"#, [:])
        }
        let client = TracksideClient(serverID: "home", endpoints: endpoints, apiKey: TracksideTesting.apiKey, mediaToken: nil, transport: transport)
        #expect(try await client.genres().isEmpty)
        #expect(endpoints.baseURL == Self.away)
    }

    @Test("with no https address that answers, it says so rather than asking to sign in")
    func httpsRequiredNowhere() async throws {
        let endpoints = Self.endpoints([TracksideServerURL(url: Self.home, kind: .lan)])
        let body = #"{"error": {"code": "https_required", "message": "https only"}}"#
        let client = TracksideClient(serverID: "home", endpoints: endpoints, apiKey: TracksideTesting.apiKey, mediaToken: nil, transport: StubTracksideTransport(status: 403, body: body))
        await #expect(throws: TracksideError.httpsRequired) { try await client.genres() }
    }

    @Test("a refusal that clears a cookie falls back to the known https address, never to signing in")
    func httpsRequiredClearingCookie() async throws {
        let endpoints = Self.endpoints([TracksideServerURL(url: Self.home, kind: .lan), TracksideServerURL(url: Self.away, kind: .public, tls: true)])
        let body = #"{"error": {"code": "https_required", "message": "https only"}}"#
        let transport = StubTracksideTransport { request in
            request.url?.scheme == "http"
                ? (403, body, ["Set-Cookie": "trackside_session=; Max-Age=0; Path=/; HttpOnly"])
                : (200, #"{"genres": []}"#, [:])
        }
        let client = TracksideClient(serverID: "home", endpoints: endpoints, apiKey: TracksideTesting.apiKey, mediaToken: nil, transport: transport)
        #expect(try await client.genres().isEmpty)
        #expect(endpoints.baseURL == Self.away)
        #expect(transport.requests.allSatisfy { !$0.httpShouldHandleCookies && $0.value(forHTTPHeaderField: "Cookie") == nil })
        #expect(transport.requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer \(TracksideTesting.apiKey)" })
    }

    @Test("the server's events about addresses and Trackside Cloud read as such")
    func events() {
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "server_urls", data: "{}")) == .serverURLs(etag: nil))
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "cloud", data: #"{"entity": "plays"}"#)) == .cloud(entity: "plays"))
    }

    @Test("a cover address carries no media token until it's fetched, and follows the address in use")
    func coverSigning() throws {
        let client = TracksideTesting.client(StubTracksideTransport(body: "{}"))
        let cover = client.artworkURL(id: "a1", size: 600)
        #expect(TracksideTesting.query(cover)["mt"] == nil)
        let signed = try #require(client.authorize(cover))
        #expect(TracksideTesting.query(signed)["mt"] == TracksideTesting.mediaToken.token)
        let elsewhere = try #require(URL(string: "https://old.example.com/api/v1/art/a1?size=600"))
        let moved = try #require(client.authorize(elsewhere))
        #expect(moved.host() == Self.home.host())
        #expect(client.authorize(try #require(URL(string: "https://evil.example.com/other"))) == nil)
    }
}

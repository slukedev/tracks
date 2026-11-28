import Testing
import Foundation
@testable import TracksCore

@Suite("Linking a TV")
struct TracksideLinkTests {
    typealias T = TracksideTesting

    private func fixture(_ name: String) throws -> String {
        String(decoding: try TracksideFixtures.data(name), as: UTF8.self)
    }

    private func body(_ request: URLRequest) throws -> [String: Any] {
        try #require(try JSONSerialization.jsonObject(with: try #require(request.httpBody)) as? [String: Any])
    }

    // MARK: - Wire

    @Test("every link fixture reads", .enabled(if: TracksideFixtures.directory != nil))
    func fixtures() throws {
        let start = try TracksideJSON.decoder.decode(TracksideLinkStart.self, from: TracksideFixtures.data("link-start.json"))
        #expect(start.deviceCode == "Hx4tQ9vLm2Rk7pW3zN8cB5yF1dJ6gS0aTe4uVq2iKo8")
        #expect(start.userCode == "WDJB-MJHT")
        #expect(start.verificationURL(against: T.home)?.absoluteString == "https://music.example.com/link?code=WDJB-MJHT")
        #expect(start.linkURL == "tracks://link?server=5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b&code=WDJB-MJHT")
        #expect(start.intervalS == 5)
        #expect(start.expiresAt == T.date("2026-11-14T09:40:00.000Z"))
        #expect(!String(describing: start).contains(start.deviceCode), "the device code never reaches a log")

        for (name, state) in [("link-lookup.json", TracksideLinkInfo.State.pending), ("link-approved.json", .approved), ("link-denied.json", .denied)] {
            let info = try TracksideJSON.decoder.decode(TracksideLinkInfo.self, from: TracksideFixtures.data(name))
            #expect(info.state == state, "\(name)")
            #expect(info.device == TracksideLinkInfo.Device(name: "Living Room TV", platform: "android_tv", model: "Chromecast with Google TV", appName: "Tracks"))
            #expect(info.nearby)
            #expect(info.requestedAt == T.date("2026-11-14T09:29:30.000Z"))
            #expect(info.expiresAt == T.date("2026-11-14T09:39:30.000Z"))
        }

        let result = try TracksideJSON.decoder.decode(TracksidePairResult.self, from: TracksideFixtures.data("link-poll-result.json"))
        #expect(result.server.serverID == T.serverUUID)

        let linked = try TracksideJSON.decoder.decode(TracksideDeviceLinked.self, from: TracksideFixtures.data("device-linked-event.json"))
        #expect(linked == TracksideDeviceLinked(id: "dchjkb9k3cnapjw0kmhq6", name: "Living Room TV", platform: "android_tv"))

        let capabilities = try TracksideJSON.decoder.decode(TracksideCapabilities.self, from: TracksideFixtures.data("capabilities.json"))
        #expect(capabilities.auth?.deviceLink == true)
    }

    @Test("a start with a bare path resolves it against the server, and a lookup missing its extras still reads")
    func tolerant() throws {
        let start = try T.decode(TracksideLinkStart.self, #"{"device_code":"d","user_code":"WDJB-MJHT","verification_url":"/link?code=WDJB-MJHT","link_url":"tracks://link","expires_at":"2026-11-14T09:40:00Z"}"#)
        #expect(start.intervalS == 5)
        #expect(start.verificationURL(against: URL(string: "https://box.example.com/music")!)?.absoluteString == "https://box.example.com/music/link?code=WDJB-MJHT")

        let info = try T.decode(TracksideLinkInfo.self, #"{"state":"later","device":{"name":"TV","extra":1},"newer":true}"#)
        #expect(info.state == .unknown)
        #expect(info.device == TracksideLinkInfo.Device(name: "TV", platform: "other"))
        #expect(!info.nearby, "not said is not nearby, so the warning shows")
        #expect(info.requestedAt == nil)
    }

    @Test("the device_linked event reads, and a malformed one is let pass")
    func event() throws {
        let data = try fixture("device-linked-event.json")
        #expect(TracksideEvent(ServerSentEvent(id: "9", event: "device_linked", data: data)) == .deviceLinked(TracksideDeviceLinked(id: "dchjkb9k3cnapjw0kmhq6", name: "Living Room TV", platform: "android_tv")))
        #expect(TracksideEvent(ServerSentEvent(id: nil, event: "device_linked", data: "{}")) == .other("device_linked"))
    }

    // MARK: - Approving

    @Test("lookup, approve and deny send the code with this device's key")
    func approver() async throws {
        let lookup = try fixture("link-lookup.json"), approved = try fixture("link-approved.json"), denied = try fixture("link-denied.json")
        let stub = StubTracksideTransport { request in
            switch request.url?.lastPathComponent {
            case "lookup": (200, lookup, [:])
            case "approve": (200, approved, [:])
            case "deny": (200, denied, [:])
            default: (404, #"{"error":{"code":"not_found","message":""}}"#, [:])
            }
        }
        let client = T.client(stub)
        #expect(try await client.lookupLink(code: "wdjb mjht").state == .pending)
        #expect(try await client.approveLink(code: "WDJB-MJHT").state == .approved)
        #expect(try await client.denyLink(code: "WDJB-MJHT").state == .denied)
        #expect(stub.requests.map { $0.url?.path() } == ["/api/v1/link/lookup", "/api/v1/link/approve", "/api/v1/link/deny"])
        for request in stub.requests {
            #expect(request.httpMethod == "POST")
            #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer tsk_9pQsecret")
        }
        #expect(try body(stub.requests[0]) as? [String: String] == ["user_code": "wdjb mjht"])
    }

    @Test("an approver's refusals each read as their own error", arguments: [
        ("error-link-expired.json", 410, TracksideError.linkExpired),
        ("error-link-locked.json", 410, .linkLocked),
        ("error-link-used.json", 409, .conflict(reason: "link_used")),
        ("error-not-found.json", 404, .notFound),
    ])
    func refusals(name: String, status: Int, expected: TracksideError) async throws {
        let stub = StubTracksideTransport(status: status, body: try fixture(name))
        await #expect(throws: expected) { try await T.client(stub).approveLink(code: "WDJB-MJHT") }
    }

    // MARK: - The TV's side

    @Test("starting sends this device and no key, and never over plain http beyond home")
    func start() async throws {
        let stub = StubTracksideTransport(status: 201, body: try fixture("link-start.json"))
        let tv = TracksideDeviceInfo(clientDeviceID: "ignored", name: "Living Room TV", platform: "android_tv")
        let start = try await TracksideClient.startLink(at: T.home, device: tv, appDeviceID: "5b1f", transport: stub)
        #expect(start.userCode == "WDJB-MJHT")
        let request = try #require(stub.requests.first)
        #expect(request.url?.path() == "/api/v1/link/start")
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
        let device = try #require(try body(request)["device"] as? [String: Any])
        #expect(device["client_device_id"] as? String == "5b1f")
        #expect(device["platform"] as? String == "android_tv")

        let away = URL(string: "http://music.example.com:4747")!
        await #expect(throws: TracksideError.insecureAddress) { try await TracksideClient.startLink(at: away, device: tv, transport: stub) }
        await #expect(throws: TracksideError.insecureAddress) { try await TracksideClient.pollLink(deviceCode: "d", at: away, transport: stub) }
        #expect(stub.requests.count == 1)
    }

    @Test("a poll's answers are answers, not errors", arguments: [
        (200, "link-poll-result.json", [String: String](), "linked"),
        (428, "error-authorization-pending.json", [:], "pending"),
        (429, "error-slow-down.json", ["Retry-After": "10"], "slowDown 10"),
        (403, "error-access-denied.json", [:], "denied"),
        (410, "error-expired-token.json", [:], "expired"),
    ])
    func poll(status: Int, name: String, headers: [String: String], expected: String) async throws {
        let body = try fixture(name)
        let stub = StubTracksideTransport { _ in (status, body, headers) }
        let poll = try await TracksideClient.pollLink(deviceCode: "Hx4t", at: T.home, transport: stub)
        let read = switch poll {
        case .linked(let result): result.device.id.isEmpty ? "" : "linked"
        case .pending: "pending"
        case .slowDown(let interval): "slowDown \(interval.components.seconds)"
        case .denied: "denied"
        case .expired: "expired"
        }
        #expect(read == expected)
        #expect(try self.body(try #require(stub.requests.first)) as? [String: String] == ["device_code": "Hx4t"])
    }

    @Test("polling too often from one address is an error, with when to try again")
    func pollRateLimited() async throws {
        let stub = StubTracksideTransport(status: 429, body: #"{"error":{"code":"rate_limited","message":""}}"#, headers: ["Retry-After": "30"])
        await #expect(throws: TracksideError.rateLimited(retryAfter: 30)) { try await TracksideClient.pollLink(deviceCode: "d", at: T.home, transport: stub) }
    }

    // MARK: - Codes and links

    @Test("a code formats as it's typed, in any case, and is whole at eight", arguments: [
        ("", "", false),
        ("w", "W", false),
        ("wdjb", "WDJB", false),
        ("wdjbm", "WDJB-M", false),
        ("wdjb mjht", "WDJB-MJHT", true),
        ("WDJB-MJHT", "WDJB-MJHT", true),
        ("wdjb-mjhtxyz", "WDJB-MJHT", true),
        (" w.d/j_b-m é j h t ", "WDJB-MJHT", true),
    ])
    func code(typed: String, shown: String, complete: Bool) {
        #expect(TracksideLinkCode.formatted(typed) == shown)
        #expect(TracksideLinkCode.isComplete(typed) == complete)
        #expect(TracksideLinkCode.formatted(TracksideLinkCode.formatted(typed)) == shown, "formatting what's shown changes nothing")
    }

    @Test("a TV's link names its server and code; anything else isn't one")
    func link() throws {
        let link = try #require(TracksideLinkRequest(url: URL(string: "tracks://link?server=5F0C2B9E-8A3D-4C1E-9B7A-2D6E4F8A1C3B&code=wdjbmjht")!))
        #expect(link.code == "WDJB-MJHT")
        #expect(link.isFor(serverUUID: T.serverUUID))
        #expect(!link.isFor(serverUUID: "another"))
        #expect(!link.isFor(serverUUID: nil))
        for text in ["tracks://link?server=s1", "tracks://link?code=WDJB-MJHT", "tracks://link?server=s1&code=WDJB", "tracks://summary", "trackside://link?server=s1&code=WDJB-MJHT"] {
            #expect(TracksideLinkRequest(url: URL(string: text)!) == nil, "\(text)")
        }
    }

    @Test("each refusal says what happened and what to do, as the contract words it")
    func words() {
        #expect(TracksideLinkWords.problem(TracksideError.notFound) == "No TV is showing that code.")
        #expect(TracksideLinkWords.problem(TracksideError.linkExpired) == "That code has expired. Get a new one on the TV.")
        #expect(TracksideLinkWords.problem(TracksideError.conflict(reason: "link_used")) == "That code has been used.")
        #expect(TracksideLinkWords.problem(TracksideError.linkLocked) == "Locked after too many wrong tries. Get a new code on the TV.")
        #expect(TracksideLinkWords.problem(TracksideError.rateLimited(retryAfter: nil)) == "Too many wrong codes. Try again in a minute.")
        #expect(TracksideLinkWords.problem(TracksideError.rateLimited(retryAfter: 42)).hasPrefix("Too many wrong codes. Try again in "))
        #expect(TracksideLinkWords.problem(TracksideError.conflict(reason: "speaker_offline")) != TracksideLinkWords.problem(TracksideError.conflict(reason: "link_used")))
        #expect(TracksideLinkWords.kind(of: .init(name: "TV", platform: "android_tv", model: "Chromecast with Google TV")) == "Chromecast with Google TV")
        #expect(TracksideLinkWords.kind(of: .init(name: "TV", platform: "android_tv")) == "Google TV")
        #expect(TracksideLinkWords.connected("Living Room TV") == "Living Room TV is connected")
        #expect(TracksideLinkWords.connected(" ") == "Your TV is connected")
    }
}

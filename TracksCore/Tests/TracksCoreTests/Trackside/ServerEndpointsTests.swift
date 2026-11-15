import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// A server reachable at several addresses, each answering after its own delay, or not at all.
final class Addresses: Sendable {
    struct Answer: Sendable {
        enum Proof: Sendable {
            /// What the real server answers.
            case real
            /// What it answers for another address: a challenge relayed from elsewhere.
            case forAnotherAddress
            case none
        }

        var delay: Duration
        var serverID: String? = TracksideTesting.serverUUID
        var proof: Proof = .real
    }

    private let answers: Mutex<[URL: Answer]>
    private let asked = Mutex<[URL]>([])

    init(_ answers: [URL: Answer]) {
        self.answers = Mutex(answers)
    }

    func set(_ answers: [URL: Answer]) {
        self.answers.withLock { $0 = answers }
    }

    var askedCount: Int { asked.withLock { $0.count } }

    var askedURLs: [URL] { asked.withLock { $0 } }

    func ping(_ url: URL, timeout: Duration, query: [URLQueryItem]) async throws -> TracksidePing {
        asked.withLock { $0.append(url) }
        guard let answer = answers.withLock({ $0[url] }) else { throw URLError(.cannotConnectToHost) }
        try await Task.sleep(for: min(answer.delay, timeout))
        guard answer.delay <= timeout, let serverID = answer.serverID else { throw URLError(.timedOut) }
        switch answer.proof {
        case .real:
            return TracksideTesting.proven(url, query, serverID: serverID)
        case .forAnotherAddress:
            return TracksideTesting.proven(URL(string: "https://music.example.com")!, query, serverID: serverID)
        case .none:
            return TracksidePing(serverID: serverID)
        }
    }
}

@Suite("Picking a server's address")
struct ServerEndpointsTests {
    static let lan = TracksideServerURL(url: URL(string: "http://192.168.1.20:4747")!, kind: .lan)
    static let tailnet = TracksideServerURL(url: URL(string: "http://box.tailnet.ts.net:4747")!, kind: .tailnet)
    static let public_ = TracksideServerURL(url: URL(string: "https://music.example.com")!, kind: .public)

    private func endpoints(_ addresses: Addresses, current: URL = public_.url, candidates: [TracksideServerURL] = [public_, tailnet, lan]) -> ServerEndpoints {
        ServerEndpoints(serverUUID: TracksideTesting.serverUUID, candidates: candidates, current: current, proofKey: TracksideTesting.deviceKey) {
            try await addresses.ping($0.url, timeout: $1, query: $2)
        }
    }

    @Test("the local address wins when it answers within a moment of a faster one")
    func localWithinGrace() async {
        let addresses = Addresses([
            Self.public_.url: .init(delay: .milliseconds(10)),
            Self.tailnet.url: .init(delay: .milliseconds(30)),
            Self.lan.url: .init(delay: .milliseconds(150)),
        ])
        let endpoints = endpoints(addresses)
        #expect(await endpoints.race() == Self.lan)
        #expect(endpoints.baseURL == Self.lan.url)
    }

    @Test("the fastest wins when the local address is too slow to wait for")
    func fastestPastGrace() async {
        let addresses = Addresses([
            Self.public_.url: .init(delay: .milliseconds(80)),
            Self.tailnet.url: .init(delay: .milliseconds(20)),
            Self.lan.url: .init(delay: .milliseconds(1_500)),
        ])
        let started = ContinuousClock.now
        let endpoints = endpoints(addresses)
        #expect(await endpoints.race() == Self.tailnet)
        // Picked once the grace ran out, without waiting for the slow local answer.
        #expect(ContinuousClock.now - started < .milliseconds(1_200))
    }

    @Test("a local answer from first place is taken at once")
    func localFirst() async {
        let addresses = Addresses([
            Self.public_.url: .init(delay: .seconds(1)),
            Self.lan.url: .init(delay: .milliseconds(5)),
        ])
        let started = ContinuousClock.now
        #expect(await endpoints(addresses).race() == Self.lan)
        #expect(ContinuousClock.now - started < .milliseconds(500))
    }

    @Test("a different server at one of the addresses is never used")
    func wrongServer() async {
        let addresses = Addresses([
            Self.lan.url: .init(delay: .milliseconds(5), serverID: "someone-elses-server"),
            Self.public_.url: .init(delay: .milliseconds(40)),
        ])
        let endpoints = endpoints(addresses, current: Self.lan.url)
        #expect(await endpoints.race() == Self.public_)
        #expect(endpoints.baseURL == Self.public_.url)
    }

    @Test("with nothing answering, the address in use stays but isn't trusted with the key")
    func nothingAnswers() async {
        let endpoints = endpoints(Addresses([:]), current: Self.tailnet.url)
        #expect(await endpoints.race() == nil)
        #expect(endpoints.baseURL == Self.tailnet.url)
        await #expect(throws: TracksideError.unproven) { try await endpoints.provenBaseURL() }
    }

    @Test("an address that answers as the server but can't prove it is never used", arguments: [Addresses.Answer.Proof.none, .forAnotherAddress])
    func impostor(proof: Addresses.Answer.Proof) async throws {
        // A stranger's network answering for the home address, fast, with the right server id.
        let addresses = Addresses([
            Self.lan.url: .init(delay: .milliseconds(1), proof: proof),
            Self.public_.url: .init(delay: .milliseconds(40)),
        ])
        let endpoints = endpoints(addresses, current: Self.lan.url)
        #expect(await endpoints.race() == Self.public_)
        #expect(try await endpoints.provenBaseURL() == Self.public_.url)

        let alone = self.endpoints(addresses, current: Self.lan.url, candidates: [Self.lan])
        await #expect(throws: TracksideError.unproven) { try await alone.provenBaseURL() }
    }

    @Test("a key is never sent to an address that hasn't proven itself")
    func unprovenSendsNothing() async {
        let addresses = Addresses([Self.lan.url: .init(delay: .milliseconds(1), proof: .none)])
        let endpoints = endpoints(addresses, current: Self.lan.url, candidates: [Self.lan])
        let stub = StubTracksideTransport(body: APIExamples.me)
        let client = TracksideClient(serverID: "home", endpoints: endpoints, apiKey: TracksideTesting.apiKey, mediaToken: nil, transport: stub)
        await #expect(throws: TracksideError.unproven) { try await client.me() }
        await #expect(throws: TracksideError.unproven) { try await client.downloadRequest(songID: "s1") }
        #expect(stub.requests.isEmpty)
    }

    @Test("the challenge names this device and the address asked, never the key")
    func challengeQuery() async throws {
        let addresses = Addresses([Self.lan.url: .init(delay: .milliseconds(1))])
        let seen = Mutex<[URLQueryItem]>([])
        let endpoints = ServerEndpoints(serverUUID: TracksideTesting.serverUUID, candidates: [Self.lan], current: Self.lan.url, proofKey: TracksideTesting.deviceKey) { candidate, timeout, query in
            seen.withLock { $0 = query }
            return try await addresses.ping(candidate.url, timeout: timeout, query: query)
        }
        #expect(await endpoints.race() == Self.lan)
        let query = Dictionary(seen.withLock { $0 }.map { ($0.name, $0.value ?? "") }) { first, _ in first }
        #expect(query["host"] == "192.168.1.20:4747")
        #expect(query["device"] == "d4k2m9q7x1v8b3n6c0de")
        #expect(query["challenge"]?.count == 22)
        #expect(!query.values.contains { $0.contains("tsk_") })
    }

    @Test("plain HTTP to an address that isn't nearby is never asked or used")
    func plainPublic() async {
        let plain = TracksideServerURL(url: URL(string: "http://203.0.113.5:4747")!, kind: .public)
        let addresses = Addresses([plain.url: .init(delay: .milliseconds(1))])
        let endpoints = endpoints(addresses, current: plain.url, candidates: [plain])
        #expect(await endpoints.race() == nil)
        #expect(addresses.askedCount == 0)
    }

    @Test("a plain HTTP address is proven again after a while, in case the network changed unseen")
    func plainProofExpires() async throws {
        let addresses = Addresses([Self.lan.url: .init(delay: .milliseconds(1))])
        let endpoints = ServerEndpoints(serverUUID: TracksideTesting.serverUUID, candidates: [Self.lan], current: Self.lan.url, proofKey: TracksideTesting.deviceKey, plainHTTPProofLifetime: .milliseconds(50)) {
            try await addresses.ping($0.url, timeout: $1, query: $2)
        }
        _ = try await endpoints.provenBaseURL()
        _ = try await endpoints.provenBaseURL()
        #expect(addresses.askedCount == 1)
        try await Task.sleep(for: .milliseconds(80))
        _ = try await endpoints.provenBaseURL()
        #expect(addresses.askedCount == 2)
    }

    @Test("a network change races again and moves to what answers now")
    func pathChange() async {
        let addresses = Addresses([
            Self.public_.url: .init(delay: .milliseconds(30)),
            Self.lan.url: .init(delay: .milliseconds(5)),
        ])
        let endpoints = endpoints(addresses)
        await endpoints.race()
        #expect(endpoints.baseURL == Self.lan.url)
        // Left home: on the new network something else answers for the home address.
        addresses.set([
            Self.public_.url: .init(delay: .milliseconds(30)),
            Self.lan.url: .init(delay: .milliseconds(1), proof: .none),
        ])
        await endpoints.pathChanged()
        #expect(endpoints.baseURL == Self.public_.url)
    }

    @Test("races asked for at once share one")
    func joined() async {
        let addresses = Addresses([Self.lan.url: .init(delay: .milliseconds(50))])
        let endpoints = endpoints(addresses)
        async let first = endpoints.race()
        async let second = endpoints.race()
        #expect(await [first, second] == [Self.lan, Self.lan])
        #expect(addresses.askedCount == 3)
    }

    @Test("one failure doesn't race; two in a row do")
    func failures() async throws {
        let addresses = Addresses([Self.lan.url: .init(delay: .milliseconds(5))])
        let endpoints = endpoints(addresses)
        await endpoints.recordFailure()
        await endpoints.recordSuccess()
        await endpoints.recordFailure()
        try await Task.sleep(for: .milliseconds(50))
        #expect(addresses.askedCount == 0)
        await endpoints.recordFailure()
        let deadline = ContinuousClock.now + .seconds(2)
        while endpoints.baseURL != Self.lan.url, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        #expect(endpoints.baseURL == Self.lan.url)
    }
}

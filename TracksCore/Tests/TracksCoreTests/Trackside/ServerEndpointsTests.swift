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

        /// How long it takes to answer, on the test's clock. Zero answers at once.
        var delay: Duration = .zero
        var serverID: String? = TracksideTesting.serverUUID
        var proof: Proof = .real
    }

    private let answers: Mutex<[URL: Answer]>
    private let asked = Mutex<[URL]>([])
    let clock: SteppedClock

    init(_ answers: [URL: Answer], clock: SteppedClock = SteppedClock()) {
        self.answers = Mutex(answers)
        self.clock = clock
    }

    func set(_ answers: [URL: Answer]) {
        self.answers.withLock { $0 = answers }
    }

    var askedCount: Int { asked.withLock { $0.count } }

    var askedURLs: [URL] { asked.withLock { $0 } }

    func ping(_ url: URL, timeout: Duration, query: [URLQueryItem]) async throws -> TracksidePing {
        asked.withLock { $0.append(url) }
        guard let answer = answers.withLock({ $0[url] }) else { throw URLError(.cannotConnectToHost) }
        if answer.delay > .zero { try await clock.sleep(for: min(answer.delay, timeout)) }
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

extension ServerEndpoints {
    /// Two races asked for together. Both are queued on the actor before either runs, so the
    /// second always finds the first still going, however busy the machine is.
    func raceTwice() async -> [TracksideServerURL?] {
        let first = Task { await race() }
        let second = Task { await race() }
        return [await first.value, await second.value]
    }
}

/// A clock that only moves when a test moves it, so a race's grace, timeout and proof
/// lifetime are decided by the steps a test takes, never by how busy the machine is.
///
/// Every test in the package shares the cooperative pool, and some of them keep its threads
/// busy for seconds. A wall-clock wait in one of these tests then woke long after its
/// deadline, and a race measured on the wall clock took 22 seconds.
final class SteppedClock: Clock, Sendable {
    struct Instant: InstantProtocol {
        var offset: Duration

        func advanced(by duration: Duration) -> Instant { Instant(offset: offset + duration) }
        func duration(to other: Instant) -> Duration { other.offset - offset }
        static func < (lhs: Instant, rhs: Instant) -> Bool { lhs.offset < rhs.offset }
    }

    private struct Sleeper {
        let deadline: Instant
        let continuation: CheckedContinuation<Void, any Error>
    }

    private struct State {
        var now = Instant(offset: .zero)
        var sleepers: [UUID: Sleeper] = [:]
        /// Sleeps cancelled before they had started waiting.
        var cancelled: Set<UUID> = []
        var watchers: [(count: Int, continuation: CheckedContinuation<Void, Never>)] = []

        /// The watchers waiting for the number of sleepers there are now.
        mutating func satisfiedWatchers() -> [CheckedContinuation<Void, Never>] {
            let count = sleepers.count
            let done = watchers.filter { $0.count == count }.map(\.continuation)
            watchers.removeAll { $0.count == count }
            return done
        }
    }

    private let state = Mutex(State())

    var now: Instant { state.withLock { $0.now } }
    var minimumResolution: Duration { .zero }

    func sleep(until deadline: Instant, tolerance: Duration?) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                let (resume, watchers): (Result<Void, any Error>?, [CheckedContinuation<Void, Never>]) = state.withLock { state in
                    if state.cancelled.remove(id) != nil { return (.failure(CancellationError()), []) }
                    if deadline <= state.now { return (.success(()), []) }
                    state.sleepers[id] = Sleeper(deadline: deadline, continuation: continuation)
                    return (nil, state.satisfiedWatchers())
                }
                if let resume { continuation.resume(with: resume) }
                for watcher in watchers { watcher.resume() }
            }
        } onCancel: {
            let (sleeper, watchers) = state.withLock { state -> (Sleeper?, [CheckedContinuation<Void, Never>]) in
                guard let sleeper = state.sleepers.removeValue(forKey: id) else {
                    state.cancelled.insert(id)
                    return (nil, [])
                }
                return (sleeper, state.satisfiedWatchers())
            }
            sleeper?.continuation.resume(throwing: CancellationError())
            for watcher in watchers { watcher.resume() }
        }
    }

    /// Moves the clock on, waking every sleep that has come due, earliest first.
    func advance(by duration: Duration) {
        let (due, watchers) = state.withLock { state -> ([Sleeper], [CheckedContinuation<Void, Never>]) in
            state.now = state.now.advanced(by: duration)
            let now = state.now
            let due = state.sleepers.filter { $0.value.deadline <= now }
            for id in due.keys { state.sleepers.removeValue(forKey: id) }
            return (due.values.sorted { $0.deadline < $1.deadline }, state.satisfiedWatchers())
        }
        for sleeper in due { sleeper.continuation.resume() }
        for watcher in watchers { watcher.resume() }
    }

    /// Returns once exactly `count` sleeps are waiting on the clock: the point a test knows
    /// what the code under test is waiting for.
    func waitForSleepers(_ count: Int) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let ready = state.withLock { state in
                if state.sleepers.count == count { return true }
                state.watchers.append((count, continuation))
                return false
            }
            if ready { continuation.resume() }
        }
    }
}
/// Every wait is on a ``SteppedClock``, so a test that hangs has found a race that no longer
/// does what it says, and the time limit ends it.
@Suite("Picking a server's address", .timeLimit(.minutes(1)))
struct ServerEndpointsTests {
    static let lan = TracksideServerURL(url: URL(string: "http://192.168.1.20:4747")!, kind: .lan)
    static let tailnet = TracksideServerURL(url: URL(string: "http://box.tailnet.ts.net:4747")!, kind: .tailnet)
    static let public_ = TracksideServerURL(url: URL(string: "https://music.example.com")!, kind: .public)

    private func endpoints(
        _ addresses: Addresses,
        current: URL = public_.url,
        candidates: [TracksideServerURL] = [public_, tailnet, lan],
        plainHTTPProofLifetime: Duration = .seconds(600)
    ) -> ServerEndpoints {
        ServerEndpoints(
            serverUUID: TracksideTesting.serverUUID,
            candidates: candidates,
            current: current,
            proofKey: TracksideTesting.deviceKey,
            plainHTTPProofLifetime: plainHTTPProofLifetime,
            clock: addresses.clock
        ) {
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
        let clock = addresses.clock
        async let chosen = endpoints.race()
        // Three answers on their way and the race's timeout.
        await clock.waitForSleepers(4)
        clock.advance(by: .milliseconds(10))
        // The public answer is in, and the grace for a local one has started in its place.
        await clock.waitForSleepers(4)
        clock.advance(by: .milliseconds(140))
        #expect(await chosen == Self.lan)
        #expect(endpoints.baseURL == Self.lan.url)
    }

    @Test("the fastest wins when the local address is too slow to wait for")
    func fastestPastGrace() async {
        let addresses = Addresses([
            Self.public_.url: .init(delay: .milliseconds(80)),
            Self.tailnet.url: .init(delay: .milliseconds(20)),
            Self.lan.url: .init(delay: .milliseconds(1_500)),
        ])
        let endpoints = endpoints(addresses)
        let clock = addresses.clock
        async let chosen = endpoints.race()
        await clock.waitForSleepers(4)
        clock.advance(by: .milliseconds(20))
        await clock.waitForSleepers(4)
        // To the end of the grace, short of the local answer.
        clock.advance(by: .milliseconds(300))
        #expect(await chosen == Self.tailnet)
        #expect(clock.now.offset < .milliseconds(1_500))
    }

    @Test("a local answer from first place is taken at once")
    func localFirst() async {
        let addresses = Addresses([
            Self.public_.url: .init(delay: .seconds(1)),
            Self.lan.url: .init(delay: .milliseconds(5)),
        ])
        let endpoints = endpoints(addresses)
        let clock = addresses.clock
        async let chosen = endpoints.race()
        await clock.waitForSleepers(3)
        clock.advance(by: .milliseconds(5))
        #expect(await chosen == Self.lan)
        #expect(clock.now.offset == .milliseconds(5))
    }

    @Test("a different server at one of the addresses is never used")
    func wrongServer() async {
        let addresses = Addresses([
            Self.lan.url: .init(serverID: "someone-elses-server"),
            Self.public_.url: .init(),
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
            Self.lan.url: .init(proof: proof),
            Self.public_.url: .init(),
        ])
        let endpoints = endpoints(addresses, current: Self.lan.url)
        #expect(await endpoints.race() == Self.public_)
        #expect(try await endpoints.provenBaseURL() == Self.public_.url)

        let alone = self.endpoints(addresses, current: Self.lan.url, candidates: [Self.lan])
        await #expect(throws: TracksideError.unproven) { try await alone.provenBaseURL() }
    }

    @Test("a key is never sent to an address that hasn't proven itself")
    func unprovenSendsNothing() async {
        let addresses = Addresses([Self.lan.url: .init(proof: .none)])
        let endpoints = endpoints(addresses, current: Self.lan.url, candidates: [Self.lan])
        let stub = StubTracksideTransport(body: APIExamples.me)
        let client = TracksideClient(serverID: "home", endpoints: endpoints, apiKey: TracksideTesting.apiKey, mediaToken: nil, transport: stub)
        await #expect(throws: TracksideError.unproven) { try await client.me() }
        await #expect(throws: TracksideError.unproven) { try await client.downloadRequest(songID: "s1") }
        #expect(stub.requests.isEmpty)
    }

    @Test("the challenge names this device and the address asked, never the key")
    func challengeQuery() async throws {
        let addresses = Addresses([Self.lan.url: .init()])
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
        let addresses = Addresses([plain.url: .init()])
        let endpoints = endpoints(addresses, current: plain.url, candidates: [plain])
        #expect(await endpoints.race() == nil)
        #expect(addresses.askedCount == 0)
    }

    @Test("a plain HTTP address is proven again after a while, in case the network changed unseen")
    func plainProofExpires() async throws {
        let addresses = Addresses([Self.lan.url: .init()])
        let endpoints = endpoints(addresses, current: Self.lan.url, candidates: [Self.lan], plainHTTPProofLifetime: .seconds(50))
        _ = try await endpoints.provenBaseURL()
        addresses.clock.advance(by: .seconds(49))
        _ = try await endpoints.provenBaseURL()
        #expect(addresses.askedCount == 1)
        addresses.clock.advance(by: .seconds(1))
        _ = try await endpoints.provenBaseURL()
        #expect(addresses.askedCount == 2)
    }

    @Test("a network change races again and moves to what answers now")
    func pathChange() async {
        let addresses = Addresses([
            Self.public_.url: .init(),
            Self.lan.url: .init(),
        ])
        let endpoints = endpoints(addresses)
        await endpoints.race()
        #expect(endpoints.baseURL == Self.lan.url)
        // Left home: on the new network something else answers for the home address.
        addresses.set([
            Self.public_.url: .init(),
            Self.lan.url: .init(proof: .none),
        ])
        await endpoints.pathChanged()
        #expect(endpoints.baseURL == Self.public_.url)
    }

    @Test("races asked for at once share one")
    func joined() async {
        let addresses = Addresses([Self.lan.url: .init(delay: .milliseconds(50))])
        let endpoints = endpoints(addresses)
        async let both = endpoints.raceTwice()
        // The local answer on its way and the timeout: one race, not two.
        await addresses.clock.waitForSleepers(2)
        // Queued behind the second race, so it has found the first by the time this returns.
        _ = await endpoints.candidates
        addresses.clock.advance(by: .milliseconds(50))
        #expect(await both == [Self.lan, Self.lan])
        #expect(addresses.askedCount == 3)
    }

    @Test("one failure doesn't race; two in a row do")
    func failures() async throws {
        let addresses = Addresses([Self.lan.url: .init(delay: .milliseconds(5))])
        let endpoints = endpoints(addresses)
        await endpoints.recordFailure()
        await endpoints.recordSuccess()
        await endpoints.recordFailure()
        // A race started here would have asked every address by now.
        try await Task.sleep(for: .milliseconds(50))
        #expect(addresses.askedCount == 0)
        await endpoints.recordFailure()
        await addresses.clock.waitForSleepers(2)
        addresses.clock.advance(by: .milliseconds(5))
        // The race runs in the background; the suite's time limit bounds the wait.
        while endpoints.baseURL != Self.lan.url {
            try await Task.sleep(for: .milliseconds(5))
        }
        #expect(addresses.askedCount == 3)
    }
}

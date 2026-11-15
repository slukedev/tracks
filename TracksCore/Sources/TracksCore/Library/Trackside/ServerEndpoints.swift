import Foundation
import Synchronization

/// The addresses one Trackside server can be reached at, and which one Tracks uses right now.
///
/// A server at home is reachable on the local network, over Tailscale and perhaps on a public
/// address, all at once. Each is asked in parallel and the answer is kept until the network
/// changes or requests start failing: the local address when it answers within a moment of
/// the first, since it's the fastest for audio, otherwise whichever answered first. The same
/// device key works on all of them.
///
/// An address is used only once it has proven it is the real server
/// (``TracksideEndpointProof``), and never over plain HTTP unless it is a nearby one, so a
/// stranger's network that answers for a home address never sees the key.
public actor ServerEndpoints {
    /// The server's own id. An answer from any other server is ignored, so a different
    /// server at the same home address is never used by mistake.
    public nonisolated let serverUUID: String
    public private(set) var candidates: [TracksideServerURL]
    /// What each address has to prove it knows before it is used.
    public nonisolated let proofKey: TracksideEndpointProof.Key
    /// Fingerprints of the server's self-signed addresses, for the transports to hold to.
    public nonisolated let pins: TracksidePins

    /// How long a local answer is waited for after the first answer from elsewhere.
    public let localGrace: Duration
    /// How long each address has to answer.
    public let timeout: Duration
    /// How long a plain HTTP address stays proven without a new race, in case a change of
    /// network went unnoticed.
    public let plainHTTPProofLifetime: Duration

    /// Read without waiting on the actor, since media addresses are built synchronously.
    private nonisolated let current: Mutex<URL>
    private let probe: @Sendable (TracksideServerURL, Duration, [URLQueryItem]) async throws -> TracksidePing
    private var failures = 0
    private var racing: Task<TracksideServerURL?, Never>?
    private var provenAt: ContinuousClock.Instant?

    /// - Parameters:
    ///   - current: the address to use once proven, such as the last one that worked.
    ///   - probe: asks one address who it is, with the challenge's query.
    ///     ``TracksideClient/ping(at:query:timeout:transport:)`` in the app.
    public init(
        serverUUID: String,
        candidates: [TracksideServerURL],
        current: URL,
        proofKey: TracksideEndpointProof.Key,
        pins: TracksidePins? = nil,
        localGrace: Duration = .milliseconds(300),
        timeout: Duration = .seconds(2),
        plainHTTPProofLifetime: Duration = .seconds(600),
        probe: @escaping @Sendable (TracksideServerURL, Duration, [URLQueryItem]) async throws -> TracksidePing
    ) {
        self.serverUUID = serverUUID
        self.candidates = candidates
        self.proofKey = proofKey
        self.pins = pins ?? TracksidePins(candidates)
        self.current = Mutex(current)
        self.localGrace = localGrace
        self.timeout = timeout
        self.plainHTTPProofLifetime = plainHTTPProofLifetime
        self.probe = probe
    }

    /// Endpoints that ask over `transport`, which should hold to ``pins``.
    public init(serverUUID: String, candidates: [TracksideServerURL], current: URL, proofKey: TracksideEndpointProof.Key, pins: TracksidePins, transport: any TracksideTransport) {
        self.init(serverUUID: serverUUID, candidates: candidates, current: current, proofKey: proofKey, pins: pins) { candidate, timeout, query in
            try await TracksideClient.ping(at: candidate.url, query: query, timeout: timeout, transport: transport)
        }
    }

    /// The address requests go to now. Media addresses are built from it; anything that
    /// carries the device key asks ``provenBaseURL()`` instead.
    public nonisolated var baseURL: URL {
        current.withLock { $0 }
    }

    /// The address in use, once it has proven it is the server: after the last race, and
    /// for a plain HTTP address within ``plainHTTPProofLifetime`` of it. Races when it hasn't.
    /// - Throws: ``TracksideError/unproven`` when no address could prove it.
    public func provenBaseURL() async throws -> URL {
        if let provenAt, isFresh(provenAt) { return baseURL }
        guard await race() != nil else { throw TracksideError.unproven }
        return baseURL
    }

    private func isFresh(_ provenAt: ContinuousClock.Instant) -> Bool {
        baseURL.scheme?.lowercased() == "https" || ContinuousClock.now - provenAt < plainHTTPProofLifetime
    }

    /// Takes the server's latest list of addresses, as `/me` gives them on each connect.
    public func update(candidates: [TracksideServerURL]) {
        guard !candidates.isEmpty else { return }
        self.candidates = candidates
        pins.set(candidates)
    }

    /// Asks every address and switches to the best one that proves it is the server. A race
    /// already running is joined rather than started again.
    /// - Returns: the address chosen, or nil when none proved it, which leaves nothing proven.
    @discardableResult
    public func race() async -> TracksideServerURL? {
        if let racing { return await racing.value }
        provenAt = nil
        let task = Task { [candidates, serverUUID, proofKey, localGrace, timeout, probe] in
            await Self.pick(from: candidates, serverUUID: serverUUID, proofKey: proofKey, localGrace: localGrace, timeout: timeout, probe: probe)
        }
        racing = task
        let chosen = await task.value
        racing = nil
        failures = 0
        if let chosen {
            current.withLock { $0 = chosen.url }
            provenAt = .now
        }
        return chosen
    }

    /// The network changed: Wi-Fi joined or left, a VPN came up. Nothing is sent with the key
    /// until an address proves itself on the new network.
    public func pathChanged() async {
        provenAt = nil
        await race()
    }

    /// A request couldn't reach the server. Two in a row start a race in the background, so
    /// the failing request isn't held up by it.
    public func recordFailure() {
        failures += 1
        guard failures >= 2, racing == nil else { return }
        failures = 0
        provenAt = nil
        Task { await self.race() }
    }

    public func recordSuccess() {
        failures = 0
    }

    private enum Outcome: Sendable {
        case answered(TracksideServerURL)
        case failed(TracksideServerURL)
        case graceOver
        case timeUp
    }

    static func pick(
        from candidates: [TracksideServerURL],
        serverUUID: String,
        proofKey: TracksideEndpointProof.Key,
        localGrace: Duration,
        timeout: Duration,
        probe: @escaping @Sendable (TracksideServerURL, Duration, [URLQueryItem]) async throws -> TracksidePing
    ) async -> TracksideServerURL? {
        let usable = candidates.filter { TracksideEndpointProof.allowsCredentials(to: $0.url) }
        guard !usable.isEmpty else { return nil }
        return await withTaskGroup(of: Outcome.self) { group in
            for candidate in usable {
                group.addTask {
                    let nonce = TracksideEndpointProof.nonce()
                    guard let query = TracksideEndpointProof.query(key: proofKey, nonce: nonce, for: candidate.url),
                          let ping = try? await probe(candidate, timeout, query),
                          ping.serverID.caseInsensitiveCompare(serverUUID) == .orderedSame,
                          TracksideEndpointProof.verify(ping.proof, key: proofKey, serverID: ping.serverID, url: candidate.url, nonce: nonce)
                    else { return .failed(candidate) }
                    return .answered(candidate)
                }
            }
            group.addTask {
                try? await Task.sleep(for: timeout)
                return .timeUp
            }
            var waiting = usable.count
            var localWaiting = usable.count(where: { $0.kind == .lan })
            var fastest: TracksideServerURL?
            for await outcome in group {
                switch outcome {
                case .answered(let candidate):
                    waiting -= 1
                    if candidate.kind == .lan {
                        group.cancelAll()
                        return candidate
                    }
                    if fastest == nil {
                        fastest = candidate
                        group.addTask {
                            try? await Task.sleep(for: localGrace)
                            return .graceOver
                        }
                    }
                case .failed(let candidate):
                    waiting -= 1
                    if candidate.kind == .lan { localWaiting -= 1 }
                case .graceOver, .timeUp:
                    group.cancelAll()
                    return fastest
                }
                if waiting == 0 || (fastest != nil && localWaiting == 0) {
                    group.cancelAll()
                    return fastest
                }
            }
            return fastest
        }
    }
}

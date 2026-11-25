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
    /// When the address in use last proved itself, on ``elapsed``.
    private var provenAt: Duration?
    /// Time since these endpoints were made, and a wait on the same clock. A clock of the
    /// caller's, so the grace, the timeout and a proof's lifetime are measured as it keeps time.
    private let elapsed: @Sendable () -> Duration
    private let sleep: @Sendable (Duration) async throws -> Void

    /// - Parameters:
    ///   - current: the address to use once proven, such as the last one that worked.
    ///   - clock: what the grace, the timeout and a proof's lifetime are measured on.
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
        clock: some Clock<Duration> & Sendable = ContinuousClock(),
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
        let start = clock.now
        self.elapsed = { start.duration(to: clock.now) }
        self.sleep = { try await clock.sleep(for: $0) }
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

    private func isFresh(_ provenAt: Duration) -> Bool {
        let isHTTPS = baseURL.scheme?.lowercased() == "https"
        if requiresHTTPS && !isHTTPS { return false }
        return isHTTPS || elapsed() - provenAt < plainHTTPProofLifetime
    }

    /// Set once the server refused the key over plain HTTP (`https_required`): from then on
    /// only its https addresses are used.
    public private(set) var requiresHTTPS = false

    /// Takes the server's latest list of addresses, as `/me` and `/server/urls` give them.
    /// Only addresses Tracks may send the key to are kept (``TracksideServerURLs/trusted(_:)``),
    /// and none of them is used before it proves itself in a race.
    public func update(candidates: [TracksideServerURL]) {
        let trusted = TracksideServerURLs.trusted(candidates)
        guard !trusted.isEmpty else { return }
        self.candidates = trusted
        pins.set(trusted)
    }

    /// Adds addresses the server named, behind the ones known. None is used before it proves
    /// itself.
    public func add(_ more: [TracksideServerURL]) {
        update(candidates: candidates + more)
    }

    /// Puts addresses the server named ahead of the known ones; one on both lists takes what
    /// the server just said of it. None is used before it proves itself.
    public func prefer(_ named: [TracksideServerURL]) {
        update(candidates: named + candidates)
    }

    /// The server asked for https. Switches to one of its https addresses that proves
    /// itself, without asking anyone for a password again.
    /// - Returns: the address chosen, or nil when the server has no https address that answers.
    @discardableResult
    public func requireHTTPS() async -> TracksideServerURL? {
        requiresHTTPS = true
        provenAt = nil
        return await race()
    }

    /// Asks every address and switches to the best one that proves it is the server. A race
    /// already running is joined rather than started again.
    /// - Returns: the address chosen, or nil when none proved it, which leaves nothing proven.
    @discardableResult
    public func race() async -> TracksideServerURL? {
        if let racing { return await racing.value }
        provenAt = nil
        let usable = requiresHTTPS ? candidates.filter { $0.url.scheme?.lowercased() == "https" } : candidates
        let task = Task { [serverUUID, proofKey, localGrace, timeout, sleep, probe] in
            await Self.pick(from: usable, serverUUID: serverUUID, proofKey: proofKey, localGrace: localGrace, timeout: timeout, sleep: sleep, probe: probe)
        }
        racing = task
        let chosen = await task.value
        racing = nil
        failures = 0
        if let chosen {
            current.withLock { $0 = chosen.url }
            provenAt = elapsed()
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

    static func pick(
        from candidates: [TracksideServerURL],
        serverUUID: String,
        proofKey: TracksideEndpointProof.Key,
        localGrace: Duration,
        timeout: Duration,
        sleep: @escaping @Sendable (Duration) async throws -> Void,
        probe: @escaping @Sendable (TracksideServerURL, Duration, [URLQueryItem]) async throws -> TracksidePing
    ) async -> TracksideServerURL? {
        let usable = candidates.filter { TracksideEndpointProof.allowsCredentials(to: $0.url) }
        return await AddressRace.pick(from: usable, isLocal: { $0.kind == .lan }, localGrace: localGrace, timeout: timeout, sleep: sleep) { candidate in
            await proves(candidate, serverUUID: serverUUID, proofKey: proofKey, timeout: timeout, probe: probe)
        }
    }

    /// Whether an address answers as this server, with the proof only it can give.
    static func proves(
        _ candidate: TracksideServerURL,
        serverUUID: String,
        proofKey: TracksideEndpointProof.Key,
        timeout: Duration,
        probe: @Sendable (TracksideServerURL, Duration, [URLQueryItem]) async throws -> TracksidePing
    ) async -> Bool {
        let nonce = TracksideEndpointProof.nonce()
        guard let query = TracksideEndpointProof.query(key: proofKey, nonce: nonce, for: candidate.url),
              let ping = try? await probe(candidate, timeout, query),
              ping.serverID.caseInsensitiveCompare(serverUUID) == .orderedSame
        else { return false }
        return TracksideEndpointProof.verify(ping.proof, key: proofKey, serverID: ping.serverID, url: candidate.url, nonce: nonce)
    }
}

extension TracksideServerURLs {
    /// The addresses Tracks may send this device's key to, in the server's order, each once:
    /// https anywhere, plain HTTP only nearby, nothing else.
    public static func trusted(_ urls: [TracksideServerURL]) -> [TracksideServerURL] {
        var seen: Set<String> = []
        return urls.filter { candidate in
            guard TracksideEndpointProof.allowsCredentials(to: candidate.url),
                  candidate.url.host(percentEncoded: false)?.isEmpty == false
            else { return false }
            let scheme = candidate.url.scheme?.lowercased() ?? ""
            let key = scheme + "://" + (TracksideEndpointProof.authority(of: candidate.url) ?? "") + candidate.url.path(percentEncoded: true)
            return seen.insert(key).inserted
        }
    }

    /// Whether `urls` differs from what's known, beyond order.
    public static func differs(_ urls: [TracksideServerURL], from known: [TracksideServerURL]?) -> Bool {
        Set(trusted(urls)) != Set(known ?? [])
    }
}

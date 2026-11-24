import Foundation
import Synchronization

/// The addresses a Subsonic server can be reached at (at home, over Tailscale, on the
/// internet) and the one in use, as Trackside's are kept by ``ServerEndpoints``.
///
/// Each address is asked at once, without credentials, whether it answers as a Subsonic
/// server, and the nearby one wins when it answers within a moment of the first. The race
/// runs again when the network changes, and after two requests in a row couldn't reach the
/// server. Credentials go only to https addresses, or plain HTTP ones nearby.
///
/// Subsonic has no way to prove it's the right server, so on a nearby plain HTTP address a
/// hostile network that answers for it could see the token. That's why https is the default
/// for every address that isn't nearby, and why an API key, which can be revoked on the
/// server, is preferred where the server offers one.
public final class SubsonicEndpoints: Sendable {
    public let candidates: [TracksideServerURL]
    public let localGrace: Duration
    public let timeout: Duration

    private struct State {
        var failures = 0
        var racing: Task<URL?, Never>?
    }

    private let current: Mutex<URL>
    private let state = Mutex(State())
    private let probe: @Sendable (URL, Duration) async -> Bool

    /// - Parameter probe: whether an address answers as the server, within the timeout.
    public init(
        candidates: [TracksideServerURL],
        current: URL,
        localGrace: Duration = .milliseconds(300),
        timeout: Duration = .seconds(3),
        probe: @escaping @Sendable (URL, Duration) async -> Bool
    ) {
        let trusted = TracksideServerURLs.trusted(candidates)
        let start = trusted.contains { $0.url == current } ? current : (trusted.first?.url ?? current)
        self.candidates = trusted
        self.current = Mutex(start)
        self.localGrace = localGrace
        self.timeout = timeout
        self.probe = probe
    }

    /// Endpoints for a server's addresses, probed through `transport`.
    public convenience init(server: SubsonicServer, transport: any SubsonicTransport = URLSessionTransport()) {
        self.init(candidates: server.addresses, current: server.url) { url, timeout in
            await SubsonicClient.answersAsSubsonic(url, timeout: timeout, transport: transport)
        }
    }

    /// The address requests go to now.
    public var baseURL: URL {
        current.withLock { $0 }
    }

    /// Whether there's more than one address to choose between.
    public var hasChoice: Bool { candidates.count > 1 }

    /// Asks every address and moves to the best one that answers. A race already running is
    /// joined. With one address there's nothing to choose, and nothing is asked.
    @discardableResult
    public func race() async -> URL? {
        guard hasChoice else { return baseURL }
        let task: Task<URL?, Never> = state.withLock { state in
            if let running = state.racing { return running }
            let task = Task { [candidates, localGrace, timeout, probe] in
                await AddressRace.pick(
                    from: candidates,
                    isLocal: { $0.kind == .lan },
                    localGrace: localGrace,
                    timeout: timeout,
                    sleep: { try await Task.sleep(for: $0) },
                    probe: { await probe($0.url, timeout) }
                )?.url
            }
            state.racing = task
            return task
        }
        let chosen = await task.value
        state.withLock { state in
            if state.racing == task { state.racing = nil }
            state.failures = 0
        }
        if let chosen { current.withLock { $0 = chosen } }
        return chosen
    }

    /// The network changed: the best address may be another one now.
    public func pathChanged() async {
        await race()
    }

    /// A request couldn't reach the server. Two in a row start a race in the background.
    public func recordFailure() {
        let shouldRace = state.withLock { state in
            state.failures += 1
            guard state.failures >= 2, state.racing == nil else { return false }
            state.failures = 0
            return true
        }
        guard shouldRace, hasChoice else { return }
        Task { await self.race() }
    }

    public func recordSuccess() {
        state.withLock { $0.failures = 0 }
    }
}

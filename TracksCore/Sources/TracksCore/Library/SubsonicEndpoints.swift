import Foundation
import Synchronization

/// The addresses a Subsonic server can be reached at (at home, over Tailscale, on the
/// internet) and the one in use, as Trackside's are kept by ``ServerEndpoints``.
///
/// Each address is asked at once, without credentials, whether it answers as a Subsonic
/// server, and the nearby one wins when it answers within a moment of the first. The race
/// runs again when the network changes, and after two requests in a row couldn't reach the
/// server.
///
/// Subsonic has no way to prove it's the right server, so on a plain HTTP address whatever
/// answers there would get the credentials. Where the server has an https address, its
/// nearby plain HTTP ones are only raced, and only get credentials, on a network recorded as
/// its home (``SubsonicCredentialRule``); anywhere else the https address is used.
public final class SubsonicEndpoints: Sendable {
    public let candidates: [TracksideServerURL]
    public let localGrace: Duration
    public let timeout: Duration
    public let usesAPIKey: Bool

    private struct State {
        var failures = 0
        var racing: Task<URL?, Never>?
    }

    private let current: Mutex<URL>
    private let state = Mutex(State())
    private let homes: Mutex<[String]>
    private let probe: @Sendable (URL, Duration) async -> Bool
    private let network: @Sendable () -> String?

    /// - Parameters:
    ///   - homes: the networks recorded as the server's home (``NetworkIdentity``).
    ///   - network: the network the device is on now.
    ///   - probe: whether an address answers as the server, within the timeout.
    public init(
        candidates: [TracksideServerURL],
        current: URL,
        usesAPIKey: Bool = false,
        homes: [String] = [],
        network: @escaping @Sendable () -> String? = { CurrentNetwork.shared.identity },
        localGrace: Duration = .milliseconds(300),
        timeout: Duration = .seconds(3),
        probe: @escaping @Sendable (URL, Duration) async -> Bool
    ) {
        let trusted = TracksideServerURLs.trusted(candidates)
        let start = trusted.contains { $0.url == current } ? current : (trusted.first?.url ?? current)
        self.candidates = trusted
        self.current = Mutex(start)
        self.usesAPIKey = usesAPIKey
        self.homes = Mutex(homes)
        self.network = network
        self.localGrace = localGrace
        self.timeout = timeout
        self.probe = probe
    }

    /// Endpoints for a server's addresses, probed through `transport`.
    public convenience init(server: SubsonicServer, transport: any SubsonicTransport = URLSessionTransport()) {
        self.init(candidates: server.addresses, current: server.url, usesAPIKey: server.usesAPIKey, homes: server.homeNetworks ?? []) { url, timeout in
            await SubsonicClient.answersAsSubsonic(url, timeout: timeout, transport: transport)
        }
    }

    public var hasHTTPS: Bool {
        candidates.contains { $0.url.scheme?.lowercased() == "https" }
    }

    /// Whether the device is on one of the server's home networks now.
    public var isHome: Bool {
        SubsonicCredentialRule.isHome(network(), of: homes.withLock { $0 })
    }

    /// Whether a request to `url` may carry the credentials now.
    public func allowsCredentials(to url: URL) -> Bool {
        SubsonicCredentialRule.allows(url, usesAPIKey: usesAPIKey, hasHTTPS: hasHTTPS, onHomeNetwork: isHome)
    }

    /// Records the network now as one of the server's homes when a nearby plain HTTP address
    /// answers here, keeping the last four. Only for the form adding or editing the server:
    /// nothing else ever records a home.
    /// - Returns: the homes, when this network was added to them.
    @discardableResult
    public func recordHomeIfNearbyAnswers() async -> [String]? {
        guard network() != nil else { return nil }
        let nearby = candidates.filter { $0.url.scheme?.lowercased() == "http" && NearbyAddress.isNearby($0.url) }
        guard !nearby.isEmpty, await pick(from: nearby) != nil, let here = network() else { return nil }
        return homes.withLock { homes in
            guard !homes.contains(here) else { return nil }
            homes = Array((homes + [here]).suffix(4))
            return homes
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
        let usable = candidates.filter { allowsCredentials(to: $0.url) }
        let task: Task<URL?, Never> = state.withLock { state in
            if let running = state.racing { return running }
            let task = Task { [self] in await pick(from: usable) }
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

    private func pick(from choices: [TracksideServerURL]) async -> URL? {
        await AddressRace.pick(
            from: choices,
            isLocal: { $0.kind == .lan },
            localGrace: localGrace,
            timeout: timeout,
            sleep: { try await Task.sleep(for: $0) },
            probe: { [probe, timeout] in await probe($0.url, timeout) }
        )?.url
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

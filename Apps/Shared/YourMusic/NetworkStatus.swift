import Foundation
import Network
import Observation
import TracksCore

/// Whether the iPhone is online, and on a metered connection, so server music that isn't
/// downloaded can be shown as out of reach.
@MainActor
@Observable
final class NetworkStatus {
    private(set) var isOnline = true
    /// Cellular, or a hotspot: where downloads wait unless allowed.
    private(set) var isExpensive = false

    /// Called when the connection comes back or moves, from Wi-Fi to cellular or one network
    /// to another: a server only reachable on one of them may have come or gone.
    @ObservationIgnored var onChange: (() -> Void)?
    @ObservationIgnored private let monitor = NWPathMonitor()
    /// The interfaces in use at the last look, so a path updated with nothing new is let be.
    @ObservationIgnored private var interfaces: [String]?
    @ObservationIgnored private var settling: Task<Void, Never>?

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            // At once, before the move settles: a nearby address is somebody else's the
            // moment the network changes.
            CurrentNetwork.shared.identity = Self.identity(of: path)
            let online = path.status == .satisfied
            let expensive = path.isExpensive
            let interfaces = path.availableInterfaces.map(\.name)
            Task { @MainActor in
                guard let self else { return }
                let moved = self.interfaces != nil && (online != self.isOnline || interfaces != self.interfaces)
                if online != self.isOnline { self.isOnline = online }
                if expensive != self.isExpensive { self.isExpensive = expensive }
                self.interfaces = interfaces
                if moved { self.settle(online: online) }
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.luke.tracks.network"))
    }

    /// Which Wi-Fi or wired network the path is on (``NetworkIdentity``). Nil on cellular.
    nonisolated private static func identity(of path: NWPath) -> String? {
        guard path.status == .satisfied, path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet) else { return nil }
        let names = Set(path.availableInterfaces.filter { $0.type == .wifi || $0.type == .wiredEthernet }.map(\.name))
        let gateways = path.gateways.compactMap { endpoint -> String? in
            guard case .hostPort(let host, _) = endpoint else { return nil }
            return host.debugDescription
        }
        return NetworkIdentity.make(gateways: gateways, prefixes: NetworkIdentity.localPrefixes(onInterfaces: names))
    }

    /// Passes a move on once the path has held still for a moment. Walking out of Wi-Fi
    /// flips between networks a few times in a second, and each move asks every server again.
    private func settle(online: Bool) {
        settling?.cancel()
        settling = nil
        guard online else { return }
        settling = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self, self.isOnline else { return }
            self.settling = nil
            self.onChange?()
        }
    }

    deinit {
        monitor.cancel()
    }
}

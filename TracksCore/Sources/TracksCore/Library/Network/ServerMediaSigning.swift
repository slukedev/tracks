import Foundation
import Synchronization

/// Signs a server's cover addresses just before they're fetched.
///
/// Cover addresses travel without credentials: they're cache keys, they reach Now Playing,
/// and they're written to logs. The image loader asks here for the signed address of each
/// one it fetches, and the server it belongs to signs it with a fresh salt, its API key or
/// its media token. An address no connected server claims goes out as it is.
public final class ServerMediaSigning: Sendable {
    public static let shared = ServerMediaSigning()

    private let clients = Mutex<[String: any MusicServerClient]>([:])

    public init() {}

    public func register(_ client: any MusicServerClient, for serverID: String) {
        clients.withLock { $0[serverID] = client }
    }

    public func remove(_ serverID: String) {
        _ = clients.withLock { $0.removeValue(forKey: serverID) }
    }

    /// The address to fetch: signed by the server it belongs to, or unchanged.
    public func authorize(_ url: URL) -> URL {
        let all = clients.withLock { Array($0.values) }
        for client in all {
            if let signed = client.authorize(url) { return signed }
        }
        return url
    }
}

import Foundation

/// `GET /api/v1/server/urls`: every address a paired device can reach the server at, so a
/// phone hears about a new public address without being paired again.
///
/// The list is trusted only as far as where it came from: it's read from an address that
/// has proven it is the server, any address on it is used only once it proves itself too
/// (``ServerEndpoints``), an https address must have a certificate the system trusts or the
/// fingerprint the list gives, and plain HTTP addresses that aren't nearby are dropped.
public struct TracksideServerURLs: Codable, Sendable, Hashable {
    public var serverID: String
    public var urls: [TracksideServerURL]
    public var updatedAt: Date?

    public init(serverID: String, urls: [TracksideServerURL], updatedAt: Date? = nil) {
        self.serverID = serverID
        self.urls = urls
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case urls
        case serverID = "server_id"
        case updatedAt = "updated_at"
    }

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

/// What a conditional `GET /server/urls` found.
public enum TracksideServerURLsAnswer: Sendable, Equatable {
    /// The list is the one the ETag stands for.
    case unchanged
    case changed(TracksideServerURLs, etag: String?)
}

/// The `server_urls` event: the list changed, so a device fetches it again. It carries the
/// new ETag where the server gives it, so a device that already has that list asks nothing.
public struct TracksideServerURLsEvent: Codable, Sendable, Hashable {
    public var etag: String?

    public init(etag: String?) {
        self.etag = etag
    }
}

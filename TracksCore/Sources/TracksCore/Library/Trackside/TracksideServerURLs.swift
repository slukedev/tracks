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
    /// Names the list: the same list always has the same version. Its strong ETag.
    public var version: String?

    public init(serverID: String, urls: [TracksideServerURL], version: String? = nil) {
        self.serverID = serverID
        self.urls = urls
        self.version = version
    }

    enum CodingKeys: String, CodingKey {
        case urls, version
        case serverID = "server_id"
    }

    /// The ETag the server sends with this list.
    public var etag: String? { version.map(TracksideServerURLs.etag(forVersion:)) }

    static func etag(forVersion version: String) -> String {
        "\"\(version)\""
    }
}

/// What a conditional `GET /server/urls` found.
public enum TracksideServerURLsAnswer: Sendable, Equatable {
    /// The list is the one the ETag stands for.
    case unchanged
    case changed(TracksideServerURLs, etag: String?)
}

/// The `server_urls` event: the list changed, so a device fetches it again. It carries the
/// new list's version, so a device that already has that list asks nothing.
public struct TracksideServerURLsEvent: Codable, Sendable, Hashable {
    public var version: String?

    public init(version: String?) {
        self.version = version
    }

    /// The ETag of the list the event announces, to compare with the one kept.
    public var etag: String? { version.map(TracksideServerURLs.etag(forVersion:)) }
}

extension TracksideErrorEnvelope {
    /// The https addresses a `403 https_required` names (possibly none), or nil for any
    /// other refusal.
    static func httpsRequired(status: Int, body: Data) -> [TracksideServerURL]? {
        guard status == 403,
              let refusal = try? TracksideJSON.decoder.decode(TracksideErrorEnvelope.self, from: body).error,
              refusal.code == "https_required"
        else { return nil }
        return (refusal.details?.httpsURLs ?? []).filter { $0.url.scheme?.lowercased() == "https" }
    }
}

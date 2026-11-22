import Foundation

/// A server's address as someone types it, made into one Tracks can reach: a music server, or
/// Lidarr.
///
/// Home servers rarely have https, and people type what their browser shows without the
/// scheme: "192.168.1.223:5274". So an address without one gains http only when it can be on
/// the home network alone (``TracksideEndpointProof/isNearby(_:)``, the rule the server uses
/// too), whatever its port. Anything else gains https: "music.example.com:4533" is on the
/// internet, where the password's token must not cross in the clear. Spaces around it and
/// slashes after it go. An address that says its scheme keeps it.
public enum ServerAddress {
    /// The address to connect to, or nil for one that can't be a web server's.
    public static func url(from text: String) -> URL? {
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // Asked before the slashes go, so "http://" alone isn't read as a host named "http".
        let hasScheme = trimmed.contains("://")
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        guard !trimmed.isEmpty else { return nil }

        if hasScheme {
            guard var components = URLComponents(string: trimmed),
                  let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https"
            else { return nil }
            components.scheme = scheme
            return reachable(components)
        }

        // A bare IPv6 address has colons of its own: it goes in brackets before a port can follow.
        if trimmed.filter({ $0 == ":" }).count > 1, !trimmed.hasPrefix("[") {
            trimmed = "[\(trimmed)]"
        }
        guard var components = URLComponents(string: "http://" + trimmed), let url = reachable(components) else { return nil }
        components.scheme = isAtHome(url) ? "http" : "https"
        return reachable(components)
    }

    /// Whether an address sends what it carries unencrypted beyond the home network, which
    /// Tracks never connects to.
    public static func isExposed(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "http" && !TracksideEndpointProof.isNearby(url)
    }

    /// Whether an address typed without a scheme is a home server's, reached over http.
    static func isAtHome(_ url: URL) -> Bool {
        url.port != 443 && TracksideEndpointProof.isNearby(url)
    }

    private static func reachable(_ components: URLComponents) -> URL? {
        guard let host = components.host, !host.isEmpty, let url = components.url else { return nil }
        return url
    }
}

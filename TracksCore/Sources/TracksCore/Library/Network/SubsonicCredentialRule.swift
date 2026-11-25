import Foundation

/// Where a Subsonic server's credentials may go. Subsonic has no way for a server to prove
/// it's the right one, so on plain HTTP whatever answers at the address gets them.
///
/// - https: always.
/// - Loopback: always.
/// - An API key never goes over plain HTTP anywhere else: it's the whole account until it's
///   revoked.
/// - A server with an https address: plain HTTP only to a nearby address, and only on a
///   network recorded as its home (``NetworkIdentity``). Elsewhere a café's 192.168.1.10 is
///   someone else's.
/// - A server with no https address at all: plain HTTP, as chosen when it was added, with a
///   word that it's for home.
public enum SubsonicCredentialRule {
    public static func allows(_ url: URL, usesAPIKey: Bool, hasHTTPS: Bool, onHomeNetwork: Bool) -> Bool {
        switch url.scheme?.lowercased() {
        case "https":
            return true
        case "http":
            if isLoopback(url) { return true }
            if usesAPIKey { return false }
            guard hasHTTPS else { return true }
            return NearbyAddress.isNearby(url) && onHomeNetwork
        default:
            return false
        }
    }

    public static func isLoopback(_ url: URL) -> Bool {
        guard var host = url.host(percentEncoded: false)?.lowercased() else { return false }
        if host.hasPrefix("[") { host = String(host.dropFirst().dropLast()) }
        if host == "localhost" || host == "::1" { return true }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy { UInt8($0) != nil } && parts[0] == "127"
    }

    /// Whether a network the device is on is one of a server's homes.
    public static func isHome(_ network: String?, of homes: [String]) -> Bool {
        guard let network else { return false }
        return homes.contains(network)
    }
}

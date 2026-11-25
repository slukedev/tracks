import Foundation

/// Which addresses are only reachable nearby, and so may be spoken to over plain HTTP.
///
/// Nearby: private (RFC 1918), link-local, loopback and carrier-grade NAT (100.64/10, which
/// Tailscale uses) IPv4 addresses; unique-local, link-local and loopback IPv6; a name without
/// a dot; and names under `.local`, `.lan`, `.internal`, `.home.arpa` or `.ts.net`. Trackside
/// decides the same way, so the two never disagree about where a key may go.
///
/// Everything else gets https. Credentials only ever cross plain HTTP to a nearby address.
public enum NearbyAddress {
    public static func isNearby(_ url: URL) -> Bool {
        guard let raw = url.host(percentEncoded: false)?.lowercased(), !raw.isEmpty else { return false }
        return isNearby(host: raw)
    }

    public static func isNearby(host raw: String) -> Bool {
        let host = raw.hasPrefix("[") ? String(raw.dropFirst().dropLast()) : raw
        guard !host.isEmpty else { return false }
        if let v4 = IPv4(host) { return v4.isNearby }
        if host.contains(":") { return IPv6Prefix(host)?.isNearby ?? false }
        let name = host.hasSuffix(".") ? String(host.dropLast()) : host
        guard let dot = name.lastIndex(of: ".") else { return true }
        let tld = name[name.index(after: dot)...]
        return ["local", "lan", "internal"].contains(tld) || name.hasSuffix(".home.arpa") || name.hasSuffix(".ts.net")
    }

    /// Credentials go over TLS, or over plain HTTP only to a nearby address.
    public static func allowsCredentials(to url: URL) -> Bool {
        switch url.scheme?.lowercased() {
        case "https": true
        case "http": isNearby(url)
        default: false
        }
    }

    /// Whether an address sends what it carries unencrypted beyond the home network.
    public static func isExposed(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "http" && !isNearby(url)
    }

    private struct IPv4 {
        let octets: [UInt8]

        init?(_ text: String) {
            let parts = text.split(separator: ".", omittingEmptySubsequences: false)
            guard parts.count == 4 else { return nil }
            let octets = parts.compactMap { part -> UInt8? in
                guard !part.isEmpty, part.allSatisfy(\.isASCII), part.allSatisfy(\.isNumber) else { return nil }
                return UInt8(part)
            }
            guard octets.count == 4 else { return nil }
            self.octets = octets
        }

        var isNearby: Bool {
            switch (octets[0], octets[1]) {
            case (10, _), (127, _): true
            case (172, 16...31), (192, 168), (169, 254): true
            case (100, 64...127): true
            default: false
            }
        }
    }

    /// The first 16 bits of an IPv6 address, which is all the nearby ranges need.
    private struct IPv6Prefix {
        let first: UInt16
        let isLoopback: Bool

        init?(_ text: String) {
            let address = text.split(separator: "%").first.map(String.init) ?? text
            isLoopback = address == "::1"
            if address.hasPrefix("::") {
                first = 0
                return
            }
            guard let head = address.split(separator: ":").first, let value = UInt16(head, radix: 16) else { return nil }
            first = value
        }

        var isNearby: Bool {
            isLoopback || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80
        }
    }
}

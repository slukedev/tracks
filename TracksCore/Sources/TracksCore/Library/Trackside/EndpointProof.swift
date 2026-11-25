import Foundation
import CryptoKit
import Synchronization

/// The endpoint proof (tracks-companion's docs/adr/0011-endpoint-proof.md): before Tracks sends a device
/// key or a QR pairing secret to one of a server's addresses, it asks `/ping` there with a
/// fresh challenge, and only an address that answers with the HMAC the real server can make
/// is used.
public enum TracksideEndpointProof {
    /// What the server has to prove it knows. Neither the key nor the secret leaves the
    /// device: the HMAC key is a hash of it that only the server also holds.
    public enum Key: Sendable, Hashable {
        /// This device's key, and the device id the server gave it.
        case device(id: String, apiKey: String)
        /// A QR code's offer id and secret.
        case offer(id: String, secret: String)

        var queryItem: URLQueryItem {
            switch self {
            case .device(let id, _): URLQueryItem(name: "device", value: id)
            case .offer(let id, _): URLQueryItem(name: "offer", value: id)
            }
        }

        var hmacKey: SymmetricKey {
            switch self {
            case .device(_, let apiKey):
                SymmetricKey(data: SHA256.hash(data: Data(apiKey.utf8)))
            case .offer(_, let secret):
                SymmetricKey(data: SHA256.hash(data: Data("offer qr".utf8) + [0] + Data(secret.utf8)))
            }
        }
    }

    /// A fresh challenge: 16 random bytes, base64url without padding.
    public static func nonce() -> String {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<16).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// The query a challenge adds to `/ping`.
    public static func query(key: Key, nonce: String, for url: URL) -> [URLQueryItem]? {
        guard let host = authority(of: url) else { return nil }
        return [URLQueryItem(name: "challenge", value: nonce), URLQueryItem(name: "host", value: host), key.queryItem]
    }

    /// The proof the real server gives for this challenge at this address, as lowercase hex.
    public static func expected(key: Key, serverID: String, url: URL, nonce: String) -> String? {
        guard let host = authority(of: url) else { return nil }
        let message = ["trackside endpoint proof v1", serverID, host, nonce].joined(separator: "\0")
        let mac = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key.hmacKey)
        return mac.map { String(format: "%02x", $0) }.joined()
    }

    /// Whether `proof` is the one only the real server can give, compared in constant time.
    public static func verify(_ proof: String?, key: Key, serverID: String, url: URL, nonce: String) -> Bool {
        guard let proof, let expected = expected(key: key, serverID: serverID, url: url, nonce: nonce) else { return false }
        let a = Array(proof.lowercased().utf8)
        let b = Array(expected.utf8)
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }

    /// `host` or `host:port` as the address is written: lowercased, IPv6 in brackets, and
    /// the port only when it isn't the scheme's own.
    public static func authority(of url: URL) -> String? {
        guard let host = url.host(percentEncoded: false)?.lowercased(), !host.isEmpty else { return nil }
        let bare = host.hasPrefix("[") ? String(host.dropFirst().dropLast()) : host
        let written = bare.contains(":") ? "[\(bare)]" : bare
        let defaultPort = url.scheme?.lowercased() == "https" ? 443 : 80
        guard let port = url.port, port != defaultPort else { return written }
        return "\(written):\(port)"
    }

    /// Whether an address names something only reachable nearby. See ``NearbyAddress``.
    public static func isNearby(_ url: URL) -> Bool {
        NearbyAddress.isNearby(url)
    }

    /// Credentials go over TLS, or over plain HTTP only to a nearby address.
    public static func allowsCredentials(to url: URL) -> Bool {
        NearbyAddress.allowsCredentials(to: url)
    }
}

/// The SHA-256 fingerprints of self-signed certificates a server's addresses use, by
/// `host:port`. A connection to an address with a fingerprint is accepted only when the
/// certificate matches it, whoever signed it; any other address gets the system's checks.
public final class TracksidePins: Sendable {
    private let pins = Mutex<[String: Data]>([:])

    public init(_ candidates: [TracksideServerURL] = []) {
        set(candidates)
    }

    /// Replaces the pins with the candidates' fingerprints.
    public func set(_ candidates: [TracksideServerURL]) {
        var next: [String: Data] = [:]
        for candidate in candidates where candidate.url.scheme?.lowercased() == "https" {
            guard let fingerprint = candidate.fingerprint.flatMap(Self.bytes(ofFingerprint:)),
                  let key = Self.key(candidate.url.host(percentEncoded: false), candidate.url.port)
            else { continue }
            next[key] = fingerprint
        }
        pins.withLock { $0 = next }
    }

    /// Nil when the address isn't pinned; otherwise whether the leaf certificate matches.
    public func accepts(host: String, port: Int?, leafCertificate der: Data) -> Bool? {
        guard let key = Self.key(host, port), let pin = pins.withLock({ $0[key] }) else { return nil }
        return Data(SHA256.hash(data: der)) == pin
    }

    /// `ab:cd:...` or plain hex, either case.
    static func bytes(ofFingerprint text: String) -> Data? {
        let hex = text.filter { $0 != ":" && !$0.isWhitespace }
        guard hex.count == 64 else { return nil }
        var out = Data(capacity: 32)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            out.append(byte)
            index = next
        }
        return out
    }

    private static func key(_ host: String?, _ port: Int?) -> String? {
        guard let host = host?.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]")), !host.isEmpty else { return nil }
        return "\(host):\(port ?? 443)"
    }
}

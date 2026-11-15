import Foundation

/// What a Trackside pairing QR code or `trackside://pair` link holds: which server, where it
/// can be reached, and the secret that pairs this device to the account that showed it.
public struct PairingPayload: Codable, Sendable, Hashable {
    public var version: Int
    public var serverID: String
    public var name: String?
    public var urls: [TracksideServerURL]
    /// The offer's id, which the address asked has to prove it made before ``code`` is sent.
    public var offerID: String
    /// Sent as the code to `POST /api/v1/pair/claim`.
    public var code: String
    public var expiresAt: Date

    public enum ParseError: Error, Equatable, Sendable {
        /// Not a Trackside pairing code at all.
        case notAPairingLink
        /// Made by a newer server than this build knows how to pair with.
        case unsupportedVersion(Int)
        case unreadable
        /// No address Tracks can reach the server at.
        case noAddresses
        case expired
    }

    public static let supportedVersion = 1

    /// What an address must prove before the secret is sent to it.
    public var proofKey: TracksideEndpointProof.Key {
        .offer(id: offerID, secret: code)
    }

    /// Reads a scanned QR code or an opened link.
    /// - Parameter now: offers past their expiry are refused, so nobody waits on a claim
    ///   that can only fail.
    public init(_ text: String, now: Date = .now) throws {
        guard let components = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "trackside",
              components.host?.lowercased() == "pair",
              let encoded = components.queryItems?.first(where: { $0.name == "d" })?.value
        else { throw ParseError.notAPairingLink }
        guard let data = Self.base64URLDecoded(encoded) else { throw ParseError.unreadable }
        let wire: Wire
        do {
            wire = try JSONDecoder().decode(Wire.self, from: data)
        } catch {
            throw ParseError.unreadable
        }
        guard wire.v == Self.supportedVersion else { throw ParseError.unsupportedVersion(wire.v) }
        let urls = wire.urls.filter { ["http", "https"].contains($0.url.scheme?.lowercased()) }
        guard !urls.isEmpty else { throw ParseError.noAddresses }
        let expiresAt = Date(timeIntervalSince1970: wire.exp)
        guard expiresAt > now else { throw ParseError.expired }
        self.version = wire.v
        self.serverID = wire.serverID
        self.name = wire.name
        self.urls = urls
        self.offerID = wire.offer
        self.code = wire.code
        self.expiresAt = expiresAt
    }

    public init(url: URL, now: Date = .now) throws {
        try self.init(url.absoluteString, now: now)
    }

    /// A code as someone types it from the dashboard, tidied: "q7rm 4x2k" is "Q7RM-4X2K".
    /// Nil for anything that can't be one.
    public static func typedCode(_ text: String) -> String? {
        let characters = text.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
        guard characters.count == 8 else { return nil }
        return "\(characters.prefix(4))-\(characters.suffix(4))"
    }

    static func base64URLDecoded(_ text: String) -> Data? {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        return Data(base64Encoded: base64)
    }

    private struct Wire: Decodable {
        let v: Int
        let serverID: String
        let name: String?
        let urls: [TracksideServerURL]
        let offer: String
        let code: String
        let exp: TimeInterval

        enum CodingKeys: String, CodingKey {
            case v, name, urls, offer, code, exp
            case serverID = "server_id"
        }
    }
}

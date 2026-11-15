import Testing
import Foundation
@testable import TracksCore

@Suite("Pairing codes")
struct PairingPayloadTests {
    static let now = Date(timeIntervalSince1970: 1_763_099_000)

    static func link(_ json: String, padded: Bool = false) -> String {
        var encoded = Data(json.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        if !padded { encoded = encoded.replacingOccurrences(of: "=", with: "") }
        return "trackside://pair?d=\(encoded)"
    }

    /// Architecture section 10's example, with its elided id and secret filled in.
    static let payload = #"""
    {"v":1,"server_id":"5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b","name":"Home","urls":[
      {"url":"http://192.168.1.20:4747","kind":"lan","tls":false},
      {"url":"https://music.example.com","kind":"public","tls":true}],
     "offer":"g4k2m9q7x1v8b3n6c0de","code":"Q7RM4X2K9TZP3W8HN5VC6BYD","exp":1763100000}
    """#

    @Test("a QR code or link reads as the server, its addresses and the secret", arguments: [false, true])
    func reads(padded: Bool) throws {
        let pairing = try PairingPayload(Self.link(Self.payload, padded: padded), now: Self.now)
        #expect(pairing.serverID == "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b")
        #expect(pairing.name == "Home")
        #expect(pairing.urls.map(\.kind) == [.lan, .public])
        #expect(pairing.urls.first?.url.absoluteString == "http://192.168.1.20:4747")
        #expect(pairing.code == "Q7RM4X2K9TZP3W8HN5VC6BYD")
        #expect(pairing.offerID == "g4k2m9q7x1v8b3n6c0de")
        #expect(pairing.proofKey == .offer(id: "g4k2m9q7x1v8b3n6c0de", secret: "Q7RM4X2K9TZP3W8HN5VC6BYD"))
        #expect(pairing.expiresAt == Date(timeIntervalSince1970: 1_763_100_000))
        #expect(try PairingPayload(url: URL(string: Self.link(Self.payload))!, now: Self.now) == pairing)
        #expect(try PairingPayload("  " + Self.link(Self.payload) + "\n", now: Self.now) == pairing)
    }

    @Test("anything else is turned away, saying why", arguments: [
        ("https://example.com/pair?d=abc", PairingPayload.ParseError.notAPairingLink),
        ("trackside://pair", .notAPairingLink),
        ("Q7RM-4X2K", .notAPairingLink),
        ("trackside://pair?d=!!!", .unreadable),
        (link(#"{"v":1}"#), .unreadable),
        // Without its offer id an address can't be asked to prove it made the code.
        (link(#"{"v":1,"server_id":"x","urls":[{"url":"http://h","kind":"lan"}],"code":"c","exp":1763100000}"#), .unreadable),
        (link(#"{"v":2,"server_id":"x","urls":[],"offer":"g1","code":"c","exp":1763100000}"#), .unsupportedVersion(2)),
        (link(#"{"v":1,"server_id":"x","urls":[{"url":"ftp://h","kind":"lan"}],"offer":"g1","code":"c","exp":1763100000}"#), .noAddresses),
        (link(#"{"v":1,"server_id":"x","urls":[{"url":"http://h","kind":"lan"}],"offer":"g1","code":"c","exp":1763000000}"#), .expired),
    ])
    func refused(text: String, expected: PairingPayload.ParseError) {
        #expect(throws: expected) { try PairingPayload(text, now: Self.now) }
    }

    @Test("a typed code is tidied however it's typed", arguments: [
        ("Q7RM-4X2K", "Q7RM-4X2K"),
        ("q7rm 4x2k", "Q7RM-4X2K"),
        ("Q7RM4X2K", "Q7RM-4X2K"),
        ("Q7RM-4X2", nil),
        ("Q7RM-4X2K-9", nil),
    ] as [(String, String?)])
    func typedCode(text: String, expected: String?) {
        #expect(PairingPayload.typedCode(text) == expected)
    }
}

import Foundation
import Testing
@testable import MotifStats

struct MD5Tests {
    private func hex(_ text: String) -> String {
        MD5Digest.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// RFC 1321's test suite.
    @Test(arguments: [
        ("", "d41d8cd98f00b204e9800998ecf8427e"),
        ("a", "0cc175b9c0f1b6a831c399e269772661"),
        ("abc", "900150983cd24fb0d6963f7d28e17f72"),
        ("message digest", "f96b697d7cb7938d525a2f31aaf161d0"),
        ("abcdefghijklmnopqrstuvwxyz", "c3fcd3d76192e4007dfb496cca67e13b"),
        ("12345678901234567890123456789012345678901234567890123456789012345678901234567890", "57edf4a22be3c955ac49da2e2107b67a"),
    ])
    func matchesTheRFC(input: String, digest: String) {
        #expect(hex(input) == digest)
    }

    /// The Subsonic API documentation's own example: password "sesame", salt "c19b2d".
    @Test func makesTheSubsonicExampleToken() {
        #expect(hex("sesame" + "c19b2d") == "26719a1196d2a940705a59634eb18eab")
    }
}

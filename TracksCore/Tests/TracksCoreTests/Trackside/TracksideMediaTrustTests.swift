import Testing
import Foundation
import Security
@testable import TracksCore

/// A self-signed certificate for 192.168.1.20, as a Trackside server makes for itself.
private enum SelfSigned {
    static let der = Data(base64Encoded: "MIICETCCAbgCCQC/hnZAvMQYpjAKBggqhkjOPQQDAjAXMRUwEwYDVQQDDAwxOTIuMTY4LjEuMjAwHhcNMjYxMTIyMDYyNTAwWhcNMzYxMTE5MDYyNTAwWjAXMRUwEwYDVQQDDAwxOTIuMTY4LjEuMjAwggFLMIIBAwYHKoZIzj0CATCB9wIBATAsBgcqhkjOPQEBAiEA/////wAAAAEAAAAAAAAAAAAAAAD///////////////8wWwQg/////wAAAAEAAAAAAAAAAAAAAAD///////////////wEIFrGNdiqOpPns+u9VXaYhrxlHQawzFOw9jvOPD4n0mBLAxUAxJ02CIbnBJNqZnjhE50mt4GffpAEQQRrF9Hy4SxCR/i85uVjpEDydwN9gS3rM6D0oTlF2JjClk/jQuL+Gn+bjufrSnwPnhYrzjNXazFezsu2QGg3v1H1AiEA/////wAAAAD//////////7zm+q2nF56E87nKwvxjJVECAQEDQgAEv/JdJDzQ42/Hab1jpZcxynx4wBIfPyWU//6kPpMvr+/9RLlpqSuAx3+/jFUz91mexYPhzVp5Nvtd5AW7D0bcjDAKBggqhkjOPQQDAgNHADBEAiBiJr6xy1bVkOQ1CMbI/MLNU/BOf0QnFmDxE/zA1AlRJQIgATe/m+FhjZFZdbUObhjl9DrF0uGVRaYDl7Twk24gazc=")!
    static let fingerprint = "63:2a:61:35:a2:ce:67:0c:35:60:01:ca:c7:5d:fc:42:c4:09:b2:d2:5b:17:03:6d:78:eb:6b:78:05:a5:c7:48"
    static let wrongFingerprint = String(repeating: "ab", count: 32)

    static func trust() throws -> SecTrust {
        let certificate = try #require(SecCertificateCreateWithData(nil, der as CFData))
        var trust: SecTrust?
        let status = SecTrustCreateWithCertificates(certificate, SecPolicyCreateSSL(true, "192.168.1.20" as CFString), &trust)
        #expect(status == errSecSuccess)
        return try #require(trust)
    }
}

@Suite("Media from a server with a self-signed certificate")
struct TracksideMediaTrustTests {
    private func pins(_ fingerprint: String, port: Int = 4443) -> TracksidePins {
        TracksidePins([TracksideServerURL(url: URL(string: "https://192.168.1.20:\(port)")!, kind: .lan, fingerprint: fingerprint)])
    }

    private func answer(_ trust: TracksideMediaTrust, host: String = "192.168.1.20", port: Int = 4443, method: String = NSURLAuthenticationMethodServerTrust) throws -> URLSession.AuthChallengeDisposition {
        TracksidePinning.answer(method: method, host: host, port: port, trust: try SelfSigned.trust(), accepts: trust.accepts).0
    }

    @Test("the pinned certificate is trusted for downloads, streams and covers alike")
    func pinnedIsTrusted() throws {
        let trust = TracksideMediaTrust()
        trust.hold(pins(SelfSigned.fingerprint), for: "home")
        #expect(try answer(trust) == .useCredential)
        #expect(trust.isPinned(URL(string: "https://192.168.1.20:4443/api/v1/songs/s1/stream")!))
    }

    @Test("any other certificate at a pinned address is refused")
    func wrongCertificateFailsClosed() throws {
        let trust = TracksideMediaTrust()
        trust.hold(pins(SelfSigned.wrongFingerprint), for: "home")
        #expect(try answer(trust) == .cancelAuthenticationChallenge)
    }

    @Test("an address nobody pins gets the system's own checks")
    func unpinnedIsTheSystems() throws {
        let trust = TracksideMediaTrust()
        trust.hold(pins(SelfSigned.fingerprint), for: "home")
        #expect(try answer(trust, port: 443) == .performDefaultHandling)
        #expect(try answer(trust, host: "music.example.com") == .performDefaultHandling)
        #expect(!trust.isPinned(URL(string: "http://192.168.1.20:4443")!))
    }

    @Test("a challenge that isn't about the server's certificate is left to the system")
    func otherChallenges() throws {
        let trust = TracksideMediaTrust()
        trust.hold(pins(SelfSigned.fingerprint), for: "home")
        #expect(try answer(trust, method: NSURLAuthenticationMethodHTTPBasic) == .performDefaultHandling)
    }

    @Test("a server's pins follow its latest addresses, and go when it's removed")
    func followsTheServer() throws {
        let trust = TracksideMediaTrust()
        let held = pins(SelfSigned.fingerprint)
        trust.hold(held, for: "home")
        held.set([TracksideServerURL(url: URL(string: "https://192.168.1.20:5443")!, kind: .lan, fingerprint: SelfSigned.fingerprint)])
        #expect(try answer(trust, port: 5443) == .useCredential)
        #expect(try answer(trust, port: 4443) == .performDefaultHandling)
        trust.release("home")
        #expect(try answer(trust, port: 5443) == .performDefaultHandling)
    }

    @Test("two servers pinning one address: either's certificate is the server's")
    func twoServers() throws {
        let trust = TracksideMediaTrust()
        trust.hold(pins(SelfSigned.wrongFingerprint), for: "old")
        trust.hold(pins(SelfSigned.fingerprint), for: "home")
        #expect(try answer(trust) == .useCredential)
    }
}

@Suite("The media token")
struct TracksideMediaTokenTests {
    @Test("goes only to the server in use, never to an address elsewhere the server hands out")
    func onlyToTheServer() throws {
        let client = TracksideTesting.client(StubTracksideTransport(body: "{}"))
        let local = try #require(client.mediaURL("/api/v1/songs/s1/stream"))
        #expect(TracksideTesting.query(local)["mt"] == "eyJ1Ijoi")

        let elsewhere = try #require(client.mediaURL("https://covers.example.com/a.jpg"))
        #expect(TracksideTesting.query(elsewhere)["mt"] == nil)
        let otherPort = try #require(client.mediaURL("http://192.168.1.20:9999/api/v1/songs/s1/stream"))
        #expect(TracksideTesting.query(otherPort)["mt"] == nil)
        let sameServer = try #require(client.mediaURL("http://192.168.1.20:4747/api/v1/art/a1"))
        #expect(TracksideTesting.query(sameServer)["mt"] == "eyJ1Ijoi")
    }
}

import Testing
import Foundation
@testable import TracksCore

@Suite("Proving an address is the server")
struct EndpointProofTests {
    typealias Proof = TracksideEndpointProof

    @Test("the proof matches the server's for the same key, address and challenge")
    func vectors() {
        let serverID = "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b"
        let nonce = "AAAAAAAAAAAAAAAAAAAAAA"
        // The same vectors as the server's own tests (trackside-auth's proof.rs and the
        // `ping-proof.json` fixture).
        #expect(Proof.expected(key: .device(id: "d1", apiKey: "tsk_example"), serverID: serverID, url: URL(string: "http://192.168.1.10:4533")!, nonce: nonce)
            == "682d7ce06987ceed051cff6a1f7f067f67a95506ce02dd709e0675240bbf6c13")
        #expect(Proof.expected(key: .offer(id: "g1", secret: "Q7RM4X2K9TZP3W8HN5VC6BYD"), serverID: serverID, url: URL(string: "http://home.local:4747")!, nonce: nonce)
            == "994d60fd2d2ebc2ef8dddf646d1c1002dad03e19541caa90a7461d6dadcf8f1a")
    }

    @Test("a proof for another address, server or challenge is refused")
    func boundToEverything() {
        let key = Proof.Key.device(id: "d1", apiKey: "tsk_example")
        let home = URL(string: "http://192.168.1.10:4533")!
        let proof = Proof.expected(key: key, serverID: "s", url: home, nonce: "n1")
        #expect(Proof.verify(proof, key: key, serverID: "s", url: home, nonce: "n1"))
        #expect(Proof.verify(proof?.uppercased(), key: key, serverID: "s", url: home, nonce: "n1"))
        #expect(!Proof.verify(proof, key: key, serverID: "s", url: URL(string: "https://music.example.com")!, nonce: "n1"))
        #expect(!Proof.verify(proof, key: key, serverID: "other", url: home, nonce: "n1"))
        #expect(!Proof.verify(proof, key: key, serverID: "s", url: home, nonce: "n2"))
        #expect(!Proof.verify(proof, key: .device(id: "d1", apiKey: "tsk_other"), serverID: "s", url: home, nonce: "n1"))
        #expect(!Proof.verify(nil, key: key, serverID: "s", url: home, nonce: "n1"))
    }

    @Test("an address is written as host and port, the port only when it isn't the scheme's", arguments: [
        ("http://192.168.1.10:4533/music", "192.168.1.10:4533"),
        ("https://Music.Example.com", "music.example.com"),
        ("https://music.example.com:443", "music.example.com"),
        ("http://box.local:80", "box.local"),
        ("http://[fd00::20]:4747", "[fd00::20]:4747"),
    ])
    func authority(url: String, expected: String) {
        #expect(Proof.authority(of: URL(string: url)!) == expected)
    }

    @Test("credentials go over TLS, or plain HTTP only to a nearby address", arguments: [
        ("https://music.example.com", true),
        ("http://192.168.1.20:4747", true),
        ("http://10.0.0.2", true),
        ("http://172.20.1.1", true),
        ("http://100.101.102.103:4747", true),
        ("http://box.tail1234.ts.net:4747", true),
        ("http://home.local:4747", true),
        ("http://nas:4747", true),
        ("http://nas.home.arpa", true),
        ("http://[fd00::20]:4747", true),
        ("http://[fe80::1]:4747", true),
        ("http://[::1]:4747", true),
        ("http://203.0.113.5:4747", false),
        ("http://172.32.0.1", false),
        ("http://music.example.com", false),
        ("http://[2001:db8::1]:4747", false),
        ("ftp://192.168.1.20", false),
    ])
    func credentials(url: String, allowed: Bool) {
        #expect(Proof.allowsCredentials(to: URL(string: url)!) == allowed)
    }

    @Test("challenges are fresh and URL safe")
    func nonces() {
        let a = Proof.nonce()
        #expect(a.count == 22)
        #expect(a != Proof.nonce())
        #expect(a.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" })
    }

    @Test("a self-signed address is held to its fingerprint, and others are left to the system")
    func pins() {
        // SHA-256 of the bytes "cert".
        let fingerprint = "06:29:84:32:e8:06:6b:29:e2:22:3b:cc:23:aa:95:04:b5:6a:e5:08:fa:bf:34:35:50:88:69:b9:c3:19:0e:22"
        let pinned = TracksideServerURL(url: URL(string: "https://192.168.1.20:4748")!, kind: .lan, tls: true, fingerprint: fingerprint)
        let plain = TracksideServerURL(url: URL(string: "https://music.example.com")!, kind: .public, tls: true)
        let pins = TracksidePins([pinned, plain])
        #expect(pins.accepts(host: "192.168.1.20", port: 4748, leafCertificate: Data("cert".utf8)) == true)
        #expect(pins.accepts(host: "192.168.1.20", port: 4748, leafCertificate: Data("impostor".utf8)) == false)
        #expect(pins.accepts(host: "music.example.com", port: 443, leafCertificate: Data("anything".utf8)) == nil)
        #expect(pins.accepts(host: "192.168.1.20", port: 4747, leafCertificate: Data("cert".utf8)) == nil)
        #expect(TracksidePins.bytes(ofFingerprint: fingerprint.replacingOccurrences(of: ":", with: "").uppercased())?.count == 32)
        #expect(TracksidePins.bytes(ofFingerprint: "ab:cd") == nil)
        pins.set([plain])
        #expect(pins.accepts(host: "192.168.1.20", port: 4748, leafCertificate: Data("cert".utf8)) == nil)
    }

    @Test("a password or pairing code is never sent over plain HTTP to an address that isn't nearby")
    func signInRefusesPlainPublic() async {
        let stub = StubTracksideTransport(body: "{}")
        let far = URL(string: "http://203.0.113.5:4747")!
        let device = TracksideDeviceInfo(clientDeviceID: "A", name: "iPhone", platform: "ios")
        await #expect(throws: TracksideError.insecureAddress) {
            try await TracksideClient.login(at: far, username: "luke", password: "secret", device: device, appDeviceID: "A", transport: stub)
        }
        await #expect(throws: TracksideError.insecureAddress) {
            try await TracksideClient.claim(at: far, code: "Q7RM-4X2K", device: device, appDeviceID: "A", transport: stub)
        }
        await #expect(throws: TracksideError.insecureAddress) {
            try await TracksideClient.requestPairing(at: far, device: device, appDeviceID: "A", transport: stub)
        }
        #expect(stub.requests.isEmpty)
    }

    @Test("removing a server revokes this device's key there")
    func revoke() async throws {
        let stub = StubTracksideTransport(status: 204, body: "")
        let client = TracksideTesting.client(stub)
        try await client.revokeThisDevice()
        let request = try #require(stub.requests.first)
        #expect(request.httpMethod == "DELETE")
        #expect(request.url?.path() == "/api/v1/me/devices/d4k2m9q7x1v8b3n6c0de")
    }

    @Test("the server's proof and pairing code fixtures check out here", .enabled(if: TracksideFixtures.directory != nil, "The server's fixtures aren't in this checkout"))
    func fixtures() throws {
        let directory = try #require(TracksideFixtures.directory)
        let ping = try TracksideJSON.decoder.decode(TracksidePing.self, from: Data(contentsOf: directory.appending(path: "ping-proof.json")))
        #expect(Proof.verify(ping.proof, key: .device(id: "d1", apiKey: "tsk_example"), serverID: ping.serverID, url: URL(string: "http://192.168.1.10:4533")!, nonce: "AAAAAAAAAAAAAAAAAAAAAA"))

        let offer = try JSONSerialization.jsonObject(with: Data(contentsOf: directory.appending(path: "pairing-code.json"))) as? [String: Any]
        let link = try #require(offer?["qr_payload"] as? String)
        let payload = try PairingPayload(link, now: Date(timeIntervalSince1970: 0))
        #expect(payload.offerID == offer?["id"] as? String)
    }
}

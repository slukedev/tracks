import Foundation
import Testing
@testable import TracksCore

@Suite("Where a Subsonic server's credentials may go")
struct SubsonicCredentialTests {
    let lan = URL(string: "http://192.168.1.10:4533")!
    let https = URL(string: "https://music.example.com")!

    @Test("https always; plain http nearby only at home while there's an https address")
    func password() {
        #expect(SubsonicCredentialRule.allows(https, usesAPIKey: false, hasHTTPS: true, onHomeNetwork: false))
        #expect(SubsonicCredentialRule.allows(lan, usesAPIKey: false, hasHTTPS: true, onHomeNetwork: true))
        #expect(!SubsonicCredentialRule.allows(lan, usesAPIKey: false, hasHTTPS: true, onHomeNetwork: false))
        // Only ever reached at home: as it was added.
        #expect(SubsonicCredentialRule.allows(lan, usesAPIKey: false, hasHTTPS: false, onHomeNetwork: false))
    }

    @Test("an API key never crosses plain http, except to this device")
    func apiKey() {
        #expect(!SubsonicCredentialRule.allows(lan, usesAPIKey: true, hasHTTPS: false, onHomeNetwork: true))
        #expect(!SubsonicCredentialRule.allows(lan, usesAPIKey: true, hasHTTPS: true, onHomeNetwork: true))
        #expect(SubsonicCredentialRule.allows(URL(string: "http://127.0.0.1:4533")!, usesAPIKey: true, hasHTTPS: false, onHomeNetwork: false))
        #expect(SubsonicCredentialRule.allows(URL(string: "http://[::1]:4533")!, usesAPIKey: true, hasHTTPS: false, onHomeNetwork: false))
        #expect(!SubsonicCredentialRule.isLoopback(URL(string: "http://127.example.com")!))
        #expect(SubsonicCredentialRule.allows(https, usesAPIKey: true, hasHTTPS: true, onHomeNetwork: false))
    }

    @Test("a server with no recorded home: https only when it has it, plain http as always when not")
    func noHome() {
        let here = SubsonicCredentialRule.isHome("n1", of: [])
        #expect(!here)
        #expect(!SubsonicCredentialRule.allows(lan, usesAPIKey: false, hasHTTPS: true, onHomeNetwork: here))
        #expect(SubsonicCredentialRule.allows(https, usesAPIKey: false, hasHTTPS: true, onHomeNetwork: here))
        #expect(SubsonicCredentialRule.allows(lan, usesAPIKey: false, hasHTTPS: false, onHomeNetwork: here))
        #expect(!SubsonicCredentialRule.allows(lan, usesAPIKey: true, hasHTTPS: false, onHomeNetwork: here))
    }

    @Test("home is a network recorded for the server, never no network")
    func home() {
        #expect(SubsonicCredentialRule.isHome("n1", of: ["n0", "n1"]))
        #expect(!SubsonicCredentialRule.isHome("n2", of: ["n0", "n1"]))
        #expect(!SubsonicCredentialRule.isHome(nil, of: ["n0"]))
    }
}

@Suite("Telling networks apart")
struct NetworkIdentityTests {
    @Test("the same network reads the same in any order, another differently, and nothing as nil")
    func identity() {
        let home = NetworkIdentity.make(gateways: ["192.168.1.1", "fe80::1%en0"], prefixes: ["192.168.1.0/24", "fd12:3456:789a:1::/64"])
        #expect(home == NetworkIdentity.make(gateways: ["FE80::1%en0", "192.168.1.1"], prefixes: ["fd12:3456:789a:1::/64", "192.168.1.0/24"]))
        #expect(home != NetworkIdentity.make(gateways: ["192.168.1.1", "fe80::1%en0"], prefixes: ["192.168.1.0/24"]))
        #expect(home?.count == 32)
        #expect(NetworkIdentity.make(gateways: [], prefixes: []) == nil)
    }

    @Test("only unique-local IPv6 prefixes count, and subnets are masked")
    func prefixes() {
        var ula: [UInt8] = [0xfd, 0x12, 0x34, 0x56, 0x78, 0x9a, 0x00, 0x01] + Array(repeating: 7, count: 8)
        #expect(NetworkIdentity.uniqueLocalPrefix(ula) == "fd12:3456:789a:1::/64")
        ula[0] = 0x2a
        #expect(NetworkIdentity.uniqueLocalPrefix(ula) == nil)
        #expect(NetworkIdentity.subnet([192, 168, 1, 23], mask: [255, 255, 255, 0]) == "192.168.1.0/24")
    }

    @Test("this machine's interfaces can be read")
    func local() {
        _ = NetworkIdentity.localPrefixes(onInterfaces: ["lo", "lo0", "eth0", "en0"])
    }
}

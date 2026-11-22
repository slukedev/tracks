import Testing
import Foundation
@testable import TracksCore

@Suite("A server's address, as someone types it")
struct ServerAddressTests {
    @Test("a home server without a scheme gains http", arguments: [
        ("192.168.1.223:5274", "http://192.168.1.223:5274"),
        ("192.168.1.20", "http://192.168.1.20"),
        ("localhost:4533", "http://localhost:4533"),
        ("navidrome.local", "http://navidrome.local"),
        ("nas", "http://nas"),
        ("[fe80::1]:4533", "http://[fe80::1]:4533"),
        ("fe80::1", "http://[fe80::1]"),
        ("[fd12:3456::7]:4533", "http://[fd12:3456::7]:4533"),
        ("10.0.0.5:4533", "http://10.0.0.5:4533"),
        ("172.20.1.9", "http://172.20.1.9"),
        ("169.254.3.4:4533", "http://169.254.3.4:4533"),
        ("100.101.102.103:4533", "http://100.101.102.103:4533"),
        ("nas.home.arpa:4533", "http://nas.home.arpa:4533"),
        ("NAS:4533/navidrome/", "http://NAS:4533/navidrome"),
    ])
    func homeGainsHTTP(typed: String, expected: String) {
        #expect(ServerAddress.url(from: typed)?.absoluteString == expected)
    }

    @Test("a server on the internet without a scheme gains https", arguments: [
        ("music.example.com", "https://music.example.com"),
        ("music.example.com:443", "https://music.example.com:443"),
        ("example.com/navidrome", "https://example.com/navidrome"),
        ("music.example.com:4533", "https://music.example.com:4533"),
        ("203.0.113.7:4533", "https://203.0.113.7:4533"),
        ("[2001:db8::1]:4533", "https://[2001:db8::1]:4533"),
        ("2001:db8::1", "https://[2001:db8::1]"),
        ("192.168.1.20:443", "https://192.168.1.20:443"),
    ])
    func internetGainsHTTPS(typed: String, expected: String) {
        #expect(ServerAddress.url(from: typed)?.absoluteString == expected)
    }

    @Test("spaces around it and slashes after it go, and a scheme typed is kept", arguments: [
        ("  192.168.1.223:5274/ \n", "http://192.168.1.223:5274"),
        ("https://192.168.1.4:4533//", "https://192.168.1.4:4533"),
        ("HTTP://music.example.com", "http://music.example.com"),
        ("http://music.example.com/sub/", "http://music.example.com/sub"),
    ])
    func tidied(typed: String, expected: String) {
        #expect(ServerAddress.url(from: typed)?.absoluteString == expected)
    }

    @Test("anything that can't be a web server's address is refused", arguments: ["", "   ", "/", "ftp://nope", "http://", "https:///path"])
    func refused(typed: String) {
        #expect(ServerAddress.url(from: typed) == nil)
    }

    @Test("plain http beyond the home network is exposed, and nothing else is", arguments: [
        ("http://music.example.com:4533", true),
        ("http://203.0.113.7", true),
        ("http://[2001:db8::1]:4533", true),
        ("https://music.example.com", false),
        ("http://192.168.1.20:4533", false),
        ("http://[fe80::1]", false),
        ("http://nas", false),
        ("http://navidrome.local", false),
    ])
    func exposed(address: String, isExposed: Bool) throws {
        #expect(ServerAddress.isExposed(try #require(URL(string: address))) == isExposed)
    }

    @Test("music servers and Lidarr read addresses the same way")
    func sharedByBoth() {
        #expect(SubsonicServer.address(from: "192.168.1.223:5274")?.absoluteString == "http://192.168.1.223:5274")
        #expect(LidarrServer.address(from: "lidarr.example.com")?.absoluteString == "https://lidarr.example.com")
    }

    @Test("a Lidarr address pasted with the API's path loses it", arguments: [
        ("nas:8686/api/v1", "http://nas:8686"),
        ("http://nas:8686/api/", "http://nas:8686"),
        ("https://lidarr.example.com/lidarr/API/v1/", "https://lidarr.example.com/lidarr"),
        ("nas:8686/apis", "http://nas:8686/apis"),
    ])
    func lidarrAPIPath(typed: String, expected: String) {
        #expect(LidarrServer.address(from: typed)?.absoluteString == expected)
    }
}

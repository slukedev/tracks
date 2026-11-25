import Foundation
import Testing
@testable import TracksCore

@Suite("Addresses in logs")
struct URLRedactionTests {
    @Test("a Subsonic address keeps its server and endpoint but loses what signs in")
    func subsonic() throws {
        let url = try #require(URL(string: "https://music.example.com/rest/stream?u=luke&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=Tracks&id=42"))
        let redacted = URLRedaction.redact(url)
        #expect(redacted.contains("music.example.com/rest/stream"))
        #expect(redacted.contains("id=42"))
        #expect(redacted.contains("v=1.16.1"))
        #expect(!redacted.contains("luke"))
        #expect(!redacted.contains("26719a1196d2a940705a59634eb18eab"))
        #expect(!redacted.contains("c19b2d"))
    }

    @Test("API keys, media tokens and passwords in the address go, whatever their case")
    func otherSecrets() throws {
        let url = try #require(URL(string: "http://user:pass@192.168.1.20:4747/api/v1/art/a1?mt=mt1.secret&apiKey=k3y&P=plain"))
        let redacted = URLRedaction.redact(url)
        for secret in ["mt1.secret", "k3y", "plain", "pass", "user:"] {
            #expect(!redacted.contains(secret), "\(secret) in \(redacted)")
        }
        #expect(redacted.contains("192.168.1.20:4747/api/v1/art/a1"))
    }

    @Test("an error's description is redacted wherever it repeats the address")
    func errors() throws {
        let url = try #require(URL(string: "https://music.example.com/rest/download?u=luke&t=abcdef0123&s=salt99&id=7"))
        let error = URLError(.timedOut, userInfo: [NSURLErrorFailingURLErrorKey: url, NSURLErrorFailingURLStringErrorKey: url.absoluteString])
        let line = URLRedaction.describe(error)
        #expect(line.contains("music.example.com/rest/download"))
        #expect(!line.contains("abcdef0123"))
        #expect(!line.contains("salt99"))
        #expect(!line.contains("u=luke"))
    }

    @Test("text without an address is left alone")
    func plainText() {
        #expect(URLRedaction.redact(text: "The httpBody was empty") == "The httpBody was empty")
    }
}

@Suite("Which addresses may get credentials over plain HTTP")
struct NearbyAddressTests {
    @Test("nearby addresses", arguments: [
        "http://192.168.1.20:4533", "http://10.0.0.5", "http://172.16.4.1", "http://172.31.255.255",
        "http://169.254.10.1", "http://127.0.0.1:4747", "http://100.64.0.1", "http://100.127.1.1",
        "http://[fd00::1]:4533", "http://[fe80::1]", "http://[::1]", "http://nas.local", "http://nas",
        "http://music.home.arpa", "http://box.lan", "http://box.internal", "http://home.tail1234.ts.net",
    ])
    func nearby(_ text: String) throws {
        let url = try #require(URL(string: text))
        #expect(NearbyAddress.isNearby(url))
        #expect(NearbyAddress.allowsCredentials(to: url))
        #expect(!NearbyAddress.isExposed(url))
    }

    @Test("addresses on the internet", arguments: [
        "http://music.example.com", "http://music.example.com:4533", "http://8.8.8.8", "http://172.32.0.1",
        "http://100.128.0.1", "http://[2001:db8::1]", "http://192.169.1.1", "http://local.example.com",
        "http://1.2.3.04x",
    ])
    func remote(_ text: String) throws {
        let url = try #require(URL(string: text))
        #expect(!NearbyAddress.isNearby(url))
        #expect(!NearbyAddress.allowsCredentials(to: url))
        #expect(NearbyAddress.isExposed(url))
    }

    @Test("https may carry credentials anywhere; other schemes never")
    func schemes() throws {
        #expect(NearbyAddress.allowsCredentials(to: try #require(URL(string: "https://music.example.com"))))
        #expect(!NearbyAddress.allowsCredentials(to: try #require(URL(string: "ftp://192.168.1.2"))))
    }
}

@Suite("Racing a server's addresses")
struct AddressRaceTests {
    struct Candidate: Sendable, Equatable {
        let name: String
        let isLocal: Bool
        let answers: Bool
        let delay: Duration
    }

    static func race(_ candidates: [Candidate], grace: Duration = .milliseconds(300), timeout: Duration = .seconds(2)) async -> String? {
        await AddressRace.pick(
            from: candidates,
            isLocal: \.isLocal,
            localGrace: grace,
            timeout: timeout,
            sleep: { try await Task.sleep(for: $0) },
            probe: { candidate in
                try? await Task.sleep(for: candidate.delay)
                return candidate.answers
            }
        )?.name
    }

    @Test("the local address wins when it answers within the grace")
    func localWins() async {
        let chosen = await Self.race([
            Candidate(name: "public", isLocal: false, answers: true, delay: .milliseconds(10)),
            Candidate(name: "lan", isLocal: true, answers: true, delay: .milliseconds(100)),
        ])
        #expect(chosen == "lan")
    }

    @Test("away from home, the remote address is taken once the local one fails")
    func remoteWhenLocalFails() async {
        let chosen = await Self.race([
            Candidate(name: "lan", isLocal: true, answers: false, delay: .milliseconds(5)),
            Candidate(name: "public", isLocal: false, answers: true, delay: .milliseconds(20)),
        ])
        #expect(chosen == "public")
    }

    @Test("nothing is chosen when nothing answers in time")
    func nothing() async {
        let chosen = await Self.race([
            Candidate(name: "lan", isLocal: true, answers: true, delay: .seconds(5)),
        ], timeout: .milliseconds(50))
        #expect(chosen == nil)
        #expect(await Self.race([]) == nil)
    }
}

@Suite("Retrying reads")
struct RetryPolicyTests {
    struct Flaky: Error {}
    struct Fatal: Error {}

    final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0
        func next() -> Int { lock.withLock { value += 1; return value } }
        var count: Int { lock.withLock { value } }
    }

    @Test("delays double up to the longest, with at most a quarter more")
    func delays() {
        let policy = RetryPolicy(attempts: 5, firstDelay: .milliseconds(400), longestDelay: .seconds(2))
        #expect(policy.delay(after: 1, jitter: 0) == .milliseconds(400))
        #expect(policy.delay(after: 2, jitter: 0) == .milliseconds(800))
        #expect(policy.delay(after: 4, jitter: 0) == .seconds(2))
        #expect(policy.delay(after: 1, jitter: 1) == .milliseconds(500))
    }

    @Test("a transient failure is tried again; another isn't")
    func retries() async throws {
        let counter = Counter()
        let value = try await RetryPolicy(attempts: 3).run(isTransient: { $0 is Flaky }, sleep: { _ in }) {
            if counter.next() < 3 { throw Flaky() }
            return 7
        }
        #expect(value == 7)
        #expect(counter.count == 3)

        let fatal = Counter()
        await #expect(throws: Fatal.self) {
            try await RetryPolicy(attempts: 3).run(isTransient: { $0 is Flaky }, sleep: { _ in }) { () async throws -> Int in
                _ = fatal.next()
                throw Fatal()
            }
        }
        #expect(fatal.count == 1)
    }
}

@Suite("Play ids")
struct PlayIDTests {
    @Test("SHA-1 matches the standard's vectors")
    func sha1() {
        let hex = { (bytes: [UInt8]) in bytes.map { String(format: "%02x", $0) }.joined() }
        #expect(hex(SHA1.digest(Array("abc".utf8))) == "a9993e364706816aba3e25717850c26c9cd0d89d")
        #expect(hex(SHA1.digest(Array(repeating: UInt8(ascii: "a"), count: 1000))) == "291e9a6c66994949b57ba5e650361e98fc36b1ba")
    }

    @Test("UUIDv5 matches RFC 9562's DNS example")
    func uuidV5() throws {
        let dns = try #require(UUID(uuidString: "6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        #expect(PlayID.uuidV5(namespace: dns, name: "python.org").uuidString.lowercased() == "886313e1-3b8a-5372-9b90-0c9aee199e5d")
    }

    @Test("a play kept before ids gets the same id from the same key, on any device")
    func derived() {
        let key = PlayID.contentKey(capturedAt: Date(timeIntervalSince1970: 1_700_000_000), kind: "radio", source: "appleMusic", songIdentity: "song\u{1F}artist")
        #expect(key == "1700000000000\u{1F}radio\u{1F}appleMusic\u{1F}song\u{1F}artist")
        #expect(PlayID.derived(fromKey: key) == "ec212ee6-e655-5934-993d-33b155df2eb2")
        #expect(PlayID.derived(fromKey: "") == "25edc9e6-7801-5a5d-8a25-be94988f72d8")
    }

    struct Row: Equatable {
        let key: String
        let device: String
    }

    @Test("exact copies are numbered in the same order whatever order they're read in")
    func backfillIsDeterministic() {
        let rows = [Row(key: "k", device: "b"), Row(key: "k", device: "a"), Row(key: "j", device: "a")]
        let first = PlayID.backfill(rows, key: \.key, precedes: { $0.device < $1.device })
        let second = PlayID.backfill(rows.reversed(), key: \.key, precedes: { $0.device < $1.device })
        let ids = { (pairs: [(row: Row, id: String)]) in Dictionary(uniqueKeysWithValues: pairs.map { ($0.row.device + $0.row.key, $0.id) }) }
        #expect(ids(first) == ids(second))
        #expect(ids(first)["ak"] == PlayID.derived(fromKey: "k"))
        #expect(ids(first)["bk"] == PlayID.derived(fromKey: "k\u{1F}1"))
        #expect(Set(first.map(\.id)).count == 3)
    }

    @Test("stored ids are kept in one spelling, and junk isn't kept")
    func normalized() {
        #expect(PlayID.normalized("EC212EE6-E655-5934-993D-33B155DF2EB2") == "ec212ee6-e655-5934-993d-33b155df2eb2")
        #expect(PlayID.normalized("not an id") == nil)
        #expect(PlayID.normalized(nil) == nil)
        #expect(PlayID.normalized(PlayID.make()) != nil)
    }
}

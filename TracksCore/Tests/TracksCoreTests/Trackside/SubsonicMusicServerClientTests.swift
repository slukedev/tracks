import Testing
import Foundation
import Synchronization
@testable import TracksCore

private final class RecordingSubsonic: SubsonicTransport {
    private let body: String
    private let answers: Int
    private let asked = Mutex<[URL]>([])

    /// - Parameter answers: how many requests are answered before the connection drops.
    init(_ body: String, answers: Int = .max) {
        self.body = body
        self.answers = answers
    }

    var urls: [URL] { asked.withLock { $0 } }

    func data(for url: URL) async throws -> (Data, Int) {
        let count = asked.withLock { $0.append(url); return $0.count }
        guard count <= answers else { throw URLError(.networkConnectionLost) }
        return (Data(body.utf8), 200)
    }
}

@Suite("A Subsonic server as any music server")
struct SubsonicMusicServerClientTests {
    let server = SubsonicServer(id: "home", name: "Home", url: URL(string: "https://music.example.com")!, username: "luke", salt: "c19b2d")

    private func songs(library: Int, external: Int = 0) -> String {
        let own = (0..<library).map { #"{"id":"s\#($0)","title":"Song \#($0)"}"# }
        let found = (0..<external).map { #"{"id":"x\#($0)","title":"Found \#($0)","isExternal":true}"# }
        return #"{"subsonic-response":{"status":"ok","searchResult3":{"song":[\#((own + found).joined(separator: ","))]}}}"#
    }

    /// A server that finds songs elsewhere puts its first 12 library matches on a first page,
    /// then what it found.
    @Test("a full page may have more after it, a short one doesn't, and a first page with songs found elsewhere is full at 12", arguments: [
        (30, 0, MusicSearchPosition?.some(.offset(30))),
        (12, 0, nil),
        (12, 30, .offset(12)),
        (5, 20, nil),
    ] as [(Int, Int, MusicSearchPosition?)])
    func paging(library: Int, external: Int, next: MusicSearchPosition?) async throws {
        let size = external > 0 ? 60 : 30
        let client: any MusicServerClient = SubsonicClient(server: server, password: "x", transport: RecordingSubsonic(songs(library: library, external: external)))
        #expect(try await client.search("night", artists: 0, albums: 0, songs: size, after: nil).next == next)
    }

    @Test("the next page asks from where the library's own matches left off")
    func nextOffset() async throws {
        let transport = RecordingSubsonic(songs(library: 30))
        let client: any MusicServerClient = SubsonicClient(server: server, password: "x", transport: transport)
        let page = try await client.search("night", artists: 0, albums: 0, songs: 30, after: .offset(30))
        #expect(page.next == .offset(60))
        let query = TracksideTesting.query(try #require(transport.urls.first))
        #expect(query["songOffset"] == "30")
    }

    @Test("streams, downloads and covers keep the addresses Subsonic servers have always had")
    func media() async throws {
        let client = SubsonicClient(server: server, password: "x", transport: RecordingSubsonic("{}"))
        let stream = try await client.stream(songID: "7", maxBitRate: 320, network: .cellular)
        #expect(stream.delivered == nil)
        #expect(TracksideTesting.query(stream.url)["maxBitRate"] == "320")
        let original = try await client.downloadRequest(songID: "7", maxBitRate: nil)
        #expect(original.url?.path() == "/rest/download")
        #expect((client as any MusicServerClient).artworkURL(id: "al-1", size: 600) == client.coverArtURL(id: "al-1", size: 600))
    }

    @Test("a play is a scrobble at the time it started, and a failure after the first keeps what was sent")
    func listens() async throws {
        let ok = #"{"subsonic-response":{"status":"ok"}}"#
        let started = Date(timeIntervalSince1970: 1_763_000_000)
        let listens = ["A", "B", "C"].map { ServerListen(id: $0, songID: "7", startedAt: started, played: 200, title: "Night Drive", artist: "Mara Solis") }

        let transport = RecordingSubsonic(ok, answers: 2)
        let results = try await SubsonicClient(server: server, password: "x", transport: transport).submitListens(listens)
        #expect(results == ["A": .accepted, "B": .accepted])
        let query = TracksideTesting.query(try #require(transport.urls.first))
        #expect(query["time"] == "1763000000000")
        #expect(query["submission"] == "true")

        await #expect(throws: URLError.self) {
            try await SubsonicClient(server: server, password: "x", transport: RecordingSubsonic(ok, answers: 0)).submitListens(listens)
        }
    }
}

import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// A Subsonic server with a library of songs, as many per page as it likes, and albums of them.
private final class Library: SubsonicTransport {
    struct Behaviour: Sendable {
        /// The most songs it sends a page, whatever is asked.
        var pageCap = Int.max
        /// Answers every page from the start, as a server that ignores the offset does.
        var ignoresOffset = false
        /// Refuses an empty search, as older servers do.
        var refusesEmptySearch = false
        /// Answers search with this status, with no body it can read.
        var searchStatus: Int?
        /// Albums answered with this status instead.
        var failingAlbums: [String: Int] = [:]
        /// Albums it says are gone.
        var missingAlbums: Set<String> = []
    }

    let songs: Int
    let albumSize: Int
    let behaviour: Behaviour
    private let asked = Mutex<[String]>([])

    init(songs: Int, albumSize: Int = 10, _ behaviour: Behaviour = Behaviour()) {
        self.songs = songs
        self.albumSize = albumSize
        self.behaviour = behaviour
    }

    var requests: [String] { asked.withLock { $0 } }

    private func song(_ index: Int) -> String {
        #"{"id":"s\#(index)","title":"Song \#(index)","artist":"Artist","album":"Album \#(index / albumSize)","albumId":"al\#(index / albumSize)"}"#
    }

    private func ok(_ body: String) -> (Data, Int) {
        (Data(#"{"subsonic-response":{"status":"ok","version":"1.16.1"\#(body.isEmpty ? "" : ","+body)}}"#.utf8), 200)
    }

    func data(for url: URL) async throws -> (Data, Int) {
        let endpoint = url.lastPathComponent
        let query = Dictionary((URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") }) { a, _ in a }
        asked.withLock { $0.append(endpoint) }
        switch endpoint {
        case "search3":
            if let status = behaviour.searchStatus { return (Data("<html>busy</html>".utf8), status) }
            if behaviour.refusesEmptySearch {
                return (Data(#"{"subsonic-response":{"status":"failed","error":{"code":0,"message":"Search needs a query"}}}"#.utf8), 200)
            }
            let asked = Int(query["songCount"] ?? "0") ?? 0
            let offset = behaviour.ignoresOffset ? 0 : Int(query["songOffset"] ?? "0") ?? 0
            let count = max(0, min(min(asked, behaviour.pageCap), songs - offset))
            let page = (offset..<offset + count).map(song).joined(separator: ",")
            return ok(#""searchResult3":{"song":[\#(page)]}"#)
        case "getAlbumList2":
            let size = Int(query["size"] ?? "0") ?? 0
            let offset = Int(query["offset"] ?? "0") ?? 0
            let albumCount = (songs + albumSize - 1) / albumSize
            let ids = (offset..<min(albumCount, offset + size))
            let albums = ids.map { #"{"id":"al\#($0)","name":"Album \#($0)"}"# }.joined(separator: ",")
            return ok(#""albumList2":{"album":[\#(albums)]}"#)
        case "getAlbum":
            let id = query["id"] ?? ""
            if let status = behaviour.failingAlbums[id] { return (Data(), status) }
            if behaviour.missingAlbums.contains(id) {
                return (Data(#"{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not found"}}}"#.utf8), 200)
            }
            let number = Int(id.dropFirst(2)) ?? 0
            let range = (number * albumSize)..<min(songs, (number + 1) * albumSize)
            return ok(#""album":{"id":"\#(id)","name":"Album \#(number)","song":[\#(range.map(song).joined(separator: ","))]}"#)
        default:
            return ok("")
        }
    }
}

@Suite("Reading a Subsonic server's whole library")
struct SubsonicCatalogTests {
    private let server = SubsonicServer(id: "home", name: "Home", url: URL(string: "http://192.168.1.20:4533")!, username: "luke", salt: "abc")

    private func catalog(_ library: Library, pageSize: Int = 500, limit: Int = 60_000) async throws -> SubsonicCatalog {
        try await SubsonicClient(server: server, password: "pw", transport: library).catalog(pageSize: pageSize, limit: limit)
    }

    @Test("every song is read, each once, in the server's order")
    func everySong() async throws {
        let found = try await catalog(Library(songs: 1_234))
        #expect(found.songs.count == 1_234)
        #expect(found.songs.first?.id == "s0")
        #expect(found.songs.last?.id == "s1233")
        #expect(found.isComplete)
    }

    @Test("a server that sends fewer than asked for still has every song read")
    func cappedPages() async throws {
        let found = try await catalog(Library(songs: 1_000, .init(pageCap: 100)))
        #expect(found.songs.count == 1_000)
        #expect(Set(found.songs.map(\.id)).count == 1_000)
    }

    @Test("a server that ignores the offset isn't read over and over")
    func ignoresOffset() async throws {
        let library = Library(songs: 2_000, .init(ignoresOffset: true))
        let found = try await catalog(library)
        #expect(found.songs.count == 500)
        #expect(library.requests.count(where: { $0 == "search3" }) == 2)
    }

    @Test("a library past the limit says it's incomplete rather than passing for all of it")
    func limit() async throws {
        let found = try await catalog(Library(songs: 1_200), pageSize: 500, limit: 1_000)
        #expect(found.songs.count == 1_000)
        #expect(!found.isComplete)
    }

    @Test("a busy or unreachable server throws, so the songs already kept stay", arguments: [503, 500, 429])
    func busy(status: Int) async throws {
        await #expect(throws: SubsonicError.http(status: status)) {
            try await catalog(Library(songs: 100, .init(searchStatus: status)))
        }
    }

    @Test("a server that won't search for nothing is read album by album")
    func albumWalk() async throws {
        let library = Library(songs: 95, albumSize: 10, .init(refusesEmptySearch: true))
        let found = try await catalog(library, pageSize: 4)
        #expect(found.songs.map(\.id) == (0..<95).map { "s\($0)" })
        #expect(found.isComplete)
    }

    @Test("an album that's busy fails the walk, rather than its songs going missing")
    func busyAlbum() async throws {
        let library = Library(songs: 95, albumSize: 10, .init(refusesEmptySearch: true, failingAlbums: ["al3": 503]))
        await #expect(throws: SubsonicError.http(status: 503)) { try await catalog(library) }
    }

    @Test("an album gone since the list was read is left out, and the rest read")
    func goneAlbum() async throws {
        let library = Library(songs: 95, albumSize: 10, .init(refusesEmptySearch: true, missingAlbums: ["al3"]))
        let found = try await catalog(library)
        #expect(found.songs.count == 85)
        #expect(!found.songs.contains { $0.id == "s30" })
    }

    @Test("a busy server counts as out of reach, a refusal doesn't")
    func transient() {
        #expect(SubsonicClient.isTransient(URLError(.timedOut)))
        #expect(SubsonicClient.isTransient(SubsonicError.http(status: 502)))
        #expect(!SubsonicClient.isTransient(SubsonicError.http(status: 404)))
        #expect(!SubsonicClient.isTransient(SubsonicError.notFound))
        #expect(!SubsonicClient.isTransient(SubsonicError.wrongCredentials))
    }
}

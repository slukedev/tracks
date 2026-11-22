import Testing
import Foundation
@testable import TracksCore

@Suite("Records Tracks keeps on disk")
struct ServerStoredJSONTests {
    private struct Entry: Codable, Equatable {
        let name: String
        let count: Int
    }

    private func asides(in folder: URL) throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).filter { $0.lastPathComponent.contains(".unreadable-") }
    }

    @Test("no file reads as nothing, and nothing is set aside")
    func missing() throws {
        let folder = try TemporaryFolder()
        #expect(StoredJSON.loadEach([Entry].self, from: folder.url.appending(path: "none.json")) == nil)
        #expect(try asides(in: folder.url).isEmpty)
    }

    @Test("a file cut short is moved aside before anything can write over it", arguments: ["[{\"name\":\"A\",\"cou", "", "{not json", "null"])
    func cutShort(contents: String) throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "playlists.json")
        try Data(contents.utf8).write(to: file)

        #expect(StoredJSON.loadEach([Entry].self, from: file) == nil)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        let aside = try #require(try asides(in: folder.url).first)
        #expect(try Data(contentsOf: aside) == Data(contents.utf8))

        // What the app saves next starts afresh, and the original is still there.
        try Data("[]".utf8).write(to: file)
        #expect(try asides(in: folder.url).count == 1)
    }

    @Test("one entry a newer Tracks wrote costs only itself, and the whole file is kept aside")
    func oneBadEntry() throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "listens.json")
        let contents = #"[{"name":"A","count":1},{"name":"B","count":"two"},{"name":"C","count":3}]"#
        try Data(contents.utf8).write(to: file)

        #expect(StoredJSON.loadEach([Entry].self, from: file) == [Entry(name: "A", count: 1), Entry(name: "C", count: 3)])
        #expect(FileManager.default.fileExists(atPath: file.path))
        let aside = try #require(try asides(in: folder.url).first)
        #expect(try Data(contentsOf: aside) == Data(contents.utf8))
    }

    @Test("a table keeps every entry it can read")
    func table() throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "downloads.json")
        try Data(#"{"a":{"name":"A","count":1},"b":{"name":"B"}}"#.utf8).write(to: file)
        #expect(StoredJSON.loadEach([String: Entry].self, from: file) == ["a": Entry(name: "A", count: 1)])
        #expect(try asides(in: folder.url).count == 1)
    }

    @Test("a whole value that can't be read is moved aside")
    func whole() throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "cursor.json")
        try Data(#"{"name":"A"}"#.utf8).write(to: file)
        #expect(StoredJSON.load(Entry.self, from: file) == nil)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(try asides(in: folder.url).count == 1)
    }

    @Test("a list kept in defaults that can't all be read is copied to a file first")
    func fromDefaults() throws {
        let folder = try TemporaryFolder()
        let copy = folder.url.appending(path: "musicServers.json")
        let data = Data(#"[{"name":"A","count":1},{"name":"B"}]"#.utf8)
        #expect(StoredJSON.decodeEach([Entry].self, from: data, keepingCopyAt: copy) == [Entry(name: "A", count: 1)])
        let aside = try #require(try asides(in: folder.url).first)
        #expect(try Data(contentsOf: aside) == data)
    }

    @Test("files set aside the same second don't replace each other")
    func asidesStayApart() throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "x.json")
        for _ in 0..<3 {
            try Data("{".utf8).write(to: file)
            _ = StoredJSON.loadEach([Entry].self, from: file)
        }
        #expect(try asides(in: folder.url).count == 3)
    }

    @Test("plays waiting to be sent survive a file a newer Tracks wrote")
    func outbox() async throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "server-home-listens.json")
        let good = try String(decoding: TracksideJSON.encoder.encode([ListenOutboxTests.listen("A")]), as: UTF8.self)
        try Data("[\(good.dropFirst().dropLast()),{\"id\":\"B\"}]".utf8).write(to: file)
        let outbox = ListenOutbox(file: file)
        #expect(await outbox.listens.map(\.id) == ["A"])
        #expect(try asides(in: folder.url).count == 1)
    }
}

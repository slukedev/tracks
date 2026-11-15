import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// A server's listens endpoint: answers each batch as told, and remembers what it was sent.
private final class ListenServer: Sendable {
    private let batches = Mutex<[[String]]>([])
    private let answer: @Sendable ([ServerListen]) throws -> [String: ServerListenStatus]

    init(_ answer: @escaping @Sendable ([ServerListen]) throws -> [String: ServerListenStatus] = { Dictionary(uniqueKeysWithValues: $0.map { ($0.id, .accepted) }) }) {
        self.answer = answer
    }

    var received: [[String]] { batches.withLock { $0 } }

    func send(_ listens: [ServerListen]) throws -> [String: ServerListenStatus] {
        batches.withLock { $0.append(listens.map(\.id)) }
        return try answer(listens)
    }
}

@Suite("Listens waiting to be sent")
struct ListenOutboxTests {
    static func listen(_ id: String, songID: String? = "s1") -> ServerListen {
        ServerListen(id: id, songID: songID, startedAt: Date(timeIntervalSince1970: 1_763_000_000), played: 200, title: "Night Drive", artist: "Mara Solis", album: "Coastlines", duration: 201)
    }

    @Test("plays kept survive a relaunch, and one kept twice is sent once")
    func survivesRelaunch() async throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "Index/server-home-listens.json")
        let outbox = ListenOutbox(file: file)
        await outbox.add(Self.listen("A"))
        await outbox.add(Self.listen("B"))
        await outbox.add(Self.listen("A"))

        let relaunched = ListenOutbox(file: file)
        #expect(await relaunched.listens.map(\.id) == ["A", "B"])
        #expect(await relaunched.listens.first == Self.listen("A"))
    }

    @Test("a failed send keeps everything to send again, and the next one sends it")
    func resendsAfterFailure() async throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "listens.json")
        let outbox = ListenOutbox(file: file)
        await outbox.add(Self.listen("A"))
        await outbox.add(Self.listen("B"))

        let offline = ListenServer { _ in throw URLError(.notConnectedToInternet) }
        await #expect(throws: URLError.self) { try await outbox.flush { try offline.send($0) } }
        #expect(await ListenOutbox(file: file).listens.count == 2)

        let online = ListenServer()
        let report = try await outbox.flush { try online.send($0) }
        #expect(report == ListenOutbox.FlushReport(sent: 2, dropped: 0, waiting: 0))
        #expect(online.received == [["A", "B"]])
        #expect(await ListenOutbox(file: file).listens.isEmpty)
    }

    @Test("rejected plays are let go of, duplicates count as sent, and unanswered ones wait")
    func answers() async throws {
        let folder = try TemporaryFolder()
        let outbox = ListenOutbox(file: folder.url.appending(path: "listens.json"))
        for id in ["A", "B", "C", "D"] { await outbox.add(Self.listen(id)) }
        let server = ListenServer { _ in ["A": .accepted, "B": .duplicate, "C": .rejected] }
        let report = try await outbox.flush { try server.send($0) }
        #expect(report == ListenOutbox.FlushReport(sent: 2, dropped: 1, waiting: 1))
        #expect(await outbox.listens.map(\.id) == ["D"])
        // Not asked again in the same flush, so a server that never answers for one can't loop.
        #expect(server.received.count == 1)
    }

    @Test("a long backlog goes in batches the server takes")
    func batches() async throws {
        let folder = try TemporaryFolder()
        let outbox = ListenOutbox(file: folder.url.appending(path: "listens.json"), batchSize: 2)
        for id in ["A", "B", "C", "D", "E"] { await outbox.add(Self.listen(id)) }
        let server = ListenServer()
        #expect(try await outbox.flush { try server.send($0) }.sent == 5)
        #expect(server.received == [["A", "B"], ["C", "D"], ["E"]])
    }

    @Test("flushes asked for at once send each play once")
    func concurrentFlushes() async throws {
        let folder = try TemporaryFolder()
        let outbox = ListenOutbox(file: folder.url.appending(path: "listens.json"))
        await outbox.add(Self.listen("A"))
        let server = ListenServer()
        async let first = outbox.flush { listens in
            try await Task.sleep(for: .milliseconds(30))
            return try server.send(listens)
        }
        async let second = outbox.flush { try server.send($0) }
        _ = try await (first, second)
        #expect(server.received.flatMap { $0 } == ["A"])
    }

    @Test("through a Subsonic server, each play is a scrobble, and one without its song is let go of")
    func subsonic() async throws {
        let folder = try TemporaryFolder()
        let outbox = ListenOutbox(file: folder.url.appending(path: "listens.json"))
        await outbox.add(Self.listen("A"))
        await outbox.add(Self.listen("B", songID: nil))
        let server = SubsonicServer(id: "home", name: "Home", url: URL(string: "https://music.example.com")!, username: "luke")
        let client = SubsonicClient(server: server, password: "x", transport: SubsonicStub())
        let report = try await outbox.flush(to: client)
        #expect(report == ListenOutbox.FlushReport(sent: 1, dropped: 1, waiting: 0))
    }
}

private struct SubsonicStub: SubsonicTransport {
    func data(for url: URL) async throws -> (Data, Int) {
        (Data(#"{"subsonic-response":{"status":"ok"}}"#.utf8), 200)
    }
}

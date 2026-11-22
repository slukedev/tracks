import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// A Subsonic server's scrobble endpoint, reachable or not, that knows some songs.
private final class ScrobbleServer: SubsonicTransport {
    private let state: Mutex<(online: Bool, known: Set<String>, heard: [(String, String)])>

    init(online: Bool, known: Set<String>) {
        state = Mutex((online, known, []))
    }

    func goOnline() { state.withLock { $0.online = true } }
    var heard: [(id: String, time: String)] { state.withLock { $0.heard.map { ($0.0, $0.1) } } }

    func data(for url: URL) async throws -> (Data, Int) {
        let query = Dictionary((URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") }) { a, _ in a }
        return try state.withLock { state in
            guard state.online else { throw URLError(.notConnectedToInternet) }
            let id = query["id"] ?? ""
            guard state.known.contains(id) else {
                return (Data(#"{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Song not found"}}}"#.utf8), 200)
            }
            state.heard.append((id, query["time"] ?? ""))
            return (Data(#"{"subsonic-response":{"status":"ok"}}"#.utf8), 200)
        }
    }
}

@Suite("Plays told to a Subsonic server")
struct SubsonicListenOutboxTests {
    private let server = SubsonicServer(id: "home", name: "Home", url: URL(string: "http://192.168.1.20:4533")!, username: "luke", salt: "abc")

    private func listen(_ id: String, song: String, at seconds: TimeInterval) -> ServerListen {
        ServerListen(id: id, songID: song, startedAt: Date(timeIntervalSince1970: seconds), played: 200, title: "Night Drive", artist: "Mara Solis")
    }

    @Test("plays heard offline are kept, survive a relaunch, and are sent once back online with when they played")
    func offlineThenOnline() async throws {
        let folder = try TemporaryFolder()
        let file = folder.url.appending(path: "server-home-listens.json")
        let scrobbles = ScrobbleServer(online: false, known: ["s1", "s2"])
        let client = SubsonicClient(server: server, password: "pw", transport: scrobbles)

        let outbox = ListenOutbox(file: file)
        await outbox.add(listen("A", song: "s1", at: 1_763_000_000))
        await outbox.add(listen("B", song: "s2", at: 1_763_000_300))
        await #expect(throws: URLError.self) { try await outbox.flush(to: client) }

        let relaunched = ListenOutbox(file: file)
        #expect(await relaunched.listens.count == 2)
        scrobbles.goOnline()
        let report = try await relaunched.flush(to: client)

        #expect(report == ListenOutbox.FlushReport(sent: 2, dropped: 0, waiting: 0))
        #expect(scrobbles.heard.map(\.id) == ["s1", "s2"])
        #expect(scrobbles.heard.map(\.time) == ["1763000000000", "1763000300000"])
        #expect(await ListenOutbox(file: file).listens.isEmpty)
    }

    @Test("a song the server no longer has is let go of, and the plays behind it still go")
    func goneSong() async throws {
        let folder = try TemporaryFolder()
        let scrobbles = ScrobbleServer(online: true, known: ["s2"])
        let client = SubsonicClient(server: server, password: "pw", transport: scrobbles)
        let outbox = ListenOutbox(file: folder.url.appending(path: "listens.json"))
        await outbox.add(listen("A", song: "gone", at: 1_763_000_000))
        await outbox.add(listen("B", song: "s2", at: 1_763_000_300))

        let report = try await outbox.flush(to: client)
        #expect(report == ListenOutbox.FlushReport(sent: 1, dropped: 1, waiting: 0))
        #expect(scrobbles.heard.map(\.id) == ["s2"])
    }
}

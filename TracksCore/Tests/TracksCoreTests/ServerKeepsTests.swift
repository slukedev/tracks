import Testing
import Foundation
@testable import TracksCore

@Suite("Songs asked of a server")
struct ServerKeepsTests {
    let askedAt = Date(timeIntervalSinceReferenceDate: 800_000_000)

    @Test("a song asked for is waited for, and no other")
    func waits() {
        var keeps = ServerKeeps()
        #expect(keeps.isEmpty)
        keeps.ask(for: "night drive|mara solis", at: askedAt)
        #expect(keeps.isWaiting(for: "night drive|mara solis"))
        #expect(keeps.isWaiting(for: "tidal|mara solis") == false)
        #expect(keeps.isEmpty == false)
    }

    @Test("a sync that brings the song in ends the wait, and leaves the others waiting")
    func arrives() {
        var keeps = ServerKeeps()
        keeps.ask(for: "night drive|mara solis", at: askedAt)
        keeps.ask(for: "tidal|mara solis", at: askedAt)
        keeps.settle(arrived: ["night drive|mara solis", "something else|nova harbor"], now: askedAt.addingTimeInterval(180))
        #expect(keeps.isWaiting(for: "night drive|mara solis") == false)
        #expect(keeps.isWaiting(for: "tidal|mara solis"))
    }

    @Test("a song is given up on once a day has passed, not before", arguments: [
        (ServerKeeps.patience - 1, true),
        (ServerKeeps.patience, false),
        (ServerKeeps.patience * 2, false),
    ])
    func patience(waited: TimeInterval, stillWaiting: Bool) {
        var keeps = ServerKeeps()
        keeps.ask(for: "night drive|mara solis", at: askedAt)
        keeps.settle(arrived: [], now: askedAt.addingTimeInterval(waited))
        #expect(keeps.isWaiting(for: "night drive|mara solis") == stillWaiting)
    }

    @Test("asking again starts the wait over")
    func askAgain() {
        var keeps = ServerKeeps()
        keeps.ask(for: "night drive|mara solis", at: askedAt)
        keeps.ask(for: "night drive|mara solis", at: askedAt.addingTimeInterval(ServerKeeps.patience))
        keeps.settle(arrived: [], now: askedAt.addingTimeInterval(ServerKeeps.patience + 60))
        #expect(keeps.isWaiting(for: "night drive|mara solis"))
    }

    @Test("what's waited for survives being saved and read back, names and all")
    func roundTrip() throws {
        var keeps = ServerKeeps()
        keeps.ask(for: "night drive|mara solis", title: "Night Drive", artist: "Mara Solis", at: askedAt)
        keeps.ask(for: "tidal|mara solis", at: askedAt)
        let saved = try JSONEncoder().encode(["home": keeps])
        let read = try JSONDecoder().decode([String: ServerKeeps].self, from: saved)
        #expect(read == ["home": keeps])
    }

    @Test("what an earlier build saved, with no names, still reads")
    func readsOlderSaves() throws {
        let older = Data(#"{"home":{"asked":{"night drive|mara solis":800000000}}}"#.utf8)
        let read = try JSONDecoder().decode([String: ServerKeeps].self, from: older)
        let keeps = try #require(read["home"])
        #expect(keeps.isWaiting(for: "night drive|mara solis"))
        #expect(keeps.waiting.map(\.name) == [nil])
    }

    @Test("what's waited for lists newest first, with its names until it arrives")
    func waitingList() {
        var keeps = ServerKeeps()
        keeps.ask(for: "night drive|mara solis", title: "Night Drive", artist: "Mara Solis", at: askedAt)
        keeps.ask(for: "tidal|mara solis", title: "Tidal", artist: "Mara Solis", at: askedAt.addingTimeInterval(60))
        #expect(keeps.waiting.map(\.name?.title) == ["Tidal", "Night Drive"])
        keeps.settle(arrived: ["tidal|mara solis"], now: askedAt.addingTimeInterval(120))
        #expect(keeps.waiting.map(\.identity) == ["night drive|mara solis"])
        keeps.settle(arrived: ["night drive|mara solis"], now: askedAt.addingTimeInterval(180))
        #expect(keeps.waiting.isEmpty)
        #expect(keeps == ServerKeeps())
    }
}

@Suite("Bringing a server's song down")
struct ServerSongFetchTests {
    @Test("a song the server has downloads, once, whoever asks", arguments: [false, true])
    func inLibrary(tappedGet: Bool) {
        #expect(ServerSongFetch.decide(isInLibrary: true, isOnDevice: false, isWaiting: false, tappedGet: tappedGet) == .download)
        #expect(ServerSongFetch.decide(isInLibrary: true, isOnDevice: true, isWaiting: false, tappedGet: tappedGet) == .nothing)
    }

    @Test("a song the server doesn't have is asked for only when you tap Get, and only once")
    func notInLibrary() {
        for isOnDevice in [false, true] {
            #expect(ServerSongFetch.decide(isInLibrary: false, isOnDevice: isOnDevice, isWaiting: false, tappedGet: false) == .nothing)
            #expect(ServerSongFetch.decide(isInLibrary: false, isOnDevice: isOnDevice, isWaiting: true, tappedGet: false) == .nothing)
            #expect(ServerSongFetch.decide(isInLibrary: false, isOnDevice: isOnDevice, isWaiting: true, tappedGet: true) == .nothing)
        }
        #expect(ServerSongFetch.decide(isInLibrary: false, isOnDevice: false, isWaiting: false, tappedGet: true) == .askServer)
    }
}

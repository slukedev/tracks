import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// A clock that moves on only as the watch sleeps, so twenty minutes pass at once.
private final class PretendClock: Sendable {
    private let time: Mutex<Date>
    private let slept = Mutex<[Duration]>([])

    init(_ start: Date) { time = Mutex(start) }

    var now: Date { time.withLock { $0 } }
    var naps: [Duration] { slept.withLock { $0 } }

    func sleep(_ duration: Duration) async throws {
        try Task.checkCancellation()
        slept.withLock { $0.append(duration) }
        let (seconds, attoseconds) = duration.components
        time.withLock { $0 += TimeInterval(seconds) + TimeInterval(attoseconds) / 1e18 }
        await Task.yield()
    }
}

/// What the server has, and how often it's been asked.
private final class PretendServer: Sendable {
    private let state = Mutex<(has: Set<String>, looks: Int, failing: Bool)>(([], 0, false))

    var looks: Int { state.withLock { $0.looks } }

    func fetch(_ identity: String) { state.withLock { _ = $0.has.insert(identity) } }
    func fail(_ failing: Bool) { state.withLock { $0.failing = failing } }

    func look(_ song: ServerKeeps.Waiting) throws -> Bool {
        try state.withLock {
            $0.looks += 1
            if $0.failing { throw URLError(.cannotConnectToHost) }
            return $0.has.contains(song.identity)
        }
    }
}

@MainActor
@Suite("Watching for songs asked of a server")
struct ServerKeepWatchTests {
    let start = Date(timeIntervalSince1970: 1_763_000_000)

    private func waitUntilDone(_ watch: ServerKeepWatch) async {
        for _ in 0..<10_000 where watch.isWatching { await Task.yield() }
    }

    @Test("one Get syncs once, when the song arrives, however many times it was looked for")
    func oneGetOneSync() async {
        let clock = PretendClock(start)
        let server = PretendServer()
        var keeps = ServerKeeps()
        keeps.ask(for: "night drive|mara solis", title: "Night Drive", artist: "Mara Solis", at: start)
        var syncs = 0
        let watch = ServerKeepWatch(now: { clock.now }, sleep: { try await clock.sleep($0) })
        watch.watch(waiting: { keeps.waiting }, look: { song in
            if clock.now.timeIntervalSince(self.start) > 3 * 60 { server.fetch(song.identity) }
            return try server.look(song)
        }, arrived: {
            syncs += 1
            keeps.settle(arrived: ["night drive|mara solis"], now: clock.now)
        })
        await waitUntilDone(watch)

        #expect(syncs == 1)
        #expect(keeps.isEmpty)
        #expect(server.looks >= 5)
    }

    @Test("a second Get while watching joins the same rounds rather than starting again")
    func concurrentGetsCoalesce() async {
        let clock = PretendClock(start)
        let server = PretendServer()
        var keeps = ServerKeeps()
        keeps.ask(for: "a|x", title: "A", artist: "X", at: start)
        var syncs = 0
        let watch = ServerKeepWatch(now: { clock.now }, sleep: { try await clock.sleep($0) })
        let look: ServerKeepWatch.Look = { try server.look($0) }
        let arrived: @MainActor () async -> Void = {
            syncs += 1
            keeps.settle(arrived: ["a|x", "b|y"], now: clock.now)
        }
        watch.watch(waiting: { keeps.waiting }, look: look, arrived: arrived)
        keeps.ask(for: "b|y", title: "B", artist: "Y", at: start)
        watch.watch(waiting: { keeps.waiting }, look: look, arrived: arrived)
        server.fetch("a|x")
        server.fetch("b|y")
        await waitUntilDone(watch)

        #expect(syncs == 1)
        #expect(server.looks == 2)
    }

    @Test("a song that never comes is let go of after its window, without a sync")
    func neverComes() async {
        let clock = PretendClock(start)
        let server = PretendServer()
        var keeps = ServerKeeps()
        keeps.ask(for: "a|x", title: "A", artist: "X", at: start)
        var syncs = 0
        let watch = ServerKeepWatch(now: { clock.now }, sleep: { try await clock.sleep($0) })
        watch.watch(waiting: { keeps.waiting }, look: { try server.look($0) }, arrived: { syncs += 1 })
        await waitUntilDone(watch)

        #expect(!watch.isWatching)
        #expect(syncs == 0)
        // Every half minute for five minutes, then every two, for twenty: about nineteen looks.
        #expect((12...24).contains(server.looks))
        #expect(clock.now.timeIntervalSince(start) <= 20 * 60 + 120 * 1.2)
    }

    @Test("a server that can't be asked is asked less and less often")
    func backsOff() async {
        let clock = PretendClock(start)
        let server = PretendServer()
        server.fail(true)
        var keeps = ServerKeeps()
        keeps.ask(for: "a|x", title: "A", artist: "X", at: start)
        let watch = ServerKeepWatch(now: { clock.now }, sleep: { try await clock.sleep($0) })
        watch.watch(waiting: { keeps.waiting }, look: { try server.look($0) }, arrived: {})
        await waitUntilDone(watch)

        let naps = clock.naps
        #expect(naps.count < 10)
        #expect(zip(naps, naps.dropFirst()).allSatisfy { $1 > $0 * 1.3 || $1 >= .seconds(400) })
    }

    @Test("stopping ends the watch at once")
    func stops() async {
        let server = PretendServer()
        var keeps = ServerKeeps()
        keeps.ask(for: "a|x", title: "A", artist: "X")
        let watch = ServerKeepWatch()
        watch.watch(waiting: { keeps.waiting }, look: { try server.look($0) }, arrived: {})
        #expect(watch.isWatching)
        watch.stop()
        #expect(!watch.isWatching)
        try? await Task.sleep(for: .milliseconds(20))
        #expect(server.looks == 0)
    }

    @Test("songs saved before names were kept aren't looked for, and end no differently")
    func namelessSongsWait() async {
        let clock = PretendClock(start)
        let server = PretendServer()
        var keeps = ServerKeeps()
        keeps.ask(for: "a|x", at: start)
        let watch = ServerKeepWatch(now: { clock.now }, sleep: { try await clock.sleep($0) })
        watch.watch(waiting: { keeps.waiting }, look: { try server.look($0) }, arrived: {})
        await waitUntilDone(watch)
        #expect(server.looks == 0)
        #expect(!keeps.isEmpty)
    }
}

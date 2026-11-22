import Testing
import Foundation
@testable import TracksCore

@MainActor
@Suite("Syncing a server")
struct ServerSyncsTests {
    @Test("an entry edited during a sync never keeps the old server's songs")
    func editDuringSync() async {
        let syncs = ServerSyncs()
        var catalog: [String] = []
        var releaseOld: CheckedContinuation<Void, Never>?
        var oldStarted = false

        let old = Task {
            await syncs.run("home") { isCurrent in
                oldStarted = true
                await withCheckedContinuation { releaseOld = $0 }
                guard isCurrent() else { return }
                catalog = ["server A's song"]
            }
        }
        while !oldStarted { await Task.yield() }

        syncs.invalidate("home")
        let new = Task {
            await syncs.run("home") { isCurrent in
                guard isCurrent() else { return }
                catalog = ["server B's song"]
            }
        }
        await Task.yield()
        releaseOld?.resume()
        await old.value
        await new.value

        #expect(catalog == ["server B's song"])
    }

    @Test("asking while a sync runs waits for it rather than starting another")
    func joins() async {
        let syncs = ServerSyncs()
        var runs = 0
        var release: CheckedContinuation<Void, Never>?
        let first = Task {
            await syncs.run("home") { _ in
                runs += 1
                await withCheckedContinuation { release = $0 }
            }
        }
        while release == nil { await Task.yield() }
        let second = Task { await syncs.run("home") { _ in runs += 1 } }
        let third = Task { await syncs.run("home") { _ in runs += 1 } }
        await Task.yield()
        release?.resume()
        await first.value
        await second.value
        await third.value
        #expect(runs == 1)
        #expect(!syncs.isRunning("home"))
    }

    @Test("a change while it syncs runs it once more after, however many changes came")
    func runsAgain() async {
        let syncs = ServerSyncs()
        var runs = 0
        var release: CheckedContinuation<Void, Never>?
        #expect(!syncs.runAgainAfterCurrent("home"))
        let running = Task {
            await syncs.run("home") { _ in
                runs += 1
                if runs == 1 { await withCheckedContinuation { release = $0 } }
            }
        }
        while release == nil { await Task.yield() }
        #expect(syncs.runAgainAfterCurrent("home"))
        #expect(syncs.runAgainAfterCurrent("home"))
        release?.resume()
        await running.value
        #expect(runs == 2)
    }

    @Test("a removed server's sync is cancelled and keeps nothing")
    func removed() async {
        let syncs = ServerSyncs()
        var kept = false
        var wasCancelled = false
        var started = false
        let running = Task {
            await syncs.run("home") { isCurrent in
                started = true
                while !Task.isCancelled { await Task.yield() }
                wasCancelled = true
                kept = isCurrent()
            }
        }
        while !started { await Task.yield() }
        syncs.invalidate("home")
        await running.value
        #expect(wasCancelled)
        #expect(!kept)
    }

    @Test("servers sync independently")
    func independent() async {
        let syncs = ServerSyncs()
        var done: [String] = []
        let runs = ["a", "b"].map { id in Task { await syncs.run(id) { _ in done.append(id) } } }
        for run in runs { await run.value }
        #expect(Set(done) == ["a", "b"])
    }
}

import Testing
@testable import TracksCore

/// A refresh that waits at a gate each time it runs, and counts its runs and the ones that were
/// cancelled while they waited. The gate ignores cancellation, as a hung MusicKit request does.
@MainActor
private final class GatedWork {
    private(set) var runs = 0
    private(set) var cancelled = 0
    var timeouts = 0
    private var gates: [CheckedContinuation<Void, Never>] = []

    var waiting: Int { gates.count }

    func run() async {
        runs += 1
        await withCheckedContinuation { gates.append($0) }
        if Task.isCancelled { cancelled += 1 }
    }

    func openAll() {
        let open = gates
        gates = []
        open.forEach { $0.resume() }
    }
}

@MainActor
@Suite("Refreshing one at a time", .timeLimit(.minutes(1)))
struct SerialRefreshTests {
    private func untilWaiting(_ work: GatedWork, _ count: Int = 1) async {
        while work.waiting < count { await Task.yield() }
    }

    private func untilIdle(_ refresh: SerialRefresh) async {
        while refresh.isBusy { await Task.yield() }
    }

    @Test("asks made while a refresh runs fold into one more, which isn't cancelled by them")
    func folds() async {
        let work = GatedWork()
        let refresh = SerialRefresh { await work.run() }
        refresh.request()
        await untilWaiting(work)
        for _ in 0..<5 { refresh.request() }
        work.openAll()
        // The first run finished rather than being thrown away, and one more started.
        await untilWaiting(work)
        #expect(work.runs == 2)
        work.openAll()
        await untilIdle(refresh)
        #expect(work.runs == 2)

        refresh.request()
        await untilWaiting(work)
        #expect(work.runs == 3)
        work.openAll()
        await untilIdle(refresh)
    }

    @Test("cancelling forgets what was owed, and a refresh asked for after it runs in full")
    func cancels() async {
        let work = GatedWork()
        let refresh = SerialRefresh { await work.run() }
        refresh.request()
        await untilWaiting(work)
        refresh.request()
        refresh.cancel()
        #expect(!refresh.isBusy)

        refresh.request()
        await untilWaiting(work, 2)
        // The cancelled run ending mustn't mark the new one as done, or start the owed one.
        work.openAll()
        await untilIdle(refresh)
        #expect(work.runs == 2)
    }

    @Test("a run that hangs past the limit is cancelled and tried once more, then let go")
    func hungRunRestartsOnce() async {
        let work = GatedWork()
        let sleeps = ManualSleeps()
        let refresh = SerialRefresh(onTimeout: { work.timeouts += 1 }, sleep: sleeps.sleep) { await work.run() }
        refresh.request()
        await untilWaiting(work)
        await passLimit(sleeps, until: { work.runs == 2 })
        await passLimit(sleeps, until: { !refresh.isBusy })
        #expect(work.runs == 2)
        #expect(work.timeouts == 2)

        // Hung runs don't hold the tab: the next change is drawn.
        refresh.request()
        await untilWaiting(work, 3)
        #expect(work.runs == 3)
        work.openAll()
        await untilIdle(refresh)
        // Both runs that were given up on were told so, and the one that finished wasn't.
        await settle()
        #expect(work.cancelled == 2)
    }

    @Test("the try after a hung run waits for the hung one to stop")
    func retryWaitsForCancelledRun() async {
        let work = GatedWork()
        let sleeps = ManualSleeps()
        let refresh = SerialRefresh(onTimeout: { work.timeouts += 1 }, sleep: sleeps.sleep) { await work.run() }
        refresh.request()
        await untilWaiting(work)
        await passLimit(sleeps, until: { work.timeouts == 1 })
        await settle()
        #expect(work.runs == 1)

        // It stops as soon as it's let through: only then does the next try begin.
        work.openAll()
        await untilWaiting(work)
        #expect(work.runs == 2)
        #expect(work.cancelled == 1)
        work.openAll()
        await untilIdle(refresh)
    }

    @Test("a run that finishes within the limit is left alone")
    func finishedRunNotRestarted() async {
        let work = GatedWork()
        let sleeps = ManualSleeps()
        let refresh = SerialRefresh(onTimeout: { work.timeouts += 1 }, sleep: sleeps.sleep) { await work.run() }
        refresh.request()
        await untilWaiting(work)
        work.openAll()
        await untilIdle(refresh)
        // The limit passing after the run finished changes nothing.
        sleeps.wake()
        await settle()
        #expect(work.runs == 1)
        #expect(work.cancelled == 0)
        #expect(work.timeouts == 0)
    }

    /// Lets the limit pass, as often as it takes, until `condition` holds.
    private func passLimit(_ sleeps: ManualSleeps, until condition: () -> Bool) async {
        while !condition() {
            sleeps.wake()
            await Task.yield()
        }
    }

    private func settle() async {
        for _ in 0..<50 { await Task.yield() }
    }
}

import Foundation
import Synchronization
import Testing
@testable import TracksCore

@MainActor
@Suite("Lookups given a time budget")
struct BudgetedLookupsTests {
    /// Keys whose lookups only finish when let go, or when cancelled.
    final class Lookups: Sendable {
        private struct State {
            var waiting: [Int: CheckedContinuation<Void, Never>] = [:]
            var letGo: Set<Int> = []
            var running = 0
            var mostRunning = 0
            var started: [Int] = []
            var finished = 0
            var cancelled: Set<Int> = []
        }

        private let state = Mutex(State())
        private let changes: AsyncStream<Void>
        private let change: AsyncStream<Void>.Continuation

        init() {
            (changes, change) = AsyncStream.makeStream()
        }

        var mostRunning: Int { state.withLock { $0.mostRunning } }
        var started: [Int] { state.withLock { $0.started } }
        var finished: Int { state.withLock { $0.finished } }
        var cancelled: Set<Int> { state.withLock { $0.cancelled } }

        /// Returns once `isDone` holds, checked after every start and finish.
        func wait(until isDone: @escaping @Sendable (Lookups) -> Bool) async {
            guard !isDone(self) else { return }
            for await _ in changes where isDone(self) { return }
        }

        func lookUp(_ key: Int) async -> String {
            await withTaskCancellationHandler {
                await withCheckedContinuation { continuation in
                    let isLetGo = state.withLock { state in
                        state.running += 1
                        state.mostRunning = max(state.mostRunning, state.running)
                        state.started.append(key)
                        if state.letGo.contains(key) { return true }
                        state.waiting[key] = continuation
                        return false
                    }
                    change.yield()
                    if isLetGo { continuation.resume() }
                }
            } onCancel: {
                let waiter = state.withLock { state in
                    state.cancelled.insert(key)
                    return state.waiting.removeValue(forKey: key)
                }
                waiter?.resume()
            }
            state.withLock { state in
                state.running -= 1
                state.finished += 1
            }
            change.yield()
            return "found \(key)"
        }

        func letGo(_ keys: some Sequence<Int>) {
            let waiters = state.withLock { state in
                state.letGo.formUnion(keys)
                let ready = state.waiting.filter { state.letGo.contains($0.key) }
                for key in ready.keys { state.waiting[key] = nil }
                return Array(ready.values)
            }
            for waiter in waiters { waiter.resume() }
        }
    }

    @Test(.timeLimit(.minutes(1)))
    func `what's found in time is returned, and what's under way carries on and reports late`() async {
        let lookups = Lookups()
        let sleeps = ManualSleeps()
        lookups.letGo([1, 2])
        let (late, report) = AsyncStream<[Int: String]>.makeStream()
        // One at a time, so the third starting means the first two were kept.
        async let outcome = BudgetedLookups.run([1, 2, 3], atOnce: 1, within: .seconds(1), sleep: sleeps.sleep) { key in
            await lookups.lookUp(key)
        } late: { key, value in
            report.yield([key: value])
        }
        await lookups.wait { $0.started.count == 3 }
        while sleeps.asleep == 0 { await Task.yield() }
        sleeps.wake()
        #expect(await outcome.found == [1: "found 1", 2: "found 2"])

        lookups.letGo([3])
        var reported = late.makeAsyncIterator()
        #expect(await reported.next() == [3: "found 3"])
    }

    @Test(.timeLimit(.minutes(1)))
    func `it returns as soon as every lookup is back, not at the end of the budget`() async {
        let lookups = Lookups()
        let sleeps = ManualSleeps()
        lookups.letGo(0..<10)
        // The budget's sleep is never woken.
        let outcome = await BudgetedLookups.run(Array(0..<10), atOnce: 3, within: .seconds(1), sleep: sleeps.sleep) {
            await lookups.lookUp($0)
        }
        #expect(outcome.found.count == 10)
        #expect(outcome.notStarted.isEmpty)
    }

    @Test(.timeLimit(.minutes(1)))
    func `keys are looked up in the order given, no more at once than asked`() async {
        let lookups = Lookups()
        lookups.letGo(0..<12)
        _ = await BudgetedLookups.run(Array(0..<12), atOnce: 3, within: .seconds(60)) { await lookups.lookUp($0) }
        #expect(lookups.mostRunning <= 3)
        #expect(Array(lookups.started.prefix(3)).sorted() == [0, 1, 2])
    }

    @Test(.timeLimit(.minutes(1)))
    func `once time is up nothing more starts, and what never started is said`() async {
        let lookups = Lookups()
        let sleeps = ManualSleeps()
        let (late, report) = AsyncStream<Int>.makeStream()
        async let outcome = BudgetedLookups.run(Array(0..<6), atOnce: 2, within: .seconds(1), sleep: sleeps.sleep) { key in
            await lookups.lookUp(key)
        } late: { key, _ in
            report.yield(key)
        }
        await lookups.wait { $0.started.count == 2 }
        while sleeps.asleep == 0 { await Task.yield() }
        sleeps.wake()
        let result = await outcome
        #expect(result.found.isEmpty)
        #expect(result.notStarted == [2, 3, 4, 5])

        lookups.letGo([0, 1])
        var reported = late.makeAsyncIterator()
        let lateKeys: Set = [await reported.next(), await reported.next()]
        #expect(lateKeys == [0, 1])
        #expect(lookups.started.sorted() == [0, 1])
    }

    @Test(.timeLimit(.minutes(1)))
    func `a cancelled caller gets what was found so far at once, and the rest is cancelled`() async {
        let lookups = Lookups()
        lookups.letGo([0])
        let waiting = Task {
            await BudgetedLookups.run([0, 1], atOnce: 1, within: .seconds(60)) { await lookups.lookUp($0) }
        }
        await lookups.wait { $0.started.count == 2 }
        waiting.cancel()
        #expect(await waiting.value.found == [0: "found 0"])
        await lookups.wait { $0.cancelled == [1] }
    }
}

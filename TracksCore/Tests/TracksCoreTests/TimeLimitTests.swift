import Foundation
import Synchronization
import Testing
@testable import TracksCore

@Suite("Time limit")
struct TimeLimitTests {
    /// Work that ignores cancellation: it returns only once let go.
    final class Stuck: Sendable {
        private let waiting = Mutex<CheckedContinuation<Void, Never>?>(nil)
        private let isLetGo = Mutex(false)

        func work() async -> Int {
            await withCheckedContinuation { continuation in
                let letGo = isLetGo.withLock { $0 }
                if letGo { continuation.resume() } else { waiting.withLock { $0 = continuation } }
            }
            return 1
        }

        func letGo() {
            isLetGo.withLock { $0 = true }
            waiting.withLock { $0.take() }?.resume()
        }
    }

    @Test func `work back in time is returned`() async {
        let value = await TimeLimit.value(within: .seconds(60)) { 7 }
        #expect(value == 7)
    }

    @Test func `work that ignores cancellation doesn't hold the caller past the limit`() async {
        let stuck = Stuck()
        let value = await TimeLimit.value(within: .seconds(4), sleep: { _ in }) { await stuck.work() }
        #expect(value == nil)
        stuck.letGo()
    }

    @Test func `cancelling the caller returns at once`() async {
        let stuck = Stuck()
        let waiting = Task { await TimeLimit.value(within: .seconds(60)) { await stuck.work() } }
        waiting.cancel()
        #expect(await waiting.value == nil)
        stuck.letGo()
    }

    @Test func `throwing work back in time gives its value or its error`() async throws {
        #expect(try await TimeLimit.run(within: .seconds(60)) { 7 } == 7)
        await #expect(throws: URLError.self) {
            try await TimeLimit.run(within: .seconds(60)) { () async throws -> Int in throw URLError(.notConnectedToInternet) }
        }
    }

    @Test func `throwing work past its limit is given up on`() async {
        let stuck = Stuck()
        await #expect(throws: TimeLimit.Exceeded.self) {
            try await TimeLimit.run(within: .seconds(4), sleep: { _ in }) { await stuck.work() }
        }
        stuck.letGo()
    }

    @Test func `a cancelled caller of throwing work stops with cancellation, not a timeout`() async {
        let stuck = Stuck()
        let waiting = Task { try await TimeLimit.run(within: .seconds(60)) { await stuck.work() } }
        waiting.cancel()
        await #expect(throws: CancellationError.self) { try await waiting.value }
        stuck.letGo()
    }

    @Test func `work let go of stops the wait, and the work is left to finish`() async {
        let stuck = Stuck()
        let letGo = LetGo()
        let waiting = Task { try await TimeLimit.run(until: letGo) { await stuck.work() } }
        letGo.letGo()
        await #expect(throws: CancellationError.self) { try await waiting.value }
        stuck.letGo()
    }

    @Test func `work that finishes before it's let go of gives its value`() async throws {
        #expect(try await TimeLimit.run(until: LetGo()) { 9 } == 9)
    }

    @Test func `a wait that starts after the let go doesn't wait at all`() async {
        let stuck = Stuck()
        let letGo = LetGo()
        letGo.letGo()
        await #expect(throws: CancellationError.self) {
            try await TimeLimit.run(until: letGo) { await stuck.work() }
        }
        stuck.letGo()
    }
}

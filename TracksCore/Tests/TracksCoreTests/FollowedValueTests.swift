import Foundation
import Synchronization
import Testing
@testable import TracksCore

@MainActor
@Suite("A followed value, read live only when it isn't known")
struct FollowedValueTests {
    /// A slow answer that arrives only when let go, and counts how often it was asked for.
    final class Answer: Sendable {
        private let waiting = Mutex<[CheckedContinuation<Void, Never>]>([])
        private let isLetGo = Mutex(false)
        private let asks = Mutex(0)
        private let asked: AsyncStream<Void>
        private let ask: AsyncStream<Void>.Continuation
        let value: Int

        init(_ value: Int) {
            self.value = value
            (asked, ask) = AsyncStream.makeStream()
        }

        var askCount: Int { asks.withLock { $0 } }

        /// Returns once the answer has been asked for.
        func waitUntilAsked() async {
            for await _ in asked { return }
        }

        func read() async throws -> Int {
            asks.withLock { $0 += 1 }
            ask.yield()
            await withCheckedContinuation { continuation in
                let letGo = isLetGo.withLock { $0 }
                if letGo { continuation.resume() } else { waiting.withLock { $0.append(continuation) } }
            }
            return value
        }

        func letGo() {
            isLetGo.withLock { $0 = true }
            let waiters = waiting.withLock { waiting in
                defer { waiting = [] }
                return waiting
            }
            for waiter in waiters { waiter.resume() }
        }
    }

    @Test func `an unknown value is read once, then kept`() async throws {
        let answer = Answer(3)
        answer.letGo()
        let followed = FollowedValue { try await answer.read() }
        #expect(try await followed.value(within: .seconds(60)) == 3)
        #expect(try await followed.value(within: .seconds(60)) == 3)
        #expect(answer.askCount == 1)
    }

    @Test func `callers at the same time share one read`() async throws {
        let answer = Answer(5)
        let followed = FollowedValue { try await answer.read() }
        async let first = followed.value(within: .seconds(60))
        async let second = followed.value(within: .seconds(60))
        await answer.waitUntilAsked()
        answer.letGo()
        #expect(try await first == 5)
        #expect(try await second == 5)
        #expect(answer.askCount == 1)
    }

    @Test(.timeLimit(.minutes(1)))
    func `a read slower than the limit gives nothing in time, and its answer is kept for next time`() async throws {
        let answer = Answer(8)
        let followed = FollowedValue { try await answer.read() }
        let started = ContinuousClock.now
        #expect(try await followed.value(within: .milliseconds(50)) == nil)
        #expect(ContinuousClock.now - started < .seconds(5))

        answer.letGo()
        // The read under way is the one waited for, not a second one.
        #expect(try await followed.value(within: .seconds(60)) == 8)
        #expect(followed.latest == 8)
        #expect(answer.askCount == 1)
    }

    @Test func `an update is used without reading at all`() async throws {
        let answer = Answer(1)
        let followed = FollowedValue { try await answer.read() }
        followed.update(9)
        #expect(try await followed.value(within: .seconds(60)) == 9)
        #expect(answer.askCount == 0)
    }

    @Test(.timeLimit(.minutes(1)))
    func `a read that started before an update doesn't replace it`() async throws {
        let answer = Answer(1)
        // Always due a recheck, so the next call waits on the read still under way.
        let followed = FollowedValue(isSettled: { _ in false }, recheck: .zero) { try await answer.read() }
        #expect(try await followed.value(within: .milliseconds(10)) == nil)
        await answer.waitUntilAsked()
        followed.update(2)
        answer.letGo()
        #expect(try await followed.value(within: .seconds(60)) == 2)
        #expect(followed.latest == 2)
        #expect(answer.askCount == 1)
    }

    @Test func `a value that can change back is asked about again once it's old, and one that can't is kept`() async throws {
        let answer = Answer(7)
        answer.letGo()
        let followed = FollowedValue(isSettled: { $0 > 5 }, recheck: .zero) { try await answer.read() }
        followed.update(3)
        #expect(try await followed.value(within: .seconds(60)) == 7)
        #expect(try await followed.value(within: .seconds(60)) == 7)
        #expect(answer.askCount == 1)
    }

    @Test func `a read that fails says why, and the next call asks again`() async throws {
        let failures = Mutex(0)
        let followed = FollowedValue { () async throws -> Int in
            let count = failures.withLock { $0 += 1; return $0 }
            if count == 1 { throw URLError(.notConnectedToInternet) }
            return 4
        }
        await #expect(throws: URLError.self) { try await followed.value(within: .seconds(60)) }
        #expect(try await followed.value(within: .seconds(60)) == 4)
    }
}

import Synchronization

/// Lookups that get so long and no longer, using what's back by then.
///
/// For extras looked up on the way to playing, such as a clean song's explicit version: the
/// music shouldn't wait on the slowest of them. Once time is up nothing new starts, so the
/// next tap's lookups don't queue behind these; the ones under way carry on and report
/// through `late`, so what they find can be kept for next time.
public enum BudgetedLookups {
    public struct Outcome<Key: Hashable & Sendable, Value: Sendable>: Sendable {
        /// What came back in time.
        public var found: [Key: Value]
        /// Keys whose lookup never started, which no `late` call will report.
        public var notStarted: [Key]
    }

    /// Looks each key up, `atOnce` at a time in the order given, and returns what was found
    /// within `budget`. A cancelled caller gets what was found so far straight away, and the
    /// lookups under way are cancelled.
    /// - Parameter sleep: waits out the budget; tests pass their own.
    public static func run<Key: Hashable & Sendable, Value: Sendable>(
        _ keys: [Key],
        atOnce: Int,
        within budget: Duration,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        lookUp: @escaping @Sendable (Key) async -> Value,
        late: @escaping @Sendable (Key, Value) async -> Void = { _, _ in }
    ) async -> Outcome<Key, Value> {
        guard !keys.isEmpty else { return Outcome(found: [:], notStarted: []) }
        let found = Found<Key, Value>()
        let lookups = Task {
            await withTaskGroup(of: Void.self) { group in
                var pending = keys.makeIterator()
                func startNext() {
                    guard let key = found.start(next: &pending) else { return }
                    group.addTask {
                        let value = await lookUp(key)
                        if !found.keep(value, for: key) { await late(key, value) }
                    }
                }
                for _ in 0..<max(1, atOnce) { startNext() }
                while await group.next() != nil { startNext() }
            }
        }
        await withTaskCancellationHandler {
            _ = await TimeLimit.value(within: budget, sleep: sleep) { await lookups.value }
        } onCancel: {
            lookups.cancel()
        }
        let (values, started) = found.close()
        return Outcome(found: values, notStarted: keys.filter { !started.contains($0) })
    }
}

/// What came back in time, and what was started. Closing it is the deadline: a value kept
/// after that is late, and nothing more starts.
private final class Found<Key: Hashable & Sendable, Value: Sendable>: Sendable {
    private struct State {
        var values: [Key: Value] = [:]
        var started: Set<Key> = []
        var isClosed = false
    }

    private let state = Mutex(State())

    /// The next key to look up, or nil once there are none, or time is up.
    func start(next pending: inout IndexingIterator<[Key]>) -> Key? {
        state.withLock { state in
            guard !state.isClosed, let key = pending.next() else { return nil }
            state.started.insert(key)
            return key
        }
    }

    /// Keeps the value, or says it's too late to.
    func keep(_ value: Value, for key: Key) -> Bool {
        state.withLock { state in
            guard !state.isClosed else { return false }
            state.values[key] = value
            return true
        }
    }

    func close() -> (values: [Key: Value], started: Set<Key>) {
        state.withLock { state in
            state.isClosed = true
            return (state.values, state.started)
        }
    }
}

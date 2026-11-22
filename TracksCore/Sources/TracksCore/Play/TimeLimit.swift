import Synchronization

/// Work given so long and no longer. A task group waits for its children even once they're
/// cancelled, so work that doesn't check for cancellation would hold it past its limit; here
/// the work runs on its own and is left to finish, cancelled, when time runs out.
public enum TimeLimit {
    /// What `work` returns if it's back within `limit`, or nil once `limit` passes, or once
    /// the caller is cancelled.
    /// - Parameter sleep: waits out the limit; tests pass their own.
    public static func value<T: Sendable>(
        within limit: Duration,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        of work: @escaping @Sendable () async -> T
    ) async -> T? {
        await settle(limit: limit, letGo: nil, sleep: sleep, work: work)
    }

    private static func settle<T: Sendable>(
        limit: Duration?,
        letGo: LetGo?,
        sleep: @escaping @Sendable (Duration) async throws -> Void,
        work: @escaping @Sendable () async -> T
    ) async -> T? {
        let race = Race<T>()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                race.start(continuation)
                race.add(Task { race.finish(await work()) })
                if let limit {
                    race.add(Task {
                        try? await sleep(limit)
                        race.finish(nil)
                    })
                }
                letGo?.watch { race.finish(nil) }
            }
        } onCancel: {
            race.finish(nil)
        }
    }

    /// Thrown when work that can fail isn't back within its limit.
    public struct Exceeded: Error, Sendable {
        public init() {}
    }

    /// What `work` returns or throws if it's back within `limit`. Throws ``Exceeded`` once
    /// `limit` passes, or `CancellationError` once the caller is cancelled.
    public static func run<T: Sendable>(
        within limit: Duration,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        of work: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        let outcome = await value(within: limit, sleep: sleep) { () async -> Result<T, any Error> in
            do {
                return .success(try await work())
            } catch {
                return .failure(error)
            }
        }
        try Task.checkCancellation()
        guard let outcome else { throw Exceeded() }
        return try outcome.get()
    }

    /// What `work` returns or throws, however long it takes, unless `letGo` lets go of it
    /// first, or the caller is cancelled: then it throws `CancellationError`, and the work is
    /// left to finish, cancelled.
    public static func run<T: Sendable>(
        until letGo: LetGo,
        of work: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        let outcome = await settle(limit: nil, letGo: letGo, sleep: { _ in }) { () async -> Result<T, any Error> in
            do {
                return .success(try await work())
            } catch {
                return .failure(error)
            }
        }
        guard let outcome else { throw CancellationError() }
        return try outcome.get()
    }
}

/// Lets go, at once, of every wait watching it, and of any that starts watching after.
public final class LetGo: Sendable {
    private struct State {
        var isLetGo = false
        var watchers: [@Sendable () -> Void] = []
    }

    private let state = Mutex(State())

    public init() {}

    public var isLetGo: Bool { state.withLock { $0.isLetGo } }

    public func letGo() {
        let watchers = state.withLock { state in
            state.isLetGo = true
            defer { state.watchers = [] }
            return state.watchers
        }
        for release in watchers { release() }
    }

    fileprivate func watch(_ release: @escaping @Sendable () -> Void) {
        let isLetGo = state.withLock { state in
            if !state.isLetGo { state.watchers.append(release) }
            return state.isLetGo
        }
        if isLetGo { release() }
    }
}

/// Resumes its caller once, with whichever finishes first, and cancels the rest.
private final class Race<T: Sendable>: Sendable {
    private struct State {
        var continuation: CheckedContinuation<T?, Never>?
        var tasks: [Task<Void, Never>] = []
        var isFinished = false
    }

    private let state = Mutex(State())

    func start(_ continuation: CheckedContinuation<T?, Never>) {
        let finishedFirst = state.withLock { state in
            if !state.isFinished { state.continuation = continuation }
            return state.isFinished
        }
        // Cancelled before it began.
        if finishedFirst { continuation.resume(returning: nil) }
    }

    func add(_ task: Task<Void, Never>) {
        let finishedFirst = state.withLock { state in
            if !state.isFinished { state.tasks.append(task) }
            return state.isFinished
        }
        if finishedFirst { task.cancel() }
    }

    func finish(_ value: T?) {
        let (continuation, tasks) = state.withLock { state -> (CheckedContinuation<T?, Never>?, [Task<Void, Never>]) in
            guard !state.isFinished else { return (nil, []) }
            state.isFinished = true
            defer {
                state.continuation = nil
                state.tasks = []
            }
            return (state.continuation, state.tasks)
        }
        for task in tasks { task.cancel() }
        continuation?.resume(returning: value)
    }
}

import Foundation

/// Runs a refresh one at a time. Asked again while one runs, it runs once more when that one is
/// done, however many times it was asked, so a stream of changes can't keep cancelling a slow
/// refresh before it ever shows anything.
///
/// A run that takes longer than `limit` is cancelled and tried once more, without waiting for
/// it to notice: a request that never returns would otherwise hold the refresh, and the tab it
/// draws, for good.
@MainActor
public final class SerialRefresh {
    private let work: @MainActor () async -> Void
    private let limit: Duration
    private let onTimeout: @MainActor () -> Void
    private let sleep: @Sendable (Duration) async throws -> Void
    private var running: Task<Void, Never>?
    private var isOwed = false
    /// Moves on with every cancel, so a run that was cancelled can't clear a newer one.
    private var generation = 0

    /// - Parameter sleep: Waits out the limit; tests pass their own.
    public init(
        limit: Duration = .seconds(20),
        onTimeout: @escaping @MainActor () -> Void = {},
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        _ work: @escaping @MainActor () async -> Void
    ) {
        self.limit = limit
        self.onTimeout = onTimeout
        self.sleep = sleep
        self.work = work
    }

    /// Whether a refresh is running or waiting to.
    public var isBusy: Bool { running != nil }

    /// Refreshes now, or once more after the one under way.
    public func request() {
        guard running == nil else {
            isOwed = true
            return
        }
        let generation = generation
        running = Task { [weak self] in
            while let self, self.generation == generation, !Task.isCancelled {
                self.isOwed = false
                if !(await self.attempt()) {
                    guard self.generation == generation, !Task.isCancelled else { break }
                    _ = await self.attempt()
                }
                guard self.generation == generation, self.isOwed, !Task.isCancelled else { break }
            }
            guard let self, self.generation == generation else { return }
            self.running = nil
        }
    }

    /// Stops the refresh under way, and forgets one that was owed.
    public func cancel() {
        generation &+= 1
        running?.cancel()
        running = nil
        isOwed = false
    }

    /// One run of the work, given up on after `limit`. A run given up on is cancelled, and the
    /// next waits a little for it to stop, so the two don't fetch side by side; but not for
    /// long, since a request that ignores cancellation would hold it as long as before.
    /// - Returns: Whether it finished in time.
    private func attempt() async -> Bool {
        let (work, sleep) = (work, sleep)
        let run = Task { await work() }
        let finished = await TimeLimit.value(within: limit, sleep: sleep) { () async -> Bool in
            await run.value
            return true
        } ?? false
        guard !finished else { return true }
        run.cancel()
        guard !Task.isCancelled else { return false }
        onTimeout()
        _ = await TimeLimit.value(within: Self.stopping, sleep: sleep) { () async -> Bool in
            await run.value
            return true
        }
        return false
    }

    /// How long a cancelled run is given to stop before the next starts anyway.
    static let stopping: Duration = .seconds(5)
}

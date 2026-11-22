/// The latest of a value that rarely changes and is slow to ask for, such as what an Apple
/// Music account can play.
///
/// Updates keep it current. It's read live only while it isn't known yet, or while what's
/// known is the kind worth asking about again (an account that couldn't play, say) and is
/// older than `recheck`: updates can stop coming, and a "no" mustn't stick for good. One read
/// serves every caller waiting, and each waits no longer than it says: over a car's cellular
/// link the read can take many seconds, and someone who just pressed Play shouldn't wait on it.
@MainActor
public final class FollowedValue<Value: Sendable> {
    public private(set) var latest: Value?

    private let read: @Sendable () async throws -> Value
    private let isSettled: (Value) -> Bool
    private let recheck: Duration
    private var latestAt = ContinuousClock.now
    private var reading: Task<Value, any Error>?
    /// Moves on with every update, so a read that started before one doesn't replace it.
    private var generation = 0

    /// - Parameters:
    ///   - isSettled: whether a value can be kept until the next update. One that can't is
    ///     read again once it's older than `recheck`.
    public init(
        isSettled: @escaping (Value) -> Bool = { _ in true },
        recheck: Duration = .seconds(60),
        read: @escaping @Sendable () async throws -> Value
    ) {
        self.isSettled = isSettled
        self.recheck = recheck
        self.read = read
    }

    public func update(_ value: Value) {
        keep(value)
        generation &+= 1
    }

    /// The value, read live if it isn't known yet or is due a recheck. Nil when that read
    /// isn't back within `limit`; it carries on, and its answer is kept for next time. Throws
    /// what the read threw.
    public func value(within limit: Duration) async throws -> Value? {
        if let latest, isSettled(latest) || ContinuousClock.now - latestAt < recheck { return latest }
        let reading = reading ?? startReading()
        do {
            return try await TimeLimit.run(within: limit) { try await reading.value }
        } catch is TimeLimit.Exceeded {
            return nil
        }
    }

    private func keep(_ value: Value) {
        latest = value
        latestAt = .now
    }

    private func startReading() -> Task<Value, any Error> {
        let startedAt = generation
        let task = Task { [read, weak self] in
            defer { self?.reading = nil }
            let value = try await read()
            if let self, self.generation == startedAt { self.keep(value) }
            return self?.latest ?? value
        }
        reading = task
        return task
    }
}

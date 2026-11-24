import Foundation

/// Picks the address a server is reached at from the ones it has: each is asked at once, and
/// a nearby one wins when it answers within a moment of the first answer from elsewhere,
/// since it's the fastest for audio. Otherwise whichever answered first.
public enum AddressRace {
    private enum Outcome: Sendable {
        case answered(Int)
        case failed(Int)
        case graceOver
        case timeUp
    }

    /// - Parameters:
    ///   - isLocal: whether a candidate is on the local network, which is waited for.
    ///   - probe: whether a candidate answered as the server. It should give up by `timeout`.
    /// - Returns: the candidate chosen, or nil when none answered in time.
    public static func pick<Candidate: Sendable>(
        from candidates: [Candidate],
        isLocal: @escaping @Sendable (Candidate) -> Bool,
        localGrace: Duration,
        timeout: Duration,
        sleep: @escaping @Sendable (Duration) async throws -> Void,
        probe: @escaping @Sendable (Candidate) async -> Bool
    ) async -> Candidate? {
        guard !candidates.isEmpty else { return nil }
        let local = candidates.map(isLocal)
        let chosen: Int? = await withTaskGroup(of: Outcome.self) { group in
            for (index, candidate) in candidates.enumerated() {
                group.addTask { await probe(candidate) ? .answered(index) : .failed(index) }
            }
            group.addTask {
                try? await sleep(timeout)
                return .timeUp
            }
            var waiting = candidates.count
            var localWaiting = local.count(where: { $0 })
            var fastest: Int?
            for await outcome in group {
                switch outcome {
                case .answered(let index):
                    waiting -= 1
                    if local[index] {
                        group.cancelAll()
                        return index
                    }
                    if fastest == nil {
                        fastest = index
                        group.addTask {
                            try? await sleep(localGrace)
                            return .graceOver
                        }
                    }
                case .failed(let index):
                    waiting -= 1
                    if local[index] { localWaiting -= 1 }
                case .graceOver, .timeUp:
                    group.cancelAll()
                    return fastest
                }
                if waiting == 0 || (fastest != nil && localWaiting == 0) {
                    group.cancelAll()
                    return fastest
                }
            }
            return fastest
        }
        return chosen.map { candidates[$0] }
    }
}

/// How often, and after how long, a request that can safely be sent again is retried: a
/// read, never a write the server might already have taken.
public struct RetryPolicy: Sendable, Equatable {
    /// Tries in all, the first included.
    public var attempts: Int
    public var firstDelay: Duration
    public var longestDelay: Duration

    public init(attempts: Int = 3, firstDelay: Duration = .milliseconds(400), longestDelay: Duration = .seconds(4)) {
        self.attempts = max(1, attempts)
        self.firstDelay = firstDelay
        self.longestDelay = longestDelay
    }

    public static let none = RetryPolicy(attempts: 1)

    /// The wait before try `attempt + 1`, doubling each time, with up to a quarter more at
    /// random so many devices back off apart. `jitter` is in 0...1.
    public func delay(after attempt: Int, jitter: Double) -> Duration {
        let doublings = max(0, min(attempt - 1, 16))
        var delay = firstDelay * (1 << doublings)
        if delay > longestDelay { delay = longestDelay }
        return delay + delay * (min(max(jitter, 0), 1) / 4)
    }

    /// Runs `work`, again after a wait while it fails with an error `isTransient` accepts.
    /// The last failure is thrown. Cancellation stops it at once.
    public func run<Value: Sendable>(
        isTransient: @Sendable (any Error) -> Bool,
        sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        _ work: @Sendable () async throws -> Value
    ) async throws -> Value {
        var attempt = 1
        while true {
            do {
                return try await work()
            } catch {
                guard attempt < attempts, !(error is CancellationError), isTransient(error) else { throw error }
                try await sleep(delay(after: attempt, jitter: Double.random(in: 0...1)))
                attempt += 1
            }
        }
    }
}

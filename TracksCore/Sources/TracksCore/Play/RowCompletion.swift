import Foundation

/// A CarPlay row's completion, called exactly once: when what the row started is done, or
/// after `limit` if that comes first. The car spins the row until it's called, and a song or
/// page waiting on a lost connection could otherwise leave it spinning for a minute.
@MainActor
public final class RowCompletion {
    private var completion: (() -> Void)?
    /// Whether `limit` passed first, so what the row started has been given up on.
    public private(set) var isLate = false

    /// - Parameter sleep: Waits out the limit; tests pass their own.
    public init(
        limit: Duration = .seconds(8),
        onLate: @escaping @MainActor () -> Void = {},
        sleep: @escaping @MainActor (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        _ completion: @escaping () -> Void
    ) {
        self.completion = completion
        // Held until the limit passes, so a row whose work dropped it still stops spinning.
        Task {
            try? await sleep(limit)
            guard self.completion != nil else { return }
            isLate = true
            onLate()
            call()
        }
    }

    /// Calls the completion, if it hasn't been called yet.
    /// - Returns: Whether this was the call that reached the car.
    @discardableResult
    public func call() -> Bool {
        guard let completion else { return false }
        self.completion = nil
        completion()
        return true
    }
}

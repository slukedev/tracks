import Synchronization

/// What whoever starts something playing can ask of the player on the way, without every
/// layer in between passing it along:
///
/// ```swift
/// await PlayStart.run(mayAskForAccess: false, whenQueued: { row.call() }) {
///     await player.start(request, from: context)
/// }
/// ```
///
/// It lasts only as long as `body`. Tasks that `body` starts and that outlive it, such as
/// Autoplay following on, inherit it but see the defaults once `body` has returned.
public enum PlayStart {
    /// - Parameters:
    ///   - mayAskForAccess: whether Apple Music access may be asked for if it hasn't been.
    ///     Off where a prompt would land on a phone no one is looking at, such as in the car:
    ///     the request then fails with a problem saying so.
    ///   - whenQueued: called once, on the main actor, as soon as the player has the new
    ///     queue and before it has started, which can take seconds more. Not called if the
    ///     request fails before it has a queue, or by a player that starts at once.
    nonisolated(nonsending) public static func run<T>(
        mayAskForAccess: Bool = true,
        whenQueued: (@MainActor @Sendable () -> Void)? = nil,
        _ body: nonisolated(nonsending) () async throws -> T
    ) async rethrows -> T {
        let request = Request(mayAskForAccess: mayAskForAccess, whenQueued: whenQueued)
        defer { request.end() }
        return try await $current.withValue(request, operation: body)
    }

    /// Whether the request under way may ask for Apple Music access.
    public static var mayAskForAccess: Bool {
        current?.mayAskForAccess ?? true
    }

    /// Tells whoever started the request under way that its queue is set.
    @MainActor
    public static func queued() {
        current?.takeWhenQueued()?()
    }

    @TaskLocal private static var current: Request?

    private final class Request: Sendable {
        private struct State {
            var hasEnded = false
            var whenQueued: (@MainActor @Sendable () -> Void)?
        }

        private let asks: Bool
        private let state: Mutex<State>

        init(mayAskForAccess: Bool, whenQueued: (@MainActor @Sendable () -> Void)?) {
            asks = mayAskForAccess
            state = Mutex(State(whenQueued: whenQueued))
        }

        var mayAskForAccess: Bool {
            state.withLock { $0.hasEnded } || asks
        }

        func takeWhenQueued() -> (@MainActor @Sendable () -> Void)? {
            state.withLock { state in
                defer { state.whenQueued = nil }
                return state.hasEnded ? nil : state.whenQueued
            }
        }

        func end() {
            state.withLock { state in
                state.hasEnded = true
                state.whenQueued = nil
            }
        }
    }
}

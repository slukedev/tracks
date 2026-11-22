import Foundation

/// Moves between screens in the car, one at a time. CarPlay ends the app when a screen is
/// pushed that's already on the trail, or a sheet is presented over another, and over a
/// wireless connection a push can take seconds to land. So every move runs here: the next
/// waits for the one before, and screens on their way count as on the trail until CarPlay says
/// they've arrived, even after the move that sent them has been given up on.
///
/// Knows screens only by identity, so it can be tested without a car.
@MainActor
public final class CarMover {
    /// What to do with a move asked for while another runs.
    public enum WhenBusy: Equatable, Sendable {
        /// Turned away, as a second tap is: its completion comes at once.
        case drop
        /// Run once the car is free. A newer move of the same kind takes its place.
        case wait(String)
    }

    /// The steps of one move, each checked against what's on the car's trail and on its way.
    @MainActor
    public struct Steps {
        fileprivate let mover: CarMover
        fileprivate let token: Int

        /// Screens on the trail, the root included, and pushes still on their way.
        public var depth: Int { mover.depth }

        public func isOnTrail(_ screen: ObjectIdentifier) -> Bool { mover.isOnTrail(screen) }

        /// Pushes a screen, unless it's on the trail or already on its way. It counts as on
        /// its way until `perform` returns, however long that takes.
        @discardableResult
        public func push(_ screen: ObjectIdentifier, _ perform: @MainActor () async throws -> Void) async throws -> Bool {
            try mover.check(token)
            guard !mover.isOnTrail(screen) else { return false }
            let sent = mover.send(screen)
            defer { mover.arrived(screen, sent) }
            try await perform()
            try mover.check(token)
            return true
        }

        /// Goes back, unless a push is still on its way: popping under it leaves CarPlay with
        /// a trail it didn't expect.
        @discardableResult
        public func pop(_ perform: @MainActor () async throws -> Void) async throws -> Bool {
            try mover.check(token)
            guard !mover.hasPushOnItsWay else { return false }
            try await perform()
            try mover.check(token)
            return true
        }

        /// Presents or dismisses a sheet, unless another is still on its way.
        @discardableResult
        public func present(_ perform: @MainActor () async throws -> Void) async throws -> Bool {
            try mover.check(token)
            guard !mover.hasSheetOnItsWay else { return false }
            let sent = mover.sendSheet()
            defer { mover.sheetArrived(sent) }
            try await perform()
            try mover.check(token)
            return true
        }
    }

    /// Thrown into a move's remaining steps once it has been let go.
    private struct LetGo: Error {}

    private struct Move {
        let name: String
        let token: Int
        let completion: @MainActor () -> Void
        var hasTimedOut = false
        var task: Task<Void, Never>?
        var timer: Task<Void, Never>?
    }

    private struct Waiting {
        let kind: String
        let name: String
        let steps: @MainActor (Steps) async throws -> Void
        let completion: @MainActor () -> Void
    }

    private let timeout: Duration
    private let giveUp: Duration
    private let inFlightExpiry: Duration
    private let trail: @MainActor () -> [ObjectIdentifier]
    private let onError: @MainActor (String, any Error) -> Void
    private let onStuck: @MainActor (String) -> Void
    private let sleep: @Sendable (Duration) async throws -> Void
    private let now: @MainActor () -> ContinuousClock.Instant

    private var current: Move?
    private var waiting: [Waiting] = []
    private var tokens = 0
    /// Pushes sent and not yet back, by screen: when each left, and which send it was.
    private var onItsWay: [ObjectIdentifier: (since: ContinuousClock.Instant, send: Int)] = [:]
    private var sheetOnItsWay: (since: ContinuousClock.Instant, send: Int)?

    /// - Parameters:
    ///   - timeout: How long a move may take before its completion is called anyway. It still
    ///     holds the car until CarPlay reports back, the trail changes, or `giveUp` passes.
    ///   - giveUp: How long a move may hold the car at most. A screen it sent still counts as on
    ///     its way after that, so letting go can't send it twice.
    ///   - inFlightExpiry: How long a screen sent and never reported back counts as on its way.
    ///   - trail: The screens on the car's trail now.
    ///   - sleep: Waits out the timeout; tests pass their own, as they do `now`.
    public init(
        timeout: Duration = .seconds(6),
        giveUp: Duration = .seconds(15),
        inFlightExpiry: Duration = .seconds(30),
        trail: @escaping @MainActor () -> [ObjectIdentifier],
        onError: @escaping @MainActor (String, any Error) -> Void = { _, _ in },
        onStuck: @escaping @MainActor (String) -> Void = { _ in },
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        now: @escaping @MainActor () -> ContinuousClock.Instant = { .now }
    ) {
        self.timeout = timeout
        self.giveUp = giveUp
        self.inFlightExpiry = inFlightExpiry
        self.trail = trail
        self.onError = onError
        self.onStuck = onStuck
        self.sleep = sleep
        self.now = now
    }

    /// Whether a move holds the car.
    public var isMoving: Bool { current != nil }

    /// Whether a screen is on the trail, or on its way there.
    public func isOnTrail(_ screen: ObjectIdentifier) -> Bool {
        trail().contains(screen) || isOnItsWay(screen)
    }

    /// Screens on the trail and pushes on their way, which CarPlay counts against its depth as
    /// soon as they land.
    public var depth: Int {
        let trail = trail()
        return trail.count + liveOnItsWay().filter { !trail.contains($0) }.count
    }

    /// Runs a move now, or as `whenBusy` says when another holds the car. `completion` is
    /// called once: when the move ends, when it takes longer than the timeout, or straight
    /// away when it's turned away or replaced.
    /// - Returns: Whether the move will run.
    @discardableResult
    public func run(
        _ name: String,
        whenBusy: WhenBusy = .drop,
        _ steps: @escaping @MainActor (Steps) async throws -> Void,
        completion: @escaping @MainActor () -> Void = {}
    ) -> Bool {
        guard current != nil else {
            start(name, steps, completion: completion)
            return true
        }
        switch whenBusy {
        case .drop:
            completion()
            return false
        case .wait(let kind):
            let move = Waiting(kind: kind, name: name, steps: steps, completion: completion)
            if let index = waiting.firstIndex(where: { $0.kind == kind }) {
                let replaced = waiting[index]
                waiting[index] = move
                replaced.completion()
            } else {
                waiting.append(move)
            }
            return true
        }
    }

    /// The car's trail changed: a screen appeared or went. A move that has already timed out is
    /// let go, since CarPlay has moved on, and screens that have landed no longer count as on
    /// their way.
    public func trailChanged() {
        let trail = trail()
        for screen in onItsWay.keys where trail.contains(screen) {
            onItsWay[screen] = nil
        }
        guard let current, current.hasTimedOut else { return }
        letGo(current)
        self.current = nil
        startNext()
    }

    /// The car has gone: everything waiting is dropped, the move under way let go.
    public func reset() {
        let dropped = waiting
        waiting = []
        if let current {
            letGo(current)
            self.current = nil
            if !current.hasTimedOut { current.completion() }
        }
        onItsWay = [:]
        sheetOnItsWay = nil
        dropped.forEach { $0.completion() }
    }

    // MARK: - Running

    private func start(_ name: String, _ steps: @escaping @MainActor (Steps) async throws -> Void, completion: @escaping @MainActor () -> Void) {
        tokens &+= 1
        let token = tokens
        var move = Move(name: name, token: token, completion: completion)
        let handle = Steps(mover: self, token: token)
        move.task = Task { [weak self] in
            do {
                try await steps(handle)
            } catch is LetGo {
                return
            } catch {
                self?.onError(name, error)
            }
            self?.finish(token)
        }
        let (timeout, rest, sleep) = (timeout, giveUp - timeout, sleep)
        move.timer = Task { [weak self] in
            try? await sleep(timeout)
            guard !Task.isCancelled else { return }
            self?.timedOut(token)
            // A push that never reports back and never lands would otherwise hold every move
            // after it until the car disconnects.
            try? await sleep(rest)
            guard !Task.isCancelled else { return }
            self?.gaveUp(token)
        }
        current = move
    }

    private func finish(_ token: Int) {
        guard let move = current, move.token == token else { return }
        move.timer?.cancel()
        current = nil
        if !move.hasTimedOut { move.completion() }
        startNext()
    }

    private func timedOut(_ token: Int) {
        guard var move = current, move.token == token, !move.hasTimedOut else { return }
        move.hasTimedOut = true
        current = move
        onStuck(move.name)
        move.completion()
    }

    private func gaveUp(_ token: Int) {
        guard let move = current, move.token == token else { return }
        letGo(move)
        current = nil
        startNext()
    }

    private func letGo(_ move: Move) {
        move.timer?.cancel()
        move.task?.cancel()
    }

    private func startNext() {
        guard current == nil, !waiting.isEmpty else { return }
        let next = waiting.removeFirst()
        start(next.name, next.steps, completion: next.completion)
    }

    fileprivate func check(_ token: Int) throws {
        guard current?.token == token else { throw LetGo() }
    }

    // MARK: - On its way

    private var sends = 0

    fileprivate func send(_ screen: ObjectIdentifier) -> Int {
        sends &+= 1
        onItsWay[screen] = (now(), sends)
        return sends
    }

    fileprivate func arrived(_ screen: ObjectIdentifier, _ send: Int) {
        // Only this send's: one that expired may have been sent again since.
        if onItsWay[screen]?.send == send { onItsWay[screen] = nil }
    }

    fileprivate func sendSheet() -> Int {
        sends &+= 1
        sheetOnItsWay = (now(), sends)
        return sends
    }

    fileprivate func sheetArrived(_ send: Int) {
        if sheetOnItsWay?.send == send { sheetOnItsWay = nil }
    }

    private func isOnItsWay(_ screen: ObjectIdentifier) -> Bool {
        guard let sent = onItsWay[screen] else { return false }
        return sent.since.duration(to: now()) < inFlightExpiry
    }

    private func liveOnItsWay() -> [ObjectIdentifier] {
        onItsWay.keys.filter(isOnItsWay)
    }

    fileprivate var hasPushOnItsWay: Bool {
        let trail = trail()
        return liveOnItsWay().contains { !trail.contains($0) }
    }

    fileprivate var hasSheetOnItsWay: Bool {
        guard let sent = sheetOnItsWay else { return false }
        return sent.since.duration(to: now()) < inFlightExpiry
    }
}

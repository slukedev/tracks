import Testing
@testable import TracksCore

/// A car that holds each step until the test lets it finish, as a slow wireless connection
/// does, and keeps the trail of screens a real one would.
@MainActor
private final class SlowCar {
    var trail: [ObjectIdentifier] = []
    private(set) var started: [String] = []
    private var held: [(name: String, step: CheckedContinuation<Void, any Error>)] = []

    var holding: [String] { held.map(\.name) }

    func step(_ name: String) async throws {
        started.append(name)
        try await withCheckedThrowingContinuation { held.append((name, $0)) }
    }

    /// Lets the held step finish, with the screen it pushed on the trail.
    func finish(_ name: String, landing screen: ObjectIdentifier? = nil) {
        guard let index = held.firstIndex(where: { $0.name == name }) else {
            Issue.record("Nothing held called \(name)")
            return
        }
        let step = held.remove(at: index).step
        if let screen { trail.append(screen) }
        step.resume()
    }

    func fail(_ name: String) {
        guard let index = held.firstIndex(where: { $0.name == name }) else { return }
        held.remove(at: index).step.resume(throwing: CarError())
    }

    func releaseAll() {
        let all = held
        held = []
        all.forEach { $0.step.resume() }
    }
}

private struct CarError: Error {}

/// Stands in for a template: only its identity matters.
private final class Screen {}

@MainActor
private final class Log {
    var finished: [String] = []
    var errors: [String] = []
    var stuck: [String] = []
    /// What a step said: whether it was let through to the car.
    var allowed: [String: Bool] = [:]
}

/// The time the mover reads, moved on by the test.
@MainActor
private final class TestTime {
    var now = ContinuousClock.now
}

@MainActor
@Suite("Moving between screens in the car", .timeLimit(.minutes(1)))
struct CarMoverTests {
    private let car = SlowCar()
    private let log = Log()
    private let sleeps = ManualSleeps()
    private let time = TestTime()

    private func mover() -> CarMover {
        let (car, log, time) = (car, log, time)
        return CarMover(
            inFlightExpiry: .seconds(30),
            trail: { car.trail },
            onError: { name, _ in log.errors.append(name) },
            onStuck: { name in log.stuck.append(name) },
            sleep: sleeps.sleep,
            now: { time.now }
        )
    }

    /// Lets the move called `name` run past its timeout.
    private func timeOut(_ name: String) async {
        while !log.stuck.contains(name) {
            sleeps.wake()
            await Task.yield()
        }
    }

    private func until(_ condition: () -> Bool) async {
        while !condition() { await Task.yield() }
    }

    /// Gives what was let go a moment to run.
    private func settle() async {
        for _ in 0..<50 { await Task.yield() }
    }

    /// Runs a move that pushes `screen`, finishing into the log under `name`.
    @discardableResult
    private func push(_ screen: Screen, _ name: String, on mover: CarMover, whenBusy: CarMover.WhenBusy = .drop) -> Bool {
        let (car, log) = (car, log)
        let id = ObjectIdentifier(screen)
        return mover.run(name, whenBusy: whenBusy, { steps in
            try await steps.push(id) { try await car.step(name) }
        }, completion: { log.finished.append(name) })
    }

    @Test("a tap while a move runs is turned away at once, and the move under way finishes")
    func secondTapTurnedAway() async {
        let mover = mover()
        let (album, playlist) = (Screen(), Screen())
        #expect(push(album, "album", on: mover))
        await until { car.holding == ["album"] }
        #expect(!push(playlist, "playlist", on: mover))
        #expect(log.finished == ["playlist"])
        #expect(mover.isMoving)

        car.finish("album", landing: ObjectIdentifier(album))
        await until { !mover.isMoving }
        #expect(log.finished == ["playlist", "album"])
        #expect(car.started == ["album"])
    }

    @Test("a screen on its way isn't pushed again after the move gives up, until it lands and leaves")
    func inFlightRefusedAfterTimeout() async {
        let mover = mover()
        let nowPlaying = Screen()
        let id = ObjectIdentifier(nowPlaying)
        push(nowPlaying, "now playing", on: mover)
        await timeOut("now playing")
        // The row stops spinning, but nothing else may move while CarPlay is still busy.
        #expect(log.finished == ["now playing"])
        #expect(!push(Screen(), "other", on: mover))

        // The car moved on (another screen appeared): free to move, but not to push it twice.
        mover.trailChanged()
        #expect(!mover.isMoving)
        #expect(mover.isOnTrail(id))
        push(nowPlaying, "now playing again", on: mover)
        await until { !mover.isMoving }
        #expect(car.started == ["now playing"])

        car.finish("now playing", landing: id)
        await settle()
        car.trail.removeAll()
        mover.trailChanged()
        #expect(!mover.isOnTrail(id))
        push(nowPlaying, "now playing at last", on: mover)
        await until { car.holding == ["now playing at last"] }
        car.releaseAll()
    }

    @Test("after a timeout, the late completion frees the mover for the next tap")
    func lateCompletionFrees() async {
        let mover = mover()
        let page = Screen()
        push(page, "page", on: mover)
        await timeOut("page")
        #expect(mover.isMoving)

        car.finish("page", landing: ObjectIdentifier(page))
        await until { !mover.isMoving }
        #expect(push(Screen(), "next", on: mover))
        await until { car.holding == ["next"] }
        car.releaseAll()
    }

    @Test("a push that never reports back and never lands frees the car in the end, but isn't sent again")
    func hardCap() async {
        let mover = mover()
        let page = Screen()
        push(page, "page", on: mover)
        await timeOut("page")
        #expect(mover.isMoving)
        while mover.isMoving {
            sleeps.wake()
            await Task.yield()
        }

        let other = Screen()
        #expect(push(other, "other", on: mover))
        await until { car.holding.contains("other") }
        car.finish("other", landing: ObjectIdentifier(other))
        await until { !mover.isMoving }
        push(page, "page again", on: mover)
        await until { !mover.isMoving }
        #expect(car.started == ["page", "other"])
        car.releaseAll()
    }

    @Test("a move let go after its timeout takes none of its remaining steps")
    func abandonedMoveStops() async {
        let mover = mover()
        let (car, log) = (car, log)
        let page = ObjectIdentifier(Screen())
        mover.run("restart trail", { steps in
            try await steps.pop { try await car.step("pop to root") }
            try await steps.push(page) { try await car.step("push page") }
        }, completion: { log.finished.append("restart trail") })
        await timeOut("restart trail")
        mover.trailChanged()

        car.finish("pop to root")
        await settle()
        #expect(car.started == ["pop to root"])
        #expect(log.finished == ["restart trail"])
        #expect(log.errors.isEmpty)
    }

    @Test("a pop waits for a push still on its way; a sheet doesn't")
    func popsWaitForPushes() async {
        let mover = mover()
        let (car, log) = (car, log)
        push(Screen(), "page", on: mover)
        await timeOut("page")
        mover.trailChanged()

        mover.run("back", { steps in
            log.allowed["pop"] = try await steps.pop { try await car.step("pop") }
            log.allowed["sheet"] = try await steps.present { try await car.step("sheet") }
        })
        await until { car.holding.contains("sheet") }
        #expect(log.allowed["pop"] == false)
        #expect(car.started == ["page", "sheet"])
        car.releaseAll()
    }

    @Test("a second sheet waits for one still on its way, even after its move gave up")
    func sheetInFlight() async {
        let mover = mover()
        let (car, log) = (car, log)
        mover.run("first sheet", { steps in
            try await steps.present { try await car.step("first sheet") }
        })
        await timeOut("first sheet")
        mover.trailChanged()
        mover.run("second sheet", { steps in
            log.allowed["second sheet"] = try await steps.present { try await car.step("second sheet") }
        }, completion: { log.finished.append("second sheet") })
        await until { log.finished == ["second sheet"] }
        #expect(log.allowed["second sheet"] == false)

        car.finish("first sheet")
        await settle()
        mover.run("third sheet", { steps in
            try await steps.present { try await car.step("third sheet") }
        })
        await until { car.holding == ["third sheet"] }
        car.releaseAll()
    }

    @Test("waiting moves run in turn once the car is free, the newest of a kind replacing the older")
    func queuedMovesRunInTurn() async {
        let mover = mover()
        let (car, log) = (car, log)
        let page = Screen()
        push(page, "page", on: mover)
        await until { car.holding == ["page"] }

        for name in ["sheet 1", "sheet 2"] {
            mover.run(name, whenBusy: .wait("sheet"), { _ in try await car.step(name) }, completion: { log.finished.append(name) })
        }
        mover.run("now playing", whenBusy: .wait("nowPlaying"), { _ in try await car.step("now playing") }, completion: { log.finished.append("now playing") })
        // Replaced before it ran: its completion comes now, and it never reaches the car.
        #expect(log.finished == ["sheet 1"])

        car.finish("page", landing: ObjectIdentifier(page))
        await until { car.holding == ["sheet 2"] }
        car.finish("sheet 2")
        await until { car.holding == ["now playing"] }
        car.finish("now playing")
        await until { !mover.isMoving }
        #expect(car.started == ["page", "sheet 2", "now playing"])
        #expect(log.finished == ["sheet 1", "page", "sheet 2", "now playing"])
    }

    @Test("a step CarPlay refuses ends the move, is reported, and leaves the screen free to push")
    func failedStep() async {
        let mover = mover()
        let page = Screen()
        push(page, "page", on: mover)
        await until { car.holding == ["page"] }
        car.fail("page")
        await until { !mover.isMoving }
        #expect(log.errors == ["page"])
        #expect(log.finished == ["page"])
        #expect(!mover.isOnTrail(ObjectIdentifier(page)))
        #expect(push(page, "page again", on: mover))
        await until { car.holding == ["page again"] }
        car.releaseAll()
    }

    @Test("a push that never reports back is forgotten after the expiry")
    func inFlightExpires() async {
        let mover = mover()
        let page = Screen()
        let id = ObjectIdentifier(page)
        push(page, "page", on: mover)
        await timeOut("page")
        mover.trailChanged()
        time.now += .seconds(29)
        #expect(mover.isOnTrail(id))
        #expect(mover.depth == 1)
        time.now += .seconds(2)
        #expect(!mover.isOnTrail(id))
        #expect(mover.depth == 0)
        car.releaseAll()
    }

    @Test("the trail's depth counts pushes still on their way")
    func depthCountsInFlight() async {
        let mover = mover()
        let root = Screen()
        car.trail = [ObjectIdentifier(root)]
        push(Screen(), "page", on: mover)
        await until { car.holding == ["page"] }
        #expect(mover.depth == 2)
        car.releaseAll()
    }

    @Test("the car going away drops what was waiting, and the old move's late finish changes nothing")
    func resetOnDisconnect() async {
        let mover = mover()
        let (car, log) = (car, log)
        let page = Screen()
        push(page, "page", on: mover)
        await until { car.holding == ["page"] }
        mover.run("sheet", whenBusy: .wait("sheet"), { _ in try await car.step("sheet") }, completion: { log.finished.append("sheet") })

        mover.reset()
        #expect(!mover.isMoving)
        #expect(Set(log.finished) == ["page", "sheet"])
        #expect(!mover.isOnTrail(ObjectIdentifier(page)))

        // A new move starts, then the old one's completion arrives: the new one is still under way.
        push(Screen(), "fresh", on: mover)
        await until { car.holding.contains("fresh") }
        car.finish("page")
        await settle()
        #expect(mover.isMoving)
        #expect(log.finished.count == 2)
        #expect(!car.started.contains("sheet"))
        car.releaseAll()
    }
}

import Testing
@testable import TracksCore

@MainActor
@Suite("A CarPlay row's completion", .timeLimit(.minutes(1)))
struct RowCompletionTests {
    /// Calls counted, so a second call to the car's completion shows up.
    @MainActor
    private final class Calls {
        var count = 0
        var late = 0
    }

    private let sleeps = ManualSleeps()
    private let calls = Calls()

    private func row() -> RowCompletion {
        let calls = calls
        return RowCompletion(onLate: { calls.late += 1 }, sleep: sleeps.onMain) { calls.count += 1 }
    }

    /// Lets the limit pass, as often as it takes, until `condition` holds.
    private func passLimit(until condition: () -> Bool) async {
        while !condition() {
            sleeps.wake()
            await Task.yield()
        }
    }

    @Test("reaches the car once when the work finishes first, and not again when the limit passes")
    func finishesFirst() async {
        let row = row()
        #expect(row.call())
        #expect(!row.call())
        await passLimit(until: { sleeps.asleep > 0 })
        sleeps.wake()
        for _ in 0..<5 { await Task.yield() }
        #expect(calls.count == 1)
        #expect(calls.late == 0)
        #expect(!row.isLate)
    }

    @Test("reaches the car at the limit when the work never finishes, and not again when it does")
    func limitFirst() async {
        let row = row()
        await passLimit(until: { row.isLate })
        #expect(calls.count == 1)
        #expect(calls.late == 1)
        #expect(!row.call())
        #expect(calls.count == 1)
    }

    @Test("reaches the car at the limit even when what the row started let go of it")
    func droppedStillFinishes() async {
        _ = row()
        await passLimit(until: { calls.count == 1 })
        #expect(calls.late == 1)
    }
}

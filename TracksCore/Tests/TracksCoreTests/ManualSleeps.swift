/// Sleeps that end when the test says, not when the clock does, so a timeout is the test's
/// decision rather than the machine's load. Whatever is still asleep wakes when it goes.
@MainActor
final class ManualSleeps {
    private var sleepers: [CheckedContinuation<Void, Never>] = []

    var asleep: Int { sleepers.count }

    nonisolated var sleep: @Sendable (Duration) async throws -> Void {
        { [weak self] _ in await self?.wait() }
    }

    /// The same, run on the main actor, so a wake is seen by the sleeper's next turn there.
    var onMain: @MainActor (Duration) async throws -> Void {
        { [weak self] _ in await self?.wait() }
    }

    private func wait() async {
        await withCheckedContinuation { sleepers.append($0) }
    }

    /// Ends every sleep under way.
    func wake() {
        let waking = sleepers
        sleepers = []
        waking.forEach { $0.resume() }
    }

    isolated deinit {
        wake()
    }
}

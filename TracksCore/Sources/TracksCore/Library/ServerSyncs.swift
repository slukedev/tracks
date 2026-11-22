import Foundation

/// The syncs of your music servers: one at a time for each server, shared by everyone who asks
/// while it runs, and tied to the server as it was when it started.
///
/// A server's entry can be changed while it syncs: another address, another account, another
/// kind of server. What a sync of the old one found must not be kept under the entry, so each
/// change is a new generation, the running sync is cancelled, and a sync only keeps what it
/// found while its generation is still the current one.
@MainActor
public final class ServerSyncs {
    private struct Run {
        let id: UUID
        let generation: Int
        let task: Task<Void, Never>
    }

    private var generations: [String: Int] = [:]
    private var runs: [String: Run] = [:]
    private var again: Set<String> = []

    public init() {}

    public func generation(of serverID: String) -> Int {
        generations[serverID, default: 0]
    }

    public func isCurrent(_ serverID: String, _ generation: Int) -> Bool {
        self.generation(of: serverID) == generation
    }

    public func isRunning(_ serverID: String) -> Bool {
        runs[serverID] != nil
    }

    /// The server's entry changed, or it was removed: a sync running for it stops, and
    /// nothing it found is kept.
    public func invalidate(_ serverID: String) {
        generations[serverID, default: 0] += 1
        again.remove(serverID)
        runs[serverID]?.task.cancel()
    }

    /// Syncs a server, or waits for the sync of it already running and returns with it.
    /// - Parameter work: the sync. It's handed whether what it found may still be kept, to
    ///   ask after every wait and before keeping anything.
    public func run(_ serverID: String, _ work: @escaping @MainActor (_ isCurrent: @escaping @MainActor () -> Bool) async -> Void) async {
        let generation = generation(of: serverID)
        if let running = runs[serverID] {
            if running.generation == generation {
                await running.task.value
                return
            }
            // A sync of the entry as it was, cancelled: it winds down before this one starts.
            await running.task.value
            if let started = runs[serverID], started.generation == generation {
                await started.task.value
                return
            }
            guard isCurrent(serverID, generation) else { return }
        }
        let id = UUID()
        let task = Task {
            repeat {
                again.remove(serverID)
                await work {
                    !Task.isCancelled && self.isCurrent(serverID, generation)
                }
            } while again.contains(serverID) && isCurrent(serverID, generation) && !Task.isCancelled
        }
        runs[serverID] = Run(id: id, generation: generation, task: task)
        await task.value
        if runs[serverID]?.id == id { runs[serverID] = nil }
    }

    /// Something changed on the server while it syncs: the sync runs once more when it ends.
    /// - Returns: false when nothing is running, so the caller starts a sync itself.
    @discardableResult
    public func runAgainAfterCurrent(_ serverID: String) -> Bool {
        guard runs[serverID] != nil else { return false }
        again.insert(serverID)
        return true
    }
}

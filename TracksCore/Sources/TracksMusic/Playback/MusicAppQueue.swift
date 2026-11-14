import Foundation
import Synchronization

/// Holds the one task allowed to run, cancelling the one it replaces.
final class TaskSlot: Sendable {
    private let task = Mutex<Task<Void, Never>?>(nil)

    func replace(with new: Task<Void, Never>) {
        task.withLock { current in
            current?.cancel()
            current = new
        }
    }

    func cancel() {
        task.withLock { current in
            current?.cancel()
            current = nil
        }
    }
}

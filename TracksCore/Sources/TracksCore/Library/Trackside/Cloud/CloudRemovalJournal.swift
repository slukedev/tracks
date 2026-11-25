import Foundation
import Synchronization

/// What this device removed on purpose and Trackside Cloud hasn't heard of yet: plays you
/// deleted or forgot, and duplicates merged into another play. Only these become tombstones;
/// a play that's merely missing here, as from a store that couldn't be opened, never does.
///
/// Kept in a file of its own, outside the store, and trimmed to the newest `limit`.
public final class CloudRemovalJournal: Sendable {
    private let url: URL
    private let limit: Int
    /// By kind, then id. Nil until first read from the file.
    private let removals = Mutex<[String: [String: CloudRemoval]]?>(nil)

    public init(url: URL, limit: Int = 20_000) {
        self.url = url
        self.limit = max(1, limit)
    }

    public func record(_ new: [CloudRemoval], for entity: CloudEntity) {
        guard !new.isEmpty else { return }
        change { all in
            var kept = all[entity.rawValue] ?? [:]
            for removal in new { kept[removal.id] = removal }
            if kept.count > limit {
                let newest = kept.values.sorted { ($0.at, $0.id) > ($1.at, $1.id) }.prefix(limit)
                kept = Dictionary(uniqueKeysWithValues: newest.map { ($0.id, $0) })
            }
            all[entity.rawValue] = kept
        }
    }

    /// Oldest first.
    public func pending(_ entity: CloudEntity) -> [CloudRemoval] {
        removals.withLock { state in
            let all = state ?? load()
            state = all
            return (all[entity.rawValue] ?? [:]).values.sorted { ($0.at, $0.id) < ($1.at, $1.id) }
        }
    }

    public func forget(_ ids: Set<String>, for entity: CloudEntity) {
        guard !ids.isEmpty else { return }
        change { all in
            guard var kept = all[entity.rawValue] else { return }
            for id in ids { kept[id] = nil }
            all[entity.rawValue] = kept.isEmpty ? nil : kept
        }
    }

    private func change(_ body: (inout [String: [String: CloudRemoval]]) -> Void) {
        removals.withLock { state in
            var all = state ?? load()
            body(&all)
            state = all
            save(all)
        }
    }

    private func load() -> [String: [String: CloudRemoval]] {
        guard let data = try? Data(contentsOf: url) else { return [:] }
        return (try? JSONDecoder().decode([String: [String: CloudRemoval]].self, from: data)) ?? [:]
    }

    private func save(_ all: [String: [String: CloudRemoval]]) {
        do {
            if all.isEmpty {
                try? FileManager.default.removeItem(at: url)
                return
            }
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(all).write(to: url, options: .atomic)
        } catch {
            // Kept in memory; the next change writes it again.
        }
    }
}

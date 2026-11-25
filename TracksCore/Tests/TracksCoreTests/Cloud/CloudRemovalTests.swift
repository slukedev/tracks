import Foundation
import Synchronization
import Testing
@testable import TracksCore

/// A history as the engine sees it: plays that may vanish (a store that couldn't be opened),
/// removals recorded on purpose, and changes it can name.
final class HistoryStore: CloudEntityStore {
    struct Held {
        var plays: [String: [String: JSONValue]] = [:]
        var removed: [CloudRemoval] = []
        var changed: Set<String>?
        var wholeReads = 0
        var changeReads = 0
        /// Ids it can't read, as from a newer build.
        var unreadable: Set<String> = []
    }

    let entity = CloudEntity.plays
    let held = Mutex(Held())

    init(_ plays: [String: String] = [:], tracksChanges: Bool = false) {
        held.withLock { held in
            held.plays = plays.mapValues { ["title": .string($0)] }
            held.changed = tracksChanges ? [] : nil
        }
    }

    func play(_ id: String, _ title: String) {
        held.withLock { held in
            held.plays[id] = ["title": .string(title)]
            held.changed?.insert(id)
        }
    }

    func localItems() async throws -> [CloudLocalItem] {
        held.withLock { held in
            held.wholeReads += 1
            if held.changed != nil { held.changed = [] }
            return held.plays.sorted { $0.key < $1.key }.map { CloudLocalItem(id: $0.key, fields: $0.value, modifiedAt: .distantPast) }
        }
    }

    func changedItems() async throws -> [CloudLocalItem]? {
        held.withLock { held in
            guard let changed = held.changed else { return nil }
            held.changeReads += 1
            held.changed = []
            return changed.sorted().compactMap { id in held.plays[id].map { CloudLocalItem(id: id, fields: $0, modifiedAt: .distantPast) } }
        }
    }

    func removals() async throws -> [CloudRemoval] {
        held.withLock { held in held.removed.filter { held.plays[$0.id] == nil } }
    }

    func removalsSent(_ ids: Set<String>) async {
        held.withLock { $0.removed.removeAll { ids.contains($0.id) } }
    }

    func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        held.withLock { held in
            for deletion in deletes { held.plays[deletion.id] = nil }
            var kept: [String: [String: JSONValue]] = [:]
            let removed = Set(held.removed.map(\.id))
            for record in upserts where !held.unreadable.contains(record.id) && !removed.contains(record.id) {
                held.plays[record.id] = record.fields
                kept[record.id] = record.fields
            }
            return kept
        }
    }
}

@Suite("Trackside Cloud deletes only what was removed here")
struct CloudRemovalTests {
    static func engine(_ cloud: FakeCloud, _ store: some CloudEntityStore, folder: URL) -> CloudSyncEngine {
        CloudSyncEngine(api: cloud, stores: [store], deviceID: "D1", folder: folder, now: { Date(timeIntervalSince1970: 100) })
    }

    @Test("a history that comes back nearly empty, as from a store held in memory, deletes nothing on the server")
    func emptyStoreDeletesNothing() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        let store = HistoryStore(["a": "Ivy", "b": "Halo"])
        try await Self.engine(cloud, store, folder: folder.url).sync()
        #expect(cloud.held.withLock { $0.records.count } == 2)
        // The next launch can't open the store: the same ledger, next to nothing held.
        let empty = HistoryStore()
        let report = try await Self.engine(cloud, empty, folder: folder.url).sync()
        #expect(report.deleted == 0)
        #expect(cloud.held.withLock { $0.tombstones.isEmpty && $0.records.count == 2 })
        #expect(cloud.held.withLock { $0.pushes.last?.contains(where: \.isTombstone) } != true)
    }

    @Test("a play removed here on purpose goes up as a tombstone, merged into the one kept, once")
    func recordedRemoval() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        let store = HistoryStore(["a": "Ivy", "b": "Ivy"])
        let engine = Self.engine(cloud, store, folder: folder.url)
        try await engine.sync()
        store.held.withLock { held in
            held.plays["b"] = nil
            held.removed = [CloudRemoval(id: "b", at: Date(timeIntervalSince1970: 50), mergedInto: "a")]
        }
        let report = try await engine.sync()
        #expect(report.deleted == 1)
        #expect(cloud.held.withLock { $0.tombstones } == ["b"])
        let tombstone = try #require(cloud.held.withLock { $0.pushes.last?.first { $0.id == "b" } })
        #expect(tombstone.mergedInto == "a")
        #expect(store.held.withLock { $0.removed.isEmpty })
        #expect(try await engine.sync().deleted == 0)
    }

    @Test("a removal of a play still held, whose save never happened, isn't sent")
    func removalStillHeld() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        let store = HistoryStore(["a": "Ivy"])
        store.held.withLock { $0.removed = [CloudRemoval(id: "a", at: .distantPast)] }
        let report = try await Self.engine(cloud, store, folder: folder.url).sync()
        #expect(report.deleted == 0)
        #expect(cloud.held.withLock { $0.records["a"] } != nil)
    }

    @Test("a record this device can't read is left as the server has it")
    func unreadableRecord() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        cloud.seed("new", ["title": .string("From a newer build"), "shape": .int(2)])
        let store = HistoryStore()
        store.held.withLock { $0.unreadable = ["new"] }
        let engine = Self.engine(cloud, store, folder: folder.url)
        try await engine.sync()
        let report = try await engine.sync()
        #expect(report.deleted == 0)
        #expect(cloud.held.withLock { $0.tombstones.isEmpty && $0.records["new"] != nil })
    }

    @Test("after the first sync of a run, only what changed is read")
    func incremental() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        let store = HistoryStore(["a": "Ivy"], tracksChanges: true)
        let engine = Self.engine(cloud, store, folder: folder.url)
        try await engine.sync()
        store.play("b", "Halo")
        let report = try await engine.sync()
        #expect(report.pushed == 1)
        #expect(store.held.withLock { ($0.wholeReads, $0.changeReads) } == (1, 1))
        #expect(cloud.held.withLock { Set($0.records.keys) } == ["a", "b"])
    }

    @Test("a push that fails is followed by a whole read, so nothing it carried is lost")
    func failedPushReadsWhole() async throws {
        struct Down: Error {}
        final class Flaky: CloudSyncAPI {
            let cloud = FakeCloud()
            let failing = Mutex(false)
            func pull(_ entity: CloudEntity, cursor: CloudCursor?, limit: Int) async throws -> CloudPage {
                try await cloud.pull(entity, cursor: cursor, limit: limit)
            }
            func push(_ entity: CloudEntity, items: [CloudPushItem]) async throws -> [CloudPushResult] {
                if failing.withLock({ $0 }) { throw Down() }
                return try await cloud.push(entity, items: items)
            }
        }
        let folder = try TemporaryFolder()
        let api = Flaky()
        let store = HistoryStore(["a": "Ivy"], tracksChanges: true)
        let engine = CloudSyncEngine(api: api, stores: [store], deviceID: "D1", folder: folder.url)
        try await engine.sync()
        store.play("b", "Halo")
        api.failing.withLock { $0 = true }
        await #expect(throws: Down.self) { try await engine.sync() }
        api.failing.withLock { $0 = false }
        try await engine.sync()
        #expect(store.held.withLock { $0.wholeReads } == 2)
        #expect(api.cloud.held.withLock { Set($0.records.keys) } == ["a", "b"])
    }

    @Test("a play sent without a kind still reads")
    func playWithoutKind() throws {
        let fields: [String: JSONValue] = ["title": .string("Ivy"), "artistName": .string("Mara Solis"), "playedAtMs": .int(1)]
        let play = try CloudJSON.value(CloudPlayFields.self, from: fields)
        #expect(play.kind == nil)
        #expect(try CloudJSON.fields(play)["kind"] == nil)
    }
}

@Suite("Removals kept for Trackside Cloud")
struct CloudRemovalJournalTests {
    @Test("removals are kept across launches until the server has them")
    func persists() throws {
        let folder = try TemporaryFolder()
        let url = folder.url.appending(path: "removals.json")
        let journal = CloudRemovalJournal(url: url)
        journal.record([CloudRemoval(id: "b", at: Date(timeIntervalSince1970: 2)), CloudRemoval(id: "a", at: Date(timeIntervalSince1970: 1), mergedInto: "z")], for: .plays)
        let again = CloudRemovalJournal(url: url)
        #expect(again.pending(.plays).map(\.id) == ["a", "b"])
        #expect(again.pending(.plays).first?.mergedInto == "z")
        #expect(again.pending(.sessions).isEmpty)
        again.forget(["a", "b"], for: .plays)
        #expect(CloudRemovalJournal(url: url).pending(.plays).isEmpty)
    }

    @Test("only the newest are kept past the limit")
    func limit() throws {
        let folder = try TemporaryFolder()
        let journal = CloudRemovalJournal(url: folder.url.appending(path: "removals.json"), limit: 2)
        journal.record((1...3).map { CloudRemoval(id: "p\($0)", at: Date(timeIntervalSince1970: Double($0))) }, for: .plays)
        #expect(journal.pending(.plays).map(\.id) == ["p2", "p3"])
    }
}

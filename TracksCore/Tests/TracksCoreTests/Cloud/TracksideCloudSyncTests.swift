import Foundation
import Synchronization
import Testing
@testable import TracksCore

@Suite("Trackside Cloud's wire")
struct CloudWireTests {
    @Test("a page decodes from snake_case, with the cursor kept exactly as sent")
    func page() throws {
        let json = """
        {"upserts": [{"id": "p1", "version": 917, "client_modified_at": "2026-11-24T09:30:00.000Z",
          "device_id": "D1", "fields": {"title": "Ivy", "artistName": "Mara Solis", "playedAtMs": 1764000000000, "kind": "onDemand",
          "playedMs": 201000, "extra": {"nested": [1, 2.5, true, null]}}}],
         "deletes": [{"id": "p0", "deleted_at": "2026-11-24T09:31:00Z", "merged_into": "p1"}],
         "cursor": "3f9a0c1d7e2b4a65.918", "has_more": true}
        """
        let page = try CloudJSON.decoder.decode(CloudPage.self, from: Data(json.utf8))
        #expect(page.cursor?.rawValue == "3f9a0c1d7e2b4a65.918")
        #expect(page.hasMore)
        #expect(page.deletes == [CloudDeletion(id: "p0", deletedAt: CloudJSON.date(from: "2026-11-24T09:31:00Z"), mergedInto: "p1")])
        let record = try #require(page.upserts.first)
        #expect(record.version == 917)
        #expect(record.deviceID == "D1")
        #expect(record.fields["playedMs"] == .int(201000))
        #expect(record.fields["extra"] == .object(["nested": .array([.int(1), .double(2.5), .bool(true), .null])]))
        let play = try CloudJSON.value(CloudPlayFields.self, from: record.fields)
        #expect(play.title == "Ivy")
        #expect(play.playedAtMs == 1_764_000_000_000)
        #expect(play.source == nil)
    }

    @Test("an older numeric cursor still reads, as its digits")
    func numericCursor() throws {
        let page = try CloudJSON.decoder.decode(CloudPage.self, from: Data(#"{"cursor": 918}"#.utf8))
        #expect(page.cursor?.rawValue == "918")
        #expect(page.upserts.isEmpty && page.deletes.isEmpty && !page.hasMore)
    }

    @Test("a push sends snake_case envelopes, leaves out what it hasn't got, and marks tombstones")
    func push() throws {
        let at = try #require(CloudJSON.date(from: "2026-11-24T09:30:00.000Z"))
        let item = CloudPushItem(id: "p1", baseVersion: 0, fields: ["title": .string("Ivy")], clientModifiedAt: at, deviceID: "D1")
        let tombstone = CloudPushItem(id: "p0", fields: nil, clientModifiedAt: at, deviceID: "D1", deletedAt: at, mergedInto: "p1")
        let data = try CloudJSON.encoder.encode(CloudPushRequest(items: [item, tombstone]))
        let text = String(decoding: data, as: UTF8.self)
        #expect(text == #"{"items":[{"base_version":0,"client_modified_at":"2026-11-24T09:30:00.000Z","device_id":"D1","fields":{"title":"Ivy"},"id":"p1"},{"client_modified_at":"2026-11-24T09:30:00.000Z","deleted_at":"2026-11-24T09:30:00.000Z","device_id":"D1","id":"p0","merged_into":"p1"}]}"#)
        #expect(tombstone.isTombstone && !item.isTombstone)
    }

    @Test("a cleared setting goes up as an explicit null")
    func settings() throws {
        let at = try #require(CloudJSON.date(from: "2026-11-24T09:30:00.000Z"))
        let patch = CloudSettingsPatch(settings: ["playlistName": CloudSetting(value: .null, modifiedAt: at, deviceID: "D1")])
        let text = String(decoding: try CloudJSON.encoder.encode(patch), as: UTF8.self)
        #expect(text == #"{"settings":{"playlistName":{"device_id":"D1","modified_at":"2026-11-24T09:30:00.000Z","value":null}}}"#)
        let back = try CloudJSON.decoder.decode(CloudSettingsPatch.self, from: Data(text.utf8))
        #expect(back.settings["playlistName"]?.value == .null)
    }

    @Test("a push answer reads every status, and one it doesn't know as unknown")
    func results() throws {
        let json = #"{"results": [{"id": "a", "status": "accepted", "version": 5}, {"id": "b", "status": "conflict", "current": {"id": "b", "fields": {"name": "x"}}}, {"id": "c", "status": "rejected", "reason": "device_mismatch"}, {"id": "d", "status": "queued"}]}"#
        let response = try CloudJSON.decoder.decode(CloudPushResponse.self, from: Data(json.utf8))
        #expect(response.results.map(\.status) == [.accepted, .conflict, .rejected, .unknown])
        #expect(response.results[0].version == 5)
        #expect(response.results[1].current?.record?.fields["name"] == .string("x"))
        #expect(response.results[2].reason == "device_mismatch")
    }

    @Test("paths are kebab-case under cloud/")
    func paths() {
        #expect(CloudEntity.forgottenSongs.path == "cloud/forgotten-songs")
        #expect(CloudEntity.plays.batchPath == "cloud/plays:batch")
        #expect(CloudEntity.allCases.map(\.rawValue).contains("excluded-stations"))
    }

    @Test("fields digest the same whatever order they arrive in")
    func digest() {
        let a: [String: JSONValue] = ["title": .string("Ivy"), "playedAtMs": .int(5)]
        let b: [String: JSONValue] = ["playedAtMs": .int(5), "title": .string("Ivy")]
        #expect(CloudJSON.digest(a) == CloudJSON.digest(b))
        #expect(CloudJSON.digest(a) != CloudJSON.digest(["title": .string("Ivy")]))
        #expect(CloudJSON.digest(a).count == 16)
    }
}

/// A Trackside Cloud held in memory, with the server's rules that matter here.
final class FakeCloud: CloudSyncAPI {
    struct Held {
        var records: [String: CloudRecord] = [:]
        var tombstones: Set<String> = []
        var log: [(seq: Int, id: String, deleted: Bool)] = []
        var seq = 0
        var epoch = "e1"
        var pushes: [[CloudPushItem]] = []
        var pulls: [CloudCursor?] = []
        var boundDevice: String?
    }

    let held = Mutex(Held())

    func seed(_ id: String, _ fields: [String: JSONValue]) {
        held.withLock { held in
            held.seq += 1
            held.records[id] = CloudRecord(id: id, fields: fields)
            held.log.append((held.seq, id, false))
        }
    }

    func restoreBackup() {
        held.withLock { $0.epoch = "e2" }
    }

    func pull(_ entity: CloudEntity, cursor: CloudCursor?, limit: Int) async throws -> CloudPage {
        try held.withLock { held in
            held.pulls.append(cursor)
            var after = 0
            if let cursor {
                let parts = cursor.rawValue.split(separator: ".")
                guard parts.count == 2, String(parts[0]) == held.epoch, let seq = Int(parts[1]) else { throw CloudSyncError.syncReset }
                after = seq
            }
            let changes = held.log.filter { $0.seq > after }
            let page = Array(changes.prefix(limit))
            let last = page.last?.seq ?? max(after, held.seq)
            var upserts: [CloudRecord] = []
            var deletes: [CloudDeletion] = []
            for change in page {
                if change.deleted { deletes.append(CloudDeletion(id: change.id)) } else if let record = held.records[change.id] { upserts.append(record) }
            }
            return CloudPage(upserts: upserts, deletes: deletes, cursor: CloudCursor(rawValue: "\(held.epoch).\(last)"), hasMore: changes.count > page.count)
        }
    }

    func push(_ entity: CloudEntity, items: [CloudPushItem]) async throws -> [CloudPushResult] {
        held.withLock { held in
            held.pushes.append(items)
            return items.map { item in
                if let bound = held.boundDevice, item.deviceID != bound {
                    return CloudPushResult(id: item.id, status: .rejected, reason: "device_mismatch")
                }
                held.seq += 1
                if item.isTombstone {
                    held.records[item.id] = nil
                    held.tombstones.insert(item.id)
                    held.log.append((held.seq, item.id, true))
                    return CloudPushResult(id: item.id, status: .accepted)
                }
                if held.tombstones.contains(item.id) {
                    return CloudPushResult(id: item.id, status: .rejected, reason: "deleted")
                }
                let fields = item.fields ?? [:]
                if held.records[item.id]?.fields == fields { return CloudPushResult(id: item.id, status: .duplicate) }
                held.records[item.id] = CloudRecord(id: item.id, fields: fields, deviceID: item.deviceID)
                held.log.append((held.seq, item.id, false))
                return CloudPushResult(id: item.id, status: .accepted)
            }
        }
    }
}

/// One device's names, as a store the engine syncs.
final class NameStore: CloudEntityStore {
    let entity = CloudEntity.blockedArtists
    let names = Mutex<[String: String]>([:])

    init(_ names: [String: String] = [:]) {
        self.names.withLock { $0 = names }
    }

    func localItems() async throws -> [CloudLocalItem] {
        names.withLock { names in
            names.sorted { $0.key < $1.key }.map { CloudLocalItem(id: $0.key, fields: ["name": .string($0.value)], modifiedAt: Date(timeIntervalSince1970: 0)) }
        }
    }

    func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        names.withLock { names in
            for deletion in deletes { names[deletion.id] = nil }
            var held: [String: [String: JSONValue]] = [:]
            for record in upserts {
                names[record.id] = record.fields["name"]?.string ?? ""
                held[record.id] = ["name": .string(names[record.id] ?? "")]
            }
            return held
        }
    }
}

@Suite("Syncing with Trackside Cloud")
struct CloudSyncEngineTests {
    static func engine(_ cloud: FakeCloud, _ store: NameStore, folder: URL, device: String = "D1", pageSize: Int = 2, batchSize: Int = 1_000) -> CloudSyncEngine {
        CloudSyncEngine(api: cloud, stores: [store], deviceID: device, folder: folder, pageSize: pageSize, batchSize: batchSize, now: { Date(timeIntervalSince1970: 100) })
    }

    @Test("a first sync takes the server's items and sends up the ones only here, merging both")
    func firstSync() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        cloud.seed("a", ["name": .string("Arca")])
        cloud.seed("b", ["name": .string("Björk")])
        cloud.seed("c", ["name": .string("Caribou")])
        let store = NameStore(["z": "Zola Jesus", "a": "Arca"])
        let report = try await Self.engine(cloud, store, folder: folder.url).sync()
        #expect(store.names.withLock { Set($0.keys) } == ["a", "b", "c", "z"])
        #expect(cloud.held.withLock { Set($0.records.keys) } == ["a", "b", "c", "z"])
        #expect(report.pulled == 3)
        #expect(report.pushed == 1)
        // Paged at two a time, each cursor sent back as it came.
        #expect(cloud.held.withLock { $0.pulls.map { $0?.rawValue } } == [nil, "e1.2"])
    }

    @Test("what came down isn't sent back, and a second sync sends nothing")
    func idempotent() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        cloud.seed("a", ["name": .string("Arca")])
        let store = NameStore()
        let engine = Self.engine(cloud, store, folder: folder.url)
        try await engine.sync()
        let report = try await engine.sync()
        #expect(report == CloudSyncEngine.Report())
        #expect(cloud.held.withLock { $0.pushes.allSatisfy(\.isEmpty) })
    }

    @Test("a change here goes up, and a removal here goes up as a tombstone")
    func localChanges() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        cloud.seed("a", ["name": .string("Arca")])
        cloud.seed("b", ["name": .string("Björk")])
        let store = NameStore()
        let engine = Self.engine(cloud, store, folder: folder.url)
        try await engine.sync()
        store.names.withLock { $0["a"] = "ARCA"; $0["b"] = nil }
        let report = try await engine.sync()
        #expect(report.pushed == 1)
        #expect(report.deleted == 1)
        #expect(cloud.held.withLock { $0.records["a"]?.fields["name"] } == .string("ARCA"))
        #expect(cloud.held.withLock { $0.tombstones } == ["b"])
        let tombstone = try #require(cloud.held.withLock { $0.pushes.last?.first { $0.id == "b" } })
        #expect(tombstone.deviceID == "D1")
        #expect(tombstone.fields == nil)
    }

    @Test("a cursor from before a restore starts over from a snapshot and loses nothing")
    func syncReset() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        cloud.seed("a", ["name": .string("Arca")])
        let store = NameStore()
        let engine = Self.engine(cloud, store, folder: folder.url)
        try await engine.sync()
        // The server is restored from a backup that never had "a", and this device adds "y".
        cloud.held.withLock { $0.records = [:]; $0.log = []; $0.seq = 0 }
        cloud.restoreBackup()
        store.names.withLock { $0["y"] = "Yaeji" }
        let report = try await engine.sync()
        #expect(report.resets == 1)
        #expect(cloud.held.withLock { Set($0.records.keys) } == ["a", "y"])
        #expect(await engine.cursor(for: .blockedArtists)?.rawValue.hasPrefix("e2.") == true)
    }

    @Test("the cursor survives a relaunch, so only what's new is pulled")
    func cursorPersists() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        cloud.seed("a", ["name": .string("Arca")])
        try await Self.engine(cloud, NameStore(), folder: folder.url).sync()
        cloud.seed("b", ["name": .string("Björk")])
        let store = NameStore(["a": "Arca"])
        let report = try await Self.engine(cloud, store, folder: folder.url).sync()
        #expect(report.pulled == 1)
        #expect(report.pushed == 0)
        #expect(cloud.held.withLock { $0.pulls.last??.rawValue } == "e1.1")
    }

    @Test("pushes go in batches of at most a thousand")
    func batches() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        let names = Dictionary(uniqueKeysWithValues: (0..<2_500).map { ("n\($0)", "Name \($0)") })
        let store = NameStore(names)
        let report = try await Self.engine(cloud, store, folder: folder.url, batchSize: 5_000).sync()
        #expect(report.pushed == 2_500)
        #expect(cloud.held.withLock { $0.pushes.map(\.count) } == [1_000, 1_000, 500])
    }

    @Test("a key that speaks for another device stops the sync")
    func deviceMismatch() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        cloud.held.withLock { $0.boundDevice = "OTHER" }
        let store = NameStore(["a": "Arca"])
        await #expect(throws: CloudSyncError.deviceMismatch) {
            try await Self.engine(cloud, store, folder: folder.url).sync()
        }
    }

    @Test("an item deleted on the server since is deleted here too, not sent again")
    func deletedOnServer() async throws {
        let folder = try TemporaryFolder()
        let cloud = FakeCloud()
        cloud.held.withLock { $0.tombstones = ["a"] }
        let store = NameStore(["a": "Arca"])
        let engine = Self.engine(cloud, store, folder: folder.url)
        let report = try await engine.sync()
        #expect(report.rejected == 1)
        #expect(store.names.withLock { $0["a"] } == nil)
    }

    @Test("a conflict with a tombstone deletes the item here too")
    func conflictWithTombstone() async throws {
        struct Tombstoned: CloudSyncAPI {
            func pull(_ entity: CloudEntity, cursor: CloudCursor?, limit: Int) async throws -> CloudPage {
                CloudPage(cursor: CloudCursor(rawValue: "e1.1"))
            }

            func push(_ entity: CloudEntity, items: [CloudPushItem]) async throws -> [CloudPushResult] {
                items.map { CloudPushResult(id: $0.id, status: .conflict, current: .deletion(CloudDeletion(id: $0.id, version: 9))) }
            }
        }
        let folder = try TemporaryFolder()
        let store = NameStore(["a": "Arca"])
        let engine = CloudSyncEngine(api: Tombstoned(), stores: [store], deviceID: "D1", folder: folder.url)
        let report = try await engine.sync()
        #expect(report.conflicts == 1)
        #expect(store.names.withLock { $0.isEmpty })
    }

    @Test("changes are found by comparing with the ledger")
    func changes() {
        let local = [
            CloudLocalItem(id: "same", fields: ["name": .string("A")], modifiedAt: .distantPast),
            CloudLocalItem(id: "new", fields: ["name": .string("B")], modifiedAt: .distantPast),
        ]
        let ledger = ["same": CloudJSON.digest(["name": .string("A")]), "gone": "x"]
        let items = CloudSyncEngine.changes(local: local, ledger: ledger, deviceID: "D1", now: Date(timeIntervalSince1970: 5))
        #expect(items.map(\.id) == ["new", "gone"])
        #expect(items[1].isTombstone)
    }
}

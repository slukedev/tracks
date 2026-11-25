import Foundation

/// Trackside Cloud as the sync engine asks it: pull a page, push a batch. A Trackside
/// server's client in the app; a stub in tests.
public protocol CloudSyncAPI: Sendable {
    func pull(_ entity: CloudEntity, cursor: CloudCursor?, limit: Int) async throws -> CloudPage
    func push(_ entity: CloudEntity, items: [CloudPushItem]) async throws -> [CloudPushResult]
}

/// Why a sync stopped.
public enum CloudSyncError: Error, Sendable, Equatable {
    /// The cursor is from before the server's data was restored, or too old: the next pull
    /// starts again from a full snapshot.
    case syncReset
    /// The server says this device's key speaks for another device id.
    case deviceMismatch
}

/// One kind of thing Tracks keeps, as the sync engine sees it: every item it holds now, and
/// a way to take in what the server sent.
///
/// Nothing is deleted on the server because it's missing here: only removals the store
/// recorded (``removals()``), or, for a list read whole that's always there, an item gone
/// from it (``removesByAbsence``).
public protocol CloudEntityStore: Sendable {
    var entity: CloudEntity { get }

    /// Every item kept on this device, with the fields the server would hold for it.
    /// Throws ``CloudStoreUnavailable`` when what it keeps can't be read now.
    func localItems() async throws -> [CloudLocalItem]

    /// The items that changed here since ``localItems()`` or this was last asked, or nil
    /// when the store can't tell, and every item is read.
    func changedItems() async throws -> [CloudLocalItem]?

    /// Items removed here on purpose, to go up as tombstones.
    func removals() async throws -> [CloudRemoval]

    /// The server has had these removals: they needn't go again.
    func removalsSent(_ ids: Set<String>) async

    /// Whether an item the server had that ``localItems()`` leaves out was removed here: only
    /// for a short list read whole that can't fail to load, never for the history.
    var removesByAbsence: Bool { get }

    /// Takes in a page from the server. Duplicates it makes are merged the way the store
    /// always merges them, afterwards.
    /// - Returns: the fields of each item taken in, as this device now holds it, so what
    ///   came down isn't sent straight back up. An id missing from the answer wasn't taken
    ///   in (it couldn't be read here): it's left as the server has it.
    func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]]
}

extension CloudEntityStore {
    public func changedItems() async throws -> [CloudLocalItem]? { nil }
    public func removals() async throws -> [CloudRemoval] { [] }
    public func removalsSent(_ ids: Set<String>) async {}
    public var removesByAbsence: Bool { false }
}

/// What a store keeps can't be read now, as before the device is first unlocked: nothing of
/// it goes up this time.
public struct CloudStoreUnavailable: Error, Sendable {
    public init() {}
}

/// An item removed on this device on purpose, waiting to go up as a tombstone.
public struct CloudRemoval: Codable, Sendable, Hashable {
    public var id: String
    public var at: Date
    /// The item it was merged into, where it was a duplicate of one.
    public var mergedInto: String?

    public init(id: String, at: Date, mergedInto: String? = nil) {
        self.id = id
        self.at = at
        self.mergedInto = mergedInto
    }
}

public struct CloudLocalItem: Sendable, Hashable {
    public var id: String
    public var fields: [String: JSONValue]
    /// When this device last changed it, as best it knows: a play's time, a name's change.
    public var modifiedAt: Date

    public init(id: String, fields: [String: JSONValue], modifiedAt: Date) {
        self.id = id
        self.fields = fields
        self.modifiedAt = modifiedAt
    }
}

/// Keeps what this device holds and what Trackside Cloud holds the same, one kind at a time:
/// pulls the server's changes since its cursor, then pushes what changed here since the
/// last sync, found by comparing each item with what the server last had of it. Only the
/// items a store says changed are compared, except on the first sync of a run, after a
/// reset or a push that didn't finish, and once a day, when every item is.
///
/// Nothing is lost on the way: a page is taken in before its cursor is kept, a push is
/// idempotent by id so sending twice costs nothing, and a `410 sync_reset` starts the pull
/// again from a full snapshot, after which everything held here that the server lacks goes
/// back up.
public actor CloudSyncEngine {
    public struct Report: Sendable, Equatable {
        public var pulled = 0
        public var removed = 0
        public var pushed = 0
        public var deleted = 0
        public var conflicts = 0
        public var rejected = 0
        public var resets = 0

        public init() {}
    }

    /// What the engine keeps for each kind between syncs.
    struct State: Sendable, Equatable {
        var cursor: CloudCursor?
        /// What the server holds of each item, by id, as a digest of its fields here.
        var ledger: [String: String] = [:]
    }

    public nonisolated let deviceID: String
    private let api: any CloudSyncAPI
    private let stores: [CloudEntity: any CloudEntityStore]
    private let folder: URL
    private let pageSize: Int
    private let batchSize: Int
    /// The most one batch's body may be, under the server's 8 MiB.
    private let batchBytes: Int
    private let now: @Sendable () -> Date
    private var states: [CloudEntity: State] = [:]
    private var running: Task<Report, any Error>?
    /// When every item of a kind was last compared, this run.
    private var wholePasses: [CloudEntity: Date] = [:]
    /// How long incremental pushes go on before every item is compared again.
    static let wholePassEvery: TimeInterval = 24 * 60 * 60

    public init(
        api: any CloudSyncAPI,
        stores: [any CloudEntityStore],
        deviceID: String,
        folder: URL,
        pageSize: Int = 2_000,
        batchSize: Int = 1_000,
        batchBytes: Int = 7 * 1024 * 1024,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.api = api
        self.stores = Dictionary(stores.map { ($0.entity, $0) }, uniquingKeysWith: { first, _ in first })
        self.deviceID = deviceID
        self.folder = folder
        self.pageSize = max(1, pageSize)
        self.batchSize = max(1, min(batchSize, 1_000))
        self.batchBytes = batchBytes
        self.now = now
    }

    /// The order kinds sync in: stations and sessions before the plays that name them.
    public static let order: [CloudEntity] = [.stations, .sessions, .plays, .forgottenSongs, .blockedArtists, .excludedStations, .playlists, .playlistEntries, .likes]

    /// Syncs every kind this engine has a store for, or only `entities`. A sync already
    /// running is joined rather than run twice at once.
    @discardableResult
    public func sync(_ entities: Set<CloudEntity>? = nil) async throws -> Report {
        if let running { return try await running.value }
        let wanted = Self.order.filter { stores[$0] != nil && (entities?.contains($0) ?? true) }
        let task = Task { try await syncAll(wanted) }
        running = task
        defer { running = nil }
        return try await task.value
    }

    /// Forgets where every pull got to and what the server holds, so the next sync takes a
    /// full snapshot and sends up everything held here: a first sync, or a move to this
    /// backend from another.
    public func startOver() {
        states = [:]
        wholePasses = [:]
        for entity in CloudEntity.allCases {
            try? FileManager.default.removeItem(at: cursorURL(entity))
            try? FileManager.default.removeItem(at: ledgerURL(entity))
        }
    }

    /// Where a kind's pull got to, for tests and diagnostics.
    public func cursor(for entity: CloudEntity) -> CloudCursor? {
        state(entity).cursor
    }

    private func syncAll(_ entities: [CloudEntity]) async throws -> Report {
        var report = Report()
        for entity in entities {
            try Task.checkCancellation()
            try await sync(entity, into: &report)
        }
        return report
    }

    private func sync(_ entity: CloudEntity, into report: inout Report) async throws {
        guard let store = stores[entity] else { return }
        do {
            try await pull(entity, store: store, into: &report)
        } catch CloudSyncError.syncReset {
            // The server's history isn't the one this cursor walked: start again from a
            // full snapshot, then send up whatever this device holds that it lacks.
            report.resets += 1
            wholePasses[entity] = nil
            var reset = state(entity)
            reset.cursor = nil
            reset.ledger = [:]
            keep(reset, for: entity)
            try await pull(entity, store: store, into: &report)
        }
        try await push(entity, store: store, into: &report)
    }

    // MARK: - Pulling

    private func pull(_ entity: CloudEntity, store: any CloudEntityStore, into report: inout Report) async throws {
        var current = state(entity)
        defer { save(current, for: entity) }
        while true {
            try Task.checkCancellation()
            let page = try await api.pull(entity, cursor: current.cursor, limit: pageSize)
            if !page.upserts.isEmpty || !page.deletes.isEmpty {
                let held = try await store.apply(upserts: page.upserts, deletes: page.deletes)
                // One that couldn't be taken in stays out of the ledger, and the server keeps
                // it as it is.
                for record in page.upserts {
                    if let fields = held[record.id] {
                        current.ledger[record.id] = CloudJSON.digest(fields)
                    }
                }
                for deletion in page.deletes {
                    current.ledger[deletion.id] = nil
                }
                report.pulled += page.upserts.count
                report.removed += page.deletes.count
            }
            // The page is in before its cursor is kept. The ledger is written only now and
            // then: a ledger behind its cursor only sends items up again, which the server
            // takes as duplicates.
            if let cursor = page.cursor { current.cursor = cursor }
            keep(current, for: entity)
            saveCursor(current, for: entity)
            guard page.hasMore, page.cursor != nil else { break }
        }
    }

    // MARK: - Pushing

    private func push(_ entity: CloudEntity, store: any CloudEntityStore, into report: inout Report) async throws {
        var current = state(entity)
        defer { save(current, for: entity) }
        let started = now()
        let whole = wholePasses[entity].map { started.timeIntervalSince($0) > Self.wholePassEvery } ?? true
        var changes: [CloudPushItem]
        do {
            if !whole, let changed = try await store.changedItems() {
                changes = Self.changes(local: changed, ledger: current.ledger, deviceID: deviceID, now: started)
            } else {
                let items = try await store.localItems()
                changes = Self.changes(local: items, ledger: current.ledger, deviceID: deviceID, now: started)
                if store.removesByAbsence {
                    changes += Self.absent(local: items, ledger: current.ledger, deviceID: deviceID, now: started)
                }
                wholePasses[entity] = started
            }
        } catch is CloudStoreUnavailable {
            return
        }
        let sending = Set(changes.map(\.id))
        changes += try await store.removals().filter { !sending.contains($0.id) }.map {
            CloudPushItem(id: $0.id, fields: nil, clientModifiedAt: $0.at, deviceID: deviceID, deletedAt: $0.at, mergedInto: $0.mergedInto)
        }
        do {
            try await send(changes, of: entity, store: store, state: &current, into: &report)
        } catch {
            // What changed and didn't go up is found by comparing every item next time.
            wholePasses[entity] = nil
            throw error
        }
    }

    private func send(_ changes: [CloudPushItem], of entity: CloudEntity, store: any CloudEntityStore, state current: inout State, into report: inout Report) async throws {
        for batch in batches(changes) {
            try Task.checkCancellation()
            let results = try await api.push(entity, items: batch)
            let byID = Dictionary(results.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
            var conflicts: [CloudRecord] = []
            var refused: [CloudDeletion] = []
            var removalsDone: Set<String> = []
            for item in batch {
                guard let result = byID[item.id] else { continue }
                // A tombstone the server has answered, either way, isn't sent again.
                if item.isTombstone, result.status != .unknown, result.reason != "device_mismatch" {
                    removalsDone.insert(item.id)
                }
                switch result.status {
                case .accepted, .duplicate:
                    if item.isTombstone {
                        current.ledger[item.id] = nil
                        report.deleted += 1
                    } else {
                        current.ledger[item.id] = item.fields.map(CloudJSON.digest)
                        report.pushed += 1
                    }
                case .conflict:
                    report.conflicts += 1
                    switch result.current {
                    case .record(let server):
                        conflicts.append(server)
                    case .deletion(let server):
                        refused.append(server)
                        current.ledger[item.id] = nil
                    case nil:
                        current.ledger[item.id] = nil
                    }
                case .rejected:
                    report.rejected += 1
                    switch result.reason {
                    case "device_mismatch":
                        keep(current, for: entity)
                        throw CloudSyncError.deviceMismatch
                    case "deleted":
                        // Deleted on the server since: it goes here too.
                        refused.append(CloudDeletion(id: item.id))
                        current.ledger[item.id] = nil
                    default:
                        // Never taken however often it's sent: noted as sent, so it isn't
                        // sent again until it changes here.
                        current.ledger[item.id] = item.isTombstone ? nil : item.fields.map(CloudJSON.digest)
                    }
                case .unknown:
                    break
                }
            }
            if !conflicts.isEmpty || !refused.isEmpty {
                let held = try await store.apply(upserts: conflicts, deletes: refused)
                for record in conflicts {
                    if let fields = held[record.id] { current.ledger[record.id] = CloudJSON.digest(fields) }
                }
            }
            if !removalsDone.isEmpty { await store.removalsSent(removalsDone) }
            keep(current, for: entity)
        }
    }

    /// What to send up: every item given whose fields differ from what the server last had.
    static func changes(local: [CloudLocalItem], ledger: [String: String], deviceID: String, now: Date) -> [CloudPushItem] {
        local.compactMap { item in
            guard ledger[item.id] != CloudJSON.digest(item.fields) else { return nil }
            return CloudPushItem(id: item.id, fields: item.fields, clientModifiedAt: item.modifiedAt, deviceID: deviceID)
        }
    }

    /// A tombstone for every item the server has that a whole list read here leaves out. Only
    /// for a store that ``CloudEntityStore/removesByAbsence``.
    static func absent(local: [CloudLocalItem], ledger: [String: String], deviceID: String, now: Date) -> [CloudPushItem] {
        let held = Set(local.map(\.id))
        return ledger.keys.sorted().filter { !held.contains($0) }.map {
            CloudPushItem(id: $0, fields: nil, clientModifiedAt: now, deviceID: deviceID, deletedAt: now)
        }
    }

    /// Batches of at most `batchSize` items and about `batchBytes`.
    private func batches(_ items: [CloudPushItem]) -> [[CloudPushItem]] {
        var batches: [[CloudPushItem]] = []
        var batch: [CloudPushItem] = []
        var bytes = 0
        for item in items {
            let size = (try? CloudJSON.encoder.encode(item).count) ?? 1_024
            if !batch.isEmpty, batch.count >= batchSize || bytes + size > batchBytes {
                batches.append(batch)
                batch = []
                bytes = 0
            }
            batch.append(item)
            bytes += size
        }
        if !batch.isEmpty { batches.append(batch) }
        return batches
    }

    // MARK: - State on disk

    // Each kind's cursor and ledger are files of their own: the cursor is written after every
    // page, and the ledger, which runs to megabytes for a long history, only now and then.

    private func state(_ entity: CloudEntity) -> State {
        if let known = states[entity] { return known }
        var loaded = State()
        loaded.cursor = (try? String(contentsOf: cursorURL(entity), encoding: .utf8)).flatMap { $0.isEmpty ? nil : CloudCursor(rawValue: $0) }
        loaded.ledger = (try? Data(contentsOf: ledgerURL(entity))).flatMap { try? JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
        states[entity] = loaded
        return loaded
    }

    private func keep(_ state: State, for entity: CloudEntity) {
        states[entity] = state
    }

    /// The whole state, ledger and all.
    private func save(_ state: State, for entity: CloudEntity) {
        states[entity] = state
        saveCursor(state, for: entity)
        write((try? JSONEncoder().encode(state.ledger)) ?? Data("{}".utf8), to: ledgerURL(entity))
    }

    /// The cursor alone, beside a ledger that may be older. A ledger behind its cursor only
    /// sends items up again, which the server takes as duplicates.
    private func saveCursor(_ state: State, for entity: CloudEntity) {
        if let cursor = state.cursor {
            write(Data(cursor.rawValue.utf8), to: cursorURL(entity))
        } else {
            try? FileManager.default.removeItem(at: cursorURL(entity))
        }
    }

    private func write(_ data: Data, to url: URL) {
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try data.write(to: url, options: .atomic)
        } catch {
            // Kept in memory; the next sync writes it again.
        }
    }

    private nonisolated func cursorURL(_ entity: CloudEntity) -> URL {
        folder.appending(path: "cloud-\(entity.rawValue).cursor")
    }

    private nonisolated func ledgerURL(_ entity: CloudEntity) -> URL {
        folder.appending(path: "cloud-\(entity.rawValue).ledger.json")
    }
}

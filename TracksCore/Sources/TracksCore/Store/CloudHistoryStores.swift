import Foundation
import SwiftData

// The listening history as Trackside Cloud keeps it: plays, sessions and stations, each by its
// lasting id, read from and written to the store through its ``HistoryWriter``.
//
// What comes down is taken in field by field the way the store has always merged rows: a
// play's core (when, how, from where, who recorded it) stays as it was kept; what fills in
// later (album, catalog id, scrobbled, added to the playlist) is taken where it's missing
// here; a cover is the server's. Duplicates it makes are merged afterwards by the reconciler,
// as iCloud's are, and the ones merged away go up as tombstones.

/// Plays, by ``Capture/playID``.
public struct CloudPlaysStore: CloudEntityStore {
    public let entity = CloudEntity.plays
    let writer: HistoryWriter
    let journal: CloudRemovalJournal
    private let mark = CloudHistoryMark(entity: Schema.entityName(for: Capture.self))

    init(writer: HistoryWriter, journal: CloudRemovalJournal = .shared) {
        self.writer = writer
        self.journal = journal
    }

    public func localItems() async throws -> [CloudLocalItem] {
        try await writer.read { [mark] context in
            mark.start(in: context)
            let links = try CloudSessionLinks(context)
            var descriptor = FetchDescriptor<Capture>(predicate: #Predicate { $0.playID != nil })
            descriptor.propertiesToFetch = Self.properties
            return try context.fetch(descriptor).compactMap { Self.item(of: $0, links: links) }
        }
    }

    public func changedItems() async throws -> [CloudLocalItem]? {
        try await writer.read { [mark] context in
            guard let changed = mark.changes(in: context) else { return nil }
            let links = CloudSessionLinks()
            return TracksStore.existingCaptures(Array(changed), in: context).values.compactMap {
                Self.item(of: $0, links: links, session: $0.session)
            }
        }
    }

    /// Plays deleted or merged away here that the server may still have. One still held,
    /// because the save that removed it didn't happen, stays.
    public func removals() async throws -> [CloudRemoval] {
        let pending = journal.pending(.plays)
        guard !pending.isEmpty else { return [] }
        let ids = pending.map(\.id)
        let held = try await writer.read { context in Set(try Self.captures(ids, in: context).keys) }
        return pending.filter { !held.contains($0.id) }
    }

    public func removalsSent(_ ids: Set<String>) async {
        journal.forget(ids, for: .plays)
    }

    private static func item(of capture: Capture, links: CloudSessionLinks, session: Session? = nil) -> CloudLocalItem? {
        guard let id = capture.playID, let fields = try? CloudJSON.fields(fields(of: capture, links: links, session: session)) else { return nil }
        return CloudLocalItem(id: id, fields: fields, modifiedAt: capture.scrobbledAt ?? capture.addedToPlaylistAt ?? capture.capturedAt)
    }

    public func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        // A play removed here whose tombstone hasn't gone up yet isn't brought back.
        let removed = Set(journal.pending(.plays).map(\.id))
        let upserts = upserts.filter { !removed.contains($0.id) }
        return try await writer.write { context in
            let ids = upserts.map(\.id) + deletes.map(\.id)
            let existing = try Self.captures(ids, in: context)
            for deletion in deletes {
                for capture in existing[deletion.id] ?? [] { context.delete(capture) }
            }
            let links = try CloudSessionLinks(context)
            var held: [String: [String: JSONValue]] = [:]
            for record in upserts {
                // One this build can't read is left out: not kept, and so never sent back
                // as deleted.
                guard let incoming = try? CloudJSON.value(CloudPlayFields.self, from: record.fields) else { continue }
                let capture: Capture
                if let found = existing[record.id]?.first {
                    capture = found
                    Self.fill(capture, from: incoming)
                } else {
                    capture = Self.insert(incoming, id: record.id, deviceID: record.deviceID, in: context)
                }
                if capture.session == nil, let sessionID = incoming.sessionID {
                    capture.session = links.session(sessionID)
                }
                held[record.id] = try? CloudJSON.fields(Self.fields(of: capture, links: links, session: capture.session))
            }
            return held
        }
    }

    /// A play sent without a kind, which the server allows: on demand, as Android reads one.
    static let unstatedKind = CaptureKind.onDemand

    // MARK: - Fields

    static var properties: [PartialKeyPath<Capture>] {[
        \.playID, \.title, \.artistName, \.albumTitle, \.artworkURL, \.capturedAt, \.kindRawValue, \.sourceRawValue,
        \.songKey, \.songID, \.platformRawValue, \.capturedByDeviceID, \.scrobbledAt, \.addedToPlaylistAt,
    ]}

    static func fields(of capture: Capture, links: CloudSessionLinks, session: Session? = nil) -> CloudPlayFields {
        let link = session.map { links.link(for: $0) } ?? links.link(of: capture.persistentModelID)
        return CloudPlayFields(
            title: capture.title,
            artistName: capture.artistName,
            albumTitle: capture.albumTitle,
            artworkURL: capture.artworkURL,
            playedAtMs: CloudJSON.milliseconds(capture.capturedAt),
            kind: capture.kindRawValue,
            source: capture.sourceRawValue,
            songKey: capture.songKey.isEmpty ? nil : capture.songKey,
            catalogID: capture.songID.isEmpty ? nil : capture.songID,
            stationID: link?.stationID,
            sessionID: link?.sessionID,
            platform: capture.platformRawValue,
            recordedByDeviceID: capture.capturedByDeviceID.isEmpty ? nil : capture.capturedByDeviceID,
            scrobbledAtMs: capture.scrobbledAt.map(CloudJSON.milliseconds),
            addedToPlaylistAtMs: capture.addedToPlaylistAt.map(CloudJSON.milliseconds)
        )
    }

    /// What fills in later is taken where it's missing here; the cover is the server's.
    static func fill(_ capture: Capture, from incoming: CloudPlayFields) {
        if capture.albumTitle == nil { capture.albumTitle = incoming.albumTitle }
        if let artwork = incoming.artworkURL { capture.artworkURL = artwork }
        if capture.songID.isEmpty, let catalogID = incoming.catalogID { capture.songID = catalogID }
        if capture.scrobbledAt == nil, let scrobbled = incoming.scrobbledAtMs {
            capture.scrobbledAt = CloudJSON.date(milliseconds: scrobbled)
        }
        if capture.addedToPlaylistAt == nil, let added = incoming.addedToPlaylistAtMs {
            capture.addedToPlaylistAt = CloudJSON.date(milliseconds: added)
            capture.needsPlaylistWrite = false
        }
    }

    static func insert(_ incoming: CloudPlayFields, id: String, deviceID: String?, in context: ModelContext) -> Capture {
        let kind = incoming.kind.map { CaptureKind(rawValue: $0) ?? .radio } ?? Self.unstatedKind
        let capture = Capture(
            songID: incoming.catalogID ?? "",
            songKey: incoming.songKey ?? incoming.catalogID ?? SongIdentity.key(title: incoming.title, artistName: incoming.artistName),
            title: incoming.title,
            artistName: incoming.artistName,
            albumTitle: incoming.albumTitle,
            artworkURL: incoming.artworkURL,
            kind: kind,
            capturedAt: CloudJSON.date(milliseconds: incoming.playedAtMs),
            platform: incoming.platform.flatMap(CapturePlatform.init(rawValue:)) ?? .current,
            deviceID: incoming.recordedByDeviceID ?? deviceID ?? "",
            playID: id
        )
        // The cloud's id exactly as it came, whatever its form, so this row and the server's
        // stay one item.
        capture.playID = id
        // Kept as it came, so a source this build doesn't know stays what it was.
        capture.sourceRawValue = incoming.source
        // The play's own value for the kind, even one this build reads as radio.
        capture.kindRawValue = incoming.kind ?? kind.rawValue
        capture.scrobbledAt = incoming.scrobbledAtMs.map(CloudJSON.date(milliseconds:))
        capture.addedToPlaylistAt = incoming.addedToPlaylistAtMs.map(CloudJSON.date(milliseconds:))
        // Only the device that recorded a play writes it to the playlist, and only once.
        capture.needsPlaylistWrite = kind == .radio && capture.addedToPlaylistAt == nil
        context.insert(capture)
        return capture
    }

    static func captures(_ ids: [String], in context: ModelContext) throws -> [String: [Capture]] {
        guard !ids.isEmpty else { return [:] }
        var found: [String: [Capture]] = [:]
        for start in stride(from: 0, to: ids.count, by: 500) {
            let chunk = Array(ids[start..<min(start + 500, ids.count)])
            let rows = try context.fetch(FetchDescriptor<Capture>(predicate: #Predicate { chunk.contains($0.playID ?? "") }))
            for row in rows {
                guard let id = row.playID else { continue }
                found[id, default: []].append(row)
            }
        }
        return found
    }
}

/// Which session and station each play belongs to, read once for a whole sync.
struct CloudSessionLinks {
    struct Link {
        let sessionID: String?
        let stationID: String?
    }

    private var byCapture: [PersistentIdentifier: Link] = [:]
    private var sessions: [String: Session] = [:]

    /// None read: for a few plays, each asked for its own session.
    init() {}

    init(_ context: ModelContext) throws {
        for session in try context.fetch(FetchDescriptor<Session>()) {
            let link = Self.link(for: session)
            if let id = session.sessionID { sessions[id] = session }
            for capture in session.captures ?? [] { byCapture[capture.persistentModelID] = link }
        }
    }

    func link(of capture: PersistentIdentifier) -> Link? {
        byCapture[capture]
    }

    func link(for session: Session) -> Link {
        Self.link(for: session)
    }

    func session(_ id: String) -> Session? {
        sessions[id]
    }

    private static func link(for session: Session) -> Link {
        Link(sessionID: session.sessionID, stationID: session.station.map { TracksStore.stationKey($0.name) })
    }
}

/// Sessions, by ``Session/sessionID``. Only the device that recorded one changes it.
public struct CloudSessionsStore: CloudEntityStore {
    public let entity = CloudEntity.sessions
    let writer: HistoryWriter
    private let mark = CloudHistoryMark(entity: Schema.entityName(for: Session.self))

    init(writer: HistoryWriter) {
        self.writer = writer
    }

    public func localItems() async throws -> [CloudLocalItem] {
        try await writer.read { [mark] context in
            mark.start(in: context)
            return try context.fetch(FetchDescriptor<Session>(predicate: #Predicate { $0.sessionID != nil })).compactMap(Self.item)
        }
    }

    public func changedItems() async throws -> [CloudLocalItem]? {
        try await writer.read { [mark] context in
            guard let changed = mark.changes(in: context) else { return nil }
            return try ListeningHistoryReader.fetch(Session.self, ids: changed, in: context).compactMap(Self.item)
        }
    }

    private static func item(_ session: Session) -> CloudLocalItem? {
        guard let id = session.sessionID, let fields = try? CloudJSON.fields(fields(of: session)) else { return nil }
        return CloudLocalItem(id: id, fields: fields, modifiedAt: session.endedAt ?? session.lastActivityAt)
    }

    public func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        try await writer.write { context in
            var byID: [String: Session] = [:]
            for session in try context.fetch(FetchDescriptor<Session>(predicate: #Predicate { $0.sessionID != nil })) {
                if let id = session.sessionID { byID[id] = session }
            }
            let stations = try CloudStationsStore.stationsByKey(in: context)
            for deletion in deletes {
                if let session = byID.removeValue(forKey: deletion.id) { context.delete(session) }
            }
            var held: [String: [String: JSONValue]] = [:]
            for record in upserts {
                guard let incoming = try? CloudJSON.value(CloudSessionFields.self, from: record.fields) else { continue }
                let session: Session
                if let found = byID[record.id] {
                    session = found
                } else {
                    session = Session(startedAt: CloudJSON.date(milliseconds: incoming.startedAtMs), station: nil, deviceID: incoming.deviceID)
                    session.sessionID = record.id
                    context.insert(session)
                    byID[record.id] = session
                }
                // The recording device's word on its own session is the last one.
                session.startedAt = CloudJSON.date(milliseconds: incoming.startedAtMs)
                session.lastActivityAt = CloudJSON.date(milliseconds: incoming.lastActivityAtMs)
                session.endedAt = incoming.endedAtMs.map(CloudJSON.date(milliseconds:))
                session.deviceID = incoming.deviceID
                if session.station == nil, let stationID = incoming.stationID { session.station = stations[stationID] }
                held[record.id] = try? CloudJSON.fields(Self.fields(of: session))
            }
            return held
        }
    }

    static func fields(of session: Session) -> CloudSessionFields {
        CloudSessionFields(
            startedAtMs: CloudJSON.milliseconds(session.startedAt),
            endedAtMs: session.endedAt.map(CloudJSON.milliseconds),
            lastActivityAtMs: CloudJSON.milliseconds(session.lastActivityAt),
            deviceID: session.deviceID,
            stationID: session.station.map { TracksStore.stationKey($0.name) }
        )
    }
}

/// Stations, by their folded name, so every device's copy of a station is one item.
public struct CloudStationsStore: CloudEntityStore {
    public let entity = CloudEntity.stations
    let writer: HistoryWriter

    init(writer: HistoryWriter) {
        self.writer = writer
    }

    public func localItems() async throws -> [CloudLocalItem] {
        try await writer.read { context in
            let merged = Self.merged(try context.fetch(FetchDescriptor<Station>()))
            return merged.sorted { $0.key < $1.key }.compactMap { key, fields in
                guard let encoded = try? CloudJSON.fields(fields) else { return nil }
                return CloudLocalItem(id: key, fields: encoded, modifiedAt: CloudJSON.date(milliseconds: fields.lastSeenMs))
            }
        }
    }

    public func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        try await writer.write { context in
            var stations = try Self.stationsByKey(in: context)
            for deletion in deletes {
                // A station still named by sessions here stays: removing it would orphan them.
                if let station = stations[deletion.id], (station.sessions ?? []).isEmpty {
                    context.delete(station)
                    stations[deletion.id] = nil
                }
            }
            for record in upserts {
                guard let incoming = try? CloudJSON.value(CloudStationFields.self, from: record.fields) else { continue }
                if let station = stations[record.id] {
                    station.firstSeenAt = min(station.firstSeenAt, CloudJSON.date(milliseconds: incoming.firstSeenMs))
                    station.lastSeenAt = max(station.lastSeenAt, CloudJSON.date(milliseconds: incoming.lastSeenMs))
                    if station.catalogID == nil { station.catalogID = incoming.catalogID }
                    if station.artworkURL == nil { station.artworkURL = incoming.artworkURL }
                } else {
                    let station = Station(name: incoming.name, catalogID: incoming.catalogID, artworkURL: incoming.artworkURL, firstSeenAt: CloudJSON.date(milliseconds: incoming.firstSeenMs))
                    station.lastSeenAt = CloudJSON.date(milliseconds: incoming.lastSeenMs)
                    context.insert(station)
                    stations[record.id] = station
                }
            }
            let merged = Self.merged(Array(stations.values))
            var held: [String: [String: JSONValue]] = [:]
            for record in upserts {
                held[record.id] = merged[record.id].flatMap { try? CloudJSON.fields($0) }
            }
            return held
        }
    }

    /// One station per folded name: the oldest row, as the reconciler keeps it.
    static func stationsByKey(in context: ModelContext) throws -> [String: Station] {
        let all = try context.fetch(FetchDescriptor<Station>(sortBy: [SortDescriptor(\.firstSeenAt), SortDescriptor(\.name)]))
        var byKey: [String: Station] = [:]
        for station in all where byKey[TracksStore.stationKey(station.name)] == nil {
            byKey[TracksStore.stationKey(station.name)] = station
        }
        return byKey
    }

    /// Every device's rows for a station as one: the earliest first seen, the latest last
    /// seen, the first ids known.
    static func merged(_ stations: [Station]) -> [String: CloudStationFields] {
        var merged: [String: CloudStationFields] = [:]
        for station in stations.sorted(by: { ($0.firstSeenAt, $0.name) < ($1.firstSeenAt, $1.name) }) {
            let key = TracksStore.stationKey(station.name)
            let first = CloudJSON.milliseconds(station.firstSeenAt)
            let last = CloudJSON.milliseconds(station.lastSeenAt)
            if var known = merged[key] {
                known.firstSeenMs = min(known.firstSeenMs, first)
                known.lastSeenMs = max(known.lastSeenMs, last)
                if known.catalogID == nil { known.catalogID = station.catalogID }
                if known.artworkURL == nil { known.artworkURL = station.artworkURL }
                merged[key] = known
            } else {
                merged[key] = CloudStationFields(name: station.name, catalogID: station.catalogID, artworkURL: station.artworkURL, firstSeenMs: first, lastSeenMs: last)
            }
        }
        return merged
    }
}

/// Where a store's reading of the history for Trackside Cloud got to: the store's history
/// since then names the rows that changed, so a sync reads only those.
final class CloudHistoryMark: @unchecked Sendable {
    private enum Place {
        case unread
        case at(DefaultHistoryToken?)
    }

    let entity: String
    private let lock = NSLock()
    private var place = Place.unread

    init(entity: String) {
        self.entity = entity
    }

    /// Called before every row is read: whatever saves after this is in the next changes.
    func start(in context: ModelContext) {
        let latest = try? ListeningHistoryReader.latestToken(in: context)
        lock.withLock { place = .at(latest) }
    }

    /// The rows of this kind inserted or changed since the last read, deleted ones among
    /// them. Nil when that can't be told, or is most of them: everything is read instead.
    func changes(in context: ModelContext) -> Set<PersistentIdentifier>? {
        guard case .at(let since) = lock.withLock({ place }) else { return nil }
        guard let transactions = try? ListeningHistoryReader.transactions(after: since, in: context) else {
            lock.withLock { place = .unread }
            return nil
        }
        var changed = Set<PersistentIdentifier>()
        for transaction in transactions {
            for change in transaction.changes where change.changedPersistentIdentifier.entityName == entity {
                changed.insert(change.changedPersistentIdentifier)
            }
        }
        guard changed.count <= ListeningHistoryReader.incrementalLimit else {
            lock.withLock { place = .unread }
            return nil
        }
        if let latest = transactions.last?.token { lock.withLock { place = .at(latest) } }
        return changed
    }
}

extension TracksStore {
    /// The history's stores for Trackside Cloud, working through this store's writer.
    public var cloudStores: [any CloudEntityStore] {
        [CloudStationsStore(writer: writer), CloudSessionsStore(writer: writer), CloudPlaysStore(writer: writer)]
    }
}

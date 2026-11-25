import Foundation

// What's hidden, as Trackside Cloud keeps it: one item per forgotten song, blocked artist or
// excluded station, so two devices changing a list at once both count, and a removal is a
// change of its own rather than a whole list that's newer.

/// Forgotten songs, by song identity. Grow-only, as they always were: the latest time a song
/// was forgotten wins, and a song is never unforgotten by sync.
public struct CloudForgottenSongsStore: CloudEntityStore {
    public let entity = CloudEntity.forgottenSongs

    public init() {}

    public func localItems() async throws -> [CloudLocalItem] {
        let settings = CaptureSettings()
        let dates = settings.forgottenSongDates
        return settings.forgottenSongs.sorted().compactMap { key in
            // Forgotten before dates were kept: for good, so dated 1970.
            let at = dates[key] ?? Date(timeIntervalSince1970: 0)
            guard let fields = try? CloudJSON.fields(CloudForgottenSongFields(songKey: key, forgottenAtMs: CloudJSON.milliseconds(at))) else { return nil }
            return CloudLocalItem(id: key, fields: fields, modifiedAt: at)
        }
    }

    public func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        let settings = CaptureSettings()
        var held: [String: [String: JSONValue]] = [:]
        for record in upserts {
            guard let incoming = try? CloudJSON.value(CloudForgottenSongFields.self, from: record.fields) else { continue }
            let at = CloudJSON.date(milliseconds: incoming.forgottenAtMs)
            if incoming.forgottenAtMs > 0 {
                settings.noteForgotten(incoming.songKey, at: at)
            } else {
                settings.forgottenSongs.insert(incoming.songKey)
            }
        }
        let dates = settings.forgottenSongDates
        let forgotten = settings.forgottenSongs
        for record in upserts where forgotten.contains(record.id) {
            let at = dates[record.id] ?? Date(timeIntervalSince1970: 0)
            held[record.id] = try? CloudJSON.fields(CloudForgottenSongFields(songKey: record.id, forgottenAtMs: CloudJSON.milliseconds(at)))
        }
        return held
    }
}

/// Blocked artists, by folded name. A newer add revives one another device unblocked.
public struct CloudBlockedArtistsStore: CloudEntityStore {
    public let entity = CloudEntity.blockedArtists

    public init() {}

    /// The list is the settings' own, always there: one gone from it was removed here.
    public var removesByAbsence: Bool { true }

    public func localItems() async throws -> [CloudLocalItem] {
        CaptureSettings().blockedArtists.names.compactMap { name in
            let id = StatsCalculator.folded(name)
            guard let fields = try? CloudJSON.fields(CloudNameFields(name: name)) else { return nil }
            return CloudLocalItem(id: id, fields: fields, modifiedAt: Date())
        }
    }

    public func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        let settings = CaptureSettings()
        let removed = Set(deletes.map(\.id))
        var names = settings.blockedArtists.names.filter { !removed.contains(StatsCalculator.folded($0)) }
        for record in upserts {
            guard let name = (try? CloudJSON.value(CloudNameFields.self, from: record.fields))?.name else { continue }
            names.append(name)
        }
        let blocked = BlockedArtists(names)
        settings.blockedArtists = blocked
        var held: [String: [String: JSONValue]] = [:]
        for name in blocked.names {
            let id = StatsCalculator.folded(name)
            if upserts.contains(where: { $0.id == id }) { held[id] = try? CloudJSON.fields(CloudNameFields(name: name)) }
        }
        return held
    }
}

/// Stations left out of the history, by folded name.
public struct CloudExcludedStationsStore: CloudEntityStore {
    public let entity = CloudEntity.excludedStations

    public init() {}

    /// The list is the settings' own, always there: one gone from it was removed here.
    public var removesByAbsence: Bool { true }

    public func localItems() async throws -> [CloudLocalItem] {
        Self.byKey(CaptureSettings().excludedStations).sorted { $0.key < $1.key }.compactMap { key, name in
            guard let fields = try? CloudJSON.fields(CloudNameFields(name: name)) else { return nil }
            return CloudLocalItem(id: key, fields: fields, modifiedAt: Date())
        }
    }

    public func apply(upserts: [CloudRecord], deletes: [CloudDeletion]) async throws -> [String: [String: JSONValue]] {
        let settings = CaptureSettings()
        let removed = Set(deletes.map(\.id))
        var names = settings.excludedStations.filter { !removed.contains(TracksStore.stationKey($0)) }
        for record in upserts {
            guard let name = (try? CloudJSON.value(CloudNameFields.self, from: record.fields))?.name else { continue }
            if !names.contains(where: { TracksStore.stationKey($0) == record.id }) { names.insert(name) }
        }
        settings.excludedStations = names
        let kept = Self.byKey(names)
        var held: [String: [String: JSONValue]] = [:]
        for record in upserts {
            held[record.id] = kept[record.id].flatMap { try? CloudJSON.fields(CloudNameFields(name: $0)) }
        }
        return held
    }

    /// One spelling per station, the same on every pass: the first in order.
    static func byKey(_ names: Set<String>) -> [String: String] {
        var byKey: [String: String] = [:]
        for name in names.sorted() where byKey[TracksStore.stationKey(name)] == nil {
            byKey[TracksStore.stationKey(name)] = name
        }
        return byKey
    }
}

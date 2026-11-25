import Foundation

// Trackside Cloud's wire, as tracks-companion's docs/api.md section 19 writes it: envelopes in
// snake_case with RFC 3339 times, entity paths in kebab-case, and each item's `fields` in the
// names Tracks has always used, with times inside them as integer milliseconds.

/// What Trackside Cloud keeps, by the path it's kept under.
public enum CloudEntity: String, CaseIterable, Codable, Sendable, Hashable {
    case plays
    case sessions
    case stations
    case settings
    case forgottenSongs = "forgotten-songs"
    case blockedArtists = "blocked-artists"
    case excludedStations = "excluded-stations"
    case playlists
    case playlistEntries = "playlist-entries"
    case likes

    /// `cloud/plays`, under `/api/v1/`.
    public var path: String { "cloud/\(rawValue)" }

    /// `cloud/plays:batch`, where pushes go.
    public var batchPath: String { "cloud/\(rawValue):batch" }
}

/// Where a pull got to: `"<epoch>.<seq>"`, opaque, sent back exactly as it came. A cursor from
/// before a backup was restored on the server is refused with `410 sync_reset`.
public struct CloudCursor: Codable, Sendable, Hashable, RawRepresentable, CustomStringConvertible {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let text = try? container.decode(String.self) {
            rawValue = text
        } else {
            rawValue = String(try container.decode(Int64.self))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }

    public var description: String { rawValue }
}

/// Any JSON value, for `fields` and setting values, so values this build doesn't know still
/// travel untouched.
public enum JSONValue: Codable, Sendable, Hashable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int64.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }

    public var string: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    public var isNull: Bool { self == .null }
}

/// Trackside's JSON for the cloud: snake_case envelopes set by each type's keys, times in
/// RFC 3339 with milliseconds, and keys sorted so the same value is always the same bytes.
public enum CloudJSON {
    public static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            guard let date = date(from: text) else {
                throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Not a time: \(text)"))
            }
            return date
        }
        return decoder
    }()

    public static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(string(from: date))
        }
        return encoder
    }()

    static func date(from text: String) -> Date? {
        if let date = try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) { return date }
        return try? Date(text, strategy: .iso8601)
    }

    static func string(from date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true))
    }

    /// A value's fields, as an item carries them.
    public static func fields<Value: Encodable>(_ value: Value) throws -> [String: JSONValue] {
        guard case .object(let fields) = try decoder.decode(JSONValue.self, from: encoder.encode(value)) else {
            throw EncodingError.invalidValue(value, .init(codingPath: [], debugDescription: "Fields are an object"))
        }
        return fields
    }

    /// An item's fields, read as a value.
    public static func value<Value: Decodable>(_ type: Value.Type, from fields: [String: JSONValue]) throws -> Value {
        try decoder.decode(type, from: encoder.encode(fields))
    }

    /// The same for the same fields, whatever order they came in: 64-bit FNV-1a of their
    /// sorted JSON, as hex.
    public static func digest(_ fields: [String: JSONValue]) -> String {
        let bytes = (try? encoder.encode(fields)) ?? Data()
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in bytes {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        let hex = String(hash, radix: 16)
        return String(repeating: "0", count: 16 - hex.count) + hex
    }

    /// Milliseconds since 1970, as times inside `fields` are written.
    public static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    public static func date(milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }
}

// MARK: - Pulling

/// One item as the server holds it.
public struct CloudRecord: Codable, Sendable, Hashable {
    public var id: String
    /// The server's change sequence for this item, for compare-and-set (`base_version`).
    public var version: Int64?
    public var fields: [String: JSONValue]
    public var clientModifiedAt: Date?
    public var deviceID: String?

    public init(id: String, version: Int64? = nil, fields: [String: JSONValue], clientModifiedAt: Date? = nil, deviceID: String? = nil) {
        self.id = id
        self.version = version
        self.fields = fields
        self.clientModifiedAt = clientModifiedAt
        self.deviceID = deviceID
    }

    enum CodingKeys: String, CodingKey {
        case id, version, fields
        case clientModifiedAt = "client_modified_at"
        case deviceID = "device_id"
    }
}

/// An item that's gone, and, for a duplicate merged away, the one it became part of.
public struct CloudDeletion: Codable, Sendable, Hashable {
    public var id: String
    public var version: Int64?
    public var deletedAt: Date?
    public var clientModifiedAt: Date?
    public var deviceID: String?
    public var mergedInto: String?

    public init(id: String, version: Int64? = nil, deletedAt: Date? = nil, clientModifiedAt: Date? = nil, deviceID: String? = nil, mergedInto: String? = nil) {
        self.id = id
        self.version = version
        self.deletedAt = deletedAt
        self.clientModifiedAt = clientModifiedAt
        self.deviceID = deviceID
        self.mergedInto = mergedInto
    }

    enum CodingKeys: String, CodingKey {
        case id, version
        case deletedAt = "deleted_at"
        case clientModifiedAt = "client_modified_at"
        case deviceID = "device_id"
        case mergedInto = "merged_into"
    }
}

/// `GET /cloud/{entity}?cursor=&limit=`: a page of changes after the cursor, or of a full
/// snapshot without one.
public struct CloudPage: Codable, Sendable, Hashable {
    public var upserts: [CloudRecord]
    public var deletes: [CloudDeletion]
    public var cursor: CloudCursor?
    public var hasMore: Bool

    public init(upserts: [CloudRecord] = [], deletes: [CloudDeletion] = [], cursor: CloudCursor?, hasMore: Bool = false) {
        self.upserts = upserts
        self.deletes = deletes
        self.cursor = cursor
        self.hasMore = hasMore
    }

    enum CodingKeys: String, CodingKey {
        case upserts, deletes, cursor
        case hasMore = "has_more"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        upserts = try container.decodeIfPresent([CloudRecord].self, forKey: .upserts) ?? []
        deletes = try container.decodeIfPresent([CloudDeletion].self, forKey: .deletes) ?? []
        cursor = try container.decodeIfPresent(CloudCursor.self, forKey: .cursor)
        hasMore = try container.decodeIfPresent(Bool.self, forKey: .hasMore) ?? false
    }
}

// MARK: - Pushing

/// One change sent up: an item's fields, or with `deletedAt`, its tombstone.
public struct CloudPushItem: Codable, Sendable, Hashable {
    public var id: String
    /// The `version` last seen, or 0 for "must not exist yet". Left out, the item isn't
    /// compared.
    public var baseVersion: Int64?
    public var fields: [String: JSONValue]?
    public var clientModifiedAt: Date
    /// This device's id. The server checks it's the one this device's key speaks for.
    public var deviceID: String
    public var deletedAt: Date?
    public var mergedInto: String?
    /// Brings back an item the server holds a tombstone for, instead of `rejected` `deleted`.
    public var undelete: Bool?

    public init(id: String, baseVersion: Int64? = nil, fields: [String: JSONValue]?, clientModifiedAt: Date, deviceID: String, deletedAt: Date? = nil, mergedInto: String? = nil, undelete: Bool? = nil) {
        self.id = id
        self.baseVersion = baseVersion
        self.fields = fields
        self.clientModifiedAt = clientModifiedAt
        self.deviceID = deviceID
        self.deletedAt = deletedAt
        self.mergedInto = mergedInto
        self.undelete = undelete
    }

    public var isTombstone: Bool { deletedAt != nil }

    enum CodingKeys: String, CodingKey {
        case id, fields, undelete
        case baseVersion = "base_version"
        case clientModifiedAt = "client_modified_at"
        case deviceID = "device_id"
        case deletedAt = "deleted_at"
        case mergedInto = "merged_into"
    }
}

/// `POST /cloud/{entity}:batch`.
public struct CloudPushRequest: Codable, Sendable, Hashable {
    public var items: [CloudPushItem]

    public init(items: [CloudPushItem]) {
        self.items = items
    }
}

/// What the server made of one item. Sending the same item again gets the same answer.
public enum CloudPushStatus: String, Codable, Sendable, Hashable {
    case accepted
    /// It had it already.
    case duplicate
    /// Kept the server's: `current` is what it holds.
    case conflict
    /// Never taken: `reason` says why, such as `deleted` or `device_mismatch`.
    case rejected
    case unknown

    public init(from decoder: any Decoder) throws {
        self = Self(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
    }
}

/// What the server holds of an item after a conflict: the item, or its tombstone.
public enum CloudCurrent: Codable, Sendable, Hashable {
    case record(CloudRecord)
    case deletion(CloudDeletion)

    private enum Probe: String, CodingKey {
        case fields
        case deletedAt = "deleted_at"
    }

    public init(from decoder: any Decoder) throws {
        let probe = try decoder.container(keyedBy: Probe.self)
        if probe.contains(.deletedAt) || !probe.contains(.fields) {
            self = .deletion(try CloudDeletion(from: decoder))
        } else {
            self = .record(try CloudRecord(from: decoder))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        switch self {
        case .record(let record): try record.encode(to: encoder)
        case .deletion(let deletion): try deletion.encode(to: encoder)
        }
    }

    public var record: CloudRecord? {
        if case .record(let record) = self { return record }
        return nil
    }

    public var deletion: CloudDeletion? {
        if case .deletion(let deletion) = self { return deletion }
        return nil
    }
}

public struct CloudPushResult: Codable, Sendable, Hashable {
    /// For a keyed kind pushed with an empty id, the key the server derived.
    public var id: String
    public var status: CloudPushStatus
    public var version: Int64?
    /// Why it was `rejected`: `invalid`, `deleted`, `device_mismatch`, `quota_exceeded` and
    /// others. An open set.
    public var reason: String?
    /// With `conflict`: what stands, absent when nothing is stored.
    public var current: CloudCurrent?

    public init(id: String, status: CloudPushStatus, version: Int64? = nil, reason: String? = nil, current: CloudCurrent? = nil) {
        self.id = id
        self.status = status
        self.version = version
        self.reason = reason
        self.current = current
    }
}

public struct CloudPushResponse: Codable, Sendable, Hashable {
    public var results: [CloudPushResult]
}

// MARK: - Settings

/// One setting's value, when it was set and by which device. A cleared setting is an
/// explicit null, so clearing it travels like any other change.
public struct CloudSetting: Codable, Sendable, Hashable {
    public var value: JSONValue
    public var modifiedAt: Date
    public var deviceID: String?
    /// The server's change sequence for the key, as it hands settings out. Never sent up.
    public var version: Int64?

    public init(value: JSONValue, modifiedAt: Date, deviceID: String?, version: Int64? = nil) {
        self.value = value
        self.modifiedAt = modifiedAt
        self.deviceID = deviceID
        self.version = version
    }

    enum CodingKeys: String, CodingKey {
        case value, version
        case modifiedAt = "modified_at"
        case deviceID = "device_id"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        value = try container.decodeIfPresent(JSONValue.self, forKey: .value) ?? .null
        modifiedAt = try container.decode(Date.self, forKey: .modifiedAt)
        deviceID = try container.decodeIfPresent(String.self, forKey: .deviceID)
        version = try container.decodeIfPresent(Int64.self, forKey: .version)
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(value, forKey: .value)
        try container.encode(modifiedAt, forKey: .modifiedAt)
        try container.encodeIfPresent(deviceID, forKey: .deviceID)
        try container.encodeIfPresent(version, forKey: .version)
    }

    /// As a PATCH sends it: value, time and device, without the server's version.
    public var sent: CloudSetting {
        CloudSetting(value: value, modifiedAt: modifiedAt, deviceID: deviceID)
    }
}

/// `PATCH /cloud/settings`: `value` always present, `null` to clear.
public struct CloudSettingsPatch: Codable, Sendable, Hashable {
    public var settings: [String: CloudSetting]

    public init(settings: [String: CloudSetting]) {
        self.settings = settings.mapValues(\.sent)
    }

    enum CodingKeys: String, CodingKey {
        case settings
    }

    public init(from decoder: any Decoder) throws {
        settings = try decoder.container(keyedBy: CodingKeys.self).decode([String: CloudSetting].self, forKey: .settings)
    }
}

/// What the server made of one setting sent. It decides the way every device does, and
/// `current` is the value that stands after it.
public struct CloudSettingResult: Codable, Sendable, Hashable {
    public var key: String
    public var status: CloudPushStatus
    /// Why it was `rejected`, such as `encryption_required`.
    public var reason: String?
    public var current: CloudSetting?

    public init(key: String, status: CloudPushStatus, reason: String? = nil, current: CloudSetting? = nil) {
        self.key = key
        self.status = status
        self.reason = reason
        self.current = current
    }
}

/// The answer to `PATCH /cloud/settings`, one result per key.
public struct CloudSettingResults: Codable, Sendable, Hashable {
    public var results: [CloudSettingResult]

    public init(results: [CloudSettingResult]) {
        self.results = results
    }
}

/// `GET /cloud/settings?cursor=`.
public struct CloudSettingsPage: Codable, Sendable, Hashable {
    public var settings: [String: CloudSetting]
    public var cursor: CloudCursor?
    public var hasMore: Bool

    public init(settings: [String: CloudSetting], cursor: CloudCursor?, hasMore: Bool = false) {
        self.settings = settings
        self.cursor = cursor
        self.hasMore = hasMore
    }

    enum CodingKeys: String, CodingKey {
        case settings, cursor
        case hasMore = "has_more"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        settings = try container.decodeIfPresent([String: CloudSetting].self, forKey: .settings) ?? [:]
        cursor = try container.decodeIfPresent(CloudCursor.self, forKey: .cursor)
        hasMore = try container.decodeIfPresent(Bool.self, forKey: .hasMore) ?? false
    }
}

// MARK: - Devices

/// `PUT /cloud/devices/{id}`: registers this device's id with the key it speaks with, and
/// the devices it was restored or migrated from, so their plays become its own.
public struct CloudDeviceRegistration: Codable, Sendable, Hashable {
    public var predecessors: [String]
    public var name: String?
    public var platform: String?

    public init(predecessors: [String], name: String? = nil, platform: String? = nil) {
        self.predecessors = predecessors
        self.name = name
        self.platform = platform
    }
}

public struct CloudDevice: Codable, Sendable, Hashable, Identifiable {
    public var deviceID: String
    public var predecessors: [String]
    public var name: String?
    public var platform: String?
    /// The signed-in install (`/me/devices`) that registered it.
    public var serverDeviceID: String?
    /// Whether it's the device asking.
    public var current: Bool
    public var registeredAt: Date?
    public var updatedAt: Date?

    public var id: String { deviceID }

    public init(deviceID: String, predecessors: [String] = [], name: String? = nil, platform: String? = nil, serverDeviceID: String? = nil, current: Bool = false, registeredAt: Date? = nil, updatedAt: Date? = nil) {
        self.deviceID = deviceID
        self.predecessors = predecessors
        self.name = name
        self.platform = platform
        self.serverDeviceID = serverDeviceID
        self.current = current
        self.registeredAt = registeredAt
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case predecessors, name, platform, current
        case deviceID = "device_id"
        case serverDeviceID = "server_device_id"
        case registeredAt = "registered_at"
        case updatedAt = "updated_at"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceID = try container.decode(String.self, forKey: .deviceID)
        predecessors = try container.decodeIfPresent([String].self, forKey: .predecessors) ?? []
        name = try container.decodeIfPresent(String.self, forKey: .name)
        platform = try container.decodeIfPresent(String.self, forKey: .platform)
        serverDeviceID = try container.decodeIfPresent(String.self, forKey: .serverDeviceID)
        current = try container.decodeIfPresent(Bool.self, forKey: .current) ?? false
        registeredAt = try container.decodeIfPresent(Date.self, forKey: .registeredAt)
        updatedAt = try container.decodeIfPresent(Date.self, forKey: .updatedAt)
    }
}

public struct CloudDeviceList: Codable, Sendable, Hashable {
    public var devices: [CloudDevice]
}

// MARK: - Usage

/// `GET /cloud/usage`: what this person keeps on the server, tombstones included, against
/// their quota.
public struct CloudUsage: Codable, Sendable, Hashable {
    public struct Amount: Codable, Sendable, Hashable {
        public var items: Int64
        public var bytes: Int64

        public init(items: Int64, bytes: Int64) {
            self.items = items
            self.bytes = bytes
        }
    }

    public var items: Int64
    public var bytes: Int64
    public var quota: Amount?
    /// By path, such as `plays`.
    public var entities: [String: Amount]

    public init(items: Int64, bytes: Int64, quota: Amount?, entities: [String: Amount] = [:]) {
        self.items = items
        self.bytes = bytes
        self.quota = quota
        self.entities = entities
    }

    enum CodingKeys: String, CodingKey {
        case items, bytes, quota, entities
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decode(Int64.self, forKey: .items)
        bytes = try container.decode(Int64.self, forKey: .bytes)
        quota = try container.decodeIfPresent(Amount.self, forKey: .quota)
        entities = try container.decodeIfPresent([String: Amount].self, forKey: .entities) ?? [:]
    }
}

// MARK: - Fields

/// A play's fields. Its core (title, artist, album, when, how, from where, who recorded it)
/// never changes; the rest fill in later.
public struct CloudPlayFields: Codable, Sendable, Hashable {
    public var title: String
    public var artistName: String
    public var albumTitle: String?
    public var artworkURL: String?
    public var playedAtMs: Int64
    /// Optional on the wire, as the server has it: only title, artist and time are required.
    public var kind: String?
    public var source: String?
    public var songKey: String?
    public var catalogID: String?
    public var serverSongID: String?
    public var spotifyID: String?
    public var stationID: String?
    public var sessionID: String?
    public var platform: String?
    public var recordedByDeviceID: String?
    public var playedMs: Int64?
    public var durationMs: Int64?
    /// Milliseconds, under the field's own name `scrobbledAt`.
    public var scrobbledAtMs: Int64?
    /// Milliseconds, under the field's own name `addedToPlaylistAt`.
    public var addedToPlaylistAtMs: Int64?

    public init(
        title: String, artistName: String, albumTitle: String? = nil, artworkURL: String? = nil,
        playedAtMs: Int64, kind: String?, source: String? = nil, songKey: String? = nil, catalogID: String? = nil,
        serverSongID: String? = nil, spotifyID: String? = nil,
        stationID: String? = nil, sessionID: String? = nil, platform: String? = nil, recordedByDeviceID: String? = nil,
        playedMs: Int64? = nil, durationMs: Int64? = nil,
        scrobbledAtMs: Int64? = nil, addedToPlaylistAtMs: Int64? = nil
    ) {
        self.title = title
        self.artistName = artistName
        self.albumTitle = albumTitle
        self.artworkURL = artworkURL
        self.playedAtMs = playedAtMs
        self.kind = kind
        self.source = source
        self.songKey = songKey
        self.catalogID = catalogID
        self.serverSongID = serverSongID
        self.spotifyID = spotifyID
        self.stationID = stationID
        self.sessionID = sessionID
        self.platform = platform
        self.recordedByDeviceID = recordedByDeviceID
        self.playedMs = playedMs
        self.durationMs = durationMs
        self.scrobbledAtMs = scrobbledAtMs
        self.addedToPlaylistAtMs = addedToPlaylistAtMs
    }

    enum CodingKeys: String, CodingKey {
        case title, artistName, albumTitle, artworkURL, playedAtMs, kind, source, songKey, catalogID, serverSongID, spotifyID
        case stationID, sessionID, platform, recordedByDeviceID, playedMs, durationMs
        case scrobbledAtMs = "scrobbledAt"
        case addedToPlaylistAtMs = "addedToPlaylistAt"
    }
}

/// A stretch of listening to one station, which only the device that recorded it changes.
/// The server knows that device by `recordedByDeviceID`, sent beside `deviceID`.
public struct CloudSessionFields: Codable, Sendable, Hashable {
    public var startedAtMs: Int64
    public var endedAtMs: Int64?
    public var lastActivityAtMs: Int64
    public var deviceID: String?
    public var stationID: String?

    public init(startedAtMs: Int64, endedAtMs: Int64?, lastActivityAtMs: Int64, deviceID: String?, stationID: String?) {
        self.startedAtMs = startedAtMs
        self.endedAtMs = endedAtMs
        self.lastActivityAtMs = lastActivityAtMs
        self.deviceID = deviceID
        self.stationID = stationID
    }

    enum CodingKeys: String, CodingKey {
        case startedAtMs, endedAtMs, lastActivityAtMs, deviceID, stationID, recordedByDeviceID
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        startedAtMs = try container.decode(Int64.self, forKey: .startedAtMs)
        endedAtMs = try container.decodeIfPresent(Int64.self, forKey: .endedAtMs)
        lastActivityAtMs = try container.decode(Int64.self, forKey: .lastActivityAtMs)
        deviceID = try container.decodeIfPresent(String.self, forKey: .deviceID)
            ?? container.decodeIfPresent(String.self, forKey: .recordedByDeviceID)
        stationID = try container.decodeIfPresent(String.self, forKey: .stationID)
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(startedAtMs, forKey: .startedAtMs)
        try container.encodeIfPresent(endedAtMs, forKey: .endedAtMs)
        try container.encode(lastActivityAtMs, forKey: .lastActivityAtMs)
        try container.encodeIfPresent(deviceID, forKey: .deviceID)
        try container.encodeIfPresent(deviceID, forKey: .recordedByDeviceID)
        try container.encodeIfPresent(stationID, forKey: .stationID)
    }
}

/// A station, kept once per folded name, so devices never make one each.
public struct CloudStationFields: Codable, Sendable, Hashable {
    public var name: String
    public var catalogID: String?
    public var artworkURL: String?
    public var firstSeenMs: Int64
    public var lastSeenMs: Int64

    public init(name: String, catalogID: String?, artworkURL: String?, firstSeenMs: Int64, lastSeenMs: Int64) {
        self.name = name
        self.catalogID = catalogID
        self.artworkURL = artworkURL
        self.firstSeenMs = firstSeenMs
        self.lastSeenMs = lastSeenMs
    }
}

/// A song forgotten, by its identity, and when: plays imported from before then stay out.
public struct CloudForgottenSongFields: Codable, Sendable, Hashable {
    public var songKey: String
    public var forgottenAtMs: Int64

    public init(songKey: String, forgottenAtMs: Int64) {
        self.songKey = songKey
        self.forgottenAtMs = forgottenAtMs
    }
}

/// A blocked artist or an excluded station: one name, kept or removed by itself, so two
/// devices changing the list at once both count.
public struct CloudNameFields: Codable, Sendable, Hashable {
    public var name: String

    public init(name: String) {
        self.name = name
    }
}

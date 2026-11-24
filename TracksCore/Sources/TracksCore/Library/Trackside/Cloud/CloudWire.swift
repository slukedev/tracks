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
    /// The server's change sequence for this item, for compare-and-set where a rule needs it.
    public var version: CloudCursor?
    public var fields: [String: JSONValue]
    public var clientModifiedAt: Date?
    public var deviceID: String?

    public init(id: String, version: CloudCursor? = nil, fields: [String: JSONValue], clientModifiedAt: Date? = nil, deviceID: String? = nil) {
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
    public var deletedAt: Date?
    public var deviceID: String?
    public var mergedInto: String?

    public init(id: String, deletedAt: Date? = nil, deviceID: String? = nil, mergedInto: String? = nil) {
        self.id = id
        self.deletedAt = deletedAt
        self.deviceID = deviceID
        self.mergedInto = mergedInto
    }

    enum CodingKeys: String, CodingKey {
        case id
        case deletedAt = "deleted_at"
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
    public var baseVersion: CloudCursor?
    public var fields: [String: JSONValue]?
    public var clientModifiedAt: Date
    /// This device's id. The server checks it's the one this device's key speaks for.
    public var deviceID: String
    public var deletedAt: Date?
    public var mergedInto: String?

    public init(id: String, baseVersion: CloudCursor? = nil, fields: [String: JSONValue]?, clientModifiedAt: Date, deviceID: String, deletedAt: Date? = nil, mergedInto: String? = nil) {
        self.id = id
        self.baseVersion = baseVersion
        self.fields = fields
        self.clientModifiedAt = clientModifiedAt
        self.deviceID = deviceID
        self.deletedAt = deletedAt
        self.mergedInto = mergedInto
    }

    public var isTombstone: Bool { deletedAt != nil }

    enum CodingKeys: String, CodingKey {
        case id, fields
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

public struct CloudPushResult: Codable, Sendable, Hashable {
    public var id: String
    public var status: CloudPushStatus
    public var version: CloudCursor?
    public var reason: String?
    public var current: CloudRecord?

    public init(id: String, status: CloudPushStatus, version: CloudCursor? = nil, reason: String? = nil, current: CloudRecord? = nil) {
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

    public init(value: JSONValue, modifiedAt: Date, deviceID: String?) {
        self.value = value
        self.modifiedAt = modifiedAt
        self.deviceID = deviceID
    }

    enum CodingKeys: String, CodingKey {
        case value
        case modifiedAt = "modified_at"
        case deviceID = "device_id"
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        value = try container.decodeIfPresent(JSONValue.self, forKey: .value) ?? .null
        modifiedAt = try container.decode(Date.self, forKey: .modifiedAt)
        deviceID = try container.decodeIfPresent(String.self, forKey: .deviceID)
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(value, forKey: .value)
        try container.encode(modifiedAt, forKey: .modifiedAt)
        try container.encodeIfPresent(deviceID, forKey: .deviceID)
    }
}

/// `PATCH /cloud/settings`, and its answer: the settings as they now stand.
public struct CloudSettingsPatch: Codable, Sendable, Hashable {
    public var settings: [String: CloudSetting]

    public init(settings: [String: CloudSetting]) {
        self.settings = settings
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
    public var id: String
    public var name: String?
    public var platform: String?
    public var predecessors: [String]?
    public var lastSeenAt: Date?

    enum CodingKeys: String, CodingKey {
        case id, name, platform, predecessors
        case lastSeenAt = "last_seen_at"
    }
}

public struct CloudDeviceList: Codable, Sendable, Hashable {
    public var devices: [CloudDevice]
}

// MARK: - Fields

/// A play's fields. Its core (when, how, from where, who recorded it) never changes; the
/// rest fill in later.
public struct CloudPlayFields: Codable, Sendable, Hashable {
    public var title: String
    public var artistName: String
    public var albumTitle: String?
    public var artworkURL: String?
    public var playedAtMs: Int64
    public var kind: String
    public var source: String?
    public var songKey: String?
    public var catalogID: String?
    public var stationID: String?
    public var sessionID: String?
    public var platform: String?
    public var recordedByDeviceID: String?
    public var scrobbledAtMs: Int64?
    public var addedToPlaylistAtMs: Int64?

    public init(
        title: String, artistName: String, albumTitle: String? = nil, artworkURL: String? = nil,
        playedAtMs: Int64, kind: String, source: String? = nil, songKey: String? = nil, catalogID: String? = nil,
        stationID: String? = nil, sessionID: String? = nil, platform: String? = nil, recordedByDeviceID: String? = nil,
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
        self.stationID = stationID
        self.sessionID = sessionID
        self.platform = platform
        self.recordedByDeviceID = recordedByDeviceID
        self.scrobbledAtMs = scrobbledAtMs
        self.addedToPlaylistAtMs = addedToPlaylistAtMs
    }
}

/// A stretch of listening to one station, which only the device that recorded it changes.
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

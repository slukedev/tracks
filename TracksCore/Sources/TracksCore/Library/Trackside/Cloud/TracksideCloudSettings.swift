import Foundation
import Synchronization
#if canImport(CoreFoundation)
import CoreFoundation
#endif

/// Settings as Trackside Cloud keeps them: `GET /cloud/settings` and `PATCH /cloud/settings`.
public protocol CloudSettingsAPI: Sendable {
    func settings(after cursor: CloudCursor?) async throws -> CloudSettingsPage
    /// - Returns: the settings as the server now holds them, which it decides by the same
    ///   rules as every device.
    func patch(_ settings: [String: CloudSetting]) async throws -> [String: CloudSetting]
}

/// Trackside Cloud in the shape ``SettingsSync`` already mirrors iCloud's key-value store
/// through, so settings sync by exactly the same rules whichever backend carries them.
///
/// Values read come from the last pull, kept on disk, so settings read the same before the
/// server answers. Values written wait in a queue until the next PATCH; a cleared setting
/// goes as an explicit null. When a pull brings a change, the same notification iCloud posts
/// is posted, with the keys that changed.
///
/// Hidden songs, blocked artists and excluded stations aren't settings here: they sync as
/// items of their own, so two devices changing the list at once both count.
public final class TracksideCloudSettings: KeyValueSyncStore, @unchecked Sendable {
    public static let shared = TracksideCloudSettings(file: TracksideCloudFolder.url.appending(path: "settings.json"))

    /// Kept as items instead (``CloudEntity/forgottenSongs`` and the rest).
    public static let itemKeys: Set<String> = ["forgottenSongs", "forgottenSongDates", "blockedArtists", "excludedStations"]

    struct Stored: Codable, Sendable, Equatable {
        var settings: [String: CloudSetting] = [:]
        var cursor: CloudCursor?
        var pending: [String: CloudSetting] = [:]
    }

    private let file: URL
    private let stored: Mutex<Stored>
    private let connection = Mutex<(api: any CloudSettingsAPI, deviceID: String)?>(nil)
    private let flushing = Mutex<Task<Void, Never>?>(nil)

    init(file: URL) {
        self.file = file
        let loaded = (try? Data(contentsOf: file)).flatMap { try? CloudJSON.decoder.decode(Stored.self, from: $0) } ?? Stored()
        stored = Mutex(loaded)
    }

    /// Starts talking to a server. Until then, writes wait.
    public func attach(_ api: any CloudSettingsAPI, deviceID: String) {
        connection.withLock { $0 = (api, deviceID) }
    }

    public func detach() {
        connection.withLock { $0 = nil }
    }

    /// Forgets the last pull, so the next one is a full snapshot: a move to another server.
    public func startOver() {
        stored.withLock { $0.cursor = nil; $0.settings = [:] }
        save()
    }

    // MARK: - KeyValueSyncStore

    public func object(forKey key: String) -> Any? {
        guard !Self.itemKeys.contains(key) else { return nil }
        let setting = stored.withLock { $0.pending[key] ?? $0.settings[key] }
        guard let setting else { return nil }
        var envelope: [String: Any] = [SettingsSync.modifiedAtField: setting.modifiedAt.timeIntervalSince1970]
        // A cleared setting has a time but no value, so it clears the setting where it's newer.
        if let value = Self.plist(from: setting.value) { envelope[SettingsSync.valueField] = value }
        return envelope
    }

    public func set(_ value: Any?, forKey key: String) {
        guard !Self.itemKeys.contains(key) else { return }
        let deviceID = connection.withLock { $0?.deviceID } ?? DeviceIdentity.current
        let envelope = value as? [String: Any]
        let modifiedAt = (envelope?[SettingsSync.modifiedAtField] as? Double).map(Date.init(timeIntervalSince1970:)) ?? Date()
        let json = envelope.flatMap { $0[SettingsSync.valueField] }.flatMap(Self.json(from:)) ?? .null
        stored.withLock { $0.pending[key] = CloudSetting(value: json, modifiedAt: modifiedAt, deviceID: deviceID) }
    }

    @discardableResult
    public func synchronize() -> Bool {
        save()
        flushSoon()
        return true
    }

    // MARK: - Talking to the server

    /// Sends what's waiting, then pulls what changed elsewhere.
    public func sync() async throws {
        try await flush()
        try await pull()
    }

    private func flushSoon() {
        flushing.withLock { task in
            guard task == nil else { return }
            task = Task {
                try? await self.flush()
                self.flushing.withLock { $0 = nil }
            }
        }
    }

    private func flush() async throws {
        guard let api = connection.withLock({ $0?.api }) else { return }
        let sending = stored.withLock { $0.pending }
        guard !sending.isEmpty else { return }
        let current = try await api.patch(sending)
        let changed = stored.withLock { stored -> [String] in
            for (key, sent) in sending where stored.pending[key] == sent {
                stored.pending[key] = nil
            }
            var changed: [String] = []
            for (key, setting) in current {
                // The server kept a newer value than the one sent: it comes back down.
                if let sent = sending[key], sent.value != setting.value { changed.append(key) }
                stored.settings[key] = setting
            }
            return changed
        }
        save()
        announce(changed)
    }

    private func pull() async throws {
        guard let api = connection.withLock({ $0?.api }) else { return }
        var changed: [String] = []
        while true {
            let cursor = stored.withLock { $0.cursor }
            let page: CloudSettingsPage
            do {
                page = try await api.settings(after: cursor)
            } catch CloudSyncError.syncReset {
                startOver()
                continue
            }
            stored.withLock { stored in
                for (key, setting) in page.settings where !Self.itemKeys.contains(key) {
                    if stored.settings[key] != setting { changed.append(key) }
                    stored.settings[key] = setting
                }
                if let next = page.cursor { stored.cursor = next }
            }
            save()
            guard page.hasMore, page.cursor != nil, page.cursor != cursor else { break }
        }
        announce(changed)
    }

    private func announce(_ keys: [String]) {
        guard !keys.isEmpty else { return }
        NotificationCenter.default.post(
            name: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
            object: self,
            userInfo: [
                NSUbiquitousKeyValueStoreChangeReasonKey: NSUbiquitousKeyValueStoreServerChange,
                NSUbiquitousKeyValueStoreChangedKeysKey: keys,
            ]
        )
    }

    private func save() {
        let snapshot = stored.withLock { $0 }
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try CloudJSON.encoder.encode(snapshot).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            // Held in memory; written again with the next change.
        }
    }

    // MARK: - Values

    /// A property-list value as JSON. Nil for one JSON can't carry, such as data.
    static func json(from value: Any) -> JSONValue? {
        switch value {
        case let number as NSNumber:
            #if canImport(CoreFoundation) && canImport(Darwin)
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return .bool(number.boolValue) }
            if CFNumberIsFloatType(number) { return .double(number.doubleValue) }
            #endif
            return .int(number.int64Value)
        case let text as String:
            return .string(text)
        case let date as Date:
            return .string(CloudJSON.string(from: date))
        case let array as [Any]:
            let values = array.compactMap(json(from:))
            return values.count == array.count ? .array(values) : nil
        case let dictionary as [String: Any]:
            var object: [String: JSONValue] = [:]
            for (key, value) in dictionary {
                guard let converted = json(from: value) else { return nil }
                object[key] = converted
            }
            return .object(object)
        default:
            return nil
        }
    }

    /// JSON as a property-list value. Nil for null, which is a cleared setting.
    static func plist(from value: JSONValue) -> Any? {
        switch value {
        case .null: nil
        case .bool(let value): value
        case .int(let value): Int(value)
        case .double(let value): value
        case .string(let value): value
        case .array(let values): values.compactMap(plist(from:))
        case .object(let values): values.compactMapValues(plist(from:))
        }
    }
}

/// Where Trackside Cloud's state is kept on this device: cursors, what the server holds,
/// and the settings last pulled.
public enum TracksideCloudFolder {
    public static var url: URL {
        URL.applicationSupportDirectory.appending(path: "Tracks/Trackside Cloud", directoryHint: .isDirectory)
    }

    /// Forgets everything kept for a server, so the next sync starts over: a move to another
    /// server, or back to iCloud.
    public static func clear() {
        try? FileManager.default.removeItem(at: url)
    }
}

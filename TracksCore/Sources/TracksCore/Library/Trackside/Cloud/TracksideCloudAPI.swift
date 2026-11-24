import Foundation

/// Trackside Cloud on one paired server, through its client: the device key in the header,
/// at whichever of its addresses has proven it is the server.
public struct TracksideCloudAPI: CloudSyncAPI, CloudSettingsAPI {
    public let client: TracksideClient

    public init(client: TracksideClient) {
        self.client = client
    }

    public func pull(_ entity: CloudEntity, cursor: CloudCursor?, limit: Int) async throws -> CloudPage {
        var query = ["limit": String(limit)]
        query["cursor"] = cursor?.rawValue
        return try await translating(cursor: cursor) { try await client.get(entity.path, query) }
    }

    public func push(_ entity: CloudEntity, items: [CloudPushItem]) async throws -> [CloudPushResult] {
        guard !items.isEmpty else { return [] }
        let response: CloudPushResponse = try await translating {
            try await client.send(entity.batchPath, method: "POST", body: CloudPushRequest(items: items))
        }
        return response.results
    }

    public func settings(after cursor: CloudCursor?) async throws -> CloudSettingsPage {
        var query: [String: String] = [:]
        query["cursor"] = cursor?.rawValue
        return try await translating(cursor: cursor) { try await client.get(CloudEntity.settings.path, query) }
    }

    public func patch(_ settings: [String: CloudSetting]) async throws -> [CloudSettingResult] {
        let answer: CloudSettingResults = try await translating {
            try await client.send(CloudEntity.settings.path, method: "PATCH", body: CloudSettingsPatch(settings: settings))
        }
        return answer.results
    }

    /// Registers this device's id with its key, and the devices it was restored or migrated
    /// from. Predecessors the server doesn't know are dropped rather than holding up sync.
    @discardableResult
    public func register(deviceID: String, predecessors: [String], name: String?, platform: String?) async throws -> CloudDevice {
        let path = "cloud/devices/\(deviceID)"
        do {
            return try await translating {
                try await client.send(path, method: "PUT", body: CloudDeviceRegistration(predecessors: predecessors, name: name, platform: platform))
            }
        } catch TracksideError.server(400, _, _) where !predecessors.isEmpty {
            return try await translating {
                try await client.send(path, method: "PUT", body: CloudDeviceRegistration(predecessors: [], name: name, platform: platform))
            }
        }
    }

    public func devices() async throws -> [CloudDevice] {
        let list: CloudDeviceList = try await translating { try await client.get("cloud/devices") }
        return list.devices
    }

    public func usage() async throws -> CloudUsage {
        try await translating { try await client.get("cloud/usage") }
    }

    /// The server's refusals that mean something to the sync engine, as its errors. A cursor
    /// the server says it never made, such as one from before cursors carried an epoch,
    /// starts over like one from before a restore.
    private func translating<Value>(cursor: CloudCursor? = nil, _ work: () async throws -> Value) async throws -> Value {
        do {
            return try await work()
        } catch TracksideError.server(400, "bad_request", _) where cursor != nil {
            throw CloudSyncError.syncReset
        } catch TracksideError.syncReset {
            throw CloudSyncError.syncReset
        } catch TracksideError.server(_, "device_mismatch", _) {
            throw CloudSyncError.deviceMismatch
        }
    }
}

extension TracksideCapabilities {
    /// Whether the server offers Trackside Cloud.
    public var offersCloud: Bool { features?.cloud == true || cloud != nil }
}

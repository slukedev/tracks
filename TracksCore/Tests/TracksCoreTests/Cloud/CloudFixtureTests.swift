import Foundation
import Testing
@testable import TracksCore

/// Trackside Cloud's wire against the server's own fixtures: every `cloud-*.json` reads, and
/// what Tracks sends comes out exactly as the server's examples of it.
@Suite("Trackside Cloud's fixtures", .enabled(if: TracksideFixtures.directory != nil))
struct CloudFixtureTests {
    static func decode<Value: Decodable>(_ type: Value.Type, _ name: String) throws -> Value {
        try CloudJSON.decoder.decode(type, from: TracksideFixtures.data(name))
    }

    static func json(_ name: String) throws -> JSONValue {
        try CloudJSON.decoder.decode(JSONValue.self, from: TracksideFixtures.data(name))
    }

    static func json(of value: some Encodable) throws -> JSONValue {
        try CloudJSON.decoder.decode(JSONValue.self, from: CloudJSON.encoder.encode(value))
    }

    static func date(_ text: String) throws -> Date {
        try #require(CloudJSON.date(from: text))
    }

    static var readers: [String: (Data) throws -> Void] {[
        "cloud-device-registration.json": { _ = try CloudJSON.decoder.decode(CloudDeviceRegistration.self, from: $0) },
        "cloud-devices.json": { _ = try CloudJSON.decoder.decode(CloudDeviceList.self, from: $0) },
        "cloud-event.json": { _ = try CloudJSON.decoder.decode(CloudChangeSignal.self, from: $0) },
        "cloud-playlist-entries-page.json": { _ = try CloudJSON.decoder.decode(CloudPage.self, from: $0) },
        "cloud-plays-page.json": { _ = try CloudJSON.decoder.decode(CloudPage.self, from: $0) },
        "cloud-push-plays.json": { _ = try CloudJSON.decoder.decode(CloudPushRequest.self, from: $0) },
        "cloud-push-results.json": { _ = try CloudJSON.decoder.decode(CloudPushResponse.self, from: $0) },
        "cloud-setting-results.json": { _ = try CloudJSON.decoder.decode(CloudSettingResults.self, from: $0) },
        "cloud-settings-patch.json": { _ = try CloudJSON.decoder.decode(CloudSettingsPatch.self, from: $0) },
        "cloud-settings.json": { _ = try CloudJSON.decoder.decode(CloudSettingsPage.self, from: $0) },
        "cloud-usage.json": { _ = try CloudJSON.decoder.decode(CloudUsage.self, from: $0) },
    ]}

    /// The `cloud` event's data.
    struct CloudChangeSignal: Decodable {
        let entity: String
    }

    @Test("every cloud fixture has a reader, and each decodes")
    func everyFixture() throws {
        let names = TracksideFixtures.files.map(\.lastPathComponent).filter { $0.hasPrefix("cloud-") }
        #expect(Set(names) == Set(Self.readers.keys))
        for name in names {
            do {
                try Self.readers[name]?(TracksideFixtures.data(name))
            } catch {
                Issue.record("\(name) doesn't decode: \(error)")
            }
        }
    }

    @Test("a page of plays reads, versions as numbers and the cursor exactly as sent")
    func playsPage() throws {
        let page = try Self.decode(CloudPage.self, "cloud-plays-page.json")
        #expect(page.cursor?.rawValue == "3f9a0c1d7e2b4a65.918")
        #expect(!page.hasMore)
        #expect(page.upserts.map(\.version) == [917, 640])
        #expect(page.upserts.map(\.id) == ["6A8C0E2F-4B6D-4F8A-9C1E-3B5D7F9A1C3E", "lastfm:1700000000:5f2b9c1d"])
        #expect(page.upserts.first?.deviceID == "6F1D9C2A-3B4E-4F5A-8B7C-1D2E3F4A5B6C")
        #expect(page.upserts.first?.clientModifiedAt == (try Self.date("2026-11-14T08:35:00.000Z")))
        #expect(page.deletes == [CloudDeletion(
            id: "1F3B5D7F-9A1C-4E3B-8D5F-7A9C1E3B5D7F",
            version: 918,
            deletedAt: try Self.date("2026-11-14T09:29:30.000Z"),
            clientModifiedAt: try Self.date("2026-11-14T09:29:30.000Z"),
            deviceID: "0C7B5E3A-9D1F-4E2B-8A6C-5F4E3D2C1B0A",
            mergedInto: "6A8C0E2F-4B6D-4F8A-9C1E-3B5D7F9A1C3E"
        )])
    }

    @Test("a play's fields read under their own names, and write back the same")
    func playFields() throws {
        let page = try Self.decode(CloudPage.self, "cloud-plays-page.json")
        let halo = try CloudJSON.value(CloudPlayFields.self, from: try #require(page.upserts.first).fields)
        #expect(halo.scrobbledAtMs == 1_794_645_300_000)
        #expect(halo.playedMs == 254_000)
        #expect(halo.durationMs == 261_640)
        #expect(halo.serverSongID == "s7m2k4q9x1v8b3n6c0de")
        #expect(halo.songKey == "halo\u{1F}beyonce")
        for record in page.upserts {
            let fields = try CloudJSON.value(CloudPlayFields.self, from: record.fields)
            #expect(try CloudJSON.fields(fields) == record.fields, "\(record.id)")
        }
    }

    @Test("a push of plays comes out exactly as the server's example")
    func pushPlays() throws {
        let page = try Self.decode(CloudPage.self, "cloud-plays-page.json")
        let halo = try CloudJSON.value(CloudPlayFields.self, from: page.upserts[0].fields)
        let queen = try CloudJSON.value(CloudPlayFields.self, from: page.upserts[1].fields)
        let request = CloudPushRequest(items: [
            CloudPushItem(
                id: "6A8C0E2F-4B6D-4F8A-9C1E-3B5D7F9A1C3E", fields: try CloudJSON.fields(halo),
                clientModifiedAt: try Self.date("2026-11-14T08:35:00.000Z"), deviceID: "6F1D9C2A-3B4E-4F5A-8B7C-1D2E3F4A5B6C"
            ),
            CloudPushItem(
                id: "lastfm:1700000000:5f2b9c1d", fields: try CloudJSON.fields(queen),
                clientModifiedAt: try Self.date("2026-11-14T09:29:00.000Z"), deviceID: "0C7B5E3A-9D1F-4E2B-8A6C-5F4E3D2C1B0A"
            ),
            CloudPushItem(
                id: "1F3B5D7F-9A1C-4E3B-8D5F-7A9C1E3B5D7F", baseVersion: 902, fields: nil,
                clientModifiedAt: try Self.date("2026-11-14T09:29:30.000Z"), deviceID: "0C7B5E3A-9D1F-4E2B-8A6C-5F4E3D2C1B0A",
                deletedAt: try Self.date("2026-11-14T09:29:30.000Z"), mergedInto: "6A8C0E2F-4B6D-4F8A-9C1E-3B5D7F9A1C3E"
            ),
        ])
        #expect(try Self.json(of: request) == (try Self.json("cloud-push-plays.json")))
        let decoded = try Self.decode(CloudPushRequest.self, "cloud-push-plays.json")
        #expect(decoded == request)
        #expect(decoded.items.map(\.isTombstone) == [false, false, true])
    }

    @Test("push results read every status, and a conflict's current is the record that stands")
    func pushResults() throws {
        let results = try Self.decode(CloudPushResponse.self, "cloud-push-results.json").results
        #expect(results.map(\.status) == [.accepted, .duplicate, .conflict, .rejected])
        #expect(results.map(\.version) == [918, 640, nil, nil])
        let current = try #require(results[2].current?.record)
        #expect(current.version == 911)
        #expect(current.fields["title"] == .string("Halo"))
        #expect(results[3].reason == "deleted")
        #expect(results[3].current == nil)
    }

    @Test("a conflict over something deleted reads as its tombstone")
    func conflictWithTombstone() throws {
        let json = #"{"results": [{"id": "p1", "status": "conflict", "current": {"id": "p1", "version": 12, "device_id": "D2", "client_modified_at": "2026-11-14T09:29:30.000Z", "deleted_at": "2026-11-14T09:29:30.000Z"}}]}"#
        let result = try #require(try CloudJSON.decoder.decode(CloudPushResponse.self, from: Data(json.utf8)).results.first)
        #expect(result.current?.deletion?.version == 12)
        #expect(result.current?.record == nil)
    }

    @Test("a settings patch comes out exactly as the server's example, without versions")
    func settingsPatch() throws {
        let at = try Self.date("2026-11-14T09:29:50.000Z")
        let device = "6F1D9C2A-3B4E-4F5A-8B7C-1D2E3F4A5B6C"
        let sealed: JSONValue = .object(["$encrypted": .object([
            "alg": .string("chachapoly"),
            "ct": .string("kJ4w0bQ2Xx9lY8cC1dRk3mXz5Q7pN6vT2sH8gF4aE0iL9oK3uJ1yW6rD5bM7nC2Z"),
            "kid": .string("icloud-keychain-1"),
            "nonce": .string("3q2+7wABAgMEBQYH"),
            "v": .int(1),
        ])])
        let patch = CloudSettingsPatch(settings: [
            "nearbyKey": CloudSetting(value: sealed, modifiedAt: at, deviceID: device, version: 412),
            "playlistID": CloudSetting(value: .null, modifiedAt: at, deviceID: device),
            "playlistName": CloudSetting(value: .string("Tracks"), modifiedAt: at, deviceID: device),
        ])
        #expect(try Self.json(of: patch) == (try Self.json("cloud-settings-patch.json")))
        #expect(try Self.decode(CloudSettingsPatch.self, "cloud-settings-patch.json") == patch)
    }

    @Test("setting results carry the value that stands, and a refusal carries none")
    func settingResults() throws {
        let results = try Self.decode(CloudSettingResults.self, "cloud-setting-results.json").results
        #expect(results.map(\.key) == ["playlistName", "autoAddToPlaylist", "forgottenSongs", "nearbyKey"])
        #expect(results.map(\.status) == [.accepted, .conflict, .accepted, .rejected])
        #expect(results[0].current?.value == .string("Tracks"))
        #expect(results[0].current?.version == 919)
        #expect(results[1].current?.value == .bool(true))
        #expect(results[2].current?.value == .array([.string("halo\u{1F}beyonce"), .string("lion\u{1F}elevation"), .string("tides\u{1F}umbra")]))
        #expect(results[3].reason == "encryption_required")
        #expect(results[3].current == nil)
    }

    @Test("settings read with clears as null, seeds at 1970 and the cursor as sent")
    func settings() throws {
        let page = try Self.decode(CloudSettingsPage.self, "cloud-settings.json")
        #expect(page.cursor?.rawValue == "3f9a0c1d7e2b4a65.918")
        #expect(page.settings["playlistID"]?.value == .null)
        #expect(page.settings["playlistID"]?.version == 310)
        #expect(page.settings["autoAddToPlaylist"]?.modifiedAt == Date(timeIntervalSince1970: 0))
        #expect(page.settings["playlistName"]?.deviceID == "0C7B5E3A-9D1F-4E2B-8A6C-5F4E3D2C1B0A")
        if case .object(let sealed)? = page.settings["nearbyKey"]?.value {
            #expect(sealed.keys.sorted() == ["$encrypted"])
        } else {
            Issue.record("nearbyKey isn't an envelope")
        }
    }

    @Test("a page of playlist entries keeps its nested track as sent")
    func playlistEntries() throws {
        let page = try Self.decode(CloudPage.self, "cloud-playlist-entries-page.json")
        #expect(page.hasMore)
        #expect(page.deletes.isEmpty)
        let entry = try #require(page.upserts.first)
        #expect(entry.version == 1204)
        #expect(entry.fields["order"] == .string("a0V"))
        guard case .object(let track)? = entry.fields["track"], case .object(let ids)? = track["ids"] else {
            Issue.record("track isn't an object")
            return
        }
        #expect(ids["spotify"] == .string("4JehYebiI9JE8sR8MisGVb"))
    }

    @Test("devices read by their app device id, and a registration matches the server's example")
    func devices() throws {
        let devices = try Self.decode(CloudDeviceList.self, "cloud-devices.json").devices
        #expect(devices.map(\.id) == ["6F1D9C2A-3B4E-4F5A-8B7C-1D2E3F4A5B6C", "0C7B5E3A-9D1F-4E2B-8A6C-5F4E3D2C1B0A"])
        #expect(devices.map(\.current) == [true, false])
        #expect(devices[0].predecessors == ["E2D4C6B8-1A3F-4C5E-9B7D-0F2E4D6C8A1B"])
        #expect(devices[0].serverDeviceID == "dchjkb9k3cnapjw0kmhq6")
        #expect(devices[1].serverDeviceID == nil)
        #expect(devices[0].registeredAt == (try Self.date("2026-11-11T09:30:00.000Z")))
        let registration = CloudDeviceRegistration(predecessors: ["E2D4C6B8-1A3F-4C5E-9B7D-0F2E4D6C8A1B"], name: "Luke's iPhone", platform: "ios")
        #expect(try Self.json(of: registration) == (try Self.json("cloud-device-registration.json")))
    }

    @Test("usage reads in total, against the quota and by kind")
    func usage() throws {
        let usage = try Self.decode(CloudUsage.self, "cloud-usage.json")
        #expect(usage.items == 152_340)
        #expect(usage.bytes == 98_312_448)
        #expect(usage.quota == CloudUsage.Amount(items: 2_000_000, bytes: 1_073_741_824))
        #expect(usage.entities["plays"] == CloudUsage.Amount(items: 151_900, bytes: 97_800_000))
    }

    @Test("the change signal names a kind by its path")
    func changeSignal() throws {
        let signal = try Self.decode(CloudChangeSignal.self, "cloud-event.json")
        #expect(CloudEntity(rawValue: signal.entity) == .plays)
    }

    @Test("a session tells the server who recorded it")
    func sessionOwner() throws {
        let fields = try CloudJSON.fields(CloudSessionFields(startedAtMs: 1, endedAtMs: nil, lastActivityAtMs: 2, deviceID: "D1", stationID: "s"))
        #expect(fields["recordedByDeviceID"] == .string("D1"))
        #expect(fields["deviceID"] == .string("D1"))
        let fromElsewhere = try CloudJSON.value(CloudSessionFields.self, from: ["startedAtMs": .int(1), "lastActivityAtMs": .int(2), "recordedByDeviceID": .string("D2")])
        #expect(fromElsewhere.deviceID == "D2")
    }
}

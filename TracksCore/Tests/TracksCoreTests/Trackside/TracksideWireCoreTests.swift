import Foundation
import Testing
@testable import TracksCore

@Suite("Trackside's addresses, refusals and capabilities, as the server sends them", .enabled(if: TracksideFixtures.directory != nil))
struct TracksideWireCoreTests {
    @Test("the address list reads with its version, which is also its ETag")
    func serverURLs() throws {
        let list = try TracksideJSON.decoder.decode(TracksideServerURLs.self, from: TracksideFixtures.data("server-urls.json"))
        #expect(list.serverID == "5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b")
        #expect(list.version == "a746df40293cc8d2b0f1b1ea782c9d87")
        #expect(list.etag == "\"a746df40293cc8d2b0f1b1ea782c9d87\"")
        #expect(list.urls.map(\.kind) == [.lan, .lan, .public])
        #expect(list.urls.map(\.tls) == [false, false, true])
        #expect(list.urls.last?.url == URL(string: "https://music.example.com"))
    }

    @Test("the event names the new list by the same ETag the list is sent with")
    func serverURLsEvent() throws {
        let event = try TracksideJSON.decoder.decode(TracksideServerURLsEvent.self, from: TracksideFixtures.data("server-urls-event.json"))
        let list = try TracksideJSON.decoder.decode(TracksideServerURLs.self, from: TracksideFixtures.data("server-urls.json"))
        #expect(event.version == list.version)
        #expect(event.etag == list.etag)
        #expect(TracksideServerURLsEvent(version: nil).etag == nil)
    }

    @Test("https_required names the server's https addresses, as addresses")
    func httpsRequired() throws {
        let body = try TracksideFixtures.data("error-https-required.json")
        let named = try #require(TracksideErrorEnvelope.httpsRequired(status: 403, body: body))
        #expect(named.map(\.url.absoluteString) == ["https://music.example.com", "https://home.tail4a2c.ts.net"])
        #expect(named.map(\.kind) == [.public, .tailnet])
        #expect(named.allSatisfy { $0.tls == true && $0.fingerprint == nil })
        #expect(TracksideErrorEnvelope.httpsRequired(status: 401, body: body) == nil)
        let envelope = try TracksideJSON.decoder.decode(TracksideErrorEnvelope.self, from: body)
        #expect(envelope.error.requestID == "7a1e4c9b2d8f4a6c9e3b5d7f1a2c4e6b")
    }

    @Test("https_required without addresses, or with ones in a shape it doesn't know, still reads as https_required")
    func httpsRequiredWithoutAddresses() throws {
        let bare = Data(#"{"error": {"code": "https_required", "message": "https only"}}"#.utf8)
        #expect(TracksideErrorEnvelope.httpsRequired(status: 403, body: bare) == [])
        let odd = Data(#"{"error": {"code": "https_required", "details": {"https_urls": ["https://a.example.com", {"url": "https://b.example.com", "kind": "public", "tls": true}, {"url": "http://c.example.com", "kind": "lan"}]}}}"#.utf8)
        #expect(TracksideErrorEnvelope.httpsRequired(status: 403, body: odd)?.map(\.url.absoluteString) == ["https://b.example.com"])
        let forbidden = Data(#"{"error": {"code": "forbidden"}}"#.utf8)
        #expect(TracksideErrorEnvelope.httpsRequired(status: 403, body: forbidden) == nil)
    }

    @Test("capabilities say Trackside Cloud is offered, with its epoch and limits")
    func capabilities() throws {
        let capabilities = try TracksideJSON.decoder.decode(TracksideCapabilities.self, from: TracksideFixtures.data("capabilities.json"))
        #expect(capabilities.isTrackside)
        #expect(capabilities.features?.cloud == true)
        let cloud = try #require(capabilities.cloud)
        #expect(cloud.epoch == "3f9a0c1d7e2b4a65")
        #expect(cloud.version == 1)
        #expect(cloud.maxBatch == 1_000)
        #expect(cloud.maxPage == 2_000)
        #expect(Set(cloud.entities ?? []) == Set(CloudEntity.allCases.map(\.rawValue)))
    }

    @Test("signing in introduces this device by the id Trackside Cloud knows it by")
    func deviceID() throws {
        let device = TracksideDeviceInfo(clientDeviceID: "pairing-only", name: "Luke's iPhone", platform: "ios").speaking(for: "6F1D9C2A-3B4E-4F5A-8B7C-1D2E3F4A5B6C")
        let body = try JSONSerialization.jsonObject(with: TracksideJSON.encoder.encode(device)) as? [String: Any]
        #expect(body?["client_device_id"] as? String == "6F1D9C2A-3B4E-4F5A-8B7C-1D2E3F4A5B6C")
        #expect(body?["name"] as? String == "Luke's iPhone")
    }
}

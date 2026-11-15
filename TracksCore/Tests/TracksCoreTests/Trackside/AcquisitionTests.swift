import Testing
import Foundation
@testable import TracksCore

@Suite("Getting songs the server doesn't have")
struct AcquisitionTests {
    typealias T = TracksideTesting

    @Test("without the feature in /me, it's unavailable and nothing is asked of the server")
    func unavailable() async throws {
        let stub = StubTracksideTransport(body: APIExamples.acquisitionSearch)
        let me = try T.decode(TracksideMe.self, APIExamples.meWithoutAcquisition)
        let acquisitions = TracksideAcquisitions(client: T.client(stub), me: me)
        #expect(!acquisitions.isAvailable)
        #expect(!acquisitions.offersInstantPlay)
        await #expect(throws: TracksideError.featureUnavailable) { try await acquisitions.search("beyonce halo") }
        await #expect(throws: TracksideError.featureUnavailable) { try await acquisitions.request(TracksideAcquisitionRequest(title: "Halo", artist: "Beyoncé")) }
        await #expect(throws: TracksideError.featureUnavailable) { try await acquisitions.status(of: "q8w1") }
        await #expect(throws: TracksideError.featureUnavailable) { try await acquisitions.list(finished: false) }
        await #expect(throws: TracksideError.featureUnavailable) { try await acquisitions.cancel("q8w1") }
        #expect(stub.requests.isEmpty)
        #expect(TracksideAcquisitions(client: T.client(stub), features: nil).isAvailable == false)
    }

    @Test("with it, a search finds candidates and what's already in the library")
    func search() async throws {
        let stub = StubTracksideTransport(body: APIExamples.acquisitionSearch)
        let acquisitions = TracksideAcquisitions(client: T.client(stub), me: try T.decode(TracksideMe.self, APIExamples.me))
        #expect(acquisitions.isAvailable)
        #expect(acquisitions.offersInstantPlay)
        let found = try await acquisitions.search(title: "Halo", artist: "Beyoncé", duration: 261.64)
        #expect(found.candidates.map(\.token) == ["x3n8b2v6c1m9q4w7e0r5t8y2"])
        let request = try #require(stub.requests.first)
        #expect(request.url?.path() == "/api/v1/acquisitions/search")
        let body = try JSONSerialization.jsonObject(with: try #require(request.httpBody)) as? [String: Any]
        #expect(body?["duration_ms"] as? Int == 261_640)
        #expect(body?["query"] == nil)
    }

    @Test("a request carries a key, so a retry of it is only asked once")
    func idempotent() async throws {
        let stub = StubTracksideTransport(status: 202, body: APIExamples.acquisition)
        let acquisitions = TracksideAcquisitions(client: T.client(stub), me: try T.decode(TracksideMe.self, APIExamples.me))
        let key = UUID()
        let request = TracksideAcquisitionRequest(title: "Halo", artist: "Beyoncé", candidate: "x3n8b2v6c1m9q4w7e0r5t8y2")
        _ = try await acquisitions.request(request, key: key)
        _ = try await acquisitions.request(request, key: key)
        #expect(stub.requests.map { $0.value(forHTTPHeaderField: "Idempotency-Key") } == [key.uuidString, key.uuidString])
    }

    @Test("an instant stream plays from the server with the media token")
    func instantStream() throws {
        var acquisition = try T.decode(TracksideAcquisition.self, APIExamples.acquisition)
        let acquisitions = TracksideAcquisitions(client: T.client(StubTracksideTransport(body: "{}")), me: try T.decode(TracksideMe.self, APIExamples.me))
        #expect(acquisitions.streamURL(for: acquisition) == nil)
        acquisition.stream = .init(hls: "/api/v1/acquisitions/q8w1/stream.m3u8", progressive: "/api/v1/acquisitions/q8w1/stream")
        #expect(acquisitions.streamURL(for: acquisition)?.absoluteString == "http://192.168.1.20:4747/api/v1/acquisitions/q8w1/stream.m3u8?mt=eyJ1Ijoi")
    }

    @Test("each server state shows as one of the few steps a person sees", arguments: [
        ("queued", AcquisitionPhase.waiting),
        ("searching", .searching),
        ("verifying", .adding),
        ("importing", .adding),
        ("done", .added),
        ("cancelled", .cancelled),
        ("held_for_review", .waiting),
    ])
    func phases(state: String, phase: AcquisitionPhase) throws {
        let json = #"{"id": "q1", "state": "\#(state)", "title": "Halo", "artist": "Beyoncé"}"#
        #expect(try T.decode(TracksideAcquisition.self, json).phase == phase)
    }

    @Test("a failure says why, in terms Tracks can put into words")
    func failure() throws {
        let failed = try T.decode(TracksideAcquisition.self, #"{"id": "q1", "state": "failed", "title": "Halo", "artist": "Beyoncé", "failure": "no_match"}"#)
        #expect(failed.phase == .couldNotGet(.noMatch))
        #expect(failed.isFinished)
        let odd = try T.decode(TracksideAcquisition.self, #"{"id": "q1", "state": "failed", "title": "Halo", "artist": "Beyoncé", "failure": "cosmic_rays"}"#)
        #expect(odd.phase == .couldNotGet(.unknown))
    }

    @Test("an event overtaken by a later answer doesn't put a request back a step")
    func statuses() throws {
        var statuses = AcquisitionStatuses()
        let later = try T.decode(TracksideAcquisition.self, #"{"id": "q1", "state": "done", "title": "Halo", "artist": "Beyoncé", "song_id": "s7m2", "updated_at": "2026-11-14T09:31:00.000Z"}"#)
        let earlier = try T.decode(TracksideAcquisition.self, #"{"id": "q1", "state": "downloading", "title": "Halo", "artist": "Beyoncé", "updated_at": "2026-11-14T09:30:07.000Z"}"#)
        statuses.update(later)
        statuses.update(earlier)
        #expect(statuses["q1"]?.state == .done)
        #expect(statuses["q1"]?.songID == "s7m2")
    }
}

import Testing
import Foundation
@testable import TracksCore

@Suite("Play From's music services")
struct ServicePlayFromTests {
    typealias T = TracksideTesting

    // MARK: - The order

    @Test("a service's place is stored as Android stores it, and Apple Music stays its own source")
    func storedPlaces() {
        #expect(PlayFromPlace(rawValue: "service:spotify") == .service("spotify"))
        #expect(PlayFromPlace.service("spotify").rawValue == "service:spotify")
        #expect(PlayFromPlace(rawValue: "service:apple_music") == .source(.appleMusic))
        #expect(PlayFromPlace(rawValue: "service:") == .source(.unknown("service:")))
        let order = PlayFromOrder([.service("spotify"), .source(.device), .downloads])
        #expect(PlayFromOrder(rawValue: order.rawValue) == order)
    }

    @Test("a new order puts the services after your own sources")
    func servicesGoAfterYours() {
        #expect(PlayFromOrder.standard.arranged([.service("spotify"), .source(.server("a")), .source(.device)]) == [
            .source(.device), .source(.server("a")), .service("spotify"),
        ])
    }

    @Test("a service first means looking there before your copies; yours first plays yours")
    func servicesFirst() {
        let spotifyFirst = PlayFromOrder([.service("spotify"), .source(.server("a")), .service("tidal"), .source(.device)])
        #expect(spotifyFirst.servicesFirst(["tidal", "spotify"], before: [.source(.device)]) == ["spotify", "tidal"])
        #expect(spotifyFirst.servicesFirst(["tidal", "spotify"], before: [.source(.device), .source(.server("a"))]) == ["spotify"])
        // Nothing of yours plays: every service, in order.
        #expect(spotifyFirst.servicesFirst(["tidal", "spotify"], before: []) == ["spotify", "tidal"])
        #expect(PlayFromOrder.standard.servicesFirst(["spotify"], before: [.source(.device)]).isEmpty)
        #expect(spotifyFirst.servicesFirst([], before: [.source(.device)]).isEmpty)
    }

    // MARK: - Finding songs

    @Test("a service's track is the song by the server's key, and only where it plays")
    func matching() {
        let wanted = WantedSong(title: "Halo", artist: "Beyoncé")
        #expect(wanted.isMatched(by: TracksideCatalogTrack(ref: "mc1.a", serviceID: "b1", title: "Halo", artist: "Beyoncé"), on: "b1"))
        #expect(wanted.isMatched(by: TracksideCatalogTrack(ref: "mc1.a", serviceID: "b1", title: "x", songKey: wanted.identity), on: "b1"))
        #expect(!wanted.isMatched(by: TracksideCatalogTrack(ref: "mc1.a", serviceID: "b2", title: "Halo", artist: "Beyoncé"), on: "b1"))
        #expect(!wanted.isMatched(by: TracksideCatalogTrack(ref: "mc1.a", serviceID: "b1", title: "Halo", artist: "Beyoncé", playable: false), on: "b1"))
        #expect(!wanted.isMatched(by: TracksideCatalogTrack(ref: "mc1.a", serviceID: "b1", title: "Halo (Live)", artist: "Beyoncé"), on: "b1"))
    }

    @Test("a batch match reads each song's answer, and a state this build doesn't know")
    func matchWire() throws {
        let answer = try T.decode(TracksideCatalogMatch.self, #"""
        {"complete": false, "retry_after_s": 20, "results": [
          {"id": "0", "matches": [{"service_id": "B1", "state": "found", "track": {"kind": "track", "ref": "mc1.a", "service_id": "b1", "title": "Halo", "artist": "Beyoncé"}}]},
          {"id": "1", "matches": [{"service_id": "b1", "state": "none"}]},
          {"id": "2", "matches": [{"service_id": "b1", "state": "deferred"}, {"service_id": "b2", "state": "brand_new"}]}
        ]}
        """#)
        #expect(!answer.isComplete)
        #expect(answer.retryAfter == 20)
        #expect(answer.results.map(\.id) == ["0", "1", "2"])
        #expect(answer.results[0].matches.first?.serviceID == "b1")
        #expect(answer.results[0].matches.first?.track?.ref == "mc1.a")
        #expect(answer.results[1].matches.first?.state == .notFound)
        #expect(answer.results[2].matches.map(\.state) == [.deferred, .unknown])

        let request = TracksideCatalogMatchRequest(items: [.init(id: "0", title: "Halo", artist: "Beyoncé", durationMS: 261_640)], services: ["b1"])
        let body = try #require(JSONSerialization.jsonObject(with: TracksideJSON.encoder.encode(request)) as? [String: Any])
        let item = try #require((body["items"] as? [[String: Any]])?.first)
        #expect(item["duration_ms"] as? Int == 261_640)
        #expect(item["album"] == nil)
        #expect(body["services"] as? [String] == ["b1"])
    }

    @Test("found songs are kept a week, missing ones a day, and read back from the file")
    func kept() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: "matches-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: file) }
        let clock = LookupClock(Date(timeIntervalSince1970: 1_700_000_000))
        let halo = WantedSong(title: "Halo", artist: "Beyoncé")
        let gone = WantedSong(title: "Gone", artist: "Nobody")
        let matches = ServiceMatches(file: file, now: { clock.now })
        await matches.keep([(wanted: halo, track: TracksideCatalogTrack(ref: "mc1.a", serviceID: "b1", title: "Halo")), (wanted: gone, track: nil)], serverID: "home", serviceID: "b1")

        clock.advance(by: 2 * 24 * 60 * 60)
        let reread = ServiceMatches(file: file, now: { clock.now })
        #expect(await reread.known(halo, serverID: "home", serviceID: "b1")?.track?.ref == "mc1.a")
        #expect(await reread.known(gone, serverID: "home", serviceID: "b1") == nil)
        #expect(await reread.known(halo, serverID: "away", serviceID: "b1") == nil)
        clock.advance(by: 6 * 24 * 60 * 60)
        #expect(await reread.known(halo, serverID: "home", serviceID: "b1") == nil)
    }

    @Test("without batch matching: the first song first, the rest only when it's found, kept answers not asked again")
    func searches() async throws {
        let fake = LookupCatalog()
        fake.tracks = [
            "Halo Beyoncé": TracksideCatalogTrack(ref: "mc1.halo", serviceID: "b1", title: "Halo", artist: "Beyoncé"),
            "Run the World Beyoncé": TracksideCatalogTrack(ref: "mc1.run", serviceID: "b1", title: "Run the World", artist: "Beyoncé"),
        ]
        let matches = ServiceMatches(file: nil)
        let songs = [WantedSong(title: "Halo", artist: "Beyoncé"), WantedSong(title: "Run the World", artist: "Beyoncé"), WantedSong(title: "Unknown", artist: "Nobody"), WantedSong(title: "Halo", artist: "Beyoncé")]
        let found = await ServiceLookup.find(songs, serverID: "home", serviceID: "b1", client: fake, matches: matches, batches: false)
        #expect(found.map { $0?.ref } == ["mc1.halo", "mc1.run", nil, "mc1.halo"])
        #expect(fake.searches.count == 3)

        let again = await ServiceLookup.find(songs, serverID: "home", serviceID: "b1", client: fake, matches: matches, batches: false)
        #expect(again.map { $0?.ref } == ["mc1.halo", "mc1.run", nil, "mc1.halo"])
        #expect(fake.searches.count == 3)

        let missing = LookupCatalog()
        let none = await ServiceLookup.find(songs, serverID: "home", serviceID: "b1", client: missing, matches: ServiceMatches(file: nil), batches: false)
        #expect(none.allSatisfy { $0 == nil })
        #expect(missing.searches.count == 1)
    }

    @Test("with batch matching: the whole queue in one request, a song not answered left unkept")
    func batches() async throws {
        let fake = LookupCatalog()
        fake.tracks = ["Halo Beyoncé": TracksideCatalogTrack(ref: "mc1.halo", serviceID: "b1", title: "Halo", artist: "Beyoncé")]
        fake.deferred = ["Later"]
        let matches = ServiceMatches(file: nil)
        let songs = [WantedSong(title: "Halo", artist: "Beyoncé"), WantedSong(title: "Unknown", artist: "Nobody"), WantedSong(title: "Later", artist: "Someone")]
        let found = await ServiceLookup.find(songs, serverID: "home", serviceID: "b1", client: fake, matches: matches, batches: true)
        #expect(found.map { $0?.ref } == ["mc1.halo", nil, nil])
        #expect(fake.matchRequests.map(\.items.count) == [3])
        #expect(fake.searches.isEmpty)
        #expect(await matches.known(songs[1], serverID: "home", serviceID: "b1") != nil)
        #expect(await matches.known(songs[2], serverID: "home", serviceID: "b1") == nil)
    }
}

/// A server's services as Play From asks them: searches and batch matches, answered from a
/// list of tracks by "title artist".
private final class LookupCatalog: CatalogService, @unchecked Sendable {
    private let lock = NSLock()
    private var _tracks: [String: TracksideCatalogTrack] = [:]
    private var _deferred: Set<String> = []
    private var _searches: [String] = []
    private var _matchRequests: [TracksideCatalogMatchRequest] = []

    var tracks: [String: TracksideCatalogTrack] {
        get { lock.withLock { _tracks } }
        set { lock.withLock { _tracks = newValue } }
    }
    /// Titles the batch match says it will look up later.
    var deferred: Set<String> {
        get { lock.withLock { _deferred } }
        set { lock.withLock { _deferred = newValue } }
    }
    var searches: [String] { lock.withLock { _searches } }
    var matchRequests: [TracksideCatalogMatchRequest] { lock.withLock { _matchRequests } }

    func catalogServices() async throws -> TracksideCatalogServiceList { TracksideCatalogServiceList(services: []) }

    func searchCatalog(_ query: String, types: [TracksideClient.CatalogType], services: [String], limit: Int) async throws -> TracksideCatalogSearch {
        let track = lock.withLock {
            _searches.append(query)
            return _tracks[query]
        }
        return TracksideCatalogSearch(tracks: track.map { [$0] } ?? [])
    }

    func catalogItem(_ ref: String, cursor: String?, limit: Int?) async throws -> TracksideCatalogItem { throw TracksideError.notFound }

    func catalogLibrary(service id: String, kind: TracksideCatalogLibraryPage.Kind, cursor: String?, limit: Int) async throws -> TracksideCatalogLibraryPage { throw TracksideError.notFound }

    func catalogRecommendations(services: [String], limit: Int) async throws -> TracksideCatalogRecommendations { throw TracksideError.notFound }

    func setCatalogSaved(_ saved: Bool, ref: String) async throws -> TracksideCatalogSaved { throw TracksideError.notFound }

    func matchCatalog(_ request: TracksideCatalogMatchRequest) async throws -> TracksideCatalogMatch {
        let (tracks, deferred) = lock.withLock {
            _matchRequests.append(request)
            return (_tracks, _deferred)
        }
        let results: [[String: Any]] = request.items.map { item in
            let state: [String: Any]
            if deferred.contains(item.title) {
                state = ["service_id": "b1", "state": "deferred"]
            } else if let track = tracks["\(item.title) \(item.artist)"] {
                state = ["service_id": "b1", "state": "found", "track": ["kind": "track", "ref": track.ref, "service_id": track.serviceID, "title": track.title, "artist": track.artist ?? ""]]
            } else {
                state = ["service_id": "b1", "state": "none"]
            }
            return ["id": item.id, "matches": [state]]
        }
        let data = try JSONSerialization.data(withJSONObject: ["complete": true, "results": results])
        return try TracksideJSON.decoder.decode(TracksideCatalogMatch.self, from: data)
    }
}

private final class LookupClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ start: Date) { current = start }

    var now: Date { lock.withLock { current } }

    func advance(by seconds: TimeInterval) {
        lock.withLock { current = current.addingTimeInterval(seconds) }
    }
}

@Suite("Saving to a music service, its radio, and its one stream")
struct ServiceLibraryEditTests {
    typealias T = TracksideTesting

    @Test("a service says which kinds it saves, and a track whether it's saved")
    func wire() throws {
        let service = try T.decode(TracksideCatalogService.self, #"{"id": "B1", "name": "Spotify", "logo_key": "spotify", "capabilities": ["library_edit", "search"], "library_edit_kinds": ["album", "track", 7], "account": {"state": "ok"}}"#)
        #expect(service.can(.libraryEdit))
        #expect(service.libraryEditKinds == ["album", "track"])
        #expect(service.canSave("track"))
        #expect(!service.canSave("playlist"))
        #expect(service.placeKind == "spotify")

        let unnamed = try T.decode(TracksideCatalogService.self, #"{"id": "b2", "name": "Elsewhere"}"#)
        #expect(unnamed.placeKind == "b2")
        #expect(!unnamed.canSave("track"))

        let track = try T.decode(TracksideCatalogTrack.self, #"{"ref": "mc1.a", "service_id": "b1", "title": "Halo", "saved": true}"#)
        #expect(track.saved == true)
        #expect(try T.decode(TracksideCatalogTrack.self, #"{"ref": "mc1.a", "title": "Halo"}"#).saved == nil)

        let answer = try T.decode(TracksideCatalogSaved.self, #"{"ref": "mc1.a", "saved": false, "service_id": "B1"}"#)
        #expect(!answer.isSaved)
        #expect(answer.serviceID == "b1")

        let me = try T.decode(TracksideMe.Features.self, #"{"catalog": {"save": true, "radio": true, "match": true}, "speakers": {"control": true, "takeover": true}}"#)
        #expect(me.catalog?.save == true)
        #expect(me.catalog?.radio == true)
        #expect(me.catalog?.match == true)
        #expect(me.speakers?.takeover == true)
    }

    @Test("saving puts the ref in the path, PUT to save and DELETE to remove")
    func requests() async throws {
        let stub = StubTracksideTransport { request -> StubTracksideTransport.Answer in
            (200, #"{"ref": "mc1.Ab", "saved": \#(request.httpMethod == "PUT"), "service_id": "b1"}"#, [:])
        }
        let client = T.client(stub)
        #expect(try await client.setCatalogSaved(true, ref: "mc1.Ab").isSaved)
        #expect(try await client.setCatalogSaved(false, ref: "mc1.Ab").isSaved == false)
        #expect(stub.requests.map(\.httpMethod) == ["PUT", "DELETE"])
        #expect(stub.requests.map { $0.url?.path() } == ["/api/v1/catalog/items/mc1.Ab/saved", "/api/v1/catalog/items/mc1.Ab/saved"])
    }

    @Test("a failed command says why, a reason this build doesn't know read as unknown")
    func reasons() throws {
        let failed = try T.decode(TracksideSpeakerCommandOutcome.self, #"{"command_id": "j1", "speaker_id": "O1", "outcome": "failed", "error": {"code": "command_failed", "reason": "service_busy", "service_id": "B1", "holder_name": "Kitchen"}}"#)
        #expect(failed.error?.reason == .serviceBusy)
        #expect(failed.error?.serviceID == "b1")
        #expect(failed.error?.holderName == "Kitchen")
        let newer = try T.decode(TracksideSpeakerCommandOutcome.self, #"{"command_id": "j2", "outcome": "failed", "error": {"code": "command_failed", "reason": "brand_new"}}"#)
        #expect(newer.error?.reason == .unknown)
        let older = try T.decode(TracksideSpeakerCommandOutcome.self, #"{"command_id": "j3", "outcome": "failed", "error": {"code": "powered_off"}}"#)
        #expect(older.error?.reason == nil)
        #expect(older.error?.code == "powered_off")

        #expect(SpeakerProblem.reason(code: "service_busy") == .serviceBusy)
        #expect(SpeakerProblem.reason(code: "service_signed_out") == .serviceSignedOut)
        #expect(SpeakerProblem.reason(code: "region_unavailable") == .regionUnavailable)
        #expect(SpeakerProblem.reason(code: "subscription_required") == .subscriptionRequired)
        #expect(SpeakerProblem.reason(code: "drm_failed") == .notPlayable)
    }

    @Test("a radio play sends its one item and nothing else, and takeover only when asked")
    func playBodies() throws {
        var radio = TracksideSpeakerPlayRequest.serviceRadio(from: "mc1.a")
        radio.takeover = true
        let body = try #require(JSONSerialization.jsonObject(with: TracksideJSON.encoder.encode(radio)) as? [String: Any])
        #expect(body["radio"] as? Bool == true)
        #expect(body["takeover"] as? Bool == true)
        #expect(body["mode"] as? String == "replace")
        #expect(body["start_index"] == nil)
        #expect(body["position_ms"] == nil)
        #expect(body["shuffle"] == nil)
        #expect((body["items"] as? [[String: String]]) == [["ref": "mc1.a"]])

        let plain = try #require(JSONSerialization.jsonObject(with: TracksideJSON.encoder.encode(TracksideSpeakerPlayRequest(items: [.song("s1")]))) as? [String: Any])
        #expect(plain["radio"] == nil)
        #expect(plain["takeover"] == nil)
    }

    @Test("a save shows at once, and is put back when the server refuses")
    @MainActor
    func saving() async throws {
        var list = try TracksideJSON.decoder.decode(TracksideCatalogServiceList.self, from: TracksideFixtures.data("catalog-services.json"))
        list.services[0].capabilities.insert(.libraryEdit)
        list.services[0].libraryEditKinds = ["track"]
        let refusing = SavingCatalog(services: list, refuses: true)
        let catalog = StreamingCatalog()
        catalog.attach(serverID: "home", name: "Home", service: refusing, features: TracksideMe.Catalog(relay: true, save: true))
        for _ in 0..<100 where catalog.services["home"] == nil {
            try? await Task.sleep(for: .milliseconds(10))
        }
        #expect(catalog.canSave(track: "bqtyy4kcexcsg6wa0y4w5", on: "home"))
        #expect(!catalog.canSave(track: "bnr6z9ds6zzv5xjpv0j1s", on: "home"))
        #expect(catalog.isSaved("mc1.a", on: "home", known: false) == false)
        await #expect(throws: TracksideError.self) {
            try await catalog.setSaved(true, ref: "mc1.a", on: "home")
        }
        #expect(catalog.isSaved("mc1.a", on: "home", known: false) == false)

        let older = StreamingCatalog()
        older.attach(serverID: "home", name: "Home", service: refusing, features: TracksideMe.Catalog(relay: true))
        for _ in 0..<100 where older.services["home"] == nil {
            try? await Task.sleep(for: .milliseconds(10))
        }
        #expect(!older.canSave(track: "bqtyy4kcexcsg6wa0y4w5", on: "home"))
    }
}

private final class SavingCatalog: CatalogService {
    let services: TracksideCatalogServiceList
    let refuses: Bool

    init(services: TracksideCatalogServiceList, refuses: Bool) {
        self.services = services
        self.refuses = refuses
    }

    func catalogServices() async throws -> TracksideCatalogServiceList { services }

    func searchCatalog(_ query: String, types: [TracksideClient.CatalogType], services: [String], limit: Int) async throws -> TracksideCatalogSearch { TracksideCatalogSearch() }

    func catalogItem(_ ref: String, cursor: String?, limit: Int?) async throws -> TracksideCatalogItem { throw TracksideError.notFound }

    func catalogLibrary(service id: String, kind: TracksideCatalogLibraryPage.Kind, cursor: String?, limit: Int) async throws -> TracksideCatalogLibraryPage { throw TracksideError.notFound }

    func catalogRecommendations(services: [String], limit: Int) async throws -> TracksideCatalogRecommendations { throw TracksideError.notFound }

    func matchCatalog(_ request: TracksideCatalogMatchRequest) async throws -> TracksideCatalogMatch { throw TracksideError.notFound }

    func setCatalogSaved(_ saved: Bool, ref: String) async throws -> TracksideCatalogSaved {
        if refuses { throw TracksideError.conflict(reason: "service_signed_out") }
        return try TracksideTesting.decode(TracksideCatalogSaved.self, #"{"ref": "\#(ref)", "saved": \#(saved), "service_id": "b1"}"#)
    }
}

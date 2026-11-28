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
        let answer = try TracksideJSON.decoder.decode(TracksideCatalogMatch.self, from: TracksideFixtures.data("catalog-match.json"))
        #expect(!answer.isComplete)
        #expect(answer.retryAfter == 12)
        #expect(answer.results.map(\.id) == ["sdjcz08vf67h7cx28snq6", "local-2"])
        let halo = try #require(answer.results.first)
        #expect(halo.matches.map(\.serviceID) == ["bqtyy4kcexcsg6wa0y4w5", "bnr6z9ds6zzv5xjpv0j1s"])
        #expect(halo.matches.map(\.state) == [.found, .timedOut])
        #expect(halo.matches[0].track?.ref == "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0")
        #expect(halo.matches[0].track?.songID == "sdjcz08vf67h7cx28snq6")
        #expect(halo.matches[1].track == nil)
        #expect(answer.results[1].matches.map(\.state) == [.deferred, .notFound])

        let newer = try T.decode(TracksideCatalogMatch.self, #"{"complete": true, "results": [{"id": "0", "matches": [{"service_id": "B1", "state": "brand_new"}]}]}"#)
        #expect(newer.results.first?.matches.first?.state == .unknown)
        #expect(newer.results.first?.matches.first?.serviceID == "b1")
        #expect(newer.retryAfter == nil)
    }

    @Test("a batch match asks as the server's example does")
    func matchRequest() throws {
        let request = TracksideCatalogMatchRequest(
            items: [
                .init(id: "sdjcz08vf67h7cx28snq6", title: "Halo", artist: "Beyoncé", album: "I Am... Sasha Fierce", durationMS: 261_640, isrc: "USSM10804555"),
                .init(id: "local-2", title: "Single Ladies (Put a Ring on It)", artist: "Beyoncé", durationMS: 193_213),
            ],
            services: ["bqtyy4kcexcsg6wa0y4w5", "bnr6z9ds6zzv5xjpv0j1s"]
        )
        let written = try #require(JSONSerialization.jsonObject(with: TracksideJSON.encoder.encode(request)) as? NSDictionary)
        let example = try #require(JSONSerialization.jsonObject(with: TracksideFixtures.data("catalog-match-request.json")) as? NSDictionary)
        #expect(written == example)
        #expect(try TracksideJSON.decoder.decode(TracksideCatalogMatchRequest.self, from: TracksideFixtures.data("catalog-match-request.json")) == request)
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
        let services = try TracksideJSON.decoder.decode(TracksideCatalogServiceList.self, from: TracksideFixtures.data("catalog-services.json")).services
        let spotify = services[0]
        #expect(spotify.can(.libraryEdit))
        #expect(spotify.libraryEditKinds == ["album", "artist", "playlist", "track"])
        #expect(spotify.canSave("track"))
        #expect(spotify.placeKind == "spotify")
        #expect(!services[1].canSave("track"))

        // A kind in a shape this build doesn't expect is left out, never the service.
        let odd = try T.decode(TracksideCatalogService.self, #"{"id": "B1", "name": "Spotify", "logo_key": "spotify", "capabilities": ["library_edit", "search"], "library_edit_kinds": ["album", "track", 7], "account": {"state": "ok"}}"#)
        #expect(odd.libraryEditKinds == ["album", "track"])
        #expect(!odd.canSave("playlist"))

        let unnamed = try T.decode(TracksideCatalogService.self, #"{"id": "b2", "name": "Elsewhere"}"#)
        #expect(unnamed.placeKind == "b2")
        #expect(!unnamed.canSave("track"))

        guard case .album(_, let tracks) = try TracksideJSON.decoder.decode(TracksideCatalogItem.self, from: TracksideFixtures.data("catalog-album.json")) else {
            Issue.record("expected an album")
            return
        }
        #expect(tracks.map(\.saved) == [true, false])
        let search = try TracksideJSON.decoder.decode(TracksideCatalogSearch.self, from: TracksideFixtures.data("catalog-search.json"))
        // Search results don't say, which isn't the same as not saved.
        #expect(search.tracks.first?.saved == nil)

        let answer = try TracksideJSON.decoder.decode(TracksideCatalogSaved.self, from: TracksideFixtures.data("catalog-saved.json"))
        #expect(answer.ref == "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0")
        #expect(answer.isSaved)
        #expect(answer.serviceID == "bqtyy4kcexcsg6wa0y4w5")

        let me = try TracksideJSON.decoder.decode(TracksideMe.self, from: TracksideFixtures.data("me.json"))
        #expect(me.features?.catalog?.save == true)
        #expect(me.features?.catalog?.radio == true)
        #expect(me.features?.catalog?.match == true)
        #expect(me.features?.speakers?.takeover == true)
        let older = try TracksideJSON.decoder.decode(TracksideMe.self, from: TracksideFixtures.data("me-without-acquisition.json"))
        #expect(older.features?.catalog?.save == nil)
        #expect(older.features?.speakers?.takeover == nil)
    }

    @Test("saving puts the ref in the path, PUT to save and DELETE to remove")
    func requests() async throws {
        let saved = String(decoding: try TracksideFixtures.data("catalog-saved.json"), as: UTF8.self)
        let removed = saved.replacingOccurrences(of: #""saved": true"#, with: #""saved": false"#)
        let stub = StubTracksideTransport { request -> StubTracksideTransport.Answer in
            (200, request.httpMethod == "PUT" ? saved : removed, [:])
        }
        let client = T.client(stub)
        let ref = "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0"
        #expect(try await client.setCatalogSaved(true, ref: ref).isSaved)
        #expect(try await client.setCatalogSaved(false, ref: ref).isSaved == false)
        #expect(stub.requests.map(\.httpMethod) == ["PUT", "DELETE"])
        #expect(stub.requests.map { $0.url?.path() } == ["/api/v1/catalog/items/\(ref)/saved", "/api/v1/catalog/items/\(ref)/saved"])
    }

    @Test("a failed command says why, a reason this build doesn't know read as unknown")
    func reasons() throws {
        let failed = try TracksideJSON.decoder.decode(TracksideSpeakerCommandOutcome.self, from: TracksideFixtures.data("speaker-command-failed-event.json"))
        #expect(failed.outcome == .failed)
        #expect(failed.speakerID == "o4j8fccgajqsqxancbfcz")
        #expect(failed.error?.code == "command_failed")
        #expect(failed.error?.reason == .serviceBusy)
        #expect(failed.error?.serviceID == "bqtyy4kcexcsg6wa0y4w5")
        #expect(failed.error?.holderID == "o7sny3kfnvsjbbhx01f2j")
        #expect(failed.error?.holderName == "Living Room")
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
        #expect(SpeakerProblem.reason(code: "radio_unavailable") == .notPlayable)
    }

    @Test("a radio play sends its one item and nothing else, as the server's example does")
    func playBodies() throws {
        var radio = TracksideSpeakerPlayRequest.serviceRadio(from: "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0", context: TracksideSpeaker.Context(kind: .station, name: "Halo Radio"))
        radio.takeover = true
        let example = try TracksideJSON.decoder.decode(TracksideSpeakerPlayRequest.self, from: TracksideFixtures.data("speaker-play-radio-request.json"))
        // The example leaves `mode` out, which is `replace`, as sent here.
        #expect(example == radio)
        let body = try #require(JSONSerialization.jsonObject(with: TracksideJSON.encoder.encode(radio)) as? [String: Any])
        #expect(body["radio"] as? Bool == true)
        #expect(body["takeover"] as? Bool == true)
        #expect(body["mode"] as? String == "replace")
        #expect(body["start_index"] == nil)
        #expect(body["position_ms"] == nil)
        #expect(body["shuffle"] == nil)
        #expect(body["hold"] == nil)

        let held = try TracksideJSON.decoder.decode(TracksideSpeakerPlayRequest.self, from: TracksideFixtures.data("speaker-play-held-request.json"))
        #expect(held.hold == true)
        #expect(held.takeover == true)
        #expect(held.mode == .replace)
        #expect(held.radio == nil)

        let plain = try #require(JSONSerialization.jsonObject(with: TracksideJSON.encoder.encode(TracksideSpeakerPlayRequest(items: [.song("s1")]))) as? [String: Any])
        #expect(plain["radio"] == nil)
        #expect(plain["takeover"] == nil)
        #expect(plain["hold"] == nil)
    }

    @Test("a save shows at once, is put back when the server refuses, and stays where it can't be removed")
    @MainActor
    func saving() async throws {
        let list = try TracksideJSON.decoder.decode(TracksideCatalogServiceList.self, from: TracksideFixtures.data("catalog-services.json"))
        let refusing = SavingCatalog(services: list, refusal: "service_signed_out")
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

        // Held for another source in the service's library: it stays saved.
        let shared = StreamingCatalog()
        shared.attach(serverID: "home", name: "Home", service: SavingCatalog(services: list, refusal: "not_removable"), features: TracksideMe.Catalog(relay: true, save: true))
        for _ in 0..<100 where shared.services["home"] == nil {
            try? await Task.sleep(for: .milliseconds(10))
        }
        await #expect(throws: TracksideError.conflict(reason: "not_removable")) {
            try await shared.setSaved(false, ref: "mc1.a", on: "home")
        }
        #expect(shared.isSaved("mc1.a", on: "home") == true)

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
    /// The conflict every save and removal is refused with; nil to take them.
    let refusal: String?

    init(services: TracksideCatalogServiceList, refusal: String?) {
        self.services = services
        self.refusal = refusal
    }

    func catalogServices() async throws -> TracksideCatalogServiceList { services }

    func searchCatalog(_ query: String, types: [TracksideClient.CatalogType], services: [String], limit: Int) async throws -> TracksideCatalogSearch { TracksideCatalogSearch() }

    func catalogItem(_ ref: String, cursor: String?, limit: Int?) async throws -> TracksideCatalogItem { throw TracksideError.notFound }

    func catalogLibrary(service id: String, kind: TracksideCatalogLibraryPage.Kind, cursor: String?, limit: Int) async throws -> TracksideCatalogLibraryPage { throw TracksideError.notFound }

    func catalogRecommendations(services: [String], limit: Int) async throws -> TracksideCatalogRecommendations { throw TracksideError.notFound }

    func matchCatalog(_ request: TracksideCatalogMatchRequest) async throws -> TracksideCatalogMatch { throw TracksideError.notFound }

    func setCatalogSaved(_ saved: Bool, ref: String) async throws -> TracksideCatalogSaved {
        if let refusal { throw TracksideError.conflict(reason: refusal) }
        return try TracksideTesting.decode(TracksideCatalogSaved.self, #"{"ref": "\#(ref)", "saved": \#(saved), "service_id": "b1"}"#)
    }
}

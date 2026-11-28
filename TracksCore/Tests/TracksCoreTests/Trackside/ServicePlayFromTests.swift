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

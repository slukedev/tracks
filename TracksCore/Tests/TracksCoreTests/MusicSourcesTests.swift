import Testing
import Foundation
@testable import TracksCore

@Suite("Sources")
struct MusicSourcesTests {
    // MARK: - Where a play came from

    @Test("stored sources read back as the engine reads them", arguments: [
        ("appleMusic", PlaySource.appleMusic),
        ("yourMusic", .yourMusic),
        ("spotify", .spotify),
        ("vinyl", .unknown("vinyl")),
    ])
    func storedValues(stored: String, source: PlaySource) {
        #expect(PlaySource(stored: stored) == source)
        #expect(source.rawValue == stored)
    }

    @Test("a source from a newer version round-trips unchanged")
    func unknownRoundTrips() throws {
        let source = PlaySource(stored: "tidal")
        #expect(source == .unknown("tidal"))
        let data = try JSONEncoder().encode([source])
        #expect(try JSONDecoder().decode([PlaySource].self, from: data) == [source])
        #expect(String(decoding: data, as: UTF8.self) == "[\"tidal\"]")
    }

    @Test("Spotify's player marks its observations")
    func spotifyObservation() {
        let observation = NowPlayingObservation(
            title: "Song", artistName: "Artist", playbackState: .playing,
            rawFields: [PlaySource.observationKey: PlaySource.spotifyMarker]
        )
        #expect(PlaySource(observation: observation) == .spotify)
    }

    // MARK: - Statistics scopes

    @Test("scopes are offered only with something to tell apart")
    func offeredScopes() {
        #expect(SourceScope.offered(played: []) == [])
        #expect(SourceScope.offered(played: [.appleMusic]) == [])
        #expect(SourceScope.offered(played: [.appleMusic, .yourMusic]) == [.all, .appleMusic, .yourMusic])
        #expect(SourceScope.offered(played: [.spotify]) == [.all, .spotify])
        #expect(SourceScope.offered(played: [.appleMusic, .unknown("tidal")]) == [.all, .appleMusic])
    }

    @Test("a scope counts only its own source; unknown sources only count in All")
    func scopeIncludes() {
        #expect(SourceScope.spotify.includes(.spotify))
        #expect(!SourceScope.appleMusic.includes(.spotify))
        #expect(!SourceScope.appleMusic.includes(.unknown("tidal")))
        #expect(SourceScope.all.includes(.unknown("tidal")))
    }

    // MARK: - Source ids and the filter

    @Test("source ids round-trip", arguments: ["appleMusic", "device", "spotify", "server:7F3A", "jukebox", "server:"])
    func sourceIDs(raw: String) {
        #expect(SourceID(rawValue: raw).rawValue == raw)
    }

    @Test("a server id keeps everything after its prefix")
    func serverID() {
        #expect(SourceID(rawValue: "server:a:b") == .server("a:b"))
        #expect(SourceID(rawValue: "server:") == .unknown("server:"))
    }

    @Test("the filter reads All by default and falls back to All once its source is gone")
    func selection() {
        #expect(SourceSelection(rawValue: "") == .all)
        #expect(SourceSelection(rawValue: "server:1") == .only(.server("1")))
        let sources: [SourceID] = [.appleMusic, .server("1")]
        #expect(SourceSelection.only(.server("1")).effective(among: sources) == .only(.server("1")))
        #expect(SourceSelection.only(.server("2")).effective(among: sources) == .all)
        #expect(SourceSelection.only(.appleMusic).effective(among: [.appleMusic]) == .all)
    }

    // MARK: - Play From

    @Test("a new order starts local first, servers last in the order they were added")
    func startingOrder() {
        #expect(PlayFromOrder.standard.arranged([.source(.server("a")), .source(.appleMusic), .source(.server("b")), .source(.device)]) == [
            .source(.device), .source(.appleMusic), .source(.server("a")), .source(.server("b")),
        ])
    }

    @Test("places the order doesn't name yet go last, never between the ones it does")
    func newPlacesGoLast() {
        let order = PlayFromOrder([.source(.spotify), .source(.server("a")), .source(.appleMusic), .downloads, .source(.device)])
        let here: [PlayFromPlace] = [.source(.device), .downloads, .source(.server("a")), .source(.server("b")), .source(.appleMusic), .source(.spotify)]
        #expect(order.arranged(here) == [
            .source(.spotify), .source(.server("a")), .source(.appleMusic), .downloads, .source(.device), .source(.server("b")),
        ])
        #expect(order.arranged([.source(.device)]) == [.source(.device)])
    }

    @Test("any place can go anywhere", arguments: [
        [PlayFromPlace.source(.server("b")), .source(.spotify), .downloads, .source(.appleMusic), .source(.server("a")), .source(.device)],
        [.source(.spotify), .source(.appleMusic), .source(.server("b")), .source(.server("a")), .source(.device), .downloads],
        [.source(.device), .downloads, .source(.server("a")), .source(.server("b")), .source(.appleMusic), .source(.spotify)],
    ])
    func anyOrder(chosen: [PlayFromPlace]) {
        let order = PlayFromOrder.standard.reordered(chosen)
        #expect(order.places == chosen)
        #expect(PlayFromOrder(rawValue: order.rawValue).arranged(chosen.reversed()) == chosen)
    }

    @Test("reordering what's shown keeps every other place where it was")
    func reorderKeepsHiddenPlaces() {
        let order = PlayFromOrder([.source(.server("elsewhere")), .source(.appleMusic), .source(.spotify), .source(.device), .downloads])
        let new = order.reordered([.source(.device), .source(.appleMusic)])
        #expect(new.places == [.source(.server("elsewhere")), .source(.device), .source(.spotify), .source(.appleMusic), .downloads])
        let added = order.reordered([.source(.server("new")), .source(.device)])
        #expect(added.places == [.source(.server("elsewhere")), .source(.appleMusic), .source(.spotify), .source(.server("new")), .downloads, .source(.device)])
    }

    @Test("the order round-trips through its stored form, unknown places included")
    func storedOrder() {
        let order = PlayFromOrder([.source(.spotify), .downloads, .source(.server("x")), .source(.unknown("tidal"))])
        #expect(PlayFromOrder(rawValue: order.rawValue) == order)
        #expect(PlayFromOrder(rawValue: "") == .standard)
    }

    @Test("an order saved before is written out as it played")
    func migration() {
        #expect(PlayFromOrder.standard.migrated(servers: ["a", "b"]).places == [
            .source(.device), .downloads, .source(.server("a")), .source(.server("b")), .source(.appleMusic), .source(.spotify),
        ])
        let partial = PlayFromOrder([.source(.appleMusic), .source(.device), .source(.server("elsewhere")), .source(.unknown("tidal"))])
        let migrated = partial.migrated(servers: ["a"])
        #expect(migrated.places == [
            .source(.appleMusic), .source(.spotify), .source(.device), .downloads, .source(.server("a")),
            .source(.server("elsewhere")), .source(.unknown("tidal")),
        ])
        #expect(migrated.arranged([.source(.server("a")), .source(.server("c")), .source(.device)]) == [
            .source(.device), .source(.server("a")), .source(.server("c")),
        ])
        #expect(migrated.migrated(servers: ["a"]) == migrated)
    }

    private struct Copy: Equatable {
        let name: String
        let place: PlayFromPlace
        var canPlay = true
    }

    @Test("the first copy that can play wins, in Play From order")
    func choosesCopy() {
        let copies = [
            Copy(name: "apple", place: .source(.appleMusic)),
            Copy(name: "home", place: .source(.server("home")), canPlay: false),
            Copy(name: "download", place: .downloads),
            Copy(name: "spotify", place: .source(.spotify)),
        ]
        let standard = PlayFromOrder.standard
        #expect(standard.choose(copies, place: \.place, canPlay: \.canPlay)?.name == "download")
        #expect(standard.ranked(copies, place: \.place).map(\.name) == ["download", "apple", "spotify", "home"])

        let serverFirst = PlayFromOrder([.source(.server("home")), .source(.spotify), .source(.appleMusic), .source(.device), .downloads])
        #expect(serverFirst.ranked(copies, place: \.place).map(\.name) == ["home", "spotify", "apple", "download"])
        #expect(serverFirst.choose(copies, place: \.place, canPlay: \.canPlay)?.name == "spotify")
        #expect(standard.choose(copies, place: \.place) { _ in false } == nil)
    }

    @Test("copies from places the order doesn't name come after the rest, as they came")
    func unnamedCopiesLast() {
        let copies = [
            Copy(name: "b", place: .source(.server("b"))),
            Copy(name: "tidal", place: .source(.unknown("tidal"))),
            Copy(name: "a", place: .source(.server("a"))),
            Copy(name: "device", place: .source(.device)),
        ]
        let order = PlayFromOrder([.source(.server("a"))])
        #expect(order.ranked(copies, place: \.place).map(\.name) == ["a", "device", "b", "tidal"])
        #expect(order.including([.source(.server("b"))]).ranked(copies, place: \.place).map(\.name) == ["a", "device", "b", "tidal"])
    }
}

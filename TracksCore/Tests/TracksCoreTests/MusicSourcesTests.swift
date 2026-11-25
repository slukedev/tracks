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

    @Test("the default order puts local and owned copies first")
    func defaultOrder() {
        #expect(PlayFromOrder.standard.arranged(servers: ["a", "b"]) == [
            .source(.device), .downloads, .source(.server("a")), .source(.server("b")),
            .source(.appleMusic), .source(.spotify),
        ])
    }

    @Test("a chosen order keeps its places and fits in what it doesn't name")
    func arrangedOrder() {
        let order = PlayFromOrder([.source(.appleMusic), .source(.device), .source(.server("gone"))])
        #expect(order.arranged(servers: ["a"]) == [
            .source(.appleMusic), .source(.spotify), .source(.device), .downloads, .source(.server("a")),
        ])
    }

    @Test("the order round-trips through its stored form, unknown places included")
    func storedOrder() {
        let order = PlayFromOrder([.source(.spotify), .downloads, .source(.server("x")), .source(.unknown("tidal"))])
        #expect(PlayFromOrder(rawValue: order.rawValue) == order)
        #expect(PlayFromOrder(rawValue: "") == .standard)
    }

    @Test("reordering here keeps the servers only another device has")
    func replacingKeepsOtherDevices() {
        let order = PlayFromOrder([.source(.server("elsewhere")), .source(.appleMusic)])
        let new = order.replacing(arranged: [.source(.appleMusic), .source(.device)], servers: [])
        #expect(new.places == [.source(.appleMusic), .source(.device), .source(.server("elsewhere"))])
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
        ]
        let standard = PlayFromOrder.standard
        #expect(standard.choose(copies, place: \.place, canPlay: \.canPlay)?.name == "download")
        #expect(standard.ranked(copies, place: \.place).map(\.name) == ["download", "home", "apple"])

        let appleFirst = PlayFromOrder([.source(.appleMusic), .source(.device), .downloads])
        #expect(appleFirst.choose(copies, place: \.place, canPlay: \.canPlay)?.name == "apple")
        #expect(standard.choose(copies, place: \.place) { _ in false } == nil)
    }
}

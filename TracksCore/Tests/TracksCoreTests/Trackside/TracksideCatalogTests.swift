import Testing
import Foundation
@testable import TracksCore

@Suite("Trackside's music services on the wire")
struct TracksideCatalogTests {
    typealias T = TracksideTesting

    private func fixture<Value: Decodable>(_ type: Value.Type, _ name: String) throws -> Value {
        try TracksideJSON.decoder.decode(type, from: TracksideFixtures.data(name))
    }

    @Test("the services read with their accounts, capabilities and quality")
    func services() throws {
        let list = try fixture(TracksideCatalogServiceList.self, "catalog-services.json")
        #expect(list.state == .ok)
        #expect(list.services.map(\.name) == ["Spotify", "Tidal", "Qobuz"])
        let first = list.services[0]
        #expect(first.id == "bqtyy4kcexcsg6wa0y4w5")
        #expect(first.logoKey == "spotify")
        #expect(first.capabilities == [.library, .playlists, .radio, .search, .similar, .topTracks])
        #expect(first.quality == .high)
        #expect(first.isSearchable)
        #expect(!first.isNativeOnApple)
        let signedOut = list.services[2]
        #expect(signedOut.account.state == .signedOut)
        #expect(signedOut.account.detail == "The sign-in expired.")
        #expect(!signedOut.isSearchable)

        let change = try fixture(TracksideCatalogServicesChange.self, "catalog-services-event.json")
        #expect(change.state == .ok)
    }

    @Test("a search reads every section, which services answered, and that one didn't in time")
    func search() throws {
        let found = try fixture(TracksideCatalogSearch.self, "catalog-search.json")
        #expect(!found.isComplete)
        #expect(found.services == [.init(id: "bqtyy4kcexcsg6wa0y4w5", state: .ok), .init(id: "bnr6z9ds6zzv5xjpv0j1s", state: .timedOut)])
        let track = try #require(found.tracks.first)
        #expect(track.ref == "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0")
        #expect(track.title == "Halo")
        #expect(track.artistLine == "Beyoncé")
        #expect(track.artists.first?.ref == "mc1.YXJ0aXN0H3Nwb3RpZnkfNnZXRE85NjlQdk5xTllISU9XNXYwbQ")
        #expect(track.songID == "sdjcz08vf67h7cx28snq6")
        #expect(track.songKey == "halo\u{1F}beyonce")
        #expect(track.duration == 261.64)
        #expect(track.isPlayable)
        #expect(found.albums.first?.year == 2008)
        #expect(found.albums.first?.trackCount == 11)
        #expect(found.artists.first?.identity == "beyonce")
        #expect(found.playlists.first?.owner == "Spotify")
        #expect(found.playlists.first?.trackCount == 50)
        #expect(!found.isUnavailable)
    }

    @Test("stations come under the server's own key, and are written back under it")
    func searchStations() throws {
        let found = try T.decode(TracksideCatalogSearch.self, #"{"complete": true, "services": [], "radio": {"items": [{"kind": "radio", "name": "Pure Pop", "ref": "mc1.r", "service_id": "B1"}]}}"#)
        #expect(found.radios.map(\.name) == ["Pure Pop"])
        #expect(found.radios.first?.serviceID == "b1")
        #expect(!found.isEmpty)
        let written = try TracksideJSON.encoder.encode(found)
        let object = try #require(JSONSerialization.jsonObject(with: written) as? [String: Any])
        #expect(object["radio"] != nil)
        #expect(object["radios"] == nil)
        #expect(try TracksideJSON.decoder.decode(TracksideCatalogSearch.self, from: written).radios.count == 1)
    }

    @Test("the catalog section rides along the library search, which it never joins")
    func searchWithCatalog() throws {
        let results = try fixture(TracksideSearchResults.self, "search-with-catalog.json")
        #expect(results.songs?.items.map(\.id) == ["sdjcz08vf67h7cx28snq6"])
        let catalog = try #require(results.catalog)
        #expect(catalog.isComplete)
        #expect(catalog.tracks.first?.quality == .lossless)
        #expect(catalog.tracks.first?.songID == results.songs?.items.first?.id)

        let unavailable = try T.decode(TracksideSearchResults.self, #"{"catalog": {"complete": true, "services": [], "state": "unavailable"}}"#)
        #expect(unavailable.catalog?.isUnavailable == true)
        #expect(unavailable.catalog?.isEmpty == true)

        // A catalog in a shape this build doesn't know leaves the library's results be.
        let odd = try T.decode(TracksideSearchResults.self, #"{"catalog": 3, "songs": {"items": []}}"#)
        #expect(odd.catalog == nil)
        #expect(odd.songs?.items.isEmpty == true)
    }

    @Test("an item's page reads as the kind its ref names")
    func items() throws {
        guard case .album(let album, let tracks) = try fixture(TracksideCatalogItem.self, "catalog-album.json") else {
            Issue.record("expected an album")
            return
        }
        #expect(album.name == "I Am... Sasha Fierce")
        #expect(album.artistRef == "mc1.YXJ0aXN0H3Nwb3RpZnkfNnZXRE85NjlQdk5xTllISU9XNXYwbQ")
        #expect(tracks.map(\.trackNumber) == [3, 9])
        #expect(tracks.map(\.songID) == ["sdjcz08vf67h7cx28snq6", nil])

        guard case .artist(let artist, let top, let albums) = try fixture(TracksideCatalogItem.self, "catalog-artist.json") else {
            Issue.record("expected an artist")
            return
        }
        #expect(artist.name == "Beyoncé")
        #expect(top.map(\.title) == ["Single Ladies (Put a Ring on It)"])
        #expect(albums.count == 1)

        guard case .playlist(let playlist, let songs, let next) = try fixture(TracksideCatalogItem.self, "catalog-playlist.json") else {
            Issue.record("expected a playlist")
            return
        }
        #expect(playlist.isEditable == false)
        #expect(songs.count == 1)
        #expect(next == "eyJvIjoxMDB9")
    }

    @Test("a service's saved library reads each item as its kind, and when it was synced")
    func library() throws {
        let page = try fixture(TracksideCatalogLibraryPage.self, "catalog-library.json")
        #expect(page.playlists.map(\.name) == ["This Is Beyoncé"])
        #expect(page.albums.isEmpty)
        #expect(page.nextCursor == "eyJvIjoxMDB9")
        #expect(page.syncedAt == T.date("2026-11-14T09:00:00.000Z"))

        let mixed = try T.decode(TracksideCatalogLibraryPage.self, #"{"items": [{"kind": "album", "name": "A", "ref": "mc1.a", "service_id": "b1"}, {"kind": "new_kind", "ref": "mc1.x"}, {"kind": "track", "ref": "mc1.t", "title": "T"}]}"#)
        #expect(mixed.albums.map(\.ref) == ["mc1.a"])
        #expect(mixed.tracks.map(\.title) == ["T"])
        #expect(mixed.syncedAt == nil)
    }

    @Test("refs stay exactly as given, case and all, and searches ask what's chosen")
    func requests() async throws {
        let stub = StubTracksideTransport { request -> StubTracksideTransport.Answer in
            switch request.url?.path() {
            case "/api/v1/catalog/search": (200, String(decoding: try TracksideFixtures.data("catalog-search.json"), as: UTF8.self), [:])
            case "/api/v1/search": (200, String(decoding: try TracksideFixtures.data("search-with-catalog.json"), as: UTF8.self), [:])
            case "/api/v1/catalog/services/bqtyy4kcexcsg6wa0y4w5/library": (200, String(decoding: try TracksideFixtures.data("catalog-library.json"), as: UTF8.self), [:])
            default: (200, String(decoding: try TracksideFixtures.data("catalog-playlist.json"), as: UTF8.self), [:])
            }
        }
        let client = T.client(stub)
        _ = try await client.searchCatalog("halo", services: ["bqtyy4kcexcsg6wa0y4w5"], limit: 80)
        _ = try await client.searchWithCatalog("halo", types: [.song])
        _ = try await client.catalogItem("mc1.cGxheWxpc3Qfc3BvdGlmeR8zN2k5ZFFaRjFEWDRzV1Nwd3EzTGlP", cursor: "eyJvIjoxMDB9")
        _ = try await client.catalogLibrary(service: "bqtyy4kcexcsg6wa0y4w5")
        let asked = stub.requests
        let search = T.query(try #require(asked[0].url))
        #expect(search["types"] == "track,album,artist,playlist")
        #expect(search["services"] == "bqtyy4kcexcsg6wa0y4w5")
        #expect(search["limit"] == "50")
        let combined = T.query(try #require(asked[1].url))
        #expect(combined["types"] == "song,catalog")
        #expect(combined["cursor"] == nil)
        #expect(asked[2].url?.path() == "/api/v1/catalog/items/mc1.cGxheWxpc3Qfc3BvdGlmeR8zN2k5ZFFaRjFEWDRzV1Nwd3EzTGlP")
        #expect(T.query(try #require(asked[2].url))["cursor"] == "eyJvIjoxMDB9")
        #expect(T.query(try #require(asked[3].url))["kind"] == "playlist")
    }

    @Test("/me says whether the relay can play the smaller tier too")
    func features() throws {
        let me = try fixture(TracksideMe.self, "me.json")
        #expect(me.features?.catalog?.relay == true)
        #expect(me.features?.catalog?.relayCellular == true)
        let older = try fixture(TracksideMe.self, "me-without-acquisition.json")
        #expect(older.features?.catalog == nil)
    }

    @Test("a service's results fold into the library's: owned copies shown as yours, repeats left out")
    func fold() throws {
        let found = try fixture(TracksideCatalogSearch.self, "catalog-search.json")
        let other = TracksideCatalogTrack(ref: "mc1.other", serviceID: "b1", title: "Run the World", artist: "Beyoncé", songKey: "run the world\u{1F}beyonce")
        let gone = TracksideCatalogTrack(ref: "mc1.gone", serviceID: "b1", title: "Gone", playable: false)
        let tracks = found.tracks + [other, other, gone]

        let shownAbove = CatalogFold.fold(tracks, listedKeys: ["halo\u{1F}beyonce"]) { $0 }
        #expect(shownAbove.count == 1)
        if case .track(let track) = shownAbove.first { #expect(track.ref == "mc1.other") } else { Issue.record("expected the service's track") }

        let notShown = CatalogFold.fold(tracks, listedKeys: []) { id in id == "sdjcz08vf67h7cx28snq6" ? "owned:\(id)" : nil }
        #expect(notShown.count == 2)
        if case .owned(let copy, let from) = notShown.first {
            #expect(copy == "owned:sdjcz08vf67h7cx28snq6")
            #expect(from.title == "Halo")
        } else {
            Issue.record("expected the owned copy")
        }
    }
}

@Suite("Trackside's relay")
struct TracksideRelayTests {
    typealias T = TracksideTesting

    private func fixture<Value: Decodable>(_ type: Value.Type, _ name: String) throws -> Value {
        try TracksideJSON.decoder.decode(type, from: TracksideFixtures.data(name))
    }

    @Test("a session reads its generation, playlist, format and tier")
    func session() throws {
        let playing = try fixture(TracksideRelaySession.self, "relay-session.json")
        #expect(playing.id == "h9zvca9p26dymt4v7cxj5")
        #expect(playing.speakerID == "o1a0psgqvec0jm734s9tv")
        #expect(playing.state == .playing)
        #expect(playing.generation == 3)
        #expect(playing.tier == .cellular)
        #expect(playing.stream?.hls == "/api/v1/relay/h9zvca9p26dymt4v7cxj5/3/master.m3u8")
        #expect(playing.stream?.format?.codec == "aac")
        #expect(playing.stream?.format?.audioFormat?.bitRate == 256)
        #expect(playing.latency == 4)
        #expect(playing.idleTimeoutS == 600)

        let starting = try fixture(TracksideRelaySession.self, "relay-session-starting.json")
        #expect(starting.state == .starting)
        #expect(starting.generation == 0)
        #expect(starting.stream?.hls == "/api/v1/relay/h9zvca9p26dymt4v7cxj5/1/master.m3u8")
        #expect(starting.stream?.format?.audioFormat?.codec == "FLAC")

        let ended = try fixture(TracksideRelaySession.self, "relay-session-ended.json")
        #expect(ended.hasEnded)
        #expect(ended.endedReason == .idle)
        #expect(ended.stream == nil)
    }

    @Test("the event carries the session, and the services' change says to read them again")
    func events() throws {
        let data = String(decoding: try TracksideFixtures.data("relay-session.json"), as: UTF8.self)
        guard case .relay(let session) = TracksideEvent(ServerSentEvent(id: "7", event: "relay", data: data)) else {
            Issue.record("expected a relay event")
            return
        }
        #expect(session.generation == 3)
        #expect(TracksideEvent(ServerSentEvent(id: "8", event: "catalog_services", data: #"{"state":"ok"}"#)) == .catalogServices(TracksideCatalogServicesChange(state: .ok)))
        #expect(TracksideEvent(ServerSentEvent(id: "9", event: "relay", data: "{}")) == .other("relay"))
    }

    @Test("a command to the relay hands back the session it moved to")
    func commandRelay() throws {
        var command = try fixture(TracksideSpeakerCommand.self, "speaker-command.json")
        #expect(command.relay == nil)
        command.relay = try fixture(TracksideRelaySession.self, "relay-session.json")
        let written = try TracksideJSON.encoder.encode(command)
        let read = try TracksideJSON.decoder.decode(TracksideSpeakerCommand.self, from: written)
        #expect(read.relay?.generation == 3)
    }

    @Test("starting, changing tier, ending and moving here go to the relay's own routes")
    func requests() async throws {
        let session = String(decoding: try TracksideFixtures.data("relay-session.json"), as: UTF8.self)
        let command = String(decoding: try TracksideFixtures.data("speaker-command.json"), as: UTF8.self)
        let stub = StubTracksideTransport { request -> StubTracksideTransport.Answer in
            switch (request.httpMethod, request.url?.path()) {
            case ("DELETE", _): (204, "", [:])
            case (_, "/api/v1/speakers/o4/transfer"): (202, command, [:])
            default: (201, session, [:])
            }
        }
        let client = T.client(stub)
        let play = TracksideSpeakerPlayRequest(items: [.ref("mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0"), .song("sdjcz08vf67h7cx28snq6")], startIndex: 1)
        let started = try await client.startRelay(TracksideRelayStart(play: play, name: "Luke's iPhone", tier: .cellular), key: "start-1")
        _ = try await client.setRelayTier(.lossless, session: started.id)
        try await client.endRelay(session: started.id)
        _ = try await client.transferHere(from: "o4", tier: .lossless, key: "here-1")
        let asked = stub.requests
        #expect(asked.map(\.httpMethod) == ["POST", "PATCH", "DELETE", "POST"])
        #expect(asked.map { $0.url?.path() } == [
            "/api/v1/relay/sessions", "/api/v1/relay/sessions/h9zvca9p26dymt4v7cxj5", "/api/v1/relay/sessions/h9zvca9p26dymt4v7cxj5", "/api/v1/speakers/o4/transfer",
        ])
        #expect(asked[0].value(forHTTPHeaderField: "Idempotency-Key") == "start-1")
        func body(_ index: Int) throws -> [String: Any] {
            try #require(JSONSerialization.jsonObject(with: try #require(asked[index].httpBody)) as? [String: Any])
        }
        #expect(try body(0)["tier"] as? String == "cellular")
        let sent = try #require(try body(0)["play"] as? [String: Any])
        #expect((sent["items"] as? [[String: String]])?.first?["ref"] == "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0")
        #expect(sent["start_index"] as? Int == 1)
        #expect(try body(1) as? [String: String] == ["tier": "lossless"])
        #expect(try body(3)["to"] as? String == "this_device")
        #expect(try body(3)["tier"] as? String == "lossless")

        let url = try #require(client.relayURL(started))
        #expect(url.path() == "/api/v1/relay/h9zvca9p26dymt4v7cxj5/3/master.m3u8")
        #expect(T.query(url)["mt"] == T.mediaToken.token)
    }

    @Test("the player moves to each new generation once, never back to an older one")
    func generations() throws {
        let playing = try fixture(TracksideRelaySession.self, "relay-session.json")
        var follower = RelayStreamFollower()
        #expect(follower.take(playing, wantsToPlay: true) == .open("/api/v1/relay/h9zvca9p26dymt4v7cxj5/3/master.m3u8"))
        #expect(follower.take(playing, wantsToPlay: true) == .stay)

        var next = playing
        next.generation = 4
        next.stream?.hls = "/api/v1/relay/h9zvca9p26dymt4v7cxj5/4/master.m3u8"
        #expect(follower.take(next, wantsToPlay: true) == .open("/api/v1/relay/h9zvca9p26dymt4v7cxj5/4/master.m3u8"))
        // An event about generation 3 arriving late changes nothing.
        #expect(follower.take(playing, wantsToPlay: true) == .stay)

        // Paused, the session names the next generation; it opens only on resuming.
        var paused = next
        paused.state = .paused
        paused.stream?.hls = "/api/v1/relay/h9zvca9p26dymt4v7cxj5/5/master.m3u8"
        follower.paused()
        #expect(follower.take(paused, wantsToPlay: false) == .stay)
        #expect(follower.resume() == .open("/api/v1/relay/h9zvca9p26dymt4v7cxj5/5/master.m3u8"))
        #expect(follower.resume() == .stay)
        // Gone from the server: the same playlist is asked for again once the session is read.
        #expect(follower.reopen() == .open("/api/v1/relay/h9zvca9p26dymt4v7cxj5/5/master.m3u8"))

        let ended = try fixture(TracksideRelaySession.self, "relay-session-ended.json")
        #expect(follower.take(ended, wantsToPlay: true) == .end(.idle))
        #expect(follower.reopen() == .stay)
    }

    @Test("lossless on Wi-Fi, the cellular tier on costly or constrained paths where the server has it")
    func tiers() {
        #expect(RelayTierChoice.tier(isExpensive: false, isConstrained: false, usesCellular: false, serverHasCellular: true) == .lossless)
        #expect(RelayTierChoice.tier(isExpensive: true, isConstrained: false, usesCellular: false, serverHasCellular: true) == .cellular)
        #expect(RelayTierChoice.tier(isExpensive: false, isConstrained: true, usesCellular: false, serverHasCellular: true) == .cellular)
        #expect(RelayTierChoice.tier(isExpensive: false, isConstrained: false, usesCellular: true, serverHasCellular: true) == .cellular)
        #expect(RelayTierChoice.tier(isExpensive: true, isConstrained: true, usesCellular: true, serverHasCellular: false) == .lossless)
    }

    @Test("a song change is heard once the audible program date passes it")
    func audibility() {
        let now = T.date("2026-11-14T09:30:10.000Z")
        let program = T.date("2026-11-14T09:30:06.200Z")
        #expect(RelayAudibility.audibleNow(programDate: program, serverNow: now, latency: 4) == program)
        #expect(RelayAudibility.audibleNow(programDate: nil, serverNow: now, latency: 4) == T.date("2026-11-14T09:30:06.000Z"))
        // A program date far from the server's clock isn't believed.
        #expect(RelayAudibility.audibleNow(programDate: .distantPast, serverNow: now, latency: 4) == T.date("2026-11-14T09:30:06.000Z"))
        #expect(!RelayAudibility.isAudible(T.date("2026-11-14T09:30:07.000Z"), audibleNow: program))
        #expect(RelayAudibility.isAudible(T.date("2026-11-14T09:30:06.000Z"), audibleNow: program))
    }
}

@Suite("The music services on your servers")
@MainActor
struct StreamingCatalogTests {
    private final class FakeCatalog: CatalogService {
        let services: TracksideCatalogServiceList
        let found: TracksideCatalogSearch?
        let error: TracksideError?

        init(services: TracksideCatalogServiceList, found: TracksideCatalogSearch? = nil, error: TracksideError? = nil) {
            self.services = services
            self.found = found
            self.error = error
        }

        func catalogServices() async throws -> TracksideCatalogServiceList { services }

        func searchCatalog(_ query: String, types: [TracksideClient.CatalogType], services: [String], limit: Int) async throws -> TracksideCatalogSearch {
            if let error { throw error }
            return found ?? TracksideCatalogSearch()
        }

        func catalogItem(_ ref: String, cursor: String?, limit: Int?) async throws -> TracksideCatalogItem {
            throw TracksideError.notFound
        }

        func catalogLibrary(service id: String, kind: TracksideCatalogLibraryPage.Kind, cursor: String?, limit: Int) async throws -> TracksideCatalogLibraryPage {
            throw TracksideError.notFound
        }
    }

    private func services(_ extra: [TracksideCatalogService] = []) throws -> TracksideCatalogServiceList {
        var list = try TracksideJSON.decoder.decode(TracksideCatalogServiceList.self, from: TracksideFixtures.data("catalog-services.json"))
        list.services += extra
        return list
    }

    private func settle(_ catalog: StreamingCatalog, _ serverID: String) async {
        for _ in 0..<100 where catalog.services[serverID] == nil {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    @Test("Apple Music is left out, and only signed-in services that search are searchable")
    func listing() async throws {
        let native = TracksideCatalogService(id: "bapple", name: "Apple Music", logoKey: "apple_music", capabilities: [.search])
        let catalog = StreamingCatalog()
        catalog.attach(serverID: "home", name: "Home", service: FakeCatalog(services: try services([native])), features: TracksideMe.Catalog(relay: true))
        await settle(catalog, "home")
        #expect(catalog.services["home"]?.map(\.name) == ["Spotify", "Tidal", "Qobuz"])
        #expect(catalog.searchable.map { $0.service.name } == ["Spotify", "Tidal"])
        #expect(catalog.canPlayHere("home"))
        #expect(catalog.hasServices)
        catalog.detach(serverID: "home")
        #expect(!catalog.hasServices)
    }

    @Test("a server that can't reach its services says so calmly, and the others still answer")
    func problems() async throws {
        let catalog = StreamingCatalog()
        let found = try TracksideJSON.decoder.decode(TracksideCatalogSearch.self, from: TracksideFixtures.data("catalog-search.json"))
        catalog.attach(serverID: "home", name: "Home", service: FakeCatalog(services: try services(), found: found), features: TracksideMe.Catalog())
        catalog.attach(serverID: "away", name: "Away", service: FakeCatalog(services: try services(), error: .unavailable(code: "catalog_unavailable", retryAfter: 10)), features: TracksideMe.Catalog())
        await settle(catalog, "home")
        await settle(catalog, "away")
        let answer = await catalog.search("halo")
        #expect(answer.found["home"]?.tracks.count == 1)
        #expect(answer.problems["away"] == .unreachable)
        #expect(await catalog.search("  ").found.isEmpty)
        #expect(!catalog.canPlayHere("home"))
    }
}

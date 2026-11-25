import Testing
import Foundation
@testable import TracksCore

@Suite("Trackside shelves")
struct TracksideShelvesTests {
    typealias T = TracksideTesting

    private static func fixture(_ name: String) throws -> String {
        String(decoding: try TracksideFixtures.data(name), as: UTF8.self)
    }

    @Test("the server's picks fill For You, in the shapes the rest of Tracks reads")
    func picks() async throws {
        let body = try Self.fixture("picks.json")
        let stub = StubTracksideTransport(body: body)
        let songs = try #require(try await T.client(stub).serverPicks(count: 40))
        #expect(songs.map(\.title) == ["Single Ladies (Put a Ring on It)", "Don't Stop Me Now"])
        #expect(songs.first?.coverArt == "ca81rcgy3d9pfg6saekwd")
        let url = try #require(stub.requests.first?.url)
        #expect(url.path() == "/api/v1/me/picks")
        #expect(T.query(url)["count"] == "40")
        #expect(T.query(url)["tz"] == TimeZone.current.identifier)

        let picked = try T.decode(TracksideSuggestedSongs.self, body)
        #expect(picked.external == [TracksideSuggestion(title: "Irreplaceable", artist: "Beyoncé")])
    }

    @Test("a server from before picks answers nil, so the walk from similar songs stands in; other failures say so")
    func picksFromAnOlderServer() async throws {
        let notFound = try Self.fixture("error-not-found.json")
        #expect(try await T.client(StubTracksideTransport(status: 404, body: notFound)).serverPicks(count: 40) == nil)
        #expect(try await T.client(StubTracksideTransport(status: 404, body: "404 page not found")).serverPicks(count: 40) == nil)
        await #expect(throws: TracksideError.self) {
            try await T.client(StubTracksideTransport(status: 500, body: #"{"error": {"code": "internal", "message": "No."}}"#)).serverPicks(count: 40)
        }
    }

    @Test("mixes, Get More and the server's most played")
    func shelves() async throws {
        let bodies = [
            "/api/v1/me/mixes/onRepeat": try Self.fixture("mix.json"),
            "/api/v1/me/get-more": try Self.fixture("get-more.json"),
            "/api/v1/songs/top": try Self.fixture("top-songs.json"),
        ]
        let notFound = try Self.fixture("error-not-found.json")
        let stub = StubTracksideTransport { request in
            if let body = bodies[request.url?.path() ?? ""] { return (200, body, [:]) }
            return (404, notFound, [:])
        }
        let client = T.client(stub)
        #expect(try await client.mix("onRepeat").map(\.title) == ["Halo"])
        #expect(try await client.getMore().songs.count == 1)
        #expect(try await client.mostPlayed(count: 50).count == 2)
    }

    @Test("similar artists are only ones the server has, chosen before the list is cut")
    func similarArtists() async throws {
        let stub = StubTracksideTransport(body: try Self.fixture("similar-artists.json"))
        let artists = try await T.client(stub).similarArtists(to: "rgtm6gw8kzkjyq611mzzp", count: 1)
        #expect(artists.map(\.name) == ["Queen"])
        let url = try #require(stub.requests.first?.url)
        #expect(T.query(url)["in_library"] == "true")
        #expect(T.query(url)["count"] == "3")
    }
}

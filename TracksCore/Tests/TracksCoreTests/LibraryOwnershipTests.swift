import Testing
import Foundation
@testable import TracksCore

/// The engine's ownership, Get More and fetch rules as the apps reach them. The rules
/// themselves are tested in the engine (`tracks-library`); these check the Swift side hands
/// everything across and back intact.
@Suite("What you own and what to get")
struct LibraryOwnershipTests {
    @Test("an album is owned, partly owned or missing, with the songs you don't have in order")
    func albums() {
        let owned = OwnedMusic(songs: [("Havana", "Camila Cabello"), ("Never Be the Same", "Camila Cabello")])
        #expect(owned.songCount == 2)
        #expect(owned.owns(title: "HAVANA (feat. Young Thug)", artist: "Camila Cabello & Young Thug"))

        let partly = owned.album([("Never Be the Same", "Camila Cabello"), ("Real Friends", "Camila Cabello"), ("Havana", "Camila Cabello"), ("She Loves Control", "Camila Cabello")])
        #expect(partly == AlbumHoldings(state: .partlyOwned, missing: [1, 3], ownedCount: 2))
        #expect(partly.ownedShare == 0.5)
        #expect(owned.album([("Havana", "Camila Cabello")]).state == .owned)
        #expect(owned.album([("Elsewhere", "Mara Solis")]).state == .missing)
        #expect(OwnedMusic.empty.album([]).state == .missing)
    }

    @Test("counts alone judge an album", arguments: [
        (0, 10, AlbumHoldings.State.missing),
        (4, 10, .partlyOwned),
        (10, 10, .owned),
        (3, nil, .partlyOwned),
    ] as [(Int, Int?, AlbumHoldings.State)])
    func counts(owned: Int, total: Int?, state: AlbumHoldings.State) {
        #expect(AlbumHoldings.state(owned: owned, total: total) == state)
    }

    @Test("Get More leads with your artists, then artists like them, and leaves out what you own")
    func ranking() {
        let picks = GetMoreRanking.rank(
            [
                .init(id: "like", isAlbum: false, artist: "Nils Frahm"),
                .init(id: "half", isAlbum: true, artist: "Mara Solis", ownedShare: 0.5),
                .init(id: "owned", isAlbum: true, artist: "Mara Solis", ownedShare: 1),
            ],
            taste: [.init(name: "Mara Solis", plays: 40, lastPlayed: .now)],
            similar: [.init(of: "Mara Solis", name: "Nils Frahm")]
        )
        #expect(picks.map(\.id) == ["half", "like"])
        #expect(picks.map(\.reason) == [.yourArtist("Mara Solis"), .likeYourArtist("Mara Solis")])
    }

    @Test("nothing is offered to get without a server that finds songs or Lidarr")
    func fetching() {
        #expect(FetchAbility().canGet == false)
        #expect(FetchAbility(serverFinds: true).canGet)
        #expect(FetchAbility(lidarr: true).canGet)
        let both = FetchAbility(serverFinds: true, lidarr: true)
        #expect(both.route(forSongFoundOnServer: true, hasAlbum: true) == .server)
        #expect(both.route(forSongFoundOnServer: false, hasAlbum: true) == .lidarr)
        #expect(both.route(forAlbumFoundOnServer: true) == .lidarr)
        #expect(FetchAbility(serverFinds: true).route(forAlbumFoundOnServer: true) == .server)
        #expect(FetchAbility().route(forSongFoundOnServer: true, hasAlbum: true) == nil)
        #expect(FetchAbility.searchShowsFinds(returned: 60, outsideLibrary: 48))
        #expect(FetchAbility.searchShowsFinds(returned: 50, outsideLibrary: 2) == false)
    }
}

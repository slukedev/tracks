import Foundation
import Testing
import TracksCore

@Suite("Matching what someone asked Siri for")
struct SpokenMediaMatchingTests {
    typealias Reading = SpokenMedia.Reading
    typealias Candidate = SpokenMedia.Candidate

    // MARK: - Reading a request said in one go

    @Test("a kind said first or last is taken off the name", arguments: [
        ("the song Bad Guy", Reading(name: "Bad Guy", kind: .song)),
        ("The Album Blue", Reading(name: "Blue", kind: .album)),
        ("my playlist Workout", Reading(name: "Workout", kind: .playlist)),
        ("Workout playlist", Reading(name: "Workout", kind: .playlist)),
        ("the station Beats 1", Reading(name: "Beats 1", kind: .station)),
        ("Blue album", Reading(name: "Blue", kind: .album)),
    ])
    func kinds(spoken: String, reading: Reading) {
        #expect(SpokenMedia.readings(of: spoken) == [reading])
    }

    @Test("an artist alone is read as the artist")
    func artistOnly() {
        #expect(SpokenMedia.readings(of: "songs by Phoebe Bridgers") == [Reading(name: "", artist: "Phoebe Bridgers", kind: .artist)])
        #expect(SpokenMedia.readings(of: "the band Wilco") == [Reading(name: "", artist: "Wilco", kind: .artist)])
    }

    @Test("by splits off the artist, and the whole is read too for titles that have it")
    func byArtist() {
        #expect(SpokenMedia.readings(of: "Bad Guy by Billie Eilish") == [
            Reading(name: "Bad Guy", artist: "Billie Eilish"),
            Reading(name: "Bad Guy by Billie Eilish"),
        ])
        #expect(SpokenMedia.readings(of: "the album Blue by Joni Mitchell").first == Reading(name: "Blue", artist: "Joni Mitchell", kind: .album))
        #expect(SpokenMedia.readings(of: "Stand by Me").last == Reading(name: "Stand by Me"))
    }

    @Test("a playlist's or station's name keeps its by", arguments: ["the playlist Made by Me", "Made by Me playlist"])
    func playlistKeepsBy(spoken: String) {
        #expect(SpokenMedia.readings(of: spoken) == [Reading(name: "Made by Me", kind: .playlist)])
    }

    @Test("nothing to read in a blank request", arguments: ["", "   "])
    func blank(spoken: String) {
        #expect(SpokenMedia.readings(of: spoken).isEmpty)
    }

    @Test("a mood is heard when music in general is asked for, not in a title", arguments: [
        ("relaxing music", "chill" as String?),
        ("something to study to", "focus"),
        ("songs for the gym", "workout"),
        ("Love Story", nil),
        ("Dancing Queen", nil),
        ("music", nil),
    ])
    func moodRequests(spoken: String, mood: String?) {
        #expect(SpokenMedia.moodRequest(in: spoken) == mood)
    }

    // MARK: - Ranking

    @Test("the exact title beats one that only starts with it, and a version counts as exact")
    func exactTitleFirst() {
        let found = [
            Candidate(title: "Blue Jeans", artist: "Lana Del Rey", kind: .song),
            Candidate(title: "Blue (Remastered 2011)", artist: "Joni Mitchell", kind: .album),
            Candidate(title: "Into the Blue", artist: "Kylie Minogue", kind: .song),
        ]
        #expect(SpokenMedia.rank(found, name: "Blue") == [1, 0, 2])
    }

    @Test("the artist said picks between songs of the same name")
    func artistPicks() {
        let found = [
            Candidate(title: "Hello", artist: "Adele", kind: .song),
            Candidate(title: "Hello", artist: "Lionel Richie", kind: .song),
        ]
        #expect(SpokenMedia.rank(found, name: "Hello", artist: "lionel richie").first == 1)
        #expect(SpokenMedia.rank(found, name: "Hello").first == 0)
    }

    @Test("yours comes before the catalog's, the more played first")
    func yoursFirst() {
        let found = [
            Candidate(title: "Hello", artist: "Adele", kind: .song),
            Candidate(title: "Hello", artist: "Lionel Richie", kind: .song, plays: 2),
            Candidate(title: "Hello", artist: "Evanescence", kind: .song, plays: 9),
        ]
        #expect(SpokenMedia.rank(found, name: "hello") == [2, 1, 0])
    }

    // MARK: - When to ask which one

    @Test("two of yours with the same name by different artists are worth asking about")
    func asksBetweenYours() {
        let found = [
            Candidate(title: "Hello", artist: "Adele", kind: .song, isOwned: true),
            Candidate(title: "Hello", artist: "Lionel Richie", kind: .song, plays: 3),
            Candidate(title: "Hello", artist: "Evanescence", kind: .song),
        ]
        let order = SpokenMedia.rank(found, name: "Hello")
        #expect(Set(SpokenMedia.ambiguous(found, ranked: order, name: "Hello")) == [0, 1])
    }

    @Test("no question when the artist was said, only one is yours, or they're the catalog's")
    func noQuestion() {
        let yoursAndCatalog = [
            Candidate(title: "Hello", artist: "Adele", kind: .song, plays: 4),
            Candidate(title: "Hello", artist: "Lionel Richie", kind: .song),
        ]
        let catalog = [
            Candidate(title: "Hello", artist: "Adele", kind: .song),
            Candidate(title: "Hello", artist: "Lionel Richie", kind: .song),
        ]
        let bothYours = catalog.map { Candidate(title: $0.title, artist: $0.artist, kind: .song, isOwned: true) }
        for found in [yoursAndCatalog, catalog] {
            #expect(SpokenMedia.ambiguous(found, ranked: SpokenMedia.rank(found, name: "Hello"), name: "Hello").isEmpty)
        }
        let order = SpokenMedia.rank(bothYours, name: "Hello", artist: "Adele")
        #expect(SpokenMedia.ambiguous(bothYours, ranked: order, name: "Hello", artist: "Adele").isEmpty)
    }

    @Test("the same song twice, from your library and your server, isn't a question")
    func sameArtistTwice() {
        let found = [
            Candidate(title: "Motion Sickness", artist: "Phoebe Bridgers", kind: .song, isOwned: true),
            Candidate(title: "Motion Sickness", artist: "Phoebe Bridgers", kind: .song, isOwned: true),
        ]
        #expect(SpokenMedia.ambiguous(found, ranked: [0, 1], name: "Motion Sickness").isEmpty)
    }

    // MARK: - Apple Music links

    @Test("a link names its song, album, artist, playlist or station", arguments: [
        ("https://music.apple.com/us/album/blue/1440817537?i=1440817542", SpokenMedia.Kind.song, "1440817542"),
        ("https://music.apple.com/us/album/blue/1440817537", .album, "1440817537"),
        ("https://music.apple.com/gb/song/motion-sickness/1440958417", .song, "1440958417"),
        ("https://music.apple.com/us/artist/phoebe-bridgers/1159418213", .artist, "1159418213"),
        ("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb", .playlist, "pl.f4d106fed2bd41149aaacabb233eb5eb"),
        ("https://music.apple.com/us/station/apple-music-1/ra.978194965", .station, "ra.978194965"),
    ])
    func links(link: String, kind: SpokenMedia.Kind, id: String) throws {
        let url = try #require(URL(string: link))
        let item = try #require(AppleMusicURL.item(in: url))
        #expect(item == AppleMusicURL.Item(kind: kind, id: id))
    }

    @Test("anything else isn't a link to play", arguments: [
        "https://example.com/us/album/blue/1440817537",
        "https://music.apple.com/us/browse",
        "https://music.apple.com/us/album/blue",
        "https://music.apple.com/us/playlist/todays-hits/1234",
    ])
    func notLinks(link: String) throws {
        let url = try #require(URL(string: link))
        #expect(AppleMusicURL.item(in: url) == nil)
    }
}

import Foundation
import Testing
@testable import TracksCore

struct MusicGenreTests {
    let now = Date(timeIntervalSinceReferenceDate: 800_000_000)

    @Test("the ways songs are tagged fold into the genre Play has a page for", arguments: [
        ("Hip-Hop/Rap", "hip-hop"),
        ("Rap", "hip-hop"),
        ("R&B/Soul", "r-b"),
        ("Soul", "r-b"),
        ("Indie Pop", "alternative"),
        ("Dance", "electronic"),
        ("House", "electronic"),
        ("Singer/Songwriter", "singer-songwriter"),
        ("Christian & Gospel", "christian"),
        ("Música Mexicana", "latin"),
        ("K-Pop", "k-pop"),
        ("Pop", "pop"),
    ])
    func folding(tag: String, expected: String) {
        #expect(MusicGenre.of(genre: tag)?.id == expected)
    }

    @Test("tags Play has no page for, and none at all, belong nowhere")
    func unknown() {
        #expect(MusicGenre.of(genre: "Spoken Word") == nil)
        #expect(MusicGenre.of(genre: "Music") == nil)
        #expect(MusicGenre.of(genre: nil) == nil)
    }

    @Test("every genre has its own key and its own Apple Music id")
    func unique() {
        #expect(Set(MusicGenre.all.map(\.id)).count == MusicGenre.all.count)
        #expect(Set(MusicGenre.all.map(\.appleMusicID)).count == MusicGenre.all.count)
    }

    /// Ten plays of a rock song, three of a hip-hop one, one of a song with no genre.
    func history() -> ListeningHistory {
        var plays: [CaptureStat] = []
        let songs: [(title: String, artist: String, genre: String?, plays: Int)] = [
            ("Loud", "Band", "Rock", 10),
            ("Bars", "MC", "Hip-Hop/Rap", 3),
            ("Mystery", "Nobody", nil, 1),
        ]
        var metadata: [String: SongMetadata] = [:]
        for song in songs {
            for play in 0..<song.plays {
                plays.append(CaptureStat(songKey: song.title, songID: song.title, title: song.title, artistName: song.artist, capturedAt: now.addingTimeInterval(-Double(play + 1) * 86_400)))
            }
            if let genre = song.genre {
                metadata[SongIdentity.key(title: song.title, artistName: song.artist)] = SongMetadata(genre: genre, releaseYear: nil)
            }
        }
        return ListeningHistory(plays).with(artistArtwork: [:], songMetadata: metadata)
    }

    @Test("genres you play come first, the most played first, then the rest in their usual order")
    func ranking() throws {
        let profiles = MusicGenre.profiles(in: history(), now: now)
        #expect(profiles.count == MusicGenre.all.count)
        #expect(profiles.prefix(2).map(\.genre.id) == ["rock", "hip-hop"])
        #expect(profiles.prefix(2).map(\.plays) == [10, 3])
        let first = try #require(profiles.dropFirst(2).first)
        #expect(first.genre.id == "pop")
        #expect(first.plays == 0)
        #expect(first.songs.isEmpty)
    }

    @Test("a genre's profile has your songs and artists in it, and nothing from other genres")
    func profile() throws {
        let rock = try #require(MusicGenre.profiles(in: history(), now: now).first)
        #expect(rock.songs.map(\.title) == ["Loud"])
        #expect(rock.artists.map(\.name) == ["Band"])
        #expect(rock.artists.first?.plays == 10)
    }

    @Test("a genre's station plays your songs in it, and new finds from its charts")
    func station() throws {
        let find = MixSong(songIdentity: "find", songID: "find", title: "Find", artistName: "New Band", albumTitle: nil, artworkURL: nil, plays: 0, lastHeard: .distantPast, genre: "Rock")
        let rock = try #require(MusicGenre.all.first { $0.id == "rock" })
        let mix = LiveMix.genre(rock, from: history(), newFinds: [find], now: now, seed: 1)
        let titles = Set(mix.candidates.map(\.song.title))
        #expect(titles == ["Loud", "Find"])
        #expect(mix.candidates.first { $0.song.title == "Find" }?.isNew == true)
        // Only one song of yours in it: new finds for half the picks.
        #expect(mix.newShare == 0.5)
    }

    @Test("with none of your songs in a genre, its station is all new finds")
    func stationFromNothing() throws {
        let jazz = try #require(MusicGenre.all.first { $0.id == "jazz" })
        let mix = LiveMix.genre(jazz, from: history(), now: now, seed: 2)
        #expect(mix.isEmpty)
        #expect(mix.newShare == 1)
    }

    /// A history of songs in each genre named, each by its own artist, each played a few times.
    func library(_ genres: [String: Int]) -> ListeningHistory {
        var plays: [CaptureStat] = []
        var metadata: [String: SongMetadata] = [:]
        for (genre, count) in genres {
            for index in 0..<count {
                let title = "\(genre) \(index)"
                let artist = "\(genre) Artist \(index)"
                for play in 0..<3 {
                    plays.append(CaptureStat(songKey: title, songID: title, title: title, artistName: artist, capturedAt: now.addingTimeInterval(-Double(play + 2) * 86_400)))
                }
                metadata[SongIdentity.key(title: title, artistName: artist)] = SongMetadata(genre: genre, releaseYear: nil)
            }
        }
        return ListeningHistory(plays).with(artistArtwork: [:], songMetadata: metadata)
    }

    func find(_ title: String, genre: String?) -> MixSong {
        MixSong(songIdentity: title, songID: title, title: title, artistName: "\(title) Band", albumTitle: nil, artworkURL: nil, plays: 0, lastHeard: .distantPast, genre: genre)
    }

    @Test("a genre's station leaves out its chart's songs filed under another genre")
    func chartSongsOutsideTheGenre() throws {
        let rock = try #require(MusicGenre.all.first { $0.id == "rock" })
        let mix = LiveMix.genre(rock, from: history(), newFinds: [find("Ballad", genre: "Pop"), find("Riff", genre: "Hard Rock"), find("Untagged", genre: nil)], now: now, seed: 1)
        #expect(Set(mix.candidates.filter(\.isNew).map(\.song.title)) == ["Riff", "Untagged"])
    }

    // Skipping a lot on Hip-Hop once played songs from every genre: two skips in a row in
    // "Hip-Hop/Rap" turned the station away from hip-hop itself.
    @Test("skipping song after song never takes a genre's station off its genre", arguments: 1...12 as ClosedRange<UInt64>)
    func skippingStaysInTheGenre(seed: UInt64) throws {
        let hipHop = try #require(MusicGenre.all.first { $0.id == "hip-hop" })
        let finds = (0..<10).map { find("Chart \($0)", genre: $0.isMultiple(of: 2) ? "Hip-Hop/Rap" : "Pop") }
        var mix = LiveMix.genre(hipHop, from: library(["Hip-Hop/Rap": 40, "Trap": 4, "R&B/Soul": 20, "Country": 20]), newFinds: finds, now: now, seed: seed)
        for _ in 0..<120 {
            let picked = mix.next()
            let song = try #require(picked)
            let candidate = try #require(mix.candidates.first { $0.song.songIdentity == song.songIdentity })
            #expect(hipHop.contains(genre: candidate.genre), "\(song.title) isn't hip-hop")
            mix.note(.skipped, of: song.songIdentity)
            // A kind of it, like Trap, can make way for the rest; the genre itself never does.
            if case .awayFromGenre("Hip-Hop/Rap") = mix.steering { Issue.record("turned away from the station's own genre") }
        }
    }

    @Test("a small genre's station plays all of its own before reaching into close genres, and says so", arguments: 1...12 as ClosedRange<UInt64>)
    func smallStationWidens(seed: UInt64) throws {
        let jazz = try #require(MusicGenre.all.first { $0.id == "jazz" })
        var mix = LiveMix.genre(jazz, from: library(["Jazz": 5, "Blues": 6, "Rock": 6]), now: now, seed: seed)
        let first = (0..<5).compactMap { _ in mix.next() }
        #expect(first.allSatisfy { $0.title.hasPrefix("Jazz") })
        #expect(mix.steering == nil)
        let later = (0..<6).compactMap { _ in mix.next() }
        #expect(later.allSatisfy { $0.title.hasPrefix("Blues") })
        #expect(mix.steering == .wideningBeyond(jazz))
        let widened = try #require(later.first)
        #expect(mix.reason(for: widened.songIdentity) == .closeTo(jazz))
    }

    @Test("a station with only songs in close genres has nothing of its own to play")
    func onlyCloseGenres() throws {
        let jazz = try #require(MusicGenre.all.first { $0.id == "jazz" })
        #expect(LiveMix.genre(jazz, from: library(["Blues": 6]), now: now, seed: 1).isEmpty)
    }
}

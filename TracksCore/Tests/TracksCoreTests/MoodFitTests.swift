import Foundation
import Testing
@testable import TracksCore

struct MoodFitTests {
    let now = Date(timeIntervalSinceReferenceDate: 800_000_000)

    func song(_ title: String, by artist: String, album: String? = nil, genre: String?) -> MixSong {
        MixSong(
            songIdentity: SongIdentity.key(title: title, artistName: artist), songID: "id.\(title)", title: title,
            artistName: artist, albumTitle: album, artworkURL: nil, plays: 0, lastHeard: .distantPast, genre: genre
        )
    }

    @Test("lively moods never take classical, ambient or sleep music, however it's tagged", arguments: [Mood.energy, .workout, .party])
    func livelyMoodsLeaveOutStillMusic(mood: Mood) {
        let still = [
            song("Piano Sonata No. 14 in C-Sharp Minor, Op. 27 No. 2: I. Adagio sostenuto", by: "Daniel Barenboim", genre: "Classical"),
            song("Clair de lune", by: "Claude Debussy", genre: nil),
            song("Experience", by: "Ludovico Einaudi", genre: "Classical Crossover"),
            song("Cello Suite No. 1 in G Major, BWV 1007: Prélude", by: "Yo-Yo Ma", genre: "Pop"),
            song("Spiegel im Spiegel", by: "London Philharmonic Orchestra", genre: nil),
            song("An Ending (Ascent)", by: "Brian Eno", genre: "Ambient"),
            song("Deep Sleep Rain Sounds", by: "Sleep Sounds", genre: "New Age"),
            song("Weightless", by: "Marconi Union", genre: "Ambient Electronic"),
        ]
        #expect(MoodMix.fitting(still, mood).isEmpty)
    }

    @Test("a genre that names a lively mood keeps its songs, slow-sounding titles or not")
    func genreOutweighsTitle() {
        let anthem = song("Adagio for Strings", by: "Tiësto", genre: "Dance")
        #expect(Mood.energy.fit(genre: anthem.genre, title: anthem.title, artist: anthem.artistName) == .suits)
        #expect(MoodMix.fitting([anthem], .energy) == [anthem])
    }

    @Test("quiet takes don't lift a workout, and club edits don't settle a sleep")
    func versions() {
        #expect(Mood.workout.fit(genre: "Pop", title: "Shape of You (Acoustic)", artist: "Ed Sheeran") == .clashes)
        #expect(Mood.sleep.fit(genre: nil, title: "Levels (Extended Mix)", artist: "Avicii") == .clashes)
        #expect(Mood.focus.fit(genre: "Death Metal", title: "Hammer Smashed Face", artist: "Cannibal Corpse") == .clashes)
    }

    @Test("names that only look classical stay in", arguments: [
        ("Symphony", "Clean Bandit"), ("Mr. Blue Sky", "Electric Light Orchestra"), ("Bach to the Future", "DJ Rhythm"),
    ])
    func lookalikes(title: String, artist: String) {
        #expect(Mood.energy.fit(genre: nil, title: title, artist: artist) == .unknown)
    }

    @Test("a mood's new finds lose the songs that are its opposite, and the ones it names come up most")
    func moodNewFinds() throws {
        let finds = [
            song("Lift", by: "Rock Band", genre: "Rock"),
            song("Unlabelled", by: "Someone", genre: nil),
            song("Nocturne in E-Flat Major, Op. 9 No. 2", by: "Frédéric Chopin", genre: "Classical"),
        ]
        let mix = LiveMix.mood(.energy, from: ListeningHistory([]), newFinds: finds, now: now, seed: 1)
        #expect(Set(mix.candidates.map(\.song.title)) == ["Lift", "Unlabelled"])
        let lift = try #require(mix.candidates.first { $0.song.title == "Lift" })
        let unlabelled = try #require(mix.candidates.first { $0.song.title == "Unlabelled" })
        #expect(lift.weight > unlabelled.weight)
    }

    @Test("a calm genre's song that sits still is never Energy from the history either")
    func historyLeavesOutStillTitles() {
        let titles = ["Run", "Gymnopédie No. 1"]
        let plays = titles.map { CaptureStat(songKey: $0, songID: $0, title: $0, artistName: "A", capturedAt: now.addingTimeInterval(-3_600)) }
        let metadata = Dictionary(uniqueKeysWithValues: titles.map { (SongIdentity.key(title: $0, artistName: "A"), SongMetadata(genre: "Electronic", releaseYear: nil)) })
        let history = ListeningHistory(plays).with(artistArtwork: [:], songMetadata: metadata)
        #expect(MoodMix.songs(for: .energy, in: history, now: now).map(\.title) == ["Run"])
    }
}

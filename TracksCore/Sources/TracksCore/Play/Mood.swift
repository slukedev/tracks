import Foundation

/// What someone's in the mood for: Apple Music's Moods, with a mix from their own history
/// alongside Apple's playlists and stations for it.
public enum Mood: String, CaseIterable, Sendable, Identifiable {
    case feelGood, energy, workout, focus, chill, love, drive, party, sleep, heartbreak

    public var id: String { rawValue }

    /// What to search Apple Music's catalog for.
    public var searchTerm: String {
        switch self {
        case .feelGood: "Feel Good"
        case .energy: "Energy"
        case .workout: "Workout"
        case .focus: "Focus"
        case .chill: "Chill"
        case .love: "Love Songs"
        case .drive: "Driving"
        case .heartbreak: "Heartbreak"
        case .party: "Party"
        case .sleep: "Sleep"
        }
    }

    /// Parts of Apple Music genre names that suit the mood. The history knows each song's
    /// genre and nothing about its feel, so this is the honest reach of a mood from it.
    var genreHints: [String] {
        switch self {
        case .feelGood: ["pop", "funk", "soul", "disco", "reggae", "k-pop", "motown"]
        case .energy: ["rock", "alternative", "punk", "metal", "hip-hop", "rap", "dance", "electronic"]
        case .workout: ["hip-hop", "rap", "dance", "electronic", "house", "edm", "metal", "hard rock", "drum"]
        case .focus: ["classical", "ambient", "instrumental", "soundtrack", "new age", "lo-fi", "jazz", "piano"]
        case .chill: ["ambient", "lo-fi", "jazz", "r&b", "soul", "singer/songwriter", "acoustic", "chill", "indie"]
        case .love: ["r&b", "soul", "singer/songwriter", "pop", "latin", "k-pop", "vocal"]
        case .drive: ["rock", "alternative", "indie", "country", "hip-hop", "rap", "synth", "electronic", "americana"]
        case .heartbreak: ["singer/songwriter", "alternative", "r&b", "country", "folk", "indie", "blues"]
        case .party: ["dance", "pop", "hip-hop", "rap", "latin", "house", "electronic", "reggaeton", "disco"]
        case .sleep: ["ambient", "classical", "new age", "piano", "sleep", "meditation"]
        }
    }

    private var clashingFeels: Set<SongFeel> {
        switch self {
        case .energy, .workout, .party: [.still, .soft]
        case .drive, .feelGood: [.still]
        case .focus, .sleep: [.intense, .club]
        case .chill: [.intense]
        case .love, .heartbreak: [.intense]
        }
    }

    func suits(genre: String) -> Bool {
        fit(genre: genre) == .suits
    }

    public func fit(genre: String?, title: String = "", artist: String = "", album: String? = nil) -> Fit {
        let genreFeels = genre.map(SongFeel.feels(ofGenre:)) ?? []
        if !genreFeels.isDisjoint(with: clashingFeels) { return .clashes }
        let textFeels = SongFeel.feels(title: title, artist: artist, album: album)
        if textFeels.contains(.still), clashingFeels.contains(.still) { return .clashes }
        let folded = genre.map(StatsCalculator.folded)
        if let folded, genreHints.contains(where: folded.contains) { return .suits }
        return textFeels.isDisjoint(with: clashingFeels) ? .unknown : .clashes
    }

    public enum Fit: Sendable, Equatable {
        case suits
        case unknown
        case clashes
    }
}

/// The feel a song gives away by its genre or its name, as far as either says one. The
/// history has no tempo or loudness for a song, so these are what there is to go on.
enum SongFeel: Sendable, Hashable {
    case still
    case soft
    case intense
    case club

    static func feels(ofGenre genre: String) -> Set<SongFeel> {
        let folded = StatsCalculator.folded(genre)
        var feels = Set<SongFeel>()
        if stillGenres.contains(where: folded.contains) { feels.insert(.still) }
        if intenseGenres.contains(where: folded.contains) { feels.insert(.intense) }
        return feels
    }

    static func feels(title: String, artist: String, album: String?) -> Set<SongFeel> {
        let title = words(title)
        let artist = words(artist)
        let album = album.map(words) ?? ""
        var feels = Set<SongFeel>()
        if isClassicalWork(title) || isClassicalWork(album) || classicalArtists.contains(where: { artist.contains(" \($0) ") })
            || stillWords.contains(where: { title.contains(" \($0) ") || album.contains(" \($0) ") }) {
            feels.insert(.still)
        }
        if softWords.contains(where: { title.contains(" \($0) ") }) { feels.insert(.soft) }
        if clubWords.contains(where: { title.contains(" \($0) ") }) { feels.insert(.club) }
        return feels
    }

    private static func isClassicalWork(_ text: String) -> Bool {
        if classicalForms.contains(where: { text.contains(" \($0) ") }) { return true }
        return text.contains(catalogNumber) || text.contains(key)
    }

    /// Folded, with everything but letters and digits as single spaces, and a space at each
    /// end, so a phrase is found as whole words by looking for it with spaces around it.
    private static func words(_ text: String) -> String {
        let folded = StatsCalculator.folded(text)
        let spaced = folded.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? String($0) : " " }.joined()
        return " " + spaced.split(separator: " ").joined(separator: " ") + " "
    }

    private static let stillGenres = [
        "classical", "opera", "orchestral", "baroque", "chamber music", "choral", "ambient", "new age",
        "meditation", "sleep", "lullab", "easy listening", "film score", "original score", "nature sounds",
        "healing", "relaxation", "wellness", "spoken word",
    ]
    private static let intenseGenres = [
        "metal", "punk", "hardcore", "grindcore", "drill", "dubstep", "hardstyle", "drum & bass",
        "drum and bass", "drum-and-bass", "techno", "trap", "industrial", "hard rock", "screamo",
    ]
    private static let classicalForms = [
        "sonata", "sonatas", "concerto", "concertos", "nocturne", "nocturnes", "fugue", "requiem", "scherzo",
        "partita", "cantata", "mazurka", "polonaise", "etude", "etudes", "symphony no", "string quartet",
        "piano trio", "gymnopedie", "gymnopedies", "suite no", "bwv",
    ]
    private static let classicalArtists = [
        "philharmonic", "philharmonia", "symphony orchestra", "chamber orchestra", "sinfonia", "staatskapelle",
        "concertgebouw", "string quartet", "baroque ensemble", "johann sebastian bach", "beethoven", "mozart",
        "chopin", "debussy", "vivaldi", "tchaikovsky", "satie", "brahms", "schubert", "handel", "haydn", "liszt",
        "rachmaninoff", "rachmaninov", "einaudi", "max richter", "olafur arnalds", "hans zimmer", "yiruma",
    ]
    private static let stillWords = [
        "lullaby", "lullabies", "white noise", "brown noise", "pink noise", "rain sounds", "sleep music",
        "meditation", "binaural", "solfeggio", "432 hz", "528 hz", "spa music",
    ]
    private static let softWords = ["acoustic", "unplugged", "piano version", "slowed", "stripped", "lullaby version"]
    private static let clubWords = ["club mix", "extended mix", "festival mix", "bass boosted", "hardstyle", "rave mix"]

    /// "Op 27", "BWV 1007", "K 525", "Hob XVI", "RV 269", as words.
    nonisolated(unsafe) private static let catalogNumber = /\s(op|opus|bwv|kv|k|hob|rv|hwv|wwv|woo)\s(\d+|[ivx]+)\s/
    /// "In C minor", "in E flat major".
    nonisolated(unsafe) private static let key = /\sin\s[a-g](\s(flat|sharp))?\s(major|minor)\s/
}

/// Which way to steer a mood as it plays, by what each song's genre says of it.
public enum MoodLean: String, CaseIterable, Sendable, Identifiable {
    case calmer, livelier, instrumental

    public var id: String { rawValue }

    /// How much likelier a song in this genre is: up to several times for one that leans the
    /// way asked, down to a fraction for one that leans the other way. Even for a song whose
    /// genre says nothing either way.
    public func factor(genre: String?) -> Double {
        guard let genre else { return 1 }
        switch self {
        case .calmer, .livelier:
            guard let energy = RadioMoment.energy(ofGenre: genre) else { return 1 }
            let toward = self == .livelier ? energy - 0.5 : 0.5 - energy
            return exp(Self.pull * toward)
        case .instrumental:
            return ListeningRoom.isInstrumental(genre) ? Self.instrumentalLift : Self.vocalDrop
        }
    }

    /// e to the power of this, at the far end: about eleven times.
    static let pull = 4.8
    static let instrumentalLift = 4.0
    static let vocalDrop = 0.35
}

public enum MoodMix {
    /// Under this many songs of your own, a mood leans on new finds.
    public static let fewSongs = 12

    /// Songs from the history in genres that suit the mood, the most played and most recent
    /// first, then sequenced for the day. Leaves out skipped and suggested-less songs, and
    /// anything whose genre hasn't been looked up.
    public static func songs(
        for mood: Mood,
        in history: ListeningHistory,
        signals: ListeningSignals = ListeningSignals(),
        limit: Int = MixBuilder.mixLength,
        now: Date = .now,
        calendar: Calendar = .current
    ) -> [MixSong] {
        let top = scored(for: mood, in: history, signals: signals, now: now, calendar: calendar)
            .sorted { $0.score == $1.score ? $0.song.id < $1.song.id : $0.score > $1.score }
            .prefix(limit)
            .map(\.song)
        return FreshShuffle.order(
            Array(top),
            artist: \.artistKey,
            seed: FreshShuffle.dailySeed(for: now, calendar: calendar, salt: "mood.\(mood.rawValue)")
        )
    }

    /// Every song of yours that suits the mood, scored by plays and how recently it was heard.
    static func scored(
        for mood: Mood,
        in history: ListeningHistory,
        signals: ListeningSignals,
        now: Date,
        calendar: Calendar = .current
    ) -> [(song: MixSong, score: Double)] {
        guard !history.isEmpty else { return [] }
        let halfLife: TimeInterval = 120 * 24 * 60 * 60
        return MixBuilder.aggregate(history, signals: signals, now: now, calendar: calendar).compactMap { aggregate in
            guard let genre = history.songMetadata[aggregate.identity]?.genre, mood.suits(genre: genre),
                  mood.fit(genre: nil, title: aggregate.song.title, artist: aggregate.song.artistName, album: aggregate.song.albumTitle) != .clashes
            else { return nil }
            let recency = pow(0.5, max(0, now.timeIntervalSince(aggregate.lastHeard)) / halfLife)
            return (aggregate.song, log2(1 + Double(aggregate.dates.count)) * (0.5 + recency))
        }
    }

    public static func fitting(_ songs: [MixSong], _ mood: Mood) -> [MixSong] {
        songs.filter { mood.fit(genre: $0.genre, title: $0.title, artist: $0.artistName, album: $0.albumTitle) != .clashes }
    }
}

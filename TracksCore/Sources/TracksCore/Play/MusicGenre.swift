import Foundation

/// A genre Play has a page for, as Apple Music groups its catalog: one family of the genres
/// songs are tagged with, so "Hip-Hop/Rap", "Rap" and "Trap" all count as Hip-Hop.
public struct MusicGenre: Sendable, Hashable, Identifiable {
    /// A stable key, like "hip-hop".
    public let id: String
    /// Its name in English, as Apple Music spells it. The app says it in the person's language.
    public let name: String
    /// Apple Music's id for the genre, for its charts.
    public let appleMusicID: String
    /// The genre families, as ``LiveMix`` folds them, that belong to it.
    let families: Set<String>

    init(_ id: String, _ name: String, appleMusicID: String, families: Set<String>) {
        self.id = id
        self.name = name
        self.appleMusicID = appleMusicID
        self.families = families.union([id])
    }

    /// Whether a song tagged with this genre belongs here.
    public func contains(genre: String?) -> Bool {
        guard let genre, let key = LiveMix.genreKey(genre) else { return false }
        return families.contains(key) || families.contains(StatsCalculator.folded(genre).replacingOccurrences(of: " ", with: "-"))
    }

    /// The genre a song's tag belongs to, if Play has a page for it.
    public static func of(genre: String?) -> MusicGenre? {
        all.first { $0.contains(genre: genre) }
    }

    /// The genres closest to this one, the closest first: where its station reaches once it
    /// has played all of its own songs.
    public var neighbors: [MusicGenre] {
        (Self.neighborIDs[id] ?? []).compactMap { id in Self.all.first { $0.id == id } }
    }

    private static let neighborIDs: [String: [String]] = [
        "pop": ["k-pop", "r-b", "electronic"],
        "hip-hop": ["r-b", "afrobeats"],
        "r-b": ["hip-hop", "pop", "afrobeats"],
        "rock": ["alternative", "metal", "blues"],
        "alternative": ["rock", "singer-songwriter"],
        "electronic": ["pop"],
        "country": ["singer-songwriter", "blues"],
        "latin": ["pop", "reggae"],
        "k-pop": ["pop"],
        "metal": ["rock"],
        "jazz": ["blues", "r-b"],
        "singer-songwriter": ["alternative", "country"],
        "classical": ["soundtrack"],
        "afrobeats": ["r-b", "hip-hop", "reggae"],
        "reggae": ["afrobeats", "latin"],
        "blues": ["jazz", "rock", "r-b"],
        "soundtrack": ["classical"],
        "christian": ["pop", "singer-songwriter"],
    ]

    /// Whether a song found for the genre, as on its charts, really is in it: Apple's charts
    /// for a genre carry songs filed under others. One with no genre to say is taken on the
    /// chart's word.
    func admits(_ song: MixSong) -> Bool {
        song.genre.map { contains(genre: $0) } ?? true
    }

    /// Every genre, in Apple Music's usual order of how widely it's played.
    public static let all: [MusicGenre] = [
        MusicGenre("pop", "Pop", appleMusicID: "14", families: ["pop", "pop-rock", "teen-pop", "adult-contemporary", "dance-pop", "synth-pop"]),
        MusicGenre("hip-hop", "Hip-Hop", appleMusicID: "18", families: ["hip-hop", "rap", "trap", "hip-hop-rap", "drill", "west-coast-rap", "east-coast-rap", "underground-rap", "alternative-rap"]),
        MusicGenre("r-b", "R&B", appleMusicID: "15", families: ["r&b", "r-b", "rnb", "soul", "neo-soul", "contemporary-r&b", "funk", "motown"]),
        MusicGenre("rock", "Rock", appleMusicID: "21", families: ["rock", "hard-rock", "classic-rock", "punk", "prog-rock", "arena-rock", "rock-&-roll", "rock-and-roll", "punk-rock", "psychedelic"]),
        MusicGenre("alternative", "Alternative", appleMusicID: "20", families: ["alternative", "indie", "indie-rock", "indie-pop", "alt", "alternative-rock", "new-wave", "grunge", "emo", "shoegaze", "post-punk"]),
        MusicGenre("electronic", "Electronic", appleMusicID: "7", families: ["electronic", "electronica", "dance", "house", "techno", "edm", "trance", "dubstep", "drum-&-bass", "drum-and-bass", "ambient", "downtempo", "idm", "breakbeat", "garage", "uk-garage"]),
        MusicGenre("country", "Country", appleMusicID: "6", families: ["country", "americana", "bluegrass", "contemporary-country", "urban-cowboy", "honky-tonk", "outlaw-country"]),
        MusicGenre("latin", "Latin", appleMusicID: "12", families: ["latin", "reggaeton", "urbano-latino", "salsa", "latin-pop", "bachata", "cumbia", "regional-mexicano", "latin-urban", "musica-mexicana", "bossa-nova", "tropical"]),
        MusicGenre("k-pop", "K-Pop", appleMusicID: "51", families: ["k-pop", "kpop", "korean"]),
        MusicGenre("metal", "Metal", appleMusicID: "1153", families: ["metal", "heavy-metal", "death-metal", "black-metal", "metalcore", "thrash-metal", "nu-metal", "hardcore"]),
        MusicGenre("jazz", "Jazz", appleMusicID: "11", families: ["jazz", "vocal-jazz", "smooth-jazz", "bebop", "big-band", "swing", "cool-jazz", "fusion"]),
        MusicGenre("singer-songwriter", "Singer/Songwriter", appleMusicID: "10", families: ["singer", "singer-songwriter", "folk", "folk-rock", "contemporary-folk", "acoustic"]),
        MusicGenre("classical", "Classical", appleMusicID: "5", families: ["classical", "opera", "orchestral", "baroque", "chamber-music", "piano", "contemporary-classical", "romantic-era", "modern-era"]),
        MusicGenre("afrobeats", "Afrobeats", appleMusicID: "1235", families: ["afrobeats", "afro-beats", "afropop", "afro-pop", "african", "amapiano", "afrobeat", "highlife"]),
        MusicGenre("reggae", "Reggae", appleMusicID: "24", families: ["reggae", "dancehall", "ska", "dub", "roots-reggae"]),
        MusicGenre("blues", "Blues", appleMusicID: "2", families: ["blues", "blues-rock", "electric-blues", "delta-blues"]),
        MusicGenre("soundtrack", "Soundtrack", appleMusicID: "16", families: ["soundtrack", "soundtracks", "film-score", "score", "original-score", "musicals", "tv-soundtrack", "video-game"]),
        MusicGenre("christian", "Christian", appleMusicID: "22", families: ["christian", "gospel", "christian-&-gospel", "worship", "praise-&-worship", "ccm"]),
    ]

    /// Every genre with what you play of it, the most played first; the ones never played
    /// after them, in their usual order.
    public static func profiles(
        in history: ListeningHistory,
        signals: ListeningSignals = ListeningSignals(),
        now: Date = .now,
        calendar: Calendar = .current
    ) -> [GenreProfile] {
        // Each tag is looked up once: a history has thousands of songs and a few dozen tags.
        var byTag: [String: String?] = [:]
        var songs: [String: [MixBuilder.Aggregate]] = [:]
        for aggregate in MixBuilder.aggregate(history, signals: signals, now: now, calendar: calendar) {
            guard let tag = history.songMetadata[aggregate.identity]?.genre else { continue }
            let id: String?
            if let known = byTag[tag] {
                id = known
            } else {
                id = of(genre: tag)?.id
                byTag[tag] = id
            }
            if let id { songs[id, default: []].append(aggregate) }
        }
        let order = Dictionary(uniqueKeysWithValues: all.enumerated().map { ($1.id, $0) })
        return all
            .map { genre in GenreProfile(genre: genre, songs: songs[genre.id] ?? [], artwork: history.artistArtwork) }
            .sorted { lhs, rhs in
                lhs.plays == rhs.plays ? order[lhs.genre.id, default: 0] < order[rhs.genre.id, default: 0] : lhs.plays > rhs.plays
            }
    }
}

/// What you play of a genre: how much, the songs and the artists.
public struct GenreProfile: Sendable, Identifiable {
    public let genre: MusicGenre
    /// Every play of a song in it.
    public let plays: Int
    /// Your songs in it, the most played first, the one heard last breaking a tie.
    public let songs: [MixSong]
    /// Your artists in it, the most played first.
    public let artists: [FavoriteArtist]

    public var id: String { genre.id }

    init(genre: MusicGenre, songs aggregates: [MixBuilder.Aggregate], artwork: [String: String]) {
        self.genre = genre
        let sorted = aggregates.sorted {
            $0.dates.count == $1.dates.count ? $0.lastHeard > $1.lastHeard : $0.dates.count > $1.dates.count
        }
        plays = aggregates.reduce(0) { $0 + $1.dates.count }
        songs = sorted.prefix(GenreProfile.songLimit).map(\.song)
        var byArtist: [String: (name: String, plays: Int, cover: String?)] = [:]
        for aggregate in sorted {
            let key = StatsCalculator.folded(aggregate.song.artistName)
            var artist = byArtist[key] ?? (aggregate.song.artistName, 0, aggregate.song.artworkURL)
            artist.plays += aggregate.dates.count
            byArtist[key] = artist
        }
        artists = byArtist
            .sorted { $0.value.plays == $1.value.plays ? $0.key < $1.key : $0.value.plays > $1.value.plays }
            .prefix(GenreProfile.artistLimit)
            .map { FavoriteArtist(id: $0.key, name: $0.value.name, plays: $0.value.plays, artworkURL: artwork[$0.key] ?? $0.value.cover) }
    }

    static let songLimit = 50
    static let artistLimit = 15
}

extension LiveMix {
    /// A genre's station: your songs in it, the more played the likelier, resting the ones
    /// heard lately, and new finds from Apple Music's charts for it. The fewer songs of yours
    /// are in it, the more new finds it plays. It plays only the genre: a chart's song filed
    /// under another is left out, skips never turn it away from the genre, and only once it
    /// has played every song of the genre does it reach into your songs in the genres closest
    /// to it, and says so. See ``LiveMix/anchor``.
    public static func genre(
        _ genre: MusicGenre,
        from history: ListeningHistory,
        signals: ListeningSignals = ListeningSignals(),
        newFinds: [MixSong] = [],
        now: Date = .now,
        calendar: Calendar = .current,
        seed: UInt64
    ) -> LiveMix {
        let rested = now.addingTimeInterval(-restPeriod)
        let neighbors = genre.neighbors
        var yours: [Candidate] = []
        var nearby: [Candidate] = []
        for aggregate in MixBuilder.aggregate(history, signals: signals, now: now, calendar: calendar) {
            let metadata = history.songMetadata[aggregate.identity]
            let isIn = genre.contains(genre: metadata?.genre)
            guard isIn || neighbors.contains(where: { $0.contains(genre: metadata?.genre) }) else { continue }
            let candidate = Candidate(
                song: aggregate.song,
                weight: weight(plays: aggregate.dates.count, lastHeard: aggregate.lastHeard, now: now) * freshness(lastHeard: aggregate.lastHeard, now: now),
                isResting: aggregate.lastHeard >= rested,
                genre: metadata?.genre,
                releaseYear: metadata?.releaseYear
            )
            if isIn { yours.append(candidate) } else { nearby.append(candidate.nearby(genre)) }
        }
        let known = Set(yours.map(\.song.songIdentity))
        let new = newFinds
            .filter { genre.admits($0) && !known.contains($0.songIdentity) && !signals.excludes($0.songIdentity, now: now) }
            .map { Candidate(song: $0, weight: 1, isNew: true) }
        let share = switch yours.count {
        case 0: 1.0
        case ..<genreFewSongs: 0.5
        default: 0.3
        }
        let found = Set(new.map(\.song.songIdentity))
        return LiveMix(candidates: balancedByArtist(yours) + new + balancedByArtist(nearby.filter { !found.contains($0.song.songIdentity) }), newShare: share, seed: seed)
            .anchored(to: genre)
            .following(history, now: now)
    }

    /// Fewer songs of yours in a genre than this and its station leans on new finds.
    static let genreFewSongs = 25
}

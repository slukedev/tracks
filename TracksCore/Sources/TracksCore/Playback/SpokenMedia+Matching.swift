import Foundation

extension SpokenMedia {
    /// The kinds of thing that can be asked for by name. Raw values match the app's.
    public enum Kind: String, Sendable, CaseIterable {
        case anything, song, album, artist, playlist, station, genre
    }

    /// One way to read what was said: a name, who it's by, and what kind of thing it is.
    public struct Reading: Sendable, Equatable {
        public var name: String
        public var artist: String
        public var kind: Kind

        public init(name: String, artist: String = "", kind: Kind = .anything) {
            self.name = name
            self.artist = artist
            self.kind = kind
        }
    }

    /// The ways a request said in one go can be read, most likely first. Siri hands over
    /// "the album Blue by Joni Mitchell" as one string; this splits off the kind and the
    /// artist. A title with "by" in it, like "Stand by Me", is read whole as well, so the
    /// search can find it either way.
    public static func readings(of text: String) -> [Reading] {
        var name = text.trimmingCharacters(in: .whitespacesAndNewlines)
        var kind = Kind.anything
        let folded = { (value: String) in StatsCalculator.folded(value) }

        for (phrase, phraseKind) in kindPrefixes {
            guard folded(name).hasPrefix(phrase + " ") else { continue }
            name = String(name.dropFirst(phrase.count)).trimmingCharacters(in: .whitespaces)
            kind = phraseKind
            break
        }
        if kind == .anything {
            for (phrase, phraseKind) in kindSuffixes {
                let lowered = folded(name)
                guard lowered.hasSuffix(" " + phrase), lowered.count > phrase.count + 1 else { continue }
                name = String(name.dropLast(phrase.count)).trimmingCharacters(in: .whitespaces)
                kind = phraseKind
                break
            }
        }
        guard !name.isEmpty else { return [] }

        if kind == .artist { return [Reading(name: "", artist: name, kind: .artist)] }
        guard kind != .playlist, kind != .station,
              let range = name.range(of: " by ", options: [.caseInsensitive, .backwards])
        else { return [Reading(name: name, kind: kind)] }
        let title = name[..<range.lowerBound].trimmingCharacters(in: .whitespaces)
        let artist = name[range.upperBound...].trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty, !artist.isEmpty else { return [Reading(name: name, kind: kind)] }
        return [Reading(name: title, artist: artist, kind: kind), Reading(name: name, kind: kind)]
    }

    /// A mood asked for as music rather than by a title: "relaxing music", "something to
    /// study to", "songs for the gym". "Love Story" names a song, so a mood word alone isn't
    /// enough; it takes a word that asks for music in general too.
    public static func moodRequest(in text: String) -> String? {
        let words = Set(StatsCalculator.folded(text).split { !$0.isLetter }.map(String.init))
        guard !words.isDisjoint(with: musicWords) else { return nil }
        return mood(in: text)
    }

    private static let musicWords: Set<String> = ["music", "songs", "something", "tunes", "playlist", "mix", "stuff", "vibes"]

    /// Something found for a request, as plain values for ranking.
    public struct Candidate: Sendable, Equatable {
        public var title: String
        public var artist: String?
        public var kind: Kind
        /// In your library or your own music.
        public var isOwned: Bool
        /// How often you've played it, as the history has it.
        public var plays: Int

        public init(title: String, artist: String? = nil, kind: Kind, isOwned: Bool = false, plays: Int = 0) {
            self.title = title
            self.artist = artist
            self.kind = kind
            self.isOwned = isOwned
            self.plays = plays
        }
    }

    /// The order to offer candidates in, as indices into them: closest to what was said
    /// first, then yours (owned or played) before the rest, then as found, since a search
    /// already puts the likelier one first.
    public static func rank(_ candidates: [Candidate], name: String, artist: String = "") -> [Int] {
        let scores = candidates.map { score($0, name: name, artist: artist) }
        return candidates.indices.sorted { left, right in
            if scores[left] != scores[right] { return scores[left] > scores[right] }
            if candidates[left].plays != candidates[right].plays { return candidates[left].plays > candidates[right].plays }
            return left < right
        }
    }

    /// The candidates to ask about, in order, when more than one is equally what was said:
    /// two of your own songs or albums with the same name by different artists. Empty when
    /// the first is the clear answer. Only things of yours are worth a question: between
    /// two songs from the catalog, the search's first is the one people mean.
    public static func ambiguous(_ candidates: [Candidate], ranked order: [Int], name: String, artist: String = "") -> [Int] {
        guard artist.isEmpty, order.count > 1 else { return [] }
        let first = candidates[order[0]]
        guard first.kind == .song || first.kind == .album, isYours(first),
              titleScore(first.title, name) == exactTitle
        else { return [] }
        let tied = order.prefix(4).filter { index in
            let other = candidates[index]
            return other.kind == first.kind && isYours(other) && titleScore(other.title, name) == exactTitle
        }
        let artists = Set(tied.map { StatsCalculator.folded(candidates[$0].artist ?? "") })
        return artists.count > 1 ? tied : []
    }

    // MARK: - Scoring

    private static let exactTitle = 4

    private static func isYours(_ candidate: Candidate) -> Bool {
        candidate.isOwned || candidate.plays > 0
    }

    /// How close a candidate is to what was said, for choosing between readings of it.
    public static func score(_ candidate: Candidate, name: String, artist: String = "") -> Int {
        var score = name.isEmpty ? exactTitle : titleScore(candidate.title, name)
        if !artist.isEmpty {
            let wanted = StatsCalculator.folded(artist)
            let theirs = StatsCalculator.folded(candidate.kind == .artist ? candidate.title : candidate.artist ?? "")
            score += theirs == wanted ? 3 : theirs.contains(wanted) || (!theirs.isEmpty && wanted.contains(theirs)) ? 2 : -3
        }
        if isYours(candidate) { score += 1 }
        return score
    }

    /// 4 for the same title, versions and features aside; 3 when it starts with what was
    /// said; 1 when it's in there somewhere.
    private static func titleScore(_ title: String, _ name: String) -> Int {
        let wanted = StatsCalculator.folded(name)
        let theirs = StatsCalculator.folded(title)
        if theirs == wanted || baseTitle(theirs) == baseTitle(wanted) { return exactTitle }
        if theirs.hasPrefix(wanted) { return 3 }
        return theirs.contains(wanted) ? 1 : 0
    }

    /// A title without what versions add: "(Remastered 2011)", "[Live]", " - Single".
    private static func baseTitle(_ folded: String) -> String {
        var title = folded
        for opener in [" (", " [", " - "] {
            if let range = title.range(of: opener) { title = String(title[..<range.lowerBound]) }
        }
        return title.trimmingCharacters(in: .whitespaces)
    }

    private static let kindPrefixes: [(String, Kind)] = [
        ("the song", .song), ("the track", .song), ("the single", .song),
        ("the album", .album), ("the record", .album), ("the ep", .album),
        ("the playlist", .playlist), ("my playlist", .playlist),
        ("the artist", .artist), ("the band", .artist), ("songs by", .artist), ("music by", .artist),
        ("anything by", .artist), ("something by", .artist),
        ("the station", .station), ("the radio station", .station),
    ]

    private static let kindSuffixes: [(String, Kind)] = [
        ("playlist", .playlist), ("album", .album), ("station", .station),
    ]
}

/// Apple Music links, as Siri passes on one shared or on screen: which item it names.
public enum AppleMusicURL {
    public struct Item: Sendable, Equatable {
        public let kind: SpokenMedia.Kind
        public let id: String

        public init(kind: SpokenMedia.Kind, id: String) {
            self.kind = kind
            self.id = id
        }
    }

    /// `music.apple.com/us/album/blue/1440817537?i=1440817542` is that song,
    /// without `i` the album; `/song/`, `/artist/`, `/playlist/` and `/station/` name theirs.
    public static func item(in url: URL) -> Item? {
        guard let host = url.host()?.lowercased(),
              host == "music.apple.com" || host == "itunes.apple.com" || host == "geo.music.apple.com"
        else { return nil }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard let kindIndex = parts.firstIndex(where: { kinds[$0] != nil }),
              let kind = kinds[parts[kindIndex]],
              let id = parts.last, parts.count > kindIndex + 1, isIdentifier(id, for: kind)
        else { return nil }
        if kind == .album, let song = StoreURLParser.catalogSongID(from: url.absoluteString) {
            return Item(kind: .song, id: song)
        }
        return Item(kind: kind, id: id)
    }

    private static let kinds: [String: SpokenMedia.Kind] = [
        "song": .song, "album": .album, "artist": .artist, "playlist": .playlist, "station": .station,
    ]

    private static func isIdentifier(_ value: String, for kind: SpokenMedia.Kind) -> Bool {
        switch kind {
        case .playlist: value.hasPrefix("pl.")
        case .station: value.hasPrefix("ra.")
        default: !value.isEmpty && value.allSatisfy(\.isNumber)
        }
    }
}

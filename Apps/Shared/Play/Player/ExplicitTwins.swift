import Foundation
import MusicKit
import TracksCore
import TracksMusic

/// Swaps clean songs for their explicit versions, for when explicit songs are on.
///
/// Apple Music rates a song `.clean` only when it's the edit of an explicit one, so only those
/// are looked up. History, a library or a playlist can hold the edit; with explicit songs on,
/// the original plays in its place.
enum ExplicitTwins {
    /// Twins already looked up, by the clean song's id. A nil value: the catalog has none.
    private static var known: [String: Song?] = [:]
    /// Clean songs whose twin is being looked up, so a second request doesn't look again.
    private static var lookingUp: Set<String> = []

    /// Lookups for one queue, at most, so a long playlist of edits doesn't flood the catalog.
    private static let lookupLimit = 100
    private static let lookupsAtOnce = 8
    /// How long playing waits on lookups. What isn't back by then plays as it is, and is
    /// swapped the next time it's queued; what hasn't started by then isn't looked up at all.
    static let budget: Duration = .milliseconds(1500)

    /// The songs in the same order, each clean one replaced by its explicit version where the
    /// catalog has one and it was found within ``budget``. The song with id `first` is looked
    /// up ahead of the rest, then the songs in the order they play.
    static func swapped(_ songs: [Song], first: String? = nil) async -> [Song] {
        guard songs.contains(where: { $0.contentRating == .clean }) else { return songs }
        // An explicit twin is a catalog song, which only a subscription plays. Not known in
        // time, they're looked up anyway: without one, nothing from the catalog plays either.
        if let subscription = try? await AppleMusicSubscription.current(within: .milliseconds(500)),
           !subscription.canPlayCatalogContent {
            return songs
        }

        var seen = Set<String>()
        let ordered = songs.firstIndex { $0.id.rawValue == first }.map { [songs[$0]] + songs } ?? songs
        let wanted = Array(ordered
            .filter {
                let id = $0.id.rawValue
                return $0.contentRating == .clean && !known.keys.contains(id) && !lookingUp.contains(id) && seen.insert(id).inserted
            }
            .prefix(lookupLimit))
        lookingUp.formUnion(wanted.map(\.id.rawValue))

        let outcome = await BudgetedLookups.run(wanted, atOnce: lookupsAtOnce, within: budget) { song in
            await twin(of: song)
        } late: { song, twin in
            await keep(twin, for: song)
        }
        for (song, twin) in outcome.found { keep(twin, for: song) }
        for song in outcome.notStarted { lookingUp.remove(song.id.rawValue) }

        return songs.map { song in
            guard song.contentRating == .clean, let twin = known[song.id.rawValue] ?? nil else { return song }
            return twin
        }
    }

    /// The id the song plays under once swapped: its twin's, or its own.
    static func playingID(of songID: String) -> String {
        guard let twin = known[songID] ?? nil else { return songID }
        return twin.id.rawValue
    }

    private static func keep(_ twin: Twin, for song: Song) {
        let id = song.id.rawValue
        lookingUp.remove(id)
        switch twin {
        case .found(let explicit): known[id] = .some(explicit)
        case .none: known[id] = .some(nil)
        // Offline or slow: looked up again next time, rather than kept as having none.
        case .unknown: break
        }
    }

    private nonisolated enum Twin: Sendable {
        case found(Song)
        case none
        case unknown
    }

    /// The explicit edition of a clean album, released the same year, or nil.
    static func explicitEdition(of album: Album) async -> Album? {
        guard album.contentRating == .clean else { return nil }
        var request = MusicCatalogSearchRequest(term: "\(album.title) \(album.artistName)", types: [Album.self])
        request.limit = 10
        guard let results = try? await request.response().albums else { return nil }
        let year = album.releaseDate.map { Calendar.current.component(.year, from: $0) }
        let sameYear = results.filter { candidate in
            year == nil || candidate.releaseDate.map { Calendar.current.component(.year, from: $0) } == year
        }
        return ExplicitVersions.explicitTwin(
            title: album.title,
            artist: album.artistName,
            duration: nil,
            among: Array(sameYear),
            title: \.title,
            artist: \.artistName,
            duration: { _ in nil },
            isExplicit: { $0.contentRating == .explicit }
        )
    }

    private nonisolated static func twin(of song: Song) async -> Twin {
        var request = MusicCatalogSearchRequest(term: "\(song.title) \(song.artistName)", types: [Song.self])
        request.limit = 10
        guard let results = try? await request.response().songs else { return .unknown }
        let twin = ExplicitVersions.explicitTwin(
            title: song.title,
            artist: song.artistName,
            duration: song.duration,
            among: Array(results),
            title: \.title,
            artist: \.artistName,
            duration: \.duration,
            isExplicit: { $0.contentRating == .explicit }
        )
        return twin.map(Twin.found) ?? .none
    }
}

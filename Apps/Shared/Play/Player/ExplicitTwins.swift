import Foundation
import MusicKit
import TracksCore

/// Swaps clean songs for their explicit versions, for when explicit songs are on.
///
/// Apple Music rates a song `.clean` only when it's the edit of an explicit one, so only those
/// are looked up. History, a library or a playlist can hold the edit; with explicit songs on,
/// the original plays in its place.
enum ExplicitTwins {
    /// Twins already looked up, by the clean song's id. A nil value: the catalog has none.
    private static var known: [String: Song?] = [:]

    /// Lookups for one queue, at most, so a long playlist of edits doesn't hold up playing.
    private static let lookupLimit = 100
    private static let lookupsAtOnce = 8

    /// The songs in the same order, each clean one replaced by its explicit version where the
    /// catalog has one. A song whose twin can't be found stays as it is.
    static func swapped(_ songs: [Song]) async -> [Song] {
        guard songs.contains(where: { $0.contentRating == .clean }) else { return songs }
        // An explicit twin is a catalog song, which only a subscription plays.
        guard (try? await MusicSubscription.current)?.canPlayCatalogContent == true else { return songs }

        var seen = Set<String>()
        let wanted = songs
            .filter { $0.contentRating == .clean && !known.keys.contains($0.id.rawValue) && seen.insert($0.id.rawValue).inserted }
            .prefix(lookupLimit)

        await withTaskGroup(of: (String, Song?).self) { group in
            var pending = wanted.makeIterator()
            for _ in 0..<lookupsAtOnce {
                guard let song = pending.next() else { break }
                group.addTask { (song.id.rawValue, await Self.twin(of: song)) }
            }
            while let result = await group.next() {
                known[result.0] = .some(result.1)
                if let song = pending.next() {
                    group.addTask { (song.id.rawValue, await Self.twin(of: song)) }
                }
            }
        }

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

    private nonisolated static func twin(of song: Song) async -> Song? {
        var request = MusicCatalogSearchRequest(term: "\(song.title) \(song.artistName)", types: [Song.self])
        request.limit = 10
        guard let results = try? await request.response().songs else { return nil }
        return ExplicitVersions.explicitTwin(
            title: song.title,
            artist: song.artistName,
            duration: song.duration,
            among: Array(results),
            title: \.title,
            artist: \.artistName,
            duration: \.duration,
            isExplicit: { $0.contentRating == .explicit }
        )
    }
}

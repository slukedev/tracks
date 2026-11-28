import Foundation

/// Which of the services' rows of picks Play shows, chosen the way Apple Music's are: none
/// that's the service's own recently played or mostly repeats Play's Recently Played, without
/// what Recently Played already holds, and at most a few rows from each service.
public enum CatalogRecommendationRows {
    public static let perService = 3

    /// - Parameter recentAlbums: Recently Played's albums, as ``albumKey(title:artist:)`` makes them.
    public static func shown(_ shelves: [TracksideCatalogShelf], recentAlbums: Set<String>, perService: Int = perService) -> [TracksideCatalogShelf] {
        var counts: [String: Int] = [:]
        return shelves.compactMap { shelf -> TracksideCatalogShelf? in
            guard !StatsCalculator.folded(shelf.title).contains("recently played") else { return nil }
            let kept = shelf.items.filter { !repeats($0, recentAlbums) }
            guard !kept.isEmpty, (shelf.items.count - kept.count) * 2 < shelf.items.count else { return nil }
            var shown = shelf
            shown.items = kept
            return shown
        }
        .filter { shelf in
            let count = counts[shelf.serviceID, default: 0]
            counts[shelf.serviceID] = count + 1
            return count < perService
        }
    }

    /// An album as Recently Played and the picks are compared: its title and artist, folded.
    public static func albumKey(title: String, artist: String) -> String {
        "\(StatsCalculator.folded(title))\u{1F}\(StatsCalculator.folded(artist))"
    }

    private static func repeats(_ entry: TracksideCatalogEntry, _ recent: Set<String>) -> Bool {
        switch entry {
        case .album(let album):
            recent.contains(albumKey(title: album.name, artist: album.artist ?? ""))
        case .track(let track):
            track.album.map { recent.contains(albumKey(title: $0, artist: track.artistLine)) } ?? false
        case .artist, .playlist, .radio:
            false
        }
    }
}

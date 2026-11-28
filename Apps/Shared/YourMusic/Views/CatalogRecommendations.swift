import SwiftUI
import TracksCore

/// A server's music services' own picks on Play, after the server's picks: their rows, less
/// what Recently Played already shows. Nothing shows until the server says it has them, so an
/// older server, or one whose services pick nothing, leaves no gap.
struct CatalogRecommendationShelves: View {
    let serverID: String
    /// Recently Played's albums, as ``CatalogRecommendationRows/albumKey(title:artist:)`` makes them.
    let recentAlbums: Set<String>

    @Environment(AppModel.self) private var model

    var body: some View {
        let streaming = model.streaming
        let rows = CatalogRecommendationRows.shown(streaming.recommendations[serverID] ?? [], recentAlbums: recentAlbums)
        let namesService = Set(rows.map(\.serviceID)).count > 1
        ForEach(rows) { row in
            Shelf(title: row.title, items: row.items) {
                if namesService, let name = streaming.service(CatalogServiceAddress(serverID: serverID, serviceID: row.serviceID))?.name {
                    Text(verbatim: name)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            } tile: { entry in
                CatalogEntryTile(entry: entry, row: row, serverID: serverID)
            }
        }
    }
}

extension CatalogRecommendationShelves {
    /// Recently Played's albums, from Apple Music's shelf and your own, for comparing picks with.
    static func recentAlbums(feed: [FeedItem], own: [LocalAlbum]) -> Set<String> {
        var keys = Set(own.map { CatalogRecommendationRows.albumKey(title: $0.title, artist: $0.artist) })
        for item in feed {
            if case .album = item.content { keys.insert(CatalogRecommendationRows.albumKey(title: item.title, artist: item.subtitle ?? "")) }
        }
        return keys
    }
}

/// One of a service's picks on a shelf: a song or station plays at once, the rest open their page.
private struct CatalogEntryTile: View {
    let entry: TracksideCatalogEntry
    let row: TracksideCatalogShelf
    let serverID: String

    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music

    var body: some View {
        switch entry {
        case .album(let album):
            CatalogCollectionTile(route: .album(album, on: serverID))
        case .playlist(let playlist):
            CatalogCollectionTile(route: .playlist(playlist, on: serverID))
        case .artist(let artist):
            let route = CatalogRoute.artist(artist, on: serverID)
            NavigationLink(value: PlayRoute.catalog(route)) {
                TileLabel(title: artist.name, subtitle: String(localized: "Artist")) { side in
                    ArtistPicture(cover: artist.artworkID.map { music.catalogCover($0, on: serverID, seed: artist.name) }, name: artist.name, size: side)
                }
            }
            .buttonStyle(.pressable)
        case .track(let track):
            Button {
                play(from: track)
            } label: {
                TileLabel(title: track.title, subtitle: track.artistLine) { side in
                    ArtworkView(url: coverURL(track.artworkID, seed: track.album ?? track.title), seed: track.album ?? track.title, size: side)
                }
            }
            .buttonStyle(.pressable)
            .disabled(!track.isPlayable)
            .contextMenu { CatalogTrackMenu(track: track, serverID: serverID, title: row.title) }
        case .radio(let station):
            Button {
                let queue = RelayQueue(serverID: serverID, entries: [RelayEntry(station)], context: TracksideSpeaker.Context(kind: .station, name: station.name))
                model.playThroughRelay(queue, from: PlayContext(kind: .station, title: station.name))
            } label: {
                TileLabel(title: station.name, subtitle: String(localized: "Station")) { side in
                    ArtworkView(url: coverURL(station.artworkID, seed: station.name), seed: station.name, size: side)
                }
            }
            .buttonStyle(.pressable)
        }
    }

    /// The row's songs as one queue, from the one tapped.
    private func play(from track: TracksideCatalogTrack) {
        let tracks = row.items.compactMap { entry -> TracksideCatalogTrack? in
            if case .track(let track) = entry, track.isPlayable { return track }
            return nil
        }
        guard let start = tracks.firstIndex(where: { $0.ref == track.ref }) else { return }
        let queue = music.relayQueue(tracks, startingAt: start, on: serverID, context: TracksideSpeaker.Context(kind: .other, name: row.title))
        model.playThroughRelay(queue, from: .songs(row.title))
    }

    private func coverURL(_ artworkID: String?, seed: String) -> String? {
        if case .url(let url, _) = music.catalogCover(artworkID, on: serverID, seed: seed) { return url }
        return nil
    }
}

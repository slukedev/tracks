import SwiftUI
import MusicKit
import TracksCore

/// An artist's albums in your other sources, on their page, with every source on: a shelf for
/// each source that has some, named for it with its glyph, so one page holds all their music
/// and says where each copy is.
struct ArtistElsewhere: View {
    let name: String
    /// The source the page itself is from, whose albums it already shows.
    let page: MusicSource
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @AppStorage(PlayPreferences.allowsExplicitKey) private var allowsExplicit = true
    @State private var appleMusic: [Album] = []

    var body: some View {
        Group {
            if model.mixesSources {
                if page != .yourMusic {
                    ForEach(ownShelves, id: \.source) { shelf in
                        Shelf(title: shelf.name, items: shelf.albums) {
                            glyph(shelf.source)
                        } tile: { album in
                            LocalAlbumShelfTile(album: album, showsYear: true)
                        }
                    }
                }
                if !appleMusic.isEmpty {
                    Shelf(title: String(localized: "Apple Music"), items: appleMusic) {
                        glyph(.appleMusic)
                    } tile: { album in
                        ArtistAlbumTile(album: album)
                    }
                }
            }
        }
        .task(id: "\(name).\(model.mixesSources)") { await load() }
    }

    private func glyph(_ source: SourceID) -> some View {
        SourceGlyph(source: source)
            .frame(width: 16, height: 16)
            .foregroundStyle(.secondary)
            .accessibilityHidden(true)
    }

    /// Their albums of yours, a shelf for this device and one for each server.
    private var ownShelves: [(source: SourceID, name: String, albums: [LocalAlbum])] {
        guard let artist = music.index.artist(id: StatsCalculator.folded(name)) else { return [] }
        var shelves: [(source: SourceID, name: String, albums: [LocalAlbum])] = []
        for album in artist.albums {
            let source = album.tracks.first?.serverID.map(SourceID.server) ?? .device
            if let at = shelves.firstIndex(where: { $0.source == source }) {
                shelves[at].albums.append(album)
            } else {
                shelves.append((source: source, name: music.name(of: source), albums: [album]))
            }
        }
        return shelves
    }

    private func load() async {
        guard model.mixesSources else { return }
        let key = StatsCalculator.folded(name)
        if page != .appleMusic, model.musicAuthorization == .authorized, !model.playFeed.cannotPlayCatalog, !model.isDemoLaunch {
            var request = MusicCatalogSearchRequest(term: name, types: [Artist.self])
            request.limit = 3
            if let found = try? await request.response().artists.first(where: { StatsCalculator.folded($0.name) == key }),
               let detailed = try? await found.with([.albums]) {
                appleMusic = PlayPreferences.versions(of: Array(detailed.albums ?? []), allowsExplicit: allowsExplicit)
            }
        }
    }
}

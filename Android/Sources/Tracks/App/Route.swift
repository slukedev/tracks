import SwiftUI
import TracksStats

/// A page pushed onto a tab's stack.
///
/// Identifiable, because Compose stores navigation values as strings and Skip builds them from
/// the `id`, which has to be unique and stable.
enum Route: Hashable, Identifiable {
    /// An album or artist on the music server, to play from.
    case serverAlbum(String)
    case serverArtist(String)
    /// One of the server's album lists: newest, most played, starred and so on.
    case albumList(SubsonicClient.AlbumListType)
    /// An artist, song or album in the statistics, by its identity in the history.
    case artist(String)
    case song(String)
    case album(String)
    case charts(ChartKind)
    case history
    case settings

    var id: String {
        switch self {
        case .serverAlbum(let id): "serverAlbum:\(id)"
        case .serverArtist(let id): "serverArtist:\(id)"
        case .albumList(let list): "albumList:\(list.rawValue)"
        case .artist(let id): "artist:\(id)"
        case .song(let id): "song:\(id)"
        case .album(let id): "album:\(id)"
        case .charts(let kind): "charts:\(kind.rawValue)"
        case .history: "history"
        case .settings: "settings"
        }
    }
}

extension View {
    /// The pages any tab can push. Applied once, at the root of each stack: Skip lets a pushed
    /// view's mapping for a type replace the root's.
    func tracksDestinations() -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case .serverAlbum(let id): ServerAlbumPage(albumID: id)
            case .serverArtist(let id): ServerArtistPage(artistID: id)
            case .albumList(let list): ServerAlbumListPage(list: list)
            case .artist(let id): ArtistScreen(artistID: id)
            case .song(let id): SongScreen(songID: id)
            case .album(let id): AlbumScreen(albumID: id)
            case .charts(let kind): ChartsScreen(kind: kind)
            case .history: HistoryScreen()
            case .settings: SettingsScreen()
            }
        }
    }
}

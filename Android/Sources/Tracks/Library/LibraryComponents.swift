import SwiftUI
import TracksStats

/// An album on a shelf or in a grid: its cover with 16 dp corners, name and artist.
struct AlbumCard: View {
    let album: SubsonicAlbum
    var width: CGFloat = 152
    @Environment(ServerStore.self) var servers

    var body: some View {
        NavigationLink(value: Route.serverAlbum(album.id)) {
            VStack(alignment: .leading, spacing: 8) {
                Artwork(url: coverRef, size: width, corner: Corner.large, placeholder: .album)
                VStack(alignment: .leading, spacing: 0) {
                    Text(album.name)
                        .materialType(.titleSmall)
                        .materialForeground(.onSurface)
                        .lineLimit(1)
                    Text(album.artist ?? " ")
                        .materialType(.bodySmall)
                        .materialForeground(.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
        }
        .buttonStyle(.plain)
    }

    private var coverRef: String? {
        servers.server.flatMap { ServerStore.coverRef(serverID: $0.id, coverID: album.coverArt ?? album.id) }
    }
}

/// Albums side by side, to scroll through.
struct AlbumShelf: View {
    let albums: [SubsonicAlbum]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: Space.grid) {
                ForEach(albums) { album in
                    AlbumCard(album: album)
                }
            }
            .padding(.horizontal, Space.screen)
        }
    }
}

/// Albums two to a row, filling the width.
struct AlbumGrid: View {
    let albums: [SubsonicAlbum]
    let width: CGFloat

    var body: some View {
        let columns = 2
        let cardWidth = (width - Space.screen * 2 - Space.grid) / CGFloat(columns)
        LazyVStack(alignment: .leading, spacing: 20) {
            ForEach(Array(stride(from: 0, to: albums.count, by: columns)), id: \.self) { start in
                HStack(alignment: .top, spacing: Space.grid) {
                    ForEach(albums[start..<min(start + columns, albums.count)]) { album in
                        AlbumCard(album: album, width: cardWidth)
                    }
                    if start + 1 >= albums.count { Spacer(minLength: 0) }
                }
                .padding(.horizontal, Space.screen)
            }
        }
    }
}

/// A song in an album or a list of results: its number or cover, title, artist and length.
/// The one playing shows bouncing bars in place of its number, or of its length.
struct TrackRow: View {
    let song: QueueSong
    var number: Int?
    var isCurrent = false
    let play: () -> Void
    @Environment(PlayerModel.self) var player

    var body: some View {
        Button(action: play) {
            HStack(spacing: Space.iconGap) {
                if let number {
                    if isCurrent {
                        PlayingBars(isPlaying: player.isPlaying)
                            .frame(width: 28)
                    } else {
                        Text("\(number)")
                            .materialType(.bodyLarge)
                            .materialForeground(.onSurfaceVariant)
                            .frame(width: 28)
                    }
                } else {
                    Artwork(url: song.coverRef, size: 48)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title)
                        .materialType(isCurrent ? .titleMediumEmphasized : .bodyLarge)
                        .materialForeground(isCurrent ? .primary : .onSurface)
                        .lineLimit(1)
                    Text(song.artist)
                        .materialType(.bodyMedium)
                        .materialForeground(.onSurfaceVariant)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if isCurrent && number == nil {
                    PlayingBars(isPlaying: player.isPlaying)
                } else if let duration = song.duration {
                    Text(NowPlayingScreen.time(duration))
                        .materialType(.labelMedium)
                        .materialForeground(.onSurfaceVariant)
                }
            }
            .padding(.horizontal, Space.screen)
            .padding(.vertical, 8)
            .frame(minHeight: 56)
            .fullTapArea()
        }
        .buttonStyle(.plain)
        .combinedAccessibility()
    }
}

/// Loading, failed with a way to try again, or loaded: Material's content loading pattern.
enum Loadable<Value> {
    case loading
    case failed(String)
    case loaded(Value)

    var value: Value? {
        if case .loaded(let value) = self { return value }
        return nil
    }
}

struct LoadingView: View {
    var body: some View {
        MorphingLoadingIndicator()
            .frame(maxWidth: .infinity)
            .padding(.vertical, 96)
    }
}

struct FailedView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        EmptyState(symbol: .warning, title: "Couldn't reach your server", message: message) {
            Button("Try again", action: retry)
                .buttonStyle(.borderedProminent)
        }
    }
}

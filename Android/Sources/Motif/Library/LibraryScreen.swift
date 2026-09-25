import SwiftUI
import MotifStats

/// Everything on the server, by artist or by album.
struct LibraryScreen: View {
    @Environment(ServerStore.self) var servers
    @State var tab = 0
    @State var artists: Loadable<[SubsonicArtist]> = .loading
    @State var albums: Loadable<[SubsonicAlbum]> = .loading

    var body: some View {
        VStack(spacing: 0) {
            PrimaryTabs(titles: ["Artists", "Albums"], selection: $tab)
            if tab == 0 { artistList } else { albumGrid }
        }
        .navigationTitle("Library")
        .task(id: "\(servers.server?.id ?? "")|\(tab)") { await load() }
    }

    @ViewBuilder
    private var artistList: some View {
        switch artists {
        case .loading: LoadingView()
        case .failed(let message): FailedView(message: message) { Task { await load() } }
        case .loaded(let artists):
            List {
                ForEach(artists) { artist in
                    NavigationLink(value: Route.serverArtist(artist.id)) {
                        ArtistRow(artist: artist)
                    }
                    .materialListRow()
                }
            }
            .listStyle(.plain)
            .refreshable { await load(force: true) }
        }
    }

    @ViewBuilder
    private var albumGrid: some View {
        switch albums {
        case .loading: LoadingView()
        case .failed(let message): FailedView(message: message) { Task { await load() } }
        case .loaded(let albums):
            GeometryReader { proxy in
                ScrollView {
                    AlbumGrid(albums: albums, width: proxy.size.width)
                        .padding(.vertical, Space.screen)
                }
            }
        }
    }

    private func load(force: Bool = false) async {
        guard let client = servers.client else { return }
        do {
            if tab == 0, force || artists.value == nil {
                let all = try await client.artists()
                artists = .loaded(all.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending })
            } else if tab == 1, force || albums.value == nil {
                albums = .loaded(try await client.albums(.alphabeticalByName, size: 500))
            }
        } catch {
            if tab == 0 { artists = .failed(error.localizedDescription) } else { albums = .failed(error.localizedDescription) }
        }
    }
}

/// An artist: their picture from the server, or their initials, and how many albums.
struct ArtistRow: View {
    let artist: SubsonicArtist
    @Environment(ServerStore.self) var servers

    var body: some View {
        HStack(spacing: Space.iconGap) {
            ArtistPicture(name: artist.name, coverRef: coverRef, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(artist.name)
                    .materialType(.bodyLarge)
                    .materialForeground(.onSurface)
                    .lineLimit(1)
                if let count = artist.albumCount {
                    Text(count == 1 ? "1 album" : "\(count) albums")
                        .materialType(.bodyMedium)
                        .materialForeground(.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
    }

    private var coverRef: String? {
        guard let server = servers.server, let cover = artist.coverArt else { return nil }
        return ServerStore.coverRef(serverID: server.id, coverID: cover)
    }
}

/// An artist's picture in a circle, over their initials while it loads or if there isn't one.
struct ArtistPicture: View {
    let name: String
    let coverRef: String?
    var size: CGFloat = 48
    @Environment(ServerStore.self) var servers

    var body: some View {
        ZStack {
            Monogram(name: name, size: size)
            if let url = servers.coverURL(coverRef, size: Int(size * 3)) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Color.clear
                }
                .frame(width: size, height: size)
                .clipShape(Circle())
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

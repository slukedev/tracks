import SwiftUI
import TracksStats

/// An album on the server: its cover, Play and Shuffle, and its songs.
struct ServerAlbumPage: View {
    let albumID: String
    @Environment(ServerStore.self) var servers
    @Environment(PlayerModel.self) var player
    @Environment(AppModel.self) var model
    @State var content: Loadable<AlbumContent> = .loading

    struct AlbumContent {
        let album: SubsonicAlbum
        let songs: [QueueSong]
    }

    var body: some View {
        ScrollView {
            switch content {
            case .loading: LoadingView()
            case .failed(let message): FailedView(message: message) { Task { await load() } }
            case .loaded(let content):
                CoverColored(url: servers.coverURL(coverRef(content.album), size: NowPlayingScreen.coverSize)) {
                    page(content)
                }
            }
        }
        .navigationTitle(content.value?.album.name ?? "")
        .inlineTitle()
        .task(id: albumID) { await load() }
    }

    /// The album's cover and name on a card in the cover's own colour, then its songs.
    private func page(_ content: AlbumContent) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(spacing: 16) {
                Artwork(url: coverRef(content.album), size: 232, corner: Corner.extraLarge, placeholder: .album)
                VStack(spacing: 4) {
                    Text(content.album.name)
                        .materialType(.headlineSmallEmphasized)
                        .materialForeground(.onPrimaryContainer)
                        .multilineTextAlignment(.center)
                    if let artistID = content.album.artistId, let artist = content.album.artist {
                        NavigationLink(value: Route.serverArtist(artistID)) {
                            Text(artist)
                                .materialType(.titleMedium)
                                .materialForeground(.primary)
                        }
                        .buttonStyle(.plain)
                    }
                    Text(details(content))
                        .materialType(.bodyMedium)
                        .materialForeground(.onPrimaryContainer)
                        .opacity(0.8)
                }
                PlayButtons(
                    play: { player.play(content.songs, from: content.album.name) },
                    shuffle: { player.play(content.songs, from: content.album.name, shuffled: true) },
                    onColour: true
                )
                .padding(.top, 4)
            }
            .padding(20)
            .frame(maxWidth: .infinity)
            .materialBackground(.primaryContainer, cornerRadius: Corner.extraLarge + 4)
            .padding(.horizontal, 12)
            .padding(.top, 4)
            .padding(.bottom, 12)

            ForEach(Array(content.songs.enumerated()), id: \.element.id) { index, song in
                TrackRow(song: song, number: index + 1, isCurrent: player.current?.id == song.id) {
                    player.play(content.songs, startAt: index, from: content.album.name)
                }
            }
        }
        .padding(.bottom, 24)
    }

    private func details(_ content: AlbumContent) -> String {
        let minutes = Int(content.songs.compactMap(\.duration).reduce(0, +) / 60)
        let plays = content.songs.reduce(0) { $0 + model.playCount(of: $1) }
        return [
            content.album.year.map(String.init),
            content.songs.count == 1 ? "1 song" : "\(content.songs.count) songs",
            minutes > 0 ? "\(minutes) min" : nil,
            plays > 0 ? Format.plays(plays) : nil,
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }

    private func coverRef(_ album: SubsonicAlbum) -> String? {
        servers.server.flatMap { ServerStore.coverRef(serverID: $0.id, coverID: album.coverArt ?? album.id) }
    }

    private func load() async {
        guard let client = servers.client, let serverID = servers.server?.id else { return }
        do {
            let (album, songs) = try await client.album(id: albumID)
            content = .loaded(AlbumContent(album: album, songs: songs.map { QueueSong($0, serverID: serverID) }))
        } catch {
            content = .failed(error.localizedDescription)
        }
    }
}

/// An artist on the server: their albums, and their songs to play or shuffle.
struct ServerArtistPage: View {
    let artistID: String
    @Environment(ServerStore.self) var servers
    @Environment(PlayerModel.self) var player
    @Environment(AppModel.self) var model
    @State var content: Loadable<ArtistContent> = .loading
    @State var isStarting = false

    struct ArtistContent {
        let artist: SubsonicArtist
        let albums: [SubsonicAlbum]
    }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                switch content {
                case .loading: LoadingView()
                case .failed(let message): FailedView(message: message) { Task { await load() } }
                case .loaded(let content): page(content, width: proxy.size.width)
                }
            }
        }
        .navigationTitle(content.value?.artist.name ?? "")
        .inlineTitle()
        .task(id: artistID) { await load() }
    }

    private func page(_ content: ArtistContent, width: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: Space.cards) {
            VStack(spacing: 12) {
                ArtistPicture(name: content.artist.name, coverRef: coverRef(content.artist), size: 160)
                Text(content.artist.name)
                    .materialType(.headlineMedium)
                    .materialForeground(.onSurface)
                    .multilineTextAlignment(.center)
                let statsID = StatsCalculator.folded(content.artist.name)
                if model.history.captures.contains(where: { $0.artistIdentity == statsID }) {
                    NavigationLink(value: Route.artist(statsID)) {
                        HStack(spacing: 6) {
                            SymbolImage(.stats, size: 18, role: .onSecondaryContainer)
                            Text("Your listening")
                                .materialType(.labelLarge)
                                .materialForeground(.onSecondaryContainer)
                        }
                        .padding(.horizontal, 16)
                        .frame(height: 32)
                        .materialBackground(.secondaryContainer, cornerRadius: Corner.small)
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 8)

            PlayButtons(
                play: { Task { await playAll(content, shuffled: false) } },
                shuffle: { Task { await playAll(content, shuffled: true) } },
                isEnabled: !isStarting
            )
            .padding(.horizontal, Space.screen)

            SectionHeader(title: "Albums")
                .padding(.horizontal, Space.screen)
            AlbumGrid(albums: content.albums, width: width)
        }
        .padding(.bottom, 24)
    }

    private func coverRef(_ artist: SubsonicArtist) -> String? {
        guard let server = servers.server, let cover = artist.coverArt else { return nil }
        return ServerStore.coverRef(serverID: server.id, coverID: cover)
    }

    /// Every song on their albums, in album order or shuffled.
    private func playAll(_ content: ArtistContent, shuffled: Bool) async {
        guard let client = servers.client, let serverID = servers.server?.id else { return }
        isStarting = true
        defer { isStarting = false }
        var songs: [QueueSong] = []
        for album in content.albums.prefix(12) {
            if let (_, albumSongs) = try? await client.album(id: album.id) {
                songs += albumSongs.map { QueueSong($0, serverID: serverID) }
            }
        }
        player.play(songs, from: content.artist.name, shuffled: shuffled)
    }

    private func load() async {
        guard let client = servers.client else { return }
        do {
            let (artist, albums) = try await client.artist(id: artistID)
            content = .loaded(ArtistContent(artist: artist, albums: albums.sorted { ($0.year ?? 0) > ($1.year ?? 0) }))
        } catch {
            content = .failed(error.localizedDescription)
        }
    }
}

/// One of the server's album lists, two to a row.
struct ServerAlbumListPage: View {
    let list: SubsonicClient.AlbumListType
    @Environment(ServerStore.self) var servers
    @State var content: Loadable<[SubsonicAlbum]> = .loading

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                switch content {
                case .loading: LoadingView()
                case .failed(let message): FailedView(message: message) { Task { await load() } }
                case .loaded(let albums):
                    if albums.isEmpty {
                        EmptyState(symbol: BrowseList.of(list)?.symbol ?? .album, title: "Nothing here yet", message: emptyMessage)
                    } else {
                        AlbumGrid(albums: albums, width: proxy.size.width)
                            .padding(.vertical, 8)
                    }
                }
            }
        }
        .navigationTitle(BrowseList.of(list)?.title ?? "Albums")
        .task(id: list) { await load() }
        .refreshable { await load() }
    }

    private var emptyMessage: String {
        switch list {
        case .starred: "Star albums on your server and they'll be here."
        case .frequent, .recent: "Play some albums and they'll be here."
        default: "Your server didn't list any albums."
        }
    }

    private func load() async {
        guard let client = servers.client else { return }
        do {
            content = .loaded(try await client.albums(list, size: 60))
        } catch {
            content = .failed(error.localizedDescription)
        }
    }
}

/// Play and Shuffle side by side: a filled button and a quieter one, both squaring off as
/// they're pressed.
struct PlayButtons: View {
    let play: () -> Void
    let shuffle: () -> Void
    var isEnabled = true
    /// On a coloured card, where a tonal button would disappear: Shuffle is the page's surface.
    var onColour = false

    var body: some View {
        HStack(spacing: 8) {
            ExpressiveButton(title: "Play", symbol: .play, emphasis: .filled, isEnabled: isEnabled, action: play)
            ExpressiveButton(title: "Shuffle", symbol: .shuffle, emphasis: onColour ? .surface : .tonal, isEnabled: isEnabled, action: shuffle)
        }
    }
}

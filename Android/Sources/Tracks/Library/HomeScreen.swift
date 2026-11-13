import SwiftUI
import TracksStats

/// Where listening starts: shuffle everything, pick up what you played, or find an album.
struct HomeScreen: View {
    /// Pushes a page onto Home's stack.
    var open: (Route) -> Void = { _ in }
    @Environment(ServerStore.self) var servers
    @Environment(PlayerModel.self) var player
    @Environment(AppModel.self) var model
    @State var newest: Loadable<[SubsonicAlbum]> = .loading
    @State var picks: [SubsonicAlbum] = []
    @State var isShuffling = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.cards) {
                actions
                    .padding(.horizontal, Space.screen)

                let recent = model.recentlyPlayed()
                if !recent.isEmpty {
                    SectionHeader(title: "Recently played")
                        .padding(.horizontal, Space.screen)
                    RecentSongsShelf(plays: recent)
                }

                switch newest {
                case .loading:
                    LoadingView()
                case .failed(let message):
                    FailedView(message: message) { Task { await load() } }
                case .loaded(let albums):
                    if !albums.isEmpty {
                        SectionHeader(title: "Recently added")
                            .padding(.horizontal, Space.screen)
                        CoverCarousel(items: albums.map { album in
                            CoverCarousel.Item(url: servers.coverURL(coverRef(album), size: 600), title: album.name, subtitle: album.artist ?? "")
                        }) { index in
                            if albums.indices.contains(index) { open(.serverAlbum(albums[index].id)) }
                        }
                    }
                    if !picks.isEmpty {
                        SectionHeader(title: "Something different")
                            .padding(.horizontal, Space.screen)
                        AlbumShelf(albums: picks)
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .navigationTitle("Home")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(value: Route.settings) {
                    SymbolImage(.settings, role: .onSurfaceVariant)
                        .frame(width: Space.touchTarget, height: Space.touchTarget)
                }
                .accessibilityLabel("Settings")
            }
        }
        .task(id: servers.server?.id) { await load() }
        .refreshable { await load() }
    }

    /// Shuffle everything, and play what you play most: Material 3 Expressive's medium buttons,
    /// which square off as they're pressed.
    private var actions: some View {
        HStack(spacing: 8) {
            ExpressiveButton(title: "Shuffle all", symbol: .shuffle, emphasis: .filled, isEnabled: !isShuffling) {
                Task { await shuffleEverything() }
            }
            ExpressiveButton(title: "On repeat", symbol: .onRepeat, emphasis: .tonal, isEnabled: !favourites.isEmpty) {
                playFavourites()
            }
        }
    }

    private func coverRef(_ album: SubsonicAlbum) -> String? {
        servers.server.flatMap { ServerStore.coverRef(serverID: $0.id, coverID: album.coverArt ?? album.id) }
    }

    /// The songs played most in the last month, from the statistics.
    private var favourites: [QueueSong] {
        let monthAgo = Calendar.current.date(byAdding: .month, value: -1, to: .now) ?? .now
        let serverID = servers.server?.id
        return StatsCalculator.songChart(
            in: ChartPeriod(span: .month, interval: DateInterval(start: monthAgo, end: .now)),
            history: model.history,
            limit: 50
        )
        .compactMap { QueueSong($0.item, serverID: serverID) }
    }

    private func playFavourites() {
        player.play(favourites, from: "On repeat", shuffled: true)
    }

    private func shuffleEverything() async {
        guard let client = servers.client, let serverID = servers.server?.id else { return }
        isShuffling = true
        defer { isShuffling = false }
        if let songs = try? await client.randomSongs(count: 100) {
            player.play(songs.map { QueueSong($0, serverID: serverID) }, from: "Shuffle all")
        }
    }

    private func load() async {
        guard let client = servers.client else { return }
        if newest.value == nil { newest = .loading }
        do {
            async let added = client.albums(.newest, size: 20)
            async let random = client.albums(.random, size: 20)
            newest = .loaded(try await added)
            picks = (try? await random) ?? []
        } catch {
            newest = .failed(error.localizedDescription)
        }
    }
}

/// Songs you played lately, each once, newest first. Tapping one plays it and those after it.
struct RecentSongsShelf: View {
    let plays: [Play]
    @Environment(PlayerModel.self) var player

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: Space.grid) {
                ForEach(Array(plays.enumerated()), id: \.element.id) { index, play in
                    Button {
                        player.play(queue, startAt: index, from: "Recently played")
                    } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            Artwork(url: play.artworkURL, size: 120, corner: Corner.extraLarge)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(play.title)
                                    .materialType(.titleSmall)
                                    .materialForeground(.onSurface)
                                    .lineLimit(1)
                                Text(play.artistName)
                                    .materialType(.bodySmall)
                                    .materialForeground(.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                        }
                        .frame(width: 120, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Space.screen)
        }
    }

    private var queue: [QueueSong] {
        plays.compactMap { play in
            guard let songID = play.songID, let serverID = play.serverID else { return nil }
            return QueueSong(id: songID, title: play.title, artist: play.artistName, album: play.albumTitle,
                             coverRef: play.artworkURL, duration: play.duration, serverID: serverID)
        }
    }
}

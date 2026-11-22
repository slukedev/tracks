import SwiftUI
import MusicKit
import TracksCore

/// Play with Apple Music as the source: the whole page with a connection, and only what's
/// downloaded to this iPhone without one, or with Offline Mode on.
struct AppleMusicPlayScreen: View {
    @Environment(YourMusic.self) private var music
    @AppStorage(OfflineMode.storageKey) private var isOfflineModeOn = false

    var body: some View {
        if isOfflineModeOn || !music.network.isOnline {
            OfflineScreen()
        } else {
            PlayScreen()
        }
    }
}

/// Play offline: songs downloaded to this iPhone, the mixes they make, and every one of them.
/// Everything here plays without a connection, and is kept in the history like anything else.
struct OfflineScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @Environment(PlayFeed.self) private var feed
    @Environment(DownloadedSongs.self) private var downloads
    /// The mixes and most played shelves, worked out off the main actor when the downloads
    /// or the history's mixes change.
    @State private var shelves = OfflineShelves()

    /// Mixes need this many of their songs downloaded to be worth a tile.
    private static let mixMinimum = 5

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Design.Margin.section) {
                OfflineBanner()
                    .padding(.horizontal, Design.Margin.page)

                if !model.isShowingSampleData, model.musicAuthorization != .authorized {
                    PlayAccessCard()
                        .padding(.horizontal, Design.Margin.page)
                } else if downloads.songs.isEmpty {
                    if downloads.hasLoaded {
                        emptyState
                            .padding(.horizontal, Design.Margin.page)
                    } else {
                        LoadingRows(count: 6)
                            .padding(.horizontal, Design.Margin.page)
                    }
                } else {
                    content
                }
            }
            .padding(.bottom, Design.Margin.section)
        }
        .tabRoot("Play", opensDevicesAtLaunch: true, opensSettingsAtLaunch: LaunchScene.opensPlaySettings)
        .refreshable { await downloads.load() }
        .task(id: feed.hasBuilt) { await downloads.loadIfNeeded() }
        .task(id: OfflineShelves.Key(downloads: downloads.revision, feed: feed.rebuilds)) {
            let (byIdentity, songs, mixes, facts) = (downloads.byIdentity, downloads.songs, feed.mixes.all, feed.facts)
            let next = await OfflineShelves.make(byIdentity: byIdentity, songs: songs, mixes: mixes, facts: facts, mixMinimum: Self.mixMinimum)
            guard !Task.isCancelled else { return }
            shelves = next
        }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        let songs = downloads.songs
        playButtons(songs)
            .padding(.horizontal, Design.Margin.page)

        let mixes = shelves.mixes
        if !mixes.isEmpty {
            Shelf(title: String(localized: "Made from Your Listening"), items: mixes) { mix in
                OfflineMixTile(mix: mix.mix, songs: mix.songs)
            }
        }

        let mostPlayed = shelves.mostPlayed
        if !mostPlayed.isEmpty {
            Shelf(title: String(localized: "Your Most Played"), items: mostPlayed) { song in
                OfflineSongTile(song: song, queue: mostPlayed, context: .songs(String(localized: "Your Most Played")))
            }
        }

        let recent = Array(songs.prefix(15))
        Shelf(title: String(localized: "Recently Added"), items: recent) { song in
            OfflineSongTile(song: song, queue: recent, context: .songs(String(localized: "Recently Added")))
        }

        allSongs
            .padding(.horizontal, Design.Margin.page)
    }

    private func playButtons(_ songs: [OfflineSong]) -> some View {
        let context = PlayContext.songs(String(localized: "Downloaded"))
        return HStack(spacing: Design.Control.spacing) {
            PlayButton {
                player.play(downloads.byTitle.request(), from: context)
            }
            GlassCircleButton(title: "Shuffle", systemImage: "shuffle") {
                player.play(songs.request(), from: context, shuffled: true)
            }
        }
    }

    /// Every downloaded song, A to Z, as Music lists songs.
    private var allSongs: some View {
        let songs = downloads.byTitle
        let context = PlayContext.songs(String(localized: "Downloaded"))
        return LazyVStack(alignment: .leading, spacing: 0) {
            ShelfHeader(title: String(localized: "Downloaded Songs"))
                .padding(.bottom, Design.Margin.header)

            ForEach(songs.enumerated(), id: \.element.id) { index, song in
                OfflineSongRow(song: song, index: index, songs: songs, context: context)
            }
        }
    }

    private var emptyState: some View {
        PlayStateCard(
            symbol: "arrow.down.circle",
            title: String(localized: "Nothing Downloaded"),
            message: String(localized: "Songs you download in Music play here without a connection.")
        ) {
            EmptyView()
        }
    }

}

/// The shelves made from the downloads and the history.
private nonisolated struct OfflineShelves: Sendable {
    /// Tracks’ mixes, with only their downloaded songs, when enough of them are.
    var mixes: [DownloadedMix] = []
    /// The downloaded songs you play most, from Tracks’ history.
    var mostPlayed: [OfflineSong] = []

    struct Key: Equatable {
        let downloads: Int
        let feed: Int
    }

    @concurrent
    static func make(
        byIdentity: [String: OfflineSong],
        songs: [OfflineSong],
        mixes: [Mix],
        facts: [String: SongFacts],
        mixMinimum: Int
    ) async -> OfflineShelves {
        let downloaded = mixes.compactMap { mix in
            let songs = mix.songs.compactMap { byIdentity[$0.songIdentity] }
            return songs.count >= mixMinimum ? DownloadedMix(mix: mix, songs: songs) : nil
        }
        let plays = songs.compactMap { song in facts[song.identity].map { (song, $0.plays) } }
        let mostPlayed = Array(plays.filter { $0.1 > 1 }.sorted { $0.1 > $1.1 }.prefix(15).map(\.0))
        return OfflineShelves(mixes: downloaded, mostPlayed: mostPlayed)
    }
}

/// A mix, cut down to the songs of it that are downloaded.
private nonisolated struct DownloadedMix: Identifiable, Sendable {
    let mix: Mix
    let songs: [OfflineSong]
    var id: String { mix.id }
}

/// One of every downloaded song. Its own view, so the song playing and its play count are
/// read per row rather than redrawing the whole page as songs change.
private struct OfflineSongRow: View {
    let song: OfflineSong
    let index: Int
    let songs: [OfflineSong]
    let context: PlayContext
    @Environment(PlayerModel.self) private var player
    @Environment(PlayFeed.self) private var feed

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                player.play(songs.request(startingAt: index), from: context)
            } label: {
                TrackRow(
                    title: song.title,
                    subtitle: song.artistName,
                    cover: song.cover,
                    plays: feed.facts[song.identity]?.plays,
                    isExplicit: song.isExplicit,
                    isCurrent: player.current?.songIdentity == song.identity
                )
            }
            .buttonStyle(RowButtonStyle())
            .contextMenu { OfflineSongMenu(song: song) }
            Divider().padding(.leading, 60)
        }
    }
}

/// Why Play is showing only downloads, and, when there's a connection, the way back.
private struct OfflineBanner: View {
    @Environment(YourMusic.self) private var music
    @AppStorage(OfflineMode.storageKey) private var isOfflineModeOn = false

    var body: some View {
        let isOnline = music.network.isOnline
        AdaptiveStack(verticalAlignment: .firstTextBaseline, spacing: Design.Spacing.small) {
            Label(isOnline ? "Offline Mode" : "You're Offline", systemImage: isOnline ? "arrow.down.circle" : "wifi.slash")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
            if isOnline {
                Button("Turn Off") { isOfflineModeOn = false }
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.borderless)
            }
        }
        .frame(minHeight: Design.Control.minimumTarget)
    }
}

/// A mix on the offline shelf: plays its downloaded songs, since its page needs a connection.
private struct OfflineMixTile: View {
    let mix: Mix
    let songs: [OfflineSong]
    @Environment(PlayerModel.self) private var player

    var body: some View {
        let context = PlayContext(kind: .mix, title: mix.kind.title)
        Button {
            player.play(songs.request(), from: context)
        } label: {
            TileLabel(title: mix.kind.title, subtitle: String(AttributedString(localized: "^[\(songs.count) song](inflect: true) downloaded").characters)) { side in
                MixCover(mix: mix, size: side)
            }
        }
        .buttonStyle(.pressable)
        .accessibilityHint("Plays the downloaded songs from this mix")
        .contextMenu {
            Button("Play", systemImage: "play") { player.play(songs.request(), from: context) }
            Button("Shuffle", systemImage: "shuffle") { player.play(songs.request(), from: context, shuffled: true) }
            Divider()
            Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
                player.enqueue(songs.request(), next: true, title: mix.kind.title)
            }
            Button("Play Last", systemImage: "text.line.last.and.arrowtriangle.forward") {
                player.enqueue(songs.request(), next: false, title: mix.kind.title)
            }
        }
    }
}

/// A downloaded song on a shelf: plays the shelf from it.
private struct OfflineSongTile: View {
    let song: OfflineSong
    let queue: [OfflineSong]
    let context: PlayContext
    @Environment(PlayerModel.self) private var player

    var body: some View {
        Button {
            player.play(queue.request(startingAt: queue.firstIndex(of: song) ?? 0), from: context)
        } label: {
            TileLabel(title: song.title, subtitle: song.artistName) { side in
                CoverImage(cover: song.cover, size: side)
            }
        }
        .buttonStyle(.pressable)
        .contextMenu { OfflineSongMenu(song: song) }
    }
}

/// What can be done with a downloaded song without a connection.
private struct OfflineSongMenu: View {
    let song: OfflineSong
    @Environment(PlayerModel.self) private var player
    @Environment(\.openPlayRoute) private var openPlayRoute

    var body: some View {
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
            player.enqueue([song].request(), next: true, title: song.title)
        }
        Button("Play Last", systemImage: "text.line.last.and.arrowtriangle.forward") {
            player.enqueue([song].request(), next: false, title: song.title)
        }
        Divider()
        Button("Your Stats", systemImage: "chart.bar.xaxis") {
            openPlayRoute(.stats(.song(song.identity)))
        }
    }
}

#if DEBUG
#Preview("Offline") {
    PlayPreviewHost { OfflineScreen().environment(DownloadedSongs(isDemo: true) { [] }) }
}
#endif

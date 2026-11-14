import SwiftUI
import TracksCore

/// A mix on the shelf: its covers, its name and why. On the Mac the pointer brings up
/// Play and More over its cover, as a library cover does, and it drags out as its songs.
struct MixTile: View {
    let mix: Mix
    @Environment(PlayerModel.self) private var player
    @State private var isHovered = false

    var body: some View {
        NavigationLink(value: PlayRoute.mix(mix.id)) {
            TileLabel(title: mix.kind.title, subtitle: mix.kind.tileLine) { side in
                MixCover(mix: mix, size: side)
            }
        }
        .buttonStyle(.pressable)
        .tileHover($isHovered, play: hasSongs ? { play(shuffled: false) } : nil) { menu }
        .contextMenu { menu }
        .macDraggable(SongDrag(player.songs(in: mix)))
    }

    /// Without making the songs: the tile's body runs as shelves are laid out.
    private var hasSongs: Bool {
        mix.songs.contains { player.canPlay(songID: $0.songID) }
    }

    /// The songs are made when an item is chosen, not each time the menu is.
    @ViewBuilder
    private var menu: some View {
        Button("Play", systemImage: "play") { play(shuffled: false) }
        Button("Shuffle", systemImage: "shuffle") { play(shuffled: true) }
        Divider()
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
            player.enqueue(.history(player.songs(in: mix)), next: true, title: mix.kind.title)
        }
        Button("Play Last", systemImage: "text.line.last.and.arrowtriangle.forward") {
            player.enqueue(.history(player.songs(in: mix)), next: false, title: mix.kind.title)
        }
    }

    private func play(shuffled: Bool) {
        player.play(.history(player.songs(in: mix)), from: PlayContext(kind: .mix, title: mix.kind.title), shuffled: shuffled)
    }
}

/// A station, album or playlist on a shelf. Stations start playing; the rest open. On the Mac
/// the pointer brings up Play and More over its cover, as a library cover does, and it
/// drags out as its Apple Music link.
struct FeedTile: View {
    let item: FeedItem

    var body: some View {
        // Only a station waits to start playing, so only its tile carries what that takes.
        if let (request, context) = item.stationRequest {
            StationTile(item: item, request: request, context: context)
        } else if let route = item.route {
            FeedLinkTile(item: item, route: route)
        }
    }
}

/// An album or playlist on a shelf, which opens.
private struct FeedLinkTile: View {
    let item: FeedItem
    let route: PlayRoute
    @Environment(PlayerModel.self) private var player
    @State private var isHovered = false

    var body: some View {
        NavigationLink(value: route) {
            TileLabel(title: item.title, subtitle: item.subtitle) { side in
                CoverImage(cover: item.cover, size: side)
            }
        }
        .buttonStyle(.pressable)
        .tileHover($isHovered, play: play) { FeedItemMenu(item: item) }
        .contextMenu { FeedItemMenu(item: item) }
        .macDraggable(item.url)
    }

    /// What Play over the cover does: the album or playlist from the top.
    private func play() {
        switch item.content {
        case .album(let album):
            player.play(.album(album), from: PlayContext(kind: .album, title: album.title))
        case .playlist(let playlist):
            player.play(.playlist(playlist), from: PlayContext(kind: .playlist, title: playlist.name))
        case .station, .demoStation:
            break
        }
    }
}

/// A station on a shelf, which starts playing, with a spinner until it does.
private struct StationTile: View {
    let item: FeedItem
    let request: PlayRequest
    let context: PlayContext
    @Environment(PlayerModel.self) private var player
    @State private var isHovered = false
    /// From a tap on a station until it plays: a live station takes a moment to tune in.
    @State private var isStarting = false

    var body: some View {
        Button {
            if !isPlayingThis { isStarting = true }
            player.play(request, from: context)
        } label: {
            TileLabel(title: item.title, subtitle: item.subtitle) { side in
                CoverImage(cover: item.cover, size: side)
                    .overlay(alignment: .topLeading) {
                        if item.isLive {
                            LiveBadge().padding(8)
                        }
                    }
                    .overlay(alignment: .bottomTrailing) {
                        if isPlayingThis {
                            NowPlayingBadge().padding(8)
                        } else {
                            PlayStartingBadge(isStarting: isStarting).padding(8)
                        }
                    }
            }
        }
        .accessibilityHint("Plays the station")
        .buttonStyle(.pressable)
        .tileHover($isHovered, play: { player.play(request, from: context) }) { FeedItemMenu(item: item) }
        .contextMenu { FeedItemMenu(item: item) }
        .macDraggable(item.url)
        .endsPlayStart($isStarting, hasStarted: isPlayingThis)
    }

    private var isPlayingThis: Bool {
        guard let playing = player.context, playing.isStation else { return false }
        return playing.title == item.title && player.hasQueue
    }
}

extension FeedItem {
    /// Its Apple Music link, for dragging out.
    var url: URL? {
        switch content {
        case .station(let station): station.url
        case .album(let album): album.url
        case .playlist(let playlist): playlist.url
        case .demoStation: nil
        }
    }

    /// Where an album or playlist opens. Stations play instead.
    var route: PlayRoute? {
        switch content {
        case .album(let album): .album(album)
        case .playlist(let playlist): .playlist(playlist)
        case .station, .demoStation: nil
        }
    }
}

/// Everything that can be done with a station, album or playlist, for its context menu.
struct FeedItemMenu: View {
    let item: FeedItem
    @Environment(PlayerModel.self) private var player

    var body: some View {
        switch item.content {
        case .station, .demoStation:
            if let (request, context) = item.stationRequest {
                Button("Play Station", systemImage: "play") { player.play(request, from: context) }
            }
        case .album(let album):
            let context = PlayContext(kind: .album, title: album.title)
            Button("Play", systemImage: "play") { player.play(.album(album), from: context) }
            Button("Shuffle", systemImage: "shuffle") { player.play(.album(album), from: context, shuffled: true) }
            Divider()
            Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
                player.enqueue(.album(album), next: true, title: album.title)
            }
            Button("Play Last", systemImage: "text.line.last.and.arrowtriangle.forward") {
                player.enqueue(.album(album), next: false, title: album.title)
            }
        case .playlist(let playlist):
            let context = PlayContext(kind: .playlist, title: playlist.name)
            Button("Play", systemImage: "play") { player.play(.playlist(playlist), from: context) }
            Button("Shuffle", systemImage: "shuffle") { player.play(.playlist(playlist), from: context, shuffled: true) }
            Divider()
            Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
                player.enqueue(.playlist(playlist), next: true, title: playlist.name)
            }
            Button("Play Last", systemImage: "text.line.last.and.arrowtriangle.forward") {
                player.enqueue(.playlist(playlist), next: false, title: playlist.name)
            }
        }
    }
}

/// Bars that move while this is what's playing, as on Music's covers.
struct NowPlayingBadge: View {
    @Environment(PlayerModel.self) private var player

    var body: some View {
        PlayingWaveform(isActive: player.isPlaying, color: .white)
            .font(.caption.weight(.bold))
            .frame(width: 26, height: 26)
            .background(.black.opacity(0.45), in: .circle)
            .accessibilityLabel("Now Playing")
    }
}

extension View {
    /// Music's hover on a shelf tile: Play on the left of the cover's foot and More on the
    /// right, as ``LibraryCoverTile`` has. Nothing on iPhone, or on a tile that's disabled.
    func tileHover<Menu: View>(_ isHovered: Binding<Bool>, play: (() -> Void)?, @ViewBuilder menu: () -> Menu) -> some View {
        #if os(macOS)
        modifier(TileHover(isHovered: isHovered, play: play, menu: menu()))
        #else
        self
        #endif
    }
}

#if os(macOS)
private struct TileHover<Menu: View>: ViewModifier {
    @Binding var isHovered: Bool
    let play: (() -> Void)?
    let menu: Menu
    @Environment(\.isEnabled) private var isEnabled

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                // Over the cover's foot: a square as wide as the tile, the cover's place.
                if isHovered, isEnabled {
                    Color.clear
                        .aspectRatio(1, contentMode: .fit)
                        .overlay(alignment: .bottom) {
                            HStack {
                                if let play {
                                    LibraryHoverPlayButton(action: play)
                                }
                                Spacer(minLength: 0)
                                LibraryHoverMoreButton { menu }
                            }
                            .padding(10)
                        }
                        .transition(.opacity)
                }
            }
            .onHover { hovering in
                withAnimation(PlayMotion.hover) { isHovered = hovering && isEnabled }
            }
    }
}
#endif

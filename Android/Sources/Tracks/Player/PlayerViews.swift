import SwiftUI
import TracksStats

/// A Material icon button: a 24 dp symbol in a 48 dp target.
struct IconButton: View {
    let symbol: Symbol
    let label: String
    var role: MaterialRole = .onSurfaceVariant
    var size: CGFloat = 24
    var filled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            SymbolImage(symbol, size: size, filled: filled, role: role)
                .frame(width: max(Space.touchTarget, size + 24), height: max(Space.touchTarget, size + 24))
                .fullTapArea()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}

/// What's playing, docked above the navigation bar: its cover turning like a record, the song,
/// play or pause, next, and a thin wave of progress, all in the cover's colours. Tapping it opens
/// Now Playing.
struct MiniPlayer: View {
    @Environment(PlayerModel.self) var player
    @Environment(ServerStore.self) var servers
    let open: () -> Void

    var body: some View {
        if let song = player.current {
            CoverColored(url: servers.coverURL(song.coverRef, size: NowPlayingScreen.coverSize)) {
                VStack(spacing: 0) {
                    HStack(spacing: 12) {
                        Button(action: open) {
                            HStack(spacing: 12) {
                                SpinningCover(url: servers.coverURL(song.coverRef, size: 150), isPlaying: player.isPlaying, size: 48)
                                VStack(alignment: .leading, spacing: 0) {
                                    Text(song.title)
                                        .materialType(.titleSmall)
                                        .materialForeground(.onPrimaryContainer)
                                        .lineLimit(1)
                                    Text(song.artist)
                                        .materialType(.bodySmall)
                                        .materialForeground(.onPrimaryContainer)
                                        .opacity(0.8)
                                        .lineLimit(1)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .fullTapArea()
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Now playing: \(song.title) by \(song.artist)")

                        IconButton(symbol: player.isPlaying ? .pause : .play, label: player.isPlaying ? "Pause" : "Play", role: .onPrimaryContainer, filled: true) {
                            player.toggle()
                        }
                        IconButton(symbol: .skipNext, label: "Next", role: .onPrimaryContainer, filled: true) {
                            player.next()
                        }
                    }
                    .padding(.leading, 8)
                    .padding(.trailing, 4)
                    .padding(.top, 8)

                    WavySeekBar(position: player.position, duration: player.duration, isPlaying: player.isPlaying, interactive: false)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 6)
                }
                .materialBackground(.primaryContainer, cornerRadius: Corner.extraLarge)
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 8)
        }
    }
}

/// The song playing, big, in its cover's colours: the cover, a wavy seek bar, the transport, and
/// what's next. Swiping the cover skips.
struct NowPlayingScreen: View {
    @Environment(PlayerModel.self) var player
    @Environment(AppModel.self) var model
    @Environment(ServerStore.self) var servers
    @Environment(\.dismiss) var dismiss
    /// Where the seek bar's handle is while a finger is on it.
    @State var scrubbing: Double?

    /// The size covers are asked for here and for the colours they lend, so both share one
    /// download.
    static let coverSize = 600

    var body: some View {
        CoverColored(url: coverURL) {
            VStack(spacing: 0) {
                header
                ScrollView {
                    VStack(spacing: 0) {
                        if let song = player.current {
                            MorphingCover(url: coverURL, isPlaying: player.isPlaying, size: 312, next: { player.next() }, previous: { player.previous() })
                                .padding(.top, 20)

                            VStack(alignment: .leading, spacing: 4) {
                                Text(song.title)
                                    .materialType(.headlineMediumEmphasized)
                                    .materialForeground(.onSurface)
                                    .lineLimit(2)
                                Text(song.artist)
                                    .materialType(.titleMedium)
                                    .materialForeground(.primary)
                                    .lineLimit(1)
                                let plays = model.playCount(of: song)
                                if plays > 0 {
                                    Text(plays == 1 ? "You've played this once" : "You've played this \(plays) times")
                                        .materialType(.labelLarge)
                                        .materialForeground(.onTertiaryContainer)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .materialBackground(.tertiaryContainer, cornerRadius: 16)
                                        .padding(.top, 10)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, 28)

                            scrubber.padding(.top, 20)
                            transport.padding(.top, 12)

                            if !player.upNext.isEmpty {
                                upNext.padding(.top, 32)
                            }
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 32)
                }
            }
            .background {
                Color.clear
                    .materialBackground(.surfaceContainerLow)
                    .ignoresSafeArea()
            }
        }
        .onChange(of: player.hasQueue) { _, hasQueue in
            if !hasQueue { dismiss() }
        }
    }

    private var coverURL: URL? {
        servers.coverURL(player.current?.coverRef, size: Self.coverSize)
    }

    private var header: some View {
        HStack(spacing: 0) {
            IconButton(symbol: .collapse, label: "Close", role: .onSurface) { dismiss() }
            VStack(spacing: 0) {
                Text("Playing from")
                    .materialType(.labelMedium)
                    .materialForeground(.onSurfaceVariant)
                Text(player.source)
                    .materialType(.titleSmall)
                    .materialForeground(.onSurface)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            Color.clear.frame(width: Space.touchTarget, height: Space.touchTarget)
        }
        .padding(.horizontal, 4)
        .padding(.top, 8)
    }

    private var scrubber: some View {
        let length = max(player.duration, 1)
        let shown = scrubbing ?? min(player.position, length)
        return VStack(spacing: 2) {
            WavySeekBar(
                position: player.position,
                duration: length,
                isPlaying: player.isPlaying,
                valueLabel: "\(Self.time(shown)) of \(Self.time(length))",
                seek: { player.seek(to: $0) },
                scrub: { scrubbing = $0 }
            )
            HStack {
                Text(Self.time(shown))
                Spacer()
                Text("−" + Self.time(max(length - shown, 0)))
            }
            .materialType(.labelMedium)
            .materialForeground(.onSurfaceVariant)
            .padding(.horizontal, 12)
        }
    }

    private var transport: some View {
        HStack(spacing: 0) {
            IconButton(symbol: .shuffle, label: player.shuffle ? "Shuffle on" : "Shuffle off", role: player.shuffle ? .primary : .onSurfaceVariant) {
                player.toggleShuffle()
            }
            Spacer(minLength: 0)
            IconButton(symbol: .skipPrevious, label: "Previous", role: .onSurface, size: 36, filled: true) {
                player.previous()
            }
            Spacer(minLength: 0)
            PlayPauseButton(isPlaying: player.isPlaying) { player.toggle() }
            Spacer(minLength: 0)
            IconButton(symbol: .skipNext, label: "Next", role: .onSurface, size: 36, filled: true) {
                player.next()
            }
            Spacer(minLength: 0)
            IconButton(
                symbol: player.repeatMode == 1 ? .repeatOne : .repeatAll,
                label: ["Repeat off", "Repeat song", "Repeat all"][player.repeatMode],
                role: player.repeatMode == 0 ? .onSurfaceVariant : .primary
            ) {
                player.cycleRepeat()
            }
        }
    }

    private var upNext: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Up next")
                .materialType(.titleMediumEmphasized)
                .materialForeground(.onSurface)
                .padding(.bottom, 8)
            ForEach(Array(player.upNext.prefix(20).enumerated()), id: \.element.id) { offset, song in
                Button {
                    player.skip(to: player.index + 1 + offset)
                } label: {
                    HStack(spacing: Space.iconGap) {
                        Artwork(url: song.coverRef, size: 48)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(song.title)
                                .materialType(.bodyLarge)
                                .materialForeground(.onSurface)
                                .lineLimit(1)
                            Text(song.artist)
                                .materialType(.bodyMedium)
                                .materialForeground(.onSurfaceVariant)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 6)
                    .fullTapArea()
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// "3:07", or "1:02:40" past an hour.
    static func time(_ seconds: Double) -> String {
        let total = Int(seconds.rounded(.down))
        let (hours, minutes, secs) = (total / 3600, (total % 3600) / 60, total % 60)
        let padded = secs < 10 ? "0\(secs)" : "\(secs)"
        if hours > 0 { return "\(hours):\(minutes < 10 ? "0\(minutes)" : "\(minutes)"):\(padded)" }
        return "\(minutes):\(padded)"
    }
}

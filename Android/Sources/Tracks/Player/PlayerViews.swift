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

/// What's playing, docked above the navigation bar: cover, song, play or pause, next, and a
/// thin line of progress. Tapping it opens Now Playing.
struct MiniPlayer: View {
    @Environment(PlayerModel.self) var player
    let open: () -> Void

    var body: some View {
        if let song = player.current {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Button(action: open) {
                        HStack(spacing: 12) {
                            Artwork(url: song.coverRef, size: 44, corner: Corner.small)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(song.title)
                                    .materialType(.titleSmall)
                                    .materialForeground(.onSurface)
                                    .lineLimit(1)
                                Text(song.artist)
                                    .materialType(.bodySmall)
                                    .materialForeground(.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .fullTapArea()
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Now playing: \(song.title) by \(song.artist)")

                    IconButton(symbol: player.isPlaying ? .pause : .play, label: player.isPlaying ? "Pause" : "Play", role: .onSurface, filled: true) {
                        player.toggle()
                    }
                    IconButton(symbol: .skipNext, label: "Next", role: .onSurface, filled: true) {
                        player.next()
                    }
                }
                .padding(.leading, 10)
                .padding(.trailing, 4)
                .padding(.vertical, 8)

                ProgressLine(fraction: player.duration > 0 ? player.position / player.duration : 0)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 4)
            }
            .materialBackground(.surfaceContainerHighest, cornerRadius: Corner.large)
            .padding(.horizontal, 8)
            .padding(.bottom, 8)
        }
    }
}

/// A 2 dp track and indicator in the primary colour.
struct ProgressLine: View {
    let fraction: Double

    var body: some View {
        ZStack(alignment: .leading) {
            Color.clear.frame(height: 2).frame(maxWidth: .infinity).materialBackground(.outlineVariant, cornerRadius: 1)
            HStack(spacing: 0) {
                Color.clear.frame(height: 2).frame(maxWidth: .infinity).materialBackground(.primary, cornerRadius: 1)
                    .scaleEffect(x: max(0, min(fraction, 1)), y: 1, anchor: .leading)
            }
        }
        .frame(height: 2)
        .accessibilityHidden(true)
    }
}

/// The song playing, big: cover, a scrubber, the transport, and what's next.
struct NowPlayingScreen: View {
    @Environment(PlayerModel.self) var player
    @Environment(AppModel.self) var model
    @Environment(\.dismiss) var dismiss
    /// Where the scrubber is while a finger is on it.
    @State var scrubbing: Double?

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: 0) {
                    if let song = player.current {
                        Artwork(url: song.coverRef, size: 320, corner: Corner.extraLarge)
                            .padding(.top, 16)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(song.title)
                                .materialType(.headlineSmall)
                                .materialForeground(.onSurface)
                                .lineLimit(2)
                            Text(song.artist)
                                .materialType(.titleMedium)
                                .materialForeground(.onSurfaceVariant)
                                .lineLimit(1)
                            let plays = model.playCount(of: song)
                            if plays > 0 {
                                Text(plays == 1 ? "You've played this once" : "You've played this \(plays) times")
                                    .materialType(.labelLarge)
                                    .materialForeground(.onTertiaryContainer)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .materialBackground(.tertiaryContainer, cornerRadius: Corner.small)
                                    .padding(.top, 8)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 32)

                        scrubber.padding(.top, 20)
                        transport.padding(.top, 8)

                        if !player.upNext.isEmpty {
                            upNext.padding(.top, 32)
                        }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 32)
            }
        }
        .materialBackground(.surfaceContainerLow)
        .onChange(of: player.hasQueue) { _, hasQueue in
            if !hasQueue { dismiss() }
        }
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
        return VStack(spacing: 0) {
            Slider(
                value: Binding(get: { scrubbing ?? min(player.position, length) }, set: { scrubbing = $0 }),
                in: 0...length,
                onEditingChanged: { editing in
                    if !editing, let target = scrubbing {
                        player.seek(to: target)
                        scrubbing = nil
                    }
                }
            )
            .accessibilityLabel("Position")
            HStack {
                Text(Self.time(scrubbing ?? player.position))
                Spacer()
                Text("−" + Self.time(max(length - (scrubbing ?? player.position), 0)))
            }
            .materialType(.labelMedium)
            .materialForeground(.onSurfaceVariant)
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
            Button {
                player.toggle()
            } label: {
                SymbolImage(player.isPlaying ? .pause : .play, size: 40, filled: true, role: .onPrimary)
                    .frame(width: 88, height: 88)
                    .materialBackground(.primary, cornerRadius: player.isPlaying ? 28 : 44)
                    .animation(.easeInOut(duration: 0.2), value: player.isPlaying)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
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
                .materialType(.titleMedium)
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

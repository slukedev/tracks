import SwiftUI
import TracksCore

/// The song playing, above the tab bar on every tab, as Music's mini player is. Tapping it
/// opens Now Playing, its cover growing into the player's; pushing it up opens it under the
/// finger. Pushed sideways, the song skips; held, it offers the song's menu.
struct MiniPlayer: View {
    let open: () -> Void
    let onNavigate: (NowPlayingDestination) -> Void
    @Environment(PlayerModel.self) private var player
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    @State private var handoff = PlayerHandoff.shared
    @State private var nextTaps = 0
    @State private var playTaps = 0
    @State private var opensUnderFinger = false
    @State private var rowWidth: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let track = player.current {
            HStack(spacing: 10) {
                GeometryReader { proxy in
                    SkipSwipe(travel: proxy.size.width + 10, state: .mini) { song in
                        row(song)
                            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .leading)
                    }
                    .onChange(of: coverFrame(in: proxy), initial: true) { _, frame in
                        handoff.miniCover = frame
                    }
                }
                .clipped()
                .contentShape(.rect)
                .onGeometryChange(for: CGFloat.self, of: \.size.width) { rowWidth = $0 }
                .gesture(SkipSwipeState.mini.pan(player: player, travel: rowWidth + 10, moves: !reduceMotion, onVertical: pushUp))
                .onTapGesture(perform: open)
                .accessibilityElement(children: .ignore)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction(.default, open)
                .contextMenu {
                    NowPlayingMenuItems(track: track, onNavigate: onNavigate)
                }
                .menuOrder(.fixed)
                .accessibilityLabel(Text("\(track.title), \(track.artistName)"))
                .accessibilityHint("Opens Now Playing")
                .accessibilityAction(named: "Next") { player.skipToNext() }
                .accessibilityAction(named: "Previous") { player.skipToPrevious() }

                Button {
                    playTaps += 1
                    player.togglePlayPause()
                } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title3)
                        .contentTransition(.symbolEffect(.replace.magic(fallback: .replace.downUp)))
                }
                .buttonStyle(PlayerButtonStyle(diameter: 38, highlight: .primary))
                .overlay {
                    // Asked to play, and the song's still coming: a server finding it, or the
                    // first seconds arriving.
                    if player.isPlaying, player.status == .loading {
                        TurningRing(diameter: 34, lineWidth: 2)
                            .transition(.opacity)
                            .allowsHitTesting(false)
                    }
                }
                .animation(.easeInOut(duration: 0.25), value: player.status == .loading)
                .accessibilityLabel(player.isPlaying ? "Pause" : "Play")
                .accessibilityValue(player.isPlaying && player.status == .loading ? Text("Loading") : Text(""))

                if !isInline {
                    Button {
                        nextTaps += 1
                        player.skipToNext()
                    } label: {
                        SkipArrows(direction: .forward, height: 15, trigger: nextTaps)
                    }
                    .buttonStyle(PlayerButtonStyle(diameter: 38, highlight: .primary))
                    .accessibilityLabel("Next")
                }
            }
            .foregroundStyle(.primary)
            .padding(.horizontal, 12)
            // The bar's height is the system's; bigger type would only be cut off.
            .dynamicTypeSize(...DynamicTypeSize.xxLarge)
            .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .global) }) { handoff.miniBar = $0 }
            .onDisappear {
                handoff.miniCover = nil
                handoff.miniBar = nil
            }
            .sensoryFeedback(.impact(weight: .medium, intensity: 0.8), trigger: playTaps)
            .sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: nextTaps)
            .sensoryFeedback(.impact(flexibility: .soft, intensity: 0.5), trigger: handoff.landings)
        }
    }

    private func row(_ song: PlayerTrack) -> some View {
        HStack(spacing: 10) {
            CoverImage(cover: song.cover, size: coverSide)
                .opacity(handoff.hidesMiniCover ? 0 : 1)
            VStack(alignment: .leading, spacing: 0) {
                Text(song.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if !isInline {
                    Text(song.artistName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
    }

    private var coverSide: CGFloat { isInline ? 26 : 32 }

    /// The cover's place on the screen: at the row's leading edge, centred in its height.
    /// Worked out from the row, which stays put while a song is pushed aside.
    private func coverFrame(in proxy: GeometryProxy) -> CGRect {
        let row = proxy.frame(in: .global)
        return CGRect(x: row.minX, y: row.midY - coverSide / 2, width: coverSide, height: coverSide)
    }

    private func pushUp(_ value: PanGesture.Value, hasEnded: Bool) {
        let height = value.translation.height
        if !hasEnded, !opensUnderFinger {
            guard height < 0, player.current != nil else { return }
            opensUnderFinger = true
            handoff.beginDrag(fromMiniPlayer: true)
        }
        guard opensUnderFinger else { return }
        if hasEnded {
            opensUnderFinger = false
            handoff.endDrag(translation: height, predicted: value.predictedEndTranslation.height, velocity: value.velocity.height)
        } else {
            handoff.drag(height)
        }
    }

    private var isInline: Bool { placement == .inline }
}

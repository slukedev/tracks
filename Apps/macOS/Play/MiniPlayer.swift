import SwiftUI
import AVKit
import MusicKit
import TracksCore

/// Music's Mini Player: the cover alone in a small window that stays above the others, with
/// the song and its controls over it while the pointer is there.
struct MiniPlayerWindow: Scene {
    static let id = "mini-player"
    /// Whether it's open, for the Window menu's Switch to and from Mini Player.
    static let presence = Presence()
    let model: AppModel

    @MainActor
    @Observable
    final class Presence {
        fileprivate(set) var isOpen = false
    }

    var body: some Scene {
        Window("Mini Player", id: Self.id) {
            if let capture = model.capture {
                MiniPlayer()
                    .opensSongInfo(model)
                    .tracksOnScreen()
                    .environment(model)
                    .environment(model.player)
                    .environment(model.playFeed)
                    .environment(model.yourMusic)
                    .environment(capture)
                    .environment(\.openPlayRoute, OpenPlayRouteAction(stack: "miniPlayer") { route in
                        // Pages open in the main window, brought forward for them, or opened
                        // again if it was closed.
                        model.playNavigator.show(route)
                        if let openWindow = MacAppDelegate.openWindow {
                            MainWindow.bringForward(openWindow: openWindow)
                        } else {
                            NSApp.activate(ignoringOtherApps: true)
                            MainWindow.open?.makeKeyAndOrderFront(nil)
                        }
                    })
            }
        }
        // No title bar at all: the cover is the whole window, dragged anywhere by its cover.
        .windowStyle(.plain)
        .windowResizability(.contentSize)
        .windowLevel(.floating)
        .windowBackgroundDragBehavior(.enabled)
        .defaultPosition(.bottomTrailing)
        .restorationBehavior(.disabled)
        // The Window menu has it, as Switch to Mini Player and Mini Player.
        .commandsRemoved()
    }
}

private struct MiniPlayer: View {
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.isOnScreen) private var isOnScreen
    @Environment(\.dismissWindow) private var dismissWindow
    @Environment(\.openWindow) private var openWindow
    @Environment(YourMusic.self) private var music
    @State private var isHovering = false
    @State private var tint: Color?

    static let side: CGFloat = 300
    static let cornerRadius: CGFloat = 14

    init(isHovering: Bool = false) {
        _isHovering = State(initialValue: isHovering)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            if let track = player.current {
                CoverImage(cover: track.cover, size: Self.side, isBare: true)
                    .accessibilityHidden(true)
                // Always there, so VoiceOver and the keyboard can reach them; shown while the
                // pointer is over the window, or the music is paused.
                MiniPlayerControls(track: track, cover: track.cover)
                    .opacity(showsControls ? 1 : 0)
                    .allowsHitTesting(showsControls)
                    // Hidden, its scrubber has nothing to tick for.
                    .environment(\.isOnScreen, isOnScreen && showsControls)
            } else {
                nothingPlaying
            }
        }
        .overlay(alignment: .top) {
            MiniPlayerTopBar(tint: tint, close: close, switchToMainWindow: switchToMainWindow)
                .opacity(isHovering || voiceOver ? 1 : 0)
                .allowsHitTesting(isHovering || voiceOver)
        }
        .frame(width: Self.side, height: Self.side)
        .background {
            // The cover fills the window, so only its colour shows, round the edges as it loads.
            CoverStage(tint: tint)
        }
        .clipShape(.rect(cornerRadius: Self.cornerRadius, style: .continuous))
        .environment(\.colorScheme, .dark)
        .coverTint(of: player.current?.cover, into: $tint)
        .onHover { hovering in
            withAnimation(Design.Motion.fade) { isHovering = hovering }
        }
        .focusable()
        .focusEffectDisabled()
        .onAppear {
            MiniPlayerWindow.presence.isOpen = true
            MacAppDelegate.openWindow = openWindow
        }
        .onDisappear { MiniPlayerWindow.presence.isOpen = false }
        // Add to Playlist picks in the main window, so it comes forward for the sheet.
        .onChange(of: music.playlists.picking?.id) { _, id in
            if id != nil { MainWindow.bringForward(openWindow: openWindow) }
        }
        .contextMenu {
            if let track = player.current {
                Button(player.isPlaying ? "Pause" : "Play", systemImage: player.isPlaying ? "pause" : "play") { player.togglePlayPause() }
                Button("Next", systemImage: "forward") { player.skipToNext() }
                Button("Previous", systemImage: "backward") { player.skipToPrevious() }
                    .disabled(player.context?.isStation == true)
                Divider()
                NowPlayingMenuItems(track: track)
            }
            Divider()
            Button("Switch from Mini Player", systemImage: "macwindow", action: switchToMainWindow)
            Button("Close Mini Player", systemImage: "xmark", action: close)
        }
    }

    private var showsControls: Bool {
        isHovering || !player.isPlaying || voiceOver
    }

    private func close() {
        dismissWindow(id: MiniPlayerWindow.id)
    }

    /// Music's: the main window back in its place.
    private func switchToMainWindow() {
        dismissWindow(id: MiniPlayerWindow.id)
        MainWindow.bringForward(openWindow: openWindow)
    }

    private var nothingPlaying: some View {
        VStack(spacing: Design.Spacing.medium) {
            Image(systemName: "music.note")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text("Nothing Playing")
                .font(.headline)
            PlayButton("Play Tracks Radio", systemImage: "dot.radiowaves.left.and.right", tint: tint ?? CoverStage.fallback) {
                player.playTracksRadio()
            }
        }
        .foregroundStyle(.white)
    }
}

/// Close where a window's own button is, and a capsule of glass in the cover's colour at the
/// other corner for where the music plays and the main window, as Music's MiniPlayer has.
private struct MiniPlayerTopBar: View {
    let tint: Color?
    let close: () -> Void
    let switchToMainWindow: () -> Void

    var body: some View {
        HStack(alignment: .top) {
            Button("Close Mini Player", systemImage: "xmark", action: close)
                .labelStyle(.iconOnly)
                .buttonStyle(MiniGlassCircleStyle(diameter: 24))
                .help("Close Mini Player")
            Spacer()
            HStack(spacing: 0) {
                MiniRoutePicker()
                    .frame(width: 28, height: 28)
                    .help("AirPlay")
                Button("Switch from Mini Player", systemImage: "macwindow", action: switchToMainWindow)
                    .labelStyle(.iconOnly)
                    .buttonStyle(BarIconButtonStyle(diameter: 28))
                    .help("Switch from Mini Player (⇧⌘M)")
            }
            .padding(.horizontal, Design.Spacing.xSmall)
            .padding(.vertical, Design.Spacing.xxSmall)
            .glassEffect(.regular.tint(tint?.opacity(0.55)).interactive(), in: .capsule)
        }
        .padding(Design.Spacing.medium)
        .foregroundStyle(.white)
    }
}

/// The song over the foot of its cover, as Music's MiniPlayer shows it: the cover blurring
/// progressively under the words, the song and its Favorite and More, the scrubber, then the
/// transport with Shuffle and Repeat at its ends.
private struct MiniPlayerControls: View {
    let track: PlayerTrack
    let cover: CoverArt
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var nextPresses = 0
    @State private var previousPresses = 0

    var body: some View {
        VStack(spacing: Design.Spacing.small) {
            HStack(alignment: .center, spacing: Design.Spacing.small) {
                VStack(alignment: .leading, spacing: Design.Spacing.xxSmall) {
                    Text(track.title)
                        .font(.title3.bold())
                        .lineLimit(1)
                    Text(track.artistName)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    PlayCountLine(track: track, showsSince: false)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)

                GlassEffectContainer(spacing: Design.Spacing.small) {
                    HStack(spacing: Design.Spacing.small) {
                        if let song = track.song {
                            MiniFavoriteButton(song: song)
                        }
                        Menu {
                            NowPlayingMenuItems(track: track, showsFavorite: track.song == nil)
                        } label: {
                            Image(systemName: "ellipsis")
                                .accessibilityLabel("More")
                        }
                        .menuStyle(.button)
                        .menuIndicator(.hidden)
                        .buttonStyle(MiniGlassCircleStyle())
                        .fixedSize()
                        .help("More")
                    }
                }
            }

            if let duration = track.duration, duration > 0 {
                BarScrubber(duration: duration)
            }

            transport
        }
        .padding(.horizontal, Design.Spacing.large)
        .padding(.top, 40)
        .padding(.bottom, Design.Spacing.medium)
        .frame(maxWidth: .infinity)
        .background { ProgressiveCoverBlur(cover: cover) }
        .foregroundStyle(.white)
    }

    private var transport: some View {
        HStack(spacing: 0) {
            ModeButton(title: player.isShuffled ? "Turn Off Shuffle" : "Shuffle", systemImage: "shuffle", isOn: player.isShuffled) {
                player.toggleShuffle()
            }
            .disabled(!canReorder)

            Spacer(minLength: 0)

            HStack(spacing: Design.Spacing.large) {
                Button {
                    previousPresses += 1
                    player.skipToPrevious()
                } label: {
                    SkipArrows(direction: .backward, height: 14, trigger: previousPresses)
                }
                .buttonStyle(BarIconButtonStyle(diameter: 36))
                .disabled(player.context?.isStation == true)
                .help("Previous (⌘←)")
                .accessibilityLabel("Previous")

                Button {
                    player.togglePlayPause()
                } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 26))
                        .contentTransition(reduceMotion ? .opacity : .symbolEffect(.replace))
                }
                .buttonStyle(BarIconButtonStyle(diameter: 44))
                .help(player.isPlaying ? "Pause (Space)" : "Play (Space)")
                .accessibilityLabel(player.isPlaying ? "Pause" : "Play")

                Button {
                    nextPresses += 1
                    player.skipToNext()
                } label: {
                    SkipArrows(direction: .forward, height: 14, trigger: nextPresses)
                }
                .buttonStyle(BarIconButtonStyle(diameter: 36))
                .help("Next (⌘→)")
                .accessibilityLabel("Next")
            }

            Spacer(minLength: 0)

            ModeButton(title: repeatTitle, systemImage: player.repeatMode == .one ? "repeat.1" : "repeat", isOn: player.repeatMode != .off) {
                player.cycleRepeat()
            }
            .disabled(!canReorder)
        }
    }

    /// A station or a live mix picks as it goes: there's no order to shuffle or end to repeat.
    private var canReorder: Bool {
        player.context?.isStation != true && !player.isLive
    }

    private var repeatTitle: LocalizedStringKey {
        switch player.repeatMode {
        case .off: "Repeat"
        case .all: "Repeating All"
        case .one: "Repeating One Song"
        }
    }
}

/// Shuffle or Repeat: a plain glyph while off, in a circle of glass while on.
private struct ModeButton: View {
    let title: LocalizedStringKey
    let systemImage: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(MiniGlassCircleStyle(isGlass: isOn))
        .help(Text(title))
        .accessibilityLabel(Text(title))
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

/// Favorite, a star in a small glass circle, filled while the song is one.
private struct MiniFavoriteButton: View {
    let song: Song
    @Environment(PlayerModel.self) private var player
    /// Counts stars given here, so the press is felt but a lookup finding one isn't.
    @State private var starred = 0

    var body: some View {
        let isFavorite = player.isFavorite(song)
        Button {
            if !isFavorite { starred += 1 }
            player.setFavorite(song, !isFavorite)
        } label: {
            Image(systemName: isFavorite ? "star.fill" : "star")
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(MiniGlassCircleStyle())
        .help(isFavorite ? "Undo Favorite" : "Favorite")
        .accessibilityLabel(isFavorite ? "Undo Favorite" : "Favorite")
        .sensoryFeedback(.success, trigger: starred)
        .task(id: song.id) { player.lookUpFavorite(song) }
    }
}

/// A small round button on the cover: glass, which reads on any cover, dipping on a click
/// since glass doesn't answer one on the Mac. Without glass, a plain glyph that brightens
/// under the pointer, for a mode that's off.
private struct MiniGlassCircleStyle: ButtonStyle {
    var diameter: CGFloat = 28
    var isGlass = true
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovering = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: diameter * 0.45, weight: .semibold))
            .foregroundStyle(.white.opacity(isHovering || isGlass ? 1 : 0.85))
            .opacity(isEnabled ? 1 : 0.35)
            .frame(width: diameter, height: diameter)
            .glassEffect(isGlass ? .regular.interactive() : .identity, in: .circle)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.92 : 1)
            // A bigger target than the circle, as the Mac's are.
            .frame(minWidth: Design.Control.minimumTarget, minHeight: Design.Control.minimumTarget)
            .contentShape(.circle)
            .onHover { isHovering = $0 }
            .animation(Design.Motion.hover, value: isHovering)
            .animation(Design.Motion.press, value: configuration.isPressed)
    }
}

/// The cover again under the words, blurred more the lower it goes and deepened just enough
/// for white type, in place of a black veil.
private struct ProgressiveCoverBlur: View {
    let cover: CoverArt

    var body: some View {
        // The window's own cover, so the blur lines up with the art above it.
        Color.clear
            .overlay(alignment: .bottom) {
                CoverImage(cover: cover, size: MiniPlayer.side, isBare: true)
                    .blur(radius: 18, opaque: true)
            }
            .clipped()
            .mask {
                LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.45)], startPoint: .top, endPoint: .bottom)
            }
            .overlay {
                LinearGradient(colors: [.clear, .black.opacity(0.35)], startPoint: .top, endPoint: .bottom)
            }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// AirPlay, drawn white for the glass over the cover: the system's picker, so every speaker
/// the Mac knows is there.
private struct MiniRoutePicker: NSViewRepresentable {
    func makeNSView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.isRoutePickerButtonBordered = false
        view.appearance = NSAppearance(named: .darkAqua)
        view.setRoutePickerButtonColor(.white, for: .normal)
        return view
    }

    func updateNSView(_ view: AVRoutePickerView, context: Context) {}
}

#if DEBUG
@MainActor
private let previewModel: AppModel = {
    UserDefaults.standard.setVolatileDomain(["TracksDemoData": true], forName: UserDefaults.argumentDomain)
    return AppModel()
}()

private struct MiniPlayerPreview: View {
    var isPlaying = true
    var isHovering = false

    var body: some View {
        let model = previewModel
        MiniPlayer(isHovering: isHovering)
            .environment(model)
            .environment(model.player)
            .environment(model.playFeed)
            .environment(model.yourMusic)
            .environment(\.openPlayRoute, OpenPlayRouteAction(stack: "preview") { _ in })
            .modifier(PreviewCapture(model: model))
            .task {
                guard isPlaying else { return }
                // The demo feed builds its mixes a moment after the model starts.
                for _ in 0..<20 where !model.player.hasQueue {
                    if let mix = model.playFeed.mixes.mixes.first {
                        model.player.play(.history(model.player.songs(in: mix)), from: PlayContext(kind: .mix, title: mix.kind.title))
                    } else {
                        try? await Task.sleep(for: .milliseconds(250))
                    }
                }
            }
    }
}

private struct PreviewCapture: ViewModifier {
    let model: AppModel

    func body(content: Content) -> some View {
        if let capture = model.capture {
            content.environment(capture)
        } else {
            content
        }
    }
}

#Preview("Playing") { MiniPlayerPreview() }
#Preview("Playing, pointer over") { MiniPlayerPreview(isHovering: true) }
#Preview("Nothing playing") { MiniPlayerPreview(isPlaying: false) }
#endif

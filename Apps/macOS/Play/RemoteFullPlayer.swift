import SwiftUI
import TracksCore

/// The full player for a song on another of your devices: the same cover, background and
/// type as this Mac's own, with where it's playing said at the top, and its controls sent
/// there. Play on This Mac brings it here from where it's got to.
struct RemoteFullPlayer: View {
    let device: NearbyDevices.Device
    let close: () -> Void
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openPlayRoute) private var openPlayRoute
    @AppStorage(NowPlayingBackground.storageKey) private var background = NowPlayingBackground.defaultValue
    @State private var tint: Color?
    @State private var bleed: CoverBleed?
    @State private var fillsHeight = false
    /// The song as found in the music this Mac plays from, once it's been looked for.
    @State private var resolved: PlayerTrack?
    @State private var isMoving = false

    var body: some View {
        let state = model.nearby.shownState(of: device) ?? NearbyState()
        let named = AppModel.remoteTrack(state, on: device)
        let track = resolved.flatMap { $0.songIdentity == named.songIdentity ? $0 : nil } ?? named
        ZStack {
            Color.clear
            content(track, state: state)
                .environment(\.colorScheme, ink(.trailing).colorScheme)
                .foregroundStyle(.primary)
                .tint(.primary)
        }
        .nowPlayingBackdrop(cover: track.cover, tint: tint, isPlaying: state.isPlaying)
        .coverTint(of: track.cover, into: $tint)
        .task(id: track.cover) {
            let art = await BackdropArtStore.shared.art(for: track.cover)
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.6)) {
                bleed = art?.bleed
                fillsHeight = art?.fillsHeight ?? false
            }
        }
        .task(id: named.songIdentity) {
            resolved = await model.resolve(named)
        }
        .onExitCommand(perform: close)
    }

    private var isFullCover: Bool { background == .fullCover }

    private func ink(_ edge: CoverBleed.Edge) -> CoverBleed.Ink {
        guard isFullCover, let bleed else { return .light }
        return bleed.ink(edge)
    }

    private func content(_ track: PlayerTrack, state: NearbyState) -> some View {
        VStack(spacing: 0) {
            topBar(track)
                .environment(\.colorScheme, ink(.top).colorScheme)
            GeometryReader { proxy in
                let cap: CGFloat = isFullCover && fillsHeight ? .infinity : 480
                let side = max(220, min(cap, proxy.size.height - 40, proxy.size.width - FullPlayer.column - FullPlayer.gap - 96))
                HStack(alignment: .center, spacing: FullPlayer.gap) {
                    cover(track, isPlaying: state.isPlaying, side: side)
                    details(track, state: state)
                        .frame(width: FullPlayer.column)
                        .frame(height: max(min(side, 480), FullPlayer.columnHeight))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.horizontal, 32)
        }
        .padding(.bottom, 32)
        .ignoresSafeArea(edges: .top)
    }

    // MARK: - Top bar

    private func topBar(_ track: PlayerTrack) -> some View {
        ZStack {
            VStack(spacing: 1) {
                Text("Playing On")
                    .font(.caption.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundStyle(.secondary)
                Text("\(Image(systemName: device.kind.symbol)) \(device.nameWithSpeakers)")
                    .font(.headline)
                    .lineLimit(1)
            }
            .padding(.horizontal, isFullCover ? 16 : 0)
            .padding(.vertical, isFullCover ? 6 : 0)
            .glassEffect(isFullCover ? .regular : .identity, in: .capsule)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            HStack {
                Button("Exit Full Player", systemImage: "chevron.down", action: close)
                    .labelStyle(.iconOnly)
                    .buttonStyle(BarIconButtonStyle(diameter: 34))
                    .keyboardShortcut(.escape, modifiers: [])
                    .help("Exit Full Player (Esc)")
                Spacer()
                Menu("More", systemImage: "ellipsis") {
                    NowPlayingMenuItems(track: track, onNavigate: navigate, showsFavorite: false)
                    Divider()
                    Button("Play on This Mac", systemImage: "laptopcomputer", action: playHere)
                        .disabled(isMoving)
                    Button("Show This Mac's Player", systemImage: "xmark.circle") {
                        model.control(nil)
                    }
                }
                .labelStyle(.iconOnly)
                .menuStyle(.button)
                .buttonStyle(BarIconButtonStyle(diameter: 34))
                .menuIndicator(.hidden)
                .fixedSize()
                .help("More")
            }
        }
        .padding(.horizontal, FullPlayer.barInset)
        .padding(.top, FullPlayer.barInset)
        .padding(.bottom, 12)
    }

    // MARK: - Cover

    private func cover(_ track: PlayerTrack, isPlaying: Bool, side: CGFloat) -> some View {
        let sinks = !isPlaying && !reduceMotion && !isFullCover
        return Group {
            if isFullCover {
                Color.clear.frame(width: side, height: side)
            } else {
                CoverImage(cover: track.cover, size: side)
            }
        }
        .backdropFocus()
        .shadow(color: .black.opacity(isFullCover ? 0 : sinks ? 0.2 : 0.4), radius: sinks ? 14 : 30, y: sinks ? 6 : 16)
        .scaleEffect(sinks ? 0.88 : 1)
        .animation(reduceMotion ? nil : .spring(duration: 0.34, bounce: 0.2), value: sinks)
        .accessibilityHidden(true)
    }

    // MARK: - Details

    private func details(_ track: PlayerTrack, state: NearbyState) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                NowPlayingTitleMenu(track: track, onNavigate: navigate) {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(track.title)
                                .font(.system(size: 28, weight: .bold))
                                .lineLimit(2)
                            if track.isExplicit {
                                ExplicitBadge()
                            }
                        }
                        Text(track.artistName)
                            .font(.title2)
                            .foregroundStyle(.primary.opacity(0.82))
                            .lineLimit(1)
                    }
                    .multilineTextAlignment(.leading)
                }
                .help("Go to Album or Artist")
                RemotePlayCount(track: track)
                    .font(.title3)
                    .padding(.top, 6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Waiting on a skip: the song on show is on its way out.
            .opacity(model.nearby.isSkipping(device) ? 0.5 : 1)
            .animation(.easeOut(duration: 0.2), value: model.nearby.isSkipping(device))

            Spacer(minLength: 24)

            if let duration = state.duration, duration > 0 {
                Scrubber(duration: duration, isPlaying: state.isPlaying, time: { model.nearby.shownState(of: device)?.position(at: .now) ?? 0 }, interval: Scrubber.platformInterval) { time in
                    model.nearby.perform(.seek(time), on: device)
                }
            } else {
                LiveScrubber()
            }

            RemoteFullPlayerTransport(device: device, state: state)
                .padding(.top, 16)

            secondaryControls(track)
                .padding(.top, 24)
        }
    }

    private func secondaryControls(_ track: PlayerTrack) -> some View {
        HStack(spacing: 6) {
            if track.song != nil || track.local != nil {
                FavoriteStarButton(track: track) { systemImage, _ in
                    Label("Favorite", systemImage: systemImage)
                }
                .menuStyle(.button)
                .buttonStyle(BarIconButtonStyle(diameter: 36))
                .menuIndicator(.hidden)
                .fixedSize()
            }
            if model.nearby.isUnreachable(device) {
                Label("Can't Reach \(device.name)", systemImage: "exclamationmark.triangle.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .transition(.opacity)
            }
            Spacer(minLength: 8)
            Button(action: playHere) {
                if isMoving {
                    ProgressView().controlSize(.small)
                } else {
                    Label("Play on This Mac", systemImage: "laptopcomputer")
                }
            }
            .labelStyle(.titleAndIcon)
            .buttonStyle(.glass)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .disabled(isMoving)
            .help("Play on This Mac, from where it's got to")
        }
        .labelStyle(.iconOnly)
        .animation(.easeOut(duration: 0.2), value: model.nearby.isUnreachable(device))
    }

    private func navigate(_ destination: NowPlayingDestination) {
        close()
        switch destination {
        case .play(let route): openPlayRoute(route)
        case .stats(let route): openPlayRoute(.stats(route))
        }
    }

    /// Brings the song here. The full player stays open and goes on with it, now this Mac's.
    private func playHere() {
        isMoving = true
        Task {
            let moved = await model.playHere(from: device)
            isMoving = false
            if !moved {
                model.control(device)
                player.confirm(String(localized: "Couldn't Find This Song Here"))
            }
        }
    }
}

/// How often you've heard the song, from your history: the same words as this Mac's own
/// player, without the ring that counts this Mac's play.
private struct RemotePlayCount: View {
    let track: PlayerTrack
    @Environment(PlayFeed.self) private var feed

    var body: some View {
        let plays = feed.facts(for: track)?.plays ?? 0
        Group {
            if plays == 0 {
                Text("First listen")
            } else {
                Text("^[\(plays) play](inflect: true)")
            }
        }
        .font(.subheadline.weight(.medium))
        .monospacedDigit()
        .foregroundStyle(.secondary)
        .lineLimit(1)
    }
}

/// Previous, play or pause, and next, sent to the other device, drawn as this Mac's own.
private struct RemoteFullPlayerTransport: View {
    let device: NearbyDevices.Device
    let state: NearbyState
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var previousPresses = 0
    @State private var nextPresses = 0

    var body: some View {
        HStack(spacing: 28) {
            Button {
                previousPresses += 1
                model.nearby.perform(.previous, on: device)
            } label: {
                SkipArrows(direction: .backward, height: 17, trigger: previousPresses)
            }
            .buttonStyle(BarIconButtonStyle(diameter: 44))
            .disabled(!state.canSkipBack)
            .help("Previous (⌘←)")
            .accessibilityLabel("Previous")

            Button {
                model.nearby.perform(state.isPlaying ? .pause : .play, on: device)
            } label: {
                Image(systemName: state.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 30))
                    .contentTransition(reduceMotion ? .opacity : .symbolEffect(.replace.magic(fallback: .replace.downUp)))
                    .frame(width: 32)
            }
            .buttonStyle(BarIconButtonStyle(diameter: 56))
            .help(state.isPlaying ? "Pause (Space)" : "Play (Space)")
            .accessibilityLabel(state.isPlaying ? "Pause" : "Play")
            .accessibilityHint(Text("On \(device.name)"))

            Button {
                nextPresses += 1
                model.nearby.perform(.next, on: device)
            } label: {
                SkipArrows(direction: .forward, height: 17, trigger: nextPresses)
            }
            .buttonStyle(BarIconButtonStyle(diameter: 44))
            .disabled(!state.canSkipForward)
            .help("Next (⌘→)")
            .accessibilityLabel("Next")
        }
        .frame(maxWidth: .infinity)
    }
}

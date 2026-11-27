import SwiftUI
import TracksCore

/// The full player for a song on one of your servers' speakers: the same cover, background
/// and type as this iPhone's own, with the speaker named at the top, its controls sent
/// there, and the speaker's own controls (volume, power, the speakers playing along) where
/// this iPhone's volume would be.
struct SpeakerPlayerView: View {
    let address: SpeakerAddress
    let onNavigate: (NowPlayingDestination) -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @AppStorage(NowPlayingBackground.storageKey) private var background = NowPlayingBackground.defaultValue
    @State private var tint: Color?
    @State private var bleed: CoverBleed?
    @State private var showsSettings = false
    @State private var isMoving = false
    @State private var playTaps = 0
    @State private var skipTaps = 0
    @State private var modeTaps = 0

    var body: some View {
        let speaker = model.speakers.speaker(address)
        let track = speaker.map { model.speakerTrack($0, on: address.serverID) }
        ZStack {
            Color.clear
            if let speaker, let track {
                content(speaker, track: track)
                    .modifier(NowPlayingChromeFade())
                    .environment(\.colorScheme, ink(.bottom).colorScheme)
                    .foregroundStyle(.primary)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            }
        }
        .nowPlayingBackdrop(cover: track?.cover, tint: tint, isPlaying: speaker?.isPlaying == true, style: background)
        .nowPlayingPresentation(yields: false, cover: track?.cover, isFullCover: isFullCover, sinks: sinks(speaker))
        .coverTint(of: track?.cover, into: $tint)
        .task(id: track?.cover) {
            guard let cover = track?.cover else { return }
            let found = await BackdropArtStore.shared.art(for: cover)?.bleed
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.6)) { bleed = found }
        }
        .sheet(isPresented: $showsSettings) {
            NavigationStack {
                SpeakerSettingsPage(address: address)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close", systemImage: "xmark", role: .close) { showsSettings = false }
                        }
                    }
            }
            .presentationDetents([.medium, .large])
        }
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.8), trigger: playTaps)
        .sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: skipTaps)
        .sensoryFeedback(Design.Haptic.choice, trigger: modeTaps)
    }

    private var isFullCover: Bool { background == .fullCover }

    private func sinks(_ speaker: TracksideSpeaker?) -> Bool {
        speaker?.isPlaying != true && !reduceMotion && !isFullCover
    }

    private func ink(_ edge: CoverBleed.Edge) -> CoverBleed.Ink {
        guard isFullCover, let bleed else { return .light }
        return bleed.ink(edge)
    }

    // MARK: - Layout

    private func content(_ speaker: TracksideSpeaker, track: PlayerTrack) -> some View {
        GeometryReader { proxy in
            VStack(spacing: 0) {
                topBar(speaker)
                    .environment(\.colorScheme, ink(.top).colorScheme)
                Spacer(minLength: 12)
                cover(track, speaker: speaker, in: proxy)
                Spacer(minLength: 20)
                details(track, speaker: speaker)
                Spacer(minLength: 16)
                controls(speaker)
            }
            .padding(.horizontal, 28)
            .padding(.bottom, max(8, proxy.safeAreaInsets.bottom - 14))
            .frame(width: proxy.size.width, height: proxy.size.height + proxy.safeAreaInsets.bottom, alignment: .top)
        }
    }

    private func topBar(_ speaker: TracksideSpeaker) -> some View {
        ZStack {
            VStack(spacing: 1) {
                Text("Playing On")
                    .font(.caption2.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundStyle(.primary.opacity(0.6))
                Text("\(Image(systemName: speaker.symbol)) \(speaker.name)")
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
            }
            .padding(.horizontal, isFullCover ? 14 : 0)
            .padding(.vertical, isFullCover ? 6 : 0)
            .glassEffect(isFullCover ? .regular : .identity, in: .capsule)
            .padding(.horizontal, 56)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)

            HStack {
                Button("Close", systemImage: "chevron.down") { PlayerHandoff.shared.close() }
                    .labelStyle(.iconOnly)
                    .font(.title3.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
                    .glassEffect(isFullCover ? .regular.interactive() : .identity, in: .circle)
                Spacer()
                Menu {
                    Button(playHereTitle, systemImage: NearbyDevices.thisDeviceSymbol, action: playHere)
                        .disabled(isMoving)
                    if model.player.hasQueue {
                        Button("Show This iPhone's Player", systemImage: "xmark.circle") {
                            model.control(nil)
                        }
                    }
                    Button("Speaker Settings", systemImage: "gearshape") { showsSettings = true }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .contentShape(.circle)
                        .glassEffect(isFullCover ? .regular.interactive() : .identity, in: .circle)
                }
                .accessibilityLabel("More")
            }
        }
        .buttonStyle(.plain)
        .padding(.top, 8)
    }

    private func cover(_ track: PlayerTrack, speaker: TracksideSpeaker, in proxy: GeometryProxy) -> some View {
        let share = verticalSizeClass == .compact ? 0.5 : 0.4
        let side = max(0, min(proxy.size.width - 56, proxy.size.height * share, 420))
        let sinking = sinks(speaker)
        return Group {
            if isFullCover {
                Color.clear
                    .frame(width: proxy.size.width, height: min(proxy.size.width, side + 40))
                    .backdropFocus()
            } else {
                CoverImage(cover: track.cover, size: side)
                    .frame(width: side, height: side)
            }
        }
        .backdropFocus(isFullCover ? .none : .bounds)
        .shadow(color: .black.opacity(isFullCover ? 0 : sinking ? 0.2 : 0.4), radius: sinking ? 14 : 30, y: sinking ? 6 : 16)
        .scaleEffect(sinking ? 0.82 : 1)
        .animation(reduceMotion ? nil : .spring(duration: 0.34, bounce: 0.2), value: sinking)
        .opacity(model.speakers.isSkipping(address) ? 0.6 : 1)
        .animation(Design.Motion.reduced(Design.Motion.fade, reduceMotion: reduceMotion), value: model.speakers.isSkipping(address))
        .frame(maxWidth: .infinity)
        .accessibilityHidden(true)
    }

    private func details(_ track: PlayerTrack, speaker: TracksideSpeaker) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            NowPlayingTitleMenu(track: track, onNavigate: navigate) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(track.title)
                        .font(.title2.bold())
                        .lineLimit(2)
                    Text(track.artistName)
                        .font(.title3)
                        .foregroundStyle(.primary.opacity(0.82))
                        .lineLimit(1)
                }
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let line = notice(speaker) {
                Label(line, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.primary.opacity(0.7))
                    .lineLimit(2)
                    .padding(.top, 4)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentTransition(.opacity)
        .animation(Design.Motion.reduced(Design.Motion.fade, reduceMotion: reduceMotion), value: notice(speaker))
    }

    /// What's wrong right now, said once and calmly.
    private func notice(_ speaker: TracksideSpeaker) -> String? {
        if let problem = model.speakers.problem(for: address) { return problem.message }
        if model.speakers.board(address.serverID)?.isServerReachable == false { return String(localized: "Can't Reach Your Server") }
        if speaker.availability == .poweredOff || speaker.powered == false { return String(localized: "\(speaker.name) is turned off.") }
        if !speaker.isReachable { return String(localized: "Can't Reach \(speaker.name)") }
        return nil
    }

    private func controls(_ speaker: TracksideSpeaker) -> some View {
        let canControl = model.speakers.canControl(address.serverID) && speaker.isReachable
        return VStack(spacing: 22) {
            if let duration = speaker.nowPlaying?.duration {
                if speaker.can(.seek), canControl {
                    Scrubber(duration: duration, isPlaying: speaker.isPlaying, time: { model.speakers.position(of: address, at: .now) ?? 0 }) { time in
                        model.speakers.perform(.seek(time), on: address)
                    }
                } else {
                    SpeakerScrubber(address: address, speaker: speaker)
                }
            } else {
                LiveScrubber()
            }

            transport(speaker)
                .disabled(!canControl)

            SpeakerDeviceControls(address: address, speaker: speaker) { showsSettings = true }
        }
    }

    private func transport(_ speaker: TracksideSpeaker) -> some View {
        HStack {
            modeButton(speaker, isShuffle: true)
            Spacer()
            if speaker.can(.nextPrevious) {
                Button {
                    skipTaps += 1
                    model.speakers.perform(.previous, on: address)
                } label: {
                    SkipArrows(direction: .backward, height: 24, trigger: skipTaps)
                }
                .buttonStyle(PlayerButtonStyle(diameter: 68))
                .accessibilityLabel("Previous")
                Spacer()
            }
            if speaker.can(.pause) {
                Button {
                    playTaps += 1
                    model.speakers.perform(speaker.isPlaying ? .pause : .play, on: address)
                } label: {
                    Image(systemName: speaker.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 44))
                        .contentTransition(reduceMotion ? .opacity : .symbolEffect(.replace.magic(fallback: .replace.downUp)))
                }
                .buttonStyle(PlayerButtonStyle(diameter: 84))
                .overlay {
                    if model.speakers.isConnecting(address) || speaker.playback?.state == .buffering {
                        TurningRing(diameter: 80, lineWidth: 2.5, color: .primary.opacity(0.5))
                            .transition(.opacity)
                            .allowsHitTesting(false)
                    }
                }
                .accessibilityLabel(speaker.isPlaying ? "Pause" : "Play")
                .accessibilityHint(Text("On \(speaker.name)"))
            }
            if speaker.can(.nextPrevious) {
                Spacer()
                Button {
                    skipTaps += 1
                    model.speakers.perform(.next, on: address)
                } label: {
                    SkipArrows(direction: .forward, height: 24, trigger: skipTaps)
                }
                .buttonStyle(PlayerButtonStyle(diameter: 68))
                .accessibilityLabel("Next")
            }
            Spacer()
            modeButton(speaker, isShuffle: false)
        }
    }

    /// Shuffle or repeat, lit while on, as Music's are; a space of the same size where the
    /// speaker has neither, so the transport stays centred.
    @ViewBuilder
    private func modeButton(_ speaker: TracksideSpeaker, isShuffle: Bool) -> some View {
        let capability: TracksideSpeaker.Capability = isShuffle ? .shuffle : .repeatMode
        if speaker.can(capability) {
            let repeatMode = speaker.playback?.repeatMode ?? .off
            let isOn = isShuffle ? speaker.playback?.shuffle == true : repeatMode != .off && repeatMode != .unknown
            Button {
                modeTaps += 1
                if isShuffle {
                    model.speakers.change(TracksideSpeakerChange(shuffle: !isOn), on: address)
                } else {
                    let next: TracksideSpeaker.Repeat = switch repeatMode {
                    case .off, .unknown: .all
                    case .all: .one
                    case .one: .off
                    }
                    model.speakers.change(TracksideSpeakerChange(repeatMode: next), on: address)
                }
            } label: {
                Image(systemName: isShuffle ? "shuffle" : repeatMode == .one ? "repeat.1" : "repeat")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(isOn ? AnyShapeStyle(Color(uiColor: .systemBackground)) : AnyShapeStyle(.primary.opacity(0.7)))
                    .frame(width: 34, height: 34)
                    .background(isOn ? AnyShapeStyle(.primary) : AnyShapeStyle(.clear), in: .circle)
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
                    .contentTransition(.symbolEffect(.replace))
                    .animation(reduceMotion ? nil : Design.Motion.press, value: isOn)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isShuffle ? Text("Shuffle") : Text("Repeat"))
            .accessibilityValue(isShuffle ? Text(isOn ? "On" : "Off") : Text(repeatMode == .one ? "One" : repeatMode == .all ? "All" : "Off"))
        } else {
            Color.clear.frame(width: 44, height: 44)
        }
    }

    private var playHereTitle: LocalizedStringKey {
        NearbyDevices.thisKind == .iPad ? "Play on This iPad" : "Play on This iPhone"
    }

    private func playHere() {
        isMoving = true
        Task {
            let moved = await model.playHere(from: address)
            isMoving = false
            if !moved { model.player.confirm(String(localized: "Couldn't Find This Song Here")) }
        }
    }

    private func navigate(_ destination: NowPlayingDestination) {
        PlayerHandoff.shared.close()
        onNavigate(destination)
    }
}

/// The full player for wherever the music is: a speaker's while the player shows one, this
/// iPhone's otherwise. Sending the music to a speaker with the player open hands one over to
/// the other in place, on a spring that a quick change of mind turns around.
struct MainPlayerView: View {
    let onNavigate: (NowPlayingDestination) -> Void
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let address = model.controlledSpeakerNow == nil ? nil : model.controlledSpeaker
        ZStack {
            if let address {
                SpeakerPlayerView(address: address, onNavigate: onNavigate)
                    .transition(handOver)
            } else {
                NowPlayingView(onNavigate: onNavigate)
                    .transition(handOver)
            }
        }
        .animation(reduceMotion ? Design.Motion.fade : .spring(duration: 0.45, bounce: 0.1), value: address)
        .onChange(of: address == nil && !player.hasQueue) { _, isEmpty in
            if isEmpty { PlayerHandoff.shared.close() }
        }
    }

    private var handOver: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.96))
    }
}

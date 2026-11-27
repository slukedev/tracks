import SwiftUI
import TracksCore

/// Your servers' speakers in Play On, after your devices, as AirPlay lists the rooms: a
/// checkmark on the one with the music, a spinner while the music is on its way there, and a
/// quiet line on one that's off or out of reach. Choosing one sends the music here there; one
/// already playing shows in the player.
struct SpeakerDeviceRows: View {
    /// Opens a speaker's page.
    var openSettings: ((SpeakerAddress) -> Void)?
    /// The music here was sent to a speaker, for the list's tap of feedback.
    var onCast: () -> Void = {}
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player

    var body: some View {
        let speakers = model.speakers
        let showsServer = speakers.serverIDs.count > 1
        ForEach(speakers.serverIDs, id: \.self) { serverID in
            let board = speakers.board(serverID)
            if board?.state == .unavailable {
                DeviceRow(symbol: "hifispeaker.fill", name: String(localized: "Speakers"), detail: String(localized: "Speakers aren't available right now."), isAway: true)
            } else {
                ForEach(speakers.listed(on: serverID)) { speaker in
                    row(speaker, address: SpeakerAddress(serverID: serverID, speakerID: speaker.id), serverName: showsServer ? speakers.serverNames[serverID] : nil, isServerReachable: board?.isServerReachable != false)
                }
            }
        }
    }

    private func row(_ speaker: TracksideSpeaker, address: SpeakerAddress, serverName: String?, isServerReachable: Bool) -> some View {
        let speakers = model.speakers
        let problem = speakers.problem(for: address)
        let status = speaker.statusLine(memberNames: speakers.memberNames(of: speaker, on: address.serverID))
        let detail = problem?.message ?? (isServerReachable ? status : String(localized: "Can't Reach Your Server"))
        let canChoose = isServerReachable && speakers.canControl(address.serverID) && speaker.canChoose(hasMusicHere: player.hasQueue && model.musicLocation == .here) || speaker.hasSong && speaker.isReachable
        return DeviceRow(
            symbol: speaker.symbol,
            name: speaker.name,
            detail: serverName.map { String(localized: "\($0) · \(detail)") } ?? detail,
            isPlaying: speaker.isPlaying && speaker.isReachable,
            trailing: speakers.isConnecting(address) ? .working : model.musicLocation == .speaker(address) ? .current : .none,
            isProblem: problem != nil,
            isEnabled: canChoose,
            hint: model.musicLocation == .here && player.hasQueue ? Text("Plays the music on \(speaker.name)") : Text("Shows its controls")
        ) {
            choose(speaker, address: address)
        }
        .contextMenu {
            if speaker.hasSong, speaker.can(.pause), speakers.canControl(address.serverID) {
                Button(speaker.isPlaying ? "Pause" : "Play", systemImage: speaker.isPlaying ? "pause" : "play") {
                    speakers.perform(speaker.isPlaying ? .pause : .play, on: address)
                }
                if speaker.can(.nextPrevious) {
                    Button("Next", systemImage: "forward") {
                        speakers.perform(.next, on: address)
                    }
                }
            }
            if let openSettings {
                Button("Speaker Settings", systemImage: "gearshape") { openSettings(address) }
            }
        }
    }

    private func choose(_ speaker: TracksideSpeaker, address: SpeakerAddress) {
        if model.musicLocation == .here, player.hasQueue, speaker.can(.play) {
            onCast()
            Task { await model.sendMusic(to: address) }
        } else if speaker.hasSong {
            model.control(speaker: address)
        }
    }
}

/// A speaker's song as Control Center's Now Playing, at the top of Your Devices while the
/// player shows it: where it's playing, the song, a scrubber, its controls and volume, and
/// the way to bring it here.
struct SpeakerCard: View {
    let address: SpeakerAddress
    let speaker: TracksideSpeaker
    var close: (() -> Void)?
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var isMoving = false
    @State private var notFound = false
    @State private var tint: Color?

    var body: some View {
        let track = model.speakerTrack(speaker, on: address.serverID)
        VStack(alignment: .leading, spacing: Metrics.spacing) {
            let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
            let lines = typeSize.isAccessibilitySize ? 3 : 1
            layout {
                CoverImage(cover: track.cover, size: Metrics.cover)
                    .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
                VStack(alignment: .leading, spacing: 1) {
                    Label {
                        Text(speaker.isPlaying ? "Playing on \(speaker.name)" : "Paused on \(speaker.name)")
                    } icon: {
                        Image(systemName: speaker.symbol)
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.7))
                    .lineLimit(lines)
                    .padding(.bottom, 2)
                    Text(track.title)
                        .font(.headline)
                        .lineLimit(lines)
                    Text(track.artistName)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.7))
                        .lineLimit(lines)
                }
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityElement(children: .combine)
            }
            .opacity(model.speakers.isSkipping(address) ? 0.5 : 1)
            SpeakerScrubber(address: address, speaker: speaker)
            SpeakerTransport(address: address, speaker: speaker, size: .large)
                .frame(maxWidth: .infinity)
            SpeakerVolumeSlider(address: address, speaker: speaker)
            HStack(spacing: 8) {
                playHereButton
                #if os(macOS)
                if model.controlledSpeaker != address {
                    Button {
                        model.control(speaker: address)
                        close?()
                    } label: {
                        Image(systemName: "rectangle.bottomhalf.inset.filled")
                            .font(.subheadline.weight(.semibold))
                            .frame(width: 32, height: 30)
                            .background(.white.opacity(0.18), in: .capsule)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(.pressable)
                    .help("Show in the Player")
                    .accessibilityLabel("Show in the Player")
                }
                #endif
            }
        }
        .padding(Metrics.padding)
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .background {
            ZStack {
                tint ?? CoverStage.fallback
                CoverImage(cover: track.cover, size: 300, isBare: true)
                    .scaleEffect(2.2)
                    .blur(radius: 40)
                    .saturation(1.3)
                    .opacity(0.85)
                LinearGradient(colors: [.black.opacity(0.3), .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
            }
            .accessibilityHidden(true)
        }
        .clipShape(.rect(cornerRadius: Metrics.radius, style: .continuous))
        .coverTint(of: track.cover, into: $tint)
        .animation(Design.Motion.reduced(Design.Motion.row, reduceMotion: reduceMotion), value: track.title)
        .animation(Design.Motion.reduced(Design.Motion.fade, reduceMotion: reduceMotion), value: model.speakers.isSkipping(address))
        .sensoryFeedback(.success, trigger: isMoving) { was, now in was && !now && !notFound }
        .sensoryFeedback(.error, trigger: notFound) { _, now in now }
    }

    private var playHereButton: some View {
        Button {
            Task {
                isMoving = true
                let moved = await model.playHere(from: address)
                isMoving = false
                if moved {
                    close?()
                } else {
                    notFound = true
                    try? await Task.sleep(for: .seconds(3))
                    notFound = false
                }
            }
        } label: {
            HStack(spacing: 6) {
                if isMoving {
                    ProgressView().controlSize(.small).tint(.black)
                } else {
                    Image(systemName: notFound ? "exclamationmark.circle" : NearbyDevices.thisDeviceSymbol)
                }
                Text(notFound ? "Not Available Here" : playHereTitle)
                    .contentTransition(.opacity)
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.black)
            .padding(.horizontal, 14)
            .frame(minHeight: Metrics.buttonHeight)
            .frame(maxWidth: .infinity)
            .background(.white, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(.pressable)
        .disabled(isMoving)
        .animation(Design.Motion.value, value: notFound)
    }

    private var playHereTitle: LocalizedStringKey {
        switch NearbyDevices.thisKind {
        case .iPhone: "Play on This iPhone"
        case .iPad: "Play on This iPad"
        default: "Play on This Mac"
        }
    }

    private enum Metrics {
        #if os(macOS)
        static let cover: CGFloat = 48
        static let spacing: CGFloat = 12
        static let padding: CGFloat = 14
        static let radius: CGFloat = 16
        static let buttonHeight: CGFloat = 30
        #else
        static let cover: CGFloat = 60
        static let spacing: CGFloat = 14
        static let padding: CGFloat = 16
        static let radius: CGFloat = 22
        static let buttonHeight: CGFloat = 40
        #endif
    }
}

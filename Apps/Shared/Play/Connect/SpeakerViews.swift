import SwiftUI
import TracksCore

extension TracksideSpeaker {
    /// Its shape, for its row and the player: a HomePod as a HomePod, a group as two speakers.
    var symbol: String {
        switch kind {
        case .group, .stereoPair:
            return "hifispeaker.2.fill"
        case .phone:
            return "iphone"
        case .speaker, .unknown:
            let model = (model ?? "").lowercased()
            if model.contains("homepod mini") { return "homepodmini.fill" }
            if model.contains("homepod") { return "homepod.fill" }
            return "hifispeaker.fill"
        }
    }
}

extension SpeakersStore {
    /// The speakers to list on a server: a group's members fold under their leader, and this
    /// device's own stream through the server is this device, not a speaker.
    func listed(on serverID: String) -> [TracksideSpeaker] {
        (board(serverID)?.speakers ?? []).filter { !$0.isGroupMember && $0.relay?.isThisDevice != true }
    }

    func memberNames(of speaker: TracksideSpeaker, on serverID: String) -> [String] {
        speaker.memberIDs.compactMap { board(serverID)?.speaker($0)?.name }
    }

    func problem(for address: SpeakerAddress) -> SpeakerProblem? {
        problem?.address == address ? problem : nil
    }
}

/// How far a speaker is into its song, to drag to a new place: the small one, for the card
/// and the mini player. It moves at the screen's own rate while it plays and rests otherwise.
struct SpeakerScrubber: View {
    let address: SpeakerAddress
    let speaker: TracksideSpeaker
    var showsTimes = true
    @Environment(AppModel.self) private var model
    @Environment(\.isOnScreen) private var isOnScreen
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dragFraction: Double?

    var body: some View {
        if let duration = speaker.nowPlaying?.duration {
            let canSeek = speaker.can(.seek) && model.speakers.canControl(address.serverID)
            TimelineView(.animation(paused: !speaker.isPlaying || dragFraction != nil || !isOnScreen)) { context in
                let live = min(1, max(0, (model.speakers.position(of: address, at: context.date) ?? 0) / duration))
                let fraction = dragFraction ?? live
                VStack(spacing: 4) {
                    GeometryReader { proxy in
                        ZStack(alignment: .leading) {
                            Capsule().fill(.primary.opacity(0.18))
                            Capsule()
                                .fill(.primary.opacity(dragFraction == nil ? 0.8 : 1))
                                .offset(x: -proxy.size.width * (1 - fraction))
                        }
                        .frame(height: dragFraction == nil ? 4 : 7)
                        .clipShape(.capsule)
                        .frame(maxHeight: .infinity)
                        .contentShape(.rect)
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { value in
                                    dragFraction = min(1, max(0, value.location.x / max(1, proxy.size.width)))
                                }
                                .onEnded { _ in
                                    if let dragFraction { model.speakers.perform(.seek(dragFraction * duration), on: address) }
                                    dragFraction = nil
                                }
                        )
                        .allowsHitTesting(canSeek)
                        .animation(Design.Motion.reduced(Design.Motion.hover, reduceMotion: reduceMotion), value: dragFraction == nil)
                    }
                    .frame(height: 14)
                    if showsTimes {
                        HStack {
                            Text(verbatim: Scrubber.format(fraction * duration))
                            Spacer()
                            Text(verbatim: "-" + Scrubber.format(max(0, duration - fraction * duration)))
                        }
                        .font(.caption2.weight(.medium))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement()
                .accessibilityLabel("Position")
                .accessibilityValue(Text("\(Scrubber.format(fraction * duration)) of \(Scrubber.format(duration))"))
                .accessibilityAdjustableAction { direction in
                    guard canSeek else { return }
                    let step: TimeInterval = direction == .increment ? 15 : -15
                    model.speakers.perform(.seek(min(duration, max(0, fraction * duration + step))), on: address)
                }
            }
        }
    }
}

/// Back, play or pause, and next, for a speaker: each only where the speaker has it.
struct SpeakerTransport: View {
    enum Size { case compact, large }

    let address: SpeakerAddress
    let speaker: TracksideSpeaker
    var size = Size.compact
    /// Off in the mini player, which has Play and Next only, as Music's does.
    var showsPrevious = true
    @Environment(AppModel.self) private var model
    @State private var presses = 0

    var body: some View {
        let canControl = model.speakers.canControl(address.serverID) && speaker.isReachable
        HStack(spacing: size == .large ? 26 : 8) {
            if showsPrevious, speaker.can(.nextPrevious) {
                button("Previous", "backward.fill", .previous, diameter: small)
            }
            if speaker.can(.pause) {
                button(speaker.isPlaying ? "Pause" : "Play", speaker.isPlaying ? "pause.fill" : "play.fill", speaker.isPlaying ? .pause : .play, diameter: big)
            }
            if speaker.can(.nextPrevious) {
                button("Next", "forward.fill", .next, diameter: small)
            }
        }
        .disabled(!canControl)
        .sensoryFeedback(Design.Haptic.secondary, trigger: presses)
    }

    private var small: CGFloat { size == .large ? 36 : 30 }
    private var big: CGFloat { size == .large ? 46 : 36 }

    private var hitSide: CGFloat {
        #if os(iOS)
        44
        #else
        0
        #endif
    }

    private func button(_ title: LocalizedStringKey, _ symbol: String, _ action: TracksideSpeakerAction, diameter: CGFloat) -> some View {
        Button {
            presses += 1
            model.speakers.perform(action, on: address)
        } label: {
            Image(systemName: symbol)
                .font(.system(size: diameter * 0.42, weight: .semibold))
                .contentTransition(.symbolEffect(.replace))
                .frame(width: diameter, height: diameter)
                .frame(minWidth: hitSide, minHeight: hitSide)
                .contentShape(.rect)
        }
        .buttonStyle(.pressable)
        .accessibilityLabel(Text(title))
        .help(Text(title))
    }
}

/// A speaker's volume, which follows the finger at once. The server hears the latest value,
/// a few times a second, and a change made elsewhere moves it unless it's being dragged.
struct SpeakerVolumeSlider: View {
    let address: SpeakerAddress
    let speaker: TracksideSpeaker
    /// A group's leader can set the whole group's volume as well as its own.
    var isGroup = false
    var showsMute = true
    @Environment(AppModel.self) private var model
    @State private var value: Double = 0
    @State private var isDragging = false

    private var reported: Int? { isGroup ? speaker.groupVolume : speaker.volume }

    var body: some View {
        if speaker.can(.volume), reported != nil {
            HStack(spacing: 10) {
                if showsMute, speaker.can(.mute) {
                    Button {
                        model.speakers.change(TracksideSpeakerChange(muted: speaker.muted != true), on: address)
                    } label: {
                        Image(systemName: speaker.muted == true ? "speaker.slash.fill" : "speaker.fill")
                            .font(.caption)
                            .foregroundStyle(.primary.opacity(0.6))
                            .contentTransition(.symbolEffect(.replace))
                            .frame(minWidth: 28, minHeight: 28)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(speaker.muted == true ? Text("Unmute \(speaker.name)") : Text("Mute \(speaker.name)"))
                } else {
                    Image(systemName: "speaker.fill")
                        .font(.caption)
                        .foregroundStyle(.primary.opacity(0.6))
                        .accessibilityHidden(true)
                }
                Slider(value: $value, in: 0...100) { editing in
                    isDragging = editing
                }
                .disabled(!model.speakers.canControl(address.serverID) || !speaker.isReachable)
                .accessibilityLabel(isGroup ? Text("\(speaker.name) Group Volume") : Text("\(speaker.name) Volume"))
                .accessibilityValue(Text(Double(value / 100).formatted(.percent.precision(.fractionLength(0)))))
                .onChange(of: value) { _, new in
                    let level = Int(new.rounded())
                    guard level != reported else { return }
                    let change = isGroup ? TracksideSpeakerChange(groupVolume: level) : TracksideSpeakerChange(volume: level)
                    model.speakers.change(change, on: address)
                }
                Image(systemName: "speaker.wave.3.fill")
                    .font(.caption)
                    .foregroundStyle(.primary.opacity(0.6))
                    .accessibilityHidden(true)
            }
            .opacity(speaker.muted == true ? 0.6 : 1)
            .onAppear { value = Double(reported ?? 0) }
            .onChange(of: reported) { _, new in
                if !isDragging, let new, Int(value.rounded()) != new { value = Double(new) }
            }
        }
    }
}

/// The speakers that can play along with this one, each with a checkmark while it does.
/// Each choice applies at once.
struct SpeakerMembersMenu<Label: View>: View {
    let address: SpeakerAddress
    let speaker: TracksideSpeaker
    @ViewBuilder var label: () -> Label
    @Environment(AppModel.self) private var model

    var body: some View {
        let others = speaker.canGroupWith.compactMap { model.speakers.board(address.serverID)?.speaker($0) }
        Menu {
            ForEach(others) { other in
                Toggle(isOn: Binding(get: { speaker.memberIDs.contains(other.id) }, set: { on in toggle(other.id, on: on) })) {
                    SwiftUI.Label(other.name, systemImage: other.symbol)
                }
                .disabled(!other.isReachable)
            }
        } label: {
            label()
        }
        .disabled(others.isEmpty || !model.speakers.canControl(address.serverID) || speaker.group?.isStatic == true)
    }

    private func toggle(_ id: String, on: Bool) {
        var members = speaker.memberIDs
        if on { members.append(id) } else { members.removeAll { $0 == id } }
        model.speakers.setMembers(members, of: address)
    }
}

/// A speaker's own controls under the player while the music is on it: its volume, its
/// power, the speakers playing along, and the way to its page.
struct SpeakerDeviceControls: View {
    let address: SpeakerAddress
    let speaker: TracksideSpeaker
    let openSettings: () -> Void
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 14) {
            SpeakerVolumeSlider(address: address, speaker: speaker)
            if speaker.isGroupLeader, speaker.groupVolume != nil {
                SpeakerVolumeSlider(address: address, speaker: speaker, isGroup: true, showsMute: false)
            }
            HStack(spacing: 10) {
                Label(memberLine, systemImage: speaker.symbol)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.primary.opacity(0.7))
                    .lineLimit(1)
                Spacer(minLength: 8)
                if speaker.can(.group), !speaker.canGroupWith.isEmpty {
                    SpeakerMembersMenu(address: address, speaker: speaker) {
                        Image(systemName: "hifispeaker.2")
                            .font(.body.weight(.medium))
                            .frame(width: 36, height: 36)
                            .contentShape(.circle)
                    }
                    .menuIndicator(.hidden)
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Speakers Playing Along"))
                    .help("Speakers Playing Along")
                }
                if speaker.can(.power) {
                    Button {
                        model.speakers.change(TracksideSpeakerChange(powered: speaker.powered == false), on: address)
                    } label: {
                        Image(systemName: "power")
                            .font(.body.weight(.medium))
                            .foregroundStyle(speaker.powered == false ? AnyShapeStyle(.primary.opacity(0.5)) : AnyShapeStyle(.primary))
                            .frame(width: 36, height: 36)
                            .contentShape(.circle)
                    }
                    .buttonStyle(.plain)
                    .disabled(!model.speakers.canControl(address.serverID))
                    .accessibilityLabel(speaker.powered == false ? Text("Turn On \(speaker.name)") : Text("Turn Off \(speaker.name)"))
                    .help(speaker.powered == false ? "Turn On" : "Turn Off")
                }
                Button(action: openSettings) {
                    Image(systemName: "gearshape")
                        .font(.body.weight(.medium))
                        .frame(width: 36, height: 36)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("\(speaker.name) Settings"))
                .help("Speaker Settings")
            }
        }
    }

    private var memberLine: String {
        let names = model.speakers.memberNames(of: speaker, on: address.serverID)
        guard !names.isEmpty else { return speaker.name }
        return ([speaker.name] + names).formatted(.list(type: .and))
    }
}

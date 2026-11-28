import SwiftUI
import TracksCore

/// One of your servers' speakers, as Home's page for a HomePod: how it stands, its volume and
/// power, and the speakers playing along with it, each a checkmark that applies at once.
/// Pushed from Devices on iPhone; a sheet on the Mac.
struct SpeakerSettingsPage: View {
    let address: SpeakerAddress
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    @State private var choices = 0

    var body: some View {
        let speakers = model.speakers
        Group {
            if let speaker = speakers.speaker(address) {
                form(speaker)
            } else {
                ContentUnavailableView("Speaker Not Available", systemImage: "hifispeaker.fill")
            }
        }
        #if os(macOS)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        #endif
    }

    private func form(_ speaker: TracksideSpeaker) -> some View {
        let speakers = model.speakers
        let canControl = speakers.canControl(address.serverID) && speaker.isReachable
        let problem = speakers.problem(for: address)
        return Form {
            SettingsHero(
                "\(speaker.name)",
                subtitle: (problem == nil ? SettingsTone.plain : .attention).apply(to: Text(problem?.message ?? speaker.statusLine(memberNames: speakers.memberNames(of: speaker, on: address.serverID)))),
                systemImage: speaker.symbol,
                tint: .blue
            ) {
                #if os(macOS)
                if speaker.can(.power) {
                    Toggle("Power", isOn: power(speaker))
                        .toggleStyle(.switch)
                        .labelsHidden()
                        .disabled(!speakers.canControl(address.serverID))
                }
                #endif
            }

            #if os(iOS)
            if speaker.can(.power) {
                Section {
                    Toggle("Power", isOn: power(speaker))
                        .disabled(!speakers.canControl(address.serverID))
                }
            }
            #endif

            if speaker.can(.volume) || speaker.can(.mute) {
                Section {
                    SpeakerVolumeSlider(address: address, speaker: speaker, showsMute: false)
                    if speaker.isGroupLeader, speaker.groupVolume != nil {
                        LabeledContent("Everyone") {
                            SpeakerVolumeSlider(address: address, speaker: speaker, isGroup: true, showsMute: false)
                        }
                    }
                    if speaker.can(.mute) {
                        Toggle("Mute", isOn: Binding(
                            get: { speaker.muted == true },
                            set: { speakers.change(TracksideSpeakerChange(muted: $0), on: address) }
                        ))
                    }
                } header: {
                    Text("Volume")
                }
                .disabled(!canControl)
            }

            if speaker.isGroupMember, let leaderID = speaker.group?.leaderID?.lowercased(),
               let leader = speakers.board(address.serverID)?.speaker(leaderID) {
                Section {
                    LabeledContent("Playing With", value: leader.name)
                    Button("Play on Its Own") {
                        choices += 1
                        speakers.leaveGroup(address)
                    }
                    .disabled(!canControl || speaker.group?.isStatic == true)
                } header: {
                    Text("Play Along")
                }
            } else if speaker.can(.group), !speaker.canGroupWith.isEmpty {
                membersSection(speaker, canControl: canControl)
            }

            if speaker.manufacturer != nil || speaker.model != nil || speakers.serverIDs.count > 1 {
                Section {
                    if let name = [speaker.manufacturer, speaker.model].compactMap(\.self).joined(separator: " ").nonEmpty {
                        LabeledContent("Model", value: name)
                    }
                    if speakers.serverIDs.count > 1, let server = speakers.serverNames[address.serverID] {
                        LabeledContent("Server", value: server)
                    }
                }
            }
        }
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: speaker.memberIDs)
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: speaker.availability)
        .sensoryFeedback(Design.Haptic.choice, trigger: choices)
        #if os(macOS)
        .settingsPane()
        #else
        .settingsPage("\(speaker.name)")
        #endif
    }

    /// The speakers that can play along, a checkmark on each that does.
    private func membersSection(_ speaker: TracksideSpeaker, canControl: Bool) -> some View {
        let speakers = model.speakers
        let others = speaker.canGroupWith.compactMap { speakers.board(address.serverID)?.speaker($0) }
        return Section {
            ForEach(others) { other in
                let isOn = speaker.memberIDs.contains(other.id)
                Button {
                    choices += 1
                    var members = speaker.memberIDs
                    if isOn { members.removeAll { $0 == other.id } } else { members.append(other.id) }
                    speakers.setMembers(members, of: address)
                } label: {
                    HStack {
                        Label(other.name, systemImage: other.symbol)
                            .foregroundStyle(other.isReachable ? .primary : .secondary)
                        Spacer(minLength: 8)
                        if isOn {
                            Image(systemName: "checkmark")
                                .font(.body.weight(.semibold))
                                .foregroundStyle(.tint)
                                .transition(.opacity)
                        }
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .disabled(!canControl || !other.isReachable || speaker.group?.isStatic == true)
                .accessibilityAddTraits(isOn ? .isSelected : [])
            }
        } header: {
            Text("Play Along")
        }
    }

    private func power(_ speaker: TracksideSpeaker) -> Binding<Bool> {
        Binding(
            get: { speaker.powered != false },
            set: { model.speakers.change(TracksideSpeakerChange(powered: $0), on: address) }
        )
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}

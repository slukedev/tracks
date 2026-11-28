import SwiftUI
import TracksCore

/// Your Devices in Settings: whether Tracks finds your other devices, which are here and which
/// were lately, and Handoff. An iPhone page, from Settings and from the Devices sheet, and the
/// Mac's Devices pane.
struct DevicesSettingsPage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    @AppStorage(NearbyDevices.storageKey) private var isOn = true
    @AppStorage(NowPlayingHandoff.storageKey) private var offersHandoff = true
    @AppStorage(NearbyDevices.bringsQueueKey) private var bringsQueue = true
    /// The speaker whose page is open, on the Mac, where it's a sheet.
    @State private var openSpeaker: SpeakerAddress?
    @State private var connectsTV = false

    private var status: NearbyDevicesStatus {
        let playingOn: String? = {
            guard case .device(let id) = model.musicLocation else { return nil }
            return model.nearby.devices.first { $0.id == id }?.name
        }()
        return NearbyDevicesStatus(
            isOn: isOn,
            needsLocalNetwork: model.nearby.needsLocalNetwork,
            connected: model.nearby.devices.count,
            playingOn: playingOn
        )
    }

    var body: some View {
        Form {
            SettingsHero(
                "Your Devices",
                subtitle: (status.needsAttention ? SettingsTone.attention : .plain).apply(to: Text(status.line)),
                systemImage: "laptopcomputer.and.iphone",
                tint: .blue
            ) {
                #if os(macOS)
                Toggle("Find Your Devices", isOn: $isOn)
                    .toggleStyle(.switch)
                    .labelsHidden()
                #endif
            }

            #if os(iOS)
            Section {
                Toggle("Find Your Devices", isOn: $isOn)
            }
            #endif

            if isOn {
                if model.nearby.needsLocalNetwork {
                    localNetworkSection
                }
                nearbySection
                if !model.nearby.away.isEmpty {
                    awaySection
                }
                bringsQueueSection
                handoffSection
            }
            if model.speakers.hasSpeakers {
                speakersSection
            }
            if !model.yourMusic.servers.tracksideServers.isEmpty {
                tvSection
            }
        }
        .sheet(isPresented: $connectsTV) {
            ConnectTVSheet()
                .environment(model.yourMusic)
        }
        #if os(macOS)
        .sheet(item: $openSpeaker) { address in
            SpeakerSettingsPage(address: address)
        }
        .onChange(of: model.pendingSpeakerSettings, initial: true) { _, address in
            guard let address else { return }
            openSpeaker = address
            model.pendingSpeakerSettings = nil
        }
        #endif
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: isOn)
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: model.nearby.devices.map(\.id))
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: model.nearby.away.map(\.id))
        .onChange(of: isOn) { _, on in
            // Sample data never reaches the real network, as at launch.
            guard !model.isDemoLaunch else { return }
            if on { model.nearby.start() } else { model.nearby.stop() }
        }
        .onChange(of: offersHandoff) { model.updateHandoff() }
        #if os(macOS)
        .settingsPane()
        #else
        .settingsPage("Your Devices")
        #endif
    }

    // MARK: - Sections

    private var localNetworkSection: some View {
        Section {
            #if os(macOS)
            SettingsDetailRow(title: Text("Local Network"), detail: Text("Off for Tracks, so it can't find your devices.")) {
                Button("Open Privacy Settings…") { open(DevicesSettingsLinks.localNetwork) }
            }
            #else
            Button("Turn On Local Network") { open(DevicesSettingsLinks.localNetwork) }
            #endif
        } footer: {
            #if os(iOS)
            Text(PlaySettingsWords.localNetworkOff)
            #endif
        }
    }

    private var nearbySection: some View {
        Section {
            DeviceRow(
                symbol: NearbyDevices.thisDeviceSymbol,
                name: NearbyDevices.thisDeviceName,
                detail: model.player.current.map { String(localized: "\($0.title) · \($0.artistName)") } ?? String(localized: "Not Playing"),
                isPlaying: model.player.isPlaying
            )
            ForEach(model.nearby.devices) { device in
                DeviceRow(
                    symbol: device.kind.symbol,
                    name: device.name,
                    detail: nearbyDetail(device.state),
                    isPlaying: device.state?.isPlaying == true
                )
            }
        } header: {
            Text("Nearby")
        } footer: {
            if model.nearby.devices.isEmpty {
                Text("Open Tracks on another device on the same Wi‑Fi.")
            }
        }
    }

    private var awaySection: some View {
        Section {
            ForEach(model.nearby.away) { device in
                HStack {
                    DeviceRow(
                        symbol: device.deviceKind.symbol,
                        name: device.name,
                        detail: awayDetail(device),
                        isAway: true
                    )
                    #if os(macOS)
                    Menu {
                        forgetButton(device)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .menuStyle(.button)
                    .buttonStyle(.borderless)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help("More")
                    .accessibilityLabel(Text("More for \(device.name)"))
                    #endif
                }
                .contextMenu { forgetButton(device) }
                #if os(iOS)
                .swipeActions {
                    Button("Forget", role: .destructive) { model.nearby.forget(device.id) }
                }
                #endif
            }
        } header: {
            Text("Recently Seen")
        }
    }

    /// Your servers' speakers, each opening its own page. Not behind Find Your Devices,
    /// which is about your own devices nearby.
    private var speakersSection: some View {
        let speakers = model.speakers
        return Section {
            ForEach(speakers.serverIDs, id: \.self) { serverID in
                if speakers.board(serverID)?.state == .unavailable {
                    DeviceRow(symbol: "hifispeaker.fill", name: String(localized: "Speakers"), detail: String(localized: "Speakers aren't available right now."), isAway: true)
                }
                ForEach(speakers.board(serverID)?.speakers.filter { $0.relay?.isThisDevice != true } ?? []) { speaker in
                    let address = SpeakerAddress(serverID: serverID, speakerID: speaker.id)
                    let row = DeviceRow(
                        symbol: speaker.symbol,
                        name: speaker.name,
                        detail: speaker.statusLine(memberNames: speakers.memberNames(of: speaker, on: serverID)),
                        isPlaying: speaker.isPlaying && speaker.isReachable,
                        isAway: !speaker.isReachable
                    )
                    #if os(macOS)
                    HStack {
                        row
                        Button("Settings…") { openSpeaker = address }
                            .controlSize(.small)
                    }
                    #else
                    NavigationLink {
                        SpeakerSettingsPage(address: address)
                    } label: {
                        row
                    }
                    #endif
                }
            }
        } header: {
            Text("Speakers")
        }
    }

    /// A TV signs in to your server with the code it shows, approved here.
    private var tvSection: some View {
        Section {
            #if os(macOS)
            LabeledContent {
                Button("Connect…") { connectsTV = true }
            } label: {
                Label("TV", systemImage: "tv")
            }
            #else
            Button("Connect a TV…", systemImage: "tv") { connectsTV = true }
            #endif
        }
    }

    private var bringsQueueSection: some View {
        Section {
            SettingsSwitch("Bring Up Next", isOn: $bringsQueue)
        }
    }

    private var handoffSection: some View {
        Section {
            SettingsSwitch("Handoff", isOn: $offersHandoff)
        }
    }

    private func forgetButton(_ device: KnownNearbyDevice) -> some View {
        Button("Forget This Device", systemImage: "minus.circle", role: .destructive) {
            model.nearby.forget(device.id)
        }
    }

    // MARK: - Words

    private func nearbyDetail(_ state: NearbyState?) -> String {
        guard let state, state.hasSong else { return String(localized: "Not Playing") }
        let song = [state.title, state.artist].compactMap(\.self).joined(separator: " · ")
        return state.isPlaying ? song : String(localized: "Paused · \(song)")
    }

    /// When it was here, and what it last played.
    private func awayDetail(_ device: KnownNearbyDevice) -> String {
        let seen = KnownNearbyDevices.seen(device.lastSeen)
        guard let song = device.song else { return seen }
        return String(localized: "\(seen) · \(song)")
    }

    private func open(_ url: URL?) {
        if let url { openURL(url) }
    }
}

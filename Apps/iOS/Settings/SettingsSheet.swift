import SwiftUI
import TracksCore

/// Settings on iPhone: one list of pages, each row saying where its page stands.
struct SettingsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var path: [SettingsPage] = LaunchScene.settingsPageName.flatMap(SettingsPage.init(rawValue:)).map(\.path) ?? []
    /// Kept here, so an approval pending on Last.fm survives going back to the list.
    @State private var lastFM = LastFMConnection()
    @State private var cloud: CloudSyncSettings?

    var body: some View {
        NavigationStack(path: $path) {
            Form {
                if let keptAside = LibraryKeptAside(model: model) {
                    LibraryKeptAsideRow(keptAside: keptAside)
                }

                Section {
                    SourcesRow()
                }

                Section {
                    NavigationLink(value: SettingsPage.play) {
                        SettingsRowLabel(title: "Play", systemImage: "play.fill", tint: .indigo, value: nil)
                    }
                    HistoryRow()
                    RadioRow()
                } header: {
                    Text("Listening")
                }

                Section {
                    LastFMRow(connection: lastFM)
                    DevicesRow()
                    if let cloud {
                        NavigationLink(value: SettingsPage.iCloud) {
                            SettingsRowLabel(title: "iCloud", systemImage: "icloud.fill", tint: .blue, value: cloud.status.short)
                        }
                    } else if model.isDemoLaunch {
                        NavigationLink(value: SettingsPage.iCloud) {
                            SettingsRowLabel(title: "iCloud", systemImage: "icloud.fill", tint: .blue, value: CloudSyncStatus.unavailable.short)
                        }
                    }
                } header: {
                    Text("Connections")
                }

                AboutSection()
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: SettingsPage.self) { page in
                destination(page)
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", role: .confirm) { dismiss() }
                }
            }
        }
        .task {
            // Sample data has no real store to sync; its row says so instead.
            guard cloud == nil, let store = model.store, !model.isDemoLaunch else { return }
            let settings = CloudSyncSettings(store: store, monitor: model.syncMonitor)
            cloud = settings
            await settings.refresh()
        }
        .onAppear(perform: model.refreshMusicAuthorization)
    }

    @ViewBuilder
    private func destination(_ page: SettingsPage) -> some View {
        switch page {
        case .sources: SourcesPage()
        case .play: PlaySettingsPage()
        case .history: HistorySettingsPage()
        case .radio: RadioSettingsPage()
        case .appleMusic: AppleMusicSettingsPage()
        case .lastFM: LastFMSettingsPage(connection: lastFM)
        case .devices: DevicesSettingsPage()
        case .louderAtSpeed: SpeedVolumePage()
        case .iCloud:
            if let cloud {
                CloudSyncPage(sync: cloud)
            } else {
                CloudSyncSampleDataPage()
            }
        }
    }
}

/// A page of iPhone Settings. Also what `-TracksSettings <page>` opens on.
enum SettingsPage: String, Hashable, CaseIterable {
    case sources, play, history, radio, lastFM, iCloud, devices
    /// Inside Sources.
    case appleMusic
    /// Inside Play.
    case louderAtSpeed

    /// The pages to push to reach this one.
    var path: [SettingsPage] {
        switch self {
        case .louderAtSpeed: [.play, self]
        case .appleMusic: [.sources, self]
        default: [self]
        }
    }
}

/// Your Devices' row: off, a problem, or how many are nearby.
private struct DevicesRow: View {
    @Environment(AppModel.self) private var model
    @AppStorage(NearbyDevices.storageKey) private var isOn = true

    var body: some View {
        let status = NearbyDevicesStatus(
            isOn: isOn,
            needsLocalNetwork: model.nearby.needsLocalNetwork,
            connected: model.nearby.devices.count,
            playingOn: nil
        )
        NavigationLink(value: SettingsPage.devices) {
            SettingsRowLabel(title: "Your Devices", systemImage: "laptopcomputer.and.iphone", tint: .blue, value: status.short)
        }
    }
}

/// History's row, which says whether everything or only radio is kept, or that it's paused.
private struct HistoryRow: View {
    @AppStorage(CaptureSettings.capturesOnDemandKey, store: CaptureSettings.sharedDefaults)
    private var keepsOnDemand = true
    @Environment(CaptureService.self) private var capture: CaptureService?

    var body: some View {
        NavigationLink(value: SettingsPage.history) {
            SettingsRowLabel(
                title: "History",
                systemImage: "clock.fill",
                tint: .teal,
                value: HistorySettingsWords.short(keepsOnDemand: keepsOnDemand, isPaused: capture?.isPaused == true)
            )
        }
    }
}

/// Radio's row, which names the playlist songs go into.
private struct RadioRow: View {
    @AppStorage(CaptureSettings.autoAddKey, store: CaptureSettings.sharedDefaults)
    private var addsSongs = true
    @AppStorage(CaptureSettings.playlistNameKey, store: CaptureSettings.sharedDefaults)
    private var storedName = ""

    var body: some View {
        let playlist = RadioSettingsWords.Playlist(addsSongs: addsSongs, name: storedName)
        NavigationLink(value: SettingsPage.radio) {
            SettingsRowLabel(
                title: "Radio",
                systemImage: "dot.radiowaves.left.and.right",
                tint: .orange,
                value: RadioSettingsWords.short(playlist)
            )
        }
    }
}

/// Last.fm's row: who is scrobbling, or that it isn't set up.
private struct LastFMRow: View {
    var connection: LastFMConnection
    @AppStorage(CaptureSettings.scrobblesToLastFMKey, store: CaptureSettings.sharedDefaults)
    private var scrobbles = true

    var body: some View {
        NavigationLink(value: SettingsPage.lastFM) {
            SettingsRowLabel(
                title: "Last.fm",
                systemImage: "waveform",
                tint: .red,
                value: LastFMSettingsStatus(connection.state, scrobbles: scrobbles).short
            )
        }
    }
}

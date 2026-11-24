import SwiftUI
import CloudKit
import Observation
import OSLog
import TracksCore

/// What Settings knows about sync: the switch, where it goes (iCloud or Trackside Cloud on
/// one of your Trackside servers), the account, and what each reported. One per Settings
/// screen, so the root row and the page never disagree.
@Observable
final class CloudSyncSettings {
    private let store: TracksStore
    private let monitor: CloudSyncMonitor?

    /// The switch. Read once when the store opens, so a change applies next launch.
    var wantsSync = CloudSync.isEnabled {
        didSet { CloudSync.isEnabled = wantsSync }
    }

    /// Where sync goes. Read once when the store opens, so a change applies next launch.
    var backend = CloudSync.backend {
        didSet { CloudSync.backend = backend }
    }

    /// The Trackside Cloud sync running now, for its status. Set by whoever holds the servers.
    @ObservationIgnored var tracksideCloud: () -> TracksideCloudSync? = { nil }

    /// One of your Trackside servers, which sync can go to.
    struct ServerChoice: Identifiable, Hashable {
        let id: String
        let name: String
    }

    /// Your Trackside servers, any of which can carry sync, by entry id and name.
    var tracksideServers: [ServerChoice] {
        guard let data = UserDefaults.standard.data(forKey: "musicServers"),
              let servers = try? JSONDecoder().decode([SubsonicServer].self, from: data)
        else { return [] }
        return servers.filter(\.isTrackside).map { ServerChoice(id: $0.id, name: $0.name) }
    }

    /// The server sync goes to, when it's Trackside Cloud and that server is still here.
    var tracksideServer: ServerChoice? {
        guard case .tracksideCloud(let id) = backend else { return nil }
        return tracksideServers.first { $0.id == id }
    }

    /// Whether what's chosen differs from what this run syncs with, which changes when Tracks
    /// next opens.
    var changesNextLaunch: Bool {
        (wantsSync ? backend : nil) != CloudSync.backendThisRun
    }

    /// The root row's value, whichever backend.
    var summary: String {
        guard wantsSync, case .tracksideCloud = backend else { return status.short }
        if changesNextLaunch { return String(localized: "On") }
        return tracksideCloud()?.state.short ?? TracksideCloudSync.State.waiting.short
    }

    private var account: CloudSyncStatus.Account?
    private var accountError: String?

    init(store: TracksStore, monitor: CloudSyncMonitor?) {
        self.store = store
        self.monitor = monitor
    }

    var status: CloudSyncStatus {
        CloudSyncStatus.resolve(
            isConfigured: CloudSync.isConfigured,
            wantsSync: wantsSync && backend == .iCloud,
            isRunning: store.backing.isSynced,
            startFailure: store.syncFailureReason,
            account: account,
            accountError: accountError,
            failure: monitor?.failure,
            lastSynced: monitor?.lastSynced
        )
    }

    /// Asks CloudKit about the account. Never prompts; it only reads.
    func refresh() async {
        guard let identifier = CloudSync.containerIdentifier else { return }
        do {
            let status = try await CKContainer(identifier: identifier).accountStatus()
            accountError = nil
            account = switch status {
            case .available: .available
            case .noAccount: .noAccount
            case .restricted: .restricted
            case .temporarilyUnavailable: .temporarilyUnavailable
            case .couldNotDetermine: .unknown
            @unknown default: .unknown
            }
        } catch {
            Self.log.error("Couldn't read the iCloud account: \(URLRedaction.describe(error), privacy: .public)")
            accountError = Self.message(for: error)
        }
    }

    private static let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Tracks", category: "iCloud")

    /// Where sync goes, picked from a menu: iCloud, or one of your Trackside servers.
    var choice: String {
        get {
            switch backend {
            case .iCloud: "icloud"
            case .tracksideCloud(let id): id
            }
        }
        set { backend = newValue == "icloud" ? .iCloud : .tracksideCloud(serverID: newValue) }
    }

    /// What went wrong with iCloud, and what happens next, in place of CloudKit's own words.
    nonisolated static func message(for error: any Error) -> String {
        switch (error as? CKError)?.code {
        case .networkUnavailable, .networkFailure:
            String(localized: "iCloud isn’t reachable right now. Tracks will try again once you’re online.")
        case .quotaExceeded:
            String(localized: "Your iCloud storage is full. Free up some space to keep syncing.")
        case .notAuthenticated, .accountTemporarilyUnavailable:
            String(localized: "Check that you’re signed in to iCloud. Tracks will try again.")
        case .serviceUnavailable, .requestRateLimited:
            String(localized: "iCloud is busy right now. Tracks will try again shortly.")
        default:
            String(localized: "Couldn’t sync with iCloud. Tracks will try again.")
        }
    }
}

/// Sync: the switch, where it goes, where it stands, and what it carries. An iPhone page,
/// and the Mac's iCloud pane.
struct CloudSyncPage: View {
    @Bindable var sync: CloudSyncSettings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        // Relative times ("synced 5 minutes ago") keep up while the page is open.
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let status = sync.status
            let server = sync.wantsSync ? sync.tracksideServer : nil
            Form {
                hero(status, server: server, now: context.date)

                #if os(iOS)
                if status != .unavailable || !sync.tracksideServers.isEmpty {
                    Section {
                        Toggle("Sync", isOn: $sync.wantsSync)
                        if sync.wantsSync { backendPicker }
                    } footer: {
                        footer(server: server)
                            .contentTransition(.opacity)
                    }
                }
                #else
                if sync.wantsSync, !sync.tracksideServers.isEmpty {
                    Section {
                        backendPicker
                    } footer: {
                        footer(server: server)
                    }
                }
                #endif

                if server == nil {
                    if let reason = status.reason {
                        Section {
                            Text(reason)
                                .font(.callout)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                        } header: {
                            Text("What iCloud Said")
                        }
                    }

                    if let fix = fix(for: status) {
                        Section {
                        } footer: {
                            Text(fix)
                        }
                    }
                }

                if Onboarding.shared.restore.isUnderWay {
                    Section {
                        HistoryRestoreRow(restore: Onboarding.shared.restore)
                    }
                }

                CloudSyncContentsSection(server: server?.name)
            }
            .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: status)
            .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: sync.wantsSync)
            .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: sync.choice)
        }
        .task { await sync.refresh() }
        #if os(macOS)
        .settingsPane()
        #else
        .settingsPage("Sync")
        .refreshable {
            await sync.refresh()
            await sync.tracksideCloud()?.sync()
        }
        #endif
    }

    @ViewBuilder
    private func hero(_ status: CloudSyncStatus, server: CloudSyncSettings.ServerChoice?, now: Date) -> some View {
        if let server {
            let state = sync.tracksideCloud()?.state ?? .waiting
            let line = sync.changesNextLaunch ? String(localized: "Starts the next time Tracks opens.") : state.line(now: now)
            SettingsHero(
                "\(server.name)",
                subtitle: (sync.changesNextLaunch ? SettingsTone.attention : state.tone).apply(to: Text(line)),
                systemImage: "server.rack",
                tint: .indigo
            ) {
                heroControl(status)
            }
        } else {
            SettingsHero(
                "iCloud",
                subtitle: status.tone.apply(to: Text(status.line(now: now))),
                systemImage: "icloud.fill",
                tint: .blue
            ) {
                heroControl(status)
            }
        }
    }

    /// Where sync goes: iCloud, or one of your Trackside servers. Shown only when there's a
    /// choice to make.
    @ViewBuilder
    private var backendPicker: some View {
        let servers = sync.tracksideServers
        if !servers.isEmpty {
            Picker("Sync With", selection: $sync.choice) {
                if CloudSync.isConfigured {
                    Text("iCloud").tag("icloud")
                }
                ForEach(servers) { server in
                    Text(server.name).tag(server.id)
                }
            }
        }
    }

    private func footer(server: CloudSyncSettings.ServerChoice?) -> Text {
        if !sync.wantsSync {
            #if os(iOS)
            return Text("Your history stays on this iPhone only.")
            #else
            return Text("Your history stays on this Mac only.")
            #endif
        }
        if sync.changesNextLaunch {
            return Text("Takes effect the next time Tracks opens. Nothing already synced is lost.")
        }
        if server != nil {
            return Text("Works with Tracks on any device signed in to this server.")
        }
        return Text("Changes sync on their own, so there’s nothing to press.")
    }

    /// The pane's switch on the Mac. On iPhone it's a row of its own, as in Settings.
    @ViewBuilder
    private func heroControl(_ status: CloudSyncStatus) -> some View {
        #if os(macOS)
        if status != .unavailable || !sync.tracksideServers.isEmpty {
            Toggle("Sync", isOn: $sync.wantsSync)
                .toggleStyle(.switch)
                .labelsHidden()
        }
        #endif
    }

    /// Where to go when the fix is in the system's settings, not here.
    private func fix(for status: CloudSyncStatus) -> LocalizedStringKey? {
        switch status {
        case .notSignedIn:
            #if os(iOS)
            "Sign in at the top of the Settings app, then come back to Tracks."
            #else
            "Sign in under System Settings ▸ Apple Account, then come back to Tracks."
            #endif
        case .restricted:
            "Screen Time or a device profile doesn’t allow iCloud for Tracks."
        default:
            nil
        }
    }
}

/// What sync carries, said the same way wherever sync is described.
private struct CloudSyncContentsSection: View {
    /// The Trackside server it goes to, or nil for iCloud.
    var server: String? = nil

    var body: some View {
        Section {
            Text("Your listening history, stations and sessions, your settings, and the songs you’ve removed.")
        } header: {
            Text("What Syncs")
        } footer: {
            if let server {
                Text("It all stays on \(server).")
            } else {
                Text("It all stays in your private iCloud.")
            }
        }
    }
}

/// iCloud while Tracks shows sample data, which never touches a real store: unavailable,
/// said plainly, rather than a page with nothing on it.
struct CloudSyncSampleDataPage: View {
    var body: some View {
        Form {
            SettingsHero(
                "iCloud",
                subtitle: Text("Not available while Tracks shows sample data."),
                systemImage: "icloud.fill",
                tint: .blue
            )
            CloudSyncContentsSection()
        }
        #if os(macOS)
        .settingsPane()
        #else
        .settingsPage("iCloud")
        #endif
    }
}

#if DEBUG
#Preview("iCloud, Sample Data") {
    NavigationStack { CloudSyncSampleDataPage() }
}
#endif

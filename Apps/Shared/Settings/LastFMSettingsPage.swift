import SwiftUI
import AuthenticationServices
import TracksCore

/// Connecting a Last.fm account, and what gets sent. An iPhone page, and the Mac's Last.fm
/// pane.
struct LastFMSettingsPage: View {
    /// Owned by whoever shows the page, so an approval still pending on Last.fm survives
    /// going back to the list.
    var connection: LastFMConnection

    @Environment(AppModel.self) private var model
    @Environment(\.webAuthenticationSession) private var webAuthentication
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(CaptureSettings.scrobblesToLastFMKey, store: CaptureSettings.sharedDefaults)
    private var scrobbles = true

    private var status: LastFMSettingsStatus {
        LastFMSettingsStatus(connection.state, scrobbles: scrobbles)
    }

    var body: some View {
        Form {
            hero
            #if os(iOS)
            if !status.isConnected {
                connectSection
            }
            #endif
            if let username = connectedUsername {
                scrobblingSection
                if let history = model.lastFMHistory {
                    LastFMHistorySection(state: history.state, action: history.startOrStop)
                }
                accountSection(username)
                disconnectSection
            }
        }
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: status.isConnected)
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: scrobbles)
        // Another account's progress isn't this one's.
        .onChange(of: connection.state) { model.lastFMHistory?.readProgress() }
        #if os(macOS)
        .settingsPane()
        #else
        .settingsPage("Last.fm")
        #endif
    }

    private var connectedUsername: String? {
        if case .connected(let username) = connection.state { return username }
        return nil
    }

    private var hero: some View {
        SettingsHero(
            "Last.fm",
            subtitle: status.tone.apply(to: Text(status.line)),
            systemImage: "waveform",
            tint: .red
        ) {
            heroControl
        }
    }

    private var connectSection: some View {
        Section {
            connectButton
        } footer: {
            Text("Your password stays with Last.fm.")
        }
    }

    // MARK: - Connecting

    /// The pane's one control on the Mac. On iPhone, connecting is a row of its own.
    @ViewBuilder
    private var heroControl: some View {
        #if os(macOS)
        switch connection.state {
        case .idle, .failed:
            connectButton
        case .waitingForBrowser:
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.small)
                Button("Cancel", action: connection.cancel)
            }
        case .connected:
            EmptyView()
        }
        #endif
    }

    private var connectButton: some View {
        Button {
            connect()
        } label: {
            #if os(macOS)
            Text("Connect…")
            #else
            HStack {
                Text(connection.state == .waitingForBrowser ? "Waiting for Last.fm…" : "Connect to Last.fm…")
                Spacer()
                if connection.state == .waitingForBrowser {
                    ProgressView()
                }
            }
            #endif
        }
        .disabled(connection.state == .waitingForBrowser)
    }

    private func connect() {
        connection.connect(waitsForPage: isIOS) { url in
            #if os(iOS)
            // Last.fm doesn't redirect back after approval, so this returns when the person
            // closes the sheet (or when it's cancelled after the poll succeeds).
            _ = try? await webAuthentication.authenticate(
                using: url,
                callbackURLScheme: "tracks",
                preferredBrowserSession: .shared
            )
            #else
            openURL(url)
            #endif
        }
    }

    private var isIOS: Bool {
        #if os(iOS)
        true
        #else
        false
        #endif
    }

    // MARK: - Connected

    private var scrobblingSection: some View {
        Section {
            SettingsSwitch("Scrobble Songs", isOn: $scrobbles)
        }
    }

    private func accountSection(_ username: String) -> some View {
        Section {
            LabeledContent("Signed In As", value: username)
            if let profile = URL(string: "https://www.last.fm/user/\(username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? username)") {
                SettingsLinkRow(title: "Open Your Profile", systemImage: "person.crop.circle", tint: .red, destination: profile)
            }
        } header: {
            Text("Account")
        }
    }

    /// Stops an import too. How far it got is kept for this account, so connecting it again
    /// carries on from there.
    private func disconnect() {
        model.lastFMHistory?.stop()
        connection.disconnect()
    }

    private var disconnectSection: some View {
        Section {
            #if os(macOS)
            SettingsDetailRow(
                title: Text("Disconnect"),
                detail: Text("Your scrobbles stay on Last.fm.")
            ) {
                Button("Disconnect", role: .destructive, action: disconnect)
            }
            #else
            Button("Disconnect", role: .destructive, action: disconnect)
            #endif
        } footer: {
            #if os(iOS)
            Text("Your scrobbles stay on Last.fm.")
            #endif
        }
    }
}

extension LastFMSettingsStatus {
    /// Where a live connection stands, as Settings says it.
    init(_ state: LastFMConnection.State, scrobbles: Bool) {
        switch state {
        case .idle: self = .notSetUp
        case .waitingForBrowser: self = .connecting
        case .connected(let username): self = .connected(username: username, scrobbles: scrobbles)
        case .failed(let message): self = .failed(message)
        }
    }
}

extension LastFMSessionStore {
    /// The defaults key holding the connected username, in `CaptureSettings.sharedDefaults`.
    /// The same string as TracksCore's internal `usernameKey`, which can't be renamed without
    /// signing everyone out.
    ///
    /// Views watch it with `@AppStorage` to follow connecting and disconnecting. Saving and
    /// clearing a session write it after the Keychain, so when it changes, ``current`` is
    /// already up to date.
    static let usernameDefaultsKey = "TracksLastFMUsername"
}

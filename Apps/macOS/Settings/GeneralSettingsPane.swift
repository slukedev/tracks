import SwiftUI
import ServiceManagement
import TracksCore

/// Opening at login, the Dock, Apple Music access, and About.
struct GeneralSettingsPane: View {
    @Environment(MacAppBehaviour.self) private var behaviour
    @AppStorage("showMenuBarExtra") private var showsMenuBarExtra = true
    @State private var launchesAtLogin = false

    var body: some View {
        @Bindable var behaviour = behaviour

        Form {
            SettingsHero(
                "General",
                subtitle: statusTone.apply(to: Text(statusLine)),
                systemImage: "gearshape.fill",
                tint: .gray
            )

            Section {
                if let loginDetail {
                    SettingsDetailRow(title: Text("Open at Login"), detail: loginDetail) { loginControl }
                } else {
                    LabeledContent("Open at Login") { loginControl }
                }
                SettingsSwitch(
                    "Show in Dock",
                    detail: dockDetail,
                    isOn: $behaviour.showsDockIcon
                )
                // With the menu bar extra hidden too there'd be no way back into Tracks.
                .disabled(behaviour.showsDockIcon && !showsMenuBarExtra)
            } header: {
                Text("Startup")
            }

            AppleMusicAccessSection()
            AboutSection()
        }
        .settingsPane()
        .onAppear {
            // Reads the status only. Registering happens when the switch is flipped.
            behaviour.refreshLoginItemStatus()
            launchesAtLogin = behaviour.launchesAtLogin
        }
        .onChange(of: launchesAtLogin) { _, wanted in
            // Only a change the person made. Showing the current status, on appear or after
            // a failure, writes the value it already has.
            guard wanted != behaviour.launchesAtLogin else { return }
            behaviour.setLaunchesAtLogin(wanted)
            // What actually happened, so a failed attempt puts the switch back.
            launchesAtLogin = behaviour.launchesAtLogin
        }
    }

    @ViewBuilder
    private var loginControl: some View {
        if behaviour.launchAtLoginNeedsApproval {
            Button("Open Login Items…") { SMAppService.openSystemSettingsLoginItems() }
        }
        Toggle("Open at Login", isOn: $launchesAtLogin)
            .toggleStyle(.switch)
            .labelsHidden()
    }

    /// Where Tracks can be found, or the one thing left to do.
    private var statusLine: String {
        if behaviour.launchAtLoginNeedsApproval {
            return String(localized: "Allow Tracks under Login Items to finish turning on Open at Login.")
        }
        return switch (behaviour.showsDockIcon, showsMenuBarExtra) {
        case (true, true): String(localized: "In the Dock and the menu bar")
        case (true, false): String(localized: "In the Dock")
        case (false, _): String(localized: "In the menu bar")
        }
    }

    private var statusTone: SettingsTone {
        behaviour.launchAtLoginNeedsApproval ? .attention : .plain
    }

    /// Only a failure, or the wait for approval. The hero says what to do about it.
    private var loginDetail: Text? {
        if let error = behaviour.launchAtLoginError {
            return Text(error).foregroundStyle(.ink(.red))
        }
        if behaviour.launchAtLoginNeedsApproval {
            return Text("Waiting for Login Items.")
        }
        return nil
    }

    /// Only when the switch can't be turned off, so it doesn't look broken.
    private var dockDetail: Text? {
        guard behaviour.showsDockIcon && !showsMenuBarExtra else { return nil }
        return Text("Needed while Tracks isn’t in the menu bar.")
    }
}

#if DEBUG
/// A model on sample data. The flag goes in a volatile domain, so it never reaches the real
/// app's defaults, and a preview never opens the real store.
@MainActor
private let previewModel: AppModel = {
    UserDefaults.standard.setVolatileDomain(["TracksDemoData": true], forName: UserDefaults.argumentDomain)
    return AppModel()
}()

private struct PanePreview<Pane: View>: View {
    @ViewBuilder var pane: Pane

    var body: some View {
        pane
            .environment(previewModel)
            .environment(MacAppBehaviour())
    }
}

#Preview("General") { PanePreview { GeneralSettingsPane() } }
#Preview("History") { PanePreview { HistorySettingsPage() } }
#Preview("Radio") { PanePreview { RadioSettingsPage() } }
#Preview("Menu Bar") { PanePreview { MenuBarSettingsPane() } }
#endif

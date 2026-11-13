import SwiftUI
import TracksStats

/// Android-style settings: sections under primary-coloured headings, rows led by an icon.
struct SettingsScreen: View {
    @Environment(AppModel.self) var model
    @Environment(ServerStore.self) var servers
    @Environment(PlayerModel.self) var player
    @AppStorage("appearance") var appearance = ""
    @State var confirmsDelete = false
    @State var confirmsSignOut = false

    var body: some View {
        List {
            if let server = servers.server {
                Section {
                    SettingLabel(symbol: .server, title: server.url.host ?? server.name, detail: server.url.absoluteString)
                    SettingLabel(symbol: .person, title: server.username, detail: "Signed in")
                    Button {
                        confirmsSignOut = true
                    } label: {
                        SettingLabel(symbol: .signOut, title: "Sign out", detail: "Your history stays on this phone.")
                    }
                    .buttonStyle(.plain)
                } header: {
                    SettingsHeader("Music server")
                }
            }

            Section {
                #if DEBUG
                // For screenshots and trying the statistics without a history. Debug builds only:
                // the statistics are otherwise only ever what Tracks played.
                Toggle(isOn: Binding(get: { model.hasSamples }, set: { $0 ? model.addSamples() : model.removeSamples() })) {
                    SettingLabel(symbol: .sample, title: "Sample listening", detail: "Debug builds only. Ten months of made-up plays, kept apart from your own.")
                }
                #endif
                Button {
                    confirmsDelete = true
                } label: {
                    SettingLabel(symbol: .delete, title: "Delete history", detail: "\(Format.plays(model.log.plays.count)) on this phone", role: .error)
                }
                .buttonStyle(.plain)
                .disabled(model.isEmpty)
            } header: {
                SettingsHeader("Your history")
            }

            Section {
                Picker(selection: $appearance) {
                    Text("System default").tag("")
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                } label: {
                    SettingLabel(symbol: .theme, title: "Theme")
                }
                .pickerStyle(.menu)
            } header: {
                SettingsHeader("Appearance")
            }

            Section {
                SettingLabel(symbol: .info, title: "Tracks", detail: "Version \(Self.version). Statistics come only from what Tracks plays. Your history stays on this phone; Tracks collects no analytics.")
            } header: {
                SettingsHeader("About")
            }
        }
        .listStyle(.plain)
        .navigationTitle("Settings")
        .confirmationDialog("Sign out of your server?", isPresented: $confirmsSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) {
                player.stop()
                servers.disconnect()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Music stops, and you'll need your password to sign in again. Your history stays on this phone.")
        }
        .confirmationDialog("Delete your whole history?", isPresented: $confirmsDelete, titleVisibility: .visible) {
            Button("Delete history", role: .destructive) { model.deleteEverything() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Every play on this phone is removed, and the statistics start again. This can't be undone.")
        }
    }

    static var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "2.0"
    }
}

/// A settings section's heading, in `titleSmall` and the primary colour.
struct SettingsHeader: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
            .materialType(.titleSmall)
            .materialForeground(.primary)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A setting: its icon, name and a line about it.
struct SettingLabel: View {
    let symbol: Symbol
    let title: String
    var detail: String?
    var role: MaterialRole = .onSurface

    var body: some View {
        HStack(alignment: .center, spacing: Space.iconGap) {
            SymbolImage(symbol, role: role == .onSurface ? .onSurfaceVariant : role)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .materialType(.bodyLarge)
                    .materialForeground(role)
                if let detail {
                    Text(detail)
                        .materialType(.bodyMedium)
                        .materialForeground(.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .fullTapArea()
        .combinedAccessibility()
        .listRowSeparator(.hidden)
    }
}

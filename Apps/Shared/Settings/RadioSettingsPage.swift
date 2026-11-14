import SwiftUI
import TracksCore

/// Where radio songs go, and which stations count. An iPhone page, and the Mac's Radio pane.
struct RadioSettingsPage: View {
    private let settings = CaptureSettings()

    @AppStorage(CaptureSettings.autoAddKey, store: CaptureSettings.sharedDefaults)
    private var addsSongs = true
    /// Watched so the status follows a rename, including one made on another device.
    @AppStorage(CaptureSettings.playlistNameKey, store: CaptureSettings.sharedDefaults)
    private var storedName = ""

    /// Typed here and saved on Return or on leaving, so a half-typed name never names the
    /// playlist Tracks writes to.
    @State private var playlistName = ""

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var isNameFocused: Bool

    private var playlist: RadioSettingsWords.Playlist {
        RadioSettingsWords.Playlist(addsSongs: addsSongs, name: storedName)
    }

    var body: some View {
        Form {
            SettingsHero(
                "Radio",
                subtitle: Text(RadioSettingsWords.status(playlist)),
                systemImage: "dot.radiowaves.left.and.right",
                tint: .orange
            )
            playlistSection
            StationExclusionsSection()
        }
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: addsSongs)
        .onAppear { playlistName = settings.playlistName }
        .onDisappear(perform: saveName)
        .onChange(of: storedName) { playlistName = settings.playlistName }
        #if os(macOS)
        .settingsPane()
        #else
        .settingsPage("Radio")
        .scrollDismissesKeyboard(.interactively)
        #endif
    }

    private var playlistSection: some View {
        Section {
            SettingsSwitch("Add to Playlist", isOn: $addsSongs)
            if addsSongs {
                LabeledContent("Name") {
                    TextField("Name", text: $playlistName, prompt: Text(CaptureSettings.defaultPlaylistName))
                        .labelsHidden()
                        .multilineTextAlignment(.trailing)
                        .submitLabel(.done)
                        .focused($isNameFocused)
                        .onSubmit(saveName)
                        #if os(iOS)
                        // Grey like the values around it, until it's being typed in.
                        .foregroundStyle(isNameFocused ? AnyShapeStyle(.primary) : AnyShapeStyle(.secondary))
                        #endif
                }
            }
        } header: {
            Text("Playlist")
        } footer: {
            // Nothing can take a song back out, and turning this off doesn't either.
            if addsSongs {
                Text("To take songs out, remove them in Apple Music.")
                    .contentTransition(.opacity)
            }
        }
    }

    private func saveName() {
        // Blank means "use the default"; a single space once got saved and Music could never
        // find a playlist called " ".
        let trimmed = playlistName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            playlistName = settings.playlistName
            return
        }
        settings.playlistName = trimmed
    }
}

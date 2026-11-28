import SwiftUI
import AppKit
import TracksCore

/// The Mac's Sources pane: Apple Music, the songs on this Mac and every server, then
/// Play From once there are two. A Settings pane has nowhere to push, so each source's
/// controls sit in its own section.
struct SourcesSettingsPane: View {
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let summary = SourceWords.summary(model.sourceEntries)
        Form {
            SettingsHero(
                "Sources",
                subtitle: (summary.asksForAction ? SettingsTone.attention : .plain).apply(to: Text(summary.value)),
                systemImage: "square.stack.fill",
                tint: .indigo
            )
            AppleMusicAccessSection()
            deviceSection
            ServersSection()
            PlayFromSection()
        }
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: model.sourceEntries.map(\.id))
        .settingsPane()
        .onAppear(perform: model.refreshMusicAuthorization)
    }

    private var deviceSection: some View {
        Section {
            LabeledContent("Songs") {
                Text(music.fileTracks.count, format: .number)
                    .monospacedDigit()
                    .contentTransition(.numericText(value: Double(music.fileTracks.count)))
            }
            // A size of nothing reads as a fault, not a fact.
            if music.fileBytes > 0 {
                LabeledContent("Space Used", value: LocalFormat.bytes(music.fileBytes))
            }
            LabeledContent("Music Folder") {
                Button("Show in Finder", action: showMusicFolder)
                    .help(LibraryFolders.music.path(percentEncoded: false))
            }
            LabeledContent("Look for New Songs") {
                if music.isScanning {
                    ProgressView()
                        .controlSize(.small)
                        .frame(width: 80)
                } else {
                    Button("Look Now") { Task { await music.scan() } }
                }
            }
        } header: {
            Text(MusicSource.deviceName)
        }
    }

    /// The Music folder in Finder, made first, so there's always somewhere to put songs.
    private func showMusicFolder() {
        let folder = LibraryFolders.music
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        NSWorkspace.shared.activateFileViewerSelecting([folder])
    }
}

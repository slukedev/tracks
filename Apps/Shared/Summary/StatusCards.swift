import SwiftUI
import MusicKit
import TracksCore

/// What's playing right now, shown at the top of Summary while there's music.
struct NowPlayingCard: View {
    @Environment(CaptureService.self) private var capture
    @AppStorage(CaptureSettings.scrobblesToLastFMKey, store: CaptureSettings.sharedDefaults)
    private var scrobbles = true
    /// Follows connecting and disconnecting in Settings, without a Keychain read per render.
    @AppStorage(LastFMSessionStore.usernameDefaultsKey, store: CaptureSettings.sharedDefaults)
    private var lastFMUsername: String?
    @Environment(PlayerModel.self) private var player
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let song = capture.nowPlaying {
            Card(padding: Design.Spacing.medium) {
                HStack(spacing: Design.Spacing.medium) {
                    cover(of: song)
                    VStack(alignment: .leading, spacing: 2) {
                        Label {
                            Text(caption)
                        } icon: {
                            PlayingWaveform(isActive: true)
                        }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tint)
                        Text(song.title)
                            .font(.headline)
                            .lineLimit(1)
                        Text(song.artistName)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
            }
            .accessibilityElement(children: .combine)
            .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
        }
    }

    /// Tracks’ own player has the song's cover to hand, even one only MusicKit can draw, and
    /// before any lookup: used when it's the one playing. Otherwise the cover capture found.
    @ViewBuilder
    private func cover(of song: NowPlaying) -> some View {
        if let track = player.current, player.isPlaying,
           track.title == song.title, track.artistName == song.artistName {
            CoverImage(cover: track.cover, size: 52)
        } else {
            ArtworkView(url: song.artworkURL, seed: song.albumTitle ?? song.title, size: 52)
        }
    }

    private var caption: LocalizedStringKey {
        if let station = capture.currentStation, capture.lastCapture?.kind == .radio {
            return "Playing on \(station)"
        }
        return lastFMUsername != nil && scrobbles ? "Now Playing · Scrobbling" : "Now Playing"
    }
}

/// What's wrong with the library, above everything else on Summary, as Settings puts
/// something that needs you first. Nothing while the library is open, which is every run but
/// a broken one.
struct StoreWarningBanner: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let keptAside = LibraryKeptAside(model: model) {
            StatusPanel(title: Text(LibraryKeptAside.title), systemImage: "tray.full.fill") {
                keptAside.status
            }
            .contentTransition(.opacity)
        } else if !model.isShowingSampleData, case .inMemory = model.store?.backing {
            // The reason is for diagnostics, in the backing's description, not for the panel.
            StatusPanel(title: Text("Listening Isn't Being Saved"), systemImage: "exclamationmark.triangle.fill") {
                Text("Tracks couldn't open its library. Quit and reopen Tracks to try again.")
            }
        }
    }
}

/// A status in a panel: an orange symbol, a title and one line.
struct StatusPanel<Line: View>: View {
    let title: Text
    let systemImage: String
    @ViewBuilder var line: Line

    var body: some View {
        Card {
            Label {
                VStack(alignment: .leading, spacing: Design.Spacing.xxSmall) {
                    title
                        .font(.headline)
                    line
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } icon: {
                Image(systemName: systemImage)
                    .foregroundStyle(.orange)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The library's status and what's playing, at the top of Summary. Each hides itself.
struct StatusPanels: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if LibraryKeptAside(model: model) != nil || model.capture?.nowPlaying != nil || model.hasBanner {
            VStack(spacing: Design.Card.spacing) {
                StoreWarningBanner()
                NowPlayingCard()
            }
        }
    }
}

/// The first-run screen: what Tracks does, before there's any history.
struct WelcomeView: View {
    var body: some View {
        VStack(spacing: Design.Spacing.xxLarge) {
            ContentUnavailableView {
                Label("No Listening Yet", systemImage: "waveform")
            } description: {
                Text("Play something in Tracks to see it here.")
            }
            // First run is when the reason for Apple Music access is easiest to see.
            MusicAccessCard()
                .frame(maxWidth: 520)
        }
    }
}

/// Asks for Apple Music access, or says how to turn it back on.
///
/// Tracks doesn't ask at launch: a prompt with no context is easy to refuse, and your own
/// music plays without access. Shows nothing once access is granted, or with sample data.
struct MusicAccessCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var isRequesting = false

    var body: some View {
        if !model.isShowingSampleData, model.musicAuthorization != .authorized {
            Card {
                VStack(alignment: .leading, spacing: Design.Spacing.medium) {
                    Label {
                        Text(title)
                            .font(.subheadline.weight(.semibold))
                    } icon: {
                        Image(systemName: model.musicAuthorization == .notDetermined ? "music.note" : "exclamationmark.triangle.fill")
                            .foregroundStyle(model.musicAuthorization == .notDetermined ? AnyShapeStyle(.tint) : AnyShapeStyle(.orange))
                    }
                    if let message {
                        Text(message)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    action
                }
            }
            .onAppear(perform: model.refreshMusicAuthorization)
        }
    }

    private var title: LocalizedStringKey {
        switch model.musicAuthorization {
        case .denied: "Apple Music Access Is Off"
        case .restricted: "Apple Music Access Is Restricted"
        default: "Play Apple Music in Tracks"
        }
    }

    /// Only where access is off: the title and the button say the rest.
    private var message: LocalizedStringKey? {
        switch model.musicAuthorization {
        case .denied:
            "Turn on access for Tracks in \(Self.settingsName)."
        case .restricted:
            "Screen Time or a device profile doesn’t allow Apple Music access."
        default:
            nil
        }
    }

    @ViewBuilder
    private var action: some View {
        switch model.musicAuthorization {
        case .notDetermined:
            Button("Allow Apple Music Access") {
                isRequesting = true
                Task {
                    await model.requestMusicAccess()
                    isRequesting = false
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(isRequesting)
        default:
            Button(Self.openSettingsTitle) {
                if let url = Self.settingsURL { openURL(url) }
            }
            .buttonStyle(.bordered)
        }
    }

    #if os(iOS)
    private static var settingsName: String { String(localized: "Settings") }
    private static let openSettingsTitle: LocalizedStringKey = "Open Settings"
    private static let settingsURL = URL(string: UIApplication.openSettingsURLString)
    #else
    private static var settingsName: String {
        String(localized: "System Settings › Privacy & Security › Media & Apple Music")
    }
    private static let openSettingsTitle: LocalizedStringKey = "Open System Settings"
    /// Privacy & Security › Media & Apple Music.
    private static let settingsURL = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Media")
    #endif
}

#if DEBUG
#Preview("Status") {
    VStack(spacing: Design.Card.spacing) {
        StatusPanel(title: Text(LibraryKeptAside.title), systemImage: "tray.full.fill") {
            LibraryKeptAside(opensOnRelaunch: false).status
        }
        StatusPanel(title: Text(LibraryKeptAside.title), systemImage: "tray.full.fill") {
            LibraryKeptAside(opensOnRelaunch: true).status
        }
    }
    .padding(Design.Margin.page)
}

#Preview("Status, AX5") {
    ScrollView {
        StatusPanel(title: Text(LibraryKeptAside.title), systemImage: "tray.full.fill") {
            LibraryKeptAside(opensOnRelaunch: true).status
        }
        .padding(Design.Margin.page)
    }
    .dynamicTypeSize(.accessibility5)
}
#endif

import SwiftUI
import MusicKit
import TracksCore

/// What Tracks needs from Spotify to list it as a source and play from it. Provided only in
/// builds with the `SPOTIFY` flag, by setting ``AppModel/spotify``.
@MainActor
protocol SpotifySource: AnyObject {
    /// Signed in with an account that can play.
    var isConnected: Bool { get }
    /// Signed out, refused or expired: its row asks to sign in.
    var needsSignIn: Bool { get }
    /// Opening the account saved on this device, which can take until the device is first
    /// unlocked: a Spotify filter chosen before stays chosen meanwhile.
    var isChecking: Bool { get }
    /// The account's display name, never its email.
    var accountName: String? { get }
    var engine: any PlayerEngine { get }
    /// Its page in Settings ▸ Sources: signing in, quality, Scrobble to Last.fm, Sign Out….
    func settingsPage() -> AnyView
}

/// A source's state in one word, for its row. Worst first.
enum SourceStatus: Hashable {
    case signIn
    case offline
    /// With how far it's got, where that's known.
    case syncing(Double?)
    case connecting
    /// Reached at an address away from the home network.
    case away
    case connected
    case libraryOnly
    case off
    case songs(Int)

    var word: String {
        switch self {
        case .signIn: String(localized: "Sign In")
        case .offline: String(localized: "Offline")
        case .syncing(let fraction?): String(localized: "Syncing \(fraction.formatted(.percent.precision(.fractionLength(0))))")
        case .syncing(nil): String(localized: "Syncing")
        case .connecting: String(localized: "Connecting")
        case .away: String(localized: "Away")
        case .connected: String(localized: "Connected")
        case .libraryOnly: String(localized: "Library Only")
        case .off: String(localized: "Off")
        case .songs(let count): SourceWords.songs(count)
        }
    }

    /// Only these ask for something to be done, so only these are tinted.
    var asksForAction: Bool {
        self == .signIn || self == .offline
    }
}

/// One added source, as Settings and the source filter list it.
struct SourceEntry: Identifiable, Hashable {
    let id: SourceID
    let name: String
    let status: SourceStatus
    /// Whether the filter offers it: Apple Music with access turned off doesn't count.
    let canPlay: Bool

    var symbol: String { SourceWords.symbol(for: id) }
    var tint: Color { SourceWords.tint(for: id) }
}

enum SourceWords {
    static func songs(_ count: Int) -> String {
        count == 1 ? String(localized: "1 Song") : String(localized: "\(count.formatted()) Songs")
    }

    static func symbol(for source: SourceID) -> String {
        switch source {
        case .appleMusic: MusicSource.appleMusic.symbol
        case .device: MusicSource.deviceSymbol
        case .server: "server.rack"
        case .spotify: MusicSource.spotify.symbol
        case .unknown: "music.quarternote.3"
        }
    }

    static func tint(for source: SourceID) -> Color {
        switch source {
        case .appleMusic: .pink
        case .device: .blue
        case .server: .indigo
        case .spotify: .black
        case .unknown: .gray
        }
    }

    /// The root row's value: the worst problem, else the one source's name or how many.
    static func summary(_ entries: [SourceEntry]) -> (value: String, asksForAction: Bool) {
        let offline = entries.filter { $0.status == .offline }.count
        if entries.contains(where: { $0.status == .signIn }) { return (String(localized: "Sign In"), true) }
        if offline > 0 { return (String(localized: "\(offline) Offline"), true) }
        switch entries.count {
        case 0: return (String(localized: "None"), false)
        case 1: return (entries[0].name, false)
        default: return (String(localized: "\(entries.count) Sources"), false)
        }
    }
}

extension PlayFromOrder {
    /// As chosen in Settings ▸ Sources, synced between devices.
    static var current: PlayFromOrder {
        PlayFromOrder(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "")
    }
}

extension AppModel {
    /// Every source added, in a steady order: Apple Music, Spotify, this device, then servers
    /// in the order they were added.
    var sourceEntries: [SourceEntry] {
        var entries: [SourceEntry] = []
        if isShowingSampleData {
            entries.append(SourceEntry(id: .appleMusic, name: String(localized: "Apple Music"), status: .connected, canPlay: true))
        } else {
            switch musicAuthorization {
            case .authorized:
                let status: SourceStatus = playFeed.cannotPlayCatalog ? .libraryOnly : .connected
                entries.append(SourceEntry(id: .appleMusic, name: String(localized: "Apple Music"), status: status, canPlay: true))
            case .denied, .restricted:
                entries.append(SourceEntry(id: .appleMusic, name: String(localized: "Apple Music"), status: .off, canPlay: false))
            default:
                break
            }
        }
        // Listed once signed in, or once signed in before and now asking to be again.
        if let spotify, spotify.isConnected || spotify.needsSignIn || spotify.isChecking {
            entries.append(SourceEntry(
                id: .spotify,
                name: String(localized: "Spotify"),
                status: spotify.needsSignIn ? .signIn : spotify.isChecking ? .connecting : .connected,
                canPlay: spotify.isConnected
            ))
        }
        let files = yourMusic.fileTracks.count
        if files > 0 {
            entries.append(SourceEntry(id: .device, name: MusicSource.deviceName, status: .songs(files), canPlay: true))
        }
        let servers = yourMusic.servers
        for server in servers.servers {
            entries.append(SourceEntry(id: .server(server.id), name: server.name, status: status(of: server, in: servers), canPlay: true))
        }
        return entries
    }

    private func status(of server: SubsonicServer, in servers: MusicServers) -> SourceStatus {
        switch servers.status[server.id] {
        case .some(.wrongPassword): return .signIn
        case .some(.offline), .some(.failed): return .offline
        default: break
        }
        if servers.syncing.contains(server.id) { return .syncing(servers.syncProgress[server.id]?.fraction) }
        switch servers.status[server.id] {
        case .some(.online): return servers.isAway(server.id) ? .away : .connected
        default: return .connecting
        }
    }

    /// The sources the filter offers.
    var playableSources: [SourceID] {
        sourceEntries.filter(\.canPlay).map(\.id)
    }

    /// The players those sources play through, each once.
    var playerSources: [MusicSource] {
        var players: [MusicSource] = []
        for source in playableSources where !players.contains(MusicSource(source)) {
            players.append(MusicSource(source))
        }
        return players
    }

    /// The filter as it applies: one source only while it's still there and another is too.
    var effectiveSourceSelection: SourceSelection {
        sourceSelection.effective(among: playableSources)
    }

    /// Narrows Play and Library to one source, or opens them to all. What's playing and
    /// what's queued carry on.
    func chooseSources(_ selection: SourceSelection) {
        sourceSelection = selection
        followSources()
    }

    /// Keeps Play on a player that has music: the filter's, or with every source on, the one
    /// already on while it still has a source. Waits for the device's own songs to be read,
    /// so a launch never moves away from them before they're found.
    func followSources() {
        yourMusic.scope = yourMusicScope
        // Spotify is still opening the account saved here: a Spotify filter or player chosen
        // before stays as it was, rather than moving away and forgetting the choice.
        if let spotify, spotify.isChecking,
           sourceSelection == .only(.spotify) || musicSource == .spotify {
            return
        }
        let target: MusicSource
        switch effectiveSourceSelection {
        case .only(let source):
            target = MusicSource(source)
        case .all:
            let players = playerSources
            guard yourMusic.hasScanned || isShowingSampleData, !players.isEmpty, !players.contains(musicSource) else { return }
            target = players[0]
        }
        switchSource(to: target)
    }
}

extension AppModel {
    /// What Play and Library show of your own music: one source's songs while the filter
    /// names this device or a server, otherwise all of them.
    var yourMusicScope: SourceID? {
        guard case .only(let source) = effectiveSourceSelection else { return nil }
        switch source {
        case .device, .server: return source
        case .appleMusic, .spotify, .unknown: return nil
        }
    }
}

/// A source's icon: its symbol, or Spotify's own mark, never recoloured.
struct SourceGlyph: View {
    let source: SourceID

    var body: some View {
        if case .spotify = source {
            Image("SpotifyIcon")
                .resizable()
                .scaledToFit()
                .accessibilityLabel("Spotify")
        } else {
            Image(systemName: SourceWords.symbol(for: source))
        }
    }
}

/// A source's row in Settings: its icon tile, its name as the server or account has it, and
/// its status word, tinted only when it asks for something.
struct SourceRowLabel: View {
    let entry: SourceEntry

    var body: some View {
        LabeledContent {
            Text(entry.status.word)
                .foregroundStyle(entry.status.asksForAction ? AnyShapeStyle(.ink(.orange)) : AnyShapeStyle(.secondary))
        } label: {
            Label {
                Text(verbatim: entry.name)
                    .lineLimit(1)
            } icon: {
                if case .spotify = entry.id {
                    SourceGlyph(source: .spotify)
                        .frame(width: 28, height: 28)
                } else {
                    SettingsIconTile(systemImage: entry.symbol, color: entry.tint)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

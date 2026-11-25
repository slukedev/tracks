import SwiftUI
import UniformTypeIdentifiers
import MusicKit
import TracksCore

/// Play From: which copy plays when a song is in several sources, first that can play wins.
/// Shown once there are two sources to rank. Any place can go anywhere: drag to reorder, in
/// Edit on iPhone.
struct PlayFromSection: View {
    @Environment(AppModel.self) private var model
    @AppStorage(PlayFromOrder.storageKey) private var stored = ""

    var body: some View {
        let places = visiblePlaces
        if places.count > 1 {
            Section {
                ForEach(places, id: \.self) { place in
                    Label {
                        Text(verbatim: name(of: place))
                            .lineLimit(1)
                    } icon: {
                        icon(for: place)
                    }
                }
                .onMove { from, to in
                    var moved = places
                    moved.move(fromOffsets: from, toOffset: to)
                    save(moved)
                }
            } header: {
                Text("Play From")
            }
        }
    }

    private var order: PlayFromOrder { PlayFromOrder(rawValue: stored) }

    /// The places that can hold a copy on this device now, servers in the order they were added.
    private var visiblePlaces: [PlayFromPlace] {
        let added = model.sourceEntries.filter(\.canPlay).map(\.id)
        let servers = added.filter(\.isServer).map(PlayFromPlace.source)
        let others = PlayFromOrder.starting.filter { place in
            switch place {
            case .downloads: !servers.isEmpty
            case .source(let source): added.contains(source)
            }
        }
        return order.arranged(others + servers)
    }

    private func save(_ moved: [PlayFromPlace]) {
        stored = order.reordered(moved).rawValue
    }

    private func name(of place: PlayFromPlace) -> String {
        switch place {
        case .downloads: String(localized: "Downloads")
        case .source(let source):
            model.sourceEntries.first { $0.id == source }?.name ?? String(localized: "Server")
        }
    }

    @ViewBuilder
    private func icon(for place: PlayFromPlace) -> some View {
        switch place {
        case .downloads:
            SettingsIconTile(systemImage: "arrow.down.circle.fill", color: .green)
        case .source(.spotify):
            SourceGlyph(source: .spotify)
                .frame(width: 28, height: 28)
        case .source(let source):
            SettingsIconTile(systemImage: SourceWords.symbol(for: source), color: SourceWords.tint(for: source))
        }
    }
}

#if os(iOS)
/// Settings' first row: every source in one word, the worst problem first.
struct SourcesRow: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let summary = SourceWords.summary(model.sourceEntries)
        NavigationLink(value: SettingsPage.sources) {
            LabeledContent {
                Text(summary.value)
                    .foregroundStyle(summary.asksForAction ? AnyShapeStyle(.ink(.orange)) : AnyShapeStyle(.secondary))
                    .contentTransition(.opacity)
            } label: {
                Label {
                    Text("Sources")
                } icon: {
                    SettingsIconTile(systemImage: "square.stack.fill", color: .indigo)
                }
            }
        }
    }
}

/// Settings ▸ Sources: each source with its status, its own page a tap away, Add Source…,
/// and Play From once there are two.
struct SourcesPage: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let entries = model.sourceEntries
        Form {
            Section {
                ForEach(entries) { entry in
                    NavigationLink {
                        SourceDetail(entry: entry)
                    } label: {
                        SourceRowLabel(entry: entry)
                    }
                }
                NavigationLink("Add Source…") {
                    AddSourcePage()
                }
            }
            PlayFromSection()
        }
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: entries.map(\.id))
        .navigationTitle("Sources")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if entries.filter(\.canPlay).count > 1 {
                ToolbarItem(placement: .primaryAction) { EditButton() }
            }
        }
        .onAppear(perform: model.refreshMusicAuthorization)
    }
}

/// One source's own page.
private struct SourceDetail: View {
    let entry: SourceEntry
    @Environment(AppModel.self) private var model

    var body: some View {
        switch entry.id {
        case .appleMusic: AppleMusicSettingsPage()
        case .device: DeviceSourcePage()
        case .server(let id): ServerDetailPage(serverID: id)
        case .spotify: model.spotify?.settingsPage() ?? AnyView(EmptyView())
        case .unknown: EmptyView()
        }
    }
}

/// On This iPhone: songs brought in from Files, and the Music folder Tracks always reads.
struct DeviceSourcePage: View {
    @Environment(YourMusic.self) private var music
    @State private var importsFolder: Bool?

    var body: some View {
        Form {
            SettingsHero(
                LocalizedStringKey(MusicSource.deviceName),
                subtitle: Text(SourceWords.songs(music.fileTracks.count)),
                systemImage: MusicSource.deviceSymbol,
                tint: SourceWords.tint(for: .device)
            )
            Section {
                Button("Import Songs…", systemImage: "music.note") { importsFolder = false }
                Button("Add Folder…", systemImage: "folder") { importsFolder = true }
            } footer: {
                Text("Songs in On My iPhone ▸ Tracks ▸ Music in the Files app are always here.")
            }
            Section {
                if music.fileBytes > 0 {
                    LabeledContent("Space Used", value: LocalFormat.bytes(music.fileBytes))
                }
                Button {
                    Task { await music.scan() }
                } label: {
                    HStack {
                        Label(music.isScanning ? "Looking for Songs…" : "Look for New Songs", systemImage: "arrow.clockwise")
                        Spacer()
                        if music.isScanning { ProgressView() }
                    }
                }
                .disabled(music.isScanning)
            }
        }
        .settingsPage(LocalizedStringKey(MusicSource.deviceName))
        .fileImporter(
            isPresented: Binding(get: { importsFolder != nil }, set: { if !$0 { importsFolder = nil } }),
            allowedContentTypes: importsFolder == true ? [.folder] : [.audio],
            allowsMultipleSelection: importsFolder != true
        ) { result in
            if case .success(let urls) = result {
                Task { await music.importItems(urls) }
            }
        }
    }
}

/// Add Source: the sources not added yet. Server opens the server form, which finds servers
/// on the network itself.
struct AddSourcePage: View {
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @State private var addsServer = false
    @State private var isRequesting = false
    @State private var added = 0

    var body: some View {
        Form {
            Section {
                if model.musicAuthorization == .notDetermined, !model.isShowingSampleData {
                    Button {
                        isRequesting = true
                        Task {
                            await model.requestMusicAccess()
                            isRequesting = false
                            if model.musicAuthorization == .authorized { added += 1 }
                        }
                    } label: {
                        row("Apple Music", source: .appleMusic)
                    }
                    .disabled(isRequesting)
                }
                #if SPOTIFY
                if let spotify = model.spotify, !spotify.isConnected {
                    NavigationLink {
                        spotify.settingsPage()
                    } label: {
                        row("Spotify", source: .spotify)
                    }
                }
                #endif
                if music.fileTracks.isEmpty {
                    NavigationLink {
                        DeviceSourcePage()
                    } label: {
                        row(LocalizedStringKey(MusicSource.deviceName), source: .device)
                    }
                }
                Button {
                    addsServer = true
                } label: {
                    row("Server", source: .server(""))
                }
            }
        }
        .navigationTitle("Add Source")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $addsServer) { ServerForm() }
        .sensoryFeedback(Design.Haptic.done, trigger: added)
    }

    private func row(_ title: LocalizedStringKey, source: SourceID) -> some View {
        Label {
            Text(title)
                .foregroundStyle(.primary)
        } icon: {
            if case .spotify = source {
                SourceGlyph(source: .spotify)
                    .frame(width: 28, height: 28)
            } else {
                SettingsIconTile(systemImage: SourceWords.symbol(for: source), color: SourceWords.tint(for: source))
            }
        }
    }
}
#endif

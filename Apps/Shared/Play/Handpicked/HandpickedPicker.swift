import SwiftUI
import MusicKit
import TracksCore

/// Making a Handpicked station: pick songs as Music adds songs to a playlist, from a search of
/// what you've played, your library and Apple Music (or your music and your servers),
/// suggestions like your picks, your playlists, what you've played lately and what you play
/// most, then start it. Each cover plays the song's preview; the picks gather in a tray along
/// the bottom, to remove and put in order.
struct HandpickedPicker: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @Environment(YourMusic.self) private var music
    @State private var query = ""
    @State private var found: PickSearch?
    @State private var recent: [Handpick] = []
    @State private var top: [Handpick] = []
    @State private var suggestions: [Handpick] = []
    @State private var hasLoaded = false
    @State private var selection = HandpickedSelection()
    @State private var preview = HandpickedPreview()
    @State private var isStarting = false
    #if os(macOS)
    @State private var focus: Set<PickRowID> = []
    #endif

    var body: some View {
        let source = model.musicSource
        NavigationStack {
            list(source: source)
                .navigationTitle(Handpicked.title)
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .searchable(text: $query, prompt: "Songs")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel", role: .cancel) { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if isStarting {
                            ProgressView()
                                .controlSize(.small)
                                .accessibilityLabel("Starting Station")
                        } else {
                            Button("Start", role: .confirm, action: start)
                                .disabled(selection.picks.isEmpty)
                        }
                    }
                }
                .pickedTray()
                .navigationDestination(for: PickerPlaylist.self) { playlist in
                    PickerPlaylistSongs(playlist: playlist)
                }
                .task { await loadHistory(source: source) }
                .task(id: query) { await search(source: source) }
                .task(id: selection.picks.map(\.identity)) { await suggest(source: source) }
        }
        .environment(selection)
        .environment(preview)
        .background { PreviewGuard(preview: preview) }
        .onDisappear { preview.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { preview.stop() }
        }
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 560, minHeight: 560, idealHeight: 680)
        #endif
    }

    // MARK: The list

    @ViewBuilder
    private func list(source: MusicSource) -> some View {
        #if os(macOS)
        List(selection: $focus) { content(source: source) }
            .contextMenu(forSelectionType: PickRowID.self) { rows in
                if rows.count == 1, let row = rows.first, let pick = pick(for: row) {
                    PickMenu(pick: pick)
                }
            } primaryAction: { rows in
                withAnimation(PlayMotion.row) {
                    for pick in rows.compactMap(pick(for:)) { selection.toggle(pick) }
                }
            }
            .onKeyPress(.space) {
                guard let row = focus.first, let pick = pick(for: row) else { return .ignored }
                preview.toggle(pick, source: source, music: music, player: player)
                return .handled
            }
        #else
        List { content(source: source) }
            .listStyle(.insetGrouped)
        #endif
    }

    @ViewBuilder
    private func content(source: MusicSource) -> some View {
        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            browse(source: source)
        } else {
            searchResults
        }
    }

    @ViewBuilder
    private func browse(source: MusicSource) -> some View {
        if PickerPlaylist.canBrowse(source: source, isDemo: player.isDemo) {
            Section {
                NavigationLink {
                    PickerPlaylists()
                } label: {
                    Label("Playlists", systemImage: "music.note.list")
                }
            }
        }
        rows(suggestions, in: .suggestions)
        rows(recent, in: .recent)
        rows(top, in: .top)
        if hasLoaded, recent.isEmpty, top.isEmpty {
            Section {
                Text("Search for songs you love, or pick from your playlists. Songs you play show up here too.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var searchResults: some View {
        if let found {
            let sections = found.visible
            ForEach(sections, id: \.section) { part in
                rows(part.picks, in: part.section)
            }
            if !found.pending.isEmpty {
                HStack {
                    Spacer()
                    ProgressView()
                        .controlSize(sections.isEmpty ? .regular : .small)
                    Spacer()
                }
                .listRowBackground(Color.clear)
                .accessibilityLabel("Searching")
            } else if sections.isEmpty {
                ContentUnavailableView.search(text: query)
                    .listRowBackground(Color.clear)
            }
        } else {
            HStack {
                Spacer()
                ProgressView()
                Spacer()
            }
            .listRowBackground(Color.clear)
            .accessibilityLabel("Searching")
        }
    }

    @ViewBuilder
    private func rows(_ picks: [Handpick], in section: PickSection) -> some View {
        if !picks.isEmpty {
            Section(section.title) {
                ForEach(picks) { pick in
                    PickRow(pick: pick)
                        .tag(PickRowID(section: section, identity: pick.identity))
                }
            }
        }
    }

    private func pick(for row: PickRowID) -> Handpick? {
        let shown: [Handpick] = switch row.section {
        case .suggestions: suggestions
        case .recent: recent
        case .top: top
        default: found?.sections[row.section] ?? []
        }
        return shown.first { $0.identity == row.identity }
    }

    // MARK: Starting

    private func start() {
        isStarting = true
        preview.stop(resumingSong: false)
        let picks = selection.picks
        Task {
            await HandpickedPlayback.start(model: model, picks: picks)
            dismiss()
        }
    }

    // MARK: Loading

    /// Songs from the history that can play from the source on: for your own music, only the
    /// ones in it, as your songs.
    private func loadHistory(source: MusicSource) async {
        let (history, signals) = (model.library.history, player.signals)
        let (recentSongs, mostSongs) = await OffMainActor.run {
            (HandpickedChoices.recentlyPlayed(in: history, signals: signals, limit: 40),
             HandpickedChoices.mostPlayed(in: history, signals: signals, limit: 40))
        }
        recent = Array(picks(from: recentSongs, source: source).prefix(15))
        top = Array(picks(from: mostSongs, source: source).prefix(25))
        hasLoaded = true
    }

    /// Songs of yours like the picks. The ones already shown stay where they are while they're
    /// still like them, and new ones join at the end, so nothing moves under your finger.
    private func suggest(source: MusicSource) async {
        let picked = selection.picks.map(\.mixSong)
        guard !picked.isEmpty else {
            withAnimation(PlayMotion.row) { suggestions = [] }
            return
        }
        try? await Task.sleep(for: .milliseconds(400))
        guard !Task.isCancelled else { return }
        let (history, signals) = (model.library.history, player.signals)
        let like = await OffMainActor.run {
            HandpickedChoices.like(picked, in: history, signals: signals, limit: 40)
        }
        guard !Task.isCancelled else { return }
        let fresh = picks(from: like, source: source)
        let still = Set(fresh.map(\.identity))
        let kept = suggestions.filter { still.contains($0.identity) || selection.contains($0.identity) }
        let next = Array(HandpickedChoices.merged([kept, fresh], identity: \.identity).prefix(Self.suggestionCount))
        withAnimation(PlayMotion.row) { suggestions = next }
    }

    private static let suggestionCount = 8

    private func picks(from songs: [MixSong], source: MusicSource) -> [Handpick] {
        switch source {
        case .appleMusic:
            // Songs played from your own music have no Apple Music id to play by.
            return songs.filter { player.canPlay(songID: $0.songID) && music.index.track(id: $0.songID) == nil }.map(Handpick.init)
        case .yourMusic:
            var seen = Set<String>()
            return songs.compactMap { music.track(for: HistorySong($0)) }
                .filter { seen.insert($0.identity).inserted }
                .map { Handpick($0, music: music) }
        }
    }

    // MARK: Searching

    /// Every place a song could be found at once, each showing as it answers: what you've
    /// played and your music first, as they answer at once, then the library, the catalog and
    /// your servers.
    private func search(source: MusicSource) async {
        let term = query.trimmingCharacters(in: .whitespaces)
        guard !term.isEmpty else {
            found = nil
            return
        }
        // Typing settles before asking.
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        let sections = PickSearch.sections(source: source, isDemo: player.isDemo, hasServers: !music.servers.servers.isEmpty)
        found = PickSearch(term: term, order: sections)
        // All asked at once, and taken in order: a place only shows once the ones before it
        // have answered anyway.
        let lookups = sections.map { section in
            (section, Task { await songs(in: section, matching: term, source: source) })
        }
        await withTaskCancellationHandler {
            for (section, lookup) in lookups {
                let picks = await lookup.value
                guard !Task.isCancelled, found?.term == term else { return }
                withAnimation(PlayMotion.row) { found?.arrived(picks, in: section) }
            }
        } onCancel: {
            for (_, lookup) in lookups { lookup.cancel() }
        }
    }

    private func songs(in section: PickSection, matching term: String, source: MusicSource) async -> [Handpick] {
        switch section {
        case .history:
            let (history, signals) = (model.library.history, player.signals)
            let played = await OffMainActor.run {
                HandpickedChoices.matching(term, in: history, signals: signals, limit: 30)
            }
            return Array(picks(from: played, source: source).prefix(8))
        case .library:
            guard MusicAuthorization.currentStatus == .authorized else { return [] }
            var request = MusicLibrarySearchRequest(term: term, types: [Song.self])
            request.limit = 12
            let songs = (try? await request.response().songs).map(Array.init) ?? []
            return PlayPreferences.versions(of: songs).map(Handpick.init(song:))
        case .catalog:
            return await catalogSongs(matching: term)
        case .yours:
            return music.index.search(term, limit: 40).tracks
                .filter(music.isPlayable)
                .map { Handpick($0, music: music) }
        case .servers:
            let page = await music.searchServers(term, isFull: false)
            return page.tracks.filter(music.isPlayable).prefix(20).map { Handpick($0, music: music) }
        case .suggestions, .recent, .top, .playlist, .picked:
            return []
        }
    }

    private func catalogSongs(matching term: String) async -> [Handpick] {
        #if DEBUG
        if player.isDemo {
            return DemoCatalog.songs(count: 160, offset: 0, artists: nil)
                .filter { $0.title.localizedStandardContains(term) || $0.artistName.localizedStandardContains(term) }
                .map(Handpick.init(song:))
        }
        #endif
        guard MusicAuthorization.currentStatus == .authorized else { return [] }
        var request = MusicCatalogSearchRequest(term: term, types: [Song.self])
        request.limit = 25
        let songs = (try? await request.response().songs).map(Array.init) ?? []
        return PlayPreferences.versions(of: songs).map(Handpick.init(song:))
    }
}

/// A search's results, by where they were found, as they arrive.
struct PickSearch {
    let term: String
    let order: [PickSection]
    private(set) var sections: [PickSection: [Handpick]] = [:]
    private(set) var pending: Set<PickSection>

    init(term: String, order: [PickSection]) {
        self.term = term
        self.order = order
        pending = Set(order)
    }

    /// Where to look: Apple Music's places, or your music's.
    static func sections(source: MusicSource, isDemo: Bool, hasServers: Bool) -> [PickSection] {
        switch source {
        case .appleMusic: isDemo ? [.history, .catalog] : [.history, .library, .catalog]
        case .yourMusic: hasServers ? [.yours, .servers] : [.yours]
        }
    }

    mutating func arrived(_ picks: [Handpick], in section: PickSection) {
        sections[section] = picks
        pending.remove(section)
    }

    /// The places that found something, in order, each song in the first place it was found.
    /// A place shows once the ones before it have answered, so rows don't jump as they do.
    var visible: [(section: PickSection, picks: [Handpick])] {
        var shown: [(section: PickSection, picks: [Handpick])] = []
        var seen = Set<String>()
        for section in order {
            guard let picks = sections[section] else { break }
            let fresh = picks.filter { seen.insert($0.identity).inserted }
            if !fresh.isEmpty { shown.append((section, fresh)) }
        }
        return shown
    }
}

/// Lets the preview give way when music starts some other way: from the keyboard, the menu
/// bar or Control Center.
private struct PreviewGuard: View {
    let preview: HandpickedPreview
    @Environment(PlayerModel.self) private var player

    var body: some View {
        Color.clear
            .onChange(of: player.isPlaying) { _, isPlaying in
                if isPlaying { preview.songStartedElsewhere() }
            }
    }
}

// MARK: - Playlists

/// A playlist to pick songs from: Apple Music's, or one of yours in your own music.
enum PickerPlaylist: Hashable, Identifiable {
    case appleMusic(Playlist)
    case tracks(TracksPlaylist)

    var id: String {
        switch self {
        case .appleMusic(let playlist): "am.\(playlist.id.rawValue)"
        case .tracks(let playlist): "tracks.\(playlist.id.uuidString)"
        }
    }

    var name: String {
        switch self {
        case .appleMusic(let playlist): playlist.name
        case .tracks(let playlist): playlist.name
        }
    }

    /// Sample data's Apple Music playlists have no songs to show.
    static func canBrowse(source: MusicSource, isDemo: Bool) -> Bool {
        source == .yourMusic || (!isDemo && MusicAuthorization.currentStatus == .authorized)
    }
}

/// Your playlists, the ones played most lately first.
private struct PickerPlaylists: View {
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @State private var playlists: [PickerPlaylist]?

    var body: some View {
        List {
            if let playlists {
                if playlists.isEmpty {
                    ContentUnavailableView("No Playlists", systemImage: "music.note.list", description: Text("Playlists you make or add show up here."))
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(playlists) { playlist in
                        NavigationLink(value: playlist) {
                            PickerPlaylistRow(playlist: playlist)
                        }
                    }
                }
            } else {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .listRowBackground(Color.clear)
            }
        }
        .navigationTitle("Playlists")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await load() }
    }

    private func load() async {
        guard playlists == nil else { return }
        if model.musicSource == .yourMusic {
            playlists = music.playlists.recent.map(PickerPlaylist.tracks)
            return
        }
        var request = MusicLibraryRequest<Playlist>()
        #if os(iOS)
        request.sort(by: \.lastPlayedDate, ascending: false)
        #endif
        let found = (try? await request.response().items).map(Array.init) ?? []
        playlists = found.map(PickerPlaylist.appleMusic)
    }
}

private struct PickerPlaylistRow: View {
    let playlist: PickerPlaylist

    var body: some View {
        HStack(spacing: 12) {
            switch playlist {
            case .appleMusic(let playlist):
                CoverImage(cover: playlist.artwork.map(CoverArt.artwork) ?? .url(nil, seed: playlist.name), size: 44)
            case .tracks(let playlist):
                CoverImage(cover: .url(nil, seed: playlist.name), size: 44)
            }
            Text(playlist.name)
                .lineLimit(1)
        }
    }
}

/// A playlist's songs, each to pick.
private struct PickerPlaylistSongs: View {
    let playlist: PickerPlaylist
    @Environment(YourMusic.self) private var music
    @Environment(PlayFeed.self) private var feed
    @State private var songs: [Handpick]?

    var body: some View {
        List {
            if let songs {
                if songs.isEmpty {
                    ContentUnavailableView("No Songs", systemImage: "music.note", description: Text("This playlist has no songs that can play here."))
                        .listRowBackground(Color.clear)
                } else {
                    ForEach(songs) { PickRow(pick: $0) }
                }
            } else {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .listRowBackground(Color.clear)
            }
        }
        .navigationTitle(playlist.name)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .pickedTray()
        .task { await load() }
    }

    private func load() async {
        guard songs == nil else { return }
        switch playlist {
        case .tracks(let playlist):
            var seen = Set<String>()
            songs = music.songs(in: playlist, facts: feed.facts)
                .filter { music.isPlayable($0) && seen.insert($0.identity).inserted }
                .map { Handpick($0, music: music) }
        case .appleMusic(let playlist):
            let tracks = try? await playlist.with([.tracks]).tracks
            var seen = Set<String>()
            songs = (tracks.map(Array.init) ?? []).compactMap { track -> Handpick? in
                guard case .song(let song) = track else { return nil }
                let pick = Handpick(song: song)
                return seen.insert(pick.identity).inserted ? pick : nil
            }
        }
    }
}

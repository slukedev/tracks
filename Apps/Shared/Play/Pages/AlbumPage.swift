import SwiftUI
import MusicKit
import TracksCore

/// An Apple Music album: its cover on a field of its colour, your history with it in a line
/// under the title, its songs, and what Apple Music says about it.
struct AlbumPage: View {
    let album: Album
    @Environment(PlayerModel.self) private var player
    @Environment(PlayFeed.self) private var feed
    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @Environment(\.openPlayRoute) private var openPlayRoute
    @State private var loaded: Album?
    /// The album `loaded` was loaded for: itself, or the clean edition it stands in for.
    @State private var loadedFor: MusicItemID?
    @State private var tracks: [Track] = []
    @State private var loadFailed = false
    /// True once the full track list has come back, even an empty one.
    @State private var hasLoaded = false
    @AppStorage(PlayPreferences.allowsExplicitKey) private var allowsExplicit = true
    @AppStorage(DownloadChoice.isOnKey) private var downloadIsOn = false
    /// Added from this page, so its circle turns to a check.
    @State private var isAdded = false

    var body: some View {
        let album = loaded ?? album
        let context = PlayContext(kind: .album, title: album.title)
        #if os(iOS)
        List {
            Section {
                header(album, context: context)
            }

            Section {
                if tracks.isEmpty, !loadFailed, !hasLoaded {
                    LoadingRows(count: min(max(album.trackCount, 4), 8), showsCover: false)
                        .listRowSeparator(.hidden)
                } else if hasLoaded, tracks.isEmpty {
                    CollectionMessage(text: emptyText(album), systemImage: "calendar")
                }
                ForEach(shownTracks, id: \.track.id) { index, track in
                    AvailabilityGate(title: track.title, artist: track.artistName, album: album.title) {
                        playTrack(at: index, context: context)
                    } label: {
                        TrackRow(
                            title: track.title,
                            subtitle: track.artistName == album.artistName ? nil : track.artistName,
                            number: track.trackNumber ?? index + 1,
                            isExplicit: track.contentRating == .explicit,
                            isCurrent: isCurrent(track),
                            artist: track.artistName
                        )
                    }
                    .trackMenu { songMenu(track) }
                    .swipeActions(edge: .trailing) {
                        if case .song(let song) = track { DownloadSwipeAction(subject: .song(song)) }
                    }
                    .collectionRowInsets(hasCover: false)
                }
            } footer: {
                // Plain lists inset a footer 16 points; the rows above sit on the 20 point margin.
                CollectionFooter(lines: footerLines(album))
                    .padding(.horizontal, Design.Margin.page - 16)
            }

            if loadFailed {
                Section {
                    CollectionMessage(text: String(localized: "Couldn't load the songs on this album. Check your connection and try again.")) {
                        Button("Try Again") { Task { await load() } }
                            .buttonStyle(.glass)
                    }
                }
            }
        }
        // The songs start where the header's colour has faded, not a section's gap below it.
        .listSectionSpacing(0)
        .collectionPage(title: CollectionKind.of(album).title)
        .toolbar {
            CollectionToolbar(share: album.url) { moreItems(album) }
        }
        .task(id: album.id) { await loadOnce() }
        #else
        CollectionMacLayout {
            header(album, context: context)
        } content: {
            macTracks(album, context: context)
        }
        .navigationTitle(CollectionKind.of(album).title)
        .task(id: album.id) { await loadOnce() }
        .task(id: "\(tracks.count).\(music.availabilityGeneration).\(model.musicSource == .yourMusic)") {
            await checkAvailability(album)
        }
        #endif
    }

    private func header(_ album: Album, context: PlayContext) -> some View {
        let (kind, title) = CollectionKind.of(album)
        return CollectionHeader(
            kind: kind,
            title: title,
            subtitle: album.artistName,
            onSubtitle: album.artists?.first.map { artist in { openPlayRoute(.artist(artist)) } },
            facts: factsLine(album, kind: kind),
            tintCover: cover(album),
            canPlay: !(hasLoaded && tracks.isEmpty),
            download: .album(album),
            secondary: addToLibrary(album),
            play: { playAlbum(album, context: context, shuffled: false) },
            shuffle: { playAlbum(album, context: context, shuffled: true) }
        ) {
            moreItems(album)
        } cover: { side in
            CoverImage(cover: cover(album), size: side)
        }
    }

    /// Add to Library beside Play, a check once it's added. An album already in your library
    /// has nothing to add, so More takes its place.
    private func addToLibrary(_ album: Album) -> CollectionSecondary? {
        guard !Self.isInLibrary(album) else { return nil }
        return CollectionSecondary(isAdded ? "Added to Library" : "Add to Library", systemImage: isAdded ? "checkmark" : "plus", isOn: isAdded) {
            guard !isAdded else { return }
            isAdded = true
            player.addToLibrary(album) { isAdded = false }
        }
    }

    /// Library items carry library identifiers; a catalog album doesn't say whether you have it.
    private static func isInLibrary(_ album: Album) -> Bool {
        album.id.rawValue.hasPrefix("l.")
    }

    private func cover(_ album: Album) -> CoverArt {
        album.artwork.map(CoverArt.artwork) ?? .url(nil, seed: album.title)
    }

    @ViewBuilder
    private func moreItems(_ album: Album) -> some View {
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
            player.enqueue(.album(album), next: true, title: album.title)
        }
        Button("Play Last", systemImage: "text.line.last.and.arrowtriangle.forward") {
            player.enqueue(.album(album), next: false, title: album.title)
        }
        Divider()
        if downloadIsOn {
            DownloadMenuItems(subject: .album(album))
        } else {
            if !Self.isInLibrary(album), !isAdded {
                Button("Add to Library", systemImage: "plus") {
                    isAdded = true
                    player.addToLibrary(album)
                }
            }
            GetWithLidarrButton(album: album.title, artist: album.artistName)
        }
        if let artist = album.artists?.first {
            Button("Go to Artist", systemImage: "music.microphone") { openPlayRoute(.artist(artist)) }
        }
        #if os(macOS)
        // On iPhone, Share is beside More in the toolbar.
        if let url = album.url {
            Divider()
            ShareLink(item: url) { Label("Share Album", systemImage: "square.and.arrow.up") }
        }
        #endif
    }

    @ViewBuilder
    private func songMenu(_ track: Track) -> some View {
        if case .song(let song) = track {
            if model.musicSource == .yourMusic {
                YourMusicSongMenu(song: song)
            } else {
                SongMenu(song: song, showsAlbum: false)
            }
        }
    }

    // MARK: The Mac's table

    #if os(macOS)
    @ViewBuilder
    private func macTracks(_ album: Album, context: PlayContext) -> some View {
        if tracks.isEmpty, !loadFailed, !hasLoaded {
            LoadingRows(count: min(max(album.trackCount, 4), 8), showsCover: false)
                .padding(.horizontal, Design.Margin.page)
                .padding(.top, 12)
        } else if loadFailed {
            CollectionMessage(text: String(localized: "Couldn't load the songs on this album. Check your connection and try again.")) {
                Button("Try Again") { Task { await load() } }
            }
        } else if hasLoaded, tracks.isEmpty {
            CollectionMessage(text: emptyText(album), systemImage: "calendar")
        } else {
            VStack(spacing: 0) {
                CollectionTable(
                    tracks: macRows(album),
                    style: .album,
                    play: { id in Int(id).map { playGated(at: $0, album: album, context: context) } },
                    enqueue: { ids, next in enqueue(ids.compactMap(Int.init), next: next, album: album) }
                ) { row in
                    if let index = Int(row.id), tracks.indices.contains(index) {
                        songMenu(tracks[index])
                    }
                }
                let notes = footerLines(album)
                if !notes.isEmpty {
                    VStack(alignment: .leading, spacing: 3) {
                        ForEach(notes, id: \.self) { Text($0) }
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Design.Margin.page)
                    .padding(.top, 16)
                }
            }
        }
    }

    private func macRows(_ album: Album) -> [CollectionTrack] {
        let isYourMusic = model.musicSource == .yourMusic
        return shownTracks.map { index, track in
            let availability = isYourMusic ? music.availability(title: track.title, artist: track.artistName) : nil
            return CollectionTrack(
                id: String(index),
                position: index + 1,
                number: track.trackNumber ?? index + 1,
                title: track.title,
                artist: track.artistName,
                album: album.title,
                plays: plays(of: track) ?? 0,
                duration: track.duration ?? 0,
                isExplicit: track.contentRating == .explicit,
                songIdentity: SongIdentity.key(title: track.title, artistName: track.artistName),
                isPlayable: availability.map { $0 == .checking || $0.track != nil } ?? true,
                status: availability.flatMap(Self.status),
                drag: SongDrag.Song(appleMusicID: track.id.rawValue, title: track.title, artistName: track.artistName, albumTitle: album.title)
            )
        }
    }

    /// What keeps a song from playing in Your Music, short enough for the end of its title.
    private static func status(_ availability: SongAvailability) -> String? {
        switch availability {
        case .checking, .playable: nil
        case .inLidarr: String(localized: "In Lidarr")
        case .comingFromLidarr: String(localized: "Coming from Lidarr")
        case .unavailable: String(localized: "Not Available")
        }
    }

    /// With your own music, each song is looked for there, so the table can say which play.
    private func checkAvailability(_ album: Album) async {
        guard model.musicSource == .yourMusic else { return }
        for track in tracks {
            guard !Task.isCancelled else { return }
            await music.check(title: track.title, artist: track.artistName, album: album.title)
        }
    }

    /// Plays from a song, or with your own music, says why it can't, as the iPhone's rows do.
    private func playGated(at index: Int, album: Album, context: PlayContext) {
        guard tracks.indices.contains(index) else { return }
        guard model.musicSource == .yourMusic else {
            playTrack(at: index, context: context)
            return
        }
        let track = tracks[index]
        Task {
            await music.check(title: track.title, artist: track.artistName, album: album.title)
            let found = music.availability(title: track.title, artist: track.artistName)
            if found.track != nil {
                playTrack(at: index, context: context)
            } else if let message = found.message {
                player.confirm(message)
            }
        }
    }

    private func enqueue(_ indices: [Int], next: Bool, album: Album) {
        let chosen = indices.filter(tracks.indices.contains).map { tracks[$0] }
        if model.musicSource == .yourMusic {
            let local = chosen.compactMap { music.availability(title: $0.title, artist: $0.artistName).track }
            guard !local.isEmpty else { return }
            player.enqueue(.local(local), next: next, title: album.title)
        } else {
            let songs = chosen.compactMap { track -> Song? in
                if case .song(let song) = track { return song }
                return nil
            }
            guard !songs.isEmpty else { return }
            player.enqueue(.songs(songs), next: next, title: album.title)
        }
    }
    #endif

    // MARK: Facts

    /// Every track with its place in the full list, less the explicit ones when they're off.
    /// Played by their place, so the queue lines up with the list either way.
    private var shownTracks: [(index: Int, track: Track)] {
        tracks.enumerated()
            .filter { allowsExplicit || $0.element.contentRating != .explicit }
            .map { (index: $0.offset, track: $0.element) }
    }

    private var history: CollectionHistory? {
        guard hasLoaded || !tracks.isEmpty else { return nil }
        return CollectionHistory.summary(
            songIdentities: tracks.map { SongIdentity.key(title: $0.title, artistName: $0.artistName) },
            facts: feed.facts
        )
    }

    #if os(macOS)
    /// Your plays, for the Mac table's Plays column.
    private func plays(of track: Track) -> Int? {
        feed.facts[SongIdentity.key(title: track.title, artistName: track.artistName)]?.plays
    }
    #endif

    /// "2 explicit songs hidden", when the setting hides some.
    private var hiddenNote: String? {
        let hidden = tracks.count - shownTracks.count
        guard hidden > 0 else { return nil }
        return String(AttributedString(localized: "^[\(hidden) explicit song](inflect: true) hidden. Turn on Explicit Songs in Settings to see them.").characters)
    }

    /// "EP · Alternative · 2024 · 12 songs, 48 min". An album still to come says when instead
    /// of its year. The Mac names the kind over the title instead.
    private func factsLine(_ album: Album, kind: CollectionKind) -> Text? {
        #if os(iOS)
        let kindName: String? = kind == .album ? nil : kind.eyebrow
        #else
        let kindName: String? = nil
        #endif
        let when = CollectionFacts.release(album.releaseDate)
            ?? album.releaseDate.map { Format.year(Calendar.current.component(.year, from: $0)) }
        return CollectionFacts.line([
            kindName,
            album.genreNames.first { $0 != "Music" },
            when,
            CollectionFacts.length(count: tracks.count, seconds: tracks.compactMap(\.duration).reduce(0, +)),
        ])
    }

    private func emptyText(_ album: Album) -> String {
        (album.releaseDate ?? .distantPast) > .now
            ? String(localized: "Not out yet. Its songs appear here as they're released.")
            : String(localized: "Apple Music has no songs on this album to play.")
    }

    /// "September 23, 2025", "12 songs, 48 minutes", the copyright, and any songs hidden.
    private func footerLines(_ album: Album) -> [String] {
        guard !tracks.isEmpty else { return [hiddenNote].compactMap(\.self) }
        var lines: [String] = []
        if let date = album.releaseDate {
            lines.append(date.formatted(date: .long, time: .omitted))
        }
        lines.append(TrackListFooter.summary(count: tracks.count, seconds: tracks.compactMap(\.duration).reduce(0, +)))
        if let label = album.recordLabelName, !(album.copyright ?? "").localizedCaseInsensitiveContains(label) {
            lines.append(label)
        }
        if let copyright = album.copyright { lines.append(copyright) }
        if let hiddenNote { lines.append(hiddenNote) }
        return lines
    }

    // MARK: Playing

    /// Once per album: coming back to the page keeps what loaded rather than asking again.
    private func loadOnce() async {
        guard loadedFor != album.id else { return }
        await load()
    }

    private func load() async {
        #if DEBUG
        openSampleArtist()
        #endif
        loadFailed = false
        // Whatever came with the album shows straight away; the full list replaces it.
        if tracks.isEmpty, let known = album.tracks { tracks = Array(known) }
        do {
            var detailed = try await album.with([.tracks, .artists])
            // With explicit songs on, a clean edition opens as its explicit one.
            if allowsExplicit, let explicit = await ExplicitTwins.explicitEdition(of: detailed),
               let full = try? await explicit.with([.tracks, .artists]) {
                detailed = full
            }
            loaded = detailed
            loadedFor = album.id
            tracks = Array(detailed.tracks ?? [])
            hasLoaded = true
        } catch {
            guard !Task.isCancelled else { return }
            loadFailed = tracks.isEmpty
        }
    }

    #if DEBUG
    @MainActor private static var hasOpenedSampleArtist = false

    /// `-TracksSampleArtist YES`: from the sample album, its artist's page, for screenshots of
    /// About with sample data. Once per launch.
    private func openSampleArtist() {
        guard player.isDemo, AboutSamples.opensArtist, !Self.hasOpenedSampleArtist else { return }
        Self.hasOpenedSampleArtist = true
        openPlayRoute(.artist(AboutSamples.artist()))
    }
    #endif

    /// The album, or with your own music, the songs of it you have.
    private func playAlbum(_ album: Album, context: PlayContext, shuffled: Bool) {
        if model.musicSource == .yourMusic {
            let (songs, _) = PlaylistPage.songs(in: tracks)
            player.play(.songs(songs), from: context, shuffled: shuffled)
        } else {
            player.play(.album(album), from: context, shuffled: shuffled)
        }
    }

    private func playTrack(at index: Int, context: PlayContext) {
        let (songs, positions) = PlaylistPage.songs(in: tracks)
        guard let start = positions[index] else { return }
        player.play(.songs(songs, startingAt: start), from: context)
    }

    private func isCurrent(_ track: Track) -> Bool {
        guard let current = player.current else { return false }
        return current.songIdentity == SongIdentity.key(title: track.title, artistName: track.artistName)
    }
}

enum TrackListFooter {
    /// "12 songs, 48 minutes".
    static func summary(count: Int, seconds: TimeInterval) -> String {
        let songs = String(AttributedString(localized: "^[\(count) song](inflect: true)").characters)
        guard seconds > 0 else { return songs }
        let length = Duration.seconds(seconds).formatted(.units(allowed: [.hours, .minutes], width: .wide))
        return "\(songs), \(length)"
    }
}

#if SPOTIFY
import SwiftUI
import TracksCore
import TracksEngine

// MARK: - Attribution

/// Spotify's icon as its guidelines allow it: in Spotify green, or black or white on a page
/// coloured from a cover. Read as "Spotify".
struct SpotifyMark: View {
    var size: CGFloat = 16
    @Environment(\.pageTone) private var tone

    var body: some View {
        Group {
            if let tone {
                Image("SpotifyIconMono")
                    .renderingMode(.template)
                    .resizable()
                    .foregroundStyle(tone.isDark ? Color.white : Color.black)
            } else {
                Image("SpotifyIcon")
                    .resizable()
            }
        }
        .scaledToFit()
        .frame(width: size, height: size)
        .accessibilityLabel("Spotify")
    }
}

/// A Spotify cover, never cropped, its corners rounded no more than Spotify allows.
struct SpotifyCover: View {
    let url: String?
    let seed: String
    var size: CGFloat?
    var isCircle = false

    var body: some View {
        ArtworkView(url: url, seed: seed, size: size, isCircle: isCircle, maximumCornerRadius: (size ?? 100) < 80 ? 4 : 8)
    }
}

/// Open in Spotify, for an album's, playlist's, artist's or song's menu.
struct OpenInSpotifyButton: View {
    let uri: String
    @Environment(\.openURL) private var openURL

    var body: some View {
        if let url = SpotifyLink.url(for: uri) {
            Button("Open in Spotify", systemImage: "arrow.up.forward.app") { openURL(url) }
        }
    }
}

// MARK: - Places

/// Spotify's pages, pushed onto whichever stack shows Spotify.
enum SpotifyRoute: Hashable {
    case album(String)
    case playlist(String)
    case artist(String)
    case albums
    case playlists
    case likedSongs
}

extension View {
    /// The pages a stack showing Spotify can push.
    func spotifyDestinations() -> some View {
        navigationDestination(for: SpotifyRoute.self) { route in
            Group {
                switch route {
                case .album(let uri): SpotifyAlbumPage(uri: uri)
                case .playlist(let uri): SpotifyPlaylistPage(uri: uri)
                case .artist(let uri): SpotifyArtistPage(uri: uri)
                case .albums: SpotifyAlbumsPage()
                case .playlists: SpotifyPlaylistsPage()
                case .likedSongs: SpotifyLikedSongsPage()
                }
            }
            .pageChrome()
        }
    }
}

// MARK: - Rows and tiles

/// A Spotify song: its cover or number, title, artist, and Spotify's icon.
struct SpotifyTrackRow: View {
    let track: SpotifyTrack
    var number: Int?
    @Environment(PlayerModel.self) private var player
    @Environment(\.pageTone) private var tone

    var body: some View {
        let isCurrent = player.current?.title == track.title && player.current?.artistName == track.artistLine
        HStack(spacing: Design.Spacing.medium) {
            if let number {
                Text(number, format: .number)
                    .font(.subheadline)
                    .monospacedDigit()
                    .foregroundStyle(PageInkColors(tone: tone).secondary)
                    .frame(minWidth: 24, alignment: .trailing)
            } else {
                SpotifyCover(url: track.artworkUrl, seed: track.album?.title ?? track.title, size: 44)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Design.Spacing.xSmall) {
                    Text(track.title)
                        .foregroundStyle(isCurrent ? PageInkColors(tone: tone).accent : PageInkColors(tone: tone).primary)
                        .lineLimit(1)
                    if track.explicit {
                        Image(systemName: "e.square.fill")
                            .font(.caption)
                            .foregroundStyle(PageInkColors(tone: tone).tertiary)
                            .accessibilityLabel("Explicit")
                    }
                }
                Text(track.artistLine)
                    .font(.subheadline)
                    .foregroundStyle(PageInkColors(tone: tone).secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: Design.Spacing.small)
            SpotifyMark(size: 16)
        }
        .frame(minHeight: 44)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}

/// A row of songs to play from: tapping one plays the list from it.
struct SpotifyTrackList: View {
    let tracks: [SpotifyTrack]
    let context: PlayContext
    var numbered = false
    @Environment(PlayerModel.self) private var player
    @Environment(AppModel.self) private var model

    var body: some View {
        ForEach(Array(tracks.enumerated()), id: \.offset) { position, track in
            Button {
                model.spotifyService?.remember(tracks)
                player.play(.history(tracks.map(HistorySong.init), startingAt: position), from: context)
            } label: {
                SpotifyTrackRow(track: track, number: numbered ? Int(track.trackNumber) : nil)
            }
            .buttonStyle(.plain)
            .contextMenu { SpotifySongMenu(track: track) }
            #if os(iOS)
            .collectionRowInsets(hasCover: !numbered)
            #endif
        }
    }
}

/// What a song's menu offers: Up Next, and Spotify itself.
struct SpotifySongMenu: View {
    let track: SpotifyTrack
    @Environment(PlayerModel.self) private var player
    @Environment(AppModel.self) private var model

    var body: some View {
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
            model.spotifyService?.remember([track])
            player.enqueue(.history([HistorySong(track)]), next: true, title: track.title)
        }
        Button("Play Last", systemImage: "text.line.last.and.arrowtriangle.forward") {
            model.spotifyService?.remember([track])
            player.enqueue(.history([HistorySong(track)]), next: false, title: track.title)
        }
        Divider()
        OpenInSpotifyButton(uri: track.uri)
    }
}

/// An album or playlist on a shelf or in a grid, with Spotify's icon by its line.
struct SpotifyTile: View {
    let title: String
    let subtitle: String
    let artwork: String?
    var side: CGFloat?

    var body: some View {
        VStack(alignment: .leading, spacing: Design.Shelf.captionGap) {
            SpotifyCover(url: artwork, seed: title, size: side)
                .aspectRatio(1, contentMode: .fit)
            VStack(alignment: .leading, spacing: Design.Shelf.lineGap) {
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                HStack(spacing: Design.Spacing.xSmall) {
                    SpotifyMark(size: 12)
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .frame(width: side, alignment: .leading)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}

/// A tile at the shelf's size, which grows with the text size up to a point.
private struct SpotifyShelfTile: View {
    let title: String
    let subtitle: String
    let artwork: String?
    @ScaledMetric(relativeTo: .subheadline) private var width = Design.Shelf.tile

    var body: some View {
        SpotifyTile(title: title, subtitle: subtitle, artwork: artwork, side: min(width, Design.Shelf.maximumTile))
    }
}

/// What's still loading, or couldn't, with the way to try again.
struct SpotifyLoadFailure: View {
    let retry: () -> Void

    var body: some View {
        CollectionMessage(text: SpotifyProblem.network.message, systemImage: "wifi.exclamationmark") {
            Button("Try Again", action: retry)
        }
        .padding(.vertical, Design.Margin.section)
    }
}

// MARK: - Play

/// Play, when Spotify is the source: what you've played from it lately, your playlists and
/// your albums. No Spotify browsing beyond your own library, by design.
struct SpotifyPlayScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @State private var playlists: [SpotifyPlaylist]?
    @State private var albums: [SpotifyAlbum]?
    @State private var failed = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Design.Margin.section) {
                if let service = model.spotifyService, service.isConnected {
                    content
                } else {
                    signedOut
                }
            }
            #if os(macOS)
            .padding(.top, Design.Spacing.xLarge)
            #else
            .padding(.top, Design.Spacing.xSmall)
            #endif
            .padding(.bottom, Design.Margin.section)
        }
        #if os(iOS)
        .tabRoot("Play")
        #else
        .headerUnderBar()
        #endif
        .spotifyDestinations()
        .task(id: model.spotifyService?.isConnected) { await load(refresh: false) }
        .refreshable { await load(refresh: true) }
    }

    @ViewBuilder
    private var content: some View {
        let recent = recentlyPlayed
        if !recent.isEmpty {
            Shelf(title: String(localized: "Recently Played"), items: recent) { song in
                Button {
                    let index = recent.firstIndex { $0.id == song.id } ?? 0
                    player.play(.history(recent.map(\.value), startingAt: index), from: .songs(String(localized: "Recently Played")))
                } label: {
                    SpotifyShelfTile(title: song.value.title, subtitle: song.value.artistName, artwork: song.value.artworkURL)
                }
                .buttonStyle(.pressable)
            }
        }
        if let playlists, !playlists.isEmpty {
            Shelf(title: String(localized: "Playlists"), items: playlists.prefix(20).map { Identified($0, id: $0.uri) }) { item in
                NavigationLink(value: SpotifyRoute.playlist(item.value.uri)) {
                    SpotifyShelfTile(title: item.value.name, subtitle: item.value.owner ?? "", artwork: item.value.artworkUrl)
                }
                .buttonStyle(.pressable)
            }
        }
        if let albums, !albums.isEmpty {
            Shelf(title: String(localized: "Albums"), items: albums.prefix(20).map { Identified($0, id: $0.uri) }) { item in
                NavigationLink(value: SpotifyRoute.album(item.value.uri)) {
                    SpotifyShelfTile(title: item.value.title, subtitle: item.value.artistLine, artwork: item.value.artworkUrl)
                }
                .buttonStyle(.pressable)
            }
        }
        if failed, playlists == nil, albums == nil {
            SpotifyLoadFailure { Task { await load(refresh: true) } }
        } else if playlists == nil || albums == nil {
            LoadingRows(count: 4)
                .padding(.horizontal, Design.Margin.page)
        } else if recent.isEmpty, playlists?.isEmpty == true, albums?.isEmpty == true {
            CollectionMessage(text: String(localized: "Nothing from Spotify yet"), systemImage: nil) { EmptyView() }
        }
    }

    private var signedOut: some View {
        CollectionMessage(text: String(localized: "Spotify is signed out"), systemImage: nil) {
            if let service = model.spotifyService {
                Button {
                    service.signIn()
                } label: {
                    if service.phase == .signingIn {
                        Text("Signing In…")
                    } else {
                        Text("Sign In with Spotify")
                    }
                }
                .disabled(service.phase == .signingIn || service.phase == .checking)
            }
        }
        .padding(.vertical, Design.Margin.section)
    }

    /// Your latest Spotify plays from Tracks’ own history, a song once each.
    private var recentlyPlayed: [Identified<HistorySong>] {
        var seen = Set<String>()
        var songs: [Identified<HistorySong>] = []
        for capture in model.library.history.captures.reversed() where capture.source == .spotify {
            guard seen.insert(capture.songIdentity).inserted else { continue }
            songs.append(Identified(HistorySong(songID: "", title: capture.title, artistName: capture.artistName, albumTitle: capture.albumTitle, artworkURL: capture.artworkURL), id: capture.songIdentity))
            if songs.count == 20 { break }
        }
        return songs
    }

    private func load(refresh: Bool) async {
        guard let service = model.spotifyService, service.isConnected else { return }
        failed = false
        do {
            async let lists = service.playlists(refresh: refresh)
            async let saved = service.savedAlbums(refresh: refresh)
            let (foundLists, foundAlbums) = try await (lists, saved)
            playlists = foundLists
            albums = foundAlbums
        } catch {
            failed = true
        }
    }
}

/// Something without an identity of its own, for a `ForEach`.
private struct Identified<Value>: Identifiable {
    let value: Value
    let id: String

    init(_ value: Value, id: String) {
        self.value = value
        self.id = id
    }
}

// MARK: - Library

/// Library, when Spotify is the source: your playlists, albums and liked songs, then the
/// albums you saved lately.
struct SpotifyLibraryList: View {
    @Environment(AppModel.self) private var model
    @State private var albums: [SpotifyAlbum]?

    private static let recentCount = 6
    private static let textInset: CGFloat = 28 + Design.Spacing.medium

    var body: some View {
        VStack(alignment: .leading, spacing: Design.Margin.section) {
            VStack(spacing: 0) {
                row("Playlists", symbol: "music.note.list", route: .playlists)
                Divider().padding(.leading, Self.textInset)
                row("Albums", symbol: "square.stack", route: .albums)
                Divider().padding(.leading, Self.textInset)
                row("Liked Songs", symbol: "heart", route: .likedSongs)
            }
            if let albums, !albums.isEmpty {
                VStack(alignment: .leading, spacing: Design.Margin.header) {
                    Text("Recently Added")
                        .font(.title3.bold())
                        .accessibilityAddTraits(.isHeader)
                    LibraryGrid(margin: 0) {
                        ForEach(albums.prefix(Self.recentCount), id: \.uri) { album in
                            NavigationLink(value: SpotifyRoute.album(album.uri)) {
                                SpotifyTile(title: album.title, subtitle: album.artistLine, artwork: album.artworkUrl)
                            }
                            .buttonStyle(.pressable)
                        }
                    }
                }
            }
        }
        .spotifyDestinations()
        .task { albums = try? await model.spotifyService?.savedAlbums() }
    }

    private func row(_ title: LocalizedStringKey, symbol: String, route: SpotifyRoute) -> some View {
        NavigationLink(value: route) {
            HStack(spacing: Design.Spacing.medium) {
                Image(systemName: symbol)
                    .font(.title3)
                    .foregroundStyle(.primary)
                    .frame(width: 28)
                Text(title)
                    .foregroundStyle(.primary)
                Spacer()
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .frame(minHeight: 48)
            .contentShape(.rect)
        }
        .buttonStyle(.pressable)
    }
}

/// Every album you've saved on Spotify, newest first.
struct SpotifyAlbumsPage: View {
    @Environment(AppModel.self) private var model
    @State private var albums: [SpotifyAlbum]?
    @State private var failed = false

    var body: some View {
        ScrollView {
            if let albums {
                if albums.isEmpty {
                    CollectionMessage(text: String(localized: "No Albums"), systemImage: "square.stack") { EmptyView() }
                        .padding(.vertical, Design.Margin.section)
                } else {
                    LibraryGrid {
                        ForEach(albums, id: \.uri) { album in
                            NavigationLink(value: SpotifyRoute.album(album.uri)) {
                                SpotifyTile(title: album.title, subtitle: album.artistLine, artwork: album.artworkUrl)
                            }
                            .buttonStyle(.pressable)
                        }
                    }
                    .padding(.vertical, Design.Spacing.large)
                }
            } else if failed {
                SpotifyLoadFailure { Task { await load(refresh: true) } }
            } else {
                LibraryGridPlaceholder()
                    .padding(.vertical, Design.Spacing.large)
            }
        }
        .navigationTitle("Albums")
        .task { await load(refresh: false) }
        .refreshable { await load(refresh: true) }
    }

    private func load(refresh: Bool) async {
        failed = false
        do {
            albums = try await model.spotifyService?.savedAlbums(refresh: refresh) ?? []
        } catch {
            failed = true
        }
    }
}

/// Your Spotify playlists, the ones you made and the ones you follow.
struct SpotifyPlaylistsPage: View {
    @Environment(AppModel.self) private var model
    @State private var playlists: [SpotifyPlaylist]?
    @State private var failed = false

    var body: some View {
        ScrollView {
            if let playlists {
                if playlists.isEmpty {
                    CollectionMessage(text: String(localized: "No Playlists"), systemImage: "music.note.list") { EmptyView() }
                        .padding(.vertical, Design.Margin.section)
                } else {
                    LibraryGrid {
                        ForEach(playlists, id: \.uri) { playlist in
                            NavigationLink(value: SpotifyRoute.playlist(playlist.uri)) {
                                SpotifyTile(title: playlist.name, subtitle: playlist.owner ?? "", artwork: playlist.artworkUrl)
                            }
                            .buttonStyle(.pressable)
                        }
                    }
                    .padding(.vertical, Design.Spacing.large)
                }
            } else if failed {
                SpotifyLoadFailure { Task { await load(refresh: true) } }
            } else {
                LibraryGridPlaceholder()
                    .padding(.vertical, Design.Spacing.large)
            }
        }
        .navigationTitle("Playlists")
        .task { await load(refresh: false) }
        .refreshable { await load(refresh: true) }
    }

    private func load(refresh: Bool) async {
        failed = false
        do {
            playlists = try await model.spotifyService?.playlists(refresh: refresh) ?? []
        } catch {
            failed = true
        }
    }
}

// MARK: - Collections

/// The songs of an album, a playlist or your liked songs, under a header that plays them.
private struct SpotifyCollection: View {
    let kind: CollectionKind
    let title: String
    let subtitle: String?
    var onSubtitle: (() -> Void)?
    let facts: Text?
    let artwork: String?
    let uri: String?
    let tracks: [SpotifyTrack]
    let numbered: Bool
    @Environment(PlayerModel.self) private var player
    @Environment(AppModel.self) private var model

    var body: some View {
        let context = PlayContext(kind: kind == .playlist ? .playlist : .album, title: title)
        let cover = CoverArt.url(artwork, seed: title)
        #if os(iOS)
        List {
            Section { header(context: context, cover: cover) }
            Section {
                SpotifyTrackList(tracks: tracks, context: context, numbered: numbered)
            }
        }
        .collectionPage(title: title, cover: cover)
        .toolbar {
            CollectionToolbar(share: uri.flatMap(SpotifyLink.url(for:))) { moreItems }
        }
        #else
        CollectionMacLayout {
            header(context: context, cover: cover)
        } content: {
            LazyVStack(spacing: 0) {
                SpotifyTrackList(tracks: tracks, context: context, numbered: numbered)
                    .padding(.horizontal, Design.Margin.page)
                    .padding(.vertical, Design.Spacing.xSmall)
            }
            .padding(.top, Design.Spacing.medium)
        }
        #endif
    }

    private func header(context: PlayContext, cover: CoverArt) -> some View {
        CollectionHeader(
            kind: kind,
            title: title,
            subtitle: subtitle,
            onSubtitle: onSubtitle,
            facts: facts,
            tintCover: cover,
            canPlay: !tracks.isEmpty,
            play: { play(context: context, shuffled: false) },
            shuffle: { play(context: context, shuffled: true) }
        ) {
            moreItems
        } cover: { side in
            SpotifyCover(url: artwork, seed: title, size: side)
        }
    }

    @ViewBuilder
    private var moreItems: some View {
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
            model.spotifyService?.remember(tracks)
            player.enqueue(.history(tracks.map(HistorySong.init)), next: true, title: title)
        }
        .disabled(tracks.isEmpty)
        Button("Play Last", systemImage: "text.line.last.and.arrowtriangle.forward") {
            model.spotifyService?.remember(tracks)
            player.enqueue(.history(tracks.map(HistorySong.init)), next: false, title: title)
        }
        .disabled(tracks.isEmpty)
        if let uri {
            Divider()
            OpenInSpotifyButton(uri: uri)
        }
    }

    private func play(context: PlayContext, shuffled: Bool) {
        model.spotifyService?.remember(tracks)
        player.play(.history(tracks.map(HistorySong.init)), from: context, shuffled: shuffled)
    }
}

/// The header with nothing in it yet, for a collection still loading.
private struct SpotifyCollectionLoading: View {
    var failed = false
    let retry: () -> Void

    var body: some View {
        if failed {
            SpotifyLoadFailure(retry: retry)
        } else {
            let header = CollectionHeader(kind: .album, title: "", tintCover: nil, isLoading: true, play: {}, shuffle: {}) {
                EmptyView()
            } cover: { _ in
                EmptyView()
            }
            #if os(iOS)
            List {
                Section { header }
                LoadingRows(count: 8, showsCover: false)
                    .listRowSeparator(.hidden)
            }
            .collectionPage(title: "")
            #else
            CollectionMacLayout {
                header
            } content: {
                LoadingRows(count: 8, showsCover: false)
                    .padding(.horizontal, Design.Margin.page)
                    .padding(.top, 12)
            }
            #endif
        }
    }
}

struct SpotifyAlbumPage: View {
    let uri: String
    @Environment(AppModel.self) private var model
    @State private var loaded: (album: SpotifyAlbum, tracks: [SpotifyTrack])?
    @State private var failed = false

    var body: some View {
        Group {
            if let loaded {
                let album = loaded.album
                SpotifyCollection(
                    kind: loaded.tracks.count < 4 ? .single : .album,
                    title: album.title,
                    subtitle: album.artistLine,
                    onSubtitle: nil,
                    facts: CollectionFacts.line([
                        album.releaseYear.map { Format.year(Int($0)) },
                        CollectionFacts.length(count: loaded.tracks.count, seconds: loaded.tracks.reduce(0) { $0 + $1.duration }),
                    ]),
                    artwork: album.artworkUrl,
                    uri: album.uri,
                    tracks: loaded.tracks,
                    numbered: true
                )
            } else {
                SpotifyCollectionLoading(failed: failed) { Task { await load() } }
            }
        }
        .task(id: uri) { await load() }
    }

    private func load() async {
        failed = false
        do {
            guard let service = model.spotifyService else { return }
            let (album, tracks) = try await service.album(uri)
            loaded = (album, tracks)
        } catch {
            failed = true
        }
    }
}

struct SpotifyPlaylistPage: View {
    let uri: String
    @Environment(AppModel.self) private var model
    @State private var loaded: (playlist: SpotifyPlaylist, tracks: [SpotifyTrack])?
    @State private var failed = false

    var body: some View {
        Group {
            if let loaded {
                let playlist = loaded.playlist
                SpotifyCollection(
                    kind: .playlist,
                    title: playlist.name,
                    subtitle: playlist.owner,
                    facts: CollectionFacts.line([
                        CollectionFacts.length(count: loaded.tracks.count, seconds: loaded.tracks.reduce(0) { $0 + $1.duration }),
                    ]),
                    artwork: playlist.artworkUrl,
                    uri: playlist.uri,
                    tracks: loaded.tracks,
                    numbered: false
                )
            } else {
                SpotifyCollectionLoading(failed: failed) { Task { await load() } }
            }
        }
        .task(id: uri) { await load() }
    }

    private func load() async {
        failed = false
        do {
            guard let service = model.spotifyService else { return }
            let (playlist, tracks) = try await service.playlist(uri)
            loaded = (playlist, tracks)
        } catch {
            failed = true
        }
    }
}

struct SpotifyLikedSongsPage: View {
    @Environment(AppModel.self) private var model
    @State private var tracks: [SpotifyTrack]?
    @State private var failed = false

    var body: some View {
        Group {
            if let tracks {
                SpotifyCollection(
                    kind: .playlist,
                    title: String(localized: "Liked Songs"),
                    subtitle: nil,
                    facts: CollectionFacts.line([
                        CollectionFacts.length(count: tracks.count, seconds: tracks.reduce(0) { $0 + $1.duration }),
                    ]),
                    artwork: nil,
                    uri: nil,
                    tracks: tracks,
                    numbered: false
                )
            } else {
                SpotifyCollectionLoading(failed: failed) { Task { await load(refresh: true) } }
            }
        }
        .task { await load(refresh: false) }
    }

    private func load(refresh: Bool) async {
        failed = false
        do {
            tracks = try await model.spotifyService?.likedSongs(refresh: refresh) ?? []
        } catch {
            failed = true
        }
    }
}

/// An artist on Spotify: their top songs and albums.
struct SpotifyArtistPage: View {
    let uri: String
    @Environment(AppModel.self) private var model
    @Environment(PlayerModel.self) private var player
    @State private var loaded: (artist: SpotifyArtist, top: [SpotifyTrack], albums: [SpotifyAlbum])?
    @State private var failed = false

    var body: some View {
        ScrollView {
            if let loaded {
                let context = PlayContext(kind: .artist, title: loaded.artist.name)
                VStack(alignment: .leading, spacing: Design.Margin.section) {
                    VStack(spacing: Design.Spacing.medium) {
                        SpotifyCover(url: loaded.artist.artworkUrl, seed: loaded.artist.name, size: 160, isCircle: true)
                        Text(loaded.artist.name)
                            .font(.title.bold())
                            .multilineTextAlignment(.center)
                            .accessibilityAddTraits(.isHeader)
                        HStack(spacing: Design.Spacing.medium) {
                            Button("Play", systemImage: "play.fill") {
                                model.spotifyService?.remember(loaded.top)
                                player.play(.history(loaded.top.map(HistorySong.init)), from: context)
                            }
                            Button("Shuffle", systemImage: "shuffle") {
                                model.spotifyService?.remember(loaded.top)
                                player.play(.history(loaded.top.map(HistorySong.init)), from: context, shuffled: true)
                            }
                        }
                        .buttonStyle(.glass)
                        .controlSize(.large)
                        .disabled(loaded.top.isEmpty)
                    }
                    .frame(maxWidth: .infinity)
                    if !loaded.top.isEmpty {
                        VStack(alignment: .leading, spacing: Design.Spacing.xSmall) {
                            Text("Top Songs")
                                .font(.title3.bold())
                                .accessibilityAddTraits(.isHeader)
                            SpotifyTrackList(tracks: loaded.top, context: context)
                        }
                        .padding(.horizontal, Design.Margin.page)
                    }
                    if !loaded.albums.isEmpty {
                        Shelf(title: String(localized: "Albums"), items: loaded.albums.map { Identified($0, id: $0.uri) }) { item in
                            NavigationLink(value: SpotifyRoute.album(item.value.uri)) {
                                SpotifyShelfTile(title: item.value.title, subtitle: item.value.releaseYear.map { Format.year(Int($0)) } ?? "", artwork: item.value.artworkUrl)
                            }
                            .buttonStyle(.pressable)
                        }
                    }
                }
                .padding(.vertical, Design.Spacing.large)
            } else if failed {
                SpotifyLoadFailure { Task { await load() } }
            } else {
                LoadingRows(count: 6)
                    .padding(Design.Margin.page)
            }
        }
        .navigationTitle(loaded?.artist.name ?? "")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu("More", systemImage: "ellipsis") { OpenInSpotifyButton(uri: uri) }
            }
        }
        .task(id: uri) { await load() }
    }

    private func load() async {
        failed = false
        do {
            guard let service = model.spotifyService else { return }
            loaded = try await service.artist(uri)
        } catch {
            failed = true
        }
    }
}

// MARK: - Search

/// Spotify's own search, for Search's Spotify scope: songs, albums, artists and playlists.
struct SpotifySearchView: View {
    let query: String
    @Environment(AppModel.self) private var model
    @State private var results: SpotifySearchResults?
    @State private var failed = false
    @State private var attempt = 0

    var body: some View {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        Group {
            if term.isEmpty {
                ContentUnavailableView("Search Spotify", systemImage: "magnifyingglass")
            } else if failed {
                ContentUnavailableView {
                    Label("Can't search right now.", systemImage: "wifi.exclamationmark")
                } actions: {
                    Button("Try Again") { attempt += 1 }
                }
            } else if let results {
                if isEmpty(results) {
                    ContentUnavailableView.search(text: term)
                } else {
                    list(results, context: .songs(term))
                }
            } else {
                LoadingRows(count: 6)
                    .padding(.horizontal, Design.Margin.page)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
        }
        .spotifyDestinations()
        .task(id: "\(term)#\(attempt)") {
            guard !term.isEmpty else { return }
            // Typing settles before asking.
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            failed = false
            do {
                results = try await model.spotifyService?.search(term)
            } catch {
                guard !Task.isCancelled else { return }
                failed = true
            }
        }
    }

    private func isEmpty(_ results: SpotifySearchResults) -> Bool {
        results.tracks.items.isEmpty && results.albums.items.isEmpty && results.artists.items.isEmpty && results.playlists.items.isEmpty
    }

    private func list(_ results: SpotifySearchResults, context: PlayContext) -> some View {
        List {
            if !results.tracks.items.isEmpty {
                Section("Songs") {
                    SpotifyTrackList(tracks: results.tracks.items, context: context)
                }
            }
            if !results.artists.items.isEmpty {
                Section("Artists") {
                    ForEach(results.artists.items, id: \.uri) { artist in
                        NavigationLink(value: SpotifyRoute.artist(artist.uri)) {
                            resultRow(title: artist.name, subtitle: nil, artwork: artist.artworkUrl, isCircle: true)
                        }
                    }
                }
            }
            if !results.albums.items.isEmpty {
                Section("Albums") {
                    ForEach(results.albums.items, id: \.uri) { album in
                        NavigationLink(value: SpotifyRoute.album(album.uri)) {
                            resultRow(title: album.title, subtitle: album.artistLine, artwork: album.artworkUrl)
                        }
                    }
                }
            }
            if !results.playlists.items.isEmpty {
                Section("Playlists") {
                    ForEach(results.playlists.items, id: \.uri) { playlist in
                        NavigationLink(value: SpotifyRoute.playlist(playlist.uri)) {
                            resultRow(title: playlist.name, subtitle: playlist.owner, artwork: playlist.artworkUrl)
                        }
                    }
                }
            }
        }
        #if os(iOS)
        .listStyle(.plain)
        .scrollDismissesKeyboard(.immediately)
        #endif
    }

    private func resultRow(title: String, subtitle: String?, artwork: String?, isCircle: Bool = false) -> some View {
        HStack(spacing: Design.Spacing.medium) {
            SpotifyCover(url: artwork, seed: title, size: 44, isCircle: isCircle)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .lineLimit(1)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: Design.Spacing.small)
            SpotifyMark(size: 16)
        }
        .accessibilityElement(children: .combine)
    }
}
#endif

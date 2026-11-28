import SwiftUI
import TracksCore

// A music service's albums, artists and playlists on one of your servers, laid out as your
// own music's pages are: the same headers, rows, shelves and tokens, with the service's
// covers through the server. Everything here plays through the server's relay, the server's
// own songs among them, as one queue.

/// A page of a music service's, by the ref a listing gave, on the server that gave it.
struct CatalogRoute: Hashable, Identifiable {
    enum Kind: Hashable {
        case album, artist, playlist
    }

    /// Refs are a server's own, so two servers can give the same one.
    var id: String { "\(serverID)/\(ref)" }

    let serverID: String
    let ref: String
    let kind: Kind
    /// Shown while the page loads.
    let title: String
    let subtitle: String?
    let artworkID: String?

    static func album(_ album: TracksideCatalogAlbum, on serverID: String) -> CatalogRoute {
        CatalogRoute(serverID: serverID, ref: album.ref, kind: .album, title: album.name, subtitle: album.artist, artworkID: album.artworkID)
    }

    static func artist(_ artist: TracksideCatalogArtist, on serverID: String) -> CatalogRoute {
        CatalogRoute(serverID: serverID, ref: artist.ref, kind: .artist, title: artist.name, subtitle: nil, artworkID: artist.artworkID)
    }

    static func playlist(_ playlist: TracksideCatalogPlaylist, on serverID: String) -> CatalogRoute {
        CatalogRoute(serverID: serverID, ref: playlist.ref, kind: .playlist, title: playlist.name, subtitle: playlist.owner, artworkID: playlist.artworkID)
    }
}

extension TracksideCatalogService.Quality {
    /// How it's shown: Lossless only where the server says the service plays it.
    var label: String? {
        switch self {
        case .lossless: String(localized: "Lossless")
        case .high: String(localized: "High Quality")
        case .standard, .unknown: nil
        }
    }
}

extension YourMusic {
    /// A service's picture, through the server: never from the service itself.
    func catalogCover(_ artworkID: String?, on serverID: String, seed: String) -> CoverArt {
        .url(artworkID.flatMap { servers.client(for: serverID)?.artworkURL(id: $0, size: 600) }?.absoluteString, seed: seed)
    }

    /// The song in your music that a service's track names as the same, where it does.
    func ownedCopy(of track: TracksideCatalogTrack, on serverID: String) -> LocalTrack? {
        guard let songID = track.songID else { return nil }
        return index.track(id: LocalTrack.id(for: .server(serverID: serverID, songID: songID)))
    }

    /// Tracks as the relay's queue, owned copies standing in where there are some.
    func relayQueue(_ tracks: [TracksideCatalogTrack], startingAt start: Int, on serverID: String, context: TracksideSpeaker.Context?) -> RelayQueue {
        RelayQueue(serverID: serverID, tracks: tracks, start: start, owned: { songID in
            self.index.track(id: LocalTrack.id(for: .server(serverID: serverID, songID: songID)))
        }, context: context)
    }
}

/// A service's track in a list, as any song is shown.
struct CatalogTrackLabel: View {
    let track: TracksideCatalogTrack
    let serverID: String
    var number: Int?
    var showsCover = true
    /// The service's name, where the list mixes services.
    var service: String?

    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player

    var body: some View {
        let owned = music.ownedCopy(of: track, on: serverID)
        let subtitle = [track.artistLine, service].compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " · ")
        TrackRow(
            title: track.title,
            subtitle: subtitle.isEmpty ? nil : subtitle,
            cover: showsCover ? music.catalogCover(track.artworkID, on: serverID, seed: track.album ?? track.title) : nil,
            number: number,
            isExplicit: track.isExplicit,
            isCurrent: track.songKey != nil && player.current?.songIdentity == track.songKey,
            localTrack: owned,
            isPlayable: track.isPlayable
        )
    }
}

/// Play Next, Play Later, the service's radio from it, saving it to the service, and the
/// album and artist a service's track is from.
struct CatalogTrackMenu: View {
    let track: TracksideCatalogTrack
    let serverID: String
    let title: String

    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @Environment(PlayerModel.self) private var player
    @Environment(\.openPlayRoute) private var openPlayRoute

    var body: some View {
        let queue = music.relayQueue([track], startingAt: 0, on: serverID, context: nil)
        let streaming = model.streaming
        let service = streaming.service(CatalogServiceAddress(serverID: serverID, serviceID: track.serviceID))
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") {
            player.enqueue(.relay(queue), next: true, title: title)
        }
        Button("Play Later", systemImage: "text.line.last.and.arrowtriangle.forward") {
            player.enqueue(.relay(queue), next: false, title: title)
        }
        if let service, streaming.playback[serverID]?.radio == true, service.can(.similar), track.isPlayable {
            Button("Start Radio", systemImage: "dot.radiowaves.left.and.right") {
                var radio = RelayQueue(serverID: serverID, entries: [RelayEntry(track)], context: TracksideSpeaker.Context(kind: .station, name: track.title))
                radio.isRadio = true
                model.playThroughRelay(radio, from: PlayContext(kind: .station, title: track.title))
            }
        }
        if let service, streaming.canSave(track: track.serviceID, on: serverID) {
            let isSaved = streaming.isSaved(track.ref, on: serverID, known: track.saved) ?? false
            Button(isSaved ? String(localized: "Remove from \(service.name)") : String(localized: "Save to \(service.name)"), systemImage: isSaved ? "star.slash" : "star") {
                Task {
                    do {
                        try await streaming.setSaved(!isSaved, ref: track.ref, on: serverID)
                    } catch {
                        player.confirm(isSaved ? String(localized: "Couldn't Remove from \(service.name)") : String(localized: "Couldn't Save to \(service.name)"))
                    }
                }
            }
        }
        if openPlayRoute.canPush {
            if let albumRef = track.albumRef {
                Button("Go to Album", systemImage: "square.stack") {
                    openPlayRoute(.catalog(CatalogRoute(serverID: serverID, ref: albumRef, kind: .album, title: track.album ?? "", subtitle: track.artistLine, artworkID: track.artworkID)))
                }
            }
            if let artist = track.artists.first(where: { $0.ref != nil }), let ref = artist.ref {
                Button("Go to Artist", systemImage: "music.microphone") {
                    openPlayRoute(.catalog(CatalogRoute(serverID: serverID, ref: ref, kind: .artist, title: artist.name, subtitle: nil, artworkID: nil)))
                }
            }
        }
    }
}

/// An album or playlist's cover and name on a shelf, opening its page.
struct CatalogCollectionTile: View {
    let route: CatalogRoute
    @Environment(YourMusic.self) private var music

    var body: some View {
        NavigationLink(value: PlayRoute.catalog(route)) {
            TileLabel(title: route.title, subtitle: route.subtitle) { side in
                ArtworkView(url: coverURL, seed: route.title, size: side)
            }
        }
        .buttonStyle(.pressable)
    }

    private var coverURL: String? {
        if case .url(let url, _) = music.catalogCover(route.artworkID, on: route.serverID, seed: route.title) { return url }
        return nil
    }
}

/// An artist's circle and name on a shelf, opening their page.
struct CatalogArtistTile: View {
    let route: CatalogRoute
    @Environment(YourMusic.self) private var music

    var body: some View {
        NavigationLink(value: PlayRoute.catalog(route)) {
            VStack(spacing: Design.Spacing.small) {
                ArtistPicture(cover: route.artworkID.map { music.catalogCover($0, on: route.serverID, seed: route.title) }, name: route.title, size: 120)
                Text(route.title)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
            }
            .frame(width: 120)
            .accessibilityElement(children: .combine)
        }
        .buttonStyle(.pressable)
    }
}

/// Where a page's items came from couldn't answer: said calmly in the page's place, with a
/// way to ask again.
struct CatalogPageProblem: View {
    let error: any Error
    let retry: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: symbol)
        } description: {
            Text(message)
        } actions: {
            if !isGone {
                Button("Try Again", action: retry)
                    .buttonStyle(.glass)
            }
        }
    }

    private var isGone: Bool {
        if case TracksideError.notFound = error { return true }
        return false
    }

    private var title: String {
        switch error {
        case TracksideError.notFound: String(localized: "No Longer Available")
        case TracksideError.conflict(let reason) where reason == "service_signed_out": String(localized: "Signed Out")
        case is URLError: String(localized: "Couldn't Reach the Server")
        default: String(localized: "Not Available Right Now")
        }
    }

    private var symbol: String {
        switch error {
        case is URLError: "wifi.exclamationmark"
        case TracksideError.notFound: "music.note"
        default: "waveform"
        }
    }

    private var message: String {
        switch error {
        case TracksideError.notFound: String(localized: "The service doesn't have this anymore.")
        case TracksideError.conflict(let reason) where reason == "service_signed_out": String(localized: "Sign in to the service again on your server.")
        case is URLError: String(localized: "Check that your server is online, then try again.")
        default: String(localized: "Music services aren't available right now.")
        }
    }
}

// MARK: - Album and playlist

/// A service's album or playlist: its cover on a field of its colour, and its tracks. A
/// playlist's next tracks come as they're scrolled to.
struct CatalogCollectionPage: View {
    let route: CatalogRoute

    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @Environment(\.openPlayRoute) private var openPlayRoute

    @State private var tracks: [TracksideCatalogTrack] = []
    @State private var facts: (count: Int?, year: Int?, artistRef: String?, owner: String?) = (nil, nil, nil, nil)
    @State private var nextCursor: String?
    @State private var isLoaded = false
    @State private var failure: (any Error)?
    @State private var isEndInView = false

    var body: some View {
        content
            .task(id: route) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if let failure, !isLoaded {
            CatalogPageProblem(error: failure) {
                self.failure = nil
                Task { await load() }
            }
            .navigationTitle(route.title)
        } else {
            page
        }
    }

    private var context: PlayContext {
        PlayContext(kind: route.kind == .playlist ? .playlist : .album, title: route.title)
    }

    private var speakerContext: TracksideSpeaker.Context {
        TracksideSpeaker.Context(kind: route.kind == .playlist ? .playlist : .album, name: route.title)
    }

    private var playable: [TracksideCatalogTrack] { tracks.filter(\.isPlayable) }

    private func play(from track: TracksideCatalogTrack?, shuffled: Bool = false) {
        let queue = playable
        guard !queue.isEmpty else { return }
        let start = track.flatMap { wanted in queue.firstIndex { $0.ref == wanted.ref } } ?? 0
        model.playThroughRelay(music.relayQueue(queue, startingAt: start, on: route.serverID, context: speakerContext), from: context, shuffled: shuffled)
    }

    private var header: some View {
        let cover = music.catalogCover(route.artworkID, on: route.serverID, seed: route.title)
        let seconds = tracks.compactMap(\.duration).reduce(0, +)
        return CollectionHeader(
            kind: route.kind == .playlist ? .playlist : .album,
            title: route.title,
            subtitle: route.subtitle,
            onSubtitle: facts.artistRef == nil ? nil : { openArtist() },
            facts: CollectionFacts.line([
                serviceName,
                quality,
                facts.year.map { Format.year($0) },
                CollectionFacts.length(count: facts.count ?? tracks.count, seconds: seconds),
            ]),
            tintCover: cover,
            canPlay: !playable.isEmpty,
            isLoading: !isLoaded,
            play: { play(from: nil) },
            shuffle: { play(from: nil, shuffled: true) }
        ) {
            EmptyView()
        } cover: { side in
            ArtworkView(url: coverURL(cover), seed: route.title, size: side)
        }
    }

    private func openArtist() {
        guard let ref = facts.artistRef else { return }
        openPlayRoute(.catalog(CatalogRoute(serverID: route.serverID, ref: ref, kind: .artist, title: route.subtitle ?? "", subtitle: nil, artworkID: nil)))
    }

    private func coverURL(_ cover: CoverArt) -> String? {
        if case .url(let url, _) = cover { return url }
        return nil
    }

    private var service: TracksideCatalogService? {
        model.streaming.services[route.serverID]?.first { service in tracks.first?.serviceID == service.id }
    }

    private var serviceName: String? { service?.name }

    /// The tracks' own quality where they all say one, otherwise the service's.
    private var quality: String? {
        let qualities = Set(tracks.compactMap(\.quality))
        return (qualities.count == 1 ? qualities.first : service?.quality)?.label
    }

    private func row(_ track: TracksideCatalogTrack, position: Int) -> some View {
        Button {
            play(from: track)
        } label: {
            CatalogTrackLabel(
                track: track,
                serverID: route.serverID,
                number: route.kind == .album ? (track.trackNumber ?? position + 1) : nil,
                showsCover: route.kind == .playlist
            )
        }
        .buttonStyle(.plain)
        .disabled(!track.isPlayable)
        .contextMenu { CatalogTrackMenu(track: track, serverID: route.serverID, title: route.title) }
    }

    /// The end of a playlist's tracks, once it's scrolled to: the next page.
    @ViewBuilder
    private var more: some View {
        if nextCursor != nil {
            LoadingRows(count: 2, showsCover: route.kind == .playlist)
                .onScrollVisibilityChange(threshold: 0.1) { isEndInView = $0 }
                .task(id: "\(isEndInView).\(tracks.count)") {
                    guard isEndInView else { return }
                    await loadMore()
                }
        }
    }

    @ViewBuilder
    private var page: some View {
        #if os(iOS)
        List {
            Section { header }
            Section {
                if isLoaded {
                    ForEach(tracks.enumerated(), id: \.element.ref) { position, track in
                        row(track, position: position)
                            .collectionRowInsets(hasCover: route.kind == .playlist)
                    }
                    more
                        .listRowSeparator(.hidden)
                } else {
                    LoadingRows(count: 8, showsCover: route.kind == .playlist)
                        .listRowSeparator(.hidden)
                }
            }
        }
        .collectionPage(title: route.title, cover: music.catalogCover(route.artworkID, on: route.serverID, seed: route.title))
        .animation(Design.Motion.panel, value: isLoaded)
        #else
        CollectionMacLayout {
            header
        } content: {
            LazyVStack(alignment: .leading, spacing: 0) {
                if isLoaded {
                    ForEach(tracks.enumerated(), id: \.element.ref) { position, track in
                        row(track, position: position)
                            .padding(.vertical, Design.Spacing.xSmall)
                    }
                    more
                } else {
                    LoadingRows(count: 8, showsCover: route.kind == .playlist)
                }
            }
            .padding(.horizontal, Design.Margin.page)
            .padding(.top, Design.Spacing.medium)
            .animation(Design.Motion.panel, value: isLoaded)
        }
        .navigationTitle(route.title)
        #endif
    }

    // MARK: - Loading

    private func load() async {
        do {
            let item = try await model.streaming.item(route.ref, on: route.serverID)
            guard !Task.isCancelled else { return }
            take(item, appending: false)
            isLoaded = true
        } catch {
            guard !Task.isCancelled else { return }
            failure = error
        }
    }

    private func loadMore() async {
        guard let cursor = nextCursor else { return }
        guard let item = try? await model.streaming.item(route.ref, on: route.serverID, cursor: cursor), !Task.isCancelled else { return }
        take(item, appending: true)
    }

    private func take(_ item: TracksideCatalogItem, appending: Bool) {
        switch item {
        case .album(let album, let found):
            tracks = found
            facts = (album.trackCount, album.year, album.artistRef, nil)
            nextCursor = nil
        case .playlist(let playlist, let found, let next):
            // A track a later page repeats is listed once.
            let known = Set(tracks.map(\.ref))
            tracks = appending ? tracks + found.filter { !known.contains($0.ref) } : found
            facts = (playlist.trackCount, nil, nil, playlist.owner)
            nextCursor = found.isEmpty ? nil : next
        case .track(let track):
            tracks = [track]
            nextCursor = nil
        case .artist, .radio:
            nextCursor = nil
        }
    }
}

// MARK: - Artist

/// A service's artist: their top tracks and albums, as your own artists' pages have them.
struct CatalogArtistPage: View {
    let route: CatalogRoute

    @Environment(AppModel.self) private var model
    @Environment(YourMusic.self) private var music
    @State private var topTracks: [TracksideCatalogTrack] = []
    @State private var albums: [TracksideCatalogAlbum] = []
    @State private var picture: String?
    @State private var isLoaded = false
    @State private var failure: (any Error)?

    var body: some View {
        ArtistScaffold(
            name: route.title,
            picture: (picture ?? route.artworkID).map { music.catalogCover($0, on: route.serverID, seed: route.title) },
            play: topTracks.isEmpty ? nil : { play(from: nil) },
            shuffle: topTracks.isEmpty ? nil : { play(from: nil, shuffled: true) },
            showsYourTopSongs: true
        ) {
            EmptyView()
        } sections: {
            if isLoaded {
                if !topTracks.isEmpty { topSongs }
                if !albums.isEmpty {
                    Shelf(title: String(localized: "Albums"), items: albums.map { CatalogRoute.album($0, on: route.serverID) }) { album in
                        CatalogCollectionTile(route: album)
                    }
                }
                if topTracks.isEmpty, albums.isEmpty {
                    ArtistPageMessage(
                        title: String(localized: "Nothing to Show Yet"),
                        symbol: "square.stack",
                        message: String(localized: "The service doesn't have any of their songs or albums to show.")
                    ) { EmptyView() }
                }
            } else if let failure {
                CatalogPageProblem(error: failure) {
                    self.failure = nil
                    Task { await load() }
                }
            } else {
                ArtistLoadingSections()
            }
        }
        .animation(Design.Motion.panel, value: isLoaded)
        .task(id: route) { await load() }
    }

    private var topSongs: some View {
        let visible = Array(topTracks.prefix(ArtistSongsSection<EmptyView, EmptyView>.visibleRows))
        return ArtistSongsSection(title: String(localized: "Top Songs"), showsSeeAll: topTracks.count > visible.count) {
            ArtistSongListPage(title: String(localized: "Top Songs"), artist: route.title) {
                ForEach(topTracks) { track in row(track) }
            }
        } content: {
            ForEach(visible) { track in row(track) }
        }
    }

    private func row(_ track: TracksideCatalogTrack) -> some View {
        Button {
            play(from: track)
        } label: {
            ArtistSongLabel(
                title: track.title,
                subtitle: track.album,
                cover: music.catalogCover(track.artworkID, on: route.serverID, seed: track.album ?? track.title),
                isExplicit: track.isExplicit,
                localTrack: music.ownedCopy(of: track, on: route.serverID),
                isPlayable: track.isPlayable
            )
        }
        .buttonStyle(.plain)
        .disabled(!track.isPlayable)
        .contextMenu { CatalogTrackMenu(track: track, serverID: route.serverID, title: route.title) }
    }

    private func play(from track: TracksideCatalogTrack?, shuffled: Bool = false) {
        let queue = topTracks.filter(\.isPlayable)
        guard !queue.isEmpty else { return }
        let start = track.flatMap { wanted in queue.firstIndex { $0.ref == wanted.ref } } ?? 0
        let context = TracksideSpeaker.Context(kind: .artist, name: route.title)
        model.playThroughRelay(music.relayQueue(queue, startingAt: start, on: route.serverID, context: context), from: PlayContext(kind: .artist, title: route.title), shuffled: shuffled)
    }

    private func load() async {
        do {
            let item = try await model.streaming.item(route.ref, on: route.serverID)
            guard !Task.isCancelled else { return }
            if case .artist(let artist, let top, let found) = item {
                topTracks = top
                albums = found
                picture = artist.artworkID
            }
            isLoaded = true
        } catch {
            guard !Task.isCancelled else { return }
            failure = error
        }
    }
}

// MARK: - A service's library

/// What's saved on a service: its playlists first, then albums and artists, newest first.
struct CatalogLibraryPage: View {
    let address: CatalogServiceAddress

    @Environment(AppModel.self) private var model
    @State private var playlists: [TracksideCatalogPlaylist] = []
    @State private var albums: [TracksideCatalogAlbum] = []
    @State private var artists: [TracksideCatalogArtist] = []
    @State private var isLoaded = false
    @State private var failure: (any Error)?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Design.Margin.section) {
                if isLoaded {
                    if !playlists.isEmpty {
                        Shelf(title: String(localized: "Playlists"), items: playlists.map { CatalogRoute.playlist($0, on: address.serverID) }) { playlist in
                            CatalogCollectionTile(route: playlist)
                        }
                    }
                    if !albums.isEmpty {
                        Shelf(title: String(localized: "Albums"), items: albums.map { CatalogRoute.album($0, on: address.serverID) }) { album in
                            CatalogCollectionTile(route: album)
                        }
                    }
                    if !artists.isEmpty {
                        Shelf(title: String(localized: "Artists"), items: artists.map { CatalogRoute.artist($0, on: address.serverID) }) { artist in
                            CatalogArtistTile(route: artist)
                        }
                    }
                } else if failure == nil {
                    LoadingRows(count: 6)
                        .padding(.horizontal, Design.Margin.page)
                }
            }
            .padding(.vertical, Design.Spacing.large)
            .animation(Design.Motion.panel, value: isLoaded)
        }
        .overlay {
            if let failure {
                CatalogPageProblem(error: failure) {
                    self.failure = nil
                    Task { await load() }
                }
            } else if isLoaded, playlists.isEmpty, albums.isEmpty, artists.isEmpty {
                ContentUnavailableView {
                    Label("Nothing Saved Yet", systemImage: "music.note.list")
                } description: {
                    Text("What you save on \(name) shows here once your server has caught up.")
                }
            }
        }
        .navigationTitle(name)
        .task(id: address) { await load() }
    }

    private var name: String {
        model.streaming.service(address)?.name ?? String(localized: "Library")
    }

    private func load() async {
        do {
            let found = try await model.streaming.library(of: address, kind: .playlist)
            // The other kinds on their own: one the service doesn't keep leaves the rest.
            let foundAlbums = try? await model.streaming.library(of: address, kind: .album)
            let foundArtists = try? await model.streaming.library(of: address, kind: .artist)
            guard !Task.isCancelled else { return }
            playlists = found.playlists
            albums = foundAlbums?.albums ?? []
            artists = foundArtists?.artists ?? []
            isLoaded = true
        } catch {
            guard !Task.isCancelled else { return }
            failure = error
        }
    }
}

/// The music services with a saved library, each opening it: shown only while a server
/// offers one, so an older server, or one without services, shows nothing here.
struct ServiceLibraryShelf: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let streaming = model.streaming
        let services = streaming.serverIDs.flatMap { serverID in
            (streaming.services[serverID] ?? []).filter { $0.can(.library) && $0.isSignedIn }.map { SearchableService(address: CatalogServiceAddress(serverID: serverID, serviceID: $0.id), service: $0) }
        }
        if !services.isEmpty {
            Shelf(title: String(localized: "Your Services"), items: services) { entry in
                NavigationLink(value: PlayRoute.catalogLibrary(entry.address)) {
                    TileLabel(title: entry.service.name, subtitle: String(localized: "Library")) { side in
                        ServiceTileArt(logoKey: entry.service.logoKey, side: side)
                    }
                }
                .buttonStyle(.pressable)
            }
        }
    }
}

/// A service's square on a shelf: its glyph on the page's quiet fill, never its own colours.
private struct ServiceTileArt: View {
    let logoKey: String?
    let side: CGFloat

    var body: some View {
        // A cover's own corners, so it sits in a shelf of covers.
        RoundedRectangle(cornerRadius: max(4, side * 0.12), style: .continuous)
            .fill(Color.placeholderFill)
            .frame(width: side, height: side)
            .overlay {
                ServiceGlyph(logoKey: logoKey ?? "", side: side * 0.32)
                    .font(.system(size: side * 0.28, weight: .medium))
                    .foregroundStyle(.secondary)
            }
            .accessibilityHidden(true)
    }
}

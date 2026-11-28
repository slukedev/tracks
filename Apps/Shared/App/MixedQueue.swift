import Foundation
import MusicKit
import TracksCore

/// One queue across sources: which copy of each song plays, whether a song ahead can play
/// now, and the other copies Now Playing can switch to.
extension AppModel {
    /// Whether a queue can mix sources now: every source on, and more than one player with
    /// music. Never with sample data.
    var mixesSources: Bool {
        !isDemoLaunch && effectiveSourceSelection == .all && playerSources.count > 1
    }

    /// The queue across sources, when a request's songs have their best copies in more than
    /// one source. Nil plays the request on one player, as before.
    func mixedEngineIfNeeded(for request: PlayRequest) -> MixedQueueEngine? {
        guard mixesSources, let found = sourcedItems(for: request),
              Set(found.items.map(\.source)).count > 1
        else { return nil }
        return mixedEngine
    }

    /// The sources a request's songs play best from, with every source on. Empty otherwise,
    /// and for an album, playlist or station, which play as they are.
    func sources(of request: PlayRequest) -> Set<MusicSource> {
        guard mixesSources, let found = sourcedItems(for: request) else { return [] }
        return Set(found.items.map(\.source))
    }

    /// A request's songs, each from its best copy in Play From order. Nil for what can't be
    /// split into songs here: an album, playlist or station from Apple Music.
    func sourcedItems(for request: PlayRequest) -> (items: [MixedQueueEngine.Item], start: Int)? {
        switch request {
        case .local(let tracks, let start):
            return (tracks.map { track in
                item(for: HistorySong(track), catalog: nil) ?? localItem(track)
            }, start)
        case .songs(let songs, let start):
            return aligned(songs.enumerated().map { offset, song in
                let history = HistorySong(songID: song.id.rawValue, title: song.title, artistName: song.artistName, albumTitle: song.albumTitle)
                return (offset: offset, item: item(for: history, catalog: song))
            }, start: start)
        case .history(let songs, let start):
            return aligned(songs.enumerated().map { (offset: $0.offset, item: item(for: $0.element, catalog: nil)) }, start: start)
        case .album, .playlist, .station, .demoStation, .relay:
            return nil
        }
    }

    /// ``sourcedItems(for:)``, with Apple Music's albums and playlists read into their songs.
    func resolveForMixing(_ request: PlayRequest) async throws -> (items: [MixedQueueEngine.Item], start: Int) {
        if let found = sourcedItems(for: request) { return found }
        let songs: [Song]
        switch request {
        case .album(let album):
            let detailed = try await album.with([.tracks])
            songs = detailed.tracks?.compactMap(Self.song) ?? []
        case .playlist(let playlist):
            let detailed = try await playlist.with([.tracks])
            songs = detailed.tracks?.compactMap(Self.song) ?? []
        default:
            // A station plays on its own: it has no songs to queue.
            throw PlayerProblem.nothingToPlay
        }
        return (songs.map { song in
            appleMusicItem(HistorySong(songID: song.id.rawValue, title: song.title, artistName: song.artistName, albumTitle: song.albumTitle), catalog: song)
        }, 0)
    }

    private static func song(_ track: Track) -> Song? {
        if case .song(let song) = track { return song }
        return nil
    }

    /// Items for the songs that have a copy anywhere, and where the asked-for start landed.
    private func aligned(_ found: [(offset: Int, item: MixedQueueEngine.Item?)], start: Int) -> (items: [MixedQueueEngine.Item], start: Int) {
        let kept = found.filter { $0.item != nil }
        let first = kept.firstIndex { $0.offset >= start } ?? 0
        return (kept.compactMap(\.item), first)
    }

    // MARK: - Copies

    /// The copy of a song that plays: the first in Play From order that can play now, or,
    /// when none can, the first there is, which the queue then shows as unavailable.
    func item(for song: HistorySong, catalog: Song?) -> MixedQueueEngine.Item? {
        let ranked = copies(of: song, catalog: catalog)
        // A queue left across sources comes back as it was.
        if let source = song.source, let kept = ranked.first(where: { $0.canPlay && $0.item.source == source }) {
            return kept.item
        }
        return (ranked.first { $0.canPlay } ?? ranked.first)?.item
    }

    /// Every copy of a song across sources, best first in Play From order.
    func copies(of song: HistorySong, catalog: Song?) -> [(item: MixedQueueEngine.Item, place: PlayFromPlace, canPlay: Bool)] {
        var copies: [(item: MixedQueueEngine.Item, place: PlayFromPlace, canPlay: Bool)] = []
        let identity = SongIdentity.key(title: song.title, artistName: song.artistName)
        var locals = yourMusic.index.tracks(withIdentity: identity)
        if let exact = yourMusic.index.track(id: song.songID), !locals.contains(exact) { locals.insert(exact, at: 0) }
        for track in locals {
            copies.append((localItem(track), yourMusic.playFromPlace(of: track), yourMusic.isPlayable(track)))
        }
        if musicAuthorization == .authorized, !playFeed.cannotPlayCatalog {
            // By its catalog id, or by name, which Apple Music's player looks up as it comes.
            let known = catalog != nil || Self.isCatalogID(song.songID)
            let byName = HistorySong(songID: known ? song.songID : "", title: song.title, artistName: song.artistName, albumTitle: song.albumTitle, artworkURL: song.artworkURL)
            copies.append((appleMusicItem(byName, catalog: catalog), .source(.appleMusic), true))
        }
        return yourMusic.playFromOrder.ranked(copies, place: \.place)
    }

    /// Whether any source can play a live mix's song now, quickly enough to ask of every song
    /// Tracks Radio could pick.
    func canPlayAnywhere(_ song: MixSong) -> Bool {
        if yourMusic.canPlay(song) { return true }
        return musicAuthorization == .authorized && !playFeed.cannotPlayCatalog
    }

    /// Another copy of a song ahead that can play now, from somewhere else.
    func otherCopy(of item: MixedQueueEngine.Item) -> MixedQueueEngine.Item? {
        let song = HistorySong(item.track)
        return copies(of: HistorySong(songID: item.track.song?.id.rawValue ?? song.songID, title: song.title, artistName: song.artistName, albumTitle: song.albumTitle), catalog: item.track.song)
            .first { $0.canPlay && $0.place != place(of: item) }?.item
    }

    /// The other places the song on could play from now, for Now Playing's Play From.
    func otherCopies(of track: PlayerTrack, playingFrom source: SourceID?) -> [(source: SourceID, item: MixedQueueEngine.Item)] {
        let song = HistorySong(songID: track.song?.id.rawValue ?? track.local?.id ?? "", title: track.title, artistName: track.artistName, albumTitle: track.albumTitle)
        var seen = Set<SourceID>()
        if let source { seen.insert(source) }
        return copies(of: song, catalog: track.song).compactMap { copy in
            guard copy.canPlay else { return nil }
            let id = sourceID(of: copy.item)
            guard seen.insert(id).inserted else { return nil }
            return (source: id, item: copy.item)
        }
    }

    func sourceID(of item: MixedQueueEngine.Item) -> SourceID {
        switch item.payload {
        case .local(let track): track.serverID.map(SourceID.server) ?? .device
        case .song, .history: .appleMusic
        }
    }

    private func place(of item: MixedQueueEngine.Item) -> PlayFromPlace {
        if case .local(let track) = item.payload { return yourMusic.playFromPlace(of: track) }
        return .source(sourceID(of: item))
    }

    // MARK: - Items

    func localItem(_ track: LocalTrack) -> MixedQueueEngine.Item {
        let id = UUID().uuidString
        return MixedQueueEngine.Item(
            id: id,
            source: .yourMusic,
            payload: .local(track),
            track: PlayerTrack(
                id: id,
                title: track.title,
                artistName: track.artist,
                albumTitle: track.album,
                cover: .url(yourMusic.artworkURL(track.artwork)?.absoluteString, seed: track.album ?? track.title),
                duration: track.duration,
                isExplicit: false,
                song: nil,
                local: track
            )
        )
    }

    private func appleMusicItem(_ song: HistorySong, catalog: Song?) -> MixedQueueEngine.Item {
        guard let catalog else { return itemPlaying(song, from: .appleMusic) }
        let id = UUID().uuidString
        return MixedQueueEngine.Item(
            id: id,
            source: .appleMusic,
            payload: .song(catalog),
            track: PlayerTrack(
                id: id,
                title: catalog.title,
                artistName: catalog.artistName,
                albumTitle: catalog.albumTitle,
                cover: catalog.artwork.map(CoverArt.artwork) ?? .url(nil, seed: catalog.albumTitle ?? catalog.title),
                duration: catalog.duration,
                isExplicit: catalog.contentRating == .explicit,
                song: catalog
            )
        )
    }

    /// A song known by name and id, for a source's player to look up as it comes.
    private func itemPlaying(_ song: HistorySong, from source: MusicSource) -> MixedQueueEngine.Item {
        let id = UUID().uuidString
        return MixedQueueEngine.Item(
            id: id,
            source: source,
            payload: .history(song),
            track: PlayerTrack(
                id: id,
                title: song.title,
                artistName: song.artistName,
                albumTitle: song.albumTitle,
                cover: .url(song.artworkURL, seed: song.albumTitle ?? song.title),
                duration: nil,
                isExplicit: false,
                song: nil
            )
        )
    }

    // MARK: - Whether it can play now

    /// Why a song ahead can't play now from the source it's queued from, or nil when it can.
    func unavailability(of track: PlayerTrack, from source: MusicSource?) -> PlayerModel.Unavailable? {
        if let local = track.local {
            return yourMusic.isPlayable(local) ? nil : PlayerModel.Unavailable(reason: offlineReason([local]))
        }
        switch source {
        case .appleMusic:
            return musicAuthorization == .authorized || isDemoLaunch ? nil : PlayerModel.Unavailable(reason: nil)
        case .yourMusic, nil:
            return nil
        }
    }

    /// Why songs from one source couldn't start, said once for all of them.
    func reasonUnavailable(_ source: MusicSource, _ items: [MixedQueueEngine.Item]) -> String? {
        switch source {
        case .yourMusic:
            return offlineReason(items.compactMap(\.payload.localTrack))
        case .appleMusic:
            return nil
        }
    }

    /// "Home is offline", when every song is on one server out of reach.
    private func offlineReason(_ tracks: [LocalTrack]) -> String? {
        let servers = Set(tracks.map(\.serverID))
        guard servers.count == 1, let id = servers.first ?? nil, let server = yourMusic.servers.server(id) else { return nil }
        return String(localized: "\(server.name) is offline")
    }

    /// Whether anything is here to play with no connection: files, and downloads.
    var hasDownloadsToPlay: Bool {
        !yourMusic.isDemo && !(yourMusic.fileTracks.isEmpty && yourMusic.tracks(for: .downloaded).isEmpty)
    }

    /// Everything that plays with no connection, shuffled.
    func playDownloads() {
        let tracks = yourMusic.tracks(for: .downloaded).shuffled()
        guard !tracks.isEmpty else { return }
        switchSource(to: .yourMusic)
        Task { await player.start(.local(tracks), from: .songs(String(localized: "Downloads"))) }
    }
}

// MARK: - Albums and artists across sources

extension AppModel {
    /// Where a song of yours plays from when its own copy can't play now: another source's
    /// copy that can, with every source on. Nil when it plays as it is, or nothing can.
    func playableCopy(of track: LocalTrack) -> MixedQueueEngine.Item? {
        guard mixesSources, !yourMusic.isPlayable(track) else { return nil }
        return copies(of: HistorySong(track), catalog: nil).first { $0.canPlay }?.item
    }

    /// The source a song plays from with every source on, where that isn't `page`'s: for the
    /// glyph on its row.
    func otherSource(of song: HistorySong, catalog: Song?, besides page: MusicSource) -> SourceID? {
        guard mixesSources, let best = copies(of: song, catalog: catalog).first(where: \.canPlay)?.item,
              best.source != page
        else { return nil }
        return sourceID(of: best)
    }

    /// The sources of yours an album's songs are on, by title and artist: this device and
    /// each server.
    func ownSources(ofAlbum title: String, by artist: String) -> [SourceID] {
        let key = (title: StatsCalculator.folded(title), artist: StatsCalculator.folded(artist))
        var sources: [SourceID] = []
        for album in yourMusic.index.albums where AlbumMatch.matches(album.title, album.artist, key) {
            for track in album.tracks {
                let source = track.serverID.map(SourceID.server) ?? .device
                if !sources.contains(source) { sources.append(source) }
            }
        }
        return sources
    }
}

/// Where else an album is, by title and artist: Apple Music's catalog.
/// Asked once an album a launch, and only with every source on.
@MainActor
enum AlbumElsewhere {
    private static var known: [String: [SourceID]] = [:]

    static func sources(title: String, artist: String, model: AppModel, besides page: SourceID) async -> [SourceID] {
        guard model.mixesSources else { return [] }
        let key = "\(StatsCalculator.folded(title))\u{1F}\(StatsCalculator.folded(artist))"
        if let found = known[key] { return found.filter { $0 != page } }
        let match = (title: StatsCalculator.folded(title), artist: StatsCalculator.folded(artist))
        var found: [SourceID] = []
        if page != .appleMusic, model.musicAuthorization == .authorized, !model.playFeed.cannotPlayCatalog {
            var request = MusicCatalogSearchRequest(term: "\(title) \(artist)", types: [Album.self])
            request.limit = 5
            if let response = try? await request.response(),
               response.albums.contains(where: { AlbumMatch.matches($0.title, $0.artistName, match) }) {
                found.append(.appleMusic)
            }
        }
        known[key] = found
        return found
    }
}

/// One album by two names: the same title, or one starting with the other ("Blue" and "Blue
/// (Deluxe Edition)"), by the same artist.
enum AlbumMatch {
    static func matches(_ title: String, _ artist: String, _ key: (title: String, artist: String)) -> Bool {
        let foldedTitle = StatsCalculator.folded(title)
        let foldedArtist = StatsCalculator.folded(artist)
        guard !key.title.isEmpty, foldedArtist == key.artist || foldedArtist.contains(key.artist) || key.artist.contains(foldedArtist) else { return false }
        return foldedTitle == key.title || foldedTitle.hasPrefix(key.title + " ") || key.title.hasPrefix(foldedTitle + " ")
    }
}

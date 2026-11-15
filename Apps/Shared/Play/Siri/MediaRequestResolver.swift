import Foundation
import MusicKit
import TracksCore

/// What someone asked to play, as plain values: from Siri's media intent on iPhone, Siri's
/// audio search on both, or the Play Music intent.
nonisolated struct MediaRequest: Sendable, Hashable {
    var name: String
    var artist: String = ""
    var kind: MediaKind = .anything
    /// Moods Siri heard: "something chill", "workout music".
    var moods: [String] = []
    /// Genres Siri heard: "play some jazz".
    var genres: [String] = []
    /// A source asked for in so many words, beyond any in the name.
    var source: MusicSource?

    static func readings(of spoken: String, kind: MediaKind = .anything) -> [MediaRequest] {
        SpokenMedia.readings(of: spoken).map { reading in
            let heard = MediaKind(rawValue: reading.kind.rawValue) ?? .anything
            return MediaRequest(name: reading.name, artist: reading.artist, kind: kind == .anything ? heard : kind)
        }
    }
}

/// The kinds of thing that can be asked for by name.
nonisolated enum MediaKind: String, Sendable, Hashable {
    case anything, song, album, artist, playlist, station, genre
}

/// Where what's found goes: played now, or added after the song on or at the end.
nonisolated enum MediaPlacement: Sendable {
    case now, next, later
}

/// What a request resolved to. The identifier carries it from resolving to playing, which
/// Siri does in two steps: "kind:value", with "@source" after the kind for Tracks' own mixes
/// and genres asked for from a particular source.
nonisolated struct ResolvedMedia: Sendable, Equatable {
    let identifier: String
    let title: String
    let kind: MediaKind
    var artist: String?
    var isOwned = false
    var plays = 0
    var isLive = false

    var candidate: SpokenMedia.Candidate {
        SpokenMedia.Candidate(
            title: title,
            artist: artist,
            kind: SpokenMedia.Kind(rawValue: kind.rawValue) ?? .anything,
            isOwned: isOwned,
            plays: plays
        )
    }

    var spokenTitle: String {
        [title, artist].compactMap(\.self).filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

nonisolated enum MediaMiss: Sendable, Equatable {
    case notFound(String)
    case needsAccess(neverAsked: Bool)
    case needsSubscription
    case offline
    case sampleData

    var isNotFound: Bool {
        if case .notFound = self { true } else { false }
    }

    var message: String {
        switch self {
        case .notFound(let name) where name.isEmpty:
            String(localized: "Couldn't find that in Apple Music or your music.")
        case .notFound(let name):
            String(localized: "Couldn't find “\(name)” in Apple Music or your music.")
        case .needsAccess(let neverAsked):
            neverAsked
                ? String(localized: "Open Tracks and allow Apple Music access, then ask again.")
                : String(localized: "Turn on Media & Apple Music for Tracks in Settings to play music here.")
        case .needsSubscription:
            String(localized: "Playing that needs an Apple Music subscription.")
        case .offline:
            String(localized: "Tracks can't reach Apple Music right now, and none of your own music matches.")
        case .sampleData:
            String(localized: "Tracks is showing sample data, so it can't play.")
        }
    }
}

nonisolated enum MediaAnswer: Sendable {
    case play([ResolvedMedia])
    case ask([ResolvedMedia])
    case missing(MediaMiss)
}

/// Turns what was asked for into something Tracks can play, looking in the music source
/// that's on and then the other one, and plays it, switching source on the way when the
/// match came from the other one. What was playing carries on until the new music starts.
@MainActor
enum MediaRequestResolver {
    static func item(for request: MediaRequest) async -> ResolvedMedia? {
        await matches(for: [request]).first
    }

    static func answer(for readings: [MediaRequest]) async -> MediaAnswer {
        guard !AppModel.shared.isDemoLaunch else { return .missing(.sampleData) }
        let found = await matches(for: readings)
        guard let first = found.first else { return .missing(await miss(for: readings)) }
        let reading = bestReading(of: readings, for: first)
        let candidates = found.map(\.candidate)
        let order = Array(candidates.indices)
        let ask = SpokenMedia.ambiguous(candidates, ranked: order, name: reading.name, artist: reading.artist)
        return ask.isEmpty ? .play(found) : .ask(ask.map { found[$0] })
    }

    static func matches(for readings: [MediaRequest], limit: Int = 6) async -> [ResolvedMedia] {
        var best: (score: Int, found: [ResolvedMedia])?
        for reading in readings where !reading.name.isEmpty || !reading.artist.isEmpty || readings.count == 1 {
            let found = await ranked(reading)
            guard let first = found.first else { continue }
            let score = SpokenMedia.score(first.candidate, name: reading.name, artist: reading.artist)
            if score > best?.score ?? .min { best = (score, found) }
        }
        return Array((best?.found ?? []).prefix(limit))
    }

    private static func bestReading(of readings: [MediaRequest], for first: ResolvedMedia) -> MediaRequest {
        readings.max {
            SpokenMedia.score(first.candidate, name: $0.name, artist: $0.artist)
                < SpokenMedia.score(first.candidate, name: $1.name, artist: $1.artist)
        } ?? MediaRequest(name: first.title)
    }

    static func miss(for readings: [MediaRequest]) async -> MediaMiss {
        let model = AppModel.shared
        if model.isDemoLaunch { return .sampleData }
        let name = readings.first.map { [$0.name, $0.artist].filter { !$0.isEmpty }.joined(separator: " ") } ?? ""
        let hasOwnMusic = model.yourMusic.hasMusic
        switch MusicAuthorization.currentStatus {
        case .authorized:
            break
        case .notDetermined where !hasOwnMusic:
            return .needsAccess(neverAsked: true)
        default:
            if !hasOwnMusic { return .needsAccess(neverAsked: false) }
            return .notFound(name)
        }
        if !model.yourMusic.network.isOnline { return .offline }
        if await cannotPlayCatalog() { return .needsSubscription }
        return .notFound(name)
    }

    /// Whether the other source is there to look in: Apple Music allowed, and music of your
    /// own. Not only with Quick Switch on: asking for something by name is reason enough.
    static func canSwitch(_ model: AppModel) -> Bool {
        let appleMusic = MusicAuthorization.currentStatus == .authorized
        return appleMusic && model.yourMusic.hasMusic
    }

    // MARK: - Ranking

    /// Siri asks for one request several times over, once for each kind of thing it could
    /// be, and again as it plays: each request is looked up once, and the answer kept a moment.
    private static var recent: [MediaRequest: (at: Date, task: Task<[ResolvedMedia], Never>)] = [:]
    private static let keepFor: TimeInterval = 20

    private static func ranked(_ request: MediaRequest) async -> [ResolvedMedia] {
        let now = Date.now
        recent = recent.filter { now.timeIntervalSince($0.value.at) < keepFor }
        if let kept = recent[request] { return await kept.task.value }
        let task = Task { await lookUp(request) }
        recent[request] = (now, task)
        return await task.value
    }

    private static func lookUp(_ request: MediaRequest) async -> [ResolvedMedia] {
        let model = AppModel.shared
        guard !model.isDemoLaunch else { return [] }
        let (spokenName, spokenSource) = SpokenMedia.source(in: request.name)
        let name = spokenName
        let artist = SpokenMedia.source(in: request.artist).name
        let asked = request.source ?? spokenSource.flatMap { MusicSource(rawValue: $0.rawValue) }
        let suffix = asked.map { "@\($0.rawValue)" } ?? ""

        if name.isEmpty, artist.isEmpty {
            if let genre = request.genres.lazy.compactMap(SpokenMedia.genre(named:)).first {
                return [ResolvedMedia(identifier: "genre\(suffix):\(genre.id)", title: genre.title, kind: .genre)]
            }
            // "Play something chill": one of Tracks' moods.
            if let mood = request.moods.lazy.compactMap(SpokenMedia.mood(in:)).compactMap(PlayChoice.init(rawValue:)).first {
                return [tracksItem(mood, suffix: suffix)]
            }
            // "Play music", or "Play Apple Music": Tracks Radio, which always has something,
            // or this hour's mix with it off.
            return [tracksItem(PlayPreferences.isTracksRadioOn ? .station : .forYou, suffix: suffix)]
        }
        if artist.isEmpty, request.kind == .anything || request.kind == .station,
           let choice = SpokenMedia.choice(named: name).flatMap(PlayChoice.init(rawValue:)) {
            return [tracksItem(choice, suffix: suffix)]
        }
        if artist.isEmpty, request.kind == .genre || request.kind == .anything || request.kind == .station,
           let genre = SpokenMedia.genre(named: name) {
            return [ResolvedMedia(identifier: "genre\(suffix):\(genre.id)", title: genre.title, kind: .genre)]
        }

        if asked != .appleMusic, !model.isDemoLaunch { model.prepareYourMusic() }
        let current = SpokenMedia.Source(rawValue: model.musicSource.rawValue) ?? .appleMusic
        let order = SpokenMedia.order(
            current: current,
            asked: asked.flatMap { SpokenMedia.Source(rawValue: $0.rawValue) },
            otherAvailable: canSwitch(model)
        )
        var found: [ResolvedMedia] = []
        for source in order {
            switch source {
            case .yourMusic: found += localItems(name: name, artist: artist, kind: request.kind)
            case .appleMusic: found += await appleMusicItems(name: name, artist: artist, kind: request.kind)
            }
            if found.contains(where: { isClose($0, name: name, artist: artist) }) { break }
        }
        return rank(unique(found), name: name, artist: artist)
    }

    private static func isClose(_ item: ResolvedMedia, name: String, artist: String) -> Bool {
        let kind = item.candidate.kind
        let perfect = SpokenMedia.Candidate(title: kind == .artist && name.isEmpty ? artist : name, artist: artist, kind: kind)
        return SpokenMedia.score(item.candidate, name: name, artist: artist) >= SpokenMedia.score(perfect, name: name, artist: artist)
    }

    private static func rank(_ found: [ResolvedMedia], name: String, artist: String) -> [ResolvedMedia] {
        SpokenMedia.rank(found.map(\.candidate), name: name, artist: artist).map { found[$0] }
    }

    private static func unique(_ found: [ResolvedMedia]) -> [ResolvedMedia] {
        var seen = Set<String>()
        return found.filter { item in
            let key = "\(item.kind.rawValue)\u{1F}\(SongIdentity.key(title: item.title, artistName: item.artist ?? ""))"
            return seen.insert(key).inserted
        }
    }

    // MARK: - Playing

    /// Plays what an identifier names. Returns what went wrong, if anything.
    static func play(_ identifier: String, shuffled: Bool = false, placement: MediaPlacement = .now) async -> PlayerProblem? {
        let model = AppModel.shared
        guard !model.isDemoLaunch else { return .nothingToPlay }
        let parts = identifier.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return .nothingToPlay }
        let kindParts = parts[0].split(separator: "@", maxSplits: 1).map(String.init)
        let kind = kindParts[0]
        let value = parts[1]

        // Where it plays from: a match says so itself; a mix or genre asked for from a
        // source, that one; otherwise whichever is on.
        let source: MusicSource = switch kind {
        case "local-track", "local-album", "local-artist", "local-playlist": .yourMusic
        case "song", "album", "artist", "playlist", "station": .appleMusic
        default: kindParts.count == 2 ? MusicSource(rawValue: kindParts[1]) ?? model.musicSource : model.musicSource
        }
        // No prompt from the background, where no one would see it. Your own music needs none.
        guard source == .yourMusic || MusicAuthorization.currentStatus == .authorized else { return .accessDenied }
        if source == .yourMusic { model.prepareYourMusic() }
        // Your own music's match has to still be there before anything changes.
        if kind.hasPrefix("local-") {
            guard !localTracks(kind: kind, value: value).isEmpty else { return .notInYourMusic }
        }
        if source != model.musicSource {
            // Live, as Quick Switch does: what's playing carries on until this starts.
            model.switchSource(to: source)
        }
        await model.prepareForPlaying()
        let player = model.player
        let id = MusicItemID(value)
        defer { player.problem = nil }

        func start(_ request: PlayRequest, _ context: PlayContext, shuffled: Bool = false) async {
            switch placement {
            case .now: await player.start(request, from: context, shuffled: shuffled)
            case .next, .later: player.enqueue(request, next: placement == .next, title: context.title)
            }
        }

        switch kind {
        case "local-track":
            let index = model.yourMusic.index
            guard let track = index.track(id: value) else { return .notInYourMusic }
            if placement != .now {
                await start(.local([track]), .songs(track.title))
                break
            }
            // The song, then the rest of its artist's, so the music doesn't stop after one.
            let more = index.artist(id: track.artistKey)?.tracks
                .filter { $0.id != track.id && model.yourMusic.isPlayable($0) }
                .shuffled() ?? []
            await start(.local([track] + more), .songs(track.title))
        case "local-album":
            guard let album = model.yourMusic.index.album(id: value) else { return .notInYourMusic }
            await start(.local(localTracks(kind: kind, value: value)), PlayContext(kind: .album, title: album.title), shuffled: shuffled)
        case "local-artist":
            guard let artist = model.yourMusic.index.artist(id: value) else { return .notInYourMusic }
            await start(.local(localTracks(kind: kind, value: value)), PlayContext(kind: .artist, title: artist.name), shuffled: true)
        case "local-playlist":
            guard let playlist = UUID(uuidString: value).flatMap(model.yourMusic.playlists.playlist(id:)) else { return .notInYourMusic }
            await start(.local(localTracks(kind: kind, value: value)), PlayContext(kind: .playlist, title: playlist.name), shuffled: shuffled)
        case "tracks":
            guard let choice = PlayChoice(rawValue: value) else { return .nothingToPlay }
            await TracksPlayback.start(choice, model: model)
        case "genre":
            guard let genre = MusicGenre.all.first(where: { $0.id == value }) else { return .nothingToPlay }
            await GenrePlayback.start(genre, model: model)
        case "song":
            guard let song = await song(id) else { return .nothingToPlay }
            if placement != .now {
                await start(.songs([song]), .songs(song.title))
                break
            }
            // The song, then more by the same artist, so the music doesn't stop after one.
            await start(.songs(await songAndMore(song)), .songs(song.title))
        case "album":
            guard let album = await album(id) else { return .nothingToPlay }
            await start(.album(album), PlayContext(kind: .album, title: album.title), shuffled: shuffled)
        case "playlist":
            guard let playlist = await libraryOrCatalogPlaylist(id) else { return .nothingToPlay }
            await start(.playlist(playlist), PlayContext(kind: .playlist, title: playlist.name), shuffled: shuffled)
        case "artist":
            guard let artist = await artist(id),
                  let top = try? await artist.with([.topSongs]).topSongs, !top.isEmpty
            else { return .nothingToPlay }
            let songs = PlayPreferences.versions(of: Array(top))
            await start(.songs(songs), PlayContext(kind: .artist, title: artist.name), shuffled: shuffled)
        case "station":
            guard let station = await station(id) else { return .nothingToPlay }
            await start(.station(station), PlayContext(kind: .station, title: station.name))
        default:
            return .nothingToPlay
        }
        // Answered to whoever asked, so it isn't shown again the next time the app opens: the
        // defer above clears it once this has been read.
        return player.problem
    }

    private static func localTracks(kind: String, value: String) -> [LocalTrack] {
        let music = AppModel.shared.yourMusic
        let tracks: [LocalTrack] = switch kind {
        case "local-track": music.index.track(id: value).map { [$0] } ?? []
        case "local-album": music.index.album(id: value)?.tracks ?? []
        case "local-artist": music.index.artist(id: value)?.tracks ?? []
        case "local-playlist":
            UUID(uuidString: value).flatMap(music.playlists.playlist(id:)).map {
                let feed = AppModel.shared.playFeed
                return music.songs(in: $0, facts: feed.facts, factsRevision: feed.builtRevision)
            } ?? []
        default: []
        }
        return tracks.filter(music.isPlayable)
    }

    /// "Play more like this": steers a live mix toward the song on, or starts Tracks Radio
    /// when nothing live is playing.
    static func playMoreLikeThis() async -> PlayerProblem? {
        let model = AppModel.shared
        guard !model.isDemoLaunch else { return .nothingToPlay }
        let player = model.player
        if player.canAskForMoreLikeThis {
            player.playMoreLikeThis()
            return nil
        }
        await model.prepareForPlaying()
        defer { player.problem = nil }
        await player.startTracksRadio()
        return player.problem
    }

    /// What an identifier names, for Siri asking again about something it was given earlier.
    static func describe(_ identifier: String) async -> ResolvedMedia? {
        let parts = identifier.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return nil }
        let kind = parts[0].split(separator: "@", maxSplits: 1).first.map(String.init) ?? parts[0]
        let value = parts[1]
        let music = AppModel.shared.yourMusic
        let index = music.index
        let id = MusicItemID(value)
        switch kind {
        case "local-track":
            return index.track(id: value).map { ResolvedMedia(identifier: identifier, title: $0.title, kind: .song, artist: $0.artist, isOwned: true) }
        case "local-album":
            return index.album(id: value).map { ResolvedMedia(identifier: identifier, title: $0.title, kind: .album, artist: $0.artist, isOwned: true) }
        case "local-artist":
            return index.artist(id: value).map { ResolvedMedia(identifier: identifier, title: $0.name, kind: .artist, isOwned: true) }
        case "local-playlist":
            return UUID(uuidString: value).flatMap(music.playlists.playlist(id:)).map {
                ResolvedMedia(identifier: identifier, title: $0.name, kind: .playlist, isOwned: true)
            }
        case "tracks":
            return PlayChoice(rawValue: value).map {
                ResolvedMedia(identifier: identifier, title: tracksItem($0, suffix: "").title, kind: .station, isLive: $0 == .radio)
            }
        case "genre":
            return MusicGenre.all.first { $0.id == value }.map { ResolvedMedia(identifier: identifier, title: $0.title, kind: .genre) }
        case "song":
            return await song(id).map { ResolvedMedia(identifier: identifier, title: $0.title, kind: .song, artist: $0.artistName) }
        case "album":
            return await album(id).map { ResolvedMedia(identifier: identifier, title: $0.title, kind: .album, artist: $0.artistName) }
        case "artist":
            return await artist(id).map { ResolvedMedia(identifier: identifier, title: $0.name, kind: .artist) }
        case "playlist":
            return await libraryOrCatalogPlaylist(id).map { ResolvedMedia(identifier: identifier, title: $0.name, kind: .playlist, artist: $0.curatorName) }
        case "station":
            return await station(id).map { ResolvedMedia(identifier: identifier, title: $0.name, kind: .station, isLive: $0.isLive) }
        default:
            return nil
        }
    }

    static func linked(_ urls: [URL]) async -> [ResolvedMedia] {
        var found: [ResolvedMedia] = []
        for url in urls {
            guard let item = AppleMusicURL.item(in: url),
                  let described = await describe("\(item.kind.rawValue):\(item.id)")
            else { continue }
            found.append(described)
        }
        return found
    }

    static var suggestions: [ResolvedMedia] {
        let first: PlayChoice = PlayPreferences.isTracksRadioOn ? .station : .forYou
        return [first, first == .station ? .forYou : .station, .discover].map { tracksItem($0, suffix: "") }
    }

    // MARK: - Finding things

    private static func localItems(name: String, artist: String, kind: MediaKind) -> [ResolvedMedia] {
        let music = AppModel.shared.yourMusic
        let index = music.index
        let facts = AppModel.shared.playFeed.facts
        var found: [ResolvedMedia] = []

        if kind == .playlist || kind == .anything, !name.isEmpty {
            let wanted = StatsCalculator.folded(name)
            found += music.playlists.recent
                .filter { StatsCalculator.folded($0.name).contains(wanted) }
                .prefix(5)
                .map { ResolvedMedia(identifier: "local-playlist:\($0.id.uuidString)", title: $0.name, kind: .playlist, isOwned: true) }
        }
        guard !index.isEmpty else { return found }
        let query = [name, artist].filter { !$0.isEmpty }.joined(separator: " ")
        let results = index.search(query, limit: 6)

        func artistItem(_ match: LocalArtist) -> ResolvedMedia {
            ResolvedMedia(identifier: "local-artist:\(match.id)", title: match.name, kind: .artist, isOwned: true)
        }
        func albumItem(_ match: LocalAlbum) -> ResolvedMedia {
            ResolvedMedia(identifier: "local-album:\(match.id)", title: match.title, kind: .album, artist: match.artist, isOwned: true)
        }
        func trackItem(_ track: LocalTrack) -> ResolvedMedia {
            let plays = facts[SongIdentity.key(title: track.title, artistName: track.artist)]?.plays ?? 0
            return ResolvedMedia(identifier: "local-track:\(track.id)", title: track.title, kind: .song, artist: track.artist, isOwned: true, plays: plays)
        }
        switch kind {
        case .artist:
            found += index.search(artist.isEmpty ? name : artist, limit: 3).artists.map(artistItem)
        case .album:
            found += results.albums.map(albumItem)
        case .playlist, .station, .genre:
            break
        case .song:
            found += results.tracks.filter(music.isPlayable).map(trackItem)
        case .anything:
            found += results.tracks.filter(music.isPlayable).map(trackItem)
            found += results.albums.map(albumItem)
            found += results.artists.map(artistItem)
        }
        return found
    }

    private static func appleMusicItems(name: String, artist: String, kind: MediaKind) async -> [ResolvedMedia] {
        guard MusicAuthorization.currentStatus == .authorized else { return [] }
        let both = [name, artist].filter { !$0.isEmpty }.joined(separator: " ")
        async let owned = libraryItems(term: both, playlistName: name, kind: kind)
        var catalog: [ResolvedMedia] = []
        if AppModel.shared.yourMusic.network.isOnline {
            let unplayable = await cannotPlayCatalog()
            if !unplayable { catalog = await catalogItems(term: both, name: name, artist: artist, kind: kind) }
        }
        return await owned + catalog
    }

    private static func libraryItems(term: String, playlistName: String, kind: MediaKind) async -> [ResolvedMedia] {
        let facts = AppModel.shared.playFeed.facts
        switch kind {
        case .playlist:
            return await library(Playlist.self, playlistName.isEmpty ? term : playlistName).map {
                ResolvedMedia(identifier: "playlist:\($0.id.rawValue)", title: $0.name, kind: .playlist, isOwned: true)
            }
        case .album:
            return await library(Album.self, term).map(ownedAlbum)
        case .song, .anything:
            async let songs = library(Song.self, term)
            async let albums = kind == .anything ? library(Album.self, term) : []
            let ownedSongs = await songs.map {
                let plays = facts[SongIdentity.key(title: $0.title, artistName: $0.artistName)]?.plays ?? 0
                return ResolvedMedia(identifier: "song:\($0.id.rawValue)", title: $0.title, kind: .song, artist: $0.artistName, isOwned: true, plays: plays)
            }
            return ownedSongs + (await albums).map(ownedAlbum)
        case .artist, .station, .genre:
            return []
        }
    }

    private static func ownedAlbum(_ album: Album) -> ResolvedMedia {
        ResolvedMedia(identifier: "album:\(album.id.rawValue)", title: album.title, kind: .album, artist: album.artistName, isOwned: true)
    }

    private static func catalogItems(term: String, name: String, artist: String, kind: MediaKind) async -> [ResolvedMedia] {
        let feed = AppModel.shared.playFeed
        let types: [any MusicCatalogSearchable.Type] = switch kind {
        case .song: [Song.self]
        case .album: [Album.self]
        case .artist: [Artist.self]
        case .playlist: [Playlist.self]
        case .station: [MusicKit.Station.self]
        case .anything: [Song.self, Album.self, Artist.self]
        case .genre: []
        }
        guard !types.isEmpty else { return [] }
        // Only an artist named: their songs, not a song with their name in it.
        let searchTerm = kind == .artist || (name.isEmpty && !artist.isEmpty) ? (artist.isEmpty ? name : artist) : term
        guard let response = await catalogSearch(searchTerm, types: kind == .anything && name.isEmpty ? [Artist.self] : types) else { return [] }

        let songs = PlayPreferences.versions(of: Array(response.songs)).map {
            let plays = feed.facts[SongIdentity.key(title: $0.title, artistName: $0.artistName)]?.plays ?? 0
            return ResolvedMedia(identifier: "song:\($0.id.rawValue)", title: $0.title, kind: .song, artist: $0.artistName, plays: plays)
        }
        let albums = PlayPreferences.versions(of: Array(response.albums)).map {
            ResolvedMedia(identifier: "album:\($0.id.rawValue)", title: $0.title, kind: .album, artist: $0.artistName)
        }
        let artists = response.artists.map {
            ResolvedMedia(identifier: "artist:\($0.id.rawValue)", title: $0.name, kind: .artist, plays: feed.heardArtists.contains(StatsCalculator.folded($0.name)) ? 1 : 0)
        }
        let playlists = response.playlists.map {
            ResolvedMedia(identifier: "playlist:\($0.id.rawValue)", title: $0.name, kind: .playlist, artist: $0.curatorName)
        }
        let stations = response.stations.map {
            ResolvedMedia(identifier: "station:\($0.id.rawValue)", title: $0.name, kind: .station, isLive: $0.isLive)
        }
        // Songs first, so a song called exactly what was said beats the artist of that name.
        return songs + albums + artists + playlists + stations
    }

    private static func catalogSearch(_ term: String, types: [any MusicCatalogSearchable.Type]) async -> MusicCatalogSearchResponse? {
        let term = term.trimmingCharacters(in: .whitespaces)
        guard !term.isEmpty else { return nil }
        var request = MusicCatalogSearchRequest(term: term, types: types)
        request.limit = 5
        return try? await request.response()
    }

    private static func library<Item: MusicLibraryRequestable>(_ type: Item.Type, _ term: String) async -> [Item] {
        let term = term.trimmingCharacters(in: .whitespaces)
        guard !term.isEmpty else { return [] }
        var request = MusicLibraryRequest<Item>()
        request.filter(text: term)
        request.limit = 5
        return (try? await request.response().items).map(Array.init) ?? []
    }

    /// Whether the account is known to be unable to play the catalog. Asked here rather than
    /// read from the Play tab, which may not have run when Siri starts Tracks.
    private static func cannotPlayCatalog() async -> Bool {
        if AppModel.shared.playFeed.cannotPlayCatalog { return true }
        guard let subscription = try? await MusicSubscription.current else { return false }
        return !subscription.canPlayCatalogContent
    }

    static func tracksItem(_ choice: PlayChoice, suffix: String = "") -> ResolvedMedia {
        let title = String(localized: PlayChoice.caseDisplayRepresentations[choice]?.title ?? "Tracks Radio")
        return ResolvedMedia(identifier: "tracks\(suffix):\(choice.rawValue)", title: title, kind: .station, isLive: choice == .radio)
    }

    // MARK: - Looking up by identifier

    static func appleMusicSong(_ identifier: String) async -> Song? {
        if case .song(let song) = await appleMusicItem(identifier) { song } else { nil }
    }

    static func appleMusicItem(_ identifier: String) async -> AppleMusicItem? {
        let parts = identifier.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2, MusicAuthorization.currentStatus == .authorized else { return nil }
        let id = MusicItemID(parts[1])
        switch parts[0] {
        case "song": return await song(id).map(AppleMusicItem.song)
        case "album": return await album(id).map(AppleMusicItem.album)
        case "playlist": return await libraryOrCatalogPlaylist(id).map(AppleMusicItem.playlist)
        default: return nil
        }
    }

    enum AppleMusicItem {
        case song(Song)
        case album(Album)
        case playlist(Playlist)
    }

    private static func song(_ id: MusicItemID) async -> Song? {
        if let found = try? await MusicCatalogResourceRequest<Song>(matching: \.id, equalTo: id).response().items.first { return found }
        var library = MusicLibraryRequest<Song>()
        library.filter(matching: \.id, equalTo: id)
        return try? await library.response().items.first
    }

    private static func album(_ id: MusicItemID) async -> Album? {
        if let found = try? await MusicCatalogResourceRequest<Album>(matching: \.id, equalTo: id).response().items.first { return found }
        var library = MusicLibraryRequest<Album>()
        library.filter(matching: \.id, equalTo: id)
        return try? await library.response().items.first
    }

    private static func artist(_ id: MusicItemID) async -> Artist? {
        try? await MusicCatalogResourceRequest<Artist>(matching: \.id, equalTo: id).response().items.first
    }

    private static func station(_ id: MusicItemID) async -> MusicKit.Station? {
        try? await MusicCatalogResourceRequest<MusicKit.Station>(matching: \.id, equalTo: id).response().items.first
    }

    private static func libraryOrCatalogPlaylist(_ id: MusicItemID) async -> Playlist? {
        var library = MusicLibraryRequest<Playlist>()
        library.filter(matching: \.id, equalTo: id)
        if let found = try? await library.response().items.first { return found }
        return try? await MusicCatalogResourceRequest<Playlist>(matching: \.id, equalTo: id).response().items.first
    }

    /// The song asked for, then the artist's other top songs in a fresh order.
    private static func songAndMore(_ song: Song) async -> [Song] {
        guard let artist = try? await song.with([.artists]).artists?.first,
              let top = try? await artist.with([.topSongs]).topSongs
        else { return [song] }
        let others = PlayPreferences.versions(of: Array(top)).filter {
            ExplicitVersions.versionKey(title: $0.title, artist: $0.artistName)
                != ExplicitVersions.versionKey(title: song.title, artist: song.artistName)
        }
        return [song] + others.shuffled()
    }

    // MARK: - Remembering

    /// Keeps what was asked for with recent searches, so Search in the car and on the phone
    /// shows it, as Music does with what you ask Siri for.
    static func remember(_ asked: [String?]) {
        let term = asked
            .compactMap { $0.map { SpokenMedia.source(in: $0).name } }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        // "Play music" and "something chill" aren't searches.
        guard !term.isEmpty, SpokenMedia.choice(named: term) == nil, SpokenMedia.genre(named: term) == nil else { return }
        let key = MusicSource.current == .yourMusic ? "recentYourMusicSearches" : "recentMusicSearches"
        let defaults = UserDefaults.standard
        defaults.set(RecentSearches.adding(term, to: defaults.string(forKey: key) ?? ""), forKey: key)
    }
}

import AppIntents
import CoreSpotlight
import MediaIntents
import TracksCore

// Siri's own way to play music in an app, on iPhone and Mac alike: "Play Bad Guy in Tracks",
// "Play the album Blue in Tracks next", "Shuffle Phoebe Bridgers in Tracks", "Play my station
// in Tracks". Siri hears the request and asks each kind of entity's query for matches to what
// was said (an `AudioSearch`), offers a choice when several fit, and plays the one picked
// through Play Audio. ``MediaRequestResolver`` answers from the source that's on, then the
// other one, as the iPhone's media intent does.

@AppEntity(schema: .audio.song)
struct SongAudioEntity {
    static let defaultQuery = SongAudioQuery()
    var id: String
    var title: String
    var artistName: String
    var artists: [ArtistAudioEntity]
    var album: AlbumAudioEntity?
    var albumTitle: String?
    var composerName: String?
    var composers: [ArtistAudioEntity]
    var internationalStandardRecordingCode: String?

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(artistName)", image: .init(systemName: "music.note"))
    }

    init(_ found: ResolvedMedia) {
        id = found.identifier
        title = found.title
        artistName = found.artist ?? ""
        artists = []
        composers = []
    }
}

@AppEntity(schema: .audio.album)
struct AlbumAudioEntity {
    static let defaultQuery = AlbumAudioQuery()
    var id: String
    var title: String
    var artistName: String
    var artists: [ArtistAudioEntity]
    var universalProductCode: String?

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(artistName)", image: .init(systemName: "square.stack"))
    }

    init(_ found: ResolvedMedia) {
        id = found.identifier
        title = found.title
        artistName = found.artist ?? ""
        artists = []
    }
}

@AppEntity(schema: .audio.artist)
struct ArtistAudioEntity {
    static let defaultQuery = ArtistAudioQuery()
    var id: String
    var name: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", image: .init(systemName: "music.mic"))
    }

    init(_ found: ResolvedMedia) {
        id = found.identifier
        name = found.title
    }
}

@AppEntity(schema: .audio.playlist)
struct PlaylistAudioEntity: IndexedEntity {
    static let defaultQuery = PlaylistAudioQuery()
    var id: String
    var title: String
    var owner: PlaylistOwner?
    var createdByMe: Bool?
    var curatedForMe: Bool?

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", image: .init(systemName: "music.note.list"))
    }

    init(_ found: ResolvedMedia) {
        id = found.identifier
        title = found.title
        owner = found.artist.map(PlaylistOwner.name)
        createdByMe = found.isOwned && found.artist == nil
        curatedForMe = false
    }
}

@AppEntity(schema: .audio.algorithmicRadioStation)
struct StationAudioEntity: IndexedEntity {
    static let defaultQuery = StationAudioQuery()
    var id: String
    var title: String
    var curatedForMe: Bool?

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", image: .init(systemName: "dot.radiowaves.left.and.right"))
    }

    init(_ found: ResolvedMedia) {
        id = found.identifier
        title = found.title
        // Apple Music's own stations are anyone's; Tracks' mixes are made from your listening.
        curatedForMe = !found.identifier.hasPrefix("station:")
    }
}

@AppEntity(schema: .audio.liveRadioStation)
struct LiveStationAudioEntity {
    static let defaultQuery = LiveStationAudioQuery()
    var id: String
    var title: String
    var providerName: String?

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", image: .init(systemName: "antenna.radiowaves.left.and.right"))
    }

    init(_ found: ResolvedMedia) {
        id = found.identifier
        title = found.title
        providerName = "Apple Music"
    }
}

@UnionValue
enum PlaylistOwner {
    case person(IntentPerson)
    case name(String)
}

@UnionValue
enum AudioItem {
    case song(SongAudioEntity)
    case album(AlbumAudioEntity)
    case artist(ArtistAudioEntity)
    case playlist(PlaylistAudioEntity)
    case station(StationAudioEntity)
    case liveStation(LiveStationAudioEntity)

    var identifier: String {
        switch self {
        case .song(let entity): entity.id
        case .album(let entity): entity.id
        case .artist(let entity): entity.id
        case .playlist(let entity): entity.id
        case .station(let entity): entity.id
        case .liveStation(let entity): entity.id
        }
    }
}

@AppEnum(schema: .audio.queueInsertionLocation)
enum AudioQueueLocation: String, AppEnum {
    case now, next, tail

    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .now: "Now",
        .next: "Next",
        .tail: "Last",
    ]
}

@AppEnum(schema: .audio.playbackAttributes)
enum AudioPlaybackAttribute: String, AppEnum {
    case shuffle, `repeat`

    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .shuffle: "Shuffle",
        .repeat: "Repeat",
    ]
}

@AppEnum(schema: .audio.affinityState)
enum AudioAffinityState: String, AppEnum {
    case like, dislike, unset

    static let caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .like: "Like",
        .dislike: "Dislike",
        .unset: "Unset",
    ]
}

/// Siri may ask an app to get a queue ready before it's played. Tracks starts fast enough
/// without, so this only carries Siri's reference along.
@AppEntity(schema: .audio.warmupAudioQueueResult)
struct WarmupAudioQueueResult: TransientAppEntity {
    var id = UUID().uuidString

    var displayRepresentation: DisplayRepresentation { "Queue" }

    init() {}
}

// MARK: - Intents

@AppIntent(schema: .audio.playAudio)
struct PlayAudioIntent: AudioPlaybackIntent {
    var audioEntity: AudioItem
    var queueLocation: AudioQueueLocation?
    var playbackAttributes: Set<AudioPlaybackAttribute>
    var warmupAudioQueueResult: WarmupAudioQueueResult?

    @MainActor
    func perform() async throws -> some IntentResult {
        let model = AppModel.shared
        guard !model.isDemoLaunch else { throw PlayIntentError.sampleData }
        let placement: MediaPlacement = switch queueLocation {
        case .next: .next
        case .tail: .later
        case .now, nil: .now
        }
        let identifier = audioEntity.identifier
        if let problem = await MediaRequestResolver.play(identifier, shuffled: playbackAttributes.contains(.shuffle), placement: placement) {
            throw PlayIntentError.playing(problem)
        }
        if playbackAttributes.contains(.repeat), placement == .now {
            model.player.setRepeat(.all)
        }
        return .result()
    }
}

@AppIntent(schema: .audio.createStation)
struct CreateStationIntent: AudioPlaybackIntent {
    @MainActor
    func perform() async throws -> some IntentResult {
        guard !AppModel.shared.isDemoLaunch else { throw PlayIntentError.sampleData }
        if let problem = await MediaRequestResolver.playMoreLikeThis() {
            throw PlayIntentError.playing(problem)
        }
        return .result()
    }
}

@AppIntent(schema: .audio.updateAudioAffinity)
struct UpdateAudioAffinityIntent: AppIntent {
    var affinityState: AudioAffinityState
    var target: AudioItem

    @MainActor
    func perform() async throws -> some IntentResult {
        let player = AppModel.shared.player
        guard !AppModel.shared.isDemoLaunch else { throw PlayIntentError.sampleData }
        let named = await MediaRequestResolver.appleMusicSong(target.identifier)
        let isCurrent = named == nil || player.current?.song?.id == named?.id
        let song = named ?? player.current?.song
        switch affinityState {
        case .like:
            if let song {
                player.setFavorite(song, true)
            } else if isCurrent, player.canAskForMoreLikeThis {
                player.playMoreLikeThis()
            } else {
                throw PlayIntentError.problem(String(localized: "Only Apple Music songs can be favorites."))
            }
        case .dislike, .unset:
            if let song, player.isFavorite(song) { player.setFavorite(song, false) }
            if affinityState == .dislike, isCurrent, player.current != nil { player.skipToNext() }
        }
        return .result()
    }
}

@AppIntent(schema: .audio.addToLibrary)
struct AddAudioToLibraryIntent: AppIntent {
    var audioEntity: AudioItem

    @MainActor
    func perform() async throws -> some IntentResult {
        let player = AppModel.shared.player
        guard !AppModel.shared.isDemoLaunch else { throw PlayIntentError.sampleData }
        switch await MediaRequestResolver.appleMusicItem(audioEntity.identifier) {
        case .song(let song): player.addToLibrary(song)
        case .album(let album): player.addToLibrary(album)
        case .playlist(let playlist): player.addToLibrary(playlist)
        case nil:
            guard let song = player.current?.song else {
                throw PlayIntentError.problem(String(localized: "Only Apple Music songs, albums and playlists can be added to your library."))
            }
            player.addToLibrary(song)
        }
        if let problem = player.problem {
            player.problem = nil
            throw PlayIntentError.playing(problem)
        }
        return .result()
    }
}

struct PlayPlaylistIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play Playlist"
    static let description = IntentDescription(
        "Plays one of your playlists, from Tracks or Apple Music.",
        categoryName: "Playback",
        searchKeywords: ["playlist", "shuffle"]
    )

    @Parameter(title: "Playlist")
    var playlist: PlaylistAudioEntity

    @Parameter(title: "Shuffle", default: false)
    var shuffled: Bool

    static var parameterSummary: some ParameterSummary {
        Summary("Play \(\.$playlist)") {
            \.$shuffled
        }
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard !AppModel.shared.isDemoLaunch else { throw PlayIntentError.sampleData }
        if let problem = await MediaRequestResolver.play(playlist.id, shuffled: shuffled) {
            throw PlayIntentError.playing(problem)
        }
        return .result()
    }
}

// MARK: - Queries

/// Finds what Siri names, one kind at a time, and rebuilds what it was given before.
@MainActor
enum AudioLookup {
    static func search(_ search: AudioSearch, for kind: MediaKind) async throws -> [ResolvedMedia] {
        guard !AppModel.shared.isDemoLaunch else { throw PlayIntentError.sampleData }
        let found: [ResolvedMedia]
        switch search.criteria {
        case .searchQuery(let text):
            let readings = MediaRequest.readings(of: text)
                .filter { $0.kind == .anything || $0.kind == kind || (kind == .station && $0.kind == .genre) }
                .map { reading in
                    var reading = reading
                    reading.kind = kind
                    return reading
                }
            if kind == .station, let mood = SpokenMedia.moodRequest(in: text).flatMap(PlayChoice.init(rawValue:)) {
                return [MediaRequestResolver.tracksItem(mood)]
            }
            guard !readings.isEmpty else { return [] }
            found = await MediaRequestResolver.matches(for: readings)
            if found.isEmpty {
                let miss = await MediaRequestResolver.miss(for: readings)
                if !miss.isNotFound { throw PlayIntentError.problem(miss.message) }
            }
        case .url(let urls):
            found = await MediaRequestResolver.linked(urls)
        case .unspecified:
            found = kind == .station ? MediaRequestResolver.suggestions : []
        @unknown default:
            found = []
        }
        return found.filter { item in
            switch kind {
            case .station: (item.kind == .station || item.kind == .genre) && !item.isLive
            default: item.kind == kind
            }
        }
    }

    static func find(_ name: String, kind: MediaKind) async -> [ResolvedMedia] {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !AppModel.shared.isDemoLaunch else { return [] }
        return await MediaRequestResolver.matches(for: MediaRequest.readings(of: name, kind: kind)).filter { $0.kind == kind }
    }

    static func describe(_ identifiers: [String]) async -> [ResolvedMedia] {
        var found: [ResolvedMedia] = []
        for identifier in identifiers {
            if let item = await MediaRequestResolver.describe(identifier) { found.append(item) }
        }
        return found
    }

    static func liveStations(_ search: AudioSearch) async throws -> [ResolvedMedia] {
        guard case .searchQuery(let text) = search.criteria else {
            if case .url(let urls) = search.criteria { return await MediaRequestResolver.linked(urls).filter(\.isLive) }
            return []
        }
        let found = await MediaRequestResolver.matches(for: MediaRequest.readings(of: text, kind: .station))
        return found.filter(\.isLive)
    }
}

struct SongAudioQuery: EntityStringQuery, IntentValueQuery {
    func entities(for identifiers: [String]) async throws -> [SongAudioEntity] {
        await AudioLookup.describe(identifiers).map(SongAudioEntity.init)
    }

    func entities(matching string: String) async throws -> [SongAudioEntity] {
        await AudioLookup.find(string, kind: .song).map(SongAudioEntity.init)
    }

    func values(for search: AudioSearch) async throws -> [SongAudioEntity] {
        try await AudioLookup.search(search, for: .song).map(SongAudioEntity.init)
    }
}

struct AlbumAudioQuery: EntityStringQuery, IntentValueQuery {
    func entities(for identifiers: [String]) async throws -> [AlbumAudioEntity] {
        await AudioLookup.describe(identifiers).map(AlbumAudioEntity.init)
    }

    func entities(matching string: String) async throws -> [AlbumAudioEntity] {
        await AudioLookup.find(string, kind: .album).map(AlbumAudioEntity.init)
    }

    func values(for search: AudioSearch) async throws -> [AlbumAudioEntity] {
        try await AudioLookup.search(search, for: .album).map(AlbumAudioEntity.init)
    }
}

struct ArtistAudioQuery: EntityStringQuery, IntentValueQuery {
    func entities(for identifiers: [String]) async throws -> [ArtistAudioEntity] {
        await AudioLookup.describe(identifiers).map(ArtistAudioEntity.init)
    }

    func entities(matching string: String) async throws -> [ArtistAudioEntity] {
        await AudioLookup.find(string, kind: .artist).map(ArtistAudioEntity.init)
    }

    func values(for search: AudioSearch) async throws -> [ArtistAudioEntity] {
        try await AudioLookup.search(search, for: .artist).map(ArtistAudioEntity.init)
    }
}

struct PlaylistAudioQuery: EntityStringQuery, IntentValueQuery {
    func entities(for identifiers: [String]) async throws -> [PlaylistAudioEntity] {
        await AudioLookup.describe(identifiers).map(PlaylistAudioEntity.init)
    }

    func entities(matching string: String) async throws -> [PlaylistAudioEntity] {
        await AudioLookup.find(string, kind: .playlist).map(PlaylistAudioEntity.init)
    }

    func values(for search: AudioSearch) async throws -> [PlaylistAudioEntity] {
        try await AudioLookup.search(search, for: .playlist).map(PlaylistAudioEntity.init)
    }

    func suggestedEntities() async throws -> [PlaylistAudioEntity] {
        await SiriVocabulary.playlists().map(PlaylistAudioEntity.init)
    }
}

struct StationAudioQuery: EntityStringQuery, IntentValueQuery {
    func entities(for identifiers: [String]) async throws -> [StationAudioEntity] {
        await AudioLookup.describe(identifiers).map(StationAudioEntity.init)
    }

    /// Tracks' mixes, moods and genres by name first, then Apple Music's stations.
    func entities(matching string: String) async throws -> [StationAudioEntity] {
        await AudioLookup.find(string, kind: .station).filter { !$0.isLive }.map(StationAudioEntity.init)
    }

    func values(for search: AudioSearch) async throws -> [StationAudioEntity] {
        try await AudioLookup.search(search, for: .station).map(StationAudioEntity.init)
    }

    /// Tracks' own, for Shortcuts' picker and Siri's suggestions.
    @MainActor
    func suggestedEntities() async throws -> [StationAudioEntity] {
        SiriVocabulary.stations.map(StationAudioEntity.init)
    }
}

struct LiveStationAudioQuery: EntityStringQuery, IntentValueQuery {
    func entities(for identifiers: [String]) async throws -> [LiveStationAudioEntity] {
        await AudioLookup.describe(identifiers).map(LiveStationAudioEntity.init)
    }

    func entities(matching string: String) async throws -> [LiveStationAudioEntity] {
        await AudioLookup.find(string, kind: .station).filter(\.isLive).map(LiveStationAudioEntity.init)
    }

    func values(for search: AudioSearch) async throws -> [LiveStationAudioEntity] {
        try await AudioLookup.liveStations(search).map(LiveStationAudioEntity.init)
    }
}

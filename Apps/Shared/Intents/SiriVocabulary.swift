import AppIntents
import CoreSpotlight
import MusicKit
import TracksCore
#if os(iOS)
import Intents
#endif

@MainActor
enum SiriVocabulary {
    /// The most of your playlists named anywhere: plenty for anyone's, few enough that the
    /// shortcut phrases Siri learns stay well within what it takes.
    static let playlistLimit = 50
    static let artistLimit = 200

    static var stations: [ResolvedMedia] {
        PlayChoice.allCases.map { MediaRequestResolver.tracksItem($0) }
    }

    static func playlists(limit: Int = playlistLimit) async -> [ResolvedMedia] {
        let model = AppModel.shared
        guard !model.isDemoLaunch else { return [] }
        let tracks = model.yourMusic.playlists.recent.map {
            ResolvedMedia(identifier: "local-playlist:\($0.id.uuidString)", title: $0.name, kind: .playlist, isOwned: true)
        }
        var appleMusic: [ResolvedMedia] = []
        if MusicAuthorization.currentStatus == .authorized {
            var request = MusicLibraryRequest<Playlist>()
            request.sort(by: \.lastPlayedDate, ascending: false)
            request.limit = limit
            let found = (try? await request.response().items).map(Array.init) ?? []
            appleMusic = found.map {
                ResolvedMedia(identifier: "playlist:\($0.id.rawValue)", title: $0.name, kind: .playlist, isOwned: true)
            }
        }
        var seen = Set<String>()
        return (tracks + appleMusic)
            .filter { seen.insert(StatsCalculator.folded($0.title)).inserted }
            .prefix(limit)
            .map(\.self)
    }

    static func update() async {
        guard !AppModel.shared.isDemoLaunch else { return }
        let playlists = await playlists()
        let spotlight = CSSearchableIndex.default()
        try? await spotlight.deleteAppEntities(ofType: PlaylistAudioEntity.self)
        try? await spotlight.deleteAppEntities(ofType: StationAudioEntity.self)
        try? await spotlight.indexAppEntities(playlists.map(PlaylistAudioEntity.init), priority: 3)
        try? await spotlight.indexAppEntities(stations.map(StationAudioEntity.init), priority: 2)
        #if os(iOS)
        let vocabulary = INVocabulary.shared()
        vocabulary.setVocabularyStrings(NSOrderedSet(array: playlists.map(\.title)), of: .mediaPlaylistTitle)
        let artists = AppModel.shared.yourMusic.index.artists.prefix(artistLimit).map(\.name)
        vocabulary.setVocabularyStrings(NSOrderedSet(array: Array(artists)), of: .mediaMusicArtistName)
        #endif
        TracksShortcuts.updateAppShortcutParameters()
    }
}

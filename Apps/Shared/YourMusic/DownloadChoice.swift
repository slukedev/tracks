import Foundation
import Observation
import MusicKit
import TracksCore
#if os(iOS)
import MediaPlayer
#endif

enum DownloadSubject: Hashable {
    case song(Song)
    case album(Album)
    case artist(String)

    var key: String {
        switch self {
        case .song(let song): "song:" + SongIdentity.key(title: song.title, artistName: song.artistName)
        case .album(let album): GetMore.albumKey(title: album.title, artist: album.artistName)
        case .artist(let name): "artist:" + StatsCalculator.folded(name)
        }
    }

    var title: String {
        switch self {
        case .song(let song): song.title
        case .album(let album): album.title
        case .artist(let name): name
        }
    }

    var url: URL? {
        switch self {
        case .song(let song): song.url
        case .album(let album): album.url
        case .artist: nil
        }
    }
}

/// Apple Music adds to the library; MusicKit can't download, so Music does that, opened at the
/// item. Your Music gets it the way Get does, from your server or Lidarr.
@MainActor
@Observable
final class DownloadChoice {
    static let isOnKey = "downloadChoiceIsOn"
    static let defaultKey = "downloadChoiceDefault"
    static let lastKey = "downloadChoiceLast"

    private(set) var apple: [String: AppleMusicHolding] = [:]
    private(set) var followedArtists: Set<String> = []
    private(set) var last: DownloadService? = UserDefaults.standard.string(forKey: DownloadChoice.lastKey).flatMap(DownloadService.init)

    @ObservationIgnored private unowned let music: YourMusic
    @ObservationIgnored private let isDemo: Bool
    @ObservationIgnored private var lookups: [String: Task<Void, Never>] = [:]

    init(music: YourMusic, isDemo: Bool) {
        self.music = music
        self.isDemo = isDemo
        if isDemo { last = nil }
    }

    // MARK: - What each can do

    func plan(for subject: DownloadSubject, cannotPlayCatalog: Bool, preferred: DownloadService?) -> DownloadPlan {
        DownloadPlan(
            appleMusic: appleReach(for: subject, cannotPlayCatalog: cannotPlayCatalog),
            appleHolding: apple[subject.key] ?? .unknown,
            yourMusic: serverReach(for: subject),
            serverHolding: serverHolding(of: subject),
            preferred: preferred,
            last: last
        )
    }

    private func appleReach(for subject: DownloadSubject, cannotPlayCatalog: Bool) -> AppleMusicReach {
        if case .artist = subject { return .notInCatalog }
        if isDemo { return .ready }
        guard ![.denied, .restricted].contains(MusicAuthorization.currentStatus), !cannotPlayCatalog else { return .unavailable }
        return .ready
    }

    private func serverReach(for subject: DownloadSubject) -> YourMusicReach {
        let lidarr = music.lidarr
        if case .artist = subject {
            guard let lidarr, lidarr.isSetUp else { return .notSetUp }
            return lidarr.isConnected ? .ready : .offline
        }
        let hasServers = !music.servers.servers.isEmpty
        let hasLidarr = lidarr?.isSetUp == true
        guard hasServers || hasLidarr || isDemo else { return .notSetUp }
        if music.getMore.canGet { return .ready }
        let serversOffline = hasServers && music.servers.onlineServers.isEmpty
        let lidarrOffline = hasLidarr && lidarr?.isConnected != true
        return serversOffline || lidarrOffline ? .offline : .cannotGet
    }

    private func serverHolding(of subject: DownloadSubject) -> YourMusicHolding {
        let getMore = music.getMore
        switch subject {
        case .song(let song):
            return Self.holding(getMore.state(ofSong: SongIdentity.key(title: song.title, artistName: song.artistName)))
        case .album(let album):
            let held = Self.yourAlbum(album)
            let state = getMore.state(of: held)
            if state == .idle, getMore.state(ofAlbum: held) == .partlyOwned { return .partial }
            return Self.holding(state)
        case .artist(let name):
            return music.lidarr?.artist(named: name) != nil || followedArtists.contains(subject.key) ? .held : .missing
        }
    }

    private static func holding(_ state: GetState) -> YourMusicHolding {
        switch state {
        case .idle: .missing
        case .queued: .fetching(nil)
        case .progress(let fraction): .fetching(fraction)
        case .done, .owned: .held
        case .failed(let reason): .failed(reason)
        }
    }

    private static func yourAlbum(_ album: Album) -> GetMore.Album {
        GetMore.Album(title: album.title, artist: album.artistName, songCount: album.trackCount > 0 ? album.trackCount : nil)
    }

    // MARK: - Apple Music's library

    func lookUp(_ subject: DownloadSubject, again: Bool = false) {
        let key = subject.key
        guard again || (apple[key] == nil && lookups[key] == nil) else { return }
        if isDemo {
            if apple[key] == nil { apple[key] = .missing }
            return
        }
        guard MusicAuthorization.currentStatus == .authorized else { return }
        lookups[key] = Task { [weak self] in
            let found = await Self.libraryHolding(of: subject)
            guard let self else { return }
            lookups[key] = nil
            if case .adding = apple[key] { return }
            apple[key] = found
        }
    }

    private static func libraryHolding(of subject: DownloadSubject) async -> AppleMusicHolding {
        switch subject {
        case .song(let song):
            var request = MusicLibraryRequest<Song>()
            request.filter(matching: \.title, equalTo: song.title)
            let artist = StatsCalculator.folded(song.artistName)
            guard let items = try? await request.response().items else { return .unknown }
            guard items.contains(where: { StatsCalculator.folded($0.artistName) == artist }) else { return .missing }
            #if os(iOS)
            return isOnDevice(title: song.title, artist: song.artistName, property: MPMediaItemPropertyTitle) ? .downloaded : .inLibrary
            #else
            return .inLibrary
            #endif
        case .album(let album):
            var request = MusicLibraryRequest<Album>()
            request.filter(matching: \.title, equalTo: album.title)
            let artist = StatsCalculator.folded(album.artistName)
            guard let items = try? await request.response().items else { return .unknown }
            guard items.contains(where: { StatsCalculator.folded($0.artistName) == artist }) else { return .missing }
            #if os(iOS)
            return isOnDevice(title: album.title, artist: album.artistName, property: MPMediaItemPropertyAlbumTitle) ? .downloaded : .inLibrary
            #else
            return .inLibrary
            #endif
        case .artist:
            return .missing
        }
    }

    #if os(iOS)
    private static func isOnDevice(title: String, artist: String, property: String) -> Bool {
        let query = MPMediaQuery.songs()
        query.addFilterPredicate(MPMediaPropertyPredicate(value: title, forProperty: property))
        let folded = StatsCalculator.folded(artist)
        let items = (query.items ?? []).filter { item in
            [item.artist, item.albumArtist].contains { $0.map(StatsCalculator.folded) == folded }
        }
        return !items.isEmpty && items.allSatisfy { !$0.isCloudItem }
    }
    #endif

    // MARK: - Keeping

    func keep(_ subject: DownloadSubject, with service: DownloadService, openURL: (URL) -> Void) async -> String? {
        remember(service)
        switch service {
        case .appleMusic: return await keepInAppleMusic(subject, openURL: openURL)
        case .yourMusic: return await keepInYourMusic(subject)
        }
    }

    private func remember(_ service: DownloadService) {
        last = service
        guard !isDemo else { return }
        UserDefaults.standard.set(service.rawValue, forKey: Self.lastKey)
    }

    private func keepInAppleMusic(_ subject: DownloadSubject, openURL: (URL) -> Void) async -> String? {
        let key = subject.key
        switch apple[key] {
        case .inLibrary, .downloaded:
            openURL(Self.musicURL(for: subject))
            return nil
        case .adding:
            return nil
        default:
            break
        }
        apple[key] = .adding
        do {
            if isDemo {
                try await Task.sleep(for: .milliseconds(600))
            } else {
                try await MusicKitPlayerEngine.checkAccess()
                try await Self.addToLibrary(subject)
            }
            apple[key] = .inLibrary
            return String(localized: "Added to Library. Download it in Music.")
        } catch {
            let reason = String(localized: "Apple Music couldn't add this. Try again in a moment.")
            apple[key] = .failed(reason)
            return reason
        }
    }

    private static func addToLibrary(_ subject: DownloadSubject) async throws {
        #if os(iOS)
        switch subject {
        case .song(let song): try await MusicLibrary.shared.add(song)
        case .album(let album): try await MusicLibrary.shared.add(album)
        case .artist: break
        }
        #else
        let request: URLRequest?
        switch subject {
        case .song(let song): request = AppleMusicLibraryAdd.request(adding: [song.id.rawValue], as: .songs)
        case .album(let album): request = AppleMusicLibraryAdd.request(adding: [album.id.rawValue], as: .albums)
        case .artist: request = nil
        }
        guard let request else { return }
        let response = try await MusicDataRequest(urlRequest: request).response()
        guard (200..<300).contains(response.urlResponse.statusCode) else { throw URLError(.badServerResponse) }
        #endif
    }

    /// The item in Music, which has the Download button MusicKit lacks. On the Mac its web link
    /// would open a browser, so it's handed to Music by its own scheme.
    static func musicURL(for subject: DownloadSubject) -> URL {
        let fallback = URL(string: "music://")!
        guard let url = subject.url else { return fallback }
        #if os(macOS)
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components?.scheme = "music"
        return components?.url ?? fallback
        #else
        return url
        #endif
    }

    private func keepInYourMusic(_ subject: DownloadSubject) async -> String? {
        let getMore = music.getMore
        switch subject {
        case .song(let song):
            return await getMore.get(songTitled: song.title, artist: song.artistName, album: song.albumTitle)
        case .album(let album):
            let held = Self.yourAlbum(album)
            return await getMore.get(held)
        case .artist(let name):
            guard let lidarr = music.lidarr else { return nil }
            let message = await lidarr.follow(artistNamed: name)
            followedArtists.insert(subject.key)
            return message
        }
    }
}

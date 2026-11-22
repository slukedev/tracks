import Foundation
import Observation
import TracksCore

/// Where getting something you don't own stands, for its Get button.
enum GetState: Equatable {
    /// It can be got.
    case idle
    /// Asked for, and on its way, with no telling how far along.
    case queued
    /// Coming in: a whole-percent share from 0 to 1.
    case progress(Double)
    /// Arrived just now.
    case done
    case failed(String)
    /// Yours already: nothing to get.
    case owned
}

/// Getting music you don't own: whether anything can, what's been asked for and how far
/// along it is, and what to get next. Music can be got from a server that finds songs it
/// doesn't have, from a Trackside server that gets songs when asked, or from Lidarr. With
/// none of them, nothing here offers anything and the places that would show it don't
/// appear.
///
/// The rules (what you own of an album, Get More's order, which way to get something) are
/// the shared engine's, through ``OwnedMusic``, ``GetMoreRanking`` and ``FetchAbility``.
@MainActor
@Observable
final class GetMore {
    /// An album you could get, from a server that finds songs or from Lidarr.
    nonisolated struct Album: Sendable, Hashable, Identifiable {
        let title: String
        let artist: String
        /// Where a server has it, to open its page and fetch its songs.
        var serverID: String?
        var albumID: String?
        var artwork: LocalTrack.Artwork?
        /// Its cover from outside your servers, where Lidarr has one.
        var coverURL: String?
        var songCount: Int?
        var year: Int?

        var id: String { GetMore.albumKey(title: title, artist: artist) }
    }

    /// A song a Trackside server could get for you. It can't be played until it's got.
    nonisolated struct Offer: Sendable, Hashable, Identifiable {
        let serverID: String
        let candidate: TracksideCandidate
        var identity: String { SongIdentity.key(title: candidate.title, artistName: candidate.artist) }
        var id: String { GetMore.songKey(identity) }
    }

    /// An album with what you own of it.
    nonisolated struct HeldAlbum: Sendable, Hashable, Identifiable {
        let album: Album
        let state: AlbumHoldings.State
        var id: String { album.id }
    }

    /// Something Get More suggests.
    struct Suggestion: Identifiable {
        enum Item {
            case song(LocalTrack)
            case offer(Offer)
            case album(HeldAlbum)
        }

        let item: Item
        let reason: GetMoreRanking.Reason

        var id: String {
            switch item {
            case .song(let track): GetMore.songKey(track.identity)
            case .offer(let offer): offer.id
            case .album(let held): held.id
            }
        }
    }

    /// An artist's music you don't own yet, for their page.
    struct ArtistGets {
        var albums: [HeldAlbum] = []
        var songs: [LocalTrack] = []
        /// Songs of theirs a Trackside server could get.
        var offers: [Offer] = []
        var isEmpty: Bool { albums.isEmpty && songs.isEmpty && offers.isEmpty }
    }

    /// An album's songs you don't have, for its page.
    struct AlbumGaps {
        let album: Album
        let holdings: AlbumHoldings
        let missing: [MissingSong]
    }

    struct MissingSong: Identifiable {
        let title: String
        let artist: String
        let number: Int?
        /// The copy a server that finds songs has, to get on its own.
        let found: LocalTrack?
        /// Or the song a Trackside server could get.
        var offer: Offer?
        var canGet: Bool { found != nil || offer != nil }
        var id: String { SongIdentity.key(title: title, artistName: artist) + "\u{1F}\(number ?? 0)" }
        var identity: String { SongIdentity.key(title: title, artistName: artist) }
    }

    /// Something asked for and not settled.
    struct Request: Equatable {
        enum Status: Equatable {
            case asking, waiting
            case failed(String)
        }

        let route: FetchRoute
        /// The songs it waits for, by identity. Empty for an album Lidarr gets, which is
        /// waited for whole.
        var songs: Set<String>
        let title: String
        let artist: String
        let isAlbum: Bool
        var status: Status
        var doneAt: Date?
        /// Songs of the album you had when you asked, so an album you owned part of counts as
        /// arrived only once more of it is in.
        var ownedAtAsk = 0
        /// What a Trackside server calls the request, to follow it by.
        var acquisition: AcquisitionRef?
    }

    struct AcquisitionRef: Equatable {
        let serverID: String
        let id: String
    }

    /// Servers seen to find songs they don't have. Kept between launches.
    private(set) var findingServers: Set<String> = Set(UserDefaults.standard.stringArray(forKey: GetMore.findingKey) ?? [])
    /// Your library's songs, to judge albums by. Built off the main actor with each new index.
    private(set) var owned = OwnedMusic.empty
    private(set) var requests: [String: Request] = [:]
    private(set) var suggestions: [Suggestion] = []
    /// Still finding the first suggestions, so the section holds its shape.
    private(set) var isLoadingSuggestions = false

    @ObservationIgnored private unowned let music: YourMusic
    @ObservationIgnored private let isDemo: Bool
    @ObservationIgnored private var ownedRevision = -1
    @ObservationIgnored private var building: Task<Void, Never>?
    @ObservationIgnored private var suggestionsLoadedAt: Date?
    @ObservationIgnored private var suggestionsLoad: Task<Void, Never>?
    @ObservationIgnored private var lidarrWatch: Task<Void, Never>?
    /// Asked at most a few at once, then one every few seconds, as For You's are.
    @ObservationIgnored private var budget = RequestBudget(capacity: 3, interval: 6)
    @ObservationIgnored private var serverAlbumsByArtist: [String: (at: Date, albums: [Album])] = [:]
    @ObservationIgnored private var similarByArtist: [String: (at: Date, names: [String])] = [:]
    @ObservationIgnored private var artistCache: [String: (at: Date, gets: ArtistGets)] = [:]
    @ObservationIgnored private var gapsCache: [String: (at: Date, gaps: AlbumGaps?)] = [:]
    @ObservationIgnored private var offersByQuery: [String: (at: Date, offers: [Offer])] = [:]

    private static let findingKey = "musicServersThatFindSongs"
    /// How long a lookup stands.
    private static let lookupsLast: TimeInterval = 6 * 60 * 60
    /// How long an arrival shows as done before it's simply yours.
    private static let doneShows: Duration = .seconds(4)
    private static var serverUnreachable: String { String(localized: "Couldn't reach your server. Try again once it's online.") }

    init(music: YourMusic, isDemo: Bool) {
        self.music = music
        self.isDemo = isDemo
        #if DEBUG
        if isDemo { findingServers = ["demo"] }
        #endif
    }

    // MARK: - What can fetch

    var ability: FetchAbility {
        FetchAbility(
            serverFinds: findingServerOnline != nil || requestingServerOnline != nil,
            lidarr: music.lidarr?.isConnected == true
        )
    }

    /// Whether anything can fetch right now: the places that offer to get music show only then.
    var canGet: Bool { ability.canGet }

    /// The first server you can reach that finds songs it doesn't have.
    var findingServerOnline: SubsonicServer? {
        music.servers.onlineServers.first { finds(on: $0.id) }
    }

    /// The first Trackside server you can reach that gets songs for you, which it says
    /// outright rather than being told apart by its searches.
    var requestingServerOnline: SubsonicServer? { music.servers.requestingServer }

    /// Whether a server's search answers with songs it doesn't have, to play and then keep.
    /// Never a Trackside server, which only ever answers with what it has.
    func finds(on serverID: String?) -> Bool {
        guard let serverID, !isTrackside(serverID) else { return false }
        return findingServers.contains(serverID)
    }

    private func isTrackside(_ serverID: String) -> Bool {
        music.servers.server(serverID)?.isTrackside == true
    }

    /// Hears a Subsonic server's answer to a search, to learn whether it finds songs it
    /// doesn't have: most of an answer from outside the library it synced. Only once its
    /// library is in the index, or every song would look like a find.
    func noteAnswer(trackIDs: [String], from serverID: String) {
        guard !findingServers.contains(serverID), !isTrackside(serverID), !trackIDs.isEmpty,
              music.servers.catalogs[serverID] != nil,
              music.indexedCatalogRevision == music.servers.catalogRevision
        else { return }
        let outside = trackIDs.count { music.index.track(id: $0) == nil }
        guard FetchAbility.searchShowsFinds(returned: trackIDs.count, outsideLibrary: outside) else { return }
        findingServers.insert(serverID)
        saveFinding()
    }

    /// A server was removed: what it found goes with it.
    func forget(serverID: String) {
        guard findingServers.remove(serverID) != nil else { return }
        saveFinding()
    }

    private func saveFinding() {
        guard !isDemo else { return }
        UserDefaults.standard.set(Array(findingServers), forKey: Self.findingKey)
    }

    // MARK: - Your library

    /// The library changed: what you own is read again, once a burst of changes has settled.
    func libraryChanged() {
        let revision = music.indexRevision
        guard revision != ownedRevision else { return }
        building?.cancel()
        let tracks = music.index.tracks
        building = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            let built = await Self.ownedMusic(from: tracks)
            guard !Task.isCancelled, let self else { return }
            owned = built
            ownedRevision = revision
            settleRequests()
        }
    }

    @concurrent
    nonisolated private static func ownedMusic(from tracks: [LocalTrack]) async -> OwnedMusic {
        OwnedMusic(songs: tracks.map { (title: $0.title, artist: $0.artist) })
    }

    /// What you own of an album, from the songs of it your library has, by its title or once
    /// its edition is set aside.
    func state(ofAlbum album: Album) -> AlbumHoldings.State {
        AlbumHoldings.state(owned: ownedSongs(of: album).count, total: album.songCount)
    }

    /// Your songs of an album: the album of that title by that artist, or one that differs
    /// only by an edition.
    func ownedSongs(of album: Album) -> [LocalTrack] {
        let artistKey = StatsCalculator.folded(album.artist)
        if let exact = music.index.album(id: artistKey + "\u{1F}" + StatsCalculator.folded(album.title)) {
            return exact.tracks
        }
        let key = LidarrClient.albumKey(album.title)
        let artist = music.index.artist(id: artistKey) ?? music.index.artist(id: StatsCalculator.folded(ArtistCredits.lead(of: album.artist)))
        return artist?.albums.first { LidarrClient.albumKey($0.title) == key }?.tracks ?? []
    }

    // MARK: - States

    nonisolated static func songKey(_ identity: String) -> String { "song:" + identity }

    nonisolated static func albumKey(title: String, artist: String) -> String {
        "album:" + StatsCalculator.folded(artist) + "\u{1F}" + LidarrClient.albumKey(title)
    }

    /// Where getting a song stands.
    /// - Parameter track: the copy a server found, if there is one, to follow its server's fetch.
    func state(ofSong identity: String, track: LocalTrack? = nil) -> GetState {
        let request = requests[Self.songKey(identity)]
        if let standing = Self.standing(of: request) { return standing }
        if request?.doneAt != nil { return .done }
        if let mine = music.index.tracks(withIdentity: identity).first {
            if let progress = music.downloads.progress[mine.id] { return .progress(Self.whole(progress)) }
            return request == nil ? .owned : .done
        }
        if let request, let fetching = acquisitionState(of: request) { return fetching }
        if let track, music.servers.isWaitingToKeep(track) { return .queued }
        return request == nil ? .idle : .queued
    }

    func state(of offer: Offer) -> GetState {
        state(ofSong: offer.identity)
    }

    func state(of song: MissingSong) -> GetState {
        state(ofSong: song.identity, track: song.found)
    }

    /// Where a Trackside server says a request stands, as its events tell it.
    private func acquisitionState(of request: Request) -> GetState? {
        guard let ref = request.acquisition, let acquisition = music.servers.acquisition(ref.id, on: ref.serverID) else { return nil }
        switch acquisition.phase {
        case .waiting, .searching: return .queued
        case .getting(let fraction): return fraction.map { .progress(Self.whole($0)) } ?? .queued
        case .adding: return .progress(1)
        case .added: return .done
        case .couldNotGet(let failure): return .failed(AcquisitionWords.reason(failure))
        case .cancelled: return .idle
        }
    }

    /// Where getting an album stands.
    func state(of album: Album) -> GetState {
        let request = requests[album.id]
        if let standing = Self.standing(of: request) { return standing }
        if let request, request.doneAt != nil { return .done }
        if let item = lidarrQueueItem(for: album) { return .progress(Self.whole(item.progress)) }
        guard let request else {
            return state(ofAlbum: album) == .owned ? .owned : (isWantedByLidarr(album) ? .queued : .idle)
        }
        switch request.route {
        case .lidarr:
            return .queued
        case .server:
            let total = request.songs.count
            let arrived = request.songs.count { !music.index.tracks(withIdentity: $0).isEmpty }
            guard total > 0, arrived > 0 else { return .queued }
            return arrived >= total ? .done : .progress(Self.whole(Double(arrived) / Double(total)))
        }
    }

    /// Where a request alone puts it: nil once it's waiting, when what's arrived decides.
    private static func standing(of request: Request?) -> GetState? {
        switch request?.status {
        case .failed(let reason): .failed(reason)
        case .asking: .queued
        case .waiting, nil: nil
        }
    }

    /// Progress shown in whole percents, so a ring isn't redrawn for less.
    private static func whole(_ fraction: Double) -> Double {
        (min(1, max(0, fraction)) * 100).rounded(.down) / 100
    }

    private func lidarrQueueItem(for album: Album) -> LidarrQueueItem? {
        guard let lidarr = music.lidarr, !lidarr.queue.isEmpty else { return nil }
        let key = LidarrClient.albumKey(album.title)
        let artist = StatsCalculator.folded(album.artist)
        return lidarr.queue.first { item in
            guard let title = item.album?.title, LidarrClient.albumKey(title) == key else { return false }
            return item.artist.map { StatsCalculator.folded($0.artistName) == artist } ?? true
        }
    }

    private func isWantedByLidarr(_ album: Album) -> Bool {
        guard let lidarr = music.lidarr, !lidarr.missing.isEmpty else { return false }
        let key = LidarrClient.albumKey(album.title)
        let artist = StatsCalculator.folded(album.artist)
        return lidarr.missing.contains { wanted in
            LidarrClient.albumKey(wanted.title) == key && (wanted.artist.map { StatsCalculator.folded($0.artistName) == artist } ?? false)
        }
    }

    /// Everything on its way, for Downloads and the links to it.
    var isGettingAnything: Bool {
        requests.values.contains { $0.status != .asking && $0.doneAt == nil && !isFailed($0) }
            || !music.servers.kept.isEmpty
            || !(music.lidarr?.queue.isEmpty ?? true)
    }

    private func isFailed(_ request: Request) -> Bool {
        if case .failed = request.status { return true }
        switch acquisitionState(of: request) {
        case .some(.failed), .some(.idle): return true
        default: return false
        }
    }

    /// Marks what's arrived as done, and lets it go a moment later.
    private func settleRequests() {
        for (key, request) in requests where request.doneAt == nil && request.status == .waiting {
            var arrived = !request.songs.isEmpty && request.songs.allSatisfy { !music.index.tracks(withIdentity: $0).isEmpty }
            // Trackside says which song it became, which may be named differently in the library.
            if let ref = request.acquisition, let songID = music.servers.acquisition(ref.id, on: ref.serverID)?.songID,
               music.index.track(id: LocalTrack.id(for: .server(serverID: ref.serverID, songID: songID))) != nil {
                arrived = true
            }
            if request.isAlbum, request.route == .lidarr {
                arrived = ownedSongs(of: Album(title: request.title, artist: request.artist)).count > request.ownedAtAsk
            }
            guard arrived else { continue }
            requests[key]?.doneAt = .now
            Task { [weak self] in
                try? await Task.sleep(for: Self.doneShows)
                guard let self, requests[key]?.doneAt != nil else { return }
                requests[key] = nil
            }
        }
    }

    // MARK: - Getting

    /// How Get asks a song's server for it.
    func route(for track: LocalTrack) -> UnownedSong.GetRoute {
        UnownedSong.getRoute(for: track, serverGetsSongs: track.serverID.map(music.servers.getsSongs) ?? false)
    }

    /// Whether Get shows for a song: not for one a Trackside server can't get.
    func offersGet(_ track: LocalTrack) -> Bool {
        route(for: track) != .unavailable
    }

    /// Gets a song a server found for you: the server keeps it, fetching the file, or a
    /// Trackside server that doesn't have it is asked to get it.
    /// Returns what to tell the person when it couldn't.
    @discardableResult
    func get(_ track: LocalTrack) async -> String? {
        let key = Self.songKey(track.identity)
        switch route(for: track) {
        case .keep:
            break
        case .request(let asked):
            guard let serverID = track.serverID else { return nil }
            return await request(title: track.title, artist: track.artist, album: track.album, duration: track.duration, candidate: asked.candidate, from: serverID, key: key)
        case .unavailable:
            return nil
        }
        requests[key] = Request(route: .server, songs: [track.identity], title: track.title, artist: track.artist, isAlbum: false, status: .asking)
        #if DEBUG
        if isDemo {
            demoArrive([track], key: key)
            return nil
        }
        #endif
        do {
            try await music.servers.keep(track)
            requests[key]?.status = .waiting
            return nil
        } catch {
            let reason = Self.serverUnreachable
            requests[key]?.status = .failed(reason)
            return reason
        }
    }

    /// Gets a song a Trackside server offered.
    @discardableResult
    func get(_ offer: Offer) async -> String? {
        await request(
            title: offer.candidate.title,
            artist: offer.candidate.artist,
            album: offer.candidate.album,
            duration: offer.candidate.durationMS.map { TimeInterval($0) / 1_000 },
            candidate: offer.candidate.token,
            from: offer.serverID,
            key: offer.id
        )
    }

    /// Gets one of an album's missing songs, however it can be got.
    @discardableResult
    func get(_ song: MissingSong) async -> String? {
        if let found = song.found { return await get(found) }
        if let offer = song.offer { return await get(offer) }
        return nil
    }

    /// Asks a Trackside server for a song, then follows the request by what the server says.
    private func request(title: String, artist: String, album: String?, duration: TimeInterval?, candidate: String?, from serverID: String, key: String) async -> String? {
        let identity = SongIdentity.key(title: title, artistName: artist)
        requests[key] = Request(route: .server, songs: [identity], title: title, artist: artist, isAlbum: false, status: .asking)
        do {
            let acquisition = try await music.servers.requestSong(title: title, artist: artist, album: album, duration: duration, candidate: candidate, from: serverID)
            requests[key]?.acquisition = AcquisitionRef(serverID: serverID, id: acquisition.id)
            requests[key]?.status = .waiting
            return nil
        } catch {
            requests[key]?.status = .failed(error.reason)
            return error.reason
        }
    }

    /// Gets a song named from anywhere, an Apple Music page say: from a server that finds it,
    /// a Trackside server that gets songs, or else its album from Lidarr.
    @discardableResult
    func get(songTitled title: String, artist: String, album: String?) async -> String? {
        let identity = SongIdentity.key(title: title, artistName: artist)
        let key = Self.songKey(identity)
        requests[key] = Request(route: .server, songs: [identity], title: title, artist: artist, isAlbum: false, status: .asking)
        await music.check(title: title, artist: artist, album: album)
        var found: LocalTrack?
        if case .playable(let track) = music.availability(title: title, artist: artist),
           track.isFromServer, !music.isInYourMusic(track), finds(on: track.serverID) {
            found = track
        }
        let requester = found == nil ? requestingServerOnline : nil
        var problem: String?
        switch ability.route(forSongFoundOnServer: found != nil || requester != nil, hasAlbum: album != nil) {
        case .server:
            if found == nil, let requester {
                return await request(title: title, artist: artist, album: album, duration: nil, candidate: nil, from: requester.id, key: key)
            }
            guard let found else { break }
            problem = await get(found)
            // Followed under the name it was asked by too, which the server's may not match.
            if problem == nil {
                requests[key] = Request(route: .server, songs: [found.identity], title: title, artist: artist, isAlbum: false, status: .waiting)
                return nil
            }
        case .lidarr:
            guard let album else { break }
            problem = await get(Album(title: album, artist: artist))
            if problem == nil {
                requests[key] = Request(route: .lidarr, songs: [identity], title: title, artist: artist, isAlbum: false, status: .waiting)
                return nil
            }
        case nil:
            break
        }
        let reason = problem ?? String(localized: "Couldn't find this song. Try again later.")
        requests[key] = Request(route: .server, songs: [identity], title: title, artist: artist, isAlbum: false, status: .failed(reason))
        return reason
    }

    /// Gets an album, or only the songs of it you don't have: whole from Lidarr where it's
    /// connected, or else song by song from the server that has it or can get them.
    /// - Parameter offers: its songs a Trackside server could get.
    @discardableResult
    func get(_ album: Album, only missing: [LocalTrack]? = nil, offers: [Offer] = []) async -> String? {
        let key = album.id
        let route = ability.route(forAlbumFoundOnServer: finds(on: album.serverID) || !(missing?.isEmpty ?? true) || !offers.isEmpty)
        requests[key] = Request(route: route ?? .server, songs: [], title: album.title, artist: album.artist, isAlbum: true, status: .asking, ownedAtAsk: ownedSongs(of: album).count)
        #if DEBUG
        if isDemo {
            let songs = missing ?? demoMissing(of: album)
            requests[key]?.songs = Set(songs.map(\.identity))
            demoArrive(songs, key: key)
            return nil
        }
        #endif
        switch route {
        case .lidarr:
            guard let lidarr = music.lidarr else { break }
            do {
                _ = try await lidarr.requestAlbum(album.title, by: album.artist)
                requests[key]?.status = .waiting
                watchLidarr()
                return nil
            } catch {
                let reason = Lidarr.reason(for: error)
                requests[key]?.status = .failed(reason)
                return reason
            }
        case .server:
            let songs: [LocalTrack]
            if let missing {
                songs = missing
            } else if let serverID = album.serverID, let albumID = album.albumID, let client = music.servers.client(for: serverID) {
                guard let found = try? await client.album(id: albumID).songs else {
                    let reason = Self.serverUnreachable
                    requests[key]?.status = .failed(reason)
                    return reason
                }
                songs = found.map { music.track($0, on: serverID) }
                    .filter { !music.isInYourMusic($0) }
            } else {
                songs = []
            }
            guard !songs.isEmpty || !offers.isEmpty else { break }
            music.remember(found: songs)
            requests[key]?.songs = Set(songs.map(\.identity) + offers.map(\.identity))
            var failures = 0
            for song in songs {
                switch self.route(for: song) {
                case .keep:
                    do { try await music.servers.keep(song) } catch { failures += 1 }
                case .request:
                    if await get(song) != nil { failures += 1 }
                case .unavailable:
                    failures += 1
                }
            }
            for offer in offers where await get(offer) != nil {
                failures += 1
            }
            guard failures < songs.count + offers.count else {
                let reason = Self.serverUnreachable
                requests[key]?.status = .failed(reason)
                return reason
            }
            requests[key]?.status = .waiting
            return nil
        case nil:
            break
        }
        let reason = String(localized: "Couldn't find this album. Try again later.")
        requests[key]?.status = .failed(reason)
        return reason
    }

    /// Follows Lidarr while it fetches what was asked: its queue every half minute, and your
    /// servers synced once an album leaves the queue, which is when it's been filed. Holds
    /// nothing between looks, so it ends with Get More.
    private func watchLidarr() {
        guard lidarrWatch == nil else { return }
        lidarrWatch = Task { [weak self] in
            var seen: Set<String> = []
            for _ in 0..<(3 * 60 * 2) {
                guard let queued = await self?.lookAtLidarr(since: seen) else { break }
                seen = queued
                try? await Task.sleep(for: .seconds(30))
                guard !Task.isCancelled else { return }
            }
            self?.lidarrWatch = nil
        }
    }

    /// One look at Lidarr's queue for the albums asked of it.
    /// - Returns: the ones still queued, or nil once nothing asked is waiting.
    private func lookAtLidarr(since seen: Set<String>) async -> Set<String>? {
        guard let lidarr = music.lidarr else { return nil }
        await lidarr.refreshActivity()
        let asked = requests.filter { $0.value.route == .lidarr && $0.value.status == .waiting && $0.value.doneAt == nil }
        guard !asked.isEmpty else { return nil }
        let queued = Set(asked.compactMap { key, request in
            lidarrQueueItem(for: Album(title: request.title, artist: request.artist)) == nil ? nil : key
        })
        // Out of the queue since the last look: filed, so your servers have it to sync.
        if !seen.subtracting(queued).isEmpty {
            for server in music.servers.onlineServers { await music.servers.sync(server.id) }
        }
        return queued
    }

    // MARK: - Get More

    /// Finds what to suggest, unless it was done in the last few hours: songs your servers
    /// found for you or could get, and albums by the artists you play and ones like them.
    /// Shown as each artist's answer comes in.
    /// - Parameter taste: the artists you play, most first, with their plays.
    func loadSuggestions(taste: [GetMoreRanking.Taste], force: Bool = false) async {
        guard canGet else { return }
        if !force, let loaded = suggestionsLoadedAt, Date.now.timeIntervalSince(loaded) < Self.lookupsLast {
            await rank(taste: taste, albums: knownAlbums(for: taste), offers: knownOffers(), similar: knownLikeness(for: taste))
            return
        }
        if let running = suggestionsLoad {
            await running.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            isLoadingSuggestions = suggestions.isEmpty
            defer { isLoadingSuggestions = false }
            var taste = taste
            if taste.isEmpty { taste = await Self.libraryTaste(music.index.tracks) }
            await rank(taste: taste, albums: [], offers: [], similar: [])
            let leading = Array(taste.prefix(5))
            var albums: [Album] = []
            var offers: [Offer] = []
            var similar: [GetMoreRanking.Likeness] = []
            for artist in leading {
                guard !Task.isCancelled else { return }
                albums += await self.albums(by: artist.name)
                offers += await self.offers(byArtist: artist.name)
                await rank(taste: taste, albums: albums, offers: offers, similar: similar)
            }
            // Then the closest artist like each of your top three.
            for artist in leading.prefix(3) {
                guard !Task.isCancelled else { return }
                let names = await similarArtists(to: artist.name)
                similar += names.map { GetMoreRanking.Likeness(of: artist.name, name: $0) }
                if let closest = names.first(where: { name in !leading.contains { StatsCalculator.folded($0.name) == StatsCalculator.folded(name) } }) {
                    albums += await self.albums(by: closest)
                    offers += await self.offers(byArtist: closest)
                }
                await rank(taste: taste, albums: albums, offers: offers, similar: similar)
            }
            suggestionsLoadedAt = .now
        }
        suggestionsLoad = task
        await task.value
        suggestionsLoad = nil
    }

    private func knownAlbums(for taste: [GetMoreRanking.Taste]) -> [Album] {
        serverAlbumsByArtist.values.flatMap(\.albums) + taste.prefix(5).flatMap { lidarrAlbums(by: $0.name) }
    }

    private func knownOffers() -> [Offer] {
        offersByQuery.values.flatMap(\.offers)
    }

    private func knownLikeness(for taste: [GetMoreRanking.Taste]) -> [GetMoreRanking.Likeness] {
        taste.prefix(3).flatMap { artist in
            (similarByArtist[StatsCalculator.folded(artist.name)]?.names ?? []).map { GetMoreRanking.Likeness(of: artist.name, name: $0) }
        }
    }

    /// Ranks what's been found so far, off the main actor, and shows it.
    private func rank(taste: [GetMoreRanking.Taste], albums: [Album], offers: [Offer], similar: [GetMoreRanking.Likeness]) async {
        var songs: [String: LocalTrack] = [:]
        for track in music.serverFinds where finds(on: track.serverID) {
            let key = Self.songKey(track.identity)
            if songs[key] == nil { songs[key] = track }
        }
        #if DEBUG
        if isDemo { for track in music.demoMissing { songs[Self.songKey(track.identity)] = track } }
        #endif
        var offered: [String: Offer] = [:]
        for offer in offers where songs[offer.id] == nil && offered[offer.id] == nil && music.index.tracks(withIdentity: offer.identity).isEmpty {
            offered[offer.id] = offer
        }
        // Asked for and still listed: kept, so their progress stays in place.
        for suggestion in suggestions where requests[suggestion.id] != nil {
            switch suggestion.item {
            case .song(let track): songs[suggestion.id] = track
            case .offer(let offer): offered[suggestion.id] = offer
            case .album: break
            }
        }
        var held: [String: HeldAlbum] = [:]
        for album in albums where held[album.id] == nil {
            held[album.id] = HeldAlbum(album: album, state: state(ofAlbum: album))
        }
        let candidates = songs.map { key, track in
            GetMoreRanking.Candidate(id: key, isAlbum: false, artist: track.artist, ownedShare: music.isInYourMusic(track) ? 1 : 0, isRequested: requests[key] != nil)
        } + offered.map { key, offer in
            GetMoreRanking.Candidate(id: key, isAlbum: false, artist: offer.candidate.artist, ownedShare: 0, isRequested: requests[key] != nil)
        } + held.values.map { item in
            GetMoreRanking.Candidate(id: item.id, isAlbum: true, artist: item.album.artist, ownedShare: ownedShare(of: item), isRequested: requests[item.id] != nil)
        }
        let picks = await Self.ranked(candidates, taste: taste, similar: similar)
        guard !Task.isCancelled else { return }
        suggestions = picks.compactMap { pick -> Suggestion? in
            if let track = songs[pick.id] { return Suggestion(item: .song(track), reason: pick.reason) }
            if let offer = offered[pick.id] { return Suggestion(item: .offer(offer), reason: pick.reason) }
            if let album = held[pick.id] { return Suggestion(item: .album(album), reason: pick.reason) }
            return nil
        }
    }

    private func ownedShare(of held: HeldAlbum) -> Double {
        switch held.state {
        case .owned:
            return 1
        case .missing:
            return 0
        case .partlyOwned:
            guard let total = held.album.songCount, total > 0 else { return 0.5 }
            return min(0.99, Double(ownedSongs(of: held.album).count) / Double(total))
        }
    }

    @concurrent
    nonisolated private static func ranked(_ candidates: [GetMoreRanking.Candidate], taste: [GetMoreRanking.Taste], similar: [GetMoreRanking.Likeness]) async -> [GetMoreRanking.Pick] {
        GetMoreRanking.rank(candidates, taste: taste, similar: similar, limit: 24, perArtist: 3)
    }

    /// With no listening yet, the artists your library has most songs by stand in.
    @concurrent
    nonisolated private static func libraryTaste(_ tracks: [LocalTrack]) async -> [GetMoreRanking.Taste] {
        ServerMix.libraryArtists(tracks).prefix(10).map { GetMoreRanking.Taste(name: $0, plays: 1) }
    }

    /// An artist's albums from a server that finds songs and from Lidarr, each once.
    private func albums(by artist: String) async -> [Album] {
        #if DEBUG
        if isDemo { return demoGets(forArtist: artist).albums.map(\.album) }
        #endif
        let fromServer = await serverAlbums(by: artist)
        if let lidarr = music.lidarr, lidarr.isConnected, let followed = lidarr.artist(named: artist), let id = followed.id {
            // Fills Lidarr's cache, which `lidarrAlbums(by:)` reads.
            _ = await lidarr.albums(of: id)
        }
        let seen = Set(fromServer.map(\.id))
        let fromLidarr = lidarrAlbums(by: artist).filter { !seen.contains($0.id) }
        return fromServer + fromLidarr
    }

    /// Their albums on the first server you can reach that finds songs, by searching it for
    /// their name: quick even on a server that finds songs elsewhere, as no songs are asked for.
    private func serverAlbums(by artist: String) async -> [Album] {
        guard let server = findingServerOnline, let client = music.servers.client(for: server.id) else { return [] }
        let cacheKey = server.id + "\u{1F}" + StatsCalculator.folded(artist)
        if let cached = serverAlbumsByArtist[cacheKey], Date.now.timeIntervalSince(cached.at) < Self.lookupsLast { return cached.albums }
        guard (try? await budget.wait()) != nil,
              let result = try? await music.servers.lookups(for: server.id).run({ try await client.search(artist, artists: 0, albums: 20, songs: 0, after: nil) })
        else { return [] }
        let name = StatsCalculator.folded(artist)
        let lead = StatsCalculator.folded(ArtistCredits.lead(of: artist))
        let albums = result.albums
            .filter { album in
                let theirs = StatsCalculator.folded(album.artist ?? "")
                return theirs == name || theirs == lead
            }
            .map { album in
                Album(
                    title: album.name,
                    artist: album.artist ?? artist,
                    serverID: server.id,
                    albumID: album.id,
                    artwork: album.coverArt.map { .server(serverID: server.id, coverID: $0) },
                    songCount: album.songCount,
                    year: album.year
                )
            }
        serverAlbumsByArtist[cacheKey] = (.now, albums)
        return albums
    }

    /// Their albums Lidarr knows, from what it's said already: albums it hasn't filed.
    private func lidarrAlbums(by artist: String) -> [Album] {
        guard let lidarr = music.lidarr, lidarr.isConnected, let followed = lidarr.artist(named: artist), let id = followed.id else { return [] }
        return lidarr.cachedAlbums(of: id)
            .filter { !$0.isComplete }
            .map { album in
                Album(
                    title: album.title,
                    artist: followed.artistName,
                    coverURL: album.coverURL?.absoluteString,
                    songCount: album.statistics?.totalTrackCount ?? album.statistics?.trackCount,
                    year: album.releaseDate.map { Calendar.current.component(.year, from: $0) }
                )
            }
    }

    /// Songs by an artist a Trackside server could get, from asking it for their name.
    private func offers(byArtist artist: String) async -> [Offer] {
        guard let server = requestingServerOnline else { return [] }
        let name = StatsCalculator.folded(artist)
        let lead = StatsCalculator.folded(ArtistCredits.lead(of: artist))
        return await offers(matching: artist, on: server.id).filter { offer in
            let theirs = StatsCalculator.folded(offer.candidate.artist)
            return theirs == name || theirs == lead
        }
    }

    /// Songs of an album a Trackside server could get.
    private func offers(forAlbum title: String, by artist: String, on serverID: String) async -> [Offer] {
        let key = LidarrClient.albumKey(title)
        let name = StatsCalculator.folded(artist)
        return await offers(matching: "\(artist) \(title)", on: serverID).filter { offer in
            LidarrClient.albumKey(offer.candidate.album ?? "") == key && StatsCalculator.folded(offer.candidate.artist) == name
        }
    }

    /// What a Trackside server could get for a search, one offer a song, less what's yours.
    private func offers(matching query: String, on serverID: String) async -> [Offer] {
        let cacheKey = serverID + "\u{1F}" + StatsCalculator.folded(query)
        if let cached = offersByQuery[cacheKey], Date.now.timeIntervalSince(cached.at) < 30 * 60 {
            return cached.offers.filter { music.index.tracks(withIdentity: $0.identity).isEmpty }
        }
        guard (try? await budget.wait()) != nil,
              let found = await music.servers.songsToGet(query, on: serverID)
        else { return [] }
        var seen = Set<String>()
        let offers = found
            .map { Offer(serverID: serverID, candidate: $0) }
            .filter { music.index.tracks(withIdentity: $0.identity).isEmpty && seen.insert($0.id).inserted }
        offersByQuery[cacheKey] = (.now, offers)
        return offers
    }

    /// Artists like one you play, closest first, from a server that knows them.
    private func similarArtists(to artist: String) async -> [String] {
        let key = StatsCalculator.folded(artist)
        if let cached = similarByArtist[key], Date.now.timeIntervalSince(cached.at) < Self.lookupsLast { return cached.names }
        guard let (serverID, artistID) = music.serverArtist(named: artist),
              let client = music.servers.client(for: serverID),
              (try? await budget.wait()) != nil,
              let similar = try? await music.servers.lookups(for: serverID).run({ try await client.similarArtists(to: artistID, count: 8) })
        else { return [] }
        let names = similar.map(\.name)
        similarByArtist[key] = (.now, names)
        return names
    }

    // MARK: - Artist and album pages

    /// An artist's albums you don't own all of, marked partly owned or missing, newest
    /// first, their top songs you don't have from a server that finds songs, and their songs a
    /// Trackside server could get.
    func gets(forArtist name: String) async -> ArtistGets {
        let key = StatsCalculator.folded(name)
        if let cached = artistCache[key], Date.now.timeIntervalSince(cached.at) < 10 * 60 {
            return refreshed(cached.gets)
        }
        var gets = ArtistGets()
        #if DEBUG
        if isDemo {
            gets = demoGets(forArtist: name)
            artistCache[key] = (.now, gets)
            return gets
        }
        #endif
        let albums = await albums(by: name)
        gets.albums = albums
            .map { HeldAlbum(album: $0, state: state(ofAlbum: $0)) }
            .filter { $0.state != .owned }
            .sorted { ($0.album.year ?? 0, $1.album.title) > ($1.album.year ?? 0, $0.album.title) }
        if let server = findingServerOnline, let client = music.servers.client(for: server.id),
           let top = try? await music.servers.lookups(for: server.id).run({ try await client.topSongs(artist: name, artistID: nil, count: 20) }) {
            let tracks = top.map { music.track($0, on: server.id) }
            gets.songs = Array(tracks.filter { !music.isInYourMusic($0) && music.index.tracks(withIdentity: $0.identity).isEmpty }.prefix(10))
            music.remember(found: gets.songs)
        }
        let found = Set(gets.songs.map(\.identity))
        gets.offers = Array(await offers(byArtist: name).filter { !found.contains($0.identity) }.prefix(10))
        artistCache[key] = (.now, gets)
        return gets
    }

    /// What's arrived since it was looked up drops away, without asking again.
    private func refreshed(_ gets: ArtistGets) -> ArtistGets {
        var gets = gets
        gets.albums = gets.albums.map { HeldAlbum(album: $0.album, state: state(ofAlbum: $0.album)) }
            .filter { $0.state != .owned || requests[$0.id] != nil }
        gets.songs = gets.songs.filter { music.index.tracks(withIdentity: $0.identity).isEmpty || requests[Self.songKey($0.identity)] != nil }
        gets.offers = gets.offers.filter { music.index.tracks(withIdentity: $0.identity).isEmpty || requests[$0.id] != nil }
        return gets
    }

    /// The songs of an album you own part of that you don't have, from its whole tracklist on
    /// a server that finds songs or in Lidarr, or else the songs of it a Trackside server could
    /// get. Nil when there's nothing to get, or no way to tell what's missing.
    func gaps(in album: LocalAlbum) async -> AlbumGaps? {
        let key = album.id
        if let cached = gapsCache[key], Date.now.timeIntervalSince(cached.at) < 30 * 60 {
            return cached.gaps.flatMap(stillMissing)
        }
        var tracklist: [ListedSong] = []
        var source = Album(title: album.title, artist: album.artist)
        #if DEBUG
        if isDemo {
            let missing = music.demoMissing.filter { $0.albumKey == album.id }
            tracklist = album.tracks.map { ListedSong($0, found: nil) } + missing.map { ListedSong($0, found: $0) }
            source.serverID = "demo"
        }
        #endif
        if tracklist.isEmpty, let server = findingServerOnline, let client = music.servers.client(for: server.id),
           let found = await serverTracklist(of: album, client: client, serverID: server.id) {
            tracklist = found.songs.map { ListedSong($0, found: $0) }
            source.serverID = server.id
            source.albumID = found.albumID
        }
        if tracklist.isEmpty, let lidarr = music.lidarr, lidarr.isConnected, let client = lidarr.client,
           let followed = lidarr.artist(named: album.artist), let id = followed.id {
            let known = await lidarr.albums(of: id)
            if let match = LidarrClient.match(album.title, in: known), let tracks = try? await client.tracks(ofAlbum: match.id) {
                tracklist = tracks.map { ListedSong(title: $0.title, artist: album.artist, number: Int($0.trackNumber ?? ""), found: nil) }
            }
        }
        if let server = requestingServerOnline, tracklist.allSatisfy({ $0.found == nil }) {
            let offers = await offers(forAlbum: album.title, by: album.artist, on: server.id)
            if tracklist.isEmpty, !offers.isEmpty {
                tracklist = album.tracks.map { ListedSong($0, found: nil) } + offers.map { ListedSong(offer: $0) }
            } else {
                let byIdentity = Dictionary(offers.map { ($0.identity, $0) }) { first, _ in first }
                for index in tracklist.indices {
                    tracklist[index].offer = byIdentity[SongIdentity.key(title: tracklist[index].title, artistName: tracklist[index].artist)]
                }
            }
        }
        guard !tracklist.isEmpty else {
            gapsCache[key] = (.now, nil)
            return nil
        }
        let names = tracklist.map { (title: $0.title, artist: $0.artist) }
        let holdings = await Self.holdings(names, in: owned)
        source.songCount = source.songCount ?? tracklist.count
        let missing = holdings.missing.map { index -> MissingSong in
            let song = tracklist[index]
            return MissingSong(title: song.title, artist: song.artist, number: song.number, found: song.found, offer: song.offer)
        }
        let gaps = holdings.state == .partlyOwned ? AlbumGaps(album: source, holdings: holdings, missing: missing) : nil
        gapsCache[key] = (.now, gaps)
        return gaps
    }

    /// One song of an album's whole tracklist.
    private struct ListedSong {
        let title: String
        let artist: String
        let number: Int?
        let found: LocalTrack?
        var offer: Offer?

        init(title: String, artist: String, number: Int?, found: LocalTrack?) {
            self.title = title
            self.artist = artist
            self.number = number
            self.found = found
        }

        init(_ track: LocalTrack, found: LocalTrack?) {
            self.init(title: track.title, artist: track.artist, number: track.trackNumber, found: found)
        }

        init(offer: Offer) {
            self.init(title: offer.candidate.title, artist: offer.candidate.artist, number: nil, found: nil)
            self.offer = offer
        }
    }

    @concurrent
    nonisolated private static func holdings(_ tracks: [(title: String, artist: String)], in owned: OwnedMusic) async -> AlbumHoldings {
        owned.album(tracks)
    }

    /// The gaps less what's arrived since.
    private func stillMissing(_ gaps: AlbumGaps) -> AlbumGaps? {
        let missing = gaps.missing.filter { music.index.tracks(withIdentity: $0.identity).isEmpty || requests[Self.songKey($0.identity)] != nil }
        return missing.isEmpty && requests[gaps.album.id] == nil ? nil : AlbumGaps(album: gaps.album, holdings: gaps.holdings, missing: missing)
    }

    /// The fullest copy of an album a server that finds songs has, by searching it for the
    /// album, and the songs on it, as the library has them where it does.
    private func serverTracklist(of album: LocalAlbum, client: any MusicServerClient, serverID: String) async -> (albumID: String, songs: [LocalTrack])? {
        let key = LidarrClient.albumKey(album.title)
        let artist = StatsCalculator.folded(album.artist)
        guard (try? await budget.wait()) != nil,
              let result = try? await music.servers.lookups(for: serverID).run({ try await client.search("\(album.artist) \(album.title)", artists: 0, albums: 10, songs: 0, after: nil) })
        else { return nil }
        let matches = result.albums.filter { LidarrClient.albumKey($0.name) == key && StatsCalculator.folded($0.artist ?? "") == artist }.prefix(3)
        var fullest: (albumID: String, songs: [LocalTrack])?
        for match in matches {
            guard let songs = try? await client.album(id: match.id).songs else { continue }
            if songs.count > fullest?.songs.count ?? 0 {
                let tracks = songs.map { music.track($0, on: serverID) }
                fullest = (match.id, tracks)
            }
        }
        if let fullest { music.remember(found: fullest.songs.filter { !music.isInYourMusic($0) }) }
        return fullest
    }

    // MARK: - On its way

    /// A song or album on its way, for Downloads.
    struct Incoming: Identifiable {
        let id: String
        let title: String
        let artist: String?
        let isAlbum: Bool
        let state: GetState
    }

    /// What your servers and Lidarr are fetching, newest asked first.
    var incoming: [Incoming] {
        var list: [Incoming] = []
        var listed = Set<String>()
        for server in music.servers.servers {
            // Songs asked for before names were kept have none to show, and aren't listed.
            for waiting in music.servers.kept[server.id]?.waiting ?? [] {
                guard let name = waiting.name, listed.insert(Self.songKey(waiting.identity)).inserted else { continue }
                list.append(Incoming(id: Self.songKey(waiting.identity), title: name.title, artist: name.artist, isAlbum: false, state: .queued))
            }
        }
        for item in music.lidarr?.queue ?? [] {
            guard let album = item.album else { continue }
            let key = Self.albumKey(title: album.title, artist: item.artist?.artistName ?? "")
            guard listed.insert(key).inserted else { continue }
            list.append(Incoming(id: key, title: album.title, artist: item.artist?.artistName, isAlbum: true, state: .progress(Self.whole(item.progress))))
        }
        for (key, request) in requests.sorted(by: { $0.key < $1.key }) where request.doneAt == nil && listed.insert(key).inserted {
            let state = Self.standing(of: request) ?? acquisitionState(of: request) ?? .queued
            list.append(Incoming(id: key, title: request.title, artist: request.artist, isAlbum: request.isAlbum, state: state))
        }
        return list
    }

    /// Asks again for what couldn't be got.
    func retry(_ incoming: Incoming) async -> String? {
        guard let request = requests[incoming.id] else { return nil }
        if request.isAlbum {
            return await get(Album(title: request.title, artist: request.artist))
        }
        return await get(songTitled: request.title, artist: request.artist, album: nil)
    }

    // MARK: - Sample data

    #if DEBUG
    /// Sample data has no server to ask: what was held back from the sample library arrives
    /// after a few seconds, a quarter of a second at a time.
    private func demoArrive(_ tracks: [LocalTrack], key: String) {
        requests[key]?.songs = Set(tracks.map(\.identity))
        requests[key]?.status = .waiting
        Task { [weak self] in
            for _ in 0..<12 {
                try? await Task.sleep(for: .milliseconds(250))
            }
            self?.music.demoAdd(tracks)
        }
    }

    private func demoMissing(of album: Album) -> [LocalTrack] {
        music.demoMissing.filter { GetMore.albumKey(title: $0.album ?? $0.title, artist: $0.albumArtistName) == album.id }
    }

    private func demoGets(forArtist name: String) -> ArtistGets {
        let key = StatsCalculator.folded(name)
        let theirs = music.demoMissing.filter { $0.artistKey == key }
        let albums = Dictionary(grouping: theirs.filter { $0.album != nil }, by: \.albumKey).values.compactMap { tracks -> HeldAlbum? in
            guard let first = tracks.first, let title = first.album else { return nil }
            let album = Album(title: title, artist: first.albumArtistName, serverID: "demo", artwork: first.artwork, songCount: tracks.count + ownedSongs(of: Album(title: title, artist: first.albumArtistName)).count)
            return HeldAlbum(album: album, state: state(ofAlbum: album))
        }
        return ArtistGets(albums: albums.sorted { $0.album.title < $1.album.title }, songs: Array(theirs.prefix(5)))
    }
    #endif
}

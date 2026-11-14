import Foundation
import Observation
import SwiftData
import TracksCore
import TracksMusic

/// Owns the one `NowPlayingSource` and feeds it to the coordinator.
///
/// Tracks keeps only what its own players play: Apple Music through MusicKit, and your own
/// files and servers. Keep this a single instance, or every song is seen twice.
@MainActor
@Observable
public final class CaptureService {
    public private(set) var isRunning = false
    /// Whether you've paused history: nothing you play is kept until you resume. Kept on this
    /// device, and across launches, since a pause that quietly ended when the app next opened
    /// wouldn't be one.
    public private(set) var isPaused = UserDefaults.standard.bool(forKey: CaptureService.pausedKey)

    /// Where the pause is kept. Not a synced setting: pausing one device leaves the others.
    public nonisolated static let pausedKey = "historyPaused"

    /// Whether history is paused, for code that doesn't hold the service: the player telling a
    /// music server what played, say.
    public nonisolated static var isHistoryPaused: Bool {
        UserDefaults.standard.bool(forKey: pausedKey)
    }
    public private(set) var lastCapture: CaptureSnapshot?
    public private(set) var lastDecision: CaptureDecision?
    public private(set) var currentStation: String?

    /// What is audible right now, captured or not. A song plays from its first second but
    /// only becomes ``lastCapture`` once it passes the minimum listening time.
    public private(set) var nowPlaying: NowPlaying?
    /// The session in progress, if any. Nil once silence closes it.
    public private(set) var openSession: Session?

    private let store: TracksStore
    /// The store's reads for what's shown, off the main actor.
    private let reads: CaptureReads
    /// Goes up with each observation recorded, so an older read can't show over a newer one.
    private var displayGeneration = 0
    /// The song ``nowPlaying`` is about to show once its read lands.
    private var pendingNowPlayingKey: String?
    private let settings: CaptureSettings
    private let scrobbler: ScrobbleService
    private let coordinator: CaptureCoordinator
    private let source: any NowPlayingSource
    private var pump: Task<Void, Never>?
    private var housekeeping: Task<Void, Never>?
    private var recheck: Task<Void, Never>?
    private var artworkLookup: Task<Void, Never>?
    /// The Last.fm send started after an observation, and whether another was asked for
    /// while it ran.
    private var scrobbling: Task<Void, Never>?
    private var scrobbleAgain = false
    /// The song artwork was last looked up for, so a failed lookup is not retried every poll.
    private var artworkLookupKey: String?
    /// What that lookup found. Kept separately because `nowPlaying` is rebuilt from each
    /// observation, which may carry no artwork.
    private var artworkLookupResult: String?

    /// How long a run of observations has to send what's owed to Last.fm once.
    private static let scrobbleSettle: Duration = .seconds(1)

    public init(store: TracksStore, settings: CaptureSettings = CaptureSettings()) {
        self.store = store
        self.reads = CaptureReads(container: store.container)
        self.settings = settings
        self.scrobbler = ScrobbleService(store: store, settings: settings)

        // Tracks’ Apple Music player and its player for your own music. Only one plays at a time.
        self.source = MergedNowPlayingSource([ApplicationMusicPlayerSource(), PushedNowPlayingSource.yourMusic])

        // A radio song's cover is a `musicKit://` URL that never loads, so its rows get their
        // artwork from here, and rows synced without an id get one.
        self.coordinator = CaptureCoordinator(
            store: store,
            settings: settings,
            playlistWriter: MusicKitPlaylistWriter(),
            catalogResolver: CatalogLookup()
        )
    }

    /// The service for ``TracksStore/shared()``, one per process for the app and its intents
    /// alike: a second would drain the same scrobbles and playlist writes beside the first
    /// and send each twice.
    public static func shared() throws -> CaptureService {
        if let sharedService { return sharedService }
        let service = CaptureService(store: try TracksStore.shared())
        sharedService = service
        return service
    }

    private static var sharedService: CaptureService?

    /// Retries whatever is outstanding: unresolved ids, missing artwork, failed playlist
    /// writes, unsent scrobbles. Called at launch and on coming back to the front.
    public func catchUp() async {
        await closeStaleSessions()
        await mergeSyncedDuplicates()
        await coordinator.drainPendingWrites()
        await scrobbler.drain()
    }

    /// Stops keeping what you play until ``resume()``, even across launches.
    public func pause() {
        isPaused = true
        UserDefaults.standard.set(true, forKey: Self.pausedKey)
        stop()
    }

    /// Keeps what you play again.
    public func resume() async {
        isPaused = false
        UserDefaults.standard.set(false, forKey: Self.pausedKey)
        start()
    }

    /// Finds covers that no longer load and fetches them again.
    ///
    /// Two kinds go wrong: `musicKit://` addresses that were never loadable (see
    /// ``ArtworkURL``), spotted without a network, and `https` ones that have since been taken
    /// down, which only a request can tell. Both are forgotten, and then the ordinary backfill
    /// asks the catalog for them again. Artists Apple Music had no picture for are asked about
    /// again too, since an early lookup can have missed one.
    ///
    /// Driven from Settings, so it reports what it did rather than working silently.
    public func repairArtwork() async -> ArtworkRepairReport {
        var report = ArtworkRepairReport()

        // Cheap and offline, so it happens whether or not the probes get through.
        var forget = Set<String>()
        let urls = (try? await store.artworkURLs()) ?? []
        report.checked = urls.count
        forget.formUnion(ArtworkRepair.unloadable(among: urls))

        let reachable = urls.filter { !forget.contains($0) }
        forget.formUnion(await ArtworkRepair.broken(
            among: reachable,
            probe: ArtworkRepair.networkProbe()
        ))

        report.cleared = (try? store.clearArtwork(urls: forget)) ?? 0
        // Reads and writes the whole file.
        await Self.forgetMissingArtistArtwork()
        report.restored = await coordinator.backfillAllArtwork()
        return report
    }

    @concurrent
    private nonisolated static func forgetMissingArtistArtwork() async {
        ArtistArtworkCache().forgetMissing()
    }

    /// Folds together the duplicate rows iCloud sync produces. Runs at launch and on the
    /// housekeeping timer, since synced rows can arrive at any time.
    ///
    /// Not gated on sync being on: duplicates outlive sync being turned off. The merge is
    /// idempotent, and cheap however long the history: the duplicates are found in the
    /// background.
    private func mergeSyncedDuplicates() async {
        _ = await SyncReconciler.merge(store)
    }

    /// Ends sessions that have gone quiet. Silence produces no observations, so this runs on
    /// a timer as well as at launch.
    private func closeStaleSessions() async {
        _ = try? await store.closeStaleSessions()
        showOpenSession(await reads.read(latest: false, song: nil, deviceID: DeviceIdentity.current).openSession)
    }

    public func start() {
        guard !isRunning, !isPaused else { return }
        isRunning = true
        // Seed from the store so the menu bar has something to show before the next capture.
        if lastCapture == nil {
            Task { [weak self, reads] in
                let latest = await reads.read(latest: true, song: nil, deviceID: DeviceIdentity.current).latest
                if let self, self.lastCapture == nil { self.lastCapture = latest }
            }
        }
        // A new stream every time: the one before was finished by `stop()`, so resuming
        // after a pause has to iterate this one, not the old one.
        let observations = source.start()
        // Well inside the fifteen minute silence timeout, and the query is cheap.
        housekeeping = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                await self?.closeStaleSessions()
                await self?.mergeSyncedDuplicates()
                // Catches scrobbles queued before a Last.fm account was connected mid-session.
                await self?.scrobbler.drain()
            }
        }
        pump = Task { [coordinator] in
            for await observation in observations {
                if Task.isCancelled { return }
                let decision = await coordinator.handle(observation)
                await self.record(decision, observation: observation)
                self.scrobbleSoon()
            }
        }
    }

    public func stop() {
        guard isRunning else { return }
        isRunning = false
        // Nothing is being watched, so don't claim to know what's playing.
        nowPlaying = nil
        pendingNowPlayingKey = nil
        // A read still on its way mustn't show a song again.
        displayGeneration &+= 1
        pump?.cancel()
        pump = nil
        housekeeping?.cancel()
        housekeeping = nil
        recheck?.cancel()
        recheck = nil
        artworkLookup?.cancel()
        artworkLookup = nil
        artworkLookupKey = nil
        artworkLookupResult = nil
        source.stop()
    }

    /// Checks again once a song has played long enough to count.
    ///
    /// The players only report a change, so without this a song waiting out its minimum
    /// listening time would never be captured.
    private func scheduleRecheck(after remaining: TimeInterval) {
        recheck?.cancel()
        recheck = Task { [weak self] in
            // Slightly past due, so rounding can't make it fire early and reschedule.
            try? await Task.sleep(for: .seconds(max(0.5, remaining) + 0.5))
            guard let self, !Task.isCancelled, self.isRunning else { return }
            guard let observation = await self.currentObservation(),
                  observation.playbackState == .playing
            else { return }
            let decision = await self.coordinator.handle(observation)
            await self.record(decision, observation: observation)
            self.scrobbleSoon()
        }
    }

    /// Sends what's owed to Last.fm without holding up the next observation: a slow request
    /// would otherwise start the next song's listening clock late. After every observation,
    /// not only a capture, since one can release a play held until enough of it was heard.
    private func scrobbleSoon() {
        guard scrobbling == nil else {
            scrobbleAgain = true
            return
        }
        scrobbling = Task { [weak self] in
            // Observations come in runs (a change, a recheck), and each drain saves and reads
            // the store on the main actor: one drain answers the run.
            try? await Task.sleep(for: Self.scrobbleSettle)
            repeat {
                self?.scrobbleAgain = false
                await self?.scrobbler.drain()
            } while self?.scrobbleAgain == true
            self?.scrobbling = nil
        }
    }

    /// - Parameter observation: what was playing when this was decided, so the display
    ///   follows the music rather than the writes.
    private func record(_ decision: CaptureDecision, observation: NowPlayingObservation? = nil) async {
        lastDecision = decision
        if case .ignore(.tooShort(let playedFor, let needs)) = decision {
            scheduleRecheck(after: needs - playedFor)
        }
        // Each observation lands here, often with nothing new: setting the same values again
        // would redraw everything that reads them.
        let station = coordinator.currentStationName
        if station != currentStation { currentStation = station }

        let isCapture = if case .capture = decision { true } else { false }
        let playing = observation.flatMap { $0.playbackState == .playing && $0.isIdentifiable ? $0 : nil }
        // Only an observation that changes what's shown supersedes a read on its way: one
        // player starting pauses the other, and that pause mustn't drop the new song.
        if let playing {
            pendingNowPlayingKey = Self.songKey(for: playing)
            displayGeneration &+= 1
        } else if clearNowPlaying(for: observation) {
            displayGeneration &+= 1
        }
        let generation = displayGeneration
        let read = await reads.read(
            latest: isCapture,
            song: playing.map { ($0.title, $0.artistName) },
            deviceID: DeviceIdentity.current
        )
        // A capture's row is the newest whichever read lands last.
        if isCapture, let latest = read.latest,
           latest.capturedAt >= lastCapture?.capturedAt ?? .distantPast {
            setLastCapture(latest)
        }
        // A newer observation's read is on its way, and what it shows wins.
        guard generation == displayGeneration else { return }
        showOpenSession(read.openSession, refresh: isCapture)
        if let playing {
            pendingNowPlayingKey = nil
            updateNowPlaying(playing, existing: read.song)
        }
        // Skipping back to an already captured song should still update `lastCapture`.
        if !isCapture, playing != nil, let existing = read.song { setLastCapture(existing) }
    }

    private func setLastCapture(_ snapshot: CaptureSnapshot?) {
        if snapshot != lastCapture { lastCapture = snapshot }
    }

    /// The session read in the background, as the main context's own object.
    ///
    /// - Parameter refresh: fetch it again even if it's the one shown. A capture extends the
    ///   session in the store's writer, and the main context's copy keeps its old song count
    ///   and station until it's fetched.
    private func showOpenSession(_ id: PersistentIdentifier?, refresh: Bool = false) {
        guard let id else {
            if openSession != nil { openSession = nil }
            return
        }
        guard refresh || id != openSession?.persistentModelID else { return }
        var descriptor = FetchDescriptor<Session>(predicate: #Predicate { $0.persistentModelID == id })
        descriptor.fetchLimit = 1
        let session = (try? store.context.fetch(descriptor))?.first
        if session !== openSession {
            openSession = session
        } else {
            // The same object with new values, which observation doesn't see on its own.
            withMutation(keyPath: \.openSession) {}
        }
    }

    /// What ``nowPlaying`` does for an observation with no song to show.
    /// - Returns: whether it cleared a song shown or on its way.
    private func clearNowPlaying(for observation: NowPlayingObservation?) -> Bool {
        let shown = pendingNowPlayingKey ?? nowPlaying.map(Self.songKey(for:))
        guard let shown else { return false }
        // A pause from one player is about its own song. One of Tracks’ players starting pauses
        // the other, and that pause mustn't take away the song now playing.
        if let observation, Self.songKey(for: observation) != shown { return false }
        pendingNowPlayingKey = nil
        if nowPlaying != nil { nowPlaying = nil }
        return true
    }

    /// Updates ``nowPlaying`` from every playing observation, captured or not. Artwork comes
    /// from an earlier capture of the same song, or a catalog lookup, since a radio song's
    /// own can't be loaded.
    private func updateNowPlaying(_ observation: NowPlayingObservation, existing: CaptureSnapshot?) {
        let key = Self.songKey(for: observation)
        let next = NowPlaying(
            title: observation.title,
            artistName: observation.artistName,
            albumTitle: observation.albumTitle ?? existing?.albumTitle,
            artworkURL: observation.artworkURL
                ?? existing?.artworkURL
                ?? (artworkLookupKey == key ? artworkLookupResult : nil)
        )
        if next != nowPlaying { nowPlaying = next }
        if nowPlaying?.artworkURL == nil { lookUpArtwork(for: observation) }
    }

    /// Fetches a cover for a song that has no capture to borrow one from. Runs once per
    /// song, and a failure is remembered too so it isn't retried on every observation.
    ///
    /// A radio song's own cover is a `musicKit://` URL that can't be loaded (see
    /// ``ArtworkURL``), so the observation arrives without one, but it does carry the id.
    private func lookUpArtwork(for observation: NowPlayingObservation) {
        let key = Self.songKey(for: observation)
        guard artworkLookupKey != key else { return }
        artworkLookupKey = key
        artworkLookupResult = nil
        artworkLookup?.cancel()

        artworkLookup = Task { [weak self] in
            guard let found = await Self.catalogArtwork(for: observation), !Task.isCancelled,
                  let self, self.artworkLookupKey == key
            else { return }
            // Kept for the song whether or not it's on show this moment, so the next rebuild of
            // `nowPlaying` has it: an observation in between can clear the card briefly, and the
            // cover mustn't be lost with it, since it isn't looked up twice.
            self.artworkLookupResult = found.artworkURL
            guard self.nowPlaying.map(Self.songKey(for:)) == key else { return }
            self.nowPlaying = NowPlaying(
                title: observation.title,
                artistName: observation.artistName,
                albumTitle: self.nowPlaying?.albumTitle ?? found.albumTitle,
                artworkURL: found.artworkURL
            )
        }
    }

    /// A loadable cover: by id when the observation has one, otherwise by searching the
    /// catalog, as for one of your own files.
    private static func catalogArtwork(
        for observation: NowPlayingObservation
    ) async -> (artworkURL: String, albumTitle: String?)? {
        let lookup = CatalogLookup()
        if let id = observation.catalogSongID,
           MusicItemIdentity.isCatalogID(id) || MusicItemIdentity.isLibraryID(id) {
            guard let url = try? await lookup.artworkURLs(forSongIDs: [id])[id] else { return nil }
            return (url, nil)
        }
        let query = CatalogQuery(
            title: observation.title,
            artistName: observation.artistName,
            duration: observation.duration,
            albumTitle: observation.albumTitle
        )
        guard let match = try? await lookup.resolve(query), let url = match.artworkURL else { return nil }
        return (url, match.albumTitle)
    }

    static func songKey(for observation: NowPlayingObservation) -> String {
        songKey(title: observation.title, artistName: observation.artistName)
    }

    static func songKey(for song: NowPlaying) -> String {
        songKey(title: song.title, artistName: song.artistName)
    }

    private static func songKey(title: String, artistName: String) -> String {
        "\(title)\u{1F}\(artistName)"
    }

    private func currentObservation() async -> NowPlayingObservation? {
        #if os(iOS)
        // A read can reconnect to Apple's player service on the main thread. A recheck can
        // run in the background, so it waits out scene changes (see PlayerQuiet).
        await PlayerQuiet.settled()
        #endif
        // Whichever of Tracks’ players is making sound.
        if PushedNowPlayingSource.yourMusic.isPlaying {
            return PushedNowPlayingSource.yourMusic.current
        }
        return await ApplicationMusicPlayerSource.readObservation()
    }
}

/// What the status display reads from the store after each observation, each in a context of
/// its own on the concurrent pool so an observation never waits on the store on the main actor. The
/// coordinator has saved the rows these look for before they run.
nonisolated struct CaptureReads: Sendable {
    let container: ModelContainer

    struct Read: Sendable {
        /// The newest capture, when asked for.
        var latest: CaptureSnapshot?
        /// The newest capture of the song asked about.
        var song: CaptureSnapshot?
        var openSession: PersistentIdentifier?
    }

    @concurrent
    func read(latest: Bool, song: (title: String, artistName: String)?, deviceID: String) async -> Read {
        let context = ModelContext(container)
        var read = Read()
        if latest {
            read.latest = (try? context.fetch(TracksStore.allCaptures(limit: 1)))?.first?.snapshot
        }
        if let song {
            let descriptor = TracksStore.mostRecentCapture(title: song.title, artistName: song.artistName)
            read.song = (try? context.fetch(descriptor))?.first?.snapshot
        }
        var sessions = TracksStore.openSessions(deviceID: deviceID)
        sessions.fetchLimit = 1
        read.openSession = (try? context.fetch(sessions))?.first?.persistentModelID
        return read
    }
}

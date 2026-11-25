import Foundation
import OSLog
import Synchronization
import TracksEngine

/// The play history as the shared Rust engine indexes it: charts and totals read from a
/// SQLite index with running totals, instead of a pass over every play held in memory.
///
/// The index mirrors the store and is never the source of truth. It can be deleted at any
/// time and fills itself again from the history, so anything that goes wrong with it costs a
/// rebuild, never a play. Every call is synchronous and does real work: call it off the main
/// actor.
public final class HistoryEngine: Sendable {
    private let index: HistoryIndex
    /// Whether the index matches the history: true once a full sync lands, false again after
    /// any write fails, or a read finds the file damaged, until the next full sync. Charts
    /// only answer while it's true, so a missed change can never show as a wrong number; the
    /// Swift calculator stands in.
    private let isCurrent = Mutex(false)
    /// The zone last handed to the index, with its offset then, so a fallback to a fixed
    /// offset follows a daylight saving change.
    private let zone = Mutex<(identifier: String, offset: Int)?>(nil)
    /// How many copies of a play the index holds, for plays it holds more than once. See
    /// ``key(_:)``.
    private let copies = Mutex<[String: Int]>([:])

    /// Thrown by the charts while the index may be behind the history.
    public struct NotCurrent: Error {}
    private static let log = Logger(subsystem: "com.luke.tracks", category: "HistoryEngine")

    private init(index: HistoryIndex) {
        self.index = index
        followTimeZone()
    }

    /// Opens the engine the first time it's asked for, on whichever thread asks, so opening
    /// the file never lands on the main actor at launch.
    public final class Source: Sendable {
        private let state: Mutex<HistoryEngine??> = Mutex(nil)
        private let open: @Sendable () -> HistoryEngine?

        public init(_ open: @escaping @Sendable () -> HistoryEngine?) {
            self.open = open
        }

        /// The engine, opening it if this is the first ask. Nil if it couldn't be opened.
        /// Call off the main actor.
        public var engine: HistoryEngine? {
            state.withLock { state in
                if let opened = state { return opened }
                let opened = open()
                state = .some(opened)
                return opened
            }
        }
    }

    /// The index for the app's own history, beside the store in the App Group container, so
    /// the widgets can read it too. Nil if it can't be opened, and callers carry on without it.
    public static func shared() -> HistoryEngine? {
        let folder = AppGroup.containerURL.map {
            $0.appending(path: "Library/Application Support/Tracks", directoryHint: .isDirectory)
        } ?? URL.applicationSupportDirectory.appending(path: "Tracks", directoryHint: .isDirectory)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            #if os(iOS)
            // Readable from the first unlock on, so a sync while the phone is locked finds it.
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: folder.path(percentEncoded: false)
            )
            #endif
            return HistoryEngine(index: try HistoryIndex.open(path: folder.appending(path: "history-index.sqlite").path(percentEncoded: false)))
        } catch {
            log.error("Couldn't open the history index: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    /// An index that lives in memory, for sample data, previews and tests, which must never
    /// write the real one.
    public static func inMemory() -> HistoryEngine? {
        try? HistoryEngine(index: HistoryIndex.inMemory())
    }

    // MARK: - Keeping up with the store

    /// Brings the index up to date with a change the store's reader reported. Errors are
    /// logged rather than thrown: a stale index only means the charts fall back to Swift.
    ///
    /// - Returns: whether the index now matches the history. False until the next
    ///   ``ListeningHistoryReader/Change/everything(_:)`` once a change couldn't be written.
    @discardableResult
    public func apply(_ change: ListeningHistoryReader.Change) -> Bool {
        followTimeZone()
        do {
            try Self.unsuspended {
                switch change {
                case .everything(let snapshot):
                    try syncEverything(snapshot.captures)
                case .steps(let steps, _):
                    for step in steps { try write(step) }
                }
            }
        } catch {
            isCurrent.withLock { $0 = false }
            Self.log.error("Couldn't update the history index: \(error.localizedDescription, privacy: .public)")
        }
        return isCurrent.withLock { $0 }
    }

    /// Brings the index to exactly these plays: the whole history.
    public func sync(_ captures: [CaptureStat]) {
        apply(.everything(.init(captures: captures, sessions: [])))
    }

    private func syncEverything(_ captures: [CaptureStat]) throws {
        let (plays, held) = Self.plays(captures)
        let report: WriteReport
        do {
            report = try index.sync(plays: plays, marker: nil)
        } catch {
            // Damage found partway through has emptied the file, which this sends again whole.
            guard (try? index.needsRebuild()) == true else { throw error }
            Self.log.error("Rebuilding the history index after: \(error.localizedDescription, privacy: .public)")
            report = try index.sync(plays: plays, marker: nil)
        }
        copies.withLock { $0 = held }
        isCurrent.withLock { $0 = true }
        // A first fill is one transaction as large as the history, and its journal would
        // stay that size on disk until the next write. Nothing ever called `optimize()`.
        if report.inserted + report.updated + report.removed > 1_000 { try? index.optimize() }
        Self.log.info("Synced the history index: \(report.inserted) added, \(report.updated) changed, \(report.removed) removed")
    }

    private func write(_ step: ListeningHistoryReader.Step) throws {
        var moved: [String] = []
        var plays: [Play] = []
        copies.withLock { copies in
            for (old, new) in step.changed {
                let (from, to) = (Self.key(old), Self.key(new))
                if from == to {
                    plays.append(Self.play(new, id: from))
                } else {
                    // A play whose key moved (its song was renamed) goes, and comes back new.
                    moved.append(Self.removeCopy(of: from, from: &copies))
                    plays.append(Self.play(new, id: Self.addCopy(of: to, to: &copies)))
                }
            }
            for capture in step.added {
                plays.append(Self.play(capture, id: Self.addCopy(of: Self.key(capture), to: &copies)))
            }
        }
        if !moved.isEmpty { _ = try index.remove(ids: moved, marker: nil) }
        if !plays.isEmpty { _ = try index.upsert(plays: plays, marker: nil) }
    }

    /// Follows the system's time zone. A name the engine's zone database doesn't know (an
    /// older database, or a custom zone) falls back to today's offset from GMT, which is
    /// right for every play until the next daylight saving change.
    private func followTimeZone() {
        let current = TimeZone.current
        let offset = current.secondsFromGMT()
        guard zone.withLock({ $0?.identifier != current.identifier || $0?.offset != offset }) else { return }
        do {
            try index.setTimeZone(identifier: current.identifier)
        } catch {
            let fixed = TimeZone(secondsFromGMT: offset)?.identifier ?? "GMT"
            Self.log.error("The history index doesn't know the time zone \(current.identifier, privacy: .public), so it counts days at \(fixed, privacy: .public)")
            do {
                try index.setTimeZone(identifier: fixed)
            } catch {
                Self.log.error("Couldn't set the history index's time zone: \(error.localizedDescription, privacy: .public)")
                return
            }
        }
        zone.withLock { $0 = (current.identifier, offset) }
    }

    /// The reader has no stable id for a play that would survive a relaunch, so a play is
    /// known by when it was heard, how, and what.
    ///
    /// The store keeps exact copies of a play on purpose (one history brought over on two
    /// devices at once), and the Swift calculator counts each, so every copy after the first
    /// gets its ordinal on the end of the key. A copy that arrives in a step is only known to
    /// be one when the index already holds more than one, so until the next full sync it can
    /// be counted once.
    ///
    /// A play with a lasting id (``PlayID``) is known by that instead, which stays the same
    /// when its song is renamed and on every device and server.
    private static func key(_ capture: CaptureStat) -> String {
        if let playID = capture.playID { return playID }
        return PlayID.contentKey(capturedAt: capture.capturedAt, kind: capture.kind.rawValue, source: capture.source.rawValue, songIdentity: capture.songIdentity)
    }

    private static func id(_ key: String, copy: Int) -> String {
        copy == 0 ? key : "\(key)\u{1F}\(copy)"
    }

    /// Every play with its id, and how many copies of each play held more than once.
    private static func plays(_ captures: [CaptureStat]) -> (plays: [Play], copies: [String: Int]) {
        var held: [String: Int] = [:]
        let plays = captures.map { capture in
            let key = key(capture)
            let copy = held[key, default: 0]
            held[key] = copy + 1
            return play(capture, id: id(key, copy: copy))
        }
        return (plays, held.filter { $0.value > 1 })
    }

    private static func addCopy(of key: String, to copies: inout [String: Int]) -> String {
        guard let held = copies[key] else { return key }
        copies[key] = held + 1
        return id(key, copy: held)
    }

    private static func removeCopy(of key: String, from copies: inout [String: Int]) -> String {
        guard let held = copies[key] else { return key }
        copies[key] = held > 2 ? held - 1 : nil
        return id(key, copy: held - 1)
    }

    private static func play(_ capture: CaptureStat, id: String) -> Play {
        Play(
            id: id,
            catalogId: capture.songID,
            songKey: capture.songKey,
            title: capture.title,
            artistName: capture.artistName,
            albumTitle: capture.albumTitle,
            artworkUrl: capture.artworkURL,
            playedAtMs: milliseconds(capture.capturedAt),
            kind: kind(capture.kind),
            source: Self.source(capture.source),
            stationName: capture.stationName
        )
    }

    private static func kind(_ kind: CaptureKind) -> TracksEngine.PlayKind {
        switch kind {
        case .radio: .radio
        case .onDemand: .onDemand
        case .imported: .imported
        case .lastFM: .lastFm
        }
    }

    /// Keeps iOS from suspending the app while `work` holds the index's file locked: an app
    /// suspended holding a lock on a file in the App Group container is killed.
    private static func unsuspended<T>(_ work: () throws -> T) rethrows -> T {
        #if os(iOS)
        let granted = DispatchSemaphore(value: 0)
        let finished = DispatchSemaphore(value: 0)
        ProcessInfo.processInfo.performExpiringActivity(withReason: "Updating the history index") { expired in
            granted.signal()
            if !expired { finished.wait() }
        }
        granted.wait()
        defer { finished.signal() }
        #endif
        return try work()
    }

    // MARK: - Charts

    /// The songs of a period, ranked, with movement against the period before. The same
    /// chart ``StatsCalculator/songChart(in:history:calendar:limit:)`` gives.
    public func songChart(in period: ChartPeriod, scope: SourceScope, calendar: Calendar = .current, limit: Int = 100) throws -> [Ranked<SongTally>] {
        let chart = try answering { try index.songChart(period: Self.period(period), previous: Self.previous(period, calendar: calendar), filter: Self.filter(scope), limit: Self.limit(limit)) }
        return chart.rows.map { row in
            Ranked(item: Self.tally(row.song), rank: Int(row.rank), previousRank: row.previousRank.map(Int.init), hasPrevious: chart.hasPrevious)
        }
    }

    /// The artists of a period, ranked. A play counts in full for everyone it credits.
    public func artistChart(in period: ChartPeriod, scope: SourceScope, calendar: Calendar = .current, limit: Int = 100) throws -> [Ranked<ArtistTally>] {
        let chart = try answering { try index.artistChart(period: Self.period(period), previous: Self.previous(period, calendar: calendar), filter: Self.filter(scope), limit: Self.limit(limit)) }
        return chart.rows.map { row in
            let artist = row.artist
            return Ranked(
                item: ArtistTally(
                    id: artist.identity,
                    name: artist.name,
                    count: Int(artist.plays),
                    songCount: Int(artist.songCount),
                    listeningSeconds: Self.seconds(artist.listenMs),
                    artworkURL: artist.artworkUrl,
                    firstHeard: Self.date(artist.firstPlayedMs),
                    lastHeard: Self.date(artist.lastPlayedMs)
                ),
                rank: Int(row.rank),
                previousRank: row.previousRank.map(Int.init),
                hasPrevious: chart.hasPrevious
            )
        }
    }

    /// The albums of a period, ranked.
    public func albumChart(in period: ChartPeriod, scope: SourceScope, calendar: Calendar = .current, limit: Int = 100) throws -> [Ranked<AlbumTally>] {
        let chart = try answering { try index.albumChart(period: Self.period(period), previous: Self.previous(period, calendar: calendar), filter: Self.filter(scope), limit: Self.limit(limit)) }
        return chart.rows.map { row in
            let album = row.album
            return Ranked(
                item: AlbumTally(
                    id: album.identity,
                    title: album.title,
                    artistName: album.artistName,
                    count: Int(album.plays),
                    songCount: Int(album.songCount),
                    artworkURL: album.artworkUrl,
                    listeningSeconds: Self.seconds(album.listenMs)
                ),
                rank: Int(row.rank),
                previousRank: row.previousRank.map(Int.init),
                hasPrevious: chart.hasPrevious
            )
        }
    }

    /// What `read` gives, while the index matches the history.
    private func answering<T>(_ read: () throws -> T) throws -> T {
        guard isCurrent.withLock({ $0 }) else { throw NotCurrent() }
        do {
            return try read()
        } catch {
            // A read that finds the file damaged empties it, and nothing it holds is the
            // history until the next full sync.
            if (try? index.needsRebuild()) != false { isCurrent.withLock { $0 = false } }
            throw error
        }
    }

    /// How many plays the index holds, to check it caught up with the store.
    public func playCount() throws -> Int {
        Int(try index.playCount())
    }

    /// Checkpoints and tidies the file, for when the app goes to the background.
    public func optimize() {
        Self.unsuspended { try? index.optimize() }
    }

    // MARK: - Conversions

    private static func tally(_ song: SongStats) -> SongTally {
        SongTally(
            id: song.identity,
            songKey: song.songKey,
            songID: song.catalogId,
            title: song.title,
            artistName: song.artistName,
            albumTitle: song.albumTitle,
            artworkURL: song.artworkUrl,
            count: Int(song.plays),
            firstHeard: date(song.firstPlayedMs),
            lastHeard: date(song.lastPlayedMs),
            listeningSeconds: seconds(song.listenMs)
        )
    }

    private static func period(_ period: ChartPeriod) -> TracksEngine.Period {
        guard let interval = period.interval else { return TracksEngine.Period(startMs: nil, endMs: nil) }
        return TracksEngine.Period(startMs: milliseconds(interval.start), endMs: milliseconds(interval.end))
    }

    private static func previous(_ period: ChartPeriod, calendar: Calendar) -> TracksEngine.Period? {
        period.previous(calendar: calendar).map(Self.period)
    }

    private static func filter(_ scope: SourceScope) -> SourceFilter {
        switch scope {
        case .all: .all
        case .appleMusic: .appleMusic
        case .yourMusic: .yourMusic
        case .spotify: .spotify
        }
    }

    static func source(_ source: PlaySource) -> TracksEngine.PlaySource {
        switch source {
        case .appleMusic: .appleMusic
        case .yourMusic: .yourMusic
        case .spotify: .spotify
        case .unknown(let stored): .unknown(stored: stored)
        }
    }

    private static func limit(_ limit: Int) -> UInt32 {
        UInt32(clamping: max(0, limit))
    }

    private static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private static func date(_ milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }

    private static func seconds(_ milliseconds: Int64) -> TimeInterval {
        TimeInterval(milliseconds) / 1000
    }
}

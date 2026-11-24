import Foundation
import Observation
import SwiftData

/// Sends captures to Last.fm.
///
/// Like the playlist queue, failures are stored on the row, so a device that was offline
/// catches up when it's back.
@MainActor
@Observable
public final class ScrobbleService {
    private let store: TracksStore
    /// Where the queue is read and written, off the main actor.
    private let writer: HistoryWriter
    private let settings: CaptureSettings
    private let makeClient: @Sendable () -> LastFMClient?
    /// The connected account. The Keychain in the app; injected in tests, which mustn't
    /// touch the developer's own session.
    private let currentSession: @Sendable () -> LastFMSession?
    /// Whose rows this device sends: its own, and after ``DeviceIdentity/predecessorDelay``
    /// those of the devices it was restored or migrated from (see ``DeviceIdentity``).
    private let deviceID: String
    private let predecessors: @Sendable () -> [String]
    private let inFlight: ScrobblesInFlight

    public private(set) var lastError: String?

    /// The drain in progress, which later callers wait on rather than starting their own.
    ///
    /// The queue is drained from launch, after each capture and from the housekeeping timer,
    /// and each of those can arrive while another drain is waiting on Last.fm with the same
    /// rows. Two drains at once sent every scrobble twice.
    @ObservationIgnored private var running: Task<Int, Never>?
    /// Set when someone asked for a drain while one was running, so rows that turned up
    /// after the running drain last looked get another pass.
    @ObservationIgnored private var wantsAnotherPass = false

    /// Leaves Last.fm alone for a while after a failure that isn't the rows' fault.
    @ObservationIgnored private(set) var backoff = RetryBackoff()
    /// The clock, replaceable so tests can step past the backoff.
    @ObservationIgnored var now: () -> Date = { .now }

    /// How old a play Last.fm still takes.
    static let lastFMReach: TimeInterval = 14 * 24 * 60 * 60
    /// The most pages of history one check reads before it checks fewer rows at once.
    static let historyPageLimit = 5
    /// The most pages read to check a single row, which a day of listening never exceeds.
    static let singleRowHistoryPageLimit = 20

    public init(
        store: TracksStore,
        settings: CaptureSettings = CaptureSettings(),
        makeClient: @escaping @Sendable () -> LastFMClient? = { LastFMClient.configured() },
        currentSession: @escaping @Sendable () -> LastFMSession? = { LastFMSessionStore.current },
        deviceID: String = DeviceIdentity.current,
        predecessors: @escaping @Sendable () -> [String] = { DeviceIdentity.predecessors },
        inFlightDefaults: UserDefaults = CaptureSettings.sharedDefaults
    ) {
        self.store = store
        self.writer = store.writer
        self.settings = settings
        self.makeClient = makeClient
        self.currentSession = currentSession
        self.deviceID = deviceID
        self.predecessors = predecessors
        self.inFlight = ScrobblesInFlight(defaults: inFlightDefaults)
    }

    public var isConnected: Bool { currentSession() != nil }

    /// Sends everything owed, oldest first, in batches.
    ///
    /// Cheap to call any time; does nothing if there's nothing owed, no account, scrobbling
    /// is off, or Last.fm failed recently. If a drain is already running this waits for it,
    /// and it takes one more look at the queue before finishing.
    ///
    /// - Returns: how many scrobbles Last.fm accepted.
    @discardableResult
    public func drain() async -> Int {
        if let running {
            wantsAnotherPass = true
            return await running.value
        }
        let task = Task {
            var sent = 0
            repeat {
                wantsAnotherPass = false
                sent += await drainOnce()
            } while wantsAnotherPass
            // Cleared here rather than by the caller, with nothing in between that can
            // suspend, so a caller can't join a drain that has already finished looking.
            running = nil
            return sent
        }
        running = task
        return await task.value
    }

    private func drainOnce() async -> Int {
        guard settings.scrobblesToLastFM else { return 0 }
        guard let session = currentSession(), let client = makeClient() else { return 0 }
        guard backoff.allows(at: now()) else { return 0 }

        var sent = 0
        // Rows looked for on Last.fm and not found there, so safe to send.
        var cleared: Set<PersistentIdentifier> = []
        while true {
            // Saved first, so every row has the identity it will keep.
            store.saveMainContext()
            guard let owed = await owedRows(), !owed.isEmpty else { break }

            // Spotify sends its own plays, unless Scrobble Spotify is on: held, quietly.
            if !settings.scrobblesSpotify {
                let spotify = owed.filter(\.isFromSpotify).map(\.id)
                if !spotify.isEmpty {
                    guard await change(spotify, { $0.holdFromScrobbling(reason: ScrobblePolicy.spotifyReason) }) else { return sent }
                    continue
                }
            }

            let unsendable = owed.filter { !$0.isSendable }
            if !unsendable.isEmpty {
                guard await giveUp(unsendable.map(\.id), reason: Self.unsendableReason) else { return sent }
                continue
            }

            let checks = historyChecks(for: owed, cleared: cleared)
            if !checks.isEmpty {
                guard let outcome = await checkHistory(checks, client: client, session: session) else { return sent }
                cleared.formUnion(outcome.notFound)
                if !outcome.found.isEmpty {
                    guard await markSent(Array(outcome.found)) else { return sent }
                }
                inFlight.remove(owed.filter { outcome.found.contains($0.id) })
                // Fetched again: the batch has room for rows that were behind these.
                continue
            }

            let result = await send(owed, client: client, session: session)
            sent += result.accepted
            guard result.isFinished, owed.count >= LastFMClient.batchLimit else { break }
        }
        return sent
    }

    // MARK: - Retrying

    /// The plays this device and its predecessors stopped trying to send, as Settings counts
    /// them. Plays held back for being too short aren't failures, and nor are ones Last.fm
    /// will never take for what they are.
    public func failedScrobbleCounts() async -> UnsentScrobbles {
        let devices = [deviceID] + predecessors()
        let oldest = now().addingTimeInterval(-Self.lastFMReach)
        return (try? await writer.read { context in
            var counts = UnsentScrobbles()
            for row in try context.fetch(Self.givenUp(devices: devices)) {
                switch Self.retry(for: row, oldest: oldest) {
                case .possible: counts.retryable += 1
                case .tooOld: counts.tooOld += 1
                case .never: break
                }
            }
            return counts
        }) ?? UnsentScrobbles()
    }

    /// Puts the plays ``failedScrobbleCounts()`` calls retryable back in the queue, with
    /// their attempts and reason cleared, and sends what's owed. Asked for by the person, so
    /// a wait left by an earlier failure doesn't hold it up.
    ///
    /// - Returns: how many plays went back in the queue.
    @discardableResult
    public func retryFailed() async -> Int {
        let devices = [deviceID] + predecessors()
        let oldest = now().addingTimeInterval(-Self.lastFMReach)
        let queued = (try? await writer.write { context in
            var queued = 0
            for row in try context.fetch(Self.givenUp(devices: devices)) where Self.retry(for: row, oldest: oldest) == .possible {
                row.scrobbleAttempts = 0
                row.lastScrobbleError = nil
                queued += 1
            }
            return queued
        }) ?? 0
        guard queued > 0 else { return 0 }
        backoff.reset()
        await drain()
        return queued
    }

    private enum Retry {
        case possible
        case tooOld
        case never
    }

    private nonisolated static func retry(for row: Capture, oldest: Date) -> Retry {
        if row.isHeldFromScrobbling { return .never }
        switch row.scrobbleRefusal {
        case .tooOld: return .tooOld
        case .some: return .never
        case nil: return row.capturedAt < oldest ? .tooOld : .possible
        }
    }

    /// Unsent plays out of attempts, of these devices.
    private nonisolated static func givenUp(devices: [String]) -> FetchDescriptor<Capture> {
        let maxAttempts = TracksStore.maxScrobbleAttempts
        return FetchDescriptor<Capture>(predicate: #Predicate {
            $0.scrobbledAt == nil
                && $0.scrobbleAttempts >= maxAttempts
                && devices.contains($0.capturedByDeviceID)
        })
    }

    /// The oldest rows owed: this device's, and its predecessors' once they've waited.
    private func owedRows() async -> [OwedScrobble]? {
        let deviceID = deviceID
        let predecessors = predecessors().filter { $0 != deviceID }
        let cutoff = now().addingTimeInterval(-DeviceIdentity.predecessorDelay)
        let limit = LastFMClient.batchLimit
        return try? await writer.read { context in
            // Rows earlier versions imported from Recently Played still go, as they did by default.
            var rows = try context.fetch(TracksStore.pendingScrobbles(
                deviceID: deviceID,
                includingImported: true,
                limit: limit
            )).map(OwedScrobble.init)
            for predecessor in predecessors {
                rows += try context.fetch(TracksStore.pendingScrobbles(
                    deviceID: predecessor,
                    includingImported: true,
                    limit: limit
                )).filter { $0.capturedAt < cutoff }.map(OwedScrobble.init)
            }
            return Array(rows.sorted { $0.capturedAt < $1.capturedAt }.prefix(limit))
        }
    }

    /// The rows Last.fm's history must be read for before anything is sent.
    ///
    /// - Imports: Apple's recently-played list covers the whole account, so an import can be
    ///   a play another device already scrobbled.
    /// - Rows whose request may have reached Last.fm without its answer reaching Tracks (a
    ///   timeout, the app quitting, a failed save), and a predecessor's rows, which that
    ///   device may have sent: these are looked for by their exact time.
    private func historyChecks(for owed: [OwedScrobble], cleared: Set<PersistentIdentifier>) -> [HistoryCheck] {
        let window = settings.dedupePolicy.importWindow
        return owed.compactMap { row in
            guard !cleared.contains(row.id) else { return nil }
            if row.kind == .imported { return HistoryCheck(row, reach: .foundAfter(window)) }
            if row.deviceID != deviceID || inFlight.contains(row) { return HistoryCheck(row, reach: .exact) }
            return nil
        }
    }

    // MARK: - Sending

    private struct SendResult {
        var accepted = 0
        /// Whether every row went or was settled, so the next batch can follow.
        var isFinished = false
    }

    /// Sends one batch. When Last.fm refuses what a request carried, each half is sent on its
    /// own, down to single rows, so one bad row costs only itself an attempt.
    private func send(_ rows: [OwedScrobble], client: LastFMClient, session: LastFMSession) async -> SendResult {
        var result = SendResult()
        var parts = [rows[...]]
        var charged: String?
        while let part = parts.popLast() {
            let ids = part.map(\.id)
            inFlight.insert(part)
            let outcomes: [ScrobbleOutcome]
            do {
                outcomes = try await client.submit(part.map(\.scrobble), session: session)
            } catch {
                Log.lastFM.error("Scrobbling failed: \(String(describing: error), privacy: .public)")
                let message = Self.describe(error)
                if Self.isTheScrobblesFault(error) {
                    inFlight.remove(part)
                    if part.count > 1 {
                        let middle = part.startIndex + (part.count + 1) / 2
                        // The older half goes first.
                        parts.append(part[middle...])
                        parts.append(part[..<middle])
                    } else {
                        charged = message
                        guard await charge(ids, message: message) else { break }
                    }
                    continue
                }
                // Offline, Last.fm down or rate limiting, an account problem, or anything
                // else no song caused. The rows keep their attempts and wait, and the next
                // batch would fail the same way.
                if !Self.mayHaveArrived(error) { inFlight.remove(part) }
                lastError = message
                backoff.recordFailure(at: now())
                return result
            }

            // Accepted or not, a row merged away while the request was out has nothing left
            // to record it on, so the count doesn't wait for the rows.
            result.accepted += outcomes.filter(\.isAccepted).count
            let heldBack: String?
            do {
                heldBack = try await settle(ids, outcomes: outcomes)
            } catch {
                // Left in flight: the next drain looks for them on Last.fm before sending.
                return result
            }
            inFlight.remove(part)
            if let heldBack {
                // Not reset first, so a daily limit that is still in force keeps lengthening
                // the wait instead of being asked about again every thirty seconds.
                lastError = heldBack
                backoff.recordFailure(at: now())
                return result
            }
        }
        if let charged {
            // The charged row is first in the queue again, so wait rather than send it at once.
            lastError = charged
            backoff.recordFailure(at: now())
            return result
        }
        backoff.reset()
        lastError = nil
        result.isFinished = true
        return result
    }

    /// Records what Last.fm did with each row.
    /// - Returns: why the last row held back was, if one was.
    private func settle(_ ids: [PersistentIdentifier], outcomes: [ScrobbleOutcome]) async throws -> String? {
        let stamp = now()
        return try await writer.write { context in
            let rows = TracksStore.existingCaptures(ids, in: context)
            var heldBack: String?
            for (id, outcome) in zip(ids, outcomes) {
                guard let capture = rows[id] else { continue }
                switch outcome {
                case .accepted:
                    capture.scrobbledAt = stamp
                    capture.lastScrobbleError = nil
                case .ignored(let code, let message) where outcome.isRetryable:
                    // Left owed, untouched, for when the limit or the clock allows it.
                    heldBack = Self.describeIgnored(code: code, message: message)
                case .ignored(let code, let message):
                    // Last.fm will never take this one. Give up on it now, with the reason,
                    // which is what the history shows next to it. Marking it scrobbled would
                    // claim a play Last.fm doesn't have.
                    capture.scrobbleAttempts = max(capture.scrobbleAttempts, TracksStore.maxScrobbleAttempts)
                    capture.lastScrobbleError = Self.describeIgnored(code: code, message: message)
                }
            }
            return heldBack
        }
    }

    private func markSent(_ ids: [PersistentIdentifier]) async -> Bool {
        let stamp = now()
        return await change(ids) { capture in
            capture.scrobbledAt = stamp
            capture.lastScrobbleError = nil
        }
    }

    private func charge(_ ids: [PersistentIdentifier], message: String) async -> Bool {
        await change(ids) { capture in
            capture.scrobbleAttempts += 1
            capture.lastScrobbleError = message
        }
    }

    private func giveUp(_ ids: [PersistentIdentifier], reason: String) async -> Bool {
        await change(ids) { capture in
            capture.scrobbleAttempts = max(capture.scrobbleAttempts, TracksStore.maxScrobbleAttempts)
            capture.lastScrobbleError = reason
        }
    }

    /// Changes the rows that still exist and saves.
    /// - Returns: whether it was saved.
    private func change(_ ids: [PersistentIdentifier], _ body: @escaping @Sendable (Capture) -> Void) async -> Bool {
        do {
            try await writer.write { context in
                for capture in TracksStore.existingCaptures(ids, in: context).values { body(capture) }
            }
            return true
        } catch {
            return false
        }
    }

    // MARK: - Checking Last.fm's history

    /// What a history check found.
    struct HistoryCheckOutcome: Sendable, Equatable {
        /// Rows Last.fm already has.
        var found: Set<PersistentIdentifier>
        /// Rows looked for and not there.
        var notFound: Set<PersistentIdentifier>
    }

    /// Reads the history the checks cover and matches them against it. When the range is
    /// too long to read in one go, the older half of the rows is checked first.
    ///
    /// - Returns: nil when the history couldn't be read, after backing off. Sending without
    ///   knowing could scrobble a play twice.
    private func checkHistory(
        _ checks: [HistoryCheck],
        client: LastFMClient,
        session: LastFMSession
    ) async -> HistoryCheckOutcome? {
        var checks = checks.sorted { $0.at < $1.at }
        while let earliest = checks.map(\.earliest).min(), let latest = checks.map(\.at).max() {
            let pageLimit = checks.count == 1 ? Self.singleRowHistoryPageLimit : Self.historyPageLimit
            let history: [ScrobbledTrack]?
            do {
                history = try await readHistory(
                    client: client,
                    session: session,
                    after: earliest,
                    before: latest.addingTimeInterval(1),
                    pageLimit: pageLimit
                )
            } catch {
                Log.lastFM.error("Checking Last.fm history failed: \(String(describing: error), privacy: .public)")
                lastError = Self.describe(error)
                backoff.recordFailure(at: now())
                return nil
            }
            guard let history else {
                if checks.count > 1 {
                    checks = Array(checks.prefix((checks.count + 1) / 2))
                    continue
                }
                Log.lastFM.error("Too much Last.fm history to check one scrobble against")
                backoff.recordFailure(at: now())
                return nil
            }
            let found = Self.alreadyScrobbled(checks, history: history)
            return HistoryCheckOutcome(found: found, notFound: Set(checks.map(\.id)).subtracting(found))
        }
        return HistoryCheckOutcome(found: [], notFound: [])
    }

    /// Every scrobble in the range, or nil when it runs past `pageLimit` pages.
    private func readHistory(
        client: LastFMClient,
        session: LastFMSession,
        after: Date,
        before: Date,
        pageLimit: Int
    ) async throws -> [ScrobbledTrack]? {
        var scrobbles: [ScrobbledTrack] = []
        var page = 1
        while true {
            let read = try await client.recentTracks(session: session, after: after, before: before, page: page)
            guard read.totalPages <= pageLimit else { return nil }
            scrobbles += read.scrobbles
            guard page < read.totalPages, !read.scrobbles.isEmpty else { return scrobbles }
            page += 1
        }
    }

    /// A row owed a scrobble, read in the store's writer.
    struct OwedScrobble: Sendable {
        let id: PersistentIdentifier
        let kind: CaptureKind
        let deviceID: String
        let title: String
        let artistName: String
        let albumTitle: String?
        let capturedAt: Date
        let isFromSpotify: Bool

        init(_ capture: Capture) {
            id = capture.persistentModelID
            kind = capture.kind
            deviceID = capture.capturedByDeviceID
            title = capture.title
            artistName = capture.artistName
            albumTitle = capture.albumTitle
            capturedAt = capture.capturedAt
            if case .spotify = capture.source { isFromSpotify = true } else { isFromSpotify = false }
        }

        var scrobble: Scrobble {
            Scrobble(artist: artistName, track: title, album: albumTitle, playedAt: capturedAt)
        }

        /// Last.fm refuses a whole request over one entry with no title or artist.
        var isSendable: Bool {
            !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && !artistName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    /// A row to look for in Last.fm's history before sending it.
    struct HistoryCheck: Sendable {
        enum Reach: Sendable, Equatable {
            /// An import, dated when it was found: the same song scrobbled up to this long
            /// before is the same play.
            case foundAfter(TimeInterval)
            /// A scrobble Tracks may already have sent: the same second, and the same title
            /// or artist, since Last.fm can correct one of them.
            case exact
        }

        let id: PersistentIdentifier
        let title: String
        let artistName: String
        let at: Date
        let reach: Reach

        init(id: PersistentIdentifier, title: String, artistName: String, at: Date, reach: Reach) {
            self.id = id
            self.title = title
            self.artistName = artistName
            self.at = at
            self.reach = reach
        }

        init(_ row: OwedScrobble, reach: Reach) {
            self.init(id: row.id, title: row.title, artistName: row.artistName, at: row.capturedAt, reach: reach)
        }

        /// The oldest scrobble that could be this play.
        var earliest: Date {
            switch reach {
            case .foundAfter(let window): at.addingTimeInterval(-window)
            case .exact: at.addingTimeInterval(-1)
            }
        }

        func matches(_ track: ScrobbledTrack) -> Bool {
            switch reach {
            case .foundAfter(let window):
                return SongIdentity.key(title: track.title, artistName: track.artistName)
                    == SongIdentity.key(title: title, artistName: artistName)
                    && track.playedAt <= at
                    && at.timeIntervalSince(track.playedAt) <= window
            case .exact:
                guard Int(track.playedAt.timeIntervalSince1970) == Int(at.timeIntervalSince1970) else { return false }
                return Self.folded(track.title) == Self.folded(title)
                    || Self.folded(track.artistName) == Self.folded(artistName)
            }
        }

        private static func folded(_ value: String) -> String {
            SongIdentity.key(title: value, artistName: "")
        }
    }

    /// The rows Last.fm already has. Each scrobble accounts for one row at most, oldest rows
    /// first.
    static func alreadyScrobbled(_ checks: [HistoryCheck], history: [ScrobbledTrack]) -> Set<PersistentIdentifier> {
        var unclaimed = history
        var found: Set<PersistentIdentifier> = []
        for check in checks.sorted(by: { $0.at < $1.at }) {
            guard let index = unclaimed.firstIndex(where: check.matches) else { continue }
            unclaimed.remove(at: index)
            found.insert(check.id)
        }
        return found
    }

    /// Whether a failed request says something about the scrobbles in it, so it's split or,
    /// for a single row, charged an attempt.
    ///
    /// Only Last.fm refusing what the request carried counts. Everything else (no network, a
    /// timeout, Last.fm down or rate limiting, the account, a code Tracks doesn't know, a
    /// proxy's bare 4xx or a page that isn't Last.fm's) would fail any request, and charging
    /// the rows would throw away listening because of an outage.
    nonisolated static func isTheScrobblesFault(_ error: any Error) -> Bool {
        (error as? LastFMError)?.concernsRequestContent == true
    }

    /// Whether a request that failed may still have been stored by Last.fm: the answer was
    /// lost, not refused. Those rows are looked for on Last.fm before being sent again.
    nonisolated static func mayHaveArrived(_ error: any Error) -> Bool {
        switch error {
        case let error as URLError:
            !neverSent.contains(error.code)
        case let error as LastFMError:
            switch error {
            // A gateway can time out after Last.fm took the request; 503 means it didn't.
            case .http(let status): status >= 500 && status != 503
            case .malformedResponse: true
            case .service, .notConfigured, .notConnected, .notAuthorised: false
            }
        default:
            true
        }
    }

    /// Transport failures from before a request left the device.
    private nonisolated static let neverSent: Set<URLError.Code> = [
        .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff, .callIsActive,
        .cannotFindHost, .dnsLookupFailed, .cannotConnectToHost, .badURL, .unsupportedURL,
        .appTransportSecurityRequiresSecureConnection, .secureConnectionFailed,
        .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateNotYetValid,
        .serverCertificateHasUnknownRoot, .clientCertificateRejected, .clientCertificateRequired,
    ]

    /// Why a row Last.fm could never take is given up on, as the history shows it.
    nonisolated static var unsendableReason: String {
        ScrobbleRefusal.missingTitleOrArtist.rawValue
    }

    /// Tells Last.fm what's playing. Not queued, since it expires on its own.
    public func updateNowPlaying(title: String, artistName: String, albumTitle: String?) async {
        guard settings.scrobblesToLastFM,
              let session = currentSession(),
              let client = makeClient()
        else { return }
        try? await client.updateNowPlaying(
            Scrobble(artist: artistName, track: title, album: albumTitle, playedAt: .now),
            session: session
        )
    }

    /// What went wrong talking to Last.fm, in words: what happened and what to do. Last.fm's
    /// own codes and messages mean nothing to the person reading, so they only go to the log.
    /// Public so the connect flow reports failures in the same words.
    public nonisolated static func describe(_ error: any Error) -> String {
        if let error = error as? URLError {
            return switch error.code {
            case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff:
                String(localized: "You’re offline. Connect to the internet and try again.")
            case .timedOut, .cannotConnectToHost, .cannotFindHost:
                String(localized: "Last.fm isn’t responding. Try again later.")
            default:
                String(localized: "Couldn’t reach Last.fm. Try again.")
            }
        }
        guard let error = error as? LastFMError else {
            return String(localized: "Something went wrong with Last.fm. Try again.")
        }
        return switch error {
        case .notConfigured:
            String(localized: "Last.fm isn’t set up in this build of Tracks.")
        // 4 authentication failed, 9 invalid session key.
        case .notConnected, .service(code: 4, _), .service(code: 9, _):
            String(localized: "You’re signed out of Last.fm. Connect your account again.")
        // 14 token not authorised, 15 token expired.
        case .notAuthorised, .service(code: 14, _), .service(code: 15, _):
            String(localized: "Last.fm didn’t get your approval. Try connecting again.")
        case _ where error.isRetryable:
            String(localized: "Last.fm isn’t responding. Try again later.")
        case .http, .service, .malformedResponse:
            String(localized: "Something went wrong with Last.fm. Try again.")
        }
    }

    /// Why Last.fm ignored one scrobble from a batch, in words for the history. Last.fm's own
    /// message for it only goes to the log.
    public nonisolated static func describeIgnored(code: Int, message: String) -> String {
        if !message.isEmpty {
            Log.lastFM.notice("Last.fm ignored a scrobble, code \(code): \(message, privacy: .public)")
        }
        if let refusal = ScrobbleRefusal(code: code) { return refusal.rawValue }
        return switch code {
        case 4: String(localized: "Last.fm thinks this was played in the future. Check the date and time.")
        case 5: String(localized: "Last.fm’s daily scrobble limit was reached.")
        default: String(localized: "Last.fm didn’t take this scrobble.")
        }
    }
}

/// The scrobbles sent in a request whose answer hasn't been recorded yet, kept in defaults so
/// that survives the app quitting mid-request. Kept by what was sent rather than by row, so
/// it holds across a store that is rebuilt or migrated.
struct ScrobblesInFlight {
    // Don't rename: rows sent by the build before would be sent again unchecked.
    static let key = "TracksScrobblesInFlight"
    /// Enough for any one drain. Older entries are rows long since settled or merged away.
    static let limit = 500

    let defaults: UserDefaults

    func contains(_ row: ScrobbleService.OwedScrobble) -> Bool {
        stored.contains(Self.entry(for: row))
    }

    func insert(_ rows: some Sequence<ScrobbleService.OwedScrobble>) {
        var list = stored
        for entry in rows.map(Self.entry(for:)) where !list.contains(entry) { list.append(entry) }
        save(Array(list.suffix(Self.limit)))
    }

    func remove(_ rows: some Sequence<ScrobbleService.OwedScrobble>) {
        let removing = Set(rows.map(Self.entry(for:)))
        guard !removing.isEmpty else { return }
        save(stored.filter { !removing.contains($0) })
    }

    private static func entry(for row: ScrobbleService.OwedScrobble) -> String {
        "\(Int(row.capturedAt.timeIntervalSince1970))\u{1F}\(SongIdentity.key(title: row.title, artistName: row.artistName))"
    }

    private var stored: [String] { defaults.stringArray(forKey: Self.key) ?? [] }

    private func save(_ list: [String]) {
        if list.isEmpty {
            defaults.removeObject(forKey: Self.key)
        } else {
            defaults.set(list, forKey: Self.key)
        }
    }
}

/// Plays Last.fm wasn't sent and that Tracks stopped trying with.
public struct UnsentScrobbles: Sendable, Equatable {
    /// Ones a retry can send.
    public var retryable: Int
    /// Ones older than Last.fm takes, which no retry gets through.
    public var tooOld: Int

    public init(retryable: Int = 0, tooOld: Int = 0) {
        self.retryable = retryable
        self.tooOld = tooOld
    }
}

/// Why Last.fm will never take a play, kept on the row in ``Capture/lastScrobbleError``.
///
/// Kept as these exact words, never translated, so every device tells them apart whatever
/// language it runs in, as ``ScrobblePolicy/tooShortReason`` is. Rows given up on before
/// this existed carry the same words.
public enum ScrobbleRefusal: String, CaseIterable, Sendable {
    case artistIgnored = "Last.fm ignored the artist."
    case trackIgnored = "Last.fm ignored the track."
    case tooOld = "Too old for Last.fm, which only takes scrobbles from the last two weeks."
    case missingTitleOrArtist = "Last.fm needs a song’s title and artist."

    /// The refusal behind an ignored scrobble's code, for the codes that are one.
    init?(code: Int) {
        switch code {
        case 1: self = .artistIgnored
        case 2: self = .trackIgnored
        case 3: self = .tooOld
        default: return nil
        }
    }
}

extension Capture {
    /// Why Last.fm will never take this play, when that's why it wasn't sent.
    public var scrobbleRefusal: ScrobbleRefusal? {
        guard scrobbledAt == nil, let lastScrobbleError else { return nil }
        return ScrobbleRefusal(rawValue: lastScrobbleError)
    }
}

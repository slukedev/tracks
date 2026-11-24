import Foundation
import OSLog
import SwiftData

/// The SwiftData container in the App Group. The app, widgets and Live Activity read it,
/// and CloudKit mirrors it to the user's other devices.
@MainActor
public final class TracksStore {
    public let container: ModelContainer
    /// Where the store actually opened, which may not be where it was asked to.
    public let backing: Backing

    /// Why sync didn't start when it was asked to. Nil when sync is off or working.
    /// Shown in settings, since an unreachable container otherwise looks like an idle device.
    public let syncFailureReason: String?

    /// Where this run keeps its plays while the library on disk can't be opened, so they
    /// reach the library the next time it opens. Nil whenever the library is open.
    let unsavedPlays: UnsavedPlays?
    private var unsavedPlaysObserver: (any NSObjectProtocol)?

    /// Whether the library on disk couldn't be opened, so this run keeps what's played in
    /// memory and aside in a file of its own, and saves it to the library on a later launch
    /// that can open it. See ``libraryOpensNow()``.
    public var isLibraryUnavailable: Bool { unsavedPlays != nil }

    public enum Backing: Sendable, Equatable, CustomStringConvertible {
        case appGroup(String, synced: Bool)
        case inMemory(reason: String)

        public var description: String {
            switch self {
            case .appGroup(let id, let synced):
                "App Group container (\(id))\(synced ? ", synced to iCloud" : "")"
            case .inMemory(let reason): "in-memory (\(reason))"
            }
        }

        public var isSynced: Bool {
            if case .appGroup(_, let synced) = self { return synced }
            return false
        }
    }

    /// The one read-write store for this process.
    ///
    /// All writes go through this. A second read-write open of the same file fails and
    /// falls back to an empty in-memory store. App Intents run inside the app's process when
    /// it's open, so an intent with its own store would capture into nothing.
    ///
    /// Read-only callers (the widget) and tests make their own.
    @MainActor
    public static func shared() throws -> TracksStore {
        if let existing = sharedStore { return existing }
        let store = try TracksStore()
        sharedStore = store
        return store
    }

    @MainActor private static var sharedStore: TracksStore?

    /// The current schema. Always open a container through ``makeContainer(_:)`` so the
    /// migration plan comes with it.
    public nonisolated static let schema = Schema(versionedSchema: TracksSchemaV3.self)

    /// Opens the shared container, falling back to in-memory if that fails.
    ///
    /// An unsigned build, or one without `DEVELOPMENT_TEAM`, gets a plausible identifier and
    /// path that SwiftData can't write to. Trying the open is the only real test, and
    /// ``backing`` records what happened.
    /// - Parameters:
    ///   - readOnly: Opens without write access, for widgets and other second processes.
    ///     A second read-write open fails while the app has the store open.
    ///   - sync: Whether to mirror the store to iCloud. Defaults to the user's preference.
    public init(
        inMemory: Bool = false,
        readOnly: Bool = false,
        sync: Bool = CloudSync.mirrorsToICloud
    ) throws {
        var pendingSyncFailure: String?

        if !inMemory, let identifier = AppGroup.identifier, AppGroup.hasCandidateContainer {
            // Read-only openers (the widget) never sync; the app owns the file.
            let cloudIdentifier = (sync && !readOnly) ? CloudSync.containerIdentifier : nil

            if let cloudIdentifier {
                do {
                    container = try Self.open(
                        group: identifier,
                        readOnly: readOnly,
                        cloud: .private(cloudIdentifier)
                    )
                    backing = .appGroup(identifier, synced: true)
                    syncFailureReason = nil
                    unsavedPlays = nil
                    if !readOnly { didOpenLibrary() }
                    return
                } catch {
                    // Sync can fail for ordinary reasons (no iCloud account, a container
                    // the profile doesn't grant, a schema CloudKit rejects). Open the local
                    // store without sync; falling back to in-memory would hide the history.
                    pendingSyncFailure = CloudSyncMonitor.startFailure(for: error)
                }
            }

            do {
                let container = try Self.open(group: identifier, readOnly: readOnly, cloud: .none)
                self.container = container
                backing = .appGroup(identifier, synced: false)
                syncFailureReason = pendingSyncFailure
                unsavedPlays = nil
                if !readOnly { didOpenLibrary() }
                return
            } catch {
                // Fall back to in-memory, keeping the reason for the probe. A full disk, a
                // file another process holds or a migration that fails would otherwise lose
                // every play of the run at quit, so they're kept aside too.
                container = try Self.inMemoryContainer()
                backing = .inMemory(reason: "App Group \(identifier) not writable: \(error.localizedDescription)")
                syncFailureReason = pendingSyncFailure
                unsavedPlays = readOnly ? nil : .standard
                if let unsavedPlays { keepPlaysAside(in: unsavedPlays) }
                return
            }
        }

        container = try Self.inMemoryContainer()
        backing = .inMemory(
            reason: inMemory ? "requested" : "no App Group configured"
        )
        syncFailureReason = nil
        unsavedPlays = nil
    }

    /// What a writable open of the library does first.
    private func didOpenLibrary() {
        stampLegacyCaptures()
        Task { [writer] in
            _ = await writer.saveUnsavedPlays(from: .standard)
            // Plays and sessions from before lasting ids get theirs (see ``PlayID``).
            _ = try? await writer.backfillLastingIDs()
        }
    }

    /// Restores what earlier runs kept aside, so this one shows and keeps it too, then keeps
    /// the history aside again after every save.
    private func keepPlaysAside(in unsavedPlays: UnsavedPlays) {
        unsavedPlays.restore(into: container)
        let keeper = UnsavedPlays.Keeper(unsavedPlays, container: container)
        unsavedPlaysObserver = NotificationCenter.default.addObserver(
            forName: ModelContext.didSave, object: nil, queue: nil
        ) { _ in
            Task { await keeper.keep() }
        }
    }

    /// Tries the library on disk again, for a run that couldn't open it. True when it opens
    /// now, so the next launch will save what this run kept aside; this run carries on in
    /// memory, since everything holding the store would have to start over.
    public func libraryOpensNow() async -> Bool {
        guard isLibraryUnavailable, let identifier = AppGroup.identifier else { return false }
        return await Self.opens(group: identifier)
    }

    @concurrent
    private nonisolated static func opens(group identifier: String) async -> Bool {
        (try? open(group: identifier, readOnly: false, cloud: .none)) != nil
    }

    /// An in-memory container that never talks to iCloud.
    ///
    /// `cloudKitDatabase` must be `.none`. The default, `.automatic`, syncs even an in-memory
    /// store, which once pulled real history into the demo store and could have pushed demo
    /// plays into the real one.
    nonisolated static func inMemoryContainer() throws -> ModelContainer {
        let configuration = ModelConfiguration(
            schema: Self.schema,
            isStoredInMemoryOnly: true,
            allowsSave: true,
            groupContainer: .none,
            cloudKitDatabase: .none
        )
        return try makeContainer(configuration)
    }

    /// Opens a container on the current schema and ``TracksMigrationPlan``.
    ///
    /// The one place a container is made, so the app, the widget's read-only open, the demo
    /// store and tests all migrate the same way.
    nonisolated static func makeContainer(_ configuration: ModelConfiguration) throws -> ModelContainer {
        try ModelContainer(
            for: Self.schema,
            migrationPlan: TracksMigrationPlan.self,
            configurations: configuration
        )
    }

    private nonisolated static func open(
        group identifier: String,
        readOnly: Bool,
        cloud: ModelConfiguration.CloudKitDatabase
    ) throws -> ModelContainer {
        let configuration = ModelConfiguration(
            schema: Self.schema,
            isStoredInMemoryOnly: false,
            allowsSave: !readOnly,
            groupContainer: .identifier(identifier),
            cloudKitDatabase: cloud
        )
        return try makeContainer(configuration)
    }

    /// Marks pre-sync rows as this device's, once.
    ///
    /// Rows that exist the first time the store opens here were made here. The playlist and
    /// scrobble queues are scoped by device ID, so unstamped rows would never be written.
    ///
    /// Runs on every writable open, synced or not. It used to run only when sync started,
    /// so a device without iCloud, or one whose CloudKit container failed to open, never
    /// stamped its rows and they sat in neither queue for good. Read-only opens skip it,
    /// since they can't save.
    ///
    /// Done once, not every launch. On a second device the CloudKit import usually hasn't
    /// landed yet, but if it has, an imported row gets stamped too and its playlist write
    /// happens twice. One duplicate is better than a capture that never reaches the playlist.
    ///
    /// The flag is only set once the stamp is saved. It used to be set first, so a failed
    /// fetch or save marked the rows as done without touching them.
    ///
    /// - Returns: whether the stamp is now done, which tests use.
    @discardableResult
    func stampLegacyCaptures(
        deviceID: String = DeviceIdentity.current,
        isDone: () -> Bool = { CloudSync.hasStampedLegacyCaptures },
        markDone: () -> Void = { CloudSync.hasStampedLegacyCaptures = true }
    ) -> Bool {
        guard !isDone() else { return true }

        let descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate { $0.capturedByDeviceID.isEmpty }
        )
        do {
            let rows = try context.fetch(descriptor)
            if !rows.isEmpty {
                for row in rows { row.capturedByDeviceID = deviceID }
                try context.save()
            }
            markDone()
            return true
        } catch {
            // Try again next launch rather than leave half-stamped rows for an unrelated
            // save to write.
            context.rollback()
            return false
        }
    }

    public var context: ModelContext { container.mainContext }

    /// The history as plain values, kept up to date in the background. One per store, so
    /// the screens and the merge share its first full read.
    public private(set) lazy var historyReader = ListeningHistoryReader(container: container)

    /// Capture, the queues and reconciling write through this, off the main actor.
    private(set) lazy var writer = HistoryWriter(container: container)

    /// Saves what the main context has changed, so rows made there have the identity they'll
    /// keep before the writer looks for them.
    func saveMainContext() {
        if context.hasChanges { try? context.save() }
    }

    // MARK: - Capture

    /// Records an observation, or returns nil if the dedupe window says it's the same play.
    ///
    /// Must stay synchronous: with an `await` between the dedupe check and the insert, two
    /// observations of the same song could both be inserted. The playlist write happens
    /// later, off `needsPlaylistWrite`.
    @discardableResult
    public func recordCapture(
        songID: String,
        title: String,
        artistName: String,
        albumTitle: String? = nil,
        artworkURL: String? = nil,
        kind: CaptureKind = .radio,
        session: Session? = nil,
        now: Date = .now,
        policy: DedupePolicy = .default
    ) throws -> Capture? {
        let lastSeen = try mostRecentCaptureDate(songID: songID, kind: kind)
        guard policy.shouldCapture(lastSeen: lastSeen, now: now) else { return nil }

        let capture = Capture(
            songID: songID,
            title: title,
            artistName: artistName,
            albumTitle: albumTitle,
            artworkURL: artworkURL,
            kind: kind,
            capturedAt: now,
            session: session
        )
        context.insert(capture)
        session?.lastActivityAt = now
        try context.save()
        return capture
    }

    /// When this track was last captured, by `songKey`, across every witnessed kind.
    ///
    /// Don't filter this to radio: on-demand songs would never dedupe, and each check of a
    /// playing song would write a new row. Imports are excluded because they're dated when
    /// found, so a recent import would suppress a real play.
    nonisolated static func lastCaptureDate(songKey: String, in context: ModelContext) throws -> Date? {
        let imported = CaptureKind.imported.rawValue
        var descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate { $0.songKey == songKey && $0.kindRawValue != imported },
            sortBy: [SortDescriptor(\.capturedAt, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first?.capturedAt
    }

    /// This device's open session for this station, starting one if the policy says to.
    ///
    /// Only this device's sessions are considered (see ``Session/deviceID``). Another device
    /// listening at the same time has its own open session, which isn't a station change or
    /// a silence here.
    ///
    /// Doesn't save.
    @discardableResult
    nonisolated static func openSession(
        in context: ModelContext,
        stationName: String?,
        policy: SessionPolicy = .default,
        now: Date = .now,
        deviceID: String = DeviceIdentity.current
    ) throws -> Session {
        var descriptor = openSessions(deviceID: deviceID)
        descriptor.fetchLimit = 1
        let open = try context.fetch(descriptor).first

        // Compared on the same key stations are matched on, so an open session whose station
        // row is spelled differently from what the player reports isn't a station change.
        let decision = policy.decide(
            openSessionStation: open?.station.map { stationKey($0.name) },
            openSessionLastActivity: open?.lastActivityAt,
            observedStation: stationName.map(stationKey),
            now: now
        )

        if decision == .current, let open {
            open.lastActivityAt = now
            // A session from before sessions had a device is this device's once it's
            // extended here, so another device stops treating it as its own.
            if open.deviceID == nil { open.deviceID = deviceID }
            // Name a session that started before we knew the station, or whose station was
            // merged away on another device before this session's link to it had synced.
            if open.station == nil, stationName != nil {
                open.station = try station(named: stationName, now: now, in: context)
            }
            return open
        }

        // Close at the last activity, not now, so the silence doesn't count as listening.
        open?.endedAt = open?.lastActivityAt

        let session = Session(
            startedAt: now,
            station: try station(named: stationName, now: now, in: context),
            deviceID: deviceID
        )
        context.insert(session)
        return session
    }

    /// The station row for a name, creating one if there isn't one.
    ///
    /// Matched on ``stationKey(_:)``, the key ``mergeDuplicateStations()`` groups on, and the
    /// oldest match wins, as it does there. An exact-name match made a new row whenever the
    /// player's spelling differed from the stored one, which the next merge deleted again,
    /// every session.
    private nonisolated static func station(named name: String?, now: Date, in context: ModelContext) throws -> Station? {
        guard let name else { return nil }
        let key = stationKey(name)
        // Station names can't be folded in a predicate. There are only as many rows as
        // stations the user has listened to, so matching in memory is cheap.
        let descriptor = FetchDescriptor<Station>(
            sortBy: [SortDescriptor(\.firstSeenAt), SortDescriptor(\.name)]
        )
        if let existing = try context.fetch(descriptor).first(where: { stationKey($0.name) == key }) {
            existing.lastSeenAt = max(existing.lastSeenAt, now)
            return existing
        }
        let station = Station(name: name, firstSeenAt: now)
        context.insert(station)
        return station
    }

    /// Open sessions belonging to this device, newest first. Sessions with no device predate
    /// the field and count as this device's, so one left open by an older build still closes.
    public nonisolated static func openSessions(deviceID: String) -> FetchDescriptor<Session> {
        let device: String? = deviceID
        return FetchDescriptor<Session>(
            predicate: #Predicate {
                $0.endedAt == nil && ($0.deviceID == nil || $0.deviceID == device)
            },
            sortBy: [SortDescriptor(\.startedAt, order: .reverse)]
        )
    }

    /// Closes this device's open sessions that have gone quiet, at their `lastActivityAt`.
    /// Without this, the last session stays open after the music stops.
    ///
    /// Another device's session is left alone: its lack of activity *here* says nothing,
    /// since that device extends it, not this one. The exception is a device this one was
    /// restored or migrated from (``DeviceIdentity/predecessors``), which may never run
    /// again: its sessions close once they've been quiet for
    /// ``DeviceIdentity/predecessorDelay``, longer than any real silence.
    ///
    /// Done by ``writer``, off the main actor.
    /// - Returns: how many were closed.
    @discardableResult
    public func closeStaleSessions(
        policy: SessionPolicy = .default,
        now: Date = .now,
        deviceID: String = DeviceIdentity.current,
        predecessors: [String] = DeviceIdentity.predecessors
    ) async throws -> Int {
        try await writer.closeStaleSessions(policy: policy, now: now, deviceID: deviceID, predecessors: predecessors)
    }

    /// ``closeStaleSessions(policy:now:deviceID:predecessors:)`` on any context. Doesn't save.
    nonisolated static func closeStaleSessions(
        in context: ModelContext,
        policy: SessionPolicy,
        now: Date,
        deviceID: String,
        predecessors: [String] = []
    ) throws -> [Session] {
        let open = try context.fetch(openSessions(deviceID: deviceID))
        var stale = open.filter { now.timeIntervalSince($0.lastActivityAt) >= policy.silenceTimeout }
        let others: [String?] = predecessors.filter { $0 != deviceID }
        if !others.isEmpty {
            let quietSince = now.addingTimeInterval(-max(policy.silenceTimeout, DeviceIdentity.predecessorDelay))
            stale += try context.fetch(FetchDescriptor<Session>(predicate: #Predicate {
                $0.endedAt == nil && $0.lastActivityAt <= quietSince && others.contains($0.deviceID)
            }))
        }
        for session in stale { session.endedAt = session.lastActivityAt }
        return stale
    }

    /// This device's open session, if it has one.
    public func currentOpenSession(deviceID: String = DeviceIdentity.current) throws -> Session? {
        var descriptor = Self.openSessions(deviceID: deviceID)
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first
    }

    /// Sessions from the last `days` days, newest first, for the Mac menu bar.
    public static func recentSessions(days: Int = 7, now: Date = .now) -> FetchDescriptor<Session> {
        let cutoff = Calendar.current.date(byAdding: .day, value: -days, to: now) ?? .distantPast
        return FetchDescriptor<Session>(
            predicate: #Predicate { $0.startedAt >= cutoff },
            sortBy: [SortDescriptor(\.startedAt, order: .reverse)]
        )
    }

    /// Inserts a capture. Called inside the decide-and-insert transaction. Doesn't save.
    @discardableResult
    nonisolated static func insertCapture(
        in context: ModelContext,
        songKey: String,
        catalogSongID: String?,
        observation: NowPlayingObservation,
        kind: CaptureKind = .radio,
        session: Session?,
        now: Date = .now
    ) -> Capture {
        let capture = Capture(
            songID: catalogSongID ?? "",
            songKey: songKey,
            title: observation.title,
            artistName: observation.artistName,
            albumTitle: observation.albumTitle,
            artworkURL: observation.artworkURL,
            kind: kind,
            capturedAt: now,
            // Apple Music or Your Music, so the two can be counted apart.
            source: PlaySource(observation: observation),
            session: session,
            // The player's own id for the play, where it gave one, so the server it played
            // from counts the same play under the same id.
            playID: observation.rawFields[PlayID.observationKey]
        )
        context.insert(capture)
        session?.lastActivityAt = now
        return capture
    }

    /// Rows with a catalog ID but no artwork.
    public static func missingArtwork(limit: Int = 50) -> FetchDescriptor<Capture> {
        var descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate { !$0.songID.isEmpty && $0.artworkURL == nil },
            sortBy: [SortDescriptor(\.capturedAt, order: .reverse)]
        )
        descriptor.fetchLimit = limit
        return descriptor
    }

    /// Rows with no catalog ID yet (macOS, before the search runs).
    ///
    /// Every kind but Last.fm's, not just radio: artwork is looked up by ID, so on-demand
    /// rows need one too. The playlist write checks the kind, not the ID. A Last.fm history
    /// can be a hundred thousand plays, and searching the catalog for each would hold up
    /// every playlist write behind it; those rows keep Last.fm's cover.
    ///
    /// One batch, oldest first, so a backlog is searched a drain at a time rather than all at
    /// once, which held up every playlist write behind it.
    public nonisolated static func awaitingCatalogID(limit: Int = catalogSearchBatch) -> FetchDescriptor<Capture> {
        let maxAttempts = maxCatalogLookupAttempts
        let lastFM = CaptureKind.lastFM.rawValue
        var descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate {
                $0.songID.isEmpty && $0.catalogLookupAttempts < maxAttempts
                    && $0.kindRawValue != lastFM
            },
            sortBy: [SortDescriptor(\.capturedAt, order: .forward)]
        )
        descriptor.fetchLimit = limit
        return descriptor
    }

    /// How many rows one drain searches the catalog for.
    public nonisolated static let catalogSearchBatch = 25

    private func mostRecentCaptureDate(songID: String, kind: CaptureKind) throws -> Date? {
        let raw = kind.rawValue
        var descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate { $0.songID == songID && $0.kindRawValue == raw },
            sortBy: [SortDescriptor(\.capturedAt, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first?.capturedAt
    }

    // MARK: - Queries

    /// Every capture, whatever the source. Use this for showing listening history;
    /// ``radioCaptures(since:limit:)`` is for the playlist.
    public nonisolated static func allCaptures(
        since: Date? = nil,
        limit: Int? = nil
    ) -> FetchDescriptor<Capture> {
        let cutoff = since ?? .distantPast
        var descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate { $0.capturedAt >= cutoff },
            sortBy: [SortDescriptor(\.capturedAt, order: .reverse)]
        )
        if let limit { descriptor.fetchLimit = limit }
        return descriptor
    }

    /// Radio rows only, for the playlist.
    public nonisolated static func radioCaptures(
        since: Date? = nil,
        limit: Int? = nil
    ) -> FetchDescriptor<Capture> {
        let radio = CaptureKind.radio.rawValue
        // `#Predicate` can't come from an `if` expression, so `.distantPast` means no cutoff.
        let cutoff = since ?? .distantPast
        var descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate { $0.kindRawValue == radio && $0.capturedAt >= cutoff },
            sortBy: [SortDescriptor(\.capturedAt, order: .reverse)]
        )
        if let limit { descriptor.fetchLimit = limit }
        return descriptor
    }

    /// The most recent row for one song, so a display can show what's playing.
    public nonisolated static func mostRecentCapture(title: String, artistName: String) -> FetchDescriptor<Capture> {
        var descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate { $0.title == title && $0.artistName == artistName },
            sortBy: [SortDescriptor(\.capturedAt, order: .reverse)]
        )
        descriptor.fetchLimit = 1
        return descriptor
    }

    /// Rows still owed a playlist write, oldest first, skipping ones that failed too often.
    ///
    /// Only this device's rows. A row synced from the iPhone may still have
    /// `needsPlaylistWrite` set, and writing it here too would add the song twice. Rows of a
    /// device this one was restored or migrated from (``DeviceIdentity/predecessors``) are
    /// written here once they were captured before `predecessorsBefore`, so a phone that was
    /// replaced doesn't leave its radio songs out of the playlist for good.
    public nonisolated static func pendingPlaylistWrites(
        deviceID: String = DeviceIdentity.current,
        predecessors: [String] = [],
        predecessorsBefore cutoff: Date = .distantPast
    ) -> FetchDescriptor<Capture> {
        let maxAttempts = maxPlaylistWriteAttempts
        let others = predecessors.filter { $0 != deviceID }
        return FetchDescriptor<Capture>(
            predicate: #Predicate {
                $0.needsPlaylistWrite
                    && $0.playlistWriteAttempts < maxAttempts
                    && ($0.capturedByDeviceID == deviceID
                        || ($0.capturedAt < cutoff && others.contains($0.capturedByDeviceID)))
            },
            sortBy: [SortDescriptor(\.capturedAt, order: .forward)]
        )
    }

    /// Writes plays read from the connected Last.fm account. See ``LastFMHistory``.
    ///
    /// Dated when they played, marked as already scrobbled so they're never sent back, and
    /// skipped when the store already has the play (``LastFMHistory/newPlays(in:known:forgotten:policy:)``).
    /// Only the rows near the page's dates are read, so a long history costs the same per
    /// page as a short one.
    ///
    /// - Returns: how many rows were written.
    @discardableResult
    public func importScrobbles(
        _ scrobbles: [ScrobbledTrack],
        settings: CaptureSettings = CaptureSettings()
    ) throws -> Int {
        guard let earliest = scrobbles.map(\.playedAt).min(),
              let latest = scrobbles.map(\.playedAt).max()
        else { return 0 }

        let policy = settings.dedupePolicy
        // An import from Recently Played can be dated up to a day after the play it's a copy of.
        let reach = max(policy.window, policy.importWindow)
        let from = earliest.addingTimeInterval(-reach)
        let to = latest.addingTimeInterval(reach)
        let nearby = try context.fetch(FetchDescriptor<Capture>(
            predicate: #Predicate { $0.capturedAt >= from && $0.capturedAt <= to }
        ))
        let known = nearby.map {
            LastFMHistory.KnownPlay(
                key: SongIdentity.key(title: $0.title, artistName: $0.artistName),
                playedAt: $0.capturedAt,
                kind: $0.kind
            )
        }
        // Scrobbles carry their real dates, so only plays from before the song was forgotten
        // stay out.
        let forgotten = settings.forgottenSongs
        let forgottenDates = settings.forgottenSongDates
        let wanted = scrobbles.filter {
            !CaptureSettings.isForgotten(
                SongIdentity.key(title: $0.title, artistName: $0.artistName),
                playedAt: $0.playedAt, forgotten: forgotten, dates: forgottenDates
            )
        }
        let new = LastFMHistory.newPlays(in: wanted, known: known, forgotten: [], policy: policy)
        guard !new.isEmpty else { return 0 }

        var inserted: [Capture] = []
        for scrobble in new {
            let capture = Capture(
                songID: "",
                // The key a Mac capture gets (``CaptureCoordinator/metadataKey(for:)``), which
                // has no catalog ID either, so the dedupe check finds one from the other.
                songKey: "\(scrobble.title)\u{1F}\(scrobble.artistName)".lowercased(),
                title: scrobble.title,
                artistName: scrobble.artistName,
                albumTitle: scrobble.albumTitle,
                artworkURL: scrobble.artworkURL,
                kind: .lastFM,
                capturedAt: scrobble.playedAt
            )
            // Already on Last.fm. When it got there isn't in the response; when it played is
            // the closest, and it keeps the row out of the scrobble queue on every device.
            capture.scrobbledAt = scrobble.playedAt
            context.insert(capture)
            inserted.append(capture)
        }
        do {
            try context.save()
        } catch {
            // Out again, so an unrelated later save doesn't write them. The progress hasn't
            // moved, so the next run reads this page again.
            for capture in inserted { context.delete(capture) }
            throw error
        }
        return new.count
    }

    /// ``forgetSong(title:artistName:settings:)``, finding the song's plays through
    /// ``historyReader`` rather than the store.
    ///
    /// The store can't fold names the way songs are matched, so for a name that isn't plain
    /// ASCII the synchronous version reads every play on the main actor. The reader already
    /// knows which plays belong to each song.
    @discardableResult
    public func forgetSong(
        title: String,
        artistName: String,
        settings: CaptureSettings = CaptureSettings()
    ) async throws -> Int {
        let identity = SongIdentity.key(title: title, artistName: artistName)
        let ids = try await historyReader.captureIDs(ofSong: identity)
        // Checked again, in case a play was renamed since the reader looked.
        let rows = existingCaptures(ids).values.filter {
            SongIdentity.key(title: $0.title, artistName: $0.artistName) == identity
        }
        for row in rows { context.delete(row) }
        settings.noteForgotten(identity)
        if !rows.isEmpty { try context.save() }
        return rows.count
    }

    /// Deletes every row for a song (matched on title and artist) and adds it to
    /// `forgottenSongs`, so the Last.fm history import doesn't bring it back.
    @discardableResult
    public func forgetSong(
        title: String,
        artistName: String,
        settings: CaptureSettings = CaptureSettings()
    ) throws -> Int {
        let identity = SongIdentity.key(title: title, artistName: artistName)
        let rows = try context.fetch(Self.possibleMatches(title: title, artistName: artistName)).filter {
            SongIdentity.key(title: $0.title, artistName: $0.artistName) == identity
        }
        for row in rows { context.delete(row) }
        settings.noteForgotten(identity)
        if !rows.isEmpty { try context.save() }
        return rows.count
    }

    /// Rows that might be this song, for narrowing before the exact match on
    /// ``SongIdentity/key(title:artistName:)``.
    ///
    /// A predicate can't fold the way that key does, but for plain ASCII names folding is
    /// only case, and a case- and diacritic-insensitive contains finds every row whose folded
    /// name is equal ("Café" still contains "cafe"). Anything else, or a name that trims to
    /// nothing, reads every row, as before, rather than risk leaving one behind.
    static func possibleMatches(title: String, artistName: String) -> FetchDescriptor<Capture> {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedArtist = artistName.trimmingCharacters(in: .whitespacesAndNewlines)
        let isPlain = { (value: String) in
            !value.isEmpty && value.unicodeScalars.allSatisfy { $0.isASCII }
        }
        guard isPlain(trimmedTitle), isPlain(trimmedArtist) else { return allCaptures() }
        return FetchDescriptor<Capture>(
            predicate: #Predicate {
                $0.title.localizedStandardContains(trimmedTitle)
                    && $0.artistName.localizedStandardContains(trimmedArtist)
            }
        )
    }

    /// Deletes a single play. It stays on Last.fm if it was already scrobbled, since
    /// Last.fm has no public API for removing one.
    public func deletePlay(_ capture: Capture) throws {
        context.delete(capture)
        try context.save()
    }

    // MARK: - Sync reconciliation

    /// Folds duplicate stations into one.
    ///
    /// Each device creates its own `Station` row, and CloudKit has no unique constraints to
    /// prevent it. Matched on a normalised name, keeping the oldest row, so every device
    /// reaches the same result on its own copy. Done by ``writer``, off the main actor.
    @discardableResult
    public func mergeDuplicateStations() async throws -> Int {
        try await writer.mergeDuplicateStations()
    }

    /// ``mergeDuplicateStations()`` on any context. Doesn't save.
    nonisolated static func mergeDuplicateStations(in context: ModelContext) throws -> Int {
        let stations = try context.fetch(FetchDescriptor<Station>())
        var groups: [String: [Station]] = [:]
        for station in stations where !stationKey(station.name).isEmpty {
            groups[stationKey(station.name), default: []].append(station)
        }

        var merged = 0
        for group in groups.values where group.count > 1 {
            let ordered = group.sorted { ($0.firstSeenAt, $0.name) < ($1.firstSeenAt, $1.name) }
            guard let survivor = ordered.first else { continue }
            // Two exact copies (Motif's history brought over on two devices at once) can't be
            // told apart, so each device could keep a different one and delete the other's,
            // leaving neither. Both stay.
            if ordered.count > 1, (ordered[1].firstSeenAt, ordered[1].name) == (survivor.firstSeenAt, survivor.name) {
                continue
            }

            for duplicate in ordered.dropFirst() {
                // Move the sessions this device knows about. Any it doesn't are left without
                // a station when the deletion reaches the device that has them, rather than
                // deleted with it (see `Station.sessions`).
                for session in duplicate.sessions ?? [] { session.station = survivor }

                survivor.catalogID = survivor.catalogID ?? duplicate.catalogID
                survivor.artworkURL = survivor.artworkURL ?? duplicate.artworkURL
                survivor.firstSeenAt = min(survivor.firstSeenAt, duplicate.firstSeenAt)
                survivor.lastSeenAt = max(survivor.lastSeenAt, duplicate.lastSeenAt)

                context.delete(duplicate)
                merged += 1
            }
        }
        return merged
    }

    /// Folds captures of the same play made on two devices.
    ///
    /// Local dedupe can't catch these: the other device's row arrives through iCloud seconds
    /// later, and the iPhone's system player often reports what the Mac is playing. The keys
    /// differ too (iOS uses the catalog ID, macOS title and artist).
    ///
    /// Matched on title and artist within the dedupe window. Outside it, the same song is a
    /// real second play.
    ///
    /// Every device runs this on its own copy and the deletions sync, so it must pick the
    /// same row to keep whatever order rows were fetched or arrived in. If two devices each
    /// kept a different one, each would delete the row the other kept and the play would be
    /// gone from both. See ``isPreferredSurvivor(_:over:)``.
    @discardableResult
    public func mergeDuplicateCaptures(policy: DedupePolicy = CaptureSettings().dedupePolicy) throws -> Int {
        // Every kind, since an import and a witnessed row are a common pair. Grouped on title
        // and artist because IDs differ across sources (see `SongIdentity.key`).
        let captures = try context.fetch(TracksStore.allCaptures())
        var groups: [String: [Capture]] = [:]
        for capture in captures {
            let identity = SongIdentity.key(title: capture.title, artistName: capture.artistName)
            groups[identity, default: []].append(capture)
        }
        let merged = Self.mergePlays(in: groups.values, policy: policy, context: context)
        if merged > 0 { try context.save() }
        return merged
    }

    /// ``mergeDuplicateCaptures(policy:)`` without reading every capture, and off the main
    /// actor.
    ///
    /// ``historyReader`` finds, in the background, the songs with plays close enough to
    /// merge, and only those songs' plays are fetched and merged, by the same rules, in
    /// ``writer``. In a long history the full merge took seconds, and it runs after every
    /// batch from iCloud.
    @discardableResult
    public func mergeDuplicatePlays(policy: DedupePolicy = CaptureSettings().dedupePolicy) async throws -> Int {
        try await writer.mergeDuplicatePlays(reader: historyReader, policy: policy)
    }

    /// Merges the plays of the songs in `groups`, each every play of one song as a reader
    /// found them. Doesn't save.
    nonisolated static func mergeDuplicatePlays(
        _ groups: [[PersistentIdentifier]],
        in context: ModelContext,
        policy: DedupePolicy
    ) throws -> Int {
        let rows = existingCaptures(groups.flatMap { $0 }, in: context)
        var songs: [[Capture]] = []
        for ids in groups {
            let plays = ids.compactMap { rows[$0] }
            // Only a song the reader and the store agree on. If a play is gone, or has been
            // renamed into another song, since the reader looked, part of the song would be
            // merged on its own, and devices would disagree on what to keep. The next merge
            // sees it whole.
            guard plays.count == ids.count,
                  let identity = plays.first.map({ SongIdentity.key(title: $0.title, artistName: $0.artistName) }),
                  plays.allSatisfy({ SongIdentity.key(title: $0.title, artistName: $0.artistName) == identity })
            else { continue }
            songs.append(plays)
        }
        return mergePlays(in: songs, policy: policy, context: context)
    }

    /// Merges the plays of each song, given every play of the song. Doesn't save.
    private nonisolated static func mergePlays(
        in groups: some Sequence<[Capture]>,
        policy: DedupePolicy,
        context: ModelContext
    ) -> Int {
        var merged = 0
        for group in groups where group.count > 1 {
            var remaining = group
            // Again over what's left until nothing changes, so a second call finds nothing.
            // One pass can leave two survivors inside each other's window: a cluster that
            // starts with an import keeps a witnessed row up to a window later than its start,
            // which can put it within reach of the next cluster's survivor.
            while true {
                var kept: [Capture] = []
                var mergedThisPass = 0
                for cluster in playClusters(remaining, policy: policy) {
                    let ranked = cluster.sorted(by: isPreferredSurvivor)
                    let survivor = ranked[0]
                    // Exact copies, such as Motif's history brought over on two devices at
                    // once, tie, and each device would keep its own and delete the other's.
                    // Leaving a duplicate is better than losing the play everywhere.
                    if ranked.count > 1, !isPreferredSurvivor(survivor, over: ranked[1]) {
                        kept.append(contentsOf: ranked)
                        continue
                    }
                    for duplicate in ranked.dropFirst() {
                        absorb(duplicate, into: survivor)
                        context.delete(duplicate)
                        mergedThisPass += 1
                    }
                    kept.append(survivor)
                }
                merged += mergedThisPass
                remaining = kept
                if mergedThisPass == 0 || remaining.count < 2 { break }
            }
        }
        return merged
    }

    /// Splits one song's rows into plays.
    ///
    /// Oldest first, each row joins the current play if it falls inside the window measured
    /// from that play's *first* row, and otherwise starts the next play. The window is
    /// measured from the first row and not from the row that will survive, because the
    /// survivor can be a later row: measuring from it would stretch a play each time a
    /// witnessed row turned up after an import, and where plays split would depend on which
    /// rows had synced.
    ///
    /// `DedupePolicy.mergeWindow(earlier:later:)` depends only on the later row's kind, so
    /// rows that tie on time give the same plays in any order.
    nonisolated static func playClusters(_ rows: [Capture], policy: DedupePolicy) -> [[Capture]] {
        // Each read of a model's property goes through SwiftData, and a sort reads them over
        // and over: sorting a much-played song's rows took most of a merge's main-actor time.
        let keyed = rows.map { (row: $0, capturedAt: $0.capturedAt, kind: $0.kind) }
        let sorted = keyed.sorted {
            $0.capturedAt != $1.capturedAt
                ? $0.capturedAt < $1.capturedAt
                : isPreferredSurvivor($0.row, over: $1.row)
        }
        var clusters: [[Capture]] = []
        var current: [Capture] = []
        var anchor: (capturedAt: Date, kind: CaptureKind)?
        for (row, capturedAt, kind) in sorted {
            // `sorted` is oldest first, so the anchor is always the earlier of the two. An
            // import following a play gets a wide window, because it is dated when it was
            // found rather than when it played. Everything else gets the ordinary one.
            //
            // This used to skip the window check entirely whenever either row was an import,
            // which merged plays any distance apart: four plays of one song a month apart
            // collapsed into one, and an old import swallowed a real play recorded weeks
            // later. The deletions synced, so both devices lost them.
            if let anchor {
                let window = policy.mergeWindow(earlier: anchor.kind, later: kind)
                if capturedAt.timeIntervalSince(anchor.capturedAt) < window {
                    current.append(row)
                    continue
                }
                clusters.append(current)
            }
            current = [row]
            anchor = (capturedAt, kind)
        }
        if !current.isEmpty { clusters.append(current) }
        return clusters
    }

    /// Which of two rows of one play to keep. A strict total order on what every device's
    /// copy of a row agrees on, so every device keeps the same one.
    ///
    /// A witnessed row beats an import, however they're dated. It used to be the oldest row
    /// that survived, and an import is dated when it was found, often before the witnessed
    /// row of the same play synced in, so the import won and the witnessed row was deleted,
    /// taking its kind, its session, a pending playlist write and its scrobble state with it.
    /// Radio beats on demand for the same reason: only radio rows go to the playlist and
    /// belong to a session, so it's the row with more to lose.
    ///
    /// Among rows of the same kind the oldest wins, then fields that only break exact ties.
    nonisolated static func isPreferredSurvivor(_ lhs: Capture, over rhs: Capture) -> Bool {
        let lhsRank = survivorRank(lhs.kind), rhsRank = survivorRank(rhs.kind)
        if lhsRank != rhsRank { return lhsRank < rhsRank }
        if lhs.capturedAt != rhs.capturedAt { return lhs.capturedAt < rhs.capturedAt }
        if lhs.kindRawValue != rhs.kindRawValue { return lhs.kindRawValue < rhs.kindRawValue }
        if lhs.capturedByDeviceID != rhs.capturedByDeviceID {
            return lhs.capturedByDeviceID < rhs.capturedByDeviceID
        }
        if lhs.songKey != rhs.songKey { return lhs.songKey < rhs.songKey }
        if lhs.songID != rhs.songID { return lhs.songID < rhs.songID }
        return (lhs.addedToPlaylistAt != nil ? 0 : 1, lhs.scrobbledAt != nil ? 0 : 1)
            < (rhs.addedToPlaylistAt != nil ? 0 : 1, rhs.scrobbledAt != nil ? 0 : 1)
    }

    private nonisolated static func survivorRank(_ kind: CaptureKind) -> Int {
        switch kind {
        case .radio: 0
        case .onDemand: 1
        // Before an import, because its time is when the song played.
        case .lastFM: 2
        case .imported: 3
        }
    }

    /// Moves what a duplicate knew onto the row that stays, so deleting it loses nothing.
    private nonisolated static func absorb(_ duplicate: Capture, into survivor: Capture) {
        // Check artwork is loadable, not just present: it may be a `musicKit://` URL (see
        // `ArtworkURL`).
        if !isLoadableArtwork(survivor.artworkURL) {
            survivor.artworkURL = duplicate.artworkURL
        }
        if survivor.albumTitle == nil { survivor.albumTitle = duplicate.albumTitle }
        if survivor.playedBackAt == nil { survivor.playedBackAt = duplicate.playedBackAt }
        if survivor.session == nil { survivor.session = duplicate.session }

        // Already in the playlist, so don't write it again.
        if duplicate.addedToPlaylistAt != nil {
            survivor.addedToPlaylistAt = survivor.addedToPlaylistAt ?? duplicate.addedToPlaylistAt
        }
        if survivor.addedToPlaylistAt != nil { survivor.needsPlaylistWrite = false }

        // Already on Last.fm, so the survivor's device mustn't send it again.
        if survivor.scrobbledAt == nil { survivor.scrobbledAt = duplicate.scrobbledAt }
    }

    /// See ``ArtworkURL``.
    public nonisolated static func isLoadableArtwork(_ urlString: String?) -> Bool {
        ArtworkURL.isLoadable(urlString)
    }

    /// Rows that have a cover address of any kind.
    ///
    /// Whether it loads is decided by parsing the URL, which a predicate can't do, but most
    /// rows from before a cover was found have none and never need reading.
    static func capturesWithArtwork() -> FetchDescriptor<Capture> {
        FetchDescriptor<Capture>(
            predicate: #Predicate { $0.artworkURL != nil },
            sortBy: [SortDescriptor(\.capturedAt, order: .reverse)]
        )
    }

    /// Forgets artwork nothing can load, so the ordinary backfill fetches it again.
    ///
    /// Runs on the main actor after every synced change, so the store narrows it to covers
    /// that don't start `http://` or `https://`. Reading every row with a cover took two
    /// seconds in a long history, and nearly all of them load.
    @discardableResult
    public func clearUnloadableArtwork() throws -> Int {
        let rows = try context.fetch(Self.possiblyUnloadableArtwork())
            .filter { !Self.isLoadableArtwork($0.artworkURL) }
        guard !rows.isEmpty else { return 0 }
        for row in rows { row.artworkURL = nil }
        try context.save()
        return rows.count
    }

    /// ``clearUnloadableArtwork()``, finding the rows through ``historyReader`` and clearing
    /// them in ``writer``. The store can't index the narrowing, so even that scanned every row
    /// on the main actor, after every synced change.
    @discardableResult
    public func clearUnloadableArtworkFoundInBackground() async throws -> Int {
        try await writer.clearUnloadableArtwork(reader: historyReader)
    }

    /// Rows whose cover address doesn't start with a lowercase `http://` or `https://`: a
    /// superset of those ``ArtworkURL`` can't load, which also parses scheme case.
    static func possiblyUnloadableArtwork() -> FetchDescriptor<Capture> {
        FetchDescriptor<Capture>(
            predicate: #Predicate { capture in
                if let url = capture.artworkURL {
                    !url.starts(with: "http://") && !url.starts(with: "https://")
                } else {
                    false
                }
            }
        )
    }

    /// Every cover address in the history, once each. What ``ArtworkRepair`` probes.
    ///
    /// From ``historyReader``: reading every row with a cover took seconds on the main actor.
    public func artworkURLs() async throws -> [String] {
        try await historyReader.artworkURLs()
    }

    /// Forgets the given covers wherever they appear, leaving the rows for the backfill to
    /// fill in again. Done by ``writer``, so it saves only what it changes.
    ///
    /// - Returns: how many rows were cleared, which is usually more than `urls.count` since
    ///   an album's songs share a cover.
    @discardableResult
    public func clearArtworkInBackground(urls: Set<String>) async throws -> Int {
        try await writer.clearArtwork(urls: urls)
    }

    /// ``clearArtworkInBackground(urls:)`` on the main context, saving it along with anything
    /// else the main context has changed. The app clears through the writer.
    @discardableResult
    public func clearArtwork(urls: Set<String>) throws -> Int {
        let rows = try Self.capturesWithArtwork(in: urls, context: context)
        guard !rows.isEmpty else { return 0 }
        for row in rows { row.artworkURL = nil }
        try context.save()
        return rows.count
    }

    /// The rows showing any of these covers, a few hundred addresses per query.
    nonisolated static func capturesWithArtwork(in urls: Set<String>, context: ModelContext) throws -> [Capture] {
        let all = Array(urls)
        var rows: [Capture] = []
        for start in stride(from: 0, to: all.count, by: 500) {
            let chunk = Array(all[start..<min(start + 500, all.count)])
            rows += try context.fetch(FetchDescriptor<Capture>(
                predicate: #Predicate { capture in
                    if let url = capture.artworkURL {
                        chunk.contains(url)
                    } else {
                        false
                    }
                }
            ))
        }
        return rows
    }

    /// Case- and accent-insensitive station name.
    nonisolated static func stationKey(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    // MARK: - Scrobbling

    public nonisolated static let maxScrobbleAttempts = 3

    /// Songs owed a scrobble, oldest first.
    ///
    /// Only this device's rows, or every device would scrobble every synced row. Rows imported
    /// from Recently Played by earlier versions are left out unless `includingImported`, since
    /// they're dated when found.
    public nonisolated static func pendingScrobbles(
        deviceID: String = DeviceIdentity.current,
        includingImported: Bool,
        limit: Int = 50
    ) -> FetchDescriptor<Capture> {
        let maxAttempts = maxScrobbleAttempts
        let imported = CaptureKind.imported.rawValue
        var descriptor = FetchDescriptor<Capture>(
            predicate: #Predicate {
                $0.scrobbledAt == nil
                    && $0.scrobbleAttempts < maxAttempts
                    && $0.capturedByDeviceID == deviceID
                    && (includingImported || $0.kindRawValue != imported)
            },
            sortBy: [SortDescriptor(\.capturedAt, order: .forward)]
        )
        descriptor.fetchLimit = limit
        return descriptor
    }
}

/// Plays kept in a file of their own while the library on disk can't be opened, so a run on
/// the in-memory fallback doesn't lose them at quit.
///
/// Each save of the fallback store writes its whole history here (only the plays since the
/// library became unavailable, so it stays small). A later run that falls back again starts
/// from them, and the first run that opens the library saves them into it and lets the file
/// go. Sessions aren't kept: plays are what can't be got back.
struct UnsavedPlays: Sendable {
    let file: URL

    /// The app's own container rather than the App Group's, which may be what failed.
    static let standard = UnsavedPlays(
        file: URL.applicationSupportDirectory.appending(path: "Tracks/Unsaved Plays.json")
    )

    /// The most kept, newest first. A build that can never open the library would otherwise
    /// grow the file with every save.
    static let limit = 5_000

    private static let log = Logger(subsystem: "com.luke.tracks", category: "UnsavedPlays")

    /// One play as the store holds it, with what has been done with it.
    struct Play: Codable, Sendable, Equatable {
        var songID: String
        var songKey: String
        var title: String
        var artistName: String
        var albumTitle: String?
        var artworkURL: String?
        var kind: String
        var capturedAt: Date
        var platform: String
        var needsPlaylistWrite: Bool
        var addedToPlaylistAt: Date?
        var playlistWriteAttempts: Int
        var playedBackAt: Date?
        var scrobbledAt: Date?
        var scrobbleAttempts: Int
        var lastScrobbleError: String?
        var deviceID: String
        var source: String?

        init(_ capture: Capture) {
            songID = capture.songID
            songKey = capture.songKey
            title = capture.title
            artistName = capture.artistName
            albumTitle = capture.albumTitle
            artworkURL = capture.artworkURL
            kind = capture.kindRawValue
            capturedAt = capture.capturedAt
            platform = capture.platformRawValue
            needsPlaylistWrite = capture.needsPlaylistWrite
            addedToPlaylistAt = capture.addedToPlaylistAt
            playlistWriteAttempts = capture.playlistWriteAttempts
            playedBackAt = capture.playedBackAt
            scrobbledAt = capture.scrobbledAt
            scrobbleAttempts = capture.scrobbleAttempts
            lastScrobbleError = capture.lastScrobbleError
            deviceID = capture.capturedByDeviceID
            source = capture.sourceRawValue
        }

        /// The play as a new row, which `context` holds until it saves.
        func insert(into context: ModelContext) {
            let capture = Capture(
                songID: songID,
                songKey: songKey,
                title: title,
                artistName: artistName,
                albumTitle: albumTitle,
                artworkURL: artworkURL,
                kind: CaptureKind(rawValue: kind) ?? .radio,
                capturedAt: capturedAt,
                platform: CapturePlatform(rawValue: platform) ?? .current,
                deviceID: deviceID
            )
            capture.kindRawValue = kind
            capture.sourceRawValue = source
            capture.needsPlaylistWrite = needsPlaylistWrite
            capture.addedToPlaylistAt = addedToPlaylistAt
            capture.playlistWriteAttempts = playlistWriteAttempts
            capture.playedBackAt = playedBackAt
            capture.scrobbledAt = scrobbledAt
            capture.scrobbleAttempts = scrobbleAttempts
            capture.lastScrobbleError = lastScrobbleError
            context.insert(capture)
        }

        /// Whether `context`'s store already has this play, from an earlier save of the file
        /// whose clean-up didn't happen.
        func isHeld(in context: ModelContext) throws -> Bool {
            let (key, kind, at) = (songKey, kind, capturedAt)
            var descriptor = FetchDescriptor<Capture>(
                predicate: #Predicate { $0.songKey == key && $0.kindRawValue == kind && $0.capturedAt == at }
            )
            descriptor.fetchLimit = 1
            return try context.fetchCount(descriptor) > 0
        }
    }

    /// What the file holds. A file that can't be read is moved aside, never overwritten, and
    /// reads as nothing.
    func read() -> [Play] {
        let data: Data
        do {
            data = try Data(contentsOf: file)
        } catch {
            return []
        }
        do {
            return try JSONDecoder().decode([Play].self, from: data)
        } catch {
            let aside = file.deletingPathExtension().appendingPathExtension("unreadable.json")
            try? FileManager.default.removeItem(at: aside)
            try? FileManager.default.moveItem(at: file, to: aside)
            Self.log.error("Moved aside unsaved plays that couldn't be read: \(error.localizedDescription, privacy: .public)")
            return []
        }
    }

    /// Replaces what the file holds with `plays`, newest first up to ``limit``.
    func write(_ plays: [Play]) throws {
        let kept = plays.sorted { $0.capturedAt > $1.capturedAt }.prefix(Self.limit)
        if plays.count > kept.count {
            Self.log.error("Keeping only the newest \(Self.limit) of \(plays.count) unsaved plays")
        }
        try FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try JSONEncoder().encode(Array(kept)).write(to: file, options: .atomic)
    }

    func remove() {
        try? FileManager.default.removeItem(at: file)
    }

    /// Puts what earlier runs kept aside into `container`, a fallback store that starts empty.
    func restore(into container: ModelContainer) {
        let plays = read()
        guard !plays.isEmpty else { return }
        let context = ModelContext(container)
        for play in plays { play.insert(into: context) }
        do {
            try context.save()
        } catch {
            Self.log.error("Couldn't restore unsaved plays: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Writes a fallback store's history to the file, one save at a time.
    actor Keeper {
        private let unsavedPlays: UnsavedPlays
        private let container: ModelContainer

        init(_ unsavedPlays: UnsavedPlays, container: ModelContainer) {
            self.unsavedPlays = unsavedPlays
            self.container = container
        }

        func keep() {
            do {
                let captures = try ModelContext(container).fetch(FetchDescriptor<Capture>())
                try unsavedPlays.write(captures.map(Play.init))
            } catch {
                UnsavedPlays.log.error("Couldn't keep plays aside: \(error.localizedDescription, privacy: .public)")
            }
        }
    }
}

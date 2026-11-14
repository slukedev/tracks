import Foundation
import Observation
import SwiftData
import MusicKit
import TracksCore
import TracksMusic

/// The headless probe's commands: the environment, the playlist write path, the store, the
/// catalog and Last.fm, each logged for the command line. See `HeadlessProbe`.
@MainActor
@Observable
final class ProbeModel {
    let log = ProbeLog()

    private(set) var environment: [EnvironmentRow] = []
    private(set) var probePlaylistID: String?
    private(set) var isWriting = false

    /// Opened once to check the App Group container is reachable. A failure is shown on
    /// screen.
    private let storeStatus: String = {
        do {
            return try TracksStore.shared().backing.description
        } catch {
            return "failed: \(error)"
        }
    }()

    // MARK: - Environment

    /// One line of the Environment section. Keys must be unique since they're the
    /// `ForEach` ids, so `set(_:_:)` updates in place.
    struct EnvironmentRow: Identifiable, Equatable {
        let key: String
        var value: String
        var id: String { key }
    }

    private func set(_ key: String, _ value: String) {
        if let index = environment.firstIndex(where: { $0.key == key }) {
            environment[index].value = value
        } else {
            environment.append(EnvironmentRow(key: key, value: value))
        }
    }

    func loadEnvironment() async {
        // Show what we know before the authorization prompt, which can sit unanswered.
        set("Platform", CapturePlatform.current.rawValue)
        set("App Group", AppGroup.identifier ?? "‹not configured›")
        set("Group container", AppGroup.containerURL?.path ?? "‹unavailable›")
        set("SwiftData store", storeStatus)

        let status = MusicAuthorization.currentStatus
        if status == .notDetermined {
            set("MusicKit authorization", "\(status), waiting for you to answer the prompt")
            let requested = await MusicAuthorization.request()
            set("MusicKit authorization", String(describing: requested))
        } else {
            set("MusicKit authorization", String(describing: status))
        }

        do {
            let subscription = try await MusicSubscription.current
            set("Can play catalog content", String(subscription.canPlayCatalogContent))
            set("Has cloud library", String(subscription.hasCloudLibraryEnabled))
            set("Can become subscriber", String(subscription.canBecomeSubscriber))
        } catch {
            set("Subscription", "error: \(error.localizedDescription)")
        }

        log.append(
            .environment,
            "Environment loaded",
            fields: environment.map { (key: $0.key, value: $0.value) }
        )
    }

    // MARK: - Playlist write path

    func testCreatePlaylist() async {
        isWriting = true
        defer { isWriting = false }

        let name = "Tracks Probe \(Date.now.formatted(date: .abbreviated, time: .shortened))"
        let writer = MusicKitPlaylistWriter { [log] transport in
            Task { @MainActor in
                log.append(.playlistWrite, "Transport used", fields: [("transport", transport.rawValue)])
            }
        }

        do {
            let id = try await writer.createPlaylist(
                name: name,
                description: "Throwaway playlist from the Tracks probe. Safe to delete."
            )
            probePlaylistID = id
            log.append(.playlistWrite, "Created playlist", fields: [
                ("name", name),
                ("id", id),
            ])
        } catch {
            log.append(.error, "Create playlist failed", fields: Self.errorFields(error))
        }
    }

    /// Prefix of every playlist the probe creates.
    static let probePlaylistPrefix = "Tracks Probe"

    /// Lists the probe's throwaway playlists so they can be deleted by hand. The Apple Music
    /// API can't delete a library playlist: DELETE returns 401 even with tokens that can
    /// create and add.
    func listProbePlaylists() async {
        do {
            let playlists = try await MusicKitPlaylistWriter().listPlaylists()
            let ours = playlists.filter { $0.name.hasPrefix(Self.probePlaylistPrefix) }
            guard !ours.isEmpty else {
                log.append(.playlistWrite, "No probe playlists found")
                return
            }
            log.append(
                .playlistWrite,
                "Delete these by hand in Music (the API cannot remove them)",
                fields: ours.map { (key: $0.id, value: $0.name) }
            )
        } catch {
            log.append(.error, "Could not list playlists", fields: Self.errorFields(error))
        }
    }

    /// Clears the give-up counters on captures the catalog couldn't identify, then retries.
    /// Useful after matching improves.
    ///
    /// Every kind, since on-demand rows need an id for their artwork too. Only radio rows go
    /// back in the playlist queue.
    func retryUnresolved() async {
        do {
            let store = try TracksStore.shared()
            let captures = try store.context.fetch(TracksStore.allCaptures())
            let stuck = captures.filter { $0.songID.isEmpty && $0.catalogLookupAttempts > 0 }
            for capture in stuck {
                capture.catalogLookupAttempts = 0
                capture.needsPlaylistWrite = capture.kind == .radio && capture.addedToPlaylistAt == nil
                capture.lastPlaylistWriteError = nil
            }
            try store.context.save()
            log.append(.environment, "Reset lookup attempts", fields: [("count", String(stuck.count))])

            let coordinator = CaptureCoordinator(
                store: store,
                playlistWriter: MusicKitPlaylistWriter(),
                catalogResolver: CatalogLookup()
            )
            await coordinator.drainPendingWrites()
            dumpCaptures()
        } catch {
            log.append(.error, "Could not retry", fields: Self.errorFields(error))
        }
    }

    /// Drains the Last.fm queue on demand and logs the result, so signing, session and
    /// batching can be tested without waiting for a capture.
    func sendScrobbles() async {
        do {
            let store = try TracksStore.shared()
            let scrobbler = ScrobbleService(store: store)
            guard scrobbler.isConnected else {
                log.append(.error, "No Last.fm account is connected")
                return
            }
            let owedBefore = try store.context.fetch(TracksStore.pendingScrobbles(includingImported: true)).count
            let sent = await scrobbler.drain()
            log.append(.environment, "Scrobbled", fields: [
                ("owed before", String(owedBefore)),
                ("sent", String(sent)),
                ("error", scrobbler.lastError ?? "‹none›"),
            ])
        } catch {
            log.append(.error, "Could not scrobble", fields: Self.errorFields(error))
        }
    }

    /// Whether Last.fm is usable, and what is owed to it.
    func dumpLastFM() {
        var fields: [(key: String, value: String)] = [
            ("application configured", String(LastFMCredentials.isConfigured)),
            ("api key", LastFMCredentials.apiKey.map { String($0.prefix(6)) + "…" } ?? "‹none›"),
            ("account", LastFMSessionStore.current?.username ?? "‹not connected›"),
            ("scrobbling enabled", String(CaptureSettings().scrobblesToLastFM)),
        ]
        do {
            let store = try TracksStore.shared()
            let owed = try store.context.fetch(TracksStore.pendingScrobbles(includingImported: true))
            fields.append(("owed a scrobble", String(owed.count)))
            fields.append(("oldest owed", owed.first.map { "\($0.title) by \($0.artistName)" } ?? "‹none›"))
            let failed = try store.context.fetch(TracksStore.allCaptures())
                .filter { $0.lastScrobbleError != nil }
            fields.append(("rows with a scrobble error", String(failed.count)))
            if let first = failed.first, let error = first.lastScrobbleError {
                fields.append(("last error", error))
            }
        } catch {
            fields.append(("store", "failed: \(error.localizedDescription)"))
        }
        log.append(.environment, "Last.fm", fields: fields)
    }

    /// Removes a song and stops the Last.fm import bringing it back. Logs the matches first,
    /// since the argument is a substring.
    func forgetSong(matching text: String) {
        do {
            let store = try TracksStore.shared()
            let needle = text.lowercased()
            let matches = try store.context.fetch(TracksStore.allCaptures()).filter {
                $0.title.lowercased().contains(needle)
            }
            guard !matches.isEmpty else {
                log.append(.environment, "Nothing matched", fields: [("text", text)])
                return
            }

            log.append(.environment, "Removing \(matches.count)", fields: matches.map {
                (key: $0.kind.rawValue, value: "\($0.title) by \($0.artistName)")
            })

            var removed = 0
            for song in Set(matches.map { "\($0.title)\u{1F}\($0.artistName)" }) {
                let parts = song.components(separatedBy: "\u{1F}")
                removed += (try? store.forgetSong(title: parts[0], artistName: parts[1])) ?? 0
            }
            log.append(.environment, "Removed", fields: [
                ("rows", String(removed)),
            ])
        } catch {
            log.append(.error, "Could not remove", fields: Self.errorFields(error))
        }
    }

    /// Sets the menu bar style without going through Settings.
    func setMenuBarStyle(_ raw: String) {
        guard let style = MenuBarLabelStyle(rawValue: raw) else {
            log.append(.error, "Unknown style", fields: [
                ("given", raw),
                ("known", MenuBarLabelStyle.allCases.map(\.rawValue).joined(separator: ", ")),
            ])
            return
        }
        CaptureSettings().menuBarLabelStyle = style
        log.append(.environment, "Menu bar style", fields: [("style", style.name)])
    }

    /// Dumps the stations Apple Music remembers, with the fields that identify them.
    func dumpStations() async {
        do {
            let stations = try await CatalogLookup().recentStations()
            guard !stations.isEmpty else {
                log.append(.environment, "No recently played stations")
                return
            }
            log.append(.environment, "Recently played stations (\(stations.count))", fields: stations.map {
                (key: $0.id, value: "\($0.name), provider \($0.providerName ?? "‹nil›"), live \($0.isLive)")
            })
        } catch {
            log.append(.error, "Could not read stations", fields: Self.errorFields(error))
        }
    }

    /// Dumps stored captures with the fields that usually explain a complaint: whether a
    /// catalog id was resolved, whether artwork was found, and whether the playlist write
    /// succeeded.
    func dumpCaptures(limit: Int = 20) {
        do {
            let store = try TracksStore.shared()
            // All kinds, not only radio.
            let captures = try store.context.fetch(TracksStore.allCaptures(limit: limit))
            guard !captures.isEmpty else {
                log.append(.environment, "No captures stored")
                return
            }
            for capture in captures {
                log.append(.environment, "\(capture.title) by \(capture.artistName)", fields: [
                    ("capturedAt", capture.capturedAt.formatted(.iso8601)),
                    ("songID", capture.songID.isEmpty ? "‹unresolved›" : capture.songID),
                    // Kind and songKey are the dedupe identity; two rows for one song differ here.
                    ("kind", capture.kind.rawValue),
                    ("songKey", capture.songKey),
                    ("platform", capture.platformRawValue),
                    ("device", capture.capturedByDeviceID.isEmpty ? "‹pre-sync›" : String(capture.capturedByDeviceID.prefix(8))),
                    ("albumTitle", capture.albumTitle ?? "‹none›"),
                    ("artworkURL", capture.artworkURL ?? "‹none›"),
                    ("addedToPlaylist", capture.addedToPlaylistAt?.formatted(.iso8601) ?? "no"),
                    ("playedBackAt", capture.playedBackAt?.formatted(.iso8601) ?? "no"),
                    ("needsPlaylistWrite", String(capture.needsPlaylistWrite)),
                    ("catalogLookupAttempts", String(capture.catalogLookupAttempts)),
                    ("lastError", capture.lastPlaylistWriteError ?? "‹none›"),
                ])
            }
        } catch {
            log.append(.error, "Could not read captures", fields: Self.errorFields(error))
        }
    }

    /// Computes the statistics screen's numbers without the screen.
    ///
    /// The calculator has tests; this checks the mapping out of SwiftData. Note that
    /// `session?.station?.name` is nil for any capture whose tune-in the app didn't see, so
    /// empty station counts can mean thin data rather than a bug.
    func dumpStats() {
        do {
            let store = try TracksStore.shared()
            let captures = try store.context.fetch(TracksStore.radioCaptures())
            let sessions = try store.context.fetch(FetchDescriptor<Session>())

            let captureStats = captures.map {
                CaptureStat(
                    songKey: $0.songKey,
                    title: $0.title,
                    artistName: $0.artistName,
                    capturedAt: $0.capturedAt,
                    stationName: $0.session?.station?.name
                )
            }
            let sessionStats = sessions.map {
                SessionStat(
                    startedAt: $0.startedAt,
                    finishedAt: $0.endedAt ?? $0.lastActivityAt,
                    stationName: $0.station?.name
                )
            }

            log.append(.environment, "Source rows", fields: [
                ("store", store.backing.description),
                ("radio captures", String(captureStats.count)),
                ("sessions", String(sessionStats.count)),
                ("captures naming a station", String(captureStats.count { $0.stationName != nil })),
                ("sessions naming a station", String(sessionStats.count { $0.stationName != nil })),
            ])

            for range in StatsRange.allCases {
                let summary = StatsCalculator.summary(
                    range: range,
                    captures: captureStats,
                    sessions: sessionStats
                )
                log.append(.environment, range.rawValue, fields: [
                    ("songs captured", String(summary.captureCount)),
                    ("different songs", String(summary.uniqueSongCount)),
                    ("artists", String(summary.uniqueArtistCount)),
                    ("sessions", String(summary.sessionCount)),
                    ("listening (estimated)", Duration.seconds(summary.listeningSeconds)
                        .formatted(.units(allowed: [.hours, .minutes, .seconds]))),
                    ("radio sessions", Duration.seconds(summary.radioSessionSeconds)
                        .formatted(.units(allowed: [.hours, .minutes, .seconds]))),
                    ("first time heard", summary.firstTimeHeardRate
                        .map { "\(summary.firstTimeHeardCount) (\($0.formatted(.percent.precision(.fractionLength(0)))))" }
                        ?? "‹no captures in range›"),
                    ("top artists", summary.topArtists.isEmpty
                        ? "‹none›"
                        : summary.topArtists.map { "\($0.name) ×\($0.count)" }.joined(separator: ", ")),
                    ("top stations", summary.topStations.isEmpty
                        ? "‹none›"
                        : summary.topStations.map { "\($0.name) ×\($0.count)" }.joined(separator: ", ")),
                    ("busiest", summary.busiestCell.map { "weekday \($0.weekday), hour \($0.hour) (\($0.count))" }
                        ?? "‹nothing yet›"),
                ])
            }
        } catch {
            log.append(.error, "Could not compute statistics", fields: Self.errorFields(error))
        }
    }

    /// Lists every library playlist, to confirm by name that the app's was created.
    func listAllPlaylists() async {
        do {
            let playlists = try await MusicKitPlaylistWriter().listPlaylists()
            log.append(
                .playlistWrite,
                "Library playlists (\(playlists.count))",
                fields: playlists.map { (key: $0.id, value: $0.name) }
            )
        } catch {
            log.append(.error, "Could not list playlists", fields: Self.errorFields(error))
        }
    }

    /// Searches the catalog, to find a valid song id for this storefront.
    func searchCatalog(term: String) async {
        do {
            let candidates = try await CatalogLookup().candidates(for: term, limit: 5)
            log.append(.playlistWrite, "Catalog search: \(term)", fields: candidates.map {
                (key: $0.id, value: "\($0.title) by \($0.artistName) [\($0.albumTitle ?? "?")] \($0.duration.map { String(format: "%.0fs", $0) } ?? "")")
            })
        } catch {
            log.append(.error, "Catalog search failed", fields: Self.errorFields(error))
        }
    }

    /// Adds a known song id, to test the add endpoint without anything playing.
    func testAddSong(id songID: String) async {
        guard let playlistID = probePlaylistID else { return }
        isWriting = true
        defer { isWriting = false }

        let writer = MusicKitPlaylistWriter { [log] transport in
            Task { @MainActor in
                log.append(.playlistWrite, "Transport used", fields: [("transport", transport.rawValue)])
            }
        }
        do {
            try await writer.addSongs(ids: [songID], toPlaylist: playlistID)
            log.append(.playlistWrite, "Added song (expect HTTP 204, no body)", fields: [
                ("songID", songID),
                ("playlistID", playlistID),
            ])
        } catch {
            log.append(.error, "Add song failed", fields: Self.errorFields(error))
        }
    }

    static func errorFields(_ error: any Error) -> [(key: String, value: String)] {
        var fields: [(key: String, value: String)] = [("error", String(describing: error))]
        if let writeError = error as? PlaylistWriteError {
            fields.append(("retryable", String(writeError.isRetryable)))
            if let guidance = writeError.guidance {
                fields.append(("what to do", guidance))
            }
        }
        if let dataError = error as? MusicDataRequest.Error {
            fields.append(("status", String(dataError.status)))
            fields.append(("code", String(dataError.code)))
            fields.append(("title", dataError.title))
            fields.append(("detail", dataError.detailText))
        }
        return fields
    }
}

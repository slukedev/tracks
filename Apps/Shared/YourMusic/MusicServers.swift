import Foundation
import Observation
import OSLog
import TracksCore
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Your music servers: the ones you've connected, whether each can be reached, and the songs
/// on each, synced so Tracks Radio, the mixes and search can reach every one of them.
///
/// Most servers speak the Subsonic API and are asked as they always have been. A Trackside
/// server is asked through its own API: it's reached at whichever of its addresses answers
/// best, its library comes down as changes rather than whole, and it says when something
/// changed while Tracks is open.
@MainActor
@Observable
final class MusicServers {
    enum Status: Equatable {
        case connecting
        case online
        case offline
        /// The password was refused, or for Trackside, this device's key.
        case wrongPassword
        case failed(String)
    }

    private(set) var servers: [SubsonicServer] = []
    private(set) var status: [String: Status] = [:]
    /// Every song on each server, as of its last sync.
    private(set) var catalogs: [String: [LocalTrack]] = [:] {
        didSet { catalogRevision &+= 1 }
    }
    /// Bumped whenever ``catalogs`` changes, so following it doesn't mean comparing them.
    private(set) var catalogRevision = 0
    /// Each server's artists, by folded name: their id there, for artists like them, and their
    /// picture's cover id, for artist pages and circles. A server not asked yet isn't here.
    private(set) var artists: [String: [String: ServerArtistRef]] = [:]
    private(set) var syncing: Set<String> = []
    private(set) var lastSynced: [String: Date] = [:]
    /// Servers not told when a song plays from them. Everyone else's are, so the server's own
    /// counts stay right.
    private(set) var quietServers: Set<String> = Set(UserDefaults.standard.stringArray(forKey: MusicServers.quietKey) ?? [])
    /// Songs you've asked each server to keep, until a sync brings them in. A server that
    /// fetches songs on request gets the file first, which takes minutes.
    private(set) var kept: [String: ServerKeeps] = MusicServers.loadKept()
    /// Entries that were another server before they were Trackside, whose downloads and
    /// playlists may still name the old server's songs.
    private(set) var movedToTrackside: Set<String> = Set(UserDefaults.standard.stringArray(forKey: MusicServers.movedKey) ?? [])
    /// For each Trackside server that offers it, the songs search found for it to get and how
    /// each one asked for is going. Absent for every other server.
    private(set) var songRequests: [String: SongRequestBoard] = [:]

    @ObservationIgnored private var subsonic: [String: SubsonicClient] = [:]
    @ObservationIgnored private var trackside: [String: TracksideConnection] = [:]
    /// Searches that make a server look songs up elsewhere go through here, a few at a time:
    /// asked for dozens at once, its own lookups are refused and everything slows.
    @ObservationIgnored let lookups = AsyncLimiter(limit: 3)
    /// Trackside answers from its own index, so it's asked more at once.
    @ObservationIgnored private let tracksideLookups = AsyncLimiter(limit: 8)
    /// Called when a server's reachability or songs change, so suggestions are judged again.
    @ObservationIgnored var onStatusChange: (() -> Void)?
    /// Called with songs asked for that a sync has brought in.
    @ObservationIgnored var onKeptArrived: (([LocalTrack]) -> Void)?
    /// Called with a server's id once it's removed.
    @ObservationIgnored var onRemoved: ((String) -> Void)?
    /// One sync at a time for each server, tied to the server as it was when it started.
    @ObservationIgnored private let syncs = ServerSyncs()
    /// For each Subsonic server asked for songs, what watches for them to arrive.
    @ObservationIgnored private var keepWatches: [String: ServerKeepWatch] = [:]
    /// Plays waiting to be told to each Subsonic server. Trackside's are its connection's.
    @ObservationIgnored private var subsonicOutboxes: [String: ListenOutbox] = [:]
    /// Servers whose password couldn't be read yet, as before the first unlock: they're out of
    /// reach rather than signed out, and the password is read again on the next connect.
    @ObservationIgnored private var lockedKeychain: Set<String> = []
    nonisolated private static let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Tracks", category: "MusicServers")
    /// Songs asked of a Trackside server before a sync had brought them in, by identity.
    @ObservationIgnored private var awaitingSync: [String: Set<String>] = [:]
    @ObservationIgnored private let isDemo: Bool
    /// Reading the saved songs at launch, off the main actor: a big library takes a moment.
    /// Connecting and syncing wait for it.
    @ObservationIgnored private var loading: Task<Void, Never>?
    /// Whether Tracks is in front, which is when Trackside servers' events are listened to.
    @ObservationIgnored private var isForeground = true
    @ObservationIgnored private var lifecycle: [any NSObjectProtocol] = []
    /// Trackside Cloud, while it's the sync backend and its server is connected.
    private(set) var cloudSync: TracksideCloudSync?

    private static let storageKey = "musicServers"
    private static let quietKey = "musicServersNotToldOfPlays"
    private static let keptKey = "musicServersKeptSongs"
    private static let movedKey = "musicServersMovedToTrackside"
    /// Songs asked for at a time while syncing.
    nonisolated private static let pageSize = 500
    /// Syncing stops here, far past any library a phone should hold at once.
    nonisolated private static let catalogLimit = 60_000

    init(isDemo: Bool) {
        self.isDemo = isDemo
        guard !isDemo else { return }
        if let data = UserDefaults.standard.data(forKey: Self.storageKey),
           let saved = StoredJSON.decodeEach([SubsonicServer].self, from: data, keepingCopyAt: LibraryFolders.index.appending(path: "music-servers.json")) {
            servers = saved
        }
        #if DEBUG
        let test = addTestServer()
        #endif
        for server in servers {
            openFromKeychain(server)
        }
        let ids = servers.map(\.id)
        loading = Task { [self] in
            let saved = await Self.loadSaved(ids)
            for (id, catalog, artists) in saved where servers.contains(where: { $0.id == id }) {
                // A sync that landed first is newer.
                if catalogs[id] == nil, !catalog.isEmpty { catalogs[id] = catalog }
                if self.artists[id] == nil, let artists { self.artists[id] = artists }
            }
            loading = nil
        }
        #if DEBUG
        // An unsigned build has no keychain.
        if let test { open(test.server, secret: test.password) }
        #endif
        followForeground()
    }

    #if DEBUG
    /// `-TracksTestServer "http://127.0.0.1:4533|user|password|Name"`: connects a test server at
    /// launch, for UI checks against a local server without typing into the form.
    private func addTestServer() -> (server: SubsonicServer, password: String)? {
        guard let value = UserDefaults.standard.string(forKey: "TracksTestServer") else { return nil }
        let parts = value.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 3, let url = URL(string: parts[0]) else { return nil }
        let id = "test-server"
        let server = SubsonicServer(id: id, name: parts.count > 3 ? parts[3] : "Test Server", url: url, username: parts[1], salt: "testsalt")
        ServerKeychain.setPassword(parts[2], for: id)
        if let index = servers.firstIndex(where: { $0.id == id }) {
            servers[index] = server
        } else {
            servers.append(server)
        }
        save()
        return (server, parts[2])
    }
    #endif

    /// Opens a server with the secret the Keychain holds for it, if it can be read now.
    /// - Returns: whether it opened.
    @discardableResult
    private func openFromKeychain(_ server: SubsonicServer) -> Bool {
        switch ServerKeychain.read(for: ServerCredential.account(for: server)) {
        case .found(let secret):
            lockedKeychain.remove(server.id)
            open(server, secret: secret)
            return true
        case .unavailable:
            lockedKeychain.insert(server.id)
            return false
        case .missing:
            lockedKeychain.remove(server.id)
            return false
        }
    }

    /// Makes the client for a server with its secret: a password, or a Trackside device key.
    /// A sync of the server as it was before stops, and keeps nothing.
    private func open(_ server: SubsonicServer, secret: String) {
        syncs.invalidate(server.id)
        switch server.kind {
        case .subsonic:
            let client = SubsonicClient(server: server, password: secret, endpoints: SubsonicEndpoints(server: server))
            subsonic[server.id] = client
            ServerMediaSigning.shared.register(client, for: server.id)
            trackside.removeValue(forKey: server.id)?.stopEvents()
            TracksideMediaTrust.shared.release(server.id)
            if subsonicOutboxes[server.id] == nil {
                subsonicOutboxes[server.id] = ListenOutbox(file: Self.listensURL(server.id))
            }
        case .trackside:
            subsonic[server.id] = nil
            subsonicOutboxes[server.id] = nil
            stopWatchingKeeps(server.id)
            trackside[server.id]?.stopEvents()
            if cloudSync?.serverID == server.id {
                // Its client is the old connection's; the next connect starts it again.
                cloudSync?.stop()
                cloudSync = nil
            }
            let connection = TracksideConnection(server: server, apiKey: secret, mediaToken: Self.loadMediaToken(for: server))
            trackside[server.id] = connection
            TracksideMediaTrust.shared.hold(connection.endpoints.pins, for: server.id)
            ServerMediaSigning.shared.register(connection.client, for: server.id)
        }
    }

    /// Whether a server is being reached from away from home: at an address that isn't on
    /// the local network.
    func isAway(_ serverID: String) -> Bool {
        let base = trackside[serverID]?.endpoints.baseURL ?? subsonic[serverID]?.baseURL
        return base.map { !NearbyAddress.isNearby($0) } ?? false
    }

    /// The server's client, whichever kind it is.
    func client(for serverID: String) -> (any MusicServerClient)? {
        if let client = subsonic[serverID] { return client }
        return trackside[serverID]?.client
    }

    /// For what only a Subsonic server does: full syncs by search, and animated covers.
    func subsonicClient(for serverID: String) -> SubsonicClient? { subsonic[serverID] }

    func tracksideClient(for serverID: String) -> TracksideClient? { trackside[serverID]?.client }

    func isTrackside(_ serverID: String) -> Bool { trackside[serverID] != nil }

    /// The queue a server's lookups wait in: Subsonic's few at a time, Trackside's more.
    func lookups(for serverID: String) -> AsyncLimiter {
        isTrackside(serverID) ? tracksideLookups : lookups
    }

    /// Whether a server hears about the songs played from it.
    func reportsPlays(to serverID: String) -> Bool { !quietServers.contains(serverID) }

    func setReportsPlays(_ reports: Bool, to serverID: String) {
        if reports { quietServers.remove(serverID) } else { quietServers.insert(serverID) }
        UserDefaults.standard.set(Array(quietServers), forKey: Self.quietKey)
    }

    func server(_ id: String) -> SubsonicServer? { servers.first { $0.id == id } }

    var onlineServers: [SubsonicServer] { servers.filter { status[$0.id] == .online } }

    // MARK: - Connecting

    /// What answers at an address: Trackside, or a server to sign in to with a password.
    func detect(at url: URL) async throws -> MusicServerDetection.Result {
        try await MusicServerDetection.detect(at: url)
    }

    /// Checks a Subsonic server and, if it answers, keeps it and its password. An entry that
    /// was Trackside goes back to being a Subsonic one.
    ///
    /// A server entered without a username is signed in to with an OpenSubsonic API key,
    /// which can be revoked on the server without changing the password. Every other is
    /// signed in to with a token made from the password, never the password itself.
    func add(_ server: SubsonicServer, password: String) async throws {
        var server = server
        let wasTrackside = server.isTrackside
        server.kind = .subsonic
        server.serverUUID = nil
        server.deviceID = nil
        server.deviceName = nil
        server.urlsETag = nil
        if wasTrackside { server.candidateURLs = nil }
        server.authMethod = server.username.trimmingCharacters(in: .whitespaces).isEmpty ? .apiKey : .token
        let client = SubsonicClient(server: server, password: password, endpoints: SubsonicEndpoints(server: server))
        if server.usesAPIKey, !(try await client.openSubsonicExtensions()).contains("apiKeyAuthentication") {
            throw SubsonicError.authMethodUnsupported
        }
        await client.endpoints?.race()
        server.url = client.baseURL
        try await client.ping()
        if wasTrackside || trackside[server.id] != nil { forgetTrackside(server) }
        if let previous = self.server(server.id), previous.url != server.url || previous.username != server.username {
            // Plays and songs asked of the server it was are nothing to the one it is now.
            forgetPlaysAndKeeps(server.id)
        }
        ServerKeychain.setPassword(password, for: ServerCredential.account(for: server))
        open(server, secret: password)
        store(server)
        status[server.id] = .online
        await sync(server.id)
    }

    /// Keeps a Trackside server this device has just been paired with, under the entry it
    /// replaces where there is one, then syncs it.
    /// - Parameter base: the address the pairing went through, used until a race finds a
    ///   better one.
    func pair(_ result: TracksidePairResult, at base: URL, name: String, replacing editing: SubsonicServer? = nil) async {
        let urls = result.server.urls.flatMap { $0.isEmpty ? nil : $0 } ?? [TracksideServerURL(url: base, kind: .lan)]
        let server = SubsonicServer(
            id: editing?.id ?? UUID().uuidString,
            name: name,
            url: base,
            username: result.user.username,
            salt: editing?.salt ?? SubsonicServer.makeSalt(),
            kind: .trackside,
            serverUUID: result.server.serverID,
            candidateURLs: urls,
            deviceID: result.device.id,
            deviceName: result.device.name
        )
        if let editing {
            if editing.kind == .subsonic {
                // Its downloads and playlists name the old server's songs, until they're matched.
                if catalogs[editing.id]?.isEmpty == false {
                    movedToTrackside.insert(editing.id)
                    saveMoved()
                }
                forgetPlaysAndKeeps(editing.id)
            } else if editing.serverUUID != result.server.serverID {
                // Another Trackside server at the same entry: its songs start over.
                try? FileManager.default.removeItem(at: Self.cursorURL(editing.id))
            }
        }
        ServerKeychain.setPassword(result.apiKey, for: ServerCredential.account(for: server))
        if let token = result.mediaToken { Self.saveMediaToken(token, for: server) }
        open(server, secret: result.apiKey)
        if editing?.kind == .subsonic {
            // The old server's song ids mean nothing here, and would be asked of the new one.
            // Downloads keep playing from their files meanwhile; the first sync takes a snapshot.
            catalogs[server.id] = nil
            artists[server.id] = nil
            try? FileManager.default.removeItem(at: Self.catalogURL(server.id))
            try? FileManager.default.removeItem(at: Self.cursorURL(server.id))
        }
        store(server)
        await connect(server.id)
    }

    private func store(_ server: SubsonicServer) {
        if let index = servers.firstIndex(where: { $0.id == server.id }) {
            servers[index] = server
        } else {
            servers.append(server)
        }
        save()
    }

    func remove(_ serverID: String) {
        ServerMediaSigning.shared.remove(serverID)
        if cloudSync?.serverID == serverID {
            cloudSync?.stop()
            cloudSync = nil
        }
        if let server = server(serverID), server.isTrackside {
            // The key is no use to anyone once the entry is gone, so the server is told to
            // forget it. Best effort: an unreachable server keeps it until it's revoked there.
            if let client = trackside[serverID]?.client {
                Task.detached { try? await client.revokeThisDevice() }
            }
            forgetTrackside(server)
        }
        syncs.invalidate(serverID)
        servers.removeAll { $0.id == serverID }
        subsonic[serverID] = nil
        status[serverID] = nil
        catalogs[serverID] = nil
        lastSynced[serverID] = nil
        lockedKeychain.remove(serverID)
        forgetPlaysAndKeeps(serverID)
        movedToTrackside.remove(serverID)
        saveMoved()
        setReportsPlays(true, to: serverID)
        ServerKeychain.removePassword(for: serverID)
        try? FileManager.default.removeItem(at: Self.catalogURL(serverID))
        writeArtists(nil, serverID)
        artists[serverID] = nil
        save()
        onRemoved?(serverID)
    }

    /// Lets go of the songs asked of a server and the plays still to tell it.
    private func forgetPlaysAndKeeps(_ serverID: String) {
        stopWatchingKeeps(serverID)
        kept[serverID] = nil
        saveKept()
        subsonicOutboxes[serverID] = nil
        try? FileManager.default.removeItem(at: Self.listensURL(serverID))
    }

    /// Lets go of what a Trackside server left on this device: its connection, the place its
    /// sync got to, its media token and any plays still to send.
    private func forgetTrackside(_ server: SubsonicServer) {
        trackside.removeValue(forKey: server.id)?.stopEvents()
        ServerMediaSigning.shared.remove(server.id)
        TracksideMediaTrust.shared.release(server.id)
        songRequests[server.id] = nil
        awaitingSync[server.id] = nil
        ServerKeychain.removePassword(for: ServerCredential.mediaTokenAccount(for: server))
        try? FileManager.default.removeItem(at: Self.cursorURL(server.id))
        try? FileManager.default.removeItem(at: Self.listensURL(server.id))
    }

    /// Checks every server, and syncs the ones not synced today.
    func connectAll() async {
        await withTaskGroup(of: Void.self) { group in
            for server in servers {
                group.addTask { await self.connect(server.id) }
            }
        }
    }

    /// Checks servers again once the connection comes back or moves: ones out of reach are
    /// tried again, and ones that answered are asked quietly whether they still do, so a
    /// server reachable only at home shows as out of reach once you've left, and back again
    /// when you're home. A Trackside server first finds which of its addresses is best on
    /// the new network. Servers not checked yet this launch are left to that.
    func networkChanged() async {
        await withTaskGroup(of: Void.self) { group in
            for server in servers where client(for: server.id) != nil {
                let endpoints = trackside[server.id]?.endpoints
                let subsonicEndpoints = subsonic[server.id]?.endpoints
                switch status[server.id] {
                case .online:
                    group.addTask {
                        if let endpoints {
                            await endpoints.pathChanged()
                            await self.noteAddress(server.id)
                        }
                        if let subsonicEndpoints {
                            await subsonicEndpoints.pathChanged()
                            await self.noteAddress(server.id)
                        }
                        await self.recheck(server.id)
                    }
                case .offline, .failed: group.addTask { await self.connect(server.id) }
                case .connecting, .wrongPassword, nil: break
                }
            }
        }
    }

    /// Asks a server that answered before whether it still does, without showing it as
    /// connecting meanwhile.
    private func recheck(_ serverID: String) async {
        guard let client = client(for: serverID) else { return }
        let next: Status
        do {
            try await client.ping()
            await flushListens(serverID)
            return
        } catch {
            next = Self.status(after: error)
        }
        // Checked again, or removed, meanwhile.
        guard status[serverID] == .online else { return }
        status[serverID] = next
        onStatusChange?()
    }

    func connect(_ serverID: String) async {
        await loading?.value
        if trackside[serverID] == nil, subsonic[serverID] == nil, let server = server(serverID) {
            // Its password couldn't be read at launch: the phone may have been unlocked since.
            openFromKeychain(server)
        }
        if let connection = trackside[serverID] {
            await connectTrackside(serverID, connection)
            return
        }
        guard let client = subsonic[serverID] else {
            guard server(serverID) != nil else { return }
            status[serverID] = lockedKeychain.contains(serverID) ? .offline : .wrongPassword
            return
        }
        let generation = syncs.generation(of: serverID)
        // Changed or removed while it was being asked: what it answered is about another server.
        let isCurrent = { [weak self] in self?.syncs.isCurrent(serverID, generation) == true && self?.server(serverID) != nil }
        status[serverID] = .connecting
        do {
            // Each of its addresses is asked first, without credentials, so the one that
            // answers best on this network is the one signed in to.
            await client.endpoints?.race()
            guard isCurrent() else { return }
            noteAddress(serverID)
            try await client.ping()
            guard isCurrent() else { return }
            status[serverID] = .online
            await flushListens(serverID)
            watchKeeps(serverID)
            let synced = lastSynced[serverID] ?? catalogDate(serverID)
            if catalogs[serverID]?.isEmpty != false || Date.now.timeIntervalSince(synced ?? .distantPast) > 24 * 60 * 60 {
                await sync(serverID)
            } else if artists[serverID] == nil {
                // Synced before its artists were kept.
                await syncArtists(serverID)
            }
        } catch {
            guard isCurrent() else { return }
            status[serverID] = Self.status(after: error)
        }
        onStatusChange?()
    }

    /// Finds the best of a Trackside server's addresses, asks who this device is there, keeps
    /// the server's latest addresses and a fresh media token, and catches up on its changes.
    private func connectTrackside(_ serverID: String, _ connection: TracksideConnection) async {
        // Paired again, or removed, while it was being asked: the answer is the old one's.
        let isCurrent = { [weak self] in self?.trackside[serverID] === connection }
        status[serverID] = .connecting
        // The key goes nowhere until one of the addresses proves it is the server.
        await connection.endpoints.race()
        guard isCurrent() else { return }
        noteAddress(serverID)
        do {
            let me = try await connection.client.me()
            guard isCurrent() else { return }
            connection.me = me
            if let urls = me.server.urls, !urls.isEmpty {
                await connection.endpoints.update(candidates: urls)
                if var server = server(serverID), server.candidateURLs != urls || server.deviceName != me.device?.name {
                    server.candidateURLs = urls
                    server.deviceName = me.device?.name ?? server.deviceName
                    store(server)
                }
            }
            await refreshAddresses(serverID)
            updateRequests(serverID, features: me.features)
            await renewMediaTokenIfDue(serverID, connection)
            guard isCurrent() else { return }
            status[serverID] = .online
            await flushListens(serverID)
            await sync(serverID)
            if isForeground, isCurrent() { startEvents(serverID) }
            startCloudSyncIfChosen(serverID)
        } catch {
            guard isCurrent() else { return }
            status[serverID] = Self.status(after: error)
        }
        onStatusChange?()
    }

    /// Asks a Trackside server for its addresses, unless they're the ones last read: a new
    /// public address is learned without pairing again. Read from an address that has proven
    /// it is the server, and each new address is used only once it proves itself too.
    private func refreshAddresses(_ serverID: String, knownETag: String? = nil) async {
        guard let connection = trackside[serverID], let entry = server(serverID) else { return }
        if let knownETag, knownETag == entry.urlsETag { return }
        guard let answer = try? await connection.client.serverURLs(ifNoneMatch: entry.urlsETag),
              trackside[serverID] === connection,
              case .changed(let list, let etag) = answer
        else { return }
        let trusted = TracksideServerURLs.trusted(list.urls)
        guard !trusted.isEmpty else { return }
        await connection.endpoints.update(candidates: trusted)
        guard var server = server(serverID) else { return }
        server.candidateURLs = trusted
        server.urlsETag = etag
        store(server)
    }

    // MARK: - Trackside Cloud

    /// Starts Trackside Cloud with a server once it's connected, when it's the backend chosen.
    private func startCloudSyncIfChosen(_ serverID: String) {
        guard !isDemo, CloudSync.tracksideServerID == serverID, let connection = trackside[serverID] else { return }
        if let cloudSync, cloudSync.serverID == serverID {
            cloudSync.syncSoon(after: .zero)
            return
        }
        cloudSync?.stop()
        guard let store = try? TracksStore.shared() else { return }
        let device = TracksideDeviceIdentity.current
        let sync = TracksideCloudSync(serverID: serverID, client: connection.client, store: store, deviceName: device.name, platform: device.platform)
        cloudSync = sync
        sync.syncSoon(after: .zero)
    }

    /// Keeps the address a race chose as the entry's, so the next launch starts there.
    private func noteAddress(_ serverID: String) {
        guard let base = trackside[serverID]?.endpoints.baseURL ?? subsonic[serverID]?.baseURL, var server = server(serverID) else { return }
        guard server.url != base else { return }
        server.url = base
        store(server)
    }

    private func renewMediaTokenIfDue(_ serverID: String, _ connection: TracksideConnection) async {
        guard connection.client.mediaToken?.needsRenewal() ?? true,
              let token = try? await connection.client.renewMediaToken(),
              let server = server(serverID)
        else { return }
        connection.use(mediaToken: token)
        ServerMediaSigning.shared.register(connection.client, for: serverID)
        Self.saveMediaToken(token, for: server)
    }

    /// What a server's state is after a request to it failed.
    private static func status(after error: any Error) -> Status {
        switch error {
        case let error as URLError where error.code == .appTransportSecurityRequiresSecureConnection:
            .failed(insecureAddressReason)
        case SubsonicError.wrongCredentials, TracksideError.unauthenticated:
            .wrongPassword
        case SubsonicError.captivePortal:
            .failed(captivePortalReason)
        case let error as SubsonicError:
            .failed(describe(error))
        case TracksideError.notTrackside, TracksideError.http, TracksideError.unproven:
            .offline
        case let error as TracksideError:
            .failed(describe(error))
        default:
            .offline
        }
    }

    // MARK: - Syncing

    /// Brings a server's songs up to date: every song, paged through, from a Subsonic server;
    /// only what changed since last time from Trackside. Songs keep the date they were first
    /// seen, so Recently Added means recently added to the server.
    func sync(_ serverID: String) async {
        // Its saved songs first, so their dates added aren't lost.
        await loading?.value
        // One already running for the server as it is now is waited for, so whoever asked
        // sees its songs when it returns.
        await syncs.run(serverID) { [weak self] isCurrent in
            guard let self else { return }
            if trackside[serverID] != nil {
                await performTracksideSync(serverID, isCurrent: isCurrent)
            } else {
                await performSync(serverID, isCurrent: isCurrent)
            }
        }
        onStatusChange?()
    }

    /// A Trackside server said something changed: sync, or once more after the one running.
    private func syncSoon(_ serverID: String) {
        guard !syncs.runAgainAfterCurrent(serverID) else { return }
        Task { await sync(serverID) }
    }

    private func performTracksideSync(_ serverID: String, isCurrent: @escaping @MainActor () -> Bool) async {
        guard let client = trackside[serverID]?.client else { return }
        syncing.insert(serverID)
        defer { syncing.remove(serverID) }
        guard let synced = try? await Self.runSync(
            serverID: serverID,
            tracks: catalogs[serverID] ?? [],
            artists: artists[serverID] ?? [:],
            cursorFile: Self.cursorURL(serverID),
            client: client
        ), isCurrent(), trackside[serverID] != nil
        else { return }
        lastSynced[serverID] = .now
        if let tracks = synced.tracks { catalogs[serverID] = tracks }
        if let refs = synced.artists { artists[serverID] = refs }
        writeTracksideMirror(songs: synced.tracks, artists: synced.artists ?? artists[serverID] ?? [:], cursor: synced.cursor, serverID)
        if let tracks = synced.tracks { settleAwaited(serverID, synced: tracks) }
    }

    /// What a Trackside sync changed: the songs and artists where they did, and the cursor.
    nonisolated private struct SyncedMirror: Sendable {
        let tracks: [LocalTrack]?
        let artists: [String: ServerArtistRef]?
        let cursor: String?
    }

    /// Brings the mirror up to date off the main actor: a big library takes a moment to file
    /// by id and back.
    @concurrent
    nonisolated private static func runSync(serverID: String, tracks: [LocalTrack], artists: [String: ServerArtistRef], cursorFile: URL, client: TracksideClient) async throws -> SyncedMirror {
        let before = TracksideMirror(serverID: serverID, tracks: tracks, artists: artists, cursor: TracksideCursorFile.load(from: cursorFile))
        let after = try await TracksideSync.run(before, from: client)
        return SyncedMirror(
            tracks: after.songs != before.songs ? after.tracks : nil,
            artists: after.artists != before.artists ? after.artists : nil,
            cursor: after.cursor
        )
    }

    /// Every song on a Subsonic server. A sync that can't read all of them keeps what was
    /// there: a busy server mustn't empty the library.
    private func performSync(_ serverID: String, isCurrent: @escaping @MainActor () -> Bool) async {
        guard let client = subsonic[serverID] else { return }
        syncing.insert(serverID)
        defer { syncing.remove(serverID) }
        let found: SubsonicCatalog
        do {
            found = try await Self.readCatalog(client)
        } catch {
            if !(error is CancellationError) {
                Self.log.error("A sync of a server couldn't finish: \(URLRedaction.describe(error), privacy: .public)")
            }
            return
        }
        guard isCurrent(), subsonic[serverID] != nil else { return }
        if !found.isComplete {
            Self.log.notice("A server has more than \(Self.catalogLimit) songs; the first \(Self.catalogLimit) are kept")
        }
        let known = Dictionary((catalogs[serverID] ?? []).map { ($0.id, $0.addedAt) }, uniquingKeysWith: { first, _ in first })
        let isFirstSync = known.isEmpty
        let now = Date.now
        let tracks = found.songs.map { song in
            // When the server added it, where it says, so Recently Added is the server's own
            // from the first sync. Otherwise when this sync first saw it; on a first sync
            // there's no telling, and the server's order stands.
            song.track(on: serverID, addedAt: song.createdDate ?? known[LocalTrack.id(for: .server(serverID: serverID, songID: song.id))] ?? (isFirstSync ? .distantPast : now))
        }
        catalogs[serverID] = tracks
        lastSynced[serverID] = now
        Task.detached(priority: .utility) { Self.saveCatalog(tracks, serverID) }
        settleKept(serverID, synced: tracks)
        // Its artists' pictures after the songs are safe.
        await syncArtists(serverID)
    }

    @concurrent
    nonisolated private static func readCatalog(_ client: SubsonicClient) async throws -> SubsonicCatalog {
        try await client.catalog(pageSize: pageSize, limit: catalogLimit)
    }

    // MARK: - Events

    /// Listens to a Trackside server's events while Tracks is in front: a change syncs, a
    /// request for a song moves on, and a revoked key asks for pairing again.
    private func startEvents(_ serverID: String) {
        guard let connection = trackside[serverID], connection.events == nil else { return }
        let stream = TracksideEventStream(client: connection.client, transport: connection.streamTransport).messages(after: connection.lastEventID)
        let id = UUID()
        connection.eventsID = id
        connection.events = Task { [weak self, weak connection] in
            defer { if connection?.eventsID == id { connection?.events = nil } }
            do {
                for try await message in stream {
                    connection?.lastEventID = message.lastEventID
                    self?.handle(message.event, from: serverID)
                }
            } catch TracksideError.unauthenticated {
                guard let self, let connection, self.trackside[serverID] === connection else { return }
                self.status[serverID] = .wrongPassword
                self.onStatusChange?()
            } catch {}
        }
    }

    private func handle(_ event: TracksideEvent, from serverID: String) {
        switch event {
        case .hello:
            // A reconnect: catch up on everything that may have changed meanwhile.
            syncSoon(serverID)
            Task { await refreshAddresses(serverID) }
            if cloudSync?.serverID == serverID { cloudSync?.syncSoon() }
        case .changed, .resync:
            syncSoon(serverID)
        case .serverURLs(let etag):
            Task { await refreshAddresses(serverID, knownETag: etag) }
        case .cloud:
            if cloudSync?.serverID == serverID { cloudSync?.syncSoon() }
        case .acquisition(let acquisition):
            songRequests[serverID]?.update(acquisition)
            if acquisition.state == .done { syncSoon(serverID) }
        case .revoked, .library, .other:
            break
        }
    }

    /// Follows Tracks coming to the front and going behind: events are listened to only in
    /// front, and coming back catches up on what changed meanwhile. On the Mac, Tracks stays
    /// in front for this while it runs.
    private func followForeground() {
        #if os(iOS)
        // Woken in the background, to finish downloads say: no events until it's opened.
        isForeground = UIApplication.shared.applicationState != .background
        let center = NotificationCenter.default
        lifecycle.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) { [weak self] _ in
            Task { @MainActor in self?.setForeground(true) }
        })
        lifecycle.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) { [weak self] _ in
            Task { @MainActor in self?.setForeground(false) }
        })
        #endif
    }

    private func setForeground(_ isForeground: Bool) {
        guard isForeground != self.isForeground else { return }
        self.isForeground = isForeground
        if isForeground {
            for serverID in subsonic.keys where status[serverID] == .online {
                Task { await flushListens(serverID) }
            }
        }
        for (serverID, connection) in trackside {
            if isForeground {
                guard status[serverID] == .online else { continue }
                startEvents(serverID)
                syncSoon(serverID)
                if cloudSync?.serverID == serverID { cloudSync?.syncSoon() }
                Task {
                    await refreshAddresses(serverID)
                    await renewMediaTokenIfDue(serverID, connection)
                    await flushListens(serverID)
                }
            } else {
                connection.stopEvents()
            }
        }
    }

    // MARK: - Searching and keeping

    /// How many songs a quick search asks for. A server that finds songs elsewhere keeps its
    /// first 12 for the library's own matches, so asking for no more than that answers from the
    /// library alone, at once, without it finding songs for you.
    nonisolated static let quickSearchSize = 12
    /// How many songs a full search asks for: all a server that finds songs elsewhere finds. It
    /// finds 60 for any search of more than 12 however many are asked for, and only on the
    /// first page, so asking for fewer would save it nothing and lose the rest. It takes
    /// several seconds.
    static let fullSearchSize = 60
    /// How many of the library's matches each page after the first asks for.
    static let searchPageSize = 30

    /// Songs on your servers for a search, asked of each server rather than looked up in its
    /// last sync. A server that finds songs elsewhere answers a full search with songs it can
    /// find for you as well as the ones it has, and plays them straight away.
    /// - Parameter isFull: ask for the songs a server can find too, which is slow on a server
    ///   that looks them up; otherwise only for the ones it has.
    func search(_ query: String, isFull: Bool) async -> ServerSearchPage {
        let start = Dictionary(uniqueKeysWithValues: onlineServers.map { ($0.id, MusicSearchPosition.offset(0)) })
        return await search(query, from: start, size: isFull ? Self.fullSearchSize : Self.quickSearchSize)
    }

    /// Albums and artists on your servers for a search, with ones a server finds elsewhere.
    /// Quick even then: no songs are asked for, so it doesn't go finding those.
    func searchAlbumsAndArtists(_ query: String) async -> (albums: [ServerDiscovery.Album], artists: [ServerArtist]) {
        let asked = onlineServers.compactMap { server in client(for: server.id).map { (server.id, $0, lookups(for: server.id)) } }
        let answers = await withTaskGroup(of: (String, MusicSearchResult?).self) { group in
            for (serverID, client, lookups) in asked {
                group.addTask {
                    (serverID, try? await lookups.run { try await client.search(query, artists: 6, albums: 12, songs: 0, after: nil) })
                }
            }
            var answers: [String: MusicSearchResult] = [:]
            for await (serverID, result) in group { answers[serverID] = result }
            return answers
        }
        var albums: [ServerDiscovery.Album] = []
        var artists: [ServerArtist] = []
        for (serverID, _, _) in asked {
            guard let result = answers[serverID] else { continue }
            albums += result.albums.map { ServerDiscovery.Album($0, serverID: serverID) }
            artists += result.artists.map { ServerArtist($0, serverID: serverID) }
        }
        return (albums, artists)
    }

    /// The next page of a search: more of the library's matches, from the servers that had more.
    func search(_ query: String, after page: ServerSearchPage) async -> ServerSearchPage {
        await search(query, from: page.next, size: Self.searchPageSize)
    }

    private func search(_ query: String, from positions: [String: MusicSearchPosition], size: Int) async -> ServerSearchPage {
        let asked = onlineServers.compactMap { server in
            positions[server.id].flatMap { position in client(for: server.id).map { (server.id, $0, position, lookups(for: server.id)) } }
        }
        let answers = await withTaskGroup(of: (String, ServerSearchPage?).self) { group in
            for (serverID, client, position, lookups) in asked {
                group.addTask {
                    (serverID, try? await lookups.run { try await Self.page(of: query, on: client, serverID: serverID, from: position, size: size) })
                }
            }
            var answers: [String: ServerSearchPage] = [:]
            for await (serverID, page) in group { answers[serverID] = page }
            return answers
        }
        // In the order the servers were added, so results don't reshuffle between searches.
        var page = ServerSearchPage()
        for (serverID, _, _, _) in asked {
            // One that couldn't be asked has nothing more to give this search.
            guard let found = answers[serverID] else { continue }
            page.tracks += found.tracks
            page.next.merge(found.next) { _, new in new }
        }
        return page
    }

    /// One server's page of a search. Subsonic's next page starts after the library's own
    /// matches: songs found elsewhere come on the first page only, 12 of the library's before
    /// them, as many as were asked for on other servers. Trackside says where its next page
    /// starts.
    @concurrent
    nonisolated private static func page(of query: String, on client: any MusicServerClient, serverID: String, from position: MusicSearchPosition, size: Int) async throws -> ServerSearchPage {
        var page = ServerSearchPage()
        if let subsonic = client as? SubsonicClient {
            let offset = if case .offset(let offset) = position { offset } else { 0 }
            let songs = try await subsonic.search(query, artists: 0, albums: 0, songs: size, songOffset: offset).songs
            page.tracks = songs.map { $0.track(on: serverID) }
            let library = songs.filter { $0.isExternal != true }.count
            if offset == 0 ? library >= quickSearchSize : library == size {
                page.next[serverID] = .offset(offset + library)
            }
        } else {
            let found = try await client.search(query, artists: 0, albums: 0, songs: size, after: position == .offset(0) ? nil : position)
            page.tracks = found.songs.map { $0.track(on: serverID) }
            if let next = found.next { page.next[serverID] = next }
        }
        return page
    }

    /// Stars a song on its server, which a server that fetches songs on request takes as "get
    /// me this": it fetches the file into the library. The song is then looked for on its
    /// own, cheaply, and the library synced once it's there: such a server usually has a song
    /// within a few minutes.
    ///
    /// Trackside only plays what's in its library, so a song from it is there already: a sync
    /// brings it in if the last one didn't.
    func keep(_ track: LocalTrack) async throws {
        guard case .server(let serverID, let songID) = track.origin else { return }
        if trackside[serverID] != nil {
            awaitingSync[serverID, default: []].insert(track.identity)
            await sync(serverID)
            return
        }
        guard let client = subsonic[serverID] else { return }
        try await client.star(songID: songID)
        guard subsonic[serverID] != nil else { return }
        kept[serverID, default: ServerKeeps()].ask(for: track.identity, title: track.title, artist: track.artist)
        saveKept()
        watchKeeps(serverID)
    }

    /// Looks for the songs asked of a Subsonic server until they arrive or their time is up.
    /// Watching already, the new song joins it.
    private func watchKeeps(_ serverID: String) {
        guard subsonic[serverID] != nil, kept[serverID]?.isEmpty == false else { return }
        let watch = keepWatches[serverID] ?? ServerKeepWatch()
        keepWatches[serverID] = watch
        watch.watch(
            waiting: { [weak self] in self?.kept[serverID]?.waiting ?? [] },
            look: { [weak self] song in
                guard let client = await self?.subsonicClient(for: serverID) else { throw CancellationError() }
                return try await Self.has(song, on: client, serverID: serverID)
            },
            arrived: { [weak self] in await self?.sync(serverID) }
        )
    }

    private func stopWatchingKeeps(_ serverID: String) {
        keepWatches.removeValue(forKey: serverID)?.stop()
    }

    /// Whether a server's library has a song asked of it now, by a search for its title.
    @concurrent
    nonisolated private static func has(_ song: ServerKeeps.Waiting, on client: SubsonicClient, serverID: String) async throws -> Bool {
        guard let name = song.name else { return false }
        let found = try await client.search(name.title, artists: 0, albums: 0, songs: 50).songs
        return found.contains { $0.isExternal != true && $0.track(on: serverID).identity == song.identity }
    }

    /// Whether a song has been asked for and its server hasn't got it yet.
    func isWaitingToKeep(_ track: LocalTrack) -> Bool {
        guard case .server(let serverID, _) = track.origin else { return false }
        if awaitingSync[serverID]?.contains(track.identity) == true { return true }
        return kept[serverID]?.isWaiting(for: track.identity) == true
    }

    private func settleKept(_ serverID: String, synced tracks: [LocalTrack]) {
        guard var waiting = kept[serverID] else { return }
        let arrived = tracks.filter { waiting.isWaiting(for: $0.identity) }
        if !arrived.isEmpty { onKeptArrived?(arrived) }
        waiting.settle(arrived: Set(tracks.map(\.identity)))
        kept[serverID] = waiting.isEmpty ? nil : waiting
        saveKept()
    }

    /// Songs asked of a Trackside server that a sync has brought in.
    private func settleAwaited(_ serverID: String, synced tracks: [LocalTrack]) {
        guard let waiting = awaitingSync[serverID], !waiting.isEmpty else { return }
        let arrived = tracks.filter { waiting.contains($0.identity) }
        awaitingSync[serverID] = waiting.subtracting(arrived.map(\.identity))
        if !arrived.isEmpty { onKeptArrived?(arrived) }
    }

    // MARK: - Songs to get

    /// Starts or ends a server's song requests as its `/me` says: only a server that offers
    /// them, to this user, has any.
    private func updateRequests(_ serverID: String, features: TracksideMe.Features?) {
        guard features?.acquisition != nil else {
            songRequests[serverID] = nil
            return
        }
        if songRequests[serverID]?.isOffered != true {
            songRequests[serverID] = SongRequestBoard(features: features)
        }
    }

    /// The first server you can reach that gets songs it doesn't have when asked, as its `/me`
    /// says it does for this user.
    var requestingServer: SubsonicServer? {
        onlineServers.first { songRequests[$0.id]?.isOffered == true }
    }

    var canGetSongs: Bool { requestingServer != nil }

    /// Whether a server gets songs it doesn't have when asked, as its `/me` says it does.
    func getsSongs(_ serverID: String) -> Bool {
        songRequests[serverID]?.isOffered == true
    }

    /// Asks each server that offers it for songs it could get for a search.
    func findSongsToGet(_ query: String) async {
        let asked = onlineServers.compactMap { server -> (String, TracksideAcquisitions)? in
            guard songRequests[server.id]?.isOffered == true, let connection = trackside[server.id] else { return nil }
            return (server.id, connection.acquisitions)
        }
        await withTaskGroup(of: (String, TracksideAcquisitionSearch?).self) { group in
            for (serverID, acquisitions) in asked {
                group.addTask { (serverID, try? await acquisitions.search(query)) }
            }
            for await (serverID, found) in group {
                guard let found, !Task.isCancelled else { continue }
                songRequests[serverID]?.show(found, for: query)
            }
        }
    }

    func clearSongsToGet() {
        for (serverID, board) in songRequests where !board.query.isEmpty { songRequests[serverID]?.clear() }
    }

    /// Songs one server could get for a search, leaving out the ones it has. Nil when it
    /// couldn't be asked.
    func songsToGet(_ query: String, on serverID: String) async -> [TracksideCandidate]? {
        guard songRequests[serverID]?.isOffered == true, let acquisitions = trackside[serverID]?.acquisitions else { return nil }
        guard let found = try? await lookups(for: serverID).run({ try await acquisitions.search(query) }) else { return nil }
        return SongRequestBoard.offered(found)
    }

    /// Asks a server to get a song, one its search found or simply by name, and follows the
    /// request through the server's events until a sync brings the song in.
    func requestSong(
        title: String,
        artist: String,
        album: String?,
        duration: TimeInterval?,
        candidate: String?,
        from serverID: String
    ) async throws(SongRequestProblem) -> TracksideAcquisition {
        guard let acquisitions = trackside[serverID]?.acquisitions, acquisitions.isAvailable else {
            throw SongRequestProblem(reason: String(localized: "Couldn't reach your server. Try again once it's online."))
        }
        let request = TracksideAcquisitionRequest(title: title, artist: artist, album: album, duration: duration, candidate: candidate)
        do {
            let acquisition = try await acquisitions.request(request)
            songRequests[serverID]?.requested(acquisition, for: candidate ?? acquisition.id)
            awaitingSync[serverID, default: []].insert(SongIdentity.key(title: title, artistName: artist))
            if acquisition.state == .done { syncSoon(serverID) }
            return acquisition
        } catch TracksideError.quotaExceeded {
            throw SongRequestProblem(reason: AcquisitionWords.reason(.quotaExceeded))
        } catch TracksideError.candidateExpired {
            throw SongRequestProblem(reason: String(localized: "Search again to get this song."))
        } catch {
            throw SongRequestProblem(reason: String(localized: "Couldn't reach your server. Try again once it's online."))
        }
    }

    /// The song an offer stands for, to play without keeping it, where its server can.
    func unownedTrack(for offer: GetMore.Offer) -> LocalTrack? {
        guard songRequests[offer.serverID]?.isOffered == true, trackside[offer.serverID]?.acquisitions.playsWithoutKeeping == true else { return nil }
        return UnownedSong.track(offer.candidate, on: offer.serverID)
    }

    /// Streams a song its server doesn't have, without the server keeping it.
    func streamWithoutKeeping(_ track: LocalTrack, maxBitRate: Int?, network: MusicNetwork) async -> MusicServerStream? {
        guard case .server(let serverID, _) = track.origin, let connection = trackside[serverID] else { return nil }
        return try? await UnownedSong.stream(track, from: connection.acquisitions, client: connection.client, maxBitRate: maxBitRate, network: network)
    }

    /// The latest word on a song asked for, from the server's answers and events.
    func acquisition(_ id: String, on serverID: String) -> TracksideAcquisition? {
        songRequests[serverID]?.status(of: id)
    }

    // MARK: - Playing

    /// Tells a server a song has started. Fire and forget: a missed "now playing" costs nothing.
    func nowPlaying(songID: String, on serverID: String) {
        guard let client = client(for: serverID) else { return }
        Task.detached { try? await client.nowPlaying(songID: songID) }
    }

    /// Keeps a play to tell a server about, and sends what's waiting if it can be reached.
    /// Kept on disk, so a play heard offline is sent once Tracks is back online, with when it
    /// played.
    func recordListen(_ listen: ServerListen, on serverID: String) {
        guard let outbox = outbox(for: serverID) else { return }
        let reachable = status[serverID] == .online
        Task {
            await outbox.add(listen)
            if reachable { await flushListens(serverID) }
        }
    }

    private func outbox(for serverID: String) -> ListenOutbox? {
        trackside[serverID]?.outbox ?? subsonicOutboxes[serverID]
    }

    /// Sends a server the plays waiting for it. A flush already running is joined, and one
    /// that fails leaves them for the next connect.
    private func flushListens(_ serverID: String) async {
        guard let outbox = outbox(for: serverID), let client = client(for: serverID) else { return }
        _ = try? await outbox.flush(to: client)
    }

    /// Asks Trackside to make songs about to play ready at the profile they'll be asked for,
    /// so each starts from a finished file, which plays on from the one before without a gap.
    func prepare(_ songIDs: [String], on serverID: String, maxBitRate: Int?) {
        guard let client = trackside[serverID]?.client, let profile = TracksideClient.profile(forBitRate: maxBitRate), !songIDs.isEmpty else { return }
        Task.detached { _ = try? await client.prepare(songIDs: songIDs, profile: profile) }
    }

    /// The request a background download makes: the original file, or a smaller copy at the
    /// bit rate asked for. Trackside's carries this device's key in its header.
    /// Nil for a Trackside server none of whose addresses has proven it is the server.
    func downloadRequest(serverID: String, songID: String, maxBitRate: Int?) async -> URLRequest? {
        if let client = subsonic[serverID] {
            return URLRequest(url: maxBitRate.map { client.streamURL(songID: songID, maxBitRate: $0) } ?? client.downloadURL(songID: songID))
        }
        if let client = trackside[serverID]?.client {
            return try? await client.downloadRequest(songID: songID, profile: TracksideClient.profile(forBitRate: maxBitRate))
        }
        return nil
    }

    /// The bit rate Trackside's copy is made at for a quality setting's: its AAC profiles,
    /// rather than the MP3 rates Subsonic servers are asked for.
    static func tracksideBitRate(for maxBitRate: Int?) -> Int? {
        TracksideClient.profile(forBitRate: maxBitRate).flatMap { Int($0.filter(\.isNumber)) }
    }

    /// Matching is done, or there was nothing to match.
    func finishedMatching(_ serverID: String) {
        movedToTrackside.remove(serverID)
        saveMoved()
    }

    // MARK: - Words

    /// Why an address that would send everything unencrypted over the internet isn't used.
    static var insecureAddressReason: String {
        String(localized: "Use an https address for a server outside your home network.")
    }

    /// The network wants a sign-in before it lets anything through.
    static var captivePortalReason: String {
        String(localized: "This Wi-Fi network needs you to sign in first. Sign in, then try again.")
    }

    static func describe(_ error: SubsonicError) -> String {
        switch error {
        case .wrongCredentials: String(localized: "The username, password or API key is wrong.")
        case .authMethodUnsupported: String(localized: "This server doesn’t take that way of signing in. Use a username and password, or an API key with no username.")
        case .captivePortal: captivePortalReason
        case .incompatible: String(localized: "This server is too old for Tracks.")
        case .notFound: String(localized: "The server couldn't find that.")
        case .server(_, let message): message.isEmpty ? String(localized: "The server couldn't answer.") : message
        case .notSubsonic: String(localized: "That address doesn't look like a music server. Check it's the address you use in your browser.")
        case .http(let status): String(localized: "The server answered with an error (\(status)).")
        }
    }

    static func describe(_ error: TracksideError) -> String {
        switch error {
        case .unauthenticated:
            String(localized: "The code, username or password is wrong, or the code has expired.")
        case .forbidden:
            String(localized: "Your account on this server can't do that.")
        case .notFound:
            String(localized: "The server couldn't find that.")
        case .rateLimited:
            String(localized: "Too many tries. Wait a minute, then try again.")
        case .unavailable:
            String(localized: "The server is busy or starting up. Try again in a moment.")
        case .insufficientStorage:
            String(localized: "The server is out of space.")
        case .quotaExceeded:
            String(localized: "You've reached today's limit on this server.")
        case .notTrackside:
            String(localized: "That address doesn't look like a music server. Check it's the address you use in your browser.")
        case .http(let status):
            String(localized: "The server answered with an error (\(status)).")
        case .server(_, _, let message):
            message.isEmpty ? String(localized: "The server couldn't answer.") : message
        case .unproven:
            String(localized: "None of the server's addresses could prove it's your server, so Tracks didn't connect. Check you're on a network you trust, or pair again.")
        case .insecureAddress:
            insecureAddressReason
        case .httpsRequired:
            String(localized: "Your server only connects over https, and none of its https addresses answered. Check its public address.")
        case .mediaTokenExpired, .conflict, .syncReset, .candidateExpired, .preconditionFailed, .invalidState, .featureUnavailable:
            String(localized: "The server couldn't answer.")
        }
    }

    // MARK: - Storage

    private func save() {
        guard let data = try? JSONEncoder().encode(servers) else { return }
        UserDefaults.standard.set(data, forKey: Self.storageKey)
    }

    private func saveKept() {
        guard !isDemo, let data = try? JSONEncoder().encode(kept) else { return }
        UserDefaults.standard.set(data, forKey: Self.keptKey)
    }

    private func saveMoved() {
        guard !isDemo else { return }
        UserDefaults.standard.set(Array(movedToTrackside), forKey: Self.movedKey)
    }

    private static func loadKept() -> [String: ServerKeeps] {
        guard let data = UserDefaults.standard.data(forKey: keptKey) else { return [:] }
        return (try? JSONDecoder().decode([String: ServerKeeps].self, from: data)) ?? [:]
    }

    private static func loadMediaToken(for server: SubsonicServer) -> TracksideMediaToken? {
        guard let text = ServerKeychain.password(for: ServerCredential.mediaTokenAccount(for: server)) else { return nil }
        return try? TracksideJSON.decoder.decode(TracksideMediaToken.self, from: Data(text.utf8))
    }

    private static func saveMediaToken(_ token: TracksideMediaToken, for server: SubsonicServer) {
        guard let data = try? TracksideJSON.encoder.encode(token) else { return }
        ServerKeychain.setPassword(String(decoding: data, as: UTF8.self), for: ServerCredential.mediaTokenAccount(for: server))
    }

    /// Asks a server for its artists, for their pictures and ids. One request, however many.
    private func syncArtists(_ serverID: String) async {
        let generation = syncs.generation(of: serverID)
        guard let client = subsonic[serverID], let found = try? await client.artists() else { return }
        // A big server has thousands of artists to fold and write: not on the main actor.
        let refs = await Self.artistRefs(found)
        guard syncs.isCurrent(serverID, generation), subsonic[serverID] != nil else { return }
        artists[serverID] = refs
        writeArtists(refs, serverID)
    }

    /// The index files' writes, one after another in the order they were asked for, so an
    /// older result can't land over a newer one.
    @ObservationIgnored private var indexWrites: Task<Void, Never>?

    /// Writes a server's artists in the background, or removes them for nil.
    private func writeArtists(_ refs: [String: ServerArtistRef]?, _ serverID: String) {
        let previous = indexWrites
        indexWrites = Task.detached(priority: .utility) {
            await previous?.value
            if let refs {
                Self.saveArtists(refs, serverID)
            } else {
                try? FileManager.default.removeItem(at: Self.artistsURL(serverID))
            }
        }
    }

    /// Writes what a Trackside sync brought: the songs when they changed, the artists, then
    /// the cursor, last, so a cursor is never saved ahead of the songs it stands for.
    private func writeTracksideMirror(songs: [LocalTrack]?, artists: [String: ServerArtistRef], cursor: String?, _ serverID: String) {
        let previous = indexWrites
        indexWrites = Task.detached(priority: .utility) {
            await previous?.value
            if let songs { Self.saveCatalog(songs, serverID) }
            Self.saveArtists(artists, serverID)
            try? TracksideCursorFile.save(cursor, to: Self.cursorURL(serverID))
        }
    }

    @concurrent
    nonisolated private static func artistRefs(_ found: [SubsonicArtist]) async -> [String: ServerArtistRef] {
        var refs: [String: ServerArtistRef] = [:]
        for artist in found {
            refs[StatsCalculator.folded(artist.name)] = ServerArtistRef(id: artist.id, cover: artist.coverArt.flatMap { $0.isEmpty ? nil : $0 })
        }
        return refs
    }

    nonisolated private static func saveArtists(_ refs: [String: ServerArtistRef], _ serverID: String) {
        guard let data = try? JSONEncoder().encode(refs) else { return }
        try? data.write(to: artistsURL(serverID), options: .atomic)
    }

    nonisolated private static func artistsURL(_ serverID: String) -> URL {
        LibraryFolders.index.appending(path: "server-\(serverID)-artists.json")
    }

    nonisolated private static func cursorURL(_ serverID: String) -> URL {
        LibraryFolders.index.appending(path: "server-\(serverID)-cursor.json")
    }

    nonisolated private static func listensURL(_ serverID: String) -> URL {
        LibraryFolders.index.appending(path: "server-\(serverID)-listens.json")
    }

    @concurrent
    nonisolated private static func loadSaved(_ ids: [String]) async -> [(String, [LocalTrack], [String: ServerArtistRef]?)] {
        ids.map { ($0, loadCatalog($0), loadArtists($0)) }
    }

    nonisolated private static func loadArtists(_ serverID: String) -> [String: ServerArtistRef]? {
        guard let data = try? Data(contentsOf: artistsURL(serverID)) else { return nil }
        return try? JSONDecoder().decode([String: ServerArtistRef].self, from: data)
    }

    nonisolated private static func catalogURL(_ serverID: String) -> URL {
        LibraryFolders.index.appending(path: "server-\(serverID).json")
    }

    nonisolated private static func loadCatalog(_ serverID: String) -> [LocalTrack] {
        StoredJSON.loadEach([LocalTrack].self, from: catalogURL(serverID)) ?? []
    }

    nonisolated private static func saveCatalog(_ tracks: [LocalTrack], _ serverID: String) {
        guard let data = try? JSONEncoder().encode(tracks) else { return }
        try? data.write(to: catalogURL(serverID), options: .atomic)
    }

    private func catalogDate(_ serverID: String) -> Date? {
        (try? FileManager.default.attributesOfItem(atPath: Self.catalogURL(serverID).path(percentEncoded: false)))?[.modificationDate] as? Date
    }

    #if DEBUG
    /// A pretend server, for sample data.
    func fillDemo(_ tracks: [LocalTrack], server: SubsonicServer) {
        servers = [server]
        status[server.id] = .online
        catalogs[server.id] = tracks
    }
    #endif
}

/// One Trackside server as Tracks is connected to it: its addresses, its client with the
/// current media token, who this device is there, and the plays waiting to be sent.
@MainActor
final class TracksideConnection {
    let endpoints: ServerEndpoints
    private(set) var client: TracksideClient
    var me: TracksideMe?
    let outbox: ListenOutbox
    var events: Task<Void, Never>?
    /// Which listening ``events`` is, so one ending late doesn't forget the one after it.
    var eventsID: UUID?
    /// Where the events stream picks up after a reconnect.
    var lastEventID: String?

    /// Holds the server's self-signed addresses to their fingerprints.
    let streamTransport: URLSessionTracksideStreamTransport

    init(server: SubsonicServer, apiKey: String, mediaToken: TracksideMediaToken?) {
        let candidates = server.candidateURLs ?? [TracksideServerURL(url: server.url, kind: .lan)]
        let pins = TracksidePins(candidates)
        let transport = URLSessionTracksideTransport(pins: pins)
        streamTransport = URLSessionTracksideStreamTransport(pins: pins)
        endpoints = ServerEndpoints(
            serverUUID: server.serverUUID ?? "",
            candidates: candidates,
            current: server.url,
            proofKey: .device(id: server.deviceID ?? "", apiKey: apiKey),
            pins: pins,
            transport: transport
        )
        client = TracksideClient(serverID: server.id, endpoints: endpoints, apiKey: apiKey, mediaToken: mediaToken, transport: transport)
        outbox = ListenOutbox(file: LibraryFolders.index.appending(path: "server-\(server.id)-listens.json"))
    }

    var acquisitions: TracksideAcquisitions {
        TracksideAcquisitions(client: client, features: me?.features)
    }

    func use(mediaToken: TracksideMediaToken) {
        client = client.with(mediaToken: mediaToken)
    }

    func stopEvents() {
        events?.cancel()
        events = nil
        eventsID = nil
    }
}

/// This device, as it introduces itself to a Trackside server when it's paired.
enum TracksideDeviceIdentity {
    private static let key = "tracksideClientDeviceID"

    /// The same for as long as Tracks is installed, so pairing again replaces this device's
    /// key on the server rather than adding another device.
    static var clientDeviceID: String {
        if let saved = UserDefaults.standard.string(forKey: key) { return saved }
        let made = UUID().uuidString
        UserDefaults.standard.set(made, forKey: key)
        return made
    }

    static var current: TracksideDeviceInfo {
        #if os(iOS)
        let name = UIDevice.current.name
        let platform = "ios"
        #else
        let name = Host.current().localizedName ?? String(localized: "Mac")
        let platform = "macos"
        #endif
        return TracksideDeviceInfo(
            clientDeviceID: clientDeviceID,
            name: name,
            platform: platform,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        )
    }
}

/// A page of a search of your servers, and where each server's next one starts.
struct ServerSearchPage {
    var tracks: [LocalTrack] = []
    /// Where each server's next page starts, for the servers that may have more.
    var next: [String: MusicSearchPosition] = [:]

    var hasMore: Bool { !next.isEmpty }
}

/// Why a server couldn't be asked for a song, in words to show.
struct SongRequestProblem: Error {
    let reason: String
}

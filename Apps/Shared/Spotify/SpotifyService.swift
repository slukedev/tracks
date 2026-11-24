#if SPOTIFY
import SwiftUI
import Security
import os
#if canImport(UIKit)
import UIKit
#endif
import TracksCore
import TracksEngine

/// Spotify as a source: the account, signing in and out, what Library and Search show from
/// it, and its player. Built only with the `SPOTIFY` flag.
///
/// Spotify's metadata and cover addresses are kept in memory for the session only, and
/// forgotten on signing out, as Spotify's terms ask.
@MainActor
@Observable
final class SpotifyService: SpotifySource {
    enum Phase: Equatable {
        case signedOut
        /// The browser sheet is open.
        case signingIn
        /// Back from the browser, or opening the saved account: checking it can play.
        case checking
        case connected
        case failed(SpotifyProblem)
    }

    private(set) var phase: Phase = .signedOut
    private(set) var account: SpotifyAccount?
    private(set) var accountName: String?

    /// Wi-Fi and cellular quality, in Spotify's own words. The Mac has one for every network.
    var wiFiQuality: SpotifyQuality = .stored(SpotifyQuality.wiFiKey) {
        didSet { UserDefaults.standard.set(wiFiQuality.rawValue, forKey: SpotifyQuality.wiFiKey) }
    }
    var cellularQuality: SpotifyQuality = .stored(SpotifyQuality.cellularKey) {
        didSet { UserDefaults.standard.set(cellularQuality.rawValue, forKey: SpotifyQuality.cellularKey) }
    }

    /// Whether this device's network costs money, for the quality to stream at.
    @ObservationIgnored var isExpensiveNetwork: () -> Bool = { false }

    @ObservationIgnored private var madePlayer: SpotifyPlayerEngine?
    @ObservationIgnored private let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Tracks", category: "Spotify")
    @ObservationIgnored private var waitingLogin: SpotifyLogin?
    @ObservationIgnored private var webSignIn: SpotifyWebSignIn?

    /// Signed in once on this device, so a lapsed sign-in asks to be renewed rather than
    /// Spotify disappearing from Sources.
    private var wasAdded = UserDefaults.standard.bool(forKey: SpotifyService.addedKey) {
        didSet { UserDefaults.standard.set(wasAdded, forKey: Self.addedKey) }
    }
    private static let addedKey = "spotifyAdded"

    // Metadata seen this session, by Spotify URI.
    @ObservationIgnored private var knownTracks: [String: SpotifyTrack] = [:]
    @ObservationIgnored private var found: [SpotifyLookup: SpotifyTrack?] = [:]
    @ObservationIgnored private var lists = SpotifyLists()

    init() {
        restore()
    }

    // MARK: SpotifySource

    var isConnected: Bool { phase == .connected && account != nil }

    var needsSignIn: Bool {
        guard wasAdded else { return false }
        switch phase {
        case .connected, .checking, .signingIn: return false
        case .signedOut, .failed: return true
        }
    }

    var engine: any PlayerEngine { player }

    /// Made when first asked for, so a build with Spotify that never signs in has no audio
    /// engine running.
    var player: SpotifyPlayerEngine {
        if let madePlayer { return madePlayer }
        let player = SpotifyPlayerEngine(service: self)
        madePlayer = player
        return player
    }

    func settingsPage() -> AnyView {
        AnyView(SpotifyAccountPage(service: self))
    }

    // MARK: Signing in

    /// The engine's settings for this device. Spotify refuses phones that give no OS version.
    static var config: SpotifyConfig {
        let temporary = FileManager.default.temporaryDirectory.appending(path: "Spotify", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
        #if canImport(UIKit)
        let version = UIDevice.current.systemVersion
        #else
        let os = ProcessInfo.processInfo.operatingSystemVersion
        let version = "\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)"
        #endif
        return SpotifyConfig(osVersion: version, tempDir: temporary.path(percentEncoded: false))
    }

    /// Ports tried in turn for the loopback address Spotify sends the browser back to.
    private nonisolated static let loopbackPorts: [UInt16] = [8898, 8899, 8900, 4381]

    func signIn() {
        guard phase != .signingIn, phase != .checking else { return }
        phase = .signingIn
        Task { await runSignIn() }
    }

    private func runSignIn() async {
        let login: SpotifyLogin
        do {
            login = try await SpotifyCall.run { try Self.makeLogin() }
        } catch {
            phase = .failed(SpotifyProblem(error))
            return
        }
        guard let url = URL(string: login.url()) else {
            phase = .failed(.network)
            return
        }
        waitingLogin = login
        let web = SpotifyWebSignIn()
        webSignIn = web
        let shown = web.show(url) { [weak self] in
            // The sheet closed by the person, not by the redirect arriving.
            guard let self, self.waitingLogin === login else { return }
            self.waitingLogin = nil
            login.cancel()
        }
        guard shown else {
            waitingLogin = nil
            phase = .signedOut
            return
        }
        let callback: String
        do {
            callback = try await SpotifyCall.run { try login.waitForRedirect(timeoutSecs: 600) }
        } catch {
            let wasCancelled = waitingLogin == nil
            waitingLogin = nil
            web.dismiss()
            webSignIn = nil
            phase = wasCancelled ? .signedOut : .failed(SpotifyProblem(error))
            return
        }
        waitingLogin = nil
        web.dismiss()
        webSignIn = nil
        phase = .checking
        do {
            let config = Self.config
            let account = try await SpotifyCall.run { try login.finish(callbackUrl: callback, config: config) }
            await adopt(account)
        } catch {
            phase = .failed(SpotifyProblem(error))
        }
    }

    private nonisolated static func makeLogin() throws -> SpotifyLogin {
        var lastError: (any Error)?
        for port in loopbackPorts {
            do {
                return try SpotifyLogin(redirectUri: "http://127.0.0.1:\(port)/login")
            } catch {
                lastError = error
            }
        }
        throw lastError ?? SpotifyError.Unavailable(detail: "no free port")
    }

    /// Takes on a signed-in account: Premium only, its sign-in kept in the Keychain, then one
    /// song's key asked for, since Spotify refuses some accounts to clients like this one.
    private func adopt(_ account: SpotifyAccount) async {
        phase = .checking
        do {
            try await SpotifyCall.run { try account.connect() }
        } catch {
            phase = .failed(SpotifyProblem(error))
            return
        }
        if account.isPremium() == false {
            account.signOut()
            SpotifyKeychain.delete()
            wasAdded = false
            phase = .failed(.premium)
            return
        }
        SpotifyKeychain.save(account.credentials())
        self.account = account
        accountName = account.username()
        wasAdded = true
        await checkAudio()
    }

    /// Asks for one song's key: the step Spotify refuses for some accounts.
    func checkAudio() async {
        guard let account else { return }
        phase = .checking
        do {
            try await SpotifyCall.run { try Self.tryOneKey(account) }
            phase = .connected
            madePlayer?.accountChanged()
        } catch {
            phase = .failed(SpotifyProblem(error))
        }
    }

    private nonisolated static func tryOneKey(_ account: SpotifyAccount) throws {
        let saved = try account.savedTracks(offset: 0, limit: 1).items.first?.uri
        guard let uri = try saved ?? account.search(query: "a", offset: 0, limit: 1).tracks.items.first?.uri else { return }
        let stream = try account.openStream(uri: uri, options: SpotifyStreamOptions(bitrate: .low, normalise: false))
        defer { stream.close() }
        _ = try stream.read(maxFrames: 1)
    }

    /// The account saved on this device, opened at launch. Away from a network it's kept
    /// and connects when first used.
    private func restore() {
        guard case .found(let credentials) = SpotifyKeychain.read() else { return }
        phase = .checking
        Task {
            let config = Self.config
            do {
                let account = try await SpotifyCall.run { try SpotifyAccount.open(credentials: credentials, config: config) }
                self.account = account
                accountName = account.username()
                do {
                    try await SpotifyCall.run { try account.connect() }
                    SpotifyKeychain.save(account.credentials())
                    if account.isPremium() == false {
                        phase = .failed(.premium)
                        return
                    }
                } catch let error as SpotifyError {
                    // Offline at launch isn't signed out: the session opens on first use.
                    guard case .Network = error else { throw error }
                }
                phase = .connected
                madePlayer?.accountChanged()
            } catch {
                log.error("Couldn't open the saved Spotify account: \(String(describing: SpotifyProblem(error)), privacy: .public)")
                account = nil
                phase = .failed(SpotifyProblem(error))
            }
        }
    }

    /// Signs out, forgets the sign-in and everything seen from Spotify. Plays stay in
    /// History.
    func signOut() {
        waitingLogin?.cancel()
        waitingLogin = nil
        webSignIn?.dismiss()
        webSignIn = nil
        madePlayer?.accountChanged(signingOut: true)
        account?.signOut()
        account = nil
        accountName = nil
        SpotifyKeychain.delete()
        wasAdded = false
        knownTracks = [:]
        found = [:]
        lists = SpotifyLists()
        phase = .signedOut
    }

    /// A play that failed in a way that says the account can't play: the page shows why.
    func playbackFailed(_ problem: SpotifyProblem) {
        switch problem {
        case .refused, .premium, .signIn: phase = .failed(problem)
        case .network: break
        }
    }

    // MARK: Streaming

    var streamOptions: SpotifyStreamOptions {
        #if os(macOS)
        let quality = wiFiQuality
        #else
        let quality = isExpensiveNetwork() ? cellularQuality : wiFiQuality
        #endif
        return SpotifyStreamOptions(bitrate: quality.bitrate, normalise: true)
    }

    // MARK: Finding songs

    /// Remembers tracks seen on a page, so playing them needs no further lookup.
    func remember(_ tracks: [SpotifyTrack]) {
        for track in tracks { knownTracks[track.uri] = track }
    }

    func knownTrack(_ uri: String) -> SpotifyTrack? {
        knownTracks[uri]
    }

    /// The Spotify track for a song: by its URI when it has one, else by ISRC, else by title
    /// and artist. Nil when Spotify doesn't have it.
    func track(for lookup: SpotifyLookup) async -> SpotifyTrack? {
        if let uri = lookup.uri, let known = knownTracks[uri] { return known }
        if let answer = found[lookup] { return answer }
        guard let account else { return nil }
        let answer = try? await SpotifyCall.run { try Self.find(lookup, in: account) }
        found[lookup] = .some(answer)
        if let answer { knownTracks[answer.uri] = answer }
        return answer
    }

    /// A song found on Spotify by name, as the player takes it.
    func song(title: String, artist: String, album: String?) async -> HistorySong? {
        await track(for: SpotifyLookup(title: title, artist: artist, album: album)).map(HistorySong.init)
    }

    /// Several by URI, in one request where the engine can.
    func tracks(_ uris: [String]) async throws -> [SpotifyTrack] {
        let missing = uris.filter { knownTracks[$0] == nil }
        if !missing.isEmpty, let account {
            for chunk in stride(from: 0, to: missing.count, by: 50).map({ Array(missing[$0..<min($0 + 50, missing.count)]) }) {
                let fetched = try await SpotifyCall.run { try account.tracks(uris: chunk) }
                remember(fetched)
            }
        }
        return uris.compactMap { knownTracks[$0] }
    }

    private nonisolated static func find(_ lookup: SpotifyLookup, in account: SpotifyAccount) throws -> SpotifyTrack? {
        if let uri = lookup.uri { return try account.track(uri: uri) }
        if let isrc = lookup.isrc, !isrc.isEmpty,
           let match = try account.search(query: "isrc:\(isrc)", offset: 0, limit: 5).tracks.items.first {
            return match
        }
        let query = "track:\(lookup.title) artist:\(lookup.artist)"
        let results = try account.search(query: query, offset: 0, limit: 10).tracks.items
        return results.first { lookup.matches($0) }
    }

    // MARK: Library

    func savedAlbums(refresh: Bool = false) async throws -> [SpotifyAlbum] {
        if !refresh, let albums = lists.albums.fresh { return albums }
        guard let account else { return [] }
        let albums = try await SpotifyCall.run { try Self.everything(limit: 50) { try account.savedAlbums(offset: $0, limit: $1) } }
        lists.albums = .init(albums)
        return albums
    }

    func playlists(refresh: Bool = false) async throws -> [SpotifyPlaylist] {
        if !refresh, let playlists = lists.playlists.fresh { return playlists }
        guard let account else { return [] }
        let playlists = try await SpotifyCall.run { try Self.everything(limit: 50) { try account.playlists(offset: $0, limit: $1) } }
        lists.playlists = .init(playlists)
        return playlists
    }

    func likedSongs(refresh: Bool = false) async throws -> [SpotifyTrack] {
        if !refresh, let liked = lists.liked.fresh { return liked }
        guard let account else { return [] }
        let liked = try await SpotifyCall.run { try Self.everything(limit: 50) { try account.savedTracks(offset: $0, limit: $1) } }
        lists.liked = .init(liked)
        remember(liked)
        return liked
    }

    func album(_ uri: String) async throws -> (SpotifyAlbum, [SpotifyTrack]) {
        guard let account else { throw SpotifyError.Auth(detail: "signed out") }
        let album = try await SpotifyCall.run { try account.album(uri: uri) }
        return (album, try await tracks(album.trackUris))
    }

    func playlist(_ uri: String) async throws -> (SpotifyPlaylist, [SpotifyTrack]) {
        guard let account else { throw SpotifyError.Auth(detail: "signed out") }
        let playlist = try await SpotifyCall.run { try account.playlist(uri: uri) }
        return (playlist, try await tracks(playlist.trackUris))
    }

    func artist(_ uri: String) async throws -> (artist: SpotifyArtist, top: [SpotifyTrack], albums: [SpotifyAlbum]) {
        guard let account else { throw SpotifyError.Auth(detail: "signed out") }
        let artist = try await SpotifyCall.run { try account.artist(uri: uri) }
        let top = try await tracks(Array(artist.topTrackUris.prefix(10)))
        let albumURIs = Array(artist.albumUris.prefix(12))
        let albums = try await SpotifyCall.run { albumURIs.compactMap { try? account.album(uri: $0) } }
        return (artist, top, albums)
    }

    func search(_ query: String) async throws -> SpotifySearchResults? {
        guard let account else { return nil }
        let results = try await SpotifyCall.run { try account.search(query: query, offset: 0, limit: 10) }
        remember(results.tracks.items)
        return results
    }

    /// Every page of a list, up to a limit that keeps a huge library from holding Library up.
    private nonisolated static func everything<Page: SpotifyPaged>(limit: UInt32, _ fetch: (UInt32, UInt32) throws -> Page) throws -> [Page.Item] {
        var items: [Page.Item] = []
        var offset: UInt32? = 0
        while let next = offset, items.count < 2_000 {
            let page = try fetch(next, limit)
            items += page.items
            offset = page.nextOffset
        }
        return items
    }
}

/// What went wrong with Spotify, in the words the page and the player show.
nonisolated enum SpotifyProblem: Equatable, Sendable, CustomStringConvertible {
    case premium
    /// Spotify won't give this account's songs' keys to clients like Tracks.
    case refused
    case network
    case signIn

    init(_ error: any Error) {
        switch error as? SpotifyError {
        case .NotPremium: self = .premium
        case .AudioKeyRefused: self = .refused
        case .Auth: self = .signIn
        case .Network, .RateLimited, .Unavailable, nil: self = .network
        }
    }

    var message: String {
        switch self {
        case .premium: String(localized: "Spotify Premium is needed to play here.")
        case .refused: String(localized: "Spotify can't play this account in Tracks.")
        case .network: String(localized: "Can't reach Spotify. Check your connection.")
        case .signIn: String(localized: "Spotify signed you out. Sign in again.")
        }
    }

    var description: String {
        switch self {
        case .premium: "premium"
        case .refused: "refused"
        case .network: "network"
        case .signIn: "signIn"
        }
    }
}

/// Spotify's quality names, for the engine's bitrates.
nonisolated enum SpotifyQuality: String, CaseIterable, Identifiable {
    case normal, high, veryHigh

    var id: String { rawValue }

    static let wiFiKey = "spotifyQualityWiFi"
    static let cellularKey = "spotifyQualityCellular"

    static func stored(_ key: String) -> SpotifyQuality {
        UserDefaults.standard.string(forKey: key).flatMap(SpotifyQuality.init(rawValue:)) ?? .high
    }

    var title: LocalizedStringKey {
        switch self {
        case .normal: "Normal"
        case .high: "High"
        case .veryHigh: "Very High"
        }
    }

    var bitrate: SpotifyBitrate {
        switch self {
        case .normal: .low
        case .high: .normal
        case .veryHigh: .high
        }
    }
}

/// A song to find on Spotify: by URI, by ISRC, or by its name.
nonisolated struct SpotifyLookup: Hashable, Sendable {
    let title: String
    let artist: String
    let album: String?
    let isrc: String?
    let uri: String?

    init(title: String, artist: String, album: String? = nil, isrc: String? = nil, uri: String? = nil) {
        self.title = title
        self.artist = artist
        self.album = album
        self.isrc = isrc
        self.uri = uri
    }

    init(_ song: HistorySong) {
        let uri = song.songID.hasPrefix("spotify:track:") ? song.songID : nil
        self.init(title: song.title, artist: song.artistName, album: song.albumTitle, uri: uri)
    }

    init(_ track: SpotifyTrack) {
        self.init(title: track.title, artist: track.artistLine, album: track.album?.title, isrc: track.isrc, uri: track.uri)
    }

    /// The same title, and an artist this one credits.
    func matches(_ track: SpotifyTrack) -> Bool {
        guard Self.fold(track.title) == Self.fold(title) else { return false }
        let credited = Self.fold(artist)
        return track.artists.contains { credited.contains(Self.fold($0.name)) }
    }

    private static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

nonisolated extension SpotifyTrack {
    var artistLine: String { artists.map(\.name).joined(separator: ", ") }
    var duration: TimeInterval { TimeInterval(durationMs) / 1000 }
}

nonisolated extension SpotifyAlbum {
    var artistLine: String { artists.map(\.name).joined(separator: ", ") }
}

extension HistorySong {
    /// A Spotify track, known to the player by its URI.
    init(_ track: SpotifyTrack) {
        self.init(songID: track.uri, title: track.title, artistName: track.artistLine, albumTitle: track.album?.title, artworkURL: track.artworkUrl)
    }
}

/// The web address for a Spotify URI, for Open in Spotify: `spotify:album:ID` is
/// `https://open.spotify.com/album/ID`.
nonisolated enum SpotifyLink {
    static func url(for uri: String) -> URL? {
        let parts = uri.split(separator: ":")
        guard parts.count >= 3, parts[0] == "spotify" else { return nil }
        return URL(string: "https://open.spotify.com/\(parts[1])/\(parts[2])")
    }
}

/// A page of anything from the engine.
nonisolated protocol SpotifyPaged {
    associatedtype Item
    var items: [Item] { get }
    var nextOffset: UInt32? { get }
}

nonisolated extension SpotifyAlbumPage: SpotifyPaged {}
nonisolated extension SpotifyPlaylistPage: SpotifyPaged {}
nonisolated extension SpotifyTrackPage: SpotifyPaged {}

/// The library's lists, kept for a while so moving between pages doesn't ask again.
private struct SpotifyLists {
    struct Kept<Value> {
        var value: Value?
        var at: Date = .distantPast

        init(_ value: Value? = nil) {
            self.value = value
            at = value == nil ? .distantPast : .now
        }

        var fresh: Value? {
            Date.now.timeIntervalSince(at) < 10 * 60 ? value : nil
        }
    }

    var albums = Kept<[SpotifyAlbum]>()
    var playlists = Kept<[SpotifyPlaylist]>()
    var liked = Kept<[SpotifyTrack]>()
}

/// The engine's calls block for up to a minute, so they run on a queue of their own rather
/// than on Swift's shared threads.
nonisolated enum SpotifyCall {
    private static let queue = DispatchQueue(label: "com.luke.tracks.spotify", qos: .userInitiated, attributes: .concurrent)

    static func run<Value: Sendable>(_ work: @escaping @Sendable () throws -> Value) async throws -> Value {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                continuation.resume(with: Result { try work() })
            }
        }
    }
}

/// The sign-in the engine hands back, in the Keychain on this device only, readable once the
/// device has been unlocked so playback carries on with the screen locked. Never logged.
nonisolated enum SpotifyKeychain {
    enum Read {
        case found(Data)
        case missing
        case unavailable
    }

    private static var item: [CFString: Any] {
        var item: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: "com.luke.tracks.spotify",
            kSecAttrAccount: "credentials",
        ]
        #if os(macOS)
        item[kSecUseDataProtectionKeychain] = true
        #endif
        return item
    }

    static func read() -> Read {
        var query = item
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var result: CFTypeRef?
        switch SecItemCopyMatching(query as CFDictionary, &result) {
        case errSecSuccess:
            guard let data = result as? Data else { return .missing }
            return .found(data)
        case errSecItemNotFound:
            return .missing
        default:
            return .unavailable
        }
    }

    @discardableResult
    static func save(_ data: Data) -> Bool {
        let update: [CFString: Any] = [kSecValueData: data, kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        switch SecItemUpdate(item as CFDictionary, update as CFDictionary) {
        case errSecSuccess:
            return true
        case errSecItemNotFound:
            let add = item.merging(update) { $1 }
            return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
        default:
            return false
        }
    }

    static func delete() {
        SecItemDelete(item as CFDictionary)
    }
}

extension AppModel {
    /// Spotify, as this build has it.
    var spotifyService: SpotifyService? { spotify as? SpotifyService }
}
#endif

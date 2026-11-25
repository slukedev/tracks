import Foundation

/// User-facing capture settings, shared with extensions through the App Group.
///
/// In `UserDefaults` so widgets and intents can read them without opening the model container.
///
/// Keys an older build may still write, and a newer one must not take for something else:
/// `forceCapture`, `autoPlayBack`, `importsRecentlyPlayed`, `recentlyPlayedAnchor`,
/// `passedOverSongs`, `scrobblesImported` and `showsUpNextInWidget`. Tracks once recorded
/// songs played in other apps and played radio back; nothing reads these now.
public struct CaptureSettings: Sendable {
    public static let playlistNameKey = "playlistName"
    public static let playlistIDKey = "playlistID"
    public static let dedupeWindowKey = "dedupeWindowSeconds"
    public static let autoAddKey = "autoAddToPlaylist"
    public static let capturesOnDemandKey = "capturesOnDemand"
    public static let minimumListenShareKey = "minimumListenShare"
    /// When a play counted after a set time. Read once, by ``SettingsMigrator``.
    static let legacyMinimumListenKey = "minimumListenSeconds"
    public static let forgottenSongsKey = "forgottenSongs"
    public static let forgottenSongDatesKey = "forgottenSongDates"
    public static let scrobblesToLastFMKey = "scrobblesToLastFM"
    public static let scrobblesSpotifyKey = "scrobblesSpotifyToLastFM"
    public static let menuBarLabelStyleKey = "menuBarLabelStyle"
    public static let menuBarLabelFormatKey = "menuBarLabelFormat"
    public static let animatesMenuBarKey = "animatesMenuBar"
    public static let showsMenuBarProgressKey = "showsMenuBarProgress"
    public static let excludedStationsKey = "excludedStations"
    public static let blockedArtistsKey = "blockedArtists"
    public static let artworkMissesKey = "artworkMisses"
    public static let lastFMHistoryKey = "lastFMHistoryImport"

    public static let defaultPlaylistName = "Heard on Radio"

    /// The suite name, not the `UserDefaults`, which isn't `Sendable`.
    /// `UserDefaults(suiteName:)` returns a shared instance, so resolving it each time is cheap.
    private let suiteName: String?

    /// Falls back to standard defaults without an App Group (previews, tests).
    public init(suiteName: String? = AppGroup.identifier) {
        self.suiteName = suiteName
    }

    private var defaults: UserDefaults {
        suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    /// The store this type uses. Pass it to `@AppStorage`, which otherwise writes to
    /// `UserDefaults.standard` where nothing here will read it.
    public static var sharedDefaults: UserDefaults {
        AppGroup.identifier.flatMap { UserDefaults(suiteName: $0) } ?? .standard
    }

    /// A blank name reads as unset. A whitespace-only name once broke playback, since Music
    /// was asked for `playlist " "`.
    public var playlistName: String {
        get {
            let stored = defaults.string(forKey: Self.playlistNameKey)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return (stored?.isEmpty == false) ? stored! : Self.defaultPlaylistName
        }
        nonmutating set {
            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            defaults.set(trimmed.isEmpty ? Self.defaultPlaylistName : trimmed,
                         forKey: Self.playlistNameKey)
        }
    }

    /// The playlist we created. Nil until then. Stored by ID so renaming the playlist
    /// doesn't make us create another.
    public var playlistID: String? {
        get { defaults.string(forKey: Self.playlistIDKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.playlistIDKey) }
    }

    public var dedupePolicy: DedupePolicy {
        let stored = defaults.double(forKey: Self.dedupeWindowKey)
        return DedupePolicy(window: stored > 0 ? stored : DedupePolicy.default.window)
    }

    public func setDedupeWindow(seconds: TimeInterval) {
        defaults.set(seconds, forKey: Self.dedupeWindowKey)
    }

    /// Half, as Last.fm counts a scrobble.
    public static let defaultMinimumListenShare = 0.5
    /// The most that can be asked for. A song that had to play to the end would often
    /// finish, and move on, before the check that counts it.
    public static let maximumListenShare = 0.9
    /// The length taken for a song whose own isn't known, such as one on a live stream.
    public static let assumedSongLength: TimeInterval = 240

    /// How much of a song must play before it counts, 0 to ``maximumListenShare``. Zero
    /// captures as soon as a song is seen.
    public var minimumListenShare: Double {
        get {
            guard defaults.object(forKey: Self.minimumListenShareKey) != nil else {
                return Self.defaultMinimumListenShare
            }
            return Self.clampedShare(defaults.double(forKey: Self.minimumListenShareKey))
        }
        nonmutating set { defaults.set(Self.clampedShare(newValue), forKey: Self.minimumListenShareKey) }
    }

    /// How long a song of `duration` must play before it counts, with this device's share.
    public func minimumListen(forDuration duration: TimeInterval?) -> TimeInterval {
        Self.minimumListen(share: minimumListenShare, duration: duration)
    }

    /// How long a song of `duration` must play before `share` of it has.
    public static func minimumListen(share: Double, duration: TimeInterval?) -> TimeInterval {
        let length = duration.flatMap { $0 > 0 ? $0 : nil } ?? assumedSongLength
        return clampedShare(share) * length
    }

    private static func clampedShare(_ share: Double) -> Double {
        min(max(0, share), maximumListenShare)
    }

    /// Whether to keep on-demand plays as well as radio. On by default.
    public var capturesOnDemand: Bool {
        get { defaults.object(forKey: Self.capturesOnDemandKey) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Self.capturesOnDemandKey) }
    }

    /// What the macOS menu bar shows. Defaults to the note symbol.
    public var menuBarLabelStyle: MenuBarLabelStyle {
        get { MenuBarLabelStyle(stored: defaults.string(forKey: Self.menuBarLabelStyleKey)) }
        nonmutating set { defaults.set(newValue.rawValue, forKey: Self.menuBarLabelStyleKey) }
    }

    /// Whether the status item cross-fades when the song changes. On by default; Reduce
    /// Motion turns it off regardless.
    public var animatesMenuBar: Bool {
        get { defaults.object(forKey: Self.animatesMenuBarKey) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Self.animatesMenuBarKey) }
    }

    /// Whether the menu bar window shows the song's progress, to see and drag. On by default.
    public var showsMenuBarProgress: Bool {
        get { defaults.object(forKey: Self.showsMenuBarProgressKey) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Self.showsMenuBarProgressKey) }
    }

    /// The user's own format, used only by ``MenuBarLabelStyle/custom``.
    ///
    /// Empty is allowed (cover and no text), so blank isn't replaced with the default.
    public var menuBarLabelFormat: String {
        get { defaults.string(forKey: Self.menuBarLabelFormatKey) ?? MenuBarLabelFormat.default }
        nonmutating set { defaults.set(newValue, forKey: Self.menuBarLabelFormatKey) }
    }

    public var autoAddToPlaylist: Bool {
        get { defaults.object(forKey: Self.autoAddKey) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Self.autoAddKey) }
    }

    public var excludedStations: Set<String> {
        get { Set(defaults.stringArray(forKey: Self.excludedStationsKey) ?? []) }
        nonmutating set { defaults.set(Array(newValue).sorted(), forKey: Self.excludedStationsKey) }
    }

    /// Artists never to play or suggest, as they were named when blocked, first blocked first.
    public var blockedArtists: BlockedArtists {
        get { BlockedArtists(defaults.stringArray(forKey: Self.blockedArtistsKey) ?? []) }
        nonmutating set { defaults.set(newValue.names, forKey: Self.blockedArtistsKey) }
    }

    /// Songs the user has removed, by ``SongIdentity/key(title:artistName:)``, so the Last.fm
    /// history import doesn't bring them back.
    public var forgottenSongs: Set<String> {
        get { Set(defaults.stringArray(forKey: Self.forgottenSongsKey) ?? []) }
        nonmutating set { defaults.set(Array(newValue).sorted(), forKey: Self.forgottenSongsKey) }
    }

    /// When each song in ``forgottenSongs`` was last forgotten, kept beside it as entries that
    /// sort oldest first, `"<seconds since 1970>␞<song key>"`, so two devices' copies join
    /// with a union.
    ///
    /// A key of its own, so a build that only knows ``forgottenSongs`` reads that unchanged.
    /// Songs forgotten before this existed have no date and stay forgotten for good.
    public var forgottenSongDates: [String: Date] {
        var dates: [String: Date] = [:]
        for entry in defaults.stringArray(forKey: Self.forgottenSongDatesKey) ?? [] {
            guard let (at, key) = Self.parseDated(entry) else { continue }
            dates[key] = max(dates[key] ?? at, at)
        }
        return dates
    }

    /// Adds a song to ``forgottenSongs`` and notes when.
    public func noteForgotten(_ songKey: String, at date: Date = .now) {
        forgottenSongs.insert(songKey)
        var entries = Set(defaults.stringArray(forKey: Self.forgottenSongDatesKey) ?? [])
        entries.insert(Self.dated(songKey, at: date))
        defaults.set(entries.sorted(), forKey: Self.forgottenSongDatesKey)
    }

    /// Whether a play of a forgotten song, dated `playedAt`, still counts as forgotten: only
    /// plays from before the song was forgotten. Forgetting a song used to block every later
    /// play of it on every device for good.
    static func isForgotten(
        _ songKey: String,
        playedAt: Date,
        forgotten: Set<String>,
        dates: [String: Date]
    ) -> Bool {
        guard forgotten.contains(songKey) else { return false }
        guard let forgottenAt = dates[songKey] else { return true }
        return playedAt < forgottenAt
    }

    private static let datedSeparator: Character = "\u{1E}"

    /// Whole seconds, padded so the entries sort by time as strings.
    static func dated(_ songKey: String, at date: Date) -> String {
        let seconds = max(0, Int(date.timeIntervalSince1970))
        return String(format: "%011d", seconds) + String(datedSeparator) + songKey
    }

    static func parseDated(_ entry: String) -> (at: Date, key: String)? {
        guard let split = entry.firstIndex(of: datedSeparator),
              let seconds = Int(entry[..<split])
        else { return nil }
        return (Date(timeIntervalSince1970: TimeInterval(seconds)), String(entry[entry.index(after: split)...]))
    }

    /// Whether to send listening to Last.fm. On by default, but nothing is sent until an
    /// account is connected.
    public var scrobblesToLastFM: Bool {
        get { defaults.object(forKey: Self.scrobblesToLastFMKey) as? Bool ?? true }
        nonmutating set { defaults.set(newValue, forKey: Self.scrobblesToLastFMKey) }
    }

    /// Whether plays from Spotify go to Last.fm too. Off by default: Spotify sends them itself
    /// once it's linked to Last.fm, and twice would count them twice.
    public var scrobblesSpotify: Bool {
        get { defaults.object(forKey: Self.scrobblesSpotifyKey) as? Bool ?? false }
        nonmutating set { defaults.set(newValue, forKey: Self.scrobblesSpotifyKey) }
    }

    /// Songs the catalog had no cover for, by song ID, with when to ask again.
    ///
    /// Kept so the artwork backfill moves on past them rather than asking about the same
    /// songs on every pass. See ``CaptureCoordinator``. Stored as seconds since 1970, which
    /// a property list holds without fuss.
    public var artworkMisses: [String: Date] {
        get {
            let stored = defaults.dictionary(forKey: Self.artworkMissesKey) as? [String: Double] ?? [:]
            return stored.mapValues { Date(timeIntervalSince1970: $0) }
        }
        nonmutating set {
            defaults.set(newValue.mapValues(\.timeIntervalSince1970), forKey: Self.artworkMissesKey)
        }
    }

    /// How far importing the Last.fm history has got, for whichever account it was. Kept on
    /// this device: the rows sync, and another device's import skips what they cover.
    /// Stored as JSON. See ``LastFMHistory/Progress``.
    public var lastFMHistoryProgress: LastFMHistory.Progress? {
        get {
            defaults.data(forKey: Self.lastFMHistoryKey)
                .flatMap { try? JSONDecoder().decode(LastFMHistory.Progress.self, from: $0) }
        }
        nonmutating set {
            defaults.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: Self.lastFMHistoryKey)
        }
    }

    /// The progress for this account, or a fresh start if it's never been imported or the
    /// progress is another account's.
    public func lastFMHistory(for username: String) -> LastFMHistory.Progress {
        guard let stored = lastFMHistoryProgress, stored.username == username else {
            return LastFMHistory.Progress(username: username)
        }
        return stored
    }

    public func hasForgotten(title: String, artistName: String) -> Bool {
        forgottenSongs.contains(SongIdentity.key(title: title, artistName: artistName))
    }

    public func isExcluded(station: String?) -> Bool {
        guard let station else { return false }
        return excludedStations.contains(station)
    }
}

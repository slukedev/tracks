import Foundation

/// Where a play's music came from: Apple Music (the Music app, or Tracks’ own Apple Music
/// player), music Tracks plays itself from files and servers, or Spotify.
///
/// Stored on a ``Capture`` as its raw value, the same values the engine stores. Plays kept
/// before Tracks knew the difference have none, and count as Apple Music, which is all Tracks
/// could hear then. A value from a newer version is kept as it is, so a play synced from
/// another device is never rewritten as Apple Music's.
public enum PlaySource: Sendable, Hashable, Codable, RawRepresentable {
    case appleMusic
    /// Files on the device, downloads and servers. The stored value predates the name change.
    case yourMusic
    case spotify
    case unknown(String)

    /// The sources this version knows, in the order the statistics offer them.
    public static let known: [PlaySource] = [.appleMusic, .yourMusic, .spotify]

    /// The key Tracks’ own players set in an observation's raw fields, and the values that mark
    /// them.
    public static let observationKey = "source"
    public static let yourMusicMarker = "Your Music"
    public static let spotifyMarker = "Spotify"

    public init(rawValue: String) {
        switch rawValue {
        case "", "appleMusic": self = .appleMusic
        case "yourMusic": self = .yourMusic
        case "spotify": self = .spotify
        default: self = .unknown(rawValue)
        }
    }

    public var rawValue: String {
        switch self {
        case .appleMusic: "appleMusic"
        case .yourMusic: "yourMusic"
        case .spotify: "spotify"
        case .unknown(let stored): stored
        }
    }

    /// A stored value read back: nil or empty is Apple Music.
    public init(stored rawValue: String?) {
        self.init(rawValue: rawValue ?? "")
    }

    /// Where an observed song is playing from. Only Tracks’ own players say; every other
    /// observation comes from Apple Music's players.
    public init(observation: NowPlayingObservation) {
        switch observation.rawFields[Self.observationKey] {
        case Self.yourMusicMarker: self = .yourMusic
        case Self.spotifyMarker: self = .spotify
        default: self = .appleMusic
        }
    }
}

/// Which plays the statistics count, when the person has asked to see their sources apart.
/// Plays from a source this version doesn't know count only under All.
public enum SourceScope: String, Sendable, CaseIterable, Identifiable, Codable {
    case all
    case appleMusic
    case yourMusic
    case spotify

    public var id: String { rawValue }

    /// Where the scope on show is remembered.
    public static let storageKey = "statsSourceScope"
    /// Where the setting that offers the scope is remembered. Off unless turned on.
    public static let separatesKey = "separatesMusicSources"

    public init(_ source: PlaySource) {
        switch source {
        case .appleMusic: self = .appleMusic
        case .yourMusic: self = .yourMusic
        case .spotify: self = .spotify
        case .unknown: self = .all
        }
    }

    public func includes(_ source: PlaySource) -> Bool {
        switch self {
        case .all: true
        case .appleMusic: source == .appleMusic
        case .yourMusic: source == .yourMusic
        case .spotify: source == .spotify
        }
    }

    /// The scopes worth offering: All, then each source with plays. Empty while everything
    /// came from one source, since there's nothing to tell apart.
    public static func offered(played: Set<PlaySource>) -> [SourceScope] {
        let scopes = PlaySource.known.filter(played.contains).map(SourceScope.init)
        return scopes.count > 1 || played.contains(where: { $0 != .appleMusic }) ? [.all] + scopes : []
    }

    /// The scope that applies: everything unless the setting is on and the chosen source has
    /// plays to tell apart.
    public static func effective(_ chosen: SourceScope, separates: Bool, played: Set<PlaySource>) -> SourceScope {
        separates && offered(played: played).contains(chosen) ? chosen : .all
    }
}

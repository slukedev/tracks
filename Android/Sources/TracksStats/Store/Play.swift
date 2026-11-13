import Foundation

/// One song Tracks kept on Android.
///
/// The iPhone and Mac keep plays in SwiftData, which Android doesn't have. Here they live in a
/// JSON file (see ``PlayLog``) and turn into the same ``CaptureStat`` values the other apps
/// hand to ``StatsCalculator``, so every number means the same thing on all three.
public struct Play: Codable, Sendable, Hashable, Identifiable {
    public var title: String
    public var artistName: String
    public var albumTitle: String?
    public var artworkURL: String?
    public var playedAt: Date
    /// ``CaptureKind`` as its raw value. Android keeps songs it hears as on-demand plays and
    /// Last.fm scrobbles as `lastFM`.
    public var kindRawValue: String
    /// The song on the music server it was played from, so it can be played again from the
    /// statistics.
    public var songID: String?
    public var serverID: String?
    /// The app that played it, by name, and its package. Only ever Tracks since Android keeps
    /// only what it plays itself; kept for files from builds that listened to other apps.
    public var appName: String?
    public var appPackage: String?
    /// Length in seconds, when the player said.
    public var duration: TimeInterval?
    /// Part of the sample history, so it can be cleared without touching real plays. Optional
    /// so files written before it existed still read.
    public var isSample: Bool?

    public init(
        title: String,
        artistName: String,
        albumTitle: String? = nil,
        artworkURL: String? = nil,
        playedAt: Date,
        kind: CaptureKind = .onDemand,
        songID: String? = nil,
        serverID: String? = nil,
        appName: String? = nil,
        appPackage: String? = nil,
        duration: TimeInterval? = nil,
        isSample: Bool? = nil
    ) {
        self.title = title
        self.artistName = artistName
        self.albumTitle = albumTitle
        self.artworkURL = artworkURL
        self.playedAt = playedAt
        self.kindRawValue = kind.rawValue
        self.songID = songID
        self.serverID = serverID
        self.appName = appName
        self.appPackage = appPackage
        self.duration = duration
        self.isSample = isSample
    }

    public var kind: CaptureKind { CaptureKind(rawValue: kindRawValue) ?? .onDemand }

    /// Title, artist and time together: the same song can't be kept twice at the same moment.
    public var id: String { "\(songKey)\u{1F}\(Int(playedAt.timeIntervalSince1970))" }

    /// The folded title and artist the statistics group on.
    public var songKey: String { HistoryImport.key(title: title, artistName: artistName) }

    public var stat: CaptureStat {
        CaptureStat(
            songKey: songKey,
            songID: songID ?? "",
            title: title,
            artistName: artistName,
            albumTitle: albumTitle,
            artworkURL: artworkURL,
            capturedAt: playedAt,
            kind: kind
        )
    }
}

extension Play {
    /// The sample history the iPhone and Mac show, for screenshots and trying the screens
    /// without a server. Only offered in debug builds.
    public static func samples(endingAt now: Date = .now, calendar: Calendar = .current) -> [Play] {
        DemoLibrary.plays(endingAt: now, calendar: calendar).map { play in
            Play(
                title: play.title,
                artistName: play.artistName,
                albumTitle: play.albumTitle,
                playedAt: play.capturedAt,
                kind: .onDemand,
                isSample: true
            )
        }
    }
}

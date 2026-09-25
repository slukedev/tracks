import Foundation

/// One song Motif kept on Android.
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
    /// The app that played it, by name ("YouTube Music"), when Motif heard it itself.
    public var appName: String?
    /// The app's package, which is what's stable across renames and languages.
    public var appPackage: String?
    /// Length in seconds, when the player said.
    public var duration: TimeInterval?

    public init(
        title: String,
        artistName: String,
        albumTitle: String? = nil,
        artworkURL: String? = nil,
        playedAt: Date,
        kind: CaptureKind = .onDemand,
        appName: String? = nil,
        appPackage: String? = nil,
        duration: TimeInterval? = nil
    ) {
        self.title = title
        self.artistName = artistName
        self.albumTitle = albumTitle
        self.artworkURL = artworkURL
        self.playedAt = playedAt
        self.kindRawValue = kind.rawValue
        self.appName = appName
        self.appPackage = appPackage
        self.duration = duration
    }

    public var kind: CaptureKind { CaptureKind(rawValue: kindRawValue) ?? .onDemand }

    /// Title, artist and time together: the same song can't be kept twice at the same moment.
    public var id: String { "\(songKey)\u{1F}\(Int(playedAt.timeIntervalSince1970))" }

    /// The folded title and artist the statistics group on.
    public var songKey: String { HistoryImport.key(title: title, artistName: artistName) }

    public var stat: CaptureStat {
        CaptureStat(
            songKey: songKey,
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
    /// The sample history the iPhone and Mac show, as Android keeps it.
    ///
    /// Radio is an Apple Music idea, so sample radio plays count as ordinary plays here, and
    /// they're spread across the players an Android phone would have.
    public static func samples(endingAt now: Date = .now, calendar: Calendar = .current) -> [Play] {
        let apps = SampleApp.all
        return DemoLibrary.plays(endingAt: now, calendar: calendar).enumerated().map { index, play in
            // One player per listening stretch, as people tend to stay in one app.
            let hour = calendar.component(.hour, from: play.capturedAt)
            let app = apps[(hour / 6 + index / 400) % apps.count]
            return Play(
                title: play.title,
                artistName: play.artistName,
                albumTitle: play.albumTitle,
                playedAt: play.capturedAt,
                kind: .onDemand,
                appName: app.name,
                appPackage: app.package
            )
        }
    }

    private struct SampleApp {
        let name: String
        let package: String

        static let all = [
            SampleApp(name: "YouTube Music", package: "com.google.android.apps.youtube.music"),
            SampleApp(name: "Spotify", package: "com.spotify.music"),
            SampleApp(name: "Apple Music", package: "com.apple.android.music"),
        ]
    }
}

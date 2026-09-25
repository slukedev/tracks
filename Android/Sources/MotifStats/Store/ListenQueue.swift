import Foundation

/// A song as Motif's player wrote it down.
///
/// The player (`PlaybackService.kt`) runs in its own service, so it keeps playing, and keeps
/// count, with the app closed. It appends one JSON line per song to a queue file once the song
/// has played long enough, or when it stops after playing for a while. Whether that's a play is
/// decided here, by the same ``ScrobblePolicy`` the iPhone and Mac use.
public struct Listen: Codable, Sendable, Equatable {
    public var title: String
    public var artist: String
    public var album: String?
    public var artworkURL: String?
    /// Seconds since 1970, when the song started.
    public var startedAt: Double
    public var duration: Double?
    public var playedFor: Double
    /// The song on the server, for scrobbling it there and playing it again.
    public var songID: String?
    public var serverID: String?

    public init(
        title: String,
        artist: String,
        album: String? = nil,
        artworkURL: String? = nil,
        startedAt: Double,
        duration: Double? = nil,
        playedFor: Double,
        songID: String? = nil,
        serverID: String? = nil
    ) {
        self.title = title
        self.artist = artist
        self.album = album
        self.artworkURL = artworkURL
        self.startedAt = startedAt
        self.duration = duration
        self.playedFor = playedFor
        self.songID = songID
        self.serverID = serverID
    }

    /// Whether it played long enough to keep. Without a length from the player, 30 seconds of
    /// playing, the shortest song the policy would ever count.
    public var counts: Bool {
        guard let duration, duration > 0 else { return playedFor >= ScrobblePolicy.minimumTrackDuration }
        return ScrobblePolicy().shouldScrobble(duration: duration, playedFor: playedFor)
    }

    public var play: Play {
        Play(
            title: title,
            artistName: artist,
            albumTitle: album,
            artworkURL: artworkURL,
            playedAt: Date(timeIntervalSince1970: startedAt),
            kind: .onDemand,
            songID: songID,
            serverID: serverID,
            duration: duration
        )
    }
}

/// The file the player appends to, and taking what's in it.
public enum ListenQueue {
    public static let fileName = "pending-listens.jsonl"

    /// Takes every listen waiting, leaving the queue empty for the service to go on writing.
    ///
    /// The file is moved aside before it's read, so a line the service writes meanwhile goes
    /// into a new queue rather than being lost. Lines that don't parse are skipped.
    public static func drain(at url: URL) -> [Listen] {
        let manager = FileManager.default
        guard manager.fileExists(atPath: url.path) else { return [] }
        let taken = url.deletingLastPathComponent()
            .appendingPathComponent("taking-\(UUID().uuidString)-\(url.lastPathComponent)")
        do {
            try manager.moveItem(at: url, to: taken)
        } catch {
            return []
        }
        defer { try? manager.removeItem(at: taken) }
        guard let data = try? Data(contentsOf: taken), let text = String(data: data, encoding: .utf8) else { return [] }
        return parse(text)
    }

    public static func parse(_ text: String) -> [Listen] {
        let decoder = JSONDecoder()
        return text
            .split(whereSeparator: \.isNewline)
            .compactMap { try? decoder.decode(Listen.self, from: Data($0.utf8)) }
    }

    /// The listens that count as plays.
    public static func plays(from listens: [Listen]) -> [Play] {
        listens.filter(\.counts).map(\.play)
    }
}

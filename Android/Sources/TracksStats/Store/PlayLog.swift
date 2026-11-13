import Foundation

/// Every play Tracks has kept on this phone, oldest first, saved as one JSON file.
///
/// A few years of listening is tens of thousands of rows and a few megabytes, which reads in
/// well under a second, so there's no database. Writes replace the file atomically, so a crash
/// mid-save leaves the last good copy.
public struct PlayLog: Sendable {
    public private(set) var plays: [Play]

    public init(plays: [Play] = []) {
        self.plays = plays.sorted { $0.playedAt < $1.playedAt }
    }

    // MARK: - Changes

    /// Adds plays not already kept, and returns how many were new.
    ///
    /// A play is the same as one already kept when it's the same song within
    /// ``duplicateWindow`` of it: a player that reports a song twice, or a Last.fm scrobble of
    /// a song Tracks heard itself, is one play.
    @discardableResult
    public mutating func add(_ new: [Play]) -> Int {
        var byKey: [String: [Date]] = [:]
        for play in plays { byKey[play.songKey, default: []].append(play.playedAt) }
        var added: [Play] = []
        for play in new.sorted(by: { $0.playedAt < $1.playedAt }) {
            let times = byKey[play.songKey] ?? []
            if times.contains(where: { abs($0.timeIntervalSince(play.playedAt)) < Self.duplicateWindow }) { continue }
            byKey[play.songKey, default: []].append(play.playedAt)
            added.append(play)
        }
        guard !added.isEmpty else { return 0 }
        plays = (plays + added).sorted { $0.playedAt < $1.playedAt }
        return added.count
    }

    /// Removes one play, as when a song was kept by mistake.
    public mutating func remove(id: String) {
        plays.removeAll { $0.id == id }
    }

    /// Removes every play of a kind, such as the sample data.
    public mutating func removeAll(where shouldRemove: (Play) -> Bool) {
        plays.removeAll(where: shouldRemove)
    }

    /// Closer than this, the same song twice is one play. Shorter than any real song, so a
    /// song played twice in a row is still two.
    public static let duplicateWindow: TimeInterval = 90

    // MARK: - Statistics

    /// The history ``StatsCalculator`` works from. Sorts and estimates listening once, so build
    /// it when the log changes and share it.
    public func history(songMetadata: [String: SongMetadata] = [:]) -> ListeningHistory {
        ListeningHistory(plays.map(\.stat), songMetadata: songMetadata)
    }

    // MARK: - File

    public static func load(from url: URL) throws -> PlayLog {
        guard FileManager.default.fileExists(atPath: url.path) else { return PlayLog() }
        let data = try Data(contentsOf: url)
        let file = try Self.decoder.decode(File.self, from: data)
        return PlayLog(plays: file.plays)
    }

    public func save(to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try Self.encoder.encode(File(version: File.currentVersion, plays: plays))
        try data.write(to: url, options: .atomic)
    }

    /// Versioned so a later build can read an earlier file.
    private struct File: Codable {
        static let currentVersion = 1
        var version: Int
        var plays: [Play]
    }

    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        return encoder
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return decoder
    }
}

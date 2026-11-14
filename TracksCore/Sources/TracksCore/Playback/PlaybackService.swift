import Foundation

/// Plays songs from the history in the Music app, for Play Songs In set to Apple Music.
public protocol PlaybackService: Sendable {
    /// Queues the given catalog songs and starts playing.
    func play(songIDs: [String]) async throws
    /// Queues songs known by name as well as ID. A player that finds songs by name (Music.app
    /// on the Mac) uses the names; the default goes by ID.
    func play(_ items: [PlaybackItem]) async throws
    func pause() async
    func skipToNext() async throws
}

extension PlaybackService {
    public func play(_ items: [PlaybackItem]) async throws {
        try await play(songIDs: items.map(\.songID))
    }
}

/// A song to play: its ID, and the title and artist the player itself reported, which is
/// what Music.app on the Mac knows the track by.
public struct PlaybackItem: Sendable, Equatable {
    public let songID: String
    public let title: String
    public let artistName: String

    public init(songID: String, title: String, artistName: String) {
        self.songID = songID
        self.title = title
        self.artistName = artistName
    }
}

public enum PlaybackError: Error, Sendable, Equatable {
    case nothingToPlay
    case notSubscribed
    /// With what happened in words for the person, as ``PlaybackFailure/words`` puts it.
    /// The error behind it goes to the log, not here.
    case failed(String)
    /// The player couldn't start the song, so it opened it in Music for the person to play.
    /// Not a failure to report: it did what the person asked as far as it could.
    case openedInMusic
}

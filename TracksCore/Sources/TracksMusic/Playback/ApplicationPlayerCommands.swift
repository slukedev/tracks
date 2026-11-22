import Foundation
import MusicKit
import TracksCore

/// The application player's async commands.
///
/// On the main actor, as every touch of the player is: MusicKit's player wraps MediaPlayer's,
/// which isn't safe to use from more than one thread. The commands suspend while the player
/// works, so the main thread stays free.
///
/// Starting and readying take as long as the player takes, and the caller decides how long to
/// wait (see ``TimeLimit/run(until:of:)``): given up on, they still finish inside MusicKit, and
/// can start the music after all. The others are given ``limit`` and throw
/// ``TimeLimit/Exceeded`` past it: Apple's player service can stop answering for minutes.
@MainActor
public enum ApplicationPlayerCommands {
    /// Long enough for a song to start streaming over a slow cellular link.
    public static let limit: Duration = .seconds(15)

    public static func play() async throws {
        try await ApplicationMusicPlayer.shared.play()
    }

    /// Readies the queue without playing any of it, so where it starts can be checked first.
    public static func prepare() async throws {
        try await ApplicationMusicPlayer.shared.prepareToPlay()
    }

    /// ``play()``, given ``limit``.
    public static func playWithinLimit() async throws {
        try await TimeLimit.run(within: limit) { @MainActor in try await ApplicationMusicPlayer.shared.play() }
    }

    public static func skipToNext() async throws {
        try await TimeLimit.run(within: limit) { @MainActor in try await ApplicationMusicPlayer.shared.skipToNextEntry() }
    }

    public static func skipToPrevious() async throws {
        try await TimeLimit.run(within: limit) { @MainActor in try await ApplicationMusicPlayer.shared.skipToPreviousEntry() }
    }

    /// Adds after the current song, or at the end of the queue.
    public static func insert<Item: PlayableMusicItem & Sendable>(_ items: [Item], next: Bool) async throws {
        try await TimeLimit.run(within: limit) { @MainActor in
            try await ApplicationMusicPlayer.shared.queue.insert(items, position: next ? .afterCurrentEntry : .tail)
        }
    }
}

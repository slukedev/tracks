import Testing
import Foundation
@testable import TracksCore

/// Handing songs to the Music app, for Play Songs In set to Apple Music.
@MainActor
@Suite("Playback controller", .timeLimit(.minutes(1)))
struct PlaybackControllerTests {
    let service = FakePlaybackService()

    /// The Mac opens a song that isn't in the library in Music, which is the right outcome
    /// for a click, not an error to show.
    @Test("a song opened in Music instead is neither an error nor playing")
    func openedInMusicIsNotAnError() async {
        service.playResult = .failure(PlaybackError.openedInMusic)
        let controller = PlaybackController(service: service)

        await controller.play(PlaybackItem(songID: "1", title: "T1", artistName: "A"))

        #expect(controller.lastError == nil)
        #expect(!controller.isPlaying)
    }

    @Test("a slow earlier request failing late doesn't overwrite a newer one that played")
    func latestRequestWins() async throws {
        service.slowSongs = ["1"]
        let controller = PlaybackController(service: service)

        let slow = Task { await controller.play(PlaybackItem(songID: "1", title: "T1", artistName: "A")) }
        while service.played.isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        await controller.play(PlaybackItem(songID: "2", title: "T2", artistName: "A"))
        service.playResult = .failure(PlaybackError.nothingToPlay)
        await slow.value

        #expect(controller.isPlaying)
        #expect(controller.lastError == nil)
        #expect(service.played == [["1"], ["2"]])
    }

    @Test("songs the catalog never identified are left out, and alone they say so")
    func unidentifiedSongs() async {
        let controller = PlaybackController(service: service)

        await controller.play([
            PlaybackItem(songID: "", title: "Unknown", artistName: "A"),
            PlaybackItem(songID: "2", title: "T2", artistName: "A"),
        ])
        #expect(service.played == [["2"]])
        #expect(controller.queuedCount == 1)

        await controller.play(PlaybackItem(songID: "", title: "Unknown", artistName: "A"))
        #expect(controller.lastError == "That song hasn’t been identified yet.")
        #expect(service.played == [["2"]])
    }
}

/// Records what it was asked to play.
final class FakePlaybackService: PlaybackService, @unchecked Sendable {
    private let lock = NSLock()
    private var _played: [[String]] = []
    private var _playResult: Result<Void, any Error> = .success(())
    /// Songs the player takes a while to start, as Apple Music does on a poor connection.
    var slowSongs: Set<String> = []

    var played: [[String]] { lock.withLock { _played } }
    var playResult: Result<Void, any Error> {
        get { lock.withLock { _playResult } }
        set { lock.withLock { _playResult = newValue } }
    }

    func play(songIDs: [String]) async throws {
        lock.withLock { _played.append(songIDs) }
        if !slowSongs.isDisjoint(with: songIDs) { try? await Task.sleep(for: .milliseconds(200)) }
        try playResult.get()
    }

    func pause() async {}
    func skipToNext() async throws {}
}

import Foundation
import Observation

/// Hands songs from the history to the Music app, for Play Songs In set to Apple Music.
///
/// Tracks doesn't see those plays, so they aren't kept.
@MainActor
@Observable
public final class PlaybackController {
    public private(set) var isPlaying = false
    public private(set) var lastError: String?
    /// Songs handed to the player in this run.
    public private(set) var queuedCount = 0

    private let service: any PlaybackService

    /// The service is injected so this can live in the MusicKit-free core and be tested.
    public init(service: any PlaybackService) {
        self.service = service
    }

    /// Plays one capture.
    public func play(_ capture: Capture) async {
        await play([PlaybackItem(songID: capture.songID, title: capture.title, artistName: capture.artistName)])
    }

    /// Plays a single song.
    public func play(_ item: PlaybackItem) async {
        await play([item])
    }

    /// Plays songs in order. Songs the catalog never identified are dropped rather than
    /// failing the lot.
    public func play(_ items: [PlaybackItem]) async {
        let playable = items.filter { !$0.songID.isEmpty }
        guard !playable.isEmpty else {
            lastError = items.count == 1
                ? String(localized: "That song hasn’t been identified yet.")
                : String(localized: "None of those songs have been identified yet.")
            return
        }
        lastError = nil
        queuedCount = playable.count

        latestRequest += 1
        let request = latestRequest
        do {
            try await service.play(playable)
            guard request == latestRequest else { return }
            isPlaying = true
        } catch PlaybackError.openedInMusic {
            guard request == latestRequest else { return }
            isPlaying = false
        } catch {
            guard request == latestRequest else { return }
            isPlaying = false
            lastError = Self.describe(error)
        }
    }

    /// Counts play requests, so one that finishes after a newer one leaves it alone.
    private var latestRequest = 0

    public func pause() async {
        // A request still starting is no longer the one that counts.
        latestRequest += 1
        await service.pause()
        isPlaying = false
    }

    /// What went wrong, in words. The error itself only goes to the log.
    static func describe(_ error: any Error) -> String {
        guard let playbackError = error as? PlaybackError else {
            Log.playback.error("Playback failed: \(String(describing: error), privacy: .public)")
            return PlaybackFailure(error).words
        }
        return switch playbackError {
        case .nothingToPlay: String(localized: "Those songs are no longer available in the catalog.")
        case .notSubscribed: PlaybackFailure.noSubscription.words
        // The player has already said it plainly.
        case .failed(let words): words
        case .openedInMusic: String(localized: "Opened in Music.")
        }
    }
}

import Foundation
import MusicKit

/// Reads of Tracks’ Apple Music player that ask Apple's player service and wait for its
/// answer, such as the playback position.
///
/// On the main thread, like every other touch of the player: MediaPlayer's player behind it
/// isn't safe to use from another thread, and reads from one fit crashes seen inside
/// MediaPlayer's own state updates. So the position is asked for only when the answer is
/// worth the wait: not while paused, when it doesn't move, and not in the second after a song
/// changes, when the service takes longest to answer. Then the time is worked out from the
/// last answer. Every read also waits for a scene change to settle (see ``PlayerQuiet``).
@MainActor
public enum PlayerReads {
    private static let songChangeQuiet: Duration = .seconds(1)

    private struct Known {
        var entryID: String?
        var changedAt: ContinuousClock.Instant
        var time: TimeInterval
        var timeAt: ContinuousClock.Instant
        var wasPlaying: Bool
    }

    private static var known: Known?

    /// The position in the entry with `entryID`, asked of the player or worked out.
    public static func playbackTime(of entryID: String?, isPlaying: Bool) async -> TimeInterval {
        let now = ContinuousClock.now
        if known?.entryID != entryID {
            known = Known(entryID: entryID, changedAt: now, time: 0, timeAt: now, wasPlaying: isPlaying)
        }
        guard var known else { return 0 }
        let worked = known.wasPlaying ? known.time + (now - known.timeAt).seconds : known.time
        guard isPlaying, now - known.changedAt >= songChangeQuiet else {
            known.time = worked
            known.timeAt = now
            known.wasPlaying = isPlaying
            self.known = known
            return worked
        }
        return await readNow(for: entryID)
    }

    /// Asks the player for the position now, once no scene is changing.
    public static func playbackTime() async -> TimeInterval {
        await readNow(for: nil)
    }

    private static func readNow(for entryID: String?) async -> TimeInterval {
        await PlayerQuiet.settled()
        let read = ApplicationMusicPlayer.shared.playbackTime
        let time = read.isFinite ? max(0, read) : 0
        if let entryID, known?.entryID == entryID {
            known?.time = time
            known?.timeAt = .now
            known?.wasPlaying = true
        }
        return time
    }
}

private extension Duration {
    var seconds: TimeInterval {
        Double(components.seconds) + Double(components.attoseconds) / 1e18
    }
}

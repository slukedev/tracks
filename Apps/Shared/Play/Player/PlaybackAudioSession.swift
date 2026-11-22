#if os(iOS)
import AVFoundation
import os

/// The app's audio session, for music: long-form playback that stops other audio rather than
/// mixing with it, made active just before something plays.
///
/// Activating can fail while no window of the app's is connected, as when Tracks is launched
/// by the car with the phone locked: iOS answers that it can't interrupt other audio
/// (`AVAudioSession.ErrorCode.cannotInterruptOthers`). That can clear a moment later, so it's
/// tried once more after a short wait, and logged either way.
///
/// The session stays active when Apple Music's player takes over from your own music's: it's
/// the same app's session, and letting it go would let another app's audio back in for the
/// moment before Apple Music starts, which could then refuse to interrupt it.
nonisolated enum PlaybackAudioSession {
    private static let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Tracks", category: "Audio Session")
    private static let retryDelay: Duration = .milliseconds(500)

    /// Makes the session active now. False if it couldn't be.
    @discardableResult
    static func activateNow() -> Bool {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, policy: .longFormAudio, options: [])
            try session.setActive(true)
            return true
        } catch {
            log.error("Couldn't activate the audio session: \(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// Makes the session active, trying once more after a short wait if it can't be at first.
    /// Off the main thread: activating waits on the audio server.
    @concurrent
    static func activate() async {
        guard !activateNow() else { return }
        try? await Task.sleep(for: retryDelay)
        guard !Task.isCancelled, !activateNow() else { return }
        log.error("The audio session is still inactive after trying again.")
    }

    /// ``activateNow()``, and if that fails, once more in the background a moment later.
    static func activateRetryingLater() {
        guard !activateNow() else { return }
        Task {
            try? await Task.sleep(for: retryDelay)
            activateNow()
        }
    }
}
#endif

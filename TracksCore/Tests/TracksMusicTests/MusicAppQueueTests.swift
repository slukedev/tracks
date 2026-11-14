import Foundation
import Testing
import TracksCore
@testable import TracksMusic

/// The parts of the Mac's Music.app queue that don't need Music.app.
@Suite("Music.app queue")
struct MusicAppQueueTests {

    #if os(macOS)
    /// AppleScript's answer ("Music got an error: -1708") means nothing to the person.
    @Test("Music refusing a song is said without the script's error")
    func scriptFailureInWords() {
        let words = MusicAppPlaybackService.describe(.scriptFailed("Music got an error: -1708"), playlist: "Heard on Radio")
        #expect(words == "Music couldn’t start the song. Open Music and try again.")
        #expect(MusicAppPlaybackService.describe(.musicNotRunning, playlist: "Heard on Radio")
            == "Music isn’t open. Open Music and try again.")
    }
    #endif

    /// Two queues started together each started a sequencer, and the first was never
    /// cancelled, so two loops drove Music.app at once.
    @Test("starting a sequencer cancels the one before it")
    func replacingCancels() async {
        let slot = TaskSlot()
        let first = Task<Void, Never> { try? await Task.sleep(for: .seconds(30)) }
        slot.replace(with: first)
        let second = Task<Void, Never> { try? await Task.sleep(for: .seconds(30)) }
        slot.replace(with: second)

        #expect(first.isCancelled)
        #expect(!second.isCancelled)
        slot.cancel()
        #expect(second.isCancelled)
    }
}

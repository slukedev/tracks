import Foundation
import SwiftData
import Testing
@testable import TracksCore

/// Repair Artwork clears covers through the writer, like every other change to a play, so
/// what the writer recorded about a row in the meantime (a scrobble sent, a playlist write
/// done) survives the clear.
@Suite("History writer clearing artwork")
struct HistoryWriterArtworkTests {
    @Test("clearing covers keeps what the writer recorded since the main context read the rows")
    @MainActor
    func clearingKeepsNewerWrites() async throws {
        let store = try TracksStore(inMemory: true)
        let heard = Date(timeIntervalSince1970: 1_790_000_000)
        let capture = Capture(
            songID: "1", title: "Glow", artistName: "Umbra",
            artworkURL: "https://example.test/gone.jpg", kind: .radio, capturedAt: heard
        )
        store.context.insert(capture)
        try store.context.save()
        let id = capture.persistentModelID
        let sent = heard.addingTimeInterval(60)
        await store.writer.updateCapture(id) {
            $0.scrobbledAt = sent
            $0.needsPlaylistWrite = false
        }

        let cleared = try await store.clearArtworkInBackground(urls: ["https://example.test/gone.jpg"])

        #expect(cleared == 1)
        let row = try await store.writer.read { context in
            let row = try #require(TracksStore.existingCaptures([id], in: context)[id])
            return (artwork: row.artworkURL, scrobbled: row.scrobbledAt, queued: row.needsPlaylistWrite)
        }
        #expect(row.artwork == nil)
        #expect(row.scrobbled == sent)
        #expect(!row.queued)
    }
}

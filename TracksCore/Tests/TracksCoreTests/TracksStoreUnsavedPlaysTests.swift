import Foundation
import SwiftData
import Testing
@testable import TracksCore

/// When the library on disk can't be opened, a run keeps its plays in memory. They must
/// outlive that run: kept aside in a file, carried into the next run that falls back, and
/// saved into the library once, by the first run that can open it.
@Suite("Plays kept aside while the library is unavailable")
struct TracksStoreUnsavedPlaysTests {
    private let folder: URL
    private let unsavedPlays: UnsavedPlays
    private let heard = Date(timeIntervalSince1970: 1_790_000_000)

    init() throws {
        folder = FileManager.default.temporaryDirectory.appending(path: "UnsavedPlays-\(UUID().uuidString)", directoryHint: .isDirectory)
        unsavedPlays = UnsavedPlays(file: folder.appending(path: "Unsaved Plays.json"))
    }

    /// A fallback run's store, with plays recorded and one of them already sent to Last.fm.
    private func fallbackRun(plays titles: [String]) throws -> ModelContainer {
        let container = try TracksStore.inMemoryContainer()
        let context = ModelContext(container)
        for (offset, title) in titles.enumerated() {
            let capture = Capture(
                songID: "\(offset)", title: title, artistName: "Umbra",
                kind: .radio, capturedAt: heard.addingTimeInterval(Double(offset) * 200), deviceID: "phone"
            )
            if offset == 0 { capture.scrobbledAt = capture.capturedAt }
            context.insert(capture)
        }
        try context.save()
        return container
    }

    private func plays(in container: ModelContainer) throws -> [Capture] {
        try ModelContext(container).fetch(FetchDescriptor<Capture>(sortBy: [SortDescriptor(\.capturedAt)]))
    }

    @Test("a fallback run's plays reach the library once, with what was done with them")
    func keptPlaysAreSavedOnce() async throws {
        defer { try? FileManager.default.removeItem(at: folder) }
        let run = try fallbackRun(plays: ["Glow", "Tide"])
        await UnsavedPlays.Keeper(unsavedPlays, container: run).keep()

        let library = try TracksStore.inMemoryContainer()
        let writer = HistoryWriter(container: library)
        let saved = await writer.saveUnsavedPlays(from: unsavedPlays)

        #expect(saved == 2)
        let rows = try plays(in: library)
        #expect(rows.map(\.title) == ["Glow", "Tide"])
        #expect(rows.map { $0.scrobbledAt != nil } == [true, false])
        #expect(rows.allSatisfy { $0.capturedByDeviceID == "phone" && $0.needsPlaylistWrite })
        #expect(!FileManager.default.fileExists(atPath: unsavedPlays.file.path(percentEncoded: false)))
    }

    @Test("saving again after a run stopped before letting the file go adds nothing")
    func savingTwiceIsIdempotent() async throws {
        defer { try? FileManager.default.removeItem(at: folder) }
        let run = try fallbackRun(plays: ["Glow", "Tide"])
        let keeper = UnsavedPlays.Keeper(unsavedPlays, container: run)
        await keeper.keep()
        let library = try TracksStore.inMemoryContainer()
        let writer = HistoryWriter(container: library)
        _ = await writer.saveUnsavedPlays(from: unsavedPlays)

        await keeper.keep()
        let again = await writer.saveUnsavedPlays(from: unsavedPlays)

        #expect(again == 0)
        #expect(try plays(in: library).count == 2)
    }

    @Test("a second run that falls back starts from the first one's plays and keeps both")
    func secondFallbackRunCarriesOn() async throws {
        defer { try? FileManager.default.removeItem(at: folder) }
        await UnsavedPlays.Keeper(unsavedPlays, container: try fallbackRun(plays: ["Glow"])).keep()

        let second = try TracksStore.inMemoryContainer()
        unsavedPlays.restore(into: second)
        let context = ModelContext(second)
        context.insert(Capture(songID: "9", title: "Later", artistName: "Umbra", capturedAt: heard.addingTimeInterval(9_000)))
        try context.save()
        await UnsavedPlays.Keeper(unsavedPlays, container: second).keep()

        #expect(unsavedPlays.read().map(\.title).sorted() == ["Glow", "Later"])
    }

    @Test("a file that can't be read is moved aside rather than overwritten")
    func unreadableFileIsKept() throws {
        defer { try? FileManager.default.removeItem(at: folder) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("not plays".utf8).write(to: unsavedPlays.file)

        #expect(unsavedPlays.read().isEmpty)
        let aside = folder.appending(path: "Unsaved Plays.unreadable.json")
        #expect(try Data(contentsOf: aside) == Data("not plays".utf8))
    }

    @Test("only the newest plays are kept past the limit")
    func limitKeepsNewest() throws {
        defer { try? FileManager.default.removeItem(at: folder) }
        let container = try fallbackRun(plays: (0...UnsavedPlays.limit).map { "Song \($0)" })
        try unsavedPlays.write(try plays(in: container).map(UnsavedPlays.Play.init))

        let kept = unsavedPlays.read()
        #expect(kept.count == UnsavedPlays.limit)
        #expect(!kept.contains { $0.title == "Song 0" })
    }

    @Test("a store that opened is not keeping plays aside")
    @MainActor
    func openStoreIsAvailable() async throws {
        let store = try TracksStore(inMemory: true)
        #expect(!store.isLibraryUnavailable)
        #expect(await !store.libraryOpensNow())
    }
}

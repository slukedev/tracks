import Testing
import Foundation
import SwiftData
@testable import TracksCore

@Suite("Song identity")
struct SongIdentityTests {
    @Test("case and accents do not defeat the match")
    func keyIgnoresCaseAndAccents() {
        #expect(
            SongIdentity.key(title: "Café", artistName: "Sevens")
                == SongIdentity.key(title: "  cafe ", artistName: "SEVENS")
        )
    }
}

/// Rows imported from Recently Played by earlier versions are still in people's histories.
@MainActor
@Suite("Imports and the repeat window")
struct ImportWindowTests {

    /// Found 45 minutes later, as in the first real import: far outside the ordinary
    /// 10-minute window, well inside the day an import gets.
    @Test("an import found within the import window is folded into the witnessed row")
    func foldsWithinTheImportWindow() throws {
        let store = try TracksStore(inMemory: true)
        let heard = Date(timeIntervalSince1970: 1_700_000_000)
        try insertWitnessedAndImported(into: store, heard: heard, foundAfter: 45 * 60)

        let policy = DedupePolicy()
        #expect(45 * 60 > policy.window)
        #expect(45 * 60 < policy.importWindow)
        #expect(try store.mergeDuplicateCaptures(policy: policy) == 1)

        let remaining = try store.context.fetch(TracksStore.allCaptures())
        try #require(remaining.count == 1)
        #expect(remaining[0].kind == .radio)
        #expect(remaining[0].capturedAt == heard)
    }

    /// The same 45-minute pair against a shorter import window, so the merge is shown to
    /// follow the policy's `importWindow` rather than treating every import as the same play.
    @Test("whether an import folds in is decided by the import window", arguments: [
        (importWindow: 60.0 * 60, merges: true),
        (importWindow: 30.0 * 60, merges: false),
    ])
    func importWindowDecides(importWindow: TimeInterval, merges: Bool) throws {
        let store = try TracksStore(inMemory: true)
        let heard = Date(timeIntervalSince1970: 1_700_000_000)
        try insertWitnessedAndImported(into: store, heard: heard, foundAfter: 45 * 60)

        let policy = DedupePolicy(window: 10 * 60, importWindow: importWindow)
        #expect(try store.mergeDuplicateCaptures(policy: policy) == (merges ? 1 : 0))
        #expect(try store.context.fetch(TracksStore.allCaptures()).count == (merges ? 1 : 2))
    }

    @Test("two witnessed plays hours apart are still two plays")
    func keepsWitnessedRepeats() throws {
        let store = try TracksStore(inMemory: true)
        let heard = Date(timeIntervalSince1970: 1_700_000_000)
        store.context.insert(Capture(
            songID: "1", title: "lvl 6", artistName: "Léa Sen", capturedAt: heard, deviceID: "mac"
        ))
        store.context.insert(Capture(
            songID: "1", title: "lvl 6", artistName: "Léa Sen",
            capturedAt: heard.addingTimeInterval(7200), deviceID: "mac"
        ))
        try store.context.save()

        #expect(try store.mergeDuplicateCaptures(policy: DedupePolicy()) == 0)
    }

    /// A play the Mac witnessed, and the same song imported `foundAfter` seconds later under
    /// a library id.
    private func insertWitnessedAndImported(
        into store: TracksStore,
        heard: Date,
        foundAfter: TimeInterval
    ) throws {
        store.context.insert(Capture(
            songID: "1", title: "lvl 6", artistName: "Léa Sen", capturedAt: heard, deviceID: "mac"
        ))
        store.context.insert(Capture(
            songID: "i.abc", songKey: "i.abc", title: "lvl 6", artistName: "Léa Sen",
            kind: .imported, capturedAt: heard.addingTimeInterval(foundAfter), deviceID: "phone"
        ))
        try store.context.save()
    }
}

/// Removing a song the user doesn't want kept.
@MainActor
@Suite("Forgetting a song")
struct ForgetSongTests {

    /// Removed when the test finishes.
    private let scratch = ScratchDefaults()

    @Test("every row for that song goes, whatever its source")
    func removesEveryRow() throws {
        let store = try TracksStore(inMemory: true)
        let config = scratch.settings
        store.context.insert(Capture(songID: "1", title: "LION", artistName: "Elevation", deviceID: "this-device"))
        store.context.insert(Capture(
            songID: "i.abc", songKey: "i.abc", title: "LION", artistName: "Elevation",
            kind: .imported, deviceID: "this-device"
        ))
        store.context.insert(Capture(songID: "2", title: "Keep Me", artistName: "Elevation", deviceID: "this-device"))
        try store.context.save()

        #expect(try store.forgetSong(title: "LION", artistName: "Elevation", settings: config) == 2)
        let left = try store.context.fetch(TracksStore.allCaptures())
        #expect(left.map(\.title) == ["Keep Me"])
    }

    @Test("Last.fm plays from before the song was forgotten stay out, later ones come in")
    func scrobblesAfterForgetting() throws {
        let store = try TracksStore(inMemory: true)
        let config = scratch.settings
        let forgotten = Date(timeIntervalSince1970: 1_700_000_000)
        config.noteForgotten(SongIdentity.key(title: "LION", artistName: "Elevation"), at: forgotten)

        let written = try store.importScrobbles([
            ScrobbledTrack(title: "LION", artistName: "Elevation", playedAt: forgotten.addingTimeInterval(-60)),
            ScrobbledTrack(title: "LION", artistName: "Elevation", playedAt: forgotten.addingTimeInterval(2 * 24 * 60 * 60)),
        ], settings: config)

        #expect(written == 1)
        #expect(try store.context.fetch(TracksStore.allCaptures()).map(\.capturedAt)
            == [forgotten.addingTimeInterval(2 * 24 * 60 * 60)])
    }

    /// Songs forgotten before the date was kept have nothing to measure from.
    @Test("a song forgotten without a date stays forgotten")
    func undatedStaysForgotten() {
        #expect(CaptureSettings.isForgotten("k", playedAt: .distantFuture, forgotten: ["k"], dates: [:]))
        #expect(!CaptureSettings.isForgotten("other", playedAt: .distantPast, forgotten: ["k"], dates: [:]))
    }

    @Test("forgetting a song that was never captured still stops it arriving")
    func forgetsUnseenSongs() throws {
        let store = try TracksStore(inMemory: true)
        let config = scratch.settings
        #expect(try store.forgetSong(title: "LION", artistName: "Elevation", settings: config) == 0)
        #expect(config.hasForgotten(title: "LION", artistName: "Elevation"))
    }
}

/// Apple Music's two kinds of identifier.
@Suite("Catalog and library identifiers")
struct MusicItemIdentityTests {

    /// A catalog request with only library ids got a 400: "No id(s) supplied on the request".
    @Test("a library id is not a catalog id")
    func tellsThemApart() {
        #expect(MusicItemIdentity.isCatalogID("1814555105"))
        #expect(!MusicItemIdentity.isCatalogID("i.aJGY3G9fEGApOQP"))
        #expect(MusicItemIdentity.isLibraryID("i.aJGY3G9fEGApOQP"))
        #expect(!MusicItemIdentity.isLibraryID("1814555105"))
    }

    @Test("an empty id is neither")
    func rejectsEmpty() {
        #expect(!MusicItemIdentity.isCatalogID(""))
        #expect(!MusicItemIdentity.isLibraryID(""))
    }
}

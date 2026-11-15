import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// A server's library and change log, answering sync as api.md describes: snapshots page
/// through artists then songs, deltas give each changed id once, with its latest state.
private final class FakeLibrary: TracksideSyncSource {
    private struct State {
        var songs: [String: TracksideSong] = [:]
        var artists: [String: TracksideArtist] = [:]
        var seq = 0
        /// The seq each id last changed at, and whether it's gone.
        var changed: [String: (seq: Int, kind: String)] = [:]
        /// Deltas from before this seq can't be served.
        var oldestServed = 0
        var failAfterPages: Int?
        var pagesServed = 0
    }

    private let state = Mutex(State())

    func put(_ song: TracksideSong) {
        state.withLock { $0.seq += 1; $0.songs[song.id] = song; $0.changed["song:" + song.id] = ($0.seq, "song") }
    }

    func put(_ artist: TracksideArtist) {
        state.withLock { $0.seq += 1; $0.artists[artist.id] = artist; $0.changed["artist:" + artist.id] = ($0.seq, "artist") }
    }

    func deleteSong(_ id: String) {
        state.withLock { $0.seq += 1; $0.songs[id] = nil; $0.changed["song:" + id] = ($0.seq, "song") }
    }

    func deleteArtist(_ id: String) {
        state.withLock { $0.seq += 1; $0.artists[id] = nil; $0.changed["artist:" + id] = ($0.seq, "artist") }
    }

    /// Prunes the change log, so older cursors get `sync_reset`.
    func forget() {
        state.withLock { $0.oldestServed = $0.seq }
    }

    func failing(after pages: Int?) {
        state.withLock { $0.failAfterPages = pages; $0.pagesServed = 0 }
    }

    func sync(cursor: String?, limit: Int) async throws -> TracksideSyncPage {
        try state.withLock { state in
            if let failAfter = state.failAfterPages, state.pagesServed >= failAfter { throw URLError(.networkConnectionLost) }
            state.pagesServed += 1
            let parts = (cursor ?? "snap:artists:0:\(state.seq)").split(separator: ":").map(String.init)
            if parts[0] == "delta" {
                let since = Int(parts[1])!
                guard since >= state.oldestServed else { throw TracksideError.syncReset }
                let due = state.changed.filter { $0.value.seq > since }.sorted { $0.value.seq < $1.value.seq }
                let page = due.prefix(limit)
                var upserts = TracksideSyncPage.Upserts(songs: [], artists: [])
                var deletes = TracksideSyncPage.Deletes(songs: [], artists: [])
                for (key, change) in page {
                    let id = String(key.split(separator: ":")[1])
                    if change.kind == "song" {
                        if let song = state.songs[id] { upserts.songs?.append(song) } else { deletes.songs?.append(id) }
                    } else if let artist = state.artists[id] {
                        upserts.artists?.append(artist)
                    } else {
                        deletes.artists?.append(id)
                    }
                }
                let reached = page.last?.value.seq ?? since
                var result = TracksideSyncPage(mode: .delta, nextCursor: "delta:\(reached)")
                result.upserts = upserts
                result.deletes = deletes
                result.hasMore = due.count > page.count
                return result
            }
            let kind = parts[1], offset = Int(parts[2])!, seq = Int(parts[3])!
            var result = TracksideSyncPage(mode: .snapshot, nextCursor: nil)
            result.snapshot = .init(seq: Int64(seq), kind: kind, next: nil)
            let total: Int
            if kind == "artists" {
                let all = state.artists.values.sorted { $0.id < $1.id }
                result.artists = Array(all.dropFirst(offset).prefix(limit))
                total = all.count
            } else {
                let all = state.songs.values.sorted { $0.id < $1.id }
                result.songs = Array(all.dropFirst(offset).prefix(limit))
                total = all.count
            }
            if offset + limit < total {
                result.nextCursor = "snap:\(kind):\(offset + limit):\(seq)"
            } else if kind == "artists" {
                result.nextCursor = "snap:songs:0:\(seq)"
            } else {
                result.complete = true
                result.nextCursor = "delta:\(seq)"
            }
            return result
        }
    }
}

@Suite("Syncing with Trackside")
struct TracksideSyncTests {
    static func song(_ number: Int, title: String? = nil, artist: String = "Mara Solis") -> TracksideSong {
        var song = TracksideSong(id: "s\(number)", title: title ?? "Song \(number)", artist: artist, album: "Coastlines", albumID: "a1", durationMS: 200_000 + number, artworkID: "c\(number)", addedAt: Date(timeIntervalSince1970: 1_700_000_000 + Double(number)))
        song.artistID = "r1"
        return song
    }

    private func library(songs: Int) -> FakeLibrary {
        let library = FakeLibrary()
        library.put(TracksideArtist(id: "r1", name: "Mara Solis", artworkID: "c-r1"))
        library.put(TracksideArtist(id: "r2", name: "Nova Harbor"))
        for number in 1...songs { library.put(Self.song(number)) }
        return library
    }

    @Test("a first sync takes a snapshot across pages, songs and artists both")
    func snapshot() async throws {
        let library = library(songs: 7)
        let mirror = try await TracksideSync.run(TracksideMirror(serverID: "home"), from: library, pageSize: 3)
        #expect(mirror.tracks.map(\.title) == (1...7).map { "Song \($0)" })
        #expect(mirror.tracks.first?.origin == .server(serverID: "home", songID: "s1"))
        #expect(mirror.artists["mara solis"] == ServerArtistRef(id: "r1", cover: "c-r1"))
        #expect(mirror.artists["nova harbor"] == ServerArtistRef(id: "r2", cover: nil))
        #expect(mirror.cursor != nil)
    }

    @Test("changes applied on top of a snapshot come to the same as a fresh snapshot")
    func deltasMatchSnapshot() async throws {
        let library = library(songs: 9)
        var mirror = try await TracksideSync.run(TracksideMirror(serverID: "home"), from: library, pageSize: 4)

        library.put(Self.song(3, title: "Song Three (Remastered)"))
        library.deleteSong("s5")
        library.put(Self.song(10))
        library.put(Self.song(11, artist: "Nova Harbor"))
        library.put(TracksideArtist(id: "r2", name: "Nova Harbour", artworkID: "c-r2"))
        library.deleteArtist("r1")
        var missing = Self.song(7)
        missing.missing = true
        library.put(missing)
        mirror = try await TracksideSync.run(mirror, from: library, pageSize: 2)

        let fresh = try await TracksideSync.run(TracksideMirror(serverID: "home"), from: library, pageSize: 4)
        #expect(mirror.tracks == fresh.tracks)
        #expect(mirror.artists == fresh.artists)
        #expect(mirror.cursor == fresh.cursor)
        #expect(mirror.songs["s3"]?.title == "Song Three (Remastered)")
        #expect(mirror.songs["s5"] == nil)
        #expect(mirror.songs["s7"] == nil)
        #expect(mirror.artists["nova harbour"]?.id == "r2")
        #expect(mirror.artists["nova harbor"] == nil)
        #expect(mirror.artists["mara solis"] == nil)
    }

    @Test("with nothing changed, a sync keeps everything as it was")
    func nothingChanged() async throws {
        let library = library(songs: 3)
        let mirror = try await TracksideSync.run(TracksideMirror(serverID: "home"), from: library)
        #expect(try await TracksideSync.run(mirror, from: library) == mirror)
    }

    @Test("a cursor the server can't continue from keeps the old songs until a new snapshot completes")
    func reset() async throws {
        let library = library(songs: 6)
        let before = try await TracksideSync.run(TracksideMirror(serverID: "home"), from: library, pageSize: 2)
        library.put(Self.song(7))
        library.forget()

        // The snapshot after the reset fails partway: the old mirror is still the one to keep.
        library.failing(after: 3)
        await #expect(throws: URLError.self) { try await TracksideSync.run(before, from: library, pageSize: 2) }
        #expect(before.tracks.count == 6)

        library.failing(after: nil)
        let after = try await TracksideSync.run(before, from: library, pageSize: 2)
        #expect(after.tracks.map(\.title) == (1...7).map { "Song \($0)" })
        #expect(after.cursor != before.cursor)
    }

    @Test("a failed delta leaves the cursor where it was, so nothing is skipped")
    func failedDelta() async throws {
        let library = library(songs: 2)
        let mirror = try await TracksideSync.run(TracksideMirror(serverID: "home"), from: library)
        for number in 3...8 { library.put(Self.song(number)) }
        library.failing(after: 1)
        await #expect(throws: URLError.self) { try await TracksideSync.run(mirror, from: library, pageSize: 2) }
        library.failing(after: nil)
        #expect(try await TracksideSync.run(mirror, from: library, pageSize: 2).tracks.count == 8)
    }

    @Test("a mirror read back from its saved songs and artists picks up where it left off")
    func savedAndRead() async throws {
        let library = library(songs: 4)
        let mirror = try await TracksideSync.run(TracksideMirror(serverID: "home"), from: library)
        let folder = try TemporaryFolder()
        let cursorFile = folder.url.appending(path: "server-home-cursor.json")
        try TracksideCursorFile.save(mirror.cursor, to: cursorFile)
        let restored = TracksideMirror(serverID: "home", tracks: mirror.tracks, artists: mirror.artists, cursor: TracksideCursorFile.load(from: cursorFile))
        #expect(restored == mirror)

        library.deleteArtist("r2")
        let updated = try await TracksideSync.run(restored, from: library)
        #expect(updated.artists["nova harbor"] == nil)

        try TracksideCursorFile.save(nil, to: cursorFile)
        #expect(TracksideCursorFile.load(from: cursorFile) == nil)
    }

    @Test("a 410 from the server reads as a reset")
    func resetOverHTTP() async throws {
        let stub = StubTracksideTransport(status: 410, body: #"{"error": {"code": "sync_reset", "message": "Cursor too old"}}"#)
        await #expect(throws: TracksideError.syncReset) { try await TracksideTesting.client(stub).sync(cursor: "ZGVsdGE6") }
        #expect(TracksideTesting.query(try #require(stub.requests.first?.url)) == ["cursor": "ZGVsdGE6", "limit": "2000"])
    }
}

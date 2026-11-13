import Foundation
import Observation
import SkipFuse
import TracksStats

/// A song in the queue: enough to play it, show it, and count it.
struct QueueSong: Identifiable, Hashable, Codable {
    let id: String
    let title: String
    let artist: String
    var album: String?
    /// A ``ServerStore/coverRef(serverID:coverID:)``.
    var coverRef: String?
    var duration: Double?
    let serverID: String

    init(id: String, title: String, artist: String, album: String? = nil, coverRef: String? = nil, duration: Double? = nil, serverID: String) {
        self.id = id
        self.title = title
        self.artist = artist
        self.album = album
        self.coverRef = coverRef
        self.duration = duration
        self.serverID = serverID
    }

    init(_ song: SubsonicSong, serverID: String) {
        self.init(
            id: song.id,
            title: song.title,
            artist: song.artist ?? "",
            album: song.album,
            coverRef: ServerStore.coverRef(serverID: serverID, coverID: song.coverArt ?? song.albumId),
            duration: song.duration.map(Double.init),
            serverID: serverID
        )
    }

    /// A song from the statistics, played again. Its cover is asked for by the song's own id,
    /// which Navidrome and most servers answer with the album's.
    init?(_ song: SongTally, serverID: String?) {
        guard !song.songID.isEmpty, let serverID else { return nil }
        self.init(
            id: song.songID,
            title: song.title,
            artist: song.artistName,
            album: song.albumTitle,
            coverRef: song.artworkURL ?? ServerStore.coverRef(serverID: serverID, coverID: song.songID),
            serverID: serverID
        )
    }
}

/// What's playing, mirrored from the player service, and the transport.
///
/// The music itself plays in `PlaybackService.kt`, which outlives the app's window. This keeps
/// the queue the app sent it and reads back where it has got to, a few times a second while
/// Tracks is on screen.
@MainActor @Observable
final class PlayerModel {
    private(set) var queue: [QueueSong] = []
    private(set) var index = 0
    private(set) var isPlaying = false
    private(set) var isBuffering = false
    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    private(set) var shuffle = false
    /// 0 off, 1 the song, 2 the queue.
    private(set) var repeatMode = 0
    /// Where the queue came from: an album's name, "Shuffle", "Your top songs".
    private(set) var source = ""

    private let servers: ServerStore

    init(servers: ServerStore) {
        self.servers = servers
    }

    var current: QueueSong? { queue.indices.contains(index) ? queue[index] : nil }
    var hasQueue: Bool { !queue.isEmpty }
    var upNext: ArraySlice<QueueSong> { queue.indices.contains(index + 1) ? queue[(index + 1)...] : [] }

    // MARK: - Transport

    func play(_ songs: [QueueSong], startAt start: Int = 0, from source: String, shuffled: Bool = false) {
        guard !songs.isEmpty, let client = servers.client else { return }
        var ordered = songs
        var first = min(max(start, 0), songs.count - 1)
        if shuffled {
            ordered.shuffle()
            first = 0
        }
        queue = ordered
        index = first
        position = 0
        duration = ordered[first].duration ?? 0
        isPlaying = true
        self.source = source
        let entries: [[String: Any]] = ordered.map { song in
            var entry: [String: Any] = [
                "id": song.id,
                "url": client.streamURL(songID: song.id).absoluteString,
                "title": song.title,
                "artist": song.artist,
                "serverID": song.serverID,
            ]
            if let album = song.album { entry["album"] = album }
            if let duration = song.duration { entry["duration"] = duration }
            if let coverRef = song.coverRef {
                entry["coverRef"] = coverRef
                entry["artworkURL"] = servers.coverURL(coverRef, size: 800)?.absoluteString
            }
            return entry
        }
        guard let data = try? JSONSerialization.data(withJSONObject: entries),
              let json = String(data: data, encoding: .utf8)
        else { return }
        call { bridge in let _: Void = try bridge.setQueue(json, first, true) }
    }

    /// Stops the music and empties the queue, as when signing out.
    func stop() {
        queue = []
        index = 0
        isPlaying = false
        position = 0
        call { bridge in let _: Void = try bridge.stop() }
    }

    func toggle() {
        isPlaying.toggle()
        call { bridge in
            if self.isPlaying { let _: Void = try bridge.play() } else { let _: Void = try bridge.pause() }
        }
    }

    func next() {
        call { bridge in let _: Void = try bridge.next() }
    }

    func previous() {
        call { bridge in let _: Void = try bridge.previous() }
    }

    func skip(to index: Int) {
        guard queue.indices.contains(index) else { return }
        self.index = index
        position = 0
        call { bridge in let _: Void = try bridge.skipTo(index) }
    }

    func seek(to seconds: Double) {
        position = seconds
        call { bridge in let _: Void = try bridge.seekTo(seconds) }
    }

    func toggleShuffle() {
        shuffle.toggle()
        let on = shuffle
        call { bridge in let _: Void = try bridge.setShuffle(on) }
    }

    func cycleRepeat() {
        repeatMode = repeatMode == 0 ? 2 : repeatMode == 2 ? 1 : 0
        let mode = repeatMode
        call { bridge in let _: Void = try bridge.setRepeat(mode) }
    }

    // MARK: - Following the service

    /// Reads where the player has got to. Cheap: one call into Kotlin and a little JSON.
    func refresh() {
        #if os(Android)
        guard hasQueue else { return }
        do {
            let bridge = try AnyDynamicObject(forStaticsOfClassName: "tracks.module.PlayerBridge")
            let json: String? = try bridge.snapshot()
            guard let data = json?.data(using: .utf8),
                  let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data),
                  let count = snapshot.count, count > 0
            else { return }
            if let newIndex = snapshot.index, queue.indices.contains(newIndex) { index = newIndex }
            isPlaying = snapshot.playWhenReady ?? isPlaying
            isBuffering = snapshot.isBuffering ?? false
            position = snapshot.position ?? position
            if let length = snapshot.duration, length > 0 { duration = length } else { duration = current?.duration ?? 0 }
            shuffle = snapshot.shuffle ?? shuffle
            repeatMode = snapshot.repeat ?? repeatMode
        } catch {
            logger.error("Couldn't read the player: \(error)")
        }
        #endif
    }

    private struct Snapshot: Decodable {
        var index: Int?
        var count: Int?
        var playWhenReady: Bool?
        var isBuffering: Bool?
        var position: Double?
        var duration: Double?
        var shuffle: Bool?
        var `repeat`: Int?
    }

    private func call(_ command: (AnyDynamicObject) throws -> Void) {
        #if os(Android)
        do {
            let bridge = try AnyDynamicObject(forStaticsOfClassName: "tracks.module.PlayerBridge")
            try command(bridge)
        } catch {
            logger.error("Player command failed: \(error)")
        }
        #endif
    }
}

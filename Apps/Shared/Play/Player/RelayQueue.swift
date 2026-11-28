import Foundation
import TracksCore

/// A queue for the server's relay: a music service's tracks by their refs, and the server's
/// own songs by their ids, in the order they play.
///
/// The rule is the contract's: a queue with any service track in it plays entirely through
/// the relay, the library songs with it; a queue of library songs only plays here natively.
nonisolated struct RelayQueue: Sendable {
    let serverID: String
    var entries: [RelayEntry]
    var start: Int
    /// Where the first song starts.
    var position: TimeInterval = 0
    /// What the queue was started from, for the speaker's "Playing from".
    var context: TracksideSpeaker.Context?

    init(serverID: String, entries: [RelayEntry], start: Int = 0, position: TimeInterval = 0, context: TracksideSpeaker.Context? = nil) {
        self.serverID = serverID
        self.entries = entries
        self.start = min(max(0, start), max(0, entries.count - 1))
        self.position = position
        self.context = context
    }

    /// The most the server takes in one queue, kept around the song asked for.
    var trimmed: RelayQueue {
        let limit = TracksideSpeakerPlayRequest.itemLimit
        guard entries.count > limit else { return self }
        let from = min(start, entries.count - limit)
        var queue = self
        queue.entries = Array(entries[from..<(from + limit)])
        queue.start = start - from
        return queue
    }
}

/// One song in a relay queue, with what this device shows of it until the server says.
nonisolated struct RelayEntry: Sendable, Equatable {
    let item: TracksideSpeakerPlayRequest.Item
    let title: String
    let artist: String
    let album: String?
    let artworkID: String?
    let duration: TimeInterval?
    let isExplicit: Bool
    /// The service it's from; nil for the server's own songs.
    let serviceID: String?
    /// The song in your own music, for one of the server's songs.
    let local: LocalTrack?

    init(_ track: TracksideCatalogTrack) {
        item = .ref(track.ref)
        title = track.title
        artist = track.artistLine
        album = track.album
        artworkID = track.artworkID
        duration = track.duration
        isExplicit = track.isExplicit
        serviceID = track.serviceID
        local = nil
    }

    /// A service's station, which the server plays as it goes.
    init(_ station: TracksideCatalogRadio) {
        item = .ref(station.ref)
        title = station.name
        artist = ""
        album = nil
        artworkID = station.artworkID
        duration = nil
        isExplicit = false
        serviceID = station.serviceID
        local = nil
    }

    /// One of the server's songs. Nil for a song anywhere else: a file, or another server's.
    init?(_ track: LocalTrack, on serverID: String) {
        guard !UnownedSong.isUnowned(track), case .server(let owner, let songID) = track.origin, owner == serverID else { return nil }
        item = .song(songID)
        title = track.title
        artist = track.artist
        album = track.album
        artworkID = nil
        duration = track.duration
        isExplicit = false
        serviceID = nil
        self.local = track
    }

    /// A song in the server's queue, as its speaker lists it.
    init(_ queued: TracksideSpeakerQueue.Item, local: LocalTrack?) {
        if let ref = queued.ref {
            item = .ref(ref)
        } else {
            item = .song(queued.songID ?? "")
        }
        title = queued.title
        artist = queued.artist ?? ""
        album = queued.album
        artworkID = queued.artworkID
        duration = queued.durationMS.flatMap { $0 > 0 ? TimeInterval($0) / 1_000 : nil }
        isExplicit = false
        serviceID = queued.serviceID
        self.local = local
    }

    /// A song as the speaker says it's playing.
    init(_ playing: TracksideSpeaker.NowPlaying, local: LocalTrack?) {
        if let ref = playing.ref {
            item = .ref(ref)
        } else {
            item = .song(playing.songID ?? "")
        }
        title = playing.title
        artist = playing.artist ?? ""
        album = playing.album
        artworkID = playing.artworkID
        duration = playing.duration
        isExplicit = false
        serviceID = playing.serviceID
        self.local = local
    }

    var isFromService: Bool {
        if case .ref = item { return true }
        return false
    }

    /// Whether this is the song the speaker names.
    func isSame(as playing: TracksideSpeaker.NowPlaying) -> Bool {
        switch item {
        case .ref(let ref): playing.ref == ref
        case .song(let id): playing.songID?.lowercased() == id.lowercased()
        }
    }

    func isSame(as queued: TracksideSpeakerQueue.Item) -> Bool {
        switch item {
        case .ref(let ref): queued.ref == ref
        case .song(let id): queued.songID?.lowercased() == id.lowercased()
        }
    }
}

extension RelayQueue {
    /// A queue of the server's songs and a service's tracks: tracks with an owned copy play
    /// that copy, through the relay with the rest.
    init(serverID: String, tracks: [TracksideCatalogTrack], start: Int, owned: (String) -> LocalTrack?, context: TracksideSpeaker.Context?) {
        let entries = tracks.map { track -> RelayEntry in
            if let songID = track.songID, let copy = owned(songID), let entry = RelayEntry(copy, on: serverID) { return entry }
            return RelayEntry(track)
        }
        self.init(serverID: serverID, entries: entries, start: start, context: context)
    }
}

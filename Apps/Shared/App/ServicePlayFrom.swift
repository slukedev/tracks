import Foundation
import MusicKit
import TracksCore

/// Play From's music services: a service your servers play through their relay, ranked above
/// your copies of a song, plays the song from the service.
///
/// The relay plays one queue for one server, the server's own songs among it, so a queue goes
/// to the relay as a whole: its first song from the first service ranked above your copies
/// that has it, and each song after it from that service where the order says so, or from the
/// same server's copy. A song that can be neither is left out, and the queue says so. When the
/// service doesn't have the first song, the queue plays as it would without services.
///
/// Matches are one batch request per queue where the server has it, otherwise one search for
/// each of the first few songs, and are kept for a week (see ``ServiceMatches``).
extension AppModel {
    /// One music service on one server that plays here now, through that server's relay.
    struct Streamer {
        let serverID: String
        let service: TracksideCatalogService

        var kind: String { service.placeKind }
    }

    /// Every service that plays here now: signed in and searchable, on a server whose relay
    /// this device may use and that reaches its services.
    var streamers: [Streamer] {
        let streaming = self.streaming
        return streaming.serverIDs.filter { streaming.canPlayHere($0) && !streaming.isUnavailable($0) }.flatMap { serverID in
            (streaming.services[serverID] ?? []).filter(\.isSearchable).map { Streamer(serverID: serverID, service: $0) }
        }
    }

    /// The services as places in Play From: one for each kind, named as the first server names it.
    var servicePlaces: [(kind: String, name: String)] {
        var seen = Set<String>()
        return streamers.compactMap { streamer in
            guard seen.insert(streamer.kind).inserted else { return nil }
            return (kind: streamer.kind, name: streamer.service.name.isEmpty ? streamer.kind : streamer.service.name)
        }
    }

    /// The request as the relay's queue, where Play From puts a music service before your
    /// copies of its first song and the service has it. Nil plays the request as it is.
    func throughService(_ request: PlayRequest, context: PlayContext) async -> PlayRequest? {
        guard !isDemoLaunch, context.kind != .endless else { return nil }
        let songs: [(song: HistorySong, catalog: Song?, duration: TimeInterval?)]
        let start: Int
        switch request {
        case .local(let tracks, let first):
            songs = tracks.map { (song: HistorySong($0), catalog: nil, duration: $0.duration) }
            start = first
        case .songs(let list, let first):
            songs = list.map { (song: HistorySong(songID: $0.id.rawValue, title: $0.title, artistName: $0.artistName, albumTitle: $0.albumTitle), catalog: $0, duration: $0.duration) }
            start = first
        case .history(let list, let first):
            songs = list.map { (song: $0, catalog: nil, duration: nil) }
            start = first
        case .album, .playlist, .station, .demoStation, .relay:
            return nil
        }
        guard songs.indices.contains(start) else { return nil }
        let selection = effectiveSourceSelection
        let available = streamers.filter { selection == .all || selection == .only(.server($0.serverID)) }
        guard !available.isEmpty else { return nil }

        // From the song asked for on, as many as one queue's matching covers.
        let window = Array(songs[start..<min(songs.count, start + ServiceLookup.batchLimit * TracksideCatalogMatchRequest.itemLimit)])
        let order = yourMusic.playFromOrder
        var kinds: [String] = []
        for streamer in available where !kinds.contains(streamer.kind) { kinds.append(streamer.kind) }
        let first = order.servicesFirst(kinds, before: self.copies(of: window[0].song, catalog: window[0].catalog).filter(\.canPlay).map(\.place))
        guard !first.isEmpty else { return nil }
        let copies = window.map { self.copies(of: $0.song, catalog: $0.catalog) }
        for kind in first {
            for streamer in available where streamer.kind == kind {
                if let queue = await relayQueue(window, copies: copies, via: streamer, order: order, kinds: kinds, context: context) {
                    return .relay(queue)
                }
            }
        }
        return nil
    }

    private func relayQueue(
        _ window: [(song: HistorySong, catalog: Song?, duration: TimeInterval?)],
        copies: [[(item: MixedQueueEngine.Item, place: PlayFromPlace, canPlay: Bool)]],
        via streamer: Streamer,
        order: PlayFromOrder,
        kinds: [String],
        context: PlayContext
    ) async -> RelayQueue? {
        guard let client = streaming.client(for: streamer.serverID) else { return nil }
        let wanted = window.map { WantedSong(title: $0.song.title, artist: $0.song.artistName, album: $0.song.albumTitle, duration: $0.duration) }
        let found = await ServiceLookup.find(
            wanted,
            serverID: streamer.serverID,
            serviceID: streamer.service.id,
            client: client,
            matches: ServicePlayFrom.matches,
            batches: streaming.playback[streamer.serverID]?.match == true
        )
        guard !Task.isCancelled, found.first.flatMap({ $0 }) != nil else { return nil }
        var entries: [RelayEntry] = []
        var leftOut = 0
        for (offset, track) in found.enumerated() {
            let playable = copies[offset].filter(\.canPlay)
            let wantsService = order.servicesFirst(kinds, before: playable.map(\.place)).contains(streamer.kind)
            let serverCopy = playable.lazy.compactMap { $0.item.payload.localTrack.flatMap { RelayEntry($0, on: streamer.serverID) } }.first
            if wantsService, let track {
                entries.append(RelayEntry(track))
            } else if let serverCopy {
                entries.append(serverCopy)
            } else if let track {
                // Your copy can't play through the relay: the service's does instead.
                entries.append(RelayEntry(track))
            } else {
                leftOut += 1
            }
        }
        if leftOut > 0 {
            player.noteSkipped(leftOut, reason: String(localized: "Not on \(streamer.service.name)"))
        }
        return RelayQueue(serverID: streamer.serverID, entries: entries, start: 0, context: RelayPlayerEngine.speakerContext(context))
    }
}

/// What Play From has found on the services, kept between launches.
enum ServicePlayFrom {
    static let matches = ServiceMatches(file: URL.cachesDirectory.appending(path: "ServiceMatches.json"))
}

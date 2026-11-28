import Foundation
import TracksCore

/// Your servers' speakers in the main player, as Spotify Connect shows a speaker: the music
/// goes there from here, the player shows and controls it there, and it comes back here from
/// where it's got to.
extension AppModel {
    var speakers: SpeakersStore { yourMusic.servers.speakers }

    /// The speaker the player shows, while it has a song or one is on its way there. A speaker
    /// out of reach stays, so the player can say so, rather than vanish mid-song.
    var controlledSpeakerNow: TracksideSpeaker? {
        guard let controlledSpeaker, let speaker = speakers.speaker(controlledSpeaker) else { return nil }
        return speaker.hasSong || speakers.isConnecting(controlledSpeaker) ? speaker : nil
    }

    /// Shows and controls a speaker's song in the main player.
    func control(speaker address: SpeakerAddress) {
        controlledSpeaker = address
    }

    /// Sends what's playing here to a speaker, from where it's got to, with Up Next after it.
    /// This device stops once the server takes it, and carries on if it doesn't start there.
    /// - Returns: whether the server took it.
    @discardableResult
    func sendMusic(to address: SpeakerAddress) async -> Bool {
        // The relay's queue moves as the server keeps it, services' tracks and all.
        if let moved = await moveRelay(to: address) { return moved }
        guard let current = player.current else { return false }
        let tracks = [current] + player.upNext
        let items = tracks.map { Self.speakerItem(for: $0, on: address.serverID) }
        let repeatMode: TracksideSpeaker.Repeat = switch player.repeatMode {
        case .off: .off
        case .all: .all
        case .one: .one
        }
        let context = player.context.map { TracksideSpeaker.Context(kind: Self.speakerContextKind($0.kind), name: $0.title) }
        guard let request = SpeakerCast.request(items: items, position: player.playbackTime, repeatMode: repeatMode, context: context) else {
            speakers.say(.notPlayable, about: address)
            return false
        }
        let showing = TracksideSpeaker.NowPlaying(
            title: current.title,
            artist: current.artistName,
            album: current.albumTitle,
            durationMS: current.duration.map { Int($0 * 1_000) },
            origin: .queue,
            songKey: current.songIdentity
        )
        let wasPlaying = player.isPlaying
        guard await speakers.cast(request, showing: showing, to: address) else { return false }
        if player.isPlaying { player.togglePlayPause() }
        resumesAfterFailedCast = wasPlaying ? address : nil
        controlledSpeaker = address
        return true
    }

    /// A queue sent to a speaker didn't start: the player is this device's again, and plays
    /// on if it was playing.
    func castFailed(on address: SpeakerAddress) {
        if controlledSpeaker == address { controlledSpeaker = nil }
        if let message = speakers.problem(for: address)?.message { player.confirm(message) }
        if resumesAfterFailedCast == address {
            resumesAfterFailedCast = nil
            if !player.isPlaying, player.hasQueue { player.togglePlayPause() }
        }
    }

    /// Plays here what a speaker is playing, from where it's got to, and stops it there. Only
    /// songs in your server's library come: they play from the server as any of its songs do.
    /// - Returns: whether it could.
    func playHere(from address: SpeakerAddress) async -> Bool {
        guard let speaker = speakers.speaker(address), let playing = speaker.nowPlaying else { return false }
        var queue: TracksideSpeakerQueue?
        if speaker.can(.queue) { queue = try? await speakers.queue(of: address) }
        // A queue with a service's track in it comes here through the relay, as it is.
        if let moved = await moveToRelay(from: address, queue: queue) { return moved }
        guard let catalog = yourMusic.servers.catalogs[address.serverID], !catalog.isEmpty else { return false }
        let wanted = [playing.songID] + (queue?.upNext.map(\.songID) ?? [])
        let tracks = await Self.libraryTracks(wanted, in: catalog, serverID: address.serverID)
        guard let first = tracks.first, first.0 == 0 else { return false }
        resumesAfterFailedCast = nil
        switchSource(to: .yourMusic)
        await player.start(.local(tracks.map { $0.1 }, startingAt: 0), from: .songs(String(localized: "From \(speaker.name)")))
        if let position = speakers.position(of: address, at: .now), position > 3 {
            for _ in 0..<20 where player.status != .playing {
                try? await Task.sleep(for: .milliseconds(250))
            }
            player.seek(to: position)
        }
        if speakers.speaker(address)?.can(.stop) == true {
            speakers.perform(.stop, on: address)
        } else if speakers.speaker(address)?.can(.pause) == true {
            speakers.perform(.pause, on: address)
        }
        if controlledSpeaker == address { controlledSpeaker = nil }
        return true
    }

    /// The songs found in a server's library, with where each was in the list asked for.
    /// Looked up off the main actor: a big library takes a moment to index.
    @concurrent
    nonisolated private static func libraryTracks(_ songIDs: [String?], in catalog: [LocalTrack], serverID: String) async -> [(Int, LocalTrack)] {
        var byID: [String: LocalTrack] = [:]
        byID.reserveCapacity(catalog.count)
        for track in catalog {
            if case .server(let owner, let songID) = track.origin, owner == serverID { byID[songID] = track }
        }
        return songIDs.enumerated().compactMap { index, id in
            id.flatMap { byID[$0] }.map { (index, $0) }
        }
    }

    /// What a song in the queue here is to a speaker on `serverID`: one of that server's own
    /// songs. Anything else can't be sent there yet.
    static func speakerItem(for track: PlayerTrack, on serverID: String) -> TracksideSpeakerPlayRequest.Item? {
        guard let local = track.local, !UnownedSong.isUnowned(local),
              case .server(let owner, let songID) = local.origin, owner == serverID
        else { return nil }
        return .song(songID)
    }

    private static func speakerContextKind(_ kind: PlayContext.Kind) -> TracksideSpeaker.Context.Kind {
        switch kind {
        case .station: .station
        case .mix, .endless: .mix
        case .album: .album
        case .playlist: .playlist
        case .artist: .artist
        case .songs: .other
        }
    }

    /// A speaker's song, as the player draws it.
    func speakerTrack(_ speaker: TracksideSpeaker, on serverID: String) -> PlayerTrack {
        let playing = speaker.nowPlaying
        let title = playing?.title ?? ""
        let artist = playing?.artist ?? ""
        let artwork = playing?.artworkID.flatMap { id in yourMusic.servers.tracksideClient(for: serverID)?.artworkURL(id: id).absoluteString }
        return PlayerTrack(
            id: "speaker.\(serverID).\(speaker.id).\(playing?.itemID ?? SongIdentity.key(title: title, artistName: artist))",
            title: title,
            artistName: artist,
            albumTitle: playing?.album,
            cover: .url(artwork, seed: playing?.album ?? (title.isEmpty ? speaker.name : title)),
            duration: playing?.duration,
            isExplicit: false,
            song: nil
        )
    }
}

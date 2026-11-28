import Foundation
import TracksCore

/// The music services on your servers: playing their tracks here through the server's relay,
/// and moving that between this device and a speaker.
extension AppModel {
    var streaming: StreamingCatalog { yourMusic.servers.streaming }

    /// Plays a queue with a service's tracks in it here, through the relay. Library songs in
    /// it come along: the contract's rule is one player for the whole queue.
    func playThroughRelay(_ queue: RelayQueue, from context: PlayContext, shuffled: Bool = false) {
        guard streaming.canPlayHere(queue.serverID) else {
            player.confirm(String(localized: "This device can't play music services from your server."))
            return
        }
        player.play(.relay(queue), from: context, shuffled: shuffled)
    }

    /// Adds a service's tracks to the queue playing here, which moves to the relay with them,
    /// from where it's got to. Only the server's own songs can go there: a queue with anything
    /// else in it, an Apple Music song playing, plays the tracks instead.
    func carryIntoRelay(_ request: PlayRequest, next: Bool) async -> Bool {
        guard case .relay(let added) = request, streaming.canPlayHere(added.serverID),
              let current = player.current?.local, let first = RelayEntry(current, on: added.serverID)
        else { return false }
        let ahead = player.upNext.compactMap { $0.local.flatMap { RelayEntry($0, on: added.serverID) } }
        let entries = [first] + (next ? added.entries + ahead : ahead + added.entries)
        let wasPlaying = player.isPlaying
        let queue = RelayQueue(serverID: added.serverID, entries: entries, start: 0, position: player.playbackTime, context: player.context.map(RelayPlayerEngine.speakerContext))
        // As songs, not a live mix: the relay's queue is the server's from here.
        await player.start(.relay(queue), from: .songs(player.context?.title ?? ""))
        if !wasPlaying, player.isPlaying { player.togglePlayPause() }
        return true
    }

    /// Moves the relay's queue to a speaker on the same server, as the server keeps it.
    /// - Returns: nil when the relay isn't what's playing, or isn't on that server.
    func moveRelay(to address: SpeakerAddress) async -> Bool? {
        guard player.playsThroughRelay, let relay = relayIfMade, relay.serverID == address.serverID,
              let relayID = relay.speakerID, let current = player.current
        else { return nil }
        let showing = TracksideSpeaker.NowPlaying(
            title: current.title,
            artist: current.artistName,
            album: current.albumTitle,
            durationMS: current.duration.map { Int($0 * 1_000) },
            origin: .queue,
            songKey: current.songIdentity
        )
        guard await speakers.move(from: relayID, showing: showing, at: player.playbackTime, to: address) else { return false }
        // The server stops the relay as it moves the queue; here it only goes quiet.
        if player.isPlaying { player.togglePlayPause() }
        controlledSpeaker = address
        return true
    }

    /// Moves what a speaker plays to this device when its queue has a service's track in it:
    /// the server makes or reuses this device's relay and moves the queue there.
    /// - Returns: nil when the speaker's queue is the server's songs only, which play natively.
    func moveToRelay(from address: SpeakerAddress, queue: TracksideSpeakerQueue?) async -> Bool? {
        guard let speaker = speakers.speaker(address) else { return nil }
        let hasServiceTrack = speaker.nowPlaying?.ref != nil || queue?.items.contains { $0.ref != nil } == true
        guard hasServiceTrack else { return nil }
        guard streaming.canPlayHere(address.serverID), speaker.can(.transfer),
              let client = yourMusic.servers.tracksideClient(for: address.serverID)
        else {
            speakers.say(.unsupported, about: address)
            return false
        }
        let relay = relayEngine
        do {
            let command = try await client.transferHere(from: speaker.id, tier: relay.preferredTier(on: address.serverID), key: UUID().uuidString)
            guard let session = command.relay else { return false }
            player.use(relay)
            relay.takeOver(session, on: address.serverID, showing: command.speaker)
            if controlledSpeaker == address { controlledSpeaker = nil }
            return true
        } catch {
            player.confirm(RelayPlayerEngine.words(for: error))
            return false
        }
    }
}

import Foundation

/// Which relay playlist the player should have, as word of the session comes in from the
/// command answers, the events and a read after a failure, in any order.
///
/// Each generation has its own playlist, and the player moves to a new one rather than
/// playing out what it buffered of the old: so stale audio never plays after a seek or a
/// skip. Word older than what's already known is let be, and while the music is meant to be
/// paused nothing is opened: a paused session names the next generation's playlist, which
/// only starts when playing resumes.
public struct RelayStreamFollower: Sendable, Equatable {
    public enum Step: Sendable, Equatable {
        /// Keep what's playing.
        case stay
        /// Move the player to this playlist, a path without the media token.
        case open(String)
        /// The session is over; the player stops.
        case end(TracksideRelaySession.EndedReason)
    }

    /// The playlist the player has.
    public private(set) var opened: String?
    /// The newest playlist the session has named.
    public private(set) var latest: String?
    /// The newest generation heard of.
    public private(set) var generation = -1
    public private(set) var sessionID: String?

    public init() {}

    /// Takes the session as one answer or event gave it.
    public mutating func take(_ session: TracksideRelaySession, wantsToPlay: Bool) -> Step {
        if session.id != sessionID {
            // A new session starts its own count.
            self = RelayStreamFollower()
            sessionID = session.id
        }
        if session.hasEnded {
            opened = nil
            latest = nil
            return .end(session.endedReason ?? .unknown)
        }
        guard session.generation >= generation else { return .stay }
        generation = session.generation
        guard let path = session.stream?.hls else { return .stay }
        latest = path
        return wantsToPlay ? openLatest() : .stay
    }

    /// Playing again, from a pause: the newest playlist, which may wait a moment for audio.
    public mutating func resume() -> Step { openLatest() }

    /// The player's playlist failed, gone from the server: the newest again, even if it's
    /// the same one, once the session has been read.
    public mutating func reopen() -> Step {
        guard let latest else { return .stay }
        opened = latest
        return .open(latest)
    }

    /// Paused here: the playlist the player has is over once the server ends it.
    public mutating func paused() {
        opened = nil
    }

    private mutating func openLatest() -> Step {
        guard let latest, latest != opened else { return .stay }
        opened = latest
        return .open(latest)
    }
}

/// Which tier of the relay a network calls for.
public enum RelayTierChoice {
    /// Lossless on Wi-Fi and wired networks; the cellular tier on cellular, a hotspot or Low
    /// Data Mode, where the server can make it.
    public static func tier(isExpensive: Bool, isConstrained: Bool, usesCellular: Bool, serverHasCellular: Bool) -> TracksideRelaySession.Tier {
        guard serverHasCellular else { return .lossless }
        return isExpensive || isConstrained || usesCellular ? .cellular : .lossless
    }
}

/// When a song change heard from the relay's speaker can be heard here: the server says what
/// it plays about four seconds before the phone does.
public enum RelayAudibility {
    /// The server's time at the audio the phone is playing now: the player's program date
    /// where it has one, otherwise the server's time less the relay's latency.
    public static func audibleNow(programDate: Date?, serverNow: Date, latency: TimeInterval) -> Date {
        if let programDate, abs(programDate.timeIntervalSince(serverNow)) < 60 { return programDate }
        return serverNow.addingTimeInterval(-latency)
    }

    /// Whether a change the server made at `changedAt` is audible yet.
    public static func isAudible(_ changedAt: Date, audibleNow: Date) -> Bool {
        audibleNow >= changedAt
    }
}

/// A catalog result next to the library's: either the owned copy of it, which plays as any
/// library song does, or the service's own track.
public enum CatalogFoldedRow<Owned: Sendable>: Sendable {
    case owned(Owned, from: TracksideCatalogTrack)
    case track(TracksideCatalogTrack)
}

public enum CatalogFold {
    /// The services' tracks below the library's results, without the songs already listed:
    /// one the library has (it carries `song_id`) shows as the owned copy, unless the library
    /// rows above already show it; one with the key of a song listed above is left out.
    /// - Parameters:
    ///   - listedKeys: the song keys of the library rows shown.
    ///   - owned: the library song a `song_id` names, when this device knows it.
    public static func fold<Owned: Sendable>(_ tracks: [TracksideCatalogTrack], listedKeys: Set<String>, owned: (String) -> Owned?) -> [CatalogFoldedRow<Owned>] {
        var seen = listedKeys
        var rows: [CatalogFoldedRow<Owned>] = []
        for track in tracks where track.isPlayable {
            if let key = track.songKey {
                guard seen.insert(key).inserted else { continue }
            }
            if let songID = track.songID, let copy = owned(songID) {
                rows.append(.owned(copy, from: track))
            } else {
                rows.append(.track(track))
            }
        }
        return rows
    }
}

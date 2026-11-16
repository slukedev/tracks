import Foundation

/// What one device asks another's player to do, from its own player's controls. Play and
/// Pause say which, rather than toggling, so asking twice does what was asked once.
public enum RemoteAction: Codable, Sendable, Equatable {
    case play
    case pause
    case next
    case previous
    case seek(TimeInterval)
}

/// One ask, numbered so the device that plays can tell a repeat, or one overtaken by a later
/// ask, from a new one.
public struct RemoteRequest: Codable, Sendable, Equatable {
    public var id: UUID
    /// The asking app's run. Numbers start again each time it opens.
    public var session: UUID
    /// Counts up through a run.
    public var number: Int
    public var action: RemoteAction
    /// The song on show when it was asked, as a ``SongIdentity`` key: a place in one song
    /// means nothing in the next.
    public var song: String?
    public var sentAt: Date

    public init(id: UUID = UUID(), session: UUID, number: Int, action: RemoteAction, song: String?, sentAt: Date) {
        self.id = id
        self.session = session
        self.number = number
        self.action = action
        self.song = song
        self.sentAt = sentAt
    }
}

/// The answer to a ``RemoteRequest``: whether it was done, and what's playing after it.
public struct RemoteReply: Codable, Sendable, Equatable {
    public enum Outcome: String, Sendable {
        case done
        /// Overtaken by a later ask, too old, or about a song no longer on.
        case stale
        /// Nothing to do it to, such as Previous on a station.
        case unavailable
    }

    public var id: UUID
    /// An ``Outcome``'s wire name, kept as text so one added later doesn't stop the rest reading.
    public var outcomeName: String
    public var state: NearbyState?

    public var outcome: Outcome { Outcome(rawValue: outcomeName) ?? .done }

    public init(id: UUID, outcome: Outcome, state: NearbyState?) {
        self.id = id
        self.outcomeName = outcome.rawValue
        self.state = state
    }
}

/// The asks a device has taken, so each is done once, in order, and only while it still means
/// what it meant.
public struct RemoteLedger: Sendable {
    public enum Verdict: Equatable, Sendable {
        case apply
        /// The latest ask from that run again: answered, not done twice.
        case repeated
        case stale
    }

    /// Older than this, an ask held up on its way is let go. Generous, since two devices'
    /// clocks can differ by a second or two.
    public static let oldest: TimeInterval = 30
    /// Runs remembered, latest last.
    static let sessionsKept = 16

    private var latest: [(session: UUID, number: Int)] = []

    public init() {}

    public mutating func admit(_ request: RemoteRequest, playing song: String?, at now: Date) -> Verdict {
        let index = latest.firstIndex { $0.session == request.session }
        if let index {
            let last = latest[index].number
            if request.number == last { return .repeated }
            if request.number < last { return .stale }
        }
        note(request, at: index)
        if now.timeIntervalSince(request.sentAt) > Self.oldest { return .stale }
        if case .seek = request.action, request.song != song { return .stale }
        return .apply
    }

    private mutating func note(_ request: RemoteRequest, at index: Int?) {
        if let index { latest.remove(at: index) }
        latest.append((request.session, request.number))
        if latest.count > Self.sessionsKept { latest.removeFirst(latest.count - Self.sessionsKept) }
    }
}

/// Another device's player as this one shows it: what it last said, with this one's asks laid
/// over it until it answers, so a click shows at once. An ask unanswered for long means the
/// device can't be reached.
public struct RemoteControl: Sendable, Equatable {
    /// How long an ask may go unanswered. The device that plays may wait a moment for its
    /// player to settle before it answers.
    public static let patience: TimeInterval = 5

    public let session: UUID
    public private(set) var reported: NearbyState?
    public private(set) var isUnreachable = false
    private var pending: [Pending] = []
    private var lastNumber = 0

    private struct Pending: Sendable, Equatable {
        let request: RemoteRequest
        let askedAt: Date
    }

    public init(session: UUID = UUID(), reported: NearbyState? = nil) {
        self.session = session
        self.reported = reported
    }

    /// Makes the ask to send, and shows it from now.
    public mutating func ask(_ action: RemoteAction, at now: Date) -> RemoteRequest {
        lastNumber += 1
        let song = reported.flatMap { state in
            state.title.map { SongIdentity.key(title: $0, artistName: state.artist ?? "") }
        }
        let request = RemoteRequest(session: session, number: lastNumber, action: action, song: song, sentAt: now)
        pending.append(Pending(request: request, askedAt: now))
        return request
    }

    /// What the device says it's playing, unasked. Older news than what's shown is let go.
    public mutating func report(_ state: NearbyState) {
        isUnreachable = false
        if let reported, state.sentAt < reported.sentAt { return }
        reported = state
    }

    public mutating func answer(_ reply: RemoteReply) {
        pending.removeAll { $0.request.id == reply.id }
        if let state = reply.state {
            report(state)
        } else {
            isUnreachable = false
        }
    }

    /// Lets go of asks unanswered past ``patience``. Returns whether any were.
    @discardableResult
    public mutating func expire(at now: Date) -> Bool {
        let before = pending.count
        pending.removeAll { now.timeIntervalSince($0.askedAt) >= Self.patience }
        guard pending.count < before else { return false }
        isUnreachable = true
        return true
    }

    /// Waiting on a skip: the song on show is about to change.
    public var isSkipping: Bool {
        pending.contains { $0.request.action == .next || $0.request.action == .previous }
    }

    public var isWaiting: Bool { !pending.isEmpty }

    /// The player as it should look now: what it last said, with every ask still unanswered
    /// done to it, in order.
    public var shown: NearbyState? {
        guard var state = reported else { return nil }
        for ask in pending {
            switch ask.request.action {
            case .play where !state.isPlaying:
                state.position = state.position(at: ask.askedAt)
                state.sentAt = ask.askedAt
                state.isPlaying = true
            case .pause where state.isPlaying:
                state.position = state.position(at: ask.askedAt)
                state.sentAt = ask.askedAt
                state.isPlaying = false
            case .seek(let position):
                state.position = state.duration.map { min($0, max(0, position)) } ?? max(0, position)
                state.sentAt = ask.askedAt
            case .play, .pause, .next, .previous:
                break
            }
        }
        return state
    }
}

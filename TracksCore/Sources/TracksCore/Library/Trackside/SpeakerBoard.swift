import Foundation

/// The parts of a speaker one command changes. A newer command on the same part replaces the
/// one still waiting, as dragging a volume slider does.
public enum SpeakerControl: Hashable, Sendable, CaseIterable {
    /// Playing or paused, the place in the song, and the song itself.
    case transport
    case volume
    case groupVolume
    case mute
    case power
    case shuffle
    case repeatMode
    case group
}

/// One server's speakers as Tracks shows them: what the server last said, with the commands
/// still on their way laid over it, so a tap shows at once and the server's answer settles it.
///
/// A command shows from the moment it's asked (``expect(_:on:expected:purpose:token:deadline:)``),
/// takes the server's own expectation from its `202` (``accept(_:command:deadline:)``), and
/// gives way to the truth once the server says it's done and a newer revision has come
/// (``apply(_:)``, ``settle(_:)``). A failure, or no word by its deadline, drops it, so the
/// speaker shows what it really does.
public struct SpeakerBoard: Sendable, Equatable {
    public enum Purpose: Sendable, Equatable {
        case command
        /// The queue sent to play there: the speaker's row says it's connecting.
        case cast
        /// Next or previous: the song on show is on its way out.
        case skip
    }

    struct Pending: Sendable, Equatable {
        let token: UUID
        let speakerID: String
        let control: SpeakerControl
        let purpose: Purpose
        var commandID: String?
        var expected: TracksideSpeaker
        var baseRevision: Int64
        var deadline: Date
        var isDone = false
    }

    /// A command that didn't happen, for a calm word about it.
    public struct Failure: Sendable, Equatable {
        public let speakerID: String
        public let control: SpeakerControl
        public let purpose: Purpose
        /// The server's code: one of contract 0.4's, `command_failed` or `timed_out`.
        public let code: String?
    }

    public private(set) var state: TracksideSpeakerList.State = .ok
    public private(set) var since: Date?
    /// False until the server's list has been read once.
    public private(set) var isLoaded = false
    /// The server itself answers. Its speakers stay on show, dimmed, while it doesn't.
    public var isServerReachable = true
    public private(set) var etag: String?

    private var confirmed: [String: TracksideSpeaker] = [:]
    private var order: [String] = []
    private var pending: [Pending] = []

    public init() {}

    // MARK: - What shows

    /// Every speaker, as the server orders them, with what's on its way laid over each.
    public var speakers: [TracksideSpeaker] {
        order.compactMap { speaker($0) }
    }

    public func speaker(_ id: String) -> TracksideSpeaker? {
        guard var shown = confirmed[id] else { return nil }
        for item in pending where item.speakerID == id {
            Self.overlay(item.control, from: item.expected, onto: &shown)
        }
        return shown
    }

    /// The speaker as the server last said, without anything on its way.
    public func confirmedSpeaker(_ id: String) -> TracksideSpeaker? { confirmed[id] }

    public func isConnecting(_ id: String) -> Bool {
        pending.contains { $0.speakerID == id && $0.purpose == .cast && !$0.isDone }
    }

    public func isSkipping(_ id: String) -> Bool {
        pending.contains { $0.speakerID == id && $0.purpose == .skip }
    }

    public func isWaiting(_ id: String, on control: SpeakerControl) -> Bool {
        pending.contains { $0.speakerID == id && $0.control == control }
    }

    /// The earliest deadline still to come, for one wake-up rather than a ticking timer.
    public var nextDeadline: Date? { pending.map(\.deadline).min() }

    public var hasPending: Bool { !pending.isEmpty }

    /// Where a speaker's song has got to by `serverNow`: from what a command on its way
    /// expects, or else whichever of the speaker's own report and the latest progress is
    /// newer. Nil with nothing loaded.
    public func position(of id: String, progress: TracksideSpeakerProgress?, at serverNow: Date) -> TimeInterval? {
        guard var shown = speaker(id) else { return nil }
        if !isWaiting(id, on: .transport), let progress, progress.id == id,
           var playback = shown.playback, progress.positionAt >= playback.positionAt,
           progress.revision.map({ $0 >= (confirmed[id]?.revision ?? 0) }) ?? true {
            playback.positionMS = progress.positionMS
            playback.positionAt = progress.positionAt
            if progress.state != .unknown { playback.state = progress.state }
            shown.playback = playback
        }
        return shown.position(at: serverNow)
    }

    // MARK: - What the server says

    /// The whole list, read afresh: it's the truth, whatever revisions came before, since a
    /// server that started again counts them from scratch.
    public mutating func load(_ list: TracksideSpeakerList, etag: String? = nil) {
        state = list.state
        since = list.since
        isLoaded = true
        isServerReachable = true
        self.etag = etag
        confirmed = Dictionary(list.speakers.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        order = Self.sorted(Array(confirmed.values)).map(\.id)
        pending.removeAll { item in
            guard let speaker = confirmed[item.speakerID] else { return true }
            return !speaker.isReachable || (item.isDone && speaker.revision > item.baseRevision)
        }
    }

    /// A speaker arrived or changed. One older than what's shown is a late echo and is let go.
    public mutating func apply(_ speaker: TracksideSpeaker) {
        if let known = confirmed[speaker.id], speaker.revision < known.revision { return }
        if confirmed[speaker.id] == nil {
            order = Self.sorted(order.compactMap { confirmed[$0] } + [speaker]).map(\.id)
        } else if confirmed[speaker.id]?.name != speaker.name || confirmed[speaker.id]?.kind != speaker.kind {
            confirmed[speaker.id] = speaker
            order = Self.sorted(order.compactMap { confirmed[$0] }).map(\.id)
        }
        confirmed[speaker.id] = speaker
        if !speaker.isReachable {
            pending.removeAll { $0.speakerID == speaker.id }
            return
        }
        pending.removeAll { $0.speakerID == speaker.id && $0.isDone && speaker.revision > $0.baseRevision }
    }

    /// A speaker read on its own, after a command went unanswered: the truth, as a list is.
    public mutating func replace(_ speaker: TracksideSpeaker) {
        if confirmed[speaker.id] == nil {
            order = Self.sorted(order.compactMap { confirmed[$0] } + [speaker]).map(\.id)
        }
        confirmed[speaker.id] = speaker
    }

    public mutating func remove(_ id: String) {
        confirmed[id] = nil
        order.removeAll { $0 == id }
        pending.removeAll { $0.speakerID == id }
    }

    /// Progress that says the speaker started or stopped, which the rows show. A new place
    /// alone isn't kept here, so the list isn't drawn again as a song plays.
    /// - Returns: whether what shows changed.
    @discardableResult
    public mutating func progress(_ progress: TracksideSpeakerProgress) -> Bool {
        guard var speaker = confirmed[progress.id], var playback = speaker.playback,
              progress.state != .unknown, progress.state != playback.state,
              progress.positionAt >= playback.positionAt
        else { return false }
        playback.state = progress.state
        playback.positionMS = progress.positionMS
        playback.positionAt = progress.positionAt
        speaker.playback = playback
        confirmed[progress.id] = speaker
        return true
    }

    /// Whether the server can reach its speakers. Out of reach, none can be played to, and
    /// what was on its way won't arrive.
    public mutating func setState(_ state: TracksideSpeakersState) {
        self.state = state.state
        since = state.since
        if state.state == .unavailable {
            confirmed = [:]
            order = []
            pending = []
        }
    }

    // MARK: - Commands

    /// Shows a command at once, before the server has heard it. One on its way for the same
    /// part of the same speaker gives way to it.
    public mutating func expect(_ control: SpeakerControl, on id: String, expected: TracksideSpeaker, purpose: Purpose = .command, token: UUID, deadline: Date) {
        guard let base = confirmed[id] else { return }
        pending.removeAll { $0.speakerID == id && $0.control == control }
        pending.append(Pending(token: token, speakerID: id, control: control, purpose: purpose, expected: expected, baseRevision: base.revision, deadline: deadline))
    }

    /// The server took the command: its expectation stands in for the one guessed here. Its
    /// speaker can be another one than asked, as a transfer's target is.
    public mutating func accept(_ token: UUID, command: TracksideSpeakerCommand, deadline: Date) {
        guard let index = pending.firstIndex(where: { $0.token == token }) else { return }
        pending[index].commandID = command.commandID
        pending[index].deadline = deadline
        if command.speaker.id == pending[index].speakerID {
            pending[index].expected = command.speaker
            pending[index].baseRevision = command.speaker.revision
        }
    }

    /// The command was refused, or never reached the server.
    /// - Returns: the failure, unless a newer command has taken its place.
    @discardableResult
    public mutating func reject(_ token: UUID, code: String?) -> Failure? {
        guard let index = pending.firstIndex(where: { $0.token == token }) else { return nil }
        let item = pending.remove(at: index)
        return Failure(speakerID: item.speakerID, control: item.control, purpose: item.purpose, code: code)
    }

    /// How a command went, from the server's event.
    /// - Returns: the failure, when it failed.
    @discardableResult
    public mutating func settle(_ outcome: TracksideSpeakerCommandOutcome) -> Failure? {
        guard let index = pending.firstIndex(where: { $0.commandID == outcome.commandID }) else { return nil }
        switch outcome.outcome {
        case .done, .unknown:
            pending[index].isDone = true
            let item = pending[index]
            if let speaker = confirmed[item.speakerID] {
                var after = speaker
                Self.overlay(item.control, from: item.expected, onto: &after)
                // Already the truth, or nothing to wait for: no newer revision will come.
                if speaker.revision > item.baseRevision || after == speaker {
                    pending.remove(at: index)
                }
            }
            return nil
        case .failed:
            let item = pending.remove(at: index)
            return Failure(speakerID: item.speakerID, control: item.control, purpose: item.purpose, code: outcome.error?.code ?? "command_failed")
        }
    }

    /// Drops what's waited past its deadline.
    /// - Returns: the speakers to read again, and the casts that never started.
    public mutating func expire(at now: Date) -> (reread: Set<String>, failures: [Failure]) {
        let late = pending.filter { $0.deadline <= now }
        guard !late.isEmpty else { return ([], []) }
        pending.removeAll { $0.deadline <= now }
        let failures = late.filter { !$0.isDone && $0.purpose == .cast }.map {
            Failure(speakerID: $0.speakerID, control: $0.control, purpose: $0.purpose, code: "timed_out")
        }
        return (Set(late.map(\.speakerID)), failures)
    }

    // MARK: - Rules

    /// Groups first, then by name, folded as the server folds it.
    static func sorted(_ speakers: [TracksideSpeaker]) -> [TracksideSpeaker] {
        speakers.sorted { a, b in
            let aGroup = a.kind == .group, bGroup = b.kind == .group
            if aGroup != bGroup { return aGroup }
            let order = a.name.compare(b.name, options: [.caseInsensitive, .diacriticInsensitive, .numeric])
            return order == .orderedSame ? a.id < b.id : order == .orderedAscending
        }
    }

    static func overlay(_ control: SpeakerControl, from expected: TracksideSpeaker, onto shown: inout TracksideSpeaker) {
        switch control {
        case .transport:
            if let playback = expected.playback {
                var merged = shown.playback ?? playback
                merged.state = playback.state
                merged.positionMS = playback.positionMS
                merged.positionAt = playback.positionAt
                shown.playback = merged
            }
            if let nowPlaying = expected.nowPlaying { shown.nowPlaying = nowPlaying }
            if let context = expected.context { shown.context = context }
        case .volume:
            shown.volume = expected.volume
        case .groupVolume:
            shown.groupVolume = expected.groupVolume
        case .mute:
            shown.muted = expected.muted
        case .power:
            shown.powered = expected.powered
            if expected.powered == false, var playback = shown.playback {
                playback.state = .paused
                shown.playback = playback
            }
        case .shuffle:
            if var playback = shown.playback {
                playback.shuffle = expected.playback?.shuffle
                shown.playback = playback
            }
        case .repeatMode:
            if var playback = shown.playback {
                playback.repeatMode = expected.playback?.repeatMode
                shown.playback = playback
            }
        case .group:
            shown.group = expected.group
        }
    }
}

extension TracksideSpeaker {
    /// What a command is expected to leave the speaker as, before the server says: what the
    /// row and the player show the moment it's asked.
    public func expecting(_ action: TracksideSpeakerAction, at serverNow: Date) -> TracksideSpeaker {
        var speaker = self
        guard var playback = speaker.playback else { return speaker }
        let here = position(at: serverNow) ?? 0
        switch action {
        case .play:
            playback.state = .playing
            playback.positionMS = Int((here * 1_000).rounded())
            playback.positionAt = serverNow
        case .pause, .stop:
            playback.state = action == .stop ? .idle : .paused
            playback.positionMS = Int((here * 1_000).rounded())
            playback.positionAt = serverNow
        case .seek(let time):
            let limit = nowPlaying?.duration ?? .infinity
            playback.positionMS = Int((min(limit, max(0, time)) * 1_000).rounded())
            playback.positionAt = serverNow
        case .next, .previous, .jump:
            break
        }
        speaker.playback = playback
        return speaker
    }

    public func expecting(_ change: TracksideSpeakerChange) -> TracksideSpeaker {
        var speaker = self
        if let volume = change.volume { speaker.volume = volume }
        if let muted = change.muted { speaker.muted = muted }
        if let powered = change.powered { speaker.powered = powered }
        if let groupVolume = change.groupVolume { speaker.groupVolume = groupVolume }
        if change.shuffle != nil || change.repeatMode != nil, var playback = speaker.playback {
            if let shuffle = change.shuffle { playback.shuffle = shuffle }
            if let repeatMode = change.repeatMode { playback.repeatMode = repeatMode }
            speaker.playback = playback
        }
        return speaker
    }

    /// Leading `memberIDs`, or on its own with none.
    public func expecting(members memberIDs: [String]) -> TracksideSpeaker {
        var speaker = self
        speaker.group = memberIDs.isEmpty ? nil : Group(leaderID: id, memberIDs: [id] + memberIDs, role: .leader)
        return speaker
    }

    /// Playing a new queue from `nowPlaying`, from `position`.
    public func expecting(playing nowPlaying: NowPlaying?, from position: TimeInterval, at serverNow: Date) -> TracksideSpeaker {
        var speaker = self
        var playback = speaker.playback ?? Playback(state: .buffering, positionMS: 0, positionAt: serverNow)
        playback.state = .buffering
        playback.positionMS = Int((max(0, position) * 1_000).rounded())
        playback.positionAt = serverNow
        speaker.playback = playback
        if let nowPlaying { speaker.nowPlaying = nowPlaying }
        if speaker.availability == .poweredOff {
            speaker.powered = true
        }
        return speaker
    }

    /// The controls that change each part, for which part a command waits on.
    public static func control(for change: TracksideSpeakerChange) -> SpeakerControl? {
        if change.volume != nil { return .volume }
        if change.groupVolume != nil { return .groupVolume }
        if change.muted != nil { return .mute }
        if change.powered != nil { return .power }
        if change.shuffle != nil { return .shuffle }
        if change.repeatMode != nil { return .repeatMode }
        return nil
    }
}

extension TracksideSpeaker {
    /// The line under a speaker's name: what it's playing, or why it isn't.
    /// - Parameter memberNames: the speakers playing with it, when it leads them.
    public func statusLine(memberNames: [String] = []) -> String {
        if availability == .unavailable { return String(localized: "Not Available") }
        if availability == .poweredOff || powered == false { return String(localized: "Off") }
        var line: String
        if let nowPlaying {
            let song = [nowPlaying.title, nowPlaying.artist].compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " · ")
            line = isPlaying ? song : String(localized: "Paused · \(song)")
        } else {
            line = String(localized: "Not Playing")
        }
        if !memberNames.isEmpty {
            line = String(localized: "With \(memberNames.formatted(.list(type: .and))) · \(line)")
        }
        return line
    }

    /// Whether choosing it can do anything now: play the music from here there, or show what
    /// it's playing.
    public func canChoose(hasMusicHere: Bool) -> Bool {
        switch availability {
        case .unavailable, .unknown: false
        case .poweredOff: hasMusicHere && can(.play)
        case .available: (hasMusicHere && can(.play)) || hasSong
        }
    }
}

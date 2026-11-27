import Foundation
import Observation

/// What the speakers store asks a server. ``TracksideClient`` in the app; a fake in tests.
/// Everything here runs off the main actor: the asking and the reading of the answers.
public protocol SpeakerService: Sendable {
    func speakers(ifNoneMatch etag: String?) async throws -> TracksideSpeakersAnswer
    func speaker(id: String) async throws -> TracksideSpeaker
    func speakerQueue(id: String, cursor: String?, limit: Int) async throws -> TracksideSpeakerQueue
    func control(speaker id: String, _ action: TracksideSpeakerAction, retryKey: String?) async throws -> TracksideSpeakerCommand
    func change(speaker id: String, _ change: TracksideSpeakerChange, retryKey: String?) async throws -> TracksideSpeakerCommand
    func group(speaker id: String, with memberIDs: [String], retryKey: String?) async throws -> TracksideSpeakerCommand
    func ungroup(speaker id: String, retryKey: String?) async throws -> TracksideSpeakerCommand
    func play(on id: String, _ request: TracksideSpeakerPlayRequest, key: String) async throws -> TracksideSpeakerCommand
    func transfer(from id: String, to target: String, keepPlayingHere: Bool, key: String) async throws -> TracksideSpeakerCommand
}

extension TracksideClient: SpeakerService {}

/// A speaker on one of your servers. Speakers belong to their server: two servers' ids never
/// mix.
public struct SpeakerAddress: Hashable, Sendable, Codable, Identifiable {
    public let serverID: String
    public let speakerID: String

    public var id: String { "\(serverID)/\(speakerID)" }

    public init(serverID: String, speakerID: String) {
        self.serverID = serverID
        self.speakerID = speakerID
    }
}

/// Something that didn't happen on a speaker, in a sentence to show for a moment.
public struct SpeakerProblem: Sendable, Equatable, Identifiable {
    public enum Reason: Sendable, Equatable {
        /// The speaker can't be reached.
        case offline
        case poweredOff
        case unsupported
        /// None of the server's speakers can be reached.
        case speakersUnavailable
        /// The music can't play there.
        case notPlayable
        /// The speaker didn't do it, or didn't say in time.
        case didNotRespond
        /// The server itself couldn't be reached.
        case serverUnreachable
        case busy
    }

    public let id = UUID()
    public let address: SpeakerAddress
    public let speakerName: String
    public let reason: Reason
    /// A queue sent to play there didn't start.
    public let wasCast: Bool

    public init(address: SpeakerAddress, speakerName: String, reason: Reason, wasCast: Bool = false) {
        self.address = address
        self.speakerName = speakerName
        self.reason = reason
        self.wasCast = wasCast
    }

    public static func == (a: SpeakerProblem, b: SpeakerProblem) -> Bool {
        a.address == b.address && a.reason == b.reason && a.wasCast == b.wasCast
    }

    public var message: String {
        switch reason {
        case .offline: String(localized: "\(speakerName) isn't available right now.")
        case .poweredOff: String(localized: "\(speakerName) is turned off.")
        case .unsupported: String(localized: "\(speakerName) can't do that.")
        case .speakersUnavailable: String(localized: "Speakers aren't available right now.")
        case .notPlayable: String(localized: "This music can't play on \(speakerName).")
        case .didNotRespond: String(localized: "\(speakerName) didn't respond.")
        case .serverUnreachable: String(localized: "Couldn't reach your server.")
        case .busy: String(localized: "Too many changes at once. Try again in a moment.")
        }
    }

    static func reason(code: String?) -> Reason {
        switch code {
        case "speaker_offline", "not_found": .offline
        case "powered_off": .poweredOff
        case "unsupported": .unsupported
        case "speakers_unavailable", "catalog_unavailable": .speakersUnavailable
        case "not_playable", "service_signed_out": .notPlayable
        case "rate_limited": .busy
        default: .didNotRespond
        }
    }

    static func reason(for error: any Error) -> Reason {
        switch error {
        case TracksideError.conflict(let reason): Self.reason(code: reason)
        case TracksideError.unsupported: .unsupported
        case TracksideError.notFound: .offline
        case TracksideError.unavailable: .speakersUnavailable
        case TracksideError.rateLimited: .busy
        case is URLError: .serverUnreachable
        default: .didNotRespond
        }
    }
}

/// Where each speaker's song has got to, apart from the rest, so a song playing redraws only
/// the scrubbers that show it, not every row.
@MainActor
@Observable
public final class SpeakerPositions {
    private(set) var latest: [SpeakerAddress: TracksideSpeakerProgress] = [:]

    public init() {}

    public func progress(of address: SpeakerAddress) -> TracksideSpeakerProgress? { latest[address] }

    func note(_ progress: TracksideSpeakerProgress, on serverID: String) {
        latest[SpeakerAddress(serverID: serverID, speakerID: progress.id)] = progress
    }

    func forget(serverID: String) {
        latest = latest.filter { $0.key.serverID != serverID }
    }

    func forget(_ address: SpeakerAddress) {
        latest[address] = nil
    }
}

/// The speakers on every server you've connected that has them, for Devices and the player.
///
/// Commands show at once, before the server has them, and settle on what the server says:
/// see ``SpeakerBoard``. Volume follows a slider instantly here while the server is sent only
/// the latest value, at most ten times a second. Nothing is polled: the server's events keep
/// it current, and a reconnect, a return to the front or a speaker gone quiet reads it again.
@MainActor
@Observable
public final class SpeakersStore {
    /// Each server's speakers, by server id. A server without speakers isn't here.
    public private(set) var boards: [String: SpeakerBoard] = [:]
    /// The server names, for when there's more than one.
    public private(set) var serverNames: [String: String] = [:]
    /// The latest thing that didn't happen, cleared after a moment.
    public private(set) var problem: SpeakerProblem?
    /// Kept apart, so a playing song redraws its scrubber and nothing else.
    public let positions = SpeakerPositions()

    /// Called when a queue sent to a speaker didn't start, so this device can carry on.
    @ObservationIgnored public var onCastFailed: ((SpeakerAddress) -> Void)?

    @ObservationIgnored private var services: [String: any SpeakerService] = [:]
    @ObservationIgnored private var controlAllowed: [String: Bool] = [:]
    @ObservationIgnored private var skews: [String: TimeInterval] = [:]
    @ObservationIgnored private var refreshes: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var deadlineTask: Task<Void, Never>?
    @ObservationIgnored private var problemTask: Task<Void, Never>?
    @ObservationIgnored private var coalescing: [SendKey: Task<Void, Never>] = [:]
    @ObservationIgnored private var latestChange: [SendKey: (change: TracksideSpeakerChange, token: UUID)] = [:]
    @ObservationIgnored private let now: @Sendable () -> Date
    @ObservationIgnored private let sendInterval: Duration
    @ObservationIgnored private let problemDuration: Duration

    private struct SendKey: Hashable {
        let address: SpeakerAddress
        let control: SpeakerControl
    }

    /// How long a command may wait for its outcome when the server doesn't say.
    nonisolated static let commandPatience: TimeInterval = 5
    nonisolated static let castPatience: TimeInterval = 15

    public init(now: @escaping @Sendable () -> Date = { .now }, sendInterval: Duration = .milliseconds(100), problemDuration: Duration = .seconds(4)) {
        self.now = now
        self.sendInterval = sendInterval
        self.problemDuration = problemDuration
    }

    // MARK: - Servers

    /// Starts showing a server's speakers, once it's connected and says this user has them.
    public func attach(serverID: String, name: String, service: any SpeakerService, canControl: Bool) {
        services[serverID] = service
        controlAllowed[serverID] = canControl
        serverNames[serverID] = name
        if boards[serverID] == nil { boards[serverID] = SpeakerBoard() }
        refresh(serverID)
    }

    /// Stops showing a server's speakers: it was removed, signed out, or no longer has them.
    public func detach(serverID: String) {
        services[serverID] = nil
        controlAllowed[serverID] = nil
        skews[serverID] = nil
        serverNames[serverID] = nil
        refreshes.removeValue(forKey: serverID)?.cancel()
        for key in coalescing.keys where key.address.serverID == serverID {
            coalescing.removeValue(forKey: key)?.cancel()
            latestChange[key] = nil
        }
        boards[serverID] = nil
        positions.forget(serverID: serverID)
        if problem?.address.serverID == serverID { problem = nil }
        scheduleDeadline()
    }

    /// The server stopped answering, or answers again. Its speakers stay, dimmed, meanwhile.
    public func setReachable(_ isReachable: Bool, serverID: String) {
        guard var board = boards[serverID], board.isServerReachable != isReachable else { return }
        board.isServerReachable = isReachable
        boards[serverID] = board
        if isReachable { refresh(serverID) }
    }

    public func isAttached(_ serverID: String) -> Bool { services[serverID] != nil }

    public func canControl(_ serverID: String) -> Bool { controlAllowed[serverID] == true }

    /// Reads a server's speakers again. One read at a time for each server.
    /// - Parameter isFresh: read the whole list, whatever was read before, as after the
    ///   server started again.
    public func refresh(_ serverID: String, isFresh: Bool = false) {
        guard let service = services[serverID], refreshes[serverID] == nil else { return }
        let etag = isFresh ? nil : boards[serverID]?.etag
        refreshes[serverID] = Task { [weak self] in
            let asked = self?.now() ?? .now
            let answer: TracksideSpeakersAnswer?
            var failure: (any Error)?
            do {
                answer = try await service.speakers(ifNoneMatch: etag)
            } catch {
                answer = nil
                failure = error
            }
            guard let self else { return }
            refreshes[serverID] = nil
            guard services[serverID] != nil else { return }
            switch answer {
            case .changed(let list, let newETag, let serverDate):
                if let serverDate { skews[serverID] = TracksideClock.skew(serverDate: serverDate, receivedAt: asked) }
                boards[serverID, default: SpeakerBoard()].load(list, etag: newETag)
            case .unchanged:
                boards[serverID]?.isServerReachable = true
            case nil:
                if failure is URLError { boards[serverID]?.isServerReachable = false }
            }
            scheduleDeadline()
        }
    }

    // MARK: - Events

    /// A server's event. Its speakers' own are kept; the rest are ignored.
    public func handle(_ event: TracksideEvent, from serverID: String) {
        guard services[serverID] != nil else { return }
        switch event {
        case .hello:
            // A reconnect: revisions may have started again, and events were missed.
            boards[serverID]?.isServerReachable = true
            refresh(serverID, isFresh: true)
        case .resync:
            refresh(serverID)
        case .speakerChanged(let speaker):
            boards[serverID]?.apply(speaker)
        case .speakerRemoved(let id):
            boards[serverID]?.remove(id)
            positions.forget(SpeakerAddress(serverID: serverID, speakerID: id))
        case .speakerProgress(let progress):
            positions.note(progress, on: serverID)
            if boards[serverID]?.confirmedSpeaker(progress.id)?.playback?.state != progress.state {
                boards[serverID]?.progress(progress)
            }
        case .speakerCommand(let outcome):
            if let failure = boards[serverID]?.settle(outcome) {
                report(failure, on: serverID)
            }
        case .speakers(let state):
            let was = boards[serverID]?.state
            boards[serverID]?.setState(state)
            if state.state == .ok, was != .ok { refresh(serverID) }
            if state.state == .unavailable { positions.forget(serverID: serverID) }
        case .speakerQueue, .changed, .library, .acquisition, .revoked, .serverURLs, .cloud, .deviceLinked, .relay, .catalogServices, .other:
            break
        }
        scheduleDeadline()
    }

    // MARK: - What shows

    public func speaker(_ address: SpeakerAddress) -> TracksideSpeaker? {
        boards[address.serverID]?.speaker(address.speakerID)
    }

    public func board(_ serverID: String) -> SpeakerBoard? { boards[serverID] }

    /// The servers with speakers, in a steady order.
    public var serverIDs: [String] {
        boards.keys.sorted { (serverNames[$0] ?? $0).localizedStandardCompare(serverNames[$1] ?? $1) == .orderedAscending }
    }

    public var hasSpeakers: Bool { !boards.isEmpty }

    public func isConnecting(_ address: SpeakerAddress) -> Bool {
        boards[address.serverID]?.isConnecting(address.speakerID) == true
    }

    public func isSkipping(_ address: SpeakerAddress) -> Bool {
        boards[address.serverID]?.isSkipping(address.speakerID) == true
    }

    /// The server's clock now, as near as this device can tell.
    public func serverNow(_ serverID: String, at date: Date? = nil) -> Date {
        (date ?? now()).addingTimeInterval(skews[serverID] ?? 0)
    }

    /// Where a speaker's song has got to at `date` on this device's clock.
    public func position(of address: SpeakerAddress, at date: Date) -> TimeInterval? {
        boards[address.serverID]?.position(of: address.speakerID, progress: positions.progress(of: address), at: serverNow(address.serverID, at: date))
    }

    // MARK: - Commands

    /// Play, pause, skip or seek.
    public func perform(_ action: TracksideSpeakerAction, on address: SpeakerAddress) {
        guard let service = services[address.serverID], let speaker = speaker(address) else { return }
        // A command to a member goes to its leader, which plays for both.
        let target = speaker.isGroupMember ? (speaker.group?.leaderID?.lowercased() ?? speaker.id) : speaker.id
        let token = UUID()
        let purpose: SpeakerBoard.Purpose = switch action {
        case .next, .previous, .jump: .skip
        default: .command
        }
        let expected = speaker.expecting(action, at: serverNow(address.serverID))
        boards[address.serverID]?.expect(.transport, on: speaker.id, expected: expected, purpose: purpose, token: token, deadline: deadline(Self.commandPatience))
        scheduleDeadline()
        send(token, on: address) { retryKey in
            try await service.control(speaker: target, action, retryKey: retryKey)
        }
    }

    /// Volume, mute, power, shuffle or repeat. Volume follows at once here; the server gets the
    /// latest value, at most ten times a second.
    public func change(_ change: TracksideSpeakerChange, on address: SpeakerAddress) {
        guard let service = services[address.serverID], let speaker = speaker(address),
              let control = TracksideSpeaker.control(for: change)
        else { return }
        let token = UUID()
        boards[address.serverID]?.expect(control, on: speaker.id, expected: speaker.expecting(change), token: token, deadline: deadline(Self.commandPatience))
        scheduleDeadline()
        // Shuffle and repeat belong to the queue, which a group's leader holds.
        let target = (control == .shuffle || control == .repeatMode) && speaker.isGroupMember ? (speaker.group?.leaderID?.lowercased() ?? speaker.id) : speaker.id
        guard control == .volume || control == .groupVolume else {
            send(token, on: address) { retryKey in
                try await service.change(speaker: target, change, retryKey: retryKey)
            }
            return
        }
        let key = SendKey(address: address, control: control)
        latestChange[key] = (change, token)
        guard coalescing[key] == nil else { return }
        coalescing[key] = Task { [weak self, sendInterval] in
            while true {
                try? await Task.sleep(for: sendInterval)
                guard let self, !Task.isCancelled, let latest = self.latestChange.removeValue(forKey: key) else { break }
                await self.sendNow(latest.token, on: address) { retryKey in
                    try await service.change(speaker: target, latest.change, retryKey: retryKey)
                }
            }
            self?.coalescing[key] = nil
        }
    }

    /// Groups the speakers in `memberIDs` with this one leading, or ungroups it with none.
    public func setMembers(_ memberIDs: [String], of address: SpeakerAddress) {
        guard let service = services[address.serverID], let speaker = speaker(address) else { return }
        let token = UUID()
        boards[address.serverID]?.expect(.group, on: speaker.id, expected: speaker.expecting(members: memberIDs), token: token, deadline: deadline(Self.commandPatience))
        scheduleDeadline()
        send(token, on: address) { retryKey in
            if memberIDs.isEmpty, !speaker.isGroupLeader {
                try await service.ungroup(speaker: speaker.id, retryKey: retryKey)
            } else {
                try await service.group(speaker: speaker.id, with: memberIDs, retryKey: retryKey)
            }
        }
    }

    /// Takes a speaker out of the group it's in.
    public func leaveGroup(_ address: SpeakerAddress) {
        guard let service = services[address.serverID], let speaker = speaker(address) else { return }
        let token = UUID()
        boards[address.serverID]?.expect(.group, on: speaker.id, expected: speaker.expecting(members: []), token: token, deadline: deadline(Self.commandPatience))
        scheduleDeadline()
        send(token, on: address) { retryKey in
            try await service.ungroup(speaker: speaker.id, retryKey: retryKey)
        }
    }

    /// Sends a queue to play on a speaker. The row says it's connecting at once.
    /// - Parameter showing: the song it starts with, as this device knows it, shown until the
    ///   server says.
    /// - Returns: whether the server took it: this device stops playing then.
    @discardableResult
    public func cast(_ request: TracksideSpeakerPlayRequest, showing nowPlaying: TracksideSpeaker.NowPlaying?, to address: SpeakerAddress) async -> Bool {
        guard let service = services[address.serverID], let speaker = speaker(address) else { return false }
        let token = UUID()
        let expected = speaker.expecting(playing: nowPlaying, from: TimeInterval(request.positionMS ?? 0) / 1_000, at: serverNow(address.serverID))
        boards[address.serverID]?.expect(.transport, on: speaker.id, expected: expected, purpose: .cast, token: token, deadline: deadline(Self.castPatience))
        scheduleDeadline()
        let key = UUID().uuidString
        return await sendNow(token, on: address, patience: Self.castPatience) { _ in
            try await service.play(on: speaker.id, request, key: key)
        }
    }

    /// Moves what this device's relay plays to a speaker, where it's got to, as the server
    /// keeps it: the relay's queue, with its music services' tracks. The row says it's
    /// connecting at once.
    /// - Parameter relayID: the relay's own speaker, on the same server.
    /// - Returns: whether the server took it: the relay stops then.
    @discardableResult
    public func move(from relayID: String, showing nowPlaying: TracksideSpeaker.NowPlaying?, at position: TimeInterval, to address: SpeakerAddress) async -> Bool {
        guard let service = services[address.serverID], let speaker = speaker(address) else { return false }
        let token = UUID()
        let expected = speaker.expecting(playing: nowPlaying, from: position, at: serverNow(address.serverID))
        boards[address.serverID]?.expect(.transport, on: speaker.id, expected: expected, purpose: .cast, token: token, deadline: deadline(Self.castPatience))
        scheduleDeadline()
        let key = UUID().uuidString
        return await sendNow(token, on: address, patience: Self.castPatience) { _ in
            try await service.transfer(from: relayID, to: speaker.id, keepPlayingHere: false, key: key)
        }
    }

    // MARK: - Sending

    private func send(_ token: UUID, on address: SpeakerAddress, _ command: @escaping @Sendable (String?) async throws -> TracksideSpeakerCommand) {
        Task { await sendNow(token, on: address, command) }
    }

    /// Sends a command, once more with an idempotency key after a network failure, and takes
    /// the server's expectation or drops the guess.
    @discardableResult
    private func sendNow(_ token: UUID, on address: SpeakerAddress, patience: TimeInterval = SpeakersStore.commandPatience, _ command: @escaping @Sendable (String?) async throws -> TracksideSpeakerCommand) async -> Bool {
        let result: Result<TracksideSpeakerCommand, any Error>
        do {
            result = .success(try await command(nil))
        } catch let error as URLError where error.code == .networkConnectionLost || error.code == .timedOut {
            do {
                result = .success(try await command(UUID().uuidString))
            } catch {
                result = .failure(error)
            }
        } catch {
            result = .failure(error)
        }
        guard boards[address.serverID] != nil else { return false }
        switch result {
        case .success(let accepted):
            let serverTime = serverNow(address.serverID)
            let wait = accepted.settleBy.map { max(1, $0.timeIntervalSince(serverTime)) } ?? patience
            boards[address.serverID]?.accept(token, command: accepted, deadline: now().addingTimeInterval(min(wait, patience * 2)))
            scheduleDeadline()
            return true
        case .failure(let error):
            if let failure = boards[address.serverID]?.reject(token, code: nil) {
                report(failure, on: address.serverID, reason: SpeakerProblem.reason(for: error))
            }
            if case TracksideError.conflict(let reason) = error, reason == "speaker_offline" {
                refresh(address.serverID)
            }
            scheduleDeadline()
            return false
        }
    }

    private func deadline(_ patience: TimeInterval) -> Date {
        now().addingTimeInterval(patience)
    }

    /// One wake-up at the earliest deadline, never a ticking timer.
    private func scheduleDeadline() {
        deadlineTask?.cancel()
        guard let next = boards.values.compactMap(\.nextDeadline).min() else {
            deadlineTask = nil
            return
        }
        let wait = max(0, next.timeIntervalSince(now()))
        deadlineTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait) + .milliseconds(20))
            guard !Task.isCancelled else { return }
            self?.expireLate()
        }
    }

    func expireLate() {
        let date = now()
        for serverID in Array(boards.keys) {
            guard let late = boards[serverID]?.expire(at: date) else { continue }
            for failure in late.failures { report(failure, on: serverID) }
            for id in late.reread { reread(id, on: serverID) }
        }
        scheduleDeadline()
    }

    /// Reads one speaker again, after a command went unanswered.
    private func reread(_ id: String, on serverID: String) {
        guard let service = services[serverID] else { return }
        Task { [weak self] in
            do {
                let speaker = try await service.speaker(id: id)
                guard let self, services[serverID] != nil else { return }
                boards[serverID]?.replace(speaker)
            } catch TracksideError.notFound {
                guard let self, services[serverID] != nil else { return }
                boards[serverID]?.remove(id)
            } catch {
                self?.refresh(serverID)
            }
        }
    }

    private func report(_ failure: SpeakerBoard.Failure, on serverID: String, reason: SpeakerProblem.Reason? = nil) {
        let address = SpeakerAddress(serverID: serverID, speakerID: failure.speakerID)
        let name = boards[serverID]?.confirmedSpeaker(failure.speakerID)?.name ?? ""
        show(SpeakerProblem(address: address, speakerName: name, reason: reason ?? SpeakerProblem.reason(code: failure.code), wasCast: failure.purpose == .cast))
        if failure.purpose == .cast { onCastFailed?(address) }
    }

    private func show(_ problem: SpeakerProblem) {
        self.problem = problem
        problemTask?.cancel()
        problemTask = Task { [weak self, problemDuration] in
            try? await Task.sleep(for: problemDuration)
            guard !Task.isCancelled else { return }
            self?.problem = nil
        }
    }

    /// Up Next on a speaker, from the song playing.
    public func queue(of address: SpeakerAddress, limit: Int = 500) async throws -> TracksideSpeakerQueue {
        guard let service = services[address.serverID] else { throw TracksideError.featureUnavailable }
        return try await service.speakerQueue(id: address.speakerID, cursor: nil, limit: limit)
    }

    /// Says something didn't happen on a speaker, found before anything was sent.
    public func say(_ reason: SpeakerProblem.Reason, about address: SpeakerAddress) {
        let name = speaker(address)?.name ?? ""
        show(SpeakerProblem(address: address, speakerName: name, reason: reason))
    }

    public func dismissProblem() {
        problemTask?.cancel()
        problem = nil
    }
}

/// What a queue on this device becomes, sent to a speaker.
public enum SpeakerCast {
    /// The request for a queue whose first item is the song playing: each song the speaker's
    /// server can play, in order, from where the song has got to. Songs it can't are left out.
    /// - Returns: nil when the song playing is one it can't.
    public static func request(items: [TracksideSpeakerPlayRequest.Item?], position: TimeInterval, repeatMode: TracksideSpeaker.Repeat, context: TracksideSpeaker.Context?) -> TracksideSpeakerPlayRequest? {
        guard let first = items.first, let current = first else { return nil }
        let rest = items.dropFirst().compactMap(\.self)
        let queue = [current] + rest.prefix(TracksideSpeakerPlayRequest.itemLimit - 1)
        return TracksideSpeakerPlayRequest(items: queue, mode: .replace, startIndex: 0, position: position, shuffle: false, repeatMode: repeatMode, context: context)
    }
}

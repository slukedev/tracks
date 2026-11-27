import Testing
import Foundation
import Synchronization
@testable import TracksCore

/// A server's speakers for the store to ask: answers what each test sets, and remembers what
/// it was asked.
final class FakeSpeakerService: SpeakerService {
    enum Call: Equatable {
        case list(etag: String?)
        case speaker(String)
        case control(String, TracksideSpeakerAction)
        case change(String, TracksideSpeakerChange)
        case group(String, [String])
        case ungroup(String)
        case play(String, TracksideSpeakerPlayRequest)
        case transfer(String, String)
        case queue(String)
    }

    private struct State {
        var list: TracksideSpeakerList
        var calls: [Call] = []
        var failure: (any Error)?
        var commands = 0
    }

    private let state: Mutex<State>

    init(_ speakers: [TracksideSpeaker]) {
        state = Mutex(State(list: TracksideSpeakerList(speakers: speakers)))
    }

    var calls: [Call] { state.withLock { $0.calls } }

    /// Every command from now on is refused with this.
    func refuse(with error: (any Error)?) {
        state.withLock { $0.failure = error }
    }

    func setList(_ speakers: [TracksideSpeaker]) {
        state.withLock { $0.list = TracksideSpeakerList(speakers: speakers) }
    }

    private func record(_ call: Call) {
        state.withLock { $0.calls.append(call) }
    }

    /// The server's own expectation: the speaker as asked, with an id for the outcome.
    private func accept(_ id: String, _ change: (TracksideSpeaker) -> TracksideSpeaker) throws -> TracksideSpeakerCommand {
        try state.withLock { state in
            if let failure = state.failure { throw failure }
            state.commands += 1
            guard let speaker = state.list.speakers.first(where: { $0.id == id }) else { throw TracksideError.notFound }
            return TracksideSpeakerCommand(commandID: "j\(state.commands)", settleBy: nil, speaker: change(speaker))
        }
    }

    func speakers(ifNoneMatch etag: String?) async throws -> TracksideSpeakersAnswer {
        record(.list(etag: etag))
        return .changed(state.withLock { $0.list }, etag: "W/\"1\"", serverDate: nil)
    }

    func speaker(id: String) async throws -> TracksideSpeaker {
        record(.speaker(id))
        guard let speaker = state.withLock({ $0.list.speakers.first { $0.id == id } }) else { throw TracksideError.notFound }
        return speaker
    }

    func speakerQueue(id: String, cursor: String?, limit: Int) async throws -> TracksideSpeakerQueue {
        record(.queue(id))
        throw TracksideError.notFound
    }

    func control(speaker id: String, _ action: TracksideSpeakerAction, retryKey: String?) async throws -> TracksideSpeakerCommand {
        record(.control(id, action))
        return try accept(id) { $0.expecting(action, at: SpeakerSamples.start) }
    }

    func change(speaker id: String, _ change: TracksideSpeakerChange, retryKey: String?) async throws -> TracksideSpeakerCommand {
        record(.change(id, change))
        return try accept(id) { $0.expecting(change) }
    }

    func group(speaker id: String, with memberIDs: [String], retryKey: String?) async throws -> TracksideSpeakerCommand {
        record(.group(id, memberIDs))
        return try accept(id) { $0.expecting(members: memberIDs) }
    }

    func ungroup(speaker id: String, retryKey: String?) async throws -> TracksideSpeakerCommand {
        record(.ungroup(id))
        return try accept(id) { $0.expecting(members: []) }
    }

    func play(on id: String, _ request: TracksideSpeakerPlayRequest, key: String) async throws -> TracksideSpeakerCommand {
        record(.play(id, request))
        return try accept(id) { $0.expecting(playing: TracksideSpeaker.NowPlaying(title: "Single Ladies"), from: 0, at: SpeakerSamples.start) }
    }

    func transfer(from id: String, to target: String, keepPlayingHere: Bool, key: String) async throws -> TracksideSpeakerCommand {
        record(.transfer(id, target))
        return try accept(target) { $0 }
    }
}

/// A clock a test moves by hand.
final class SpeakerTestClock: Sendable {
    private let date: Mutex<Date>

    init(_ date: Date) {
        self.date = Mutex(date)
    }

    var now: Date {
        get { date.withLock { $0 } }
        set { date.withLock { $0 = newValue } }
    }
}

@MainActor
@Suite("Speakers in Tracks")
struct SpeakersStoreTests {
    typealias S = SpeakerSamples
    let kitchen = SpeakerAddress(serverID: "home", speakerID: "o1")

    private func makeStore(_ service: FakeSpeakerService, now: Date = SpeakerSamples.start) async -> SpeakersStore {
        let store = SpeakersStore(now: { now }, sendInterval: .milliseconds(10), problemDuration: .seconds(30))
        store.attach(serverID: "home", name: "Home", service: service, canControl: true)
        await until { store.board("home")?.isLoaded == true }
        return store
    }

    /// Waits for what the store does in its own time, failing the test after a while.
    private func until(_ condition: () -> Bool, sourceLocation: SourceLocation = #_sourceLocation) async {
        for _ in 0..<200 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Never happened", sourceLocation: sourceLocation)
    }

    @Test("a queue sent to a speaker shows it connecting at once, and settles once the server says")
    func cast() async {
        let service = FakeSpeakerService([S.speaker(state: .idle)])
        let store = await makeStore(service)
        let request = TracksideSpeakerPlayRequest(items: [.song("s9")], position: 12)
        let accepted = await store.cast(request, showing: TracksideSpeaker.NowPlaying(title: "Halo"), to: kitchen)
        #expect(accepted)
        #expect(store.isConnecting(kitchen))
        // The server's own expectation stands in for the guess made here.
        #expect(store.speaker(kitchen)?.nowPlaying?.title == "Single Ladies")
        #expect(service.calls.last == .play("o1", request))

        store.handle(.speakerCommand(TracksideSpeakerCommandOutcome(commandID: "j1", speakerID: "o1", outcome: .done)), from: "home")
        #expect(store.isConnecting(kitchen) == false)
        store.handle(.speakerChanged(S.speaker(revision: 11)), from: "home")
        #expect(store.board("home")?.hasPending == false)
        #expect(store.speaker(kitchen)?.nowPlaying?.title == "Halo")
    }

    @Test("a cast that fails rolls back, says so calmly, and lets this device carry on")
    func castFails() async {
        let service = FakeSpeakerService([S.speaker(state: .idle)])
        let store = await makeStore(service)
        var failed: [SpeakerAddress] = []
        store.onCastFailed = { failed.append($0) }
        service.refuse(with: TracksideError.conflict(reason: "not_playable"))
        let accepted = await store.cast(TracksideSpeakerPlayRequest(items: [.song("s9")]), showing: nil, to: kitchen)
        #expect(!accepted)
        #expect(!store.isConnecting(kitchen))
        #expect(store.speaker(kitchen)?.playback?.state == .idle)
        #expect(store.problem?.reason == .notPlayable)
        #expect(store.problem?.message == "This music can't play on Kitchen.")
        #expect(failed == [kitchen])

        // A failure heard later, from the server.
        service.refuse(with: nil)
        _ = await store.cast(TracksideSpeakerPlayRequest(items: [.song("s9")]), showing: nil, to: kitchen)
        store.handle(.speakerCommand(TracksideSpeakerCommandOutcome(commandID: "j1", speakerID: "o1", outcome: .failed, error: .init(code: "powered_off", message: nil))), from: "home")
        #expect(store.problem?.reason == .poweredOff)
        #expect(failed == [kitchen, kitchen])
        #expect(store.speaker(kitchen)?.playback?.state == .idle)
    }

    @Test("a slider dragged fast moves at once here, and the server hears only the latest value")
    func volumeCoalesces() async {
        let service = FakeSpeakerService([S.speaker()])
        let store = await makeStore(service)
        for level in stride(from: 36, through: 60, by: 4) {
            store.change(TracksideSpeakerChange(volume: level), on: kitchen)
            #expect(store.speaker(kitchen)?.volume == level)
        }
        await until { service.calls.contains(.change("o1", TracksideSpeakerChange(volume: 60))) }
        let volumes = service.calls.compactMap { call -> Int? in
            if case .change(_, let change) = call { return change.volume }
            return nil
        }
        #expect(volumes == [60])
        #expect(store.speaker(kitchen)?.volume == 60)
    }

    @Test("a refused command rolls back and says why; one to a group's member goes to its leader")
    func refusedAndMembers() async {
        let leader = S.speaker(canGroupWith: ["o2"])
        var member = S.speaker("o2", name: "Living Room")
        member.group = TracksideSpeaker.Group(leaderID: "o1", memberIDs: ["o1", "o2"], role: .member)
        let service = FakeSpeakerService([leader, member])
        let store = await makeStore(service)

        store.perform(.pause, on: SpeakerAddress(serverID: "home", speakerID: "o2"))
        await until { service.calls.contains(.control("o1", .pause)) }

        service.refuse(with: TracksideError.unsupported(capability: "seek"))
        store.perform(.seek(30), on: kitchen)
        #expect(store.speaker(kitchen)?.playback?.positionMS == 30_000)
        await until { store.problem != nil }
        #expect(store.problem?.reason == .unsupported)
        #expect(store.speaker(kitchen)?.playback?.positionMS == 81_000)
    }

    @Test("grouping applies at once and asks the server with the whole list")
    func grouping() async {
        let service = FakeSpeakerService([S.speaker(canGroupWith: ["o2"]), S.speaker("o2", name: "Bedroom")])
        let store = await makeStore(service)
        store.setMembers(["o2"], of: kitchen)
        #expect(store.speaker(kitchen)?.memberIDs == ["o2"])
        await until { service.calls.contains(.group("o1", ["o2"])) }
        store.leaveGroup(kitchen)
        #expect(store.speaker(kitchen)?.group == nil)
        await until { service.calls.contains(.ungroup("o1")) }
    }

    @Test("a reconnect reads the whole list again, and the speakers going away leaves a calm empty list")
    func reconnects() async {
        let service = FakeSpeakerService([S.speaker()])
        let store = await makeStore(service)
        #expect(service.calls == [.list(etag: nil)])
        store.refresh("home")
        await until { service.calls.count == 2 }
        #expect(service.calls.last == .list(etag: "W/\"1\""))
        store.handle(.hello(serverID: "x", seq: 1), from: "home")
        await until { service.calls.count == 3 }
        #expect(service.calls.last == .list(etag: nil))

        store.handle(.speakers(TracksideSpeakersState(state: .unavailable)), from: "home")
        #expect(store.board("home")?.speakers.isEmpty == true)
        #expect(store.board("home")?.state == .unavailable)
        store.handle(.speakers(TracksideSpeakersState(state: .ok)), from: "home")
        await until { store.board("home")?.speakers.count == 1 }
    }

    @Test("progress moves the scrubber's clock, not the rows, until the speaker starts or stops")
    func progress() async {
        let service = FakeSpeakerService([S.speaker()])
        let store = await makeStore(service)
        let board = store.board("home")
        store.handle(.speakerProgress(TracksideSpeakerProgress(id: "o1", positionMS: 120_000, positionAt: S.start.addingTimeInterval(30), revision: 10, state: .playing)), from: "home")
        #expect(store.board("home") == board)
        #expect(store.position(of: kitchen, at: S.start.addingTimeInterval(31)) == 121)
        store.handle(.speakerProgress(TracksideSpeakerProgress(id: "o1", positionMS: 125_000, positionAt: S.start.addingTimeInterval(35), revision: 10, state: .paused)), from: "home")
        #expect(store.speaker(kitchen)?.isPlaying == false)
        #expect(store.position(of: kitchen, at: S.start.addingTimeInterval(90)) == 125)
    }

    @Test("speakers belong to their server: signing out of one leaves the other's, and its late events are ignored")
    func servers() async {
        let home = FakeSpeakerService([S.speaker()])
        let cabin = FakeSpeakerService([S.speaker(name: "Porch")])
        let store = await makeStore(home)
        store.attach(serverID: "cabin", name: "Cabin", service: cabin, canControl: false)
        await until { store.board("cabin")?.isLoaded == true }
        #expect(store.speaker(SpeakerAddress(serverID: "cabin", speakerID: "o1"))?.name == "Porch")
        #expect(store.speaker(kitchen)?.name == "Kitchen")
        #expect(store.serverIDs == ["cabin", "home"])
        #expect(!store.canControl("cabin"))

        store.detach(serverID: "home")
        store.handle(.speakerChanged(S.speaker(name: "Ghost", revision: 99)), from: "home")
        #expect(store.board("home") == nil)
        #expect(store.speaker(kitchen) == nil)
        #expect(store.serverIDs == ["cabin"])
    }

    @Test("a command nobody answers is dropped at its deadline and the speaker is read again")
    func unanswered() async {
        let service = FakeSpeakerService([S.speaker()])
        let clock = SpeakerTestClock(SpeakerSamples.start)
        let store = SpeakersStore(now: { clock.now }, sendInterval: .milliseconds(10))
        store.attach(serverID: "home", name: "Home", service: service, canControl: true)
        await until { store.board("home")?.isLoaded == true }
        store.perform(.pause, on: kitchen)
        await until { service.calls.contains(.control("o1", .pause)) }
        #expect(store.speaker(kitchen)?.isPlaying == false)
        clock.now = SpeakerSamples.start.addingTimeInterval(20)
        store.expireLate()
        #expect(store.speaker(kitchen)?.isPlaying == true)
        await until { service.calls.contains(.speaker("o1")) }
    }
}

import Testing
import Foundation
@testable import TracksCore

/// Speakers as the tests need them: playing from a known moment, by the server's clock.
enum SpeakerSamples {
    static let start = TracksideTesting.date("2026-11-14T09:30:00.000Z")

    static func speaker(_ id: String = "o1", name: String = "Kitchen", state: TracksideSpeaker.Playback.State = .playing, position: Int = 81_000, revision: Int64 = 10, volume: Int? = 35, capabilities: Set<TracksideSpeaker.Capability> = [.play, .pause, .nextPrevious, .seek, .volume, .mute, .group, .shuffle, .repeatMode, .stop], availability: TracksideSpeaker.Availability = .available, kind: TracksideSpeaker.Kind = .speaker, canGroupWith: [String] = []) -> TracksideSpeaker {
        TracksideSpeaker(
            id: id,
            name: name,
            kind: kind,
            availability: availability,
            capabilities: capabilities,
            volume: volume,
            muted: false,
            canGroupWith: canGroupWith,
            playback: TracksideSpeaker.Playback(state: state, positionMS: position, positionAt: start, shuffle: false, repeatMode: .off),
            nowPlaying: TracksideSpeaker.NowPlaying(title: "Halo", artist: "Beyoncé", durationMS: 261_640, itemID: "i1", origin: .queue, songID: "s1"),
            revision: revision
        )
    }

    static func board(_ speakers: TracksideSpeaker...) -> SpeakerBoard {
        var board = SpeakerBoard()
        board.load(TracksideSpeakerList(speakers: speakers))
        return board
    }

    static func command(_ speaker: TracksideSpeaker, id: String = "j1") -> TracksideSpeakerCommand {
        TracksideSpeakerCommand(commandID: id, settleBy: start.addingTimeInterval(5), speaker: speaker)
    }
}

@Suite("A server's speakers, with commands on their way")
struct SpeakerBoardTests {
    typealias S = SpeakerSamples
    let later = SpeakerSamples.start.addingTimeInterval(60)

    @Test("a pause shows at once, takes the server's expectation, and gives way once a newer revision is done")
    func optimisticThenTruth() {
        var board = S.board(S.speaker())
        let token = UUID()
        let asked = board.speaker("o1")!.expecting(.pause, at: S.start.addingTimeInterval(10))
        board.expect(.transport, on: "o1", expected: asked, token: token, deadline: later)
        #expect(board.speaker("o1")?.playback?.state == .paused)
        #expect(board.speaker("o1")?.playback?.positionMS == 91_000)
        #expect(board.confirmedSpeaker("o1")?.playback?.state == .playing)

        board.accept(token, command: S.command(S.speaker(state: .paused, position: 91_200)), deadline: later)
        #expect(board.speaker("o1")?.playback?.positionMS == 91_200)

        // The truth arrives before the outcome: the expectation holds until the server says.
        board.apply(S.speaker(state: .paused, position: 91_300, revision: 11))
        #expect(board.isWaiting("o1", on: .transport))
        board.settle(TracksideSpeakerCommandOutcome(commandID: "j1", speakerID: "o1", outcome: .done))
        #expect(!board.hasPending)
        #expect(board.speaker("o1")?.playback?.positionMS == 91_300)
    }

    @Test("the outcome can come before the truth; the expectation waits for a newer revision")
    func doneThenTruth() {
        var board = S.board(S.speaker())
        let token = UUID()
        board.expect(.volume, on: "o1", expected: board.speaker("o1")!.expecting(TracksideSpeakerChange(volume: 60)), token: token, deadline: later)
        board.accept(token, command: S.command(S.speaker(volume: 60)), deadline: later)
        board.settle(TracksideSpeakerCommandOutcome(commandID: "j1", speakerID: "o1", outcome: .done))
        #expect(board.speaker("o1")?.volume == 60)
        #expect(board.hasPending)
        // An echo from before the command changes nothing.
        board.apply(S.speaker(volume: 35, revision: 9))
        #expect(board.speaker("o1")?.volume == 60)
        board.apply(S.speaker(volume: 58, revision: 11))
        #expect(!board.hasPending)
        #expect(board.speaker("o1")?.volume == 58)
    }

    @Test("a failure, from the server or before it, rolls back to the truth and says what happened")
    func rollBack() {
        var board = S.board(S.speaker())
        let token = UUID()
        board.expect(.transport, on: "o1", expected: board.speaker("o1")!.expecting(.pause, at: S.start), token: token, deadline: later)
        board.accept(token, command: S.command(S.speaker(state: .paused), id: "j8"), deadline: later)
        let failure = board.settle(TracksideSpeakerCommandOutcome(commandID: "j8", speakerID: "o1", outcome: .failed, error: .init(code: "command_failed", message: nil)))
        #expect(failure == SpeakerBoard.Failure(speakerID: "o1", control: .transport, purpose: .command, code: "command_failed"))
        #expect(board.speaker("o1")?.playback?.state == .playing)

        let refused = UUID()
        board.expect(.mute, on: "o1", expected: board.speaker("o1")!.expecting(TracksideSpeakerChange(muted: true)), token: refused, deadline: later)
        #expect(board.speaker("o1")?.muted == true)
        #expect(board.reject(refused, code: "speaker_offline")?.code == "speaker_offline")
        #expect(board.speaker("o1")?.muted == false)
    }

    @Test("a newer command on the same control replaces the one waiting; other controls stay")
    func newerReplaces() {
        var board = S.board(S.speaker())
        let first = UUID(), second = UUID(), mute = UUID()
        let speaker = board.speaker("o1")!
        board.expect(.volume, on: "o1", expected: speaker.expecting(TracksideSpeakerChange(volume: 40)), token: first, deadline: later)
        board.expect(.mute, on: "o1", expected: speaker.expecting(TracksideSpeakerChange(muted: true)), token: mute, deadline: later)
        board.expect(.volume, on: "o1", expected: speaker.expecting(TracksideSpeakerChange(volume: 70)), token: second, deadline: later)
        #expect(board.speaker("o1")?.volume == 70)
        #expect(board.speaker("o1")?.muted == true)
        // The first's answer and failure come late, and change nothing.
        board.accept(first, command: S.command(S.speaker(volume: 40)), deadline: later)
        #expect(board.reject(first, code: nil) == nil)
        #expect(board.speaker("o1")?.volume == 70)
    }

    @Test("an unanswered command is dropped at its deadline, and an unanswered cast is a failure")
    func deadlines() {
        var board = S.board(S.speaker(), S.speaker("o2", name: "Bedroom", state: .idle))
        board.expect(.transport, on: "o1", expected: board.speaker("o1")!.expecting(.pause, at: S.start), token: UUID(), deadline: S.start.addingTimeInterval(5))
        board.expect(.transport, on: "o2", expected: board.speaker("o2")!.expecting(playing: nil, from: 0, at: S.start), purpose: .cast, token: UUID(), deadline: S.start.addingTimeInterval(15))
        #expect(board.nextDeadline == S.start.addingTimeInterval(5))
        #expect(board.isConnecting("o2"))

        let first = board.expire(at: S.start.addingTimeInterval(6))
        #expect(first.reread == ["o1"])
        #expect(first.failures.isEmpty)
        #expect(board.speaker("o1")?.playback?.state == .playing)

        let second = board.expire(at: S.start.addingTimeInterval(16))
        #expect(second.reread == ["o2"])
        #expect(second.failures.map(\.code) == ["timed_out"])
        #expect(!board.isConnecting("o2"))
        #expect(board.nextDeadline == nil)
    }

    @Test("a speaker going out of reach mid-command drops what was on its way, and the list may vanish")
    func outOfReach() {
        var board = S.board(S.speaker())
        board.expect(.transport, on: "o1", expected: board.speaker("o1")!.expecting(.play, at: S.start), purpose: .cast, token: UUID(), deadline: later)
        board.apply(S.speaker(revision: 11, capabilities: [], availability: .unavailable))
        #expect(!board.hasPending)
        #expect(board.speaker("o1")?.isReachable == false)

        board.setState(TracksideSpeakersState(state: .connecting))
        #expect(board.speakers.count == 1)
        board.setState(TracksideSpeakersState(state: .unavailable, since: S.start))
        #expect(board.speakers.isEmpty)
        #expect(board.state == .unavailable)
    }

    @Test("a fresh list is the truth whatever revision came before, as after the server starts again")
    func freshList() {
        var board = S.board(S.speaker(revision: 900))
        board.apply(S.speaker(volume: 10, revision: 3))
        #expect(board.speaker("o1")?.volume == 35)
        board.load(TracksideSpeakerList(speakers: [S.speaker(volume: 10, revision: 3)]))
        #expect(board.speaker("o1")?.volume == 10)
        board.remove("o1")
        #expect(board.speakers.isEmpty)
    }

    @Test("groups lead, then names in the order a person reads them, as speakers come and go")
    func order() {
        var board = S.board(S.speaker("o3", name: "Études Room"), S.speaker("o1", name: "kitchen"), S.speaker("o9", name: "Upstairs", kind: .group))
        #expect(board.speakers.map(\.name) == ["Upstairs", "Études Room", "kitchen"])
        board.apply(S.speaker("o4", name: "Bathroom"))
        #expect(board.speakers.map(\.name) == ["Upstairs", "Bathroom", "Études Room", "kitchen"])
        board.apply(S.speaker("o4", name: "Zen Den", revision: 11))
        #expect(board.speakers.map(\.name).last == "Zen Den")
    }

    @Test("progress that only moves the song isn't kept on the board; a start or stop is")
    func progress() {
        var board = S.board(S.speaker())
        let moved = TracksideSpeakerProgress(id: "o1", positionMS: 111_000, positionAt: S.start.addingTimeInterval(30), revision: 10, state: .playing)
        #expect(!board.progress(moved))
        let paused = TracksideSpeakerProgress(id: "o1", positionMS: 112_000, positionAt: S.start.addingTimeInterval(31), revision: 10, state: .paused)
        #expect(board.progress(paused))
        #expect(board.speaker("o1")?.isPlaying == false)
    }

    @Test("the place in the song runs on while it plays, from the newer report, and never past the end")
    func interpolation() {
        let board = S.board(S.speaker())
        #expect(board.position(of: "o1", progress: nil, at: S.start.addingTimeInterval(9.5)) == 90.5)
        let report = TracksideSpeakerProgress(id: "o1", positionMS: 120_000, positionAt: S.start.addingTimeInterval(30), revision: 10, state: .playing)
        #expect(board.position(of: "o1", progress: report, at: S.start.addingTimeInterval(32)) == 122)
        let stale = TracksideSpeakerProgress(id: "o1", positionMS: 5_000, positionAt: S.start.addingTimeInterval(-30), revision: 10, state: .playing)
        #expect(board.position(of: "o1", progress: stale, at: S.start.addingTimeInterval(1)) == 82)
        #expect(board.position(of: "o1", progress: nil, at: S.start.addingTimeInterval(3_600)) == 261.64)
        // A clock that's behind the report never runs the song backwards.
        #expect(board.position(of: "o1", progress: nil, at: S.start.addingTimeInterval(-5)) == 81)

        let paused = S.board(S.speaker(state: .paused))
        #expect(paused.position(of: "o1", progress: nil, at: S.start.addingTimeInterval(100)) == 81)

        var seeking = S.board(S.speaker())
        seeking.expect(.transport, on: "o1", expected: seeking.speaker("o1")!.expecting(.seek(200), at: S.start.addingTimeInterval(10)), token: UUID(), deadline: later)
        // While a seek waits, the place it asked for leads, whatever progress says.
        #expect(seeking.position(of: "o1", progress: report, at: S.start.addingTimeInterval(11)) == 201)
        #expect(seeking.speaker("o1")!.expecting(.seek(9_999), at: S.start).playback?.positionMS == 261_640)
    }

    @Test("grouping shows at once with this speaker leading, and none ungroups it")
    func grouping() {
        var board = S.board(S.speaker(canGroupWith: ["o2"]), S.speaker("o2", name: "Bedroom"))
        let token = UUID()
        board.expect(.group, on: "o1", expected: board.speaker("o1")!.expecting(members: ["o2"]), token: token, deadline: later)
        #expect(board.speaker("o1")?.memberIDs == ["o2"])
        #expect(board.speaker("o1")?.isGroupLeader == true)
        #expect(board.speaker("o1")!.expecting(members: []).group == nil)
    }

    @Test("a speaker's line says what it plays, why it doesn't, and who plays along")
    func words() {
        #expect(S.speaker().statusLine() == "Halo · Beyoncé")
        #expect(S.speaker(state: .paused).statusLine() == "Paused · Halo · Beyoncé")
        #expect(S.speaker().statusLine(memberNames: ["Living Room"]) == "With Living Room · Halo · Beyoncé")
        #expect(S.speaker(availability: .unavailable).statusLine() == "Not Available")
        #expect(S.speaker(availability: .poweredOff).statusLine() == "Off")
        var idle = S.speaker()
        idle.nowPlaying = nil
        #expect(idle.statusLine() == "Not Playing")

        #expect(S.speaker(availability: .poweredOff).canChoose(hasMusicHere: true))
        #expect(!S.speaker(capabilities: [.power], availability: .poweredOff).canChoose(hasMusicHere: true))
        #expect(!S.speaker(availability: .unavailable).canChoose(hasMusicHere: true))
        #expect(idle.canChoose(hasMusicHere: true))
        #expect(!idle.canChoose(hasMusicHere: false))
    }

    @Test("a queue goes to a speaker from the song playing, without the songs its server can't play")
    func castRequest() {
        let request = SpeakerCast.request(items: [.song("s1"), nil, .song("s3")], position: 42.25, repeatMode: .all, context: nil)
        #expect(request?.items == [.song("s1"), .song("s3")])
        #expect(request?.positionMS == 42_250)
        #expect(request?.startIndex == 0)
        #expect(request?.repeatMode == .all)
        #expect(request?.shuffle == false)
        #expect(SpeakerCast.request(items: [nil, .song("s2")], position: 0, repeatMode: .off, context: nil) == nil)
        #expect(SpeakerCast.request(items: [], position: 0, repeatMode: .off, context: nil) == nil)
        let long = SpeakerCast.request(items: Array(repeating: .song("s"), count: 1_500), position: 0, repeatMode: .off, context: nil)
        #expect(long?.items.count == TracksideSpeakerPlayRequest.itemLimit)
    }
}

import Testing
import Foundation
@testable import TracksCore

@Suite("Controlling another of your devices")
struct NearbyRemoteTests {
    let now = Date(timeIntervalSinceReferenceDate: 800_000_000)
    let session = UUID()
    let juno = SongIdentity.key(title: "Juno", artistName: "Sabrina Carpenter")

    private func request(_ number: Int, _ action: RemoteAction, song: String? = nil, sentAt: Date? = nil, session: UUID? = nil) -> RemoteRequest {
        RemoteRequest(session: session ?? self.session, number: number, action: action, song: song ?? juno, sentAt: sentAt ?? now)
    }

    private var playing: NearbyState {
        NearbyState(title: "Juno", artist: "Sabrina Carpenter", isPlaying: true, position: 40, duration: 223, sentAt: now)
    }

    // MARK: - On the wire

    @Test("asks and answers come back as they went", arguments: [
        NearbyMessage.hello(id: "A1", name: "Luke's iPhone", platform: "iPhone", kind: "iPhone", remote: 1),
        .request(RemoteRequest(session: UUID(), number: 3, action: .seek(84.5), song: "juno", sentAt: Date(timeIntervalSinceReferenceDate: 800_000_000))),
        .request(RemoteRequest(session: UUID(), number: 4, action: .pause, song: nil, sentAt: Date(timeIntervalSinceReferenceDate: 800_000_000))),
        .reply(RemoteReply(id: UUID(), outcome: .stale, state: NearbyState(title: "Juno", isPlaying: false, position: 12))),
        .reply(RemoteReply(id: UUID(), outcome: .done, state: nil)),
    ])
    func roundTrip(message: NearbyMessage) throws {
        let framed = try NearbyFraming.frame(message)
        #expect(try NearbyFraming.message(from: framed.dropFirst(4)) == message)
    }

    @Test("a hello from a device before remote control reads as understanding none")
    func helloWithoutRemote() throws {
        let old = Data(#"{"hello":{"id":"A1","name":"Luke's iPhone","platform":"iPhone","kind":"iPhone"}}"#.utf8)
        #expect(try NearbyFraming.message(from: old) == .hello(id: "A1", name: "Luke's iPhone", platform: "iPhone", kind: "iPhone", remote: nil))
    }

    @Test("an answer from a later version, with an outcome this one doesn't know, still reads as done")
    func unknownOutcome() throws {
        let id = UUID()
        let later = Data(#"{"reply":{"_0":{"id":"\#(id.uuidString)","outcomeName":"queued"}}}"#.utf8)
        guard case .reply(let reply) = try NearbyFraming.message(from: later) else {
            Issue.record("Not a reply")
            return
        }
        #expect(reply.outcome == .done)
    }

    // MARK: - On the device that plays

    @Test("asks are done in order, each once, and an overtaken one is let go")
    func ordering() {
        var ledger = RemoteLedger()
        var verdict: RemoteLedger.Verdict
        verdict = ledger.admit(request(1, .pause), playing: juno, at: now)
        #expect(verdict == .apply)
        verdict = ledger.admit(request(1, .pause), playing: juno, at: now)
        #expect(verdict == .repeated)
        verdict = ledger.admit(request(3, .play), playing: juno, at: now)
        #expect(verdict == .apply)
        verdict = ledger.admit(request(2, .pause), playing: juno, at: now)
        #expect(verdict == .stale)
        verdict = ledger.admit(request(4, .next), playing: juno, at: now)
        #expect(verdict == .apply)
    }

    @Test("a reopened app starts numbering again, and is heard")
    func newSession() {
        var ledger = RemoteLedger()
        var verdict: RemoteLedger.Verdict
        verdict = ledger.admit(request(9, .pause), playing: juno, at: now)
        #expect(verdict == .apply)
        verdict = ledger.admit(request(1, .play, session: UUID()), playing: juno, at: now)
        #expect(verdict == .apply)
        verdict = ledger.admit(request(10, .play), playing: juno, at: now)
        #expect(verdict == .apply)
    }

    @Test("an ask held up too long is stale, and so is its repeat")
    func tooOld() {
        var ledger = RemoteLedger()
        var verdict: RemoteLedger.Verdict
        let late = request(1, .play, sentAt: now.addingTimeInterval(-RemoteLedger.oldest - 1))
        verdict = ledger.admit(late, playing: juno, at: now)
        #expect(verdict == .stale)
        verdict = ledger.admit(late, playing: juno, at: now)
        #expect(verdict == .repeated)
        verdict = ledger.admit(request(2, .play, sentAt: now.addingTimeInterval(-2)), playing: juno, at: now)
        #expect(verdict == .apply)
    }

    @Test("a seek in a song that's no longer on is stale; a skip isn't tied to its song")
    func songChanged() {
        var ledger = RemoteLedger()
        var verdict: RemoteLedger.Verdict
        let other = SongIdentity.key(title: "Espresso", artistName: "Sabrina Carpenter")
        verdict = ledger.admit(request(1, .seek(30)), playing: other, at: now)
        #expect(verdict == .stale)
        verdict = ledger.admit(request(2, .seek(30)), playing: juno, at: now)
        #expect(verdict == .apply)
        verdict = ledger.admit(request(3, .next), playing: other, at: now)
        #expect(verdict == .apply)
    }

    @Test("only the latest runs are remembered")
    func boundedMemory() {
        var ledger = RemoteLedger()
        var verdict: RemoteLedger.Verdict
        let first = UUID()
        verdict = ledger.admit(request(5, .play, session: first), playing: juno, at: now)
        #expect(verdict == .apply)
        for _ in 0..<RemoteLedger.sessionsKept {
            _ = ledger.admit(request(1, .play, session: UUID()), playing: juno, at: now)
        }
        verdict = ledger.admit(request(5, .play, session: first), playing: juno, at: now)
        #expect(verdict == .apply)
    }

    // MARK: - On the device that asks

    @Test("a click shows at once, and the answer has the last word")
    func optimisticThenReconciled() throws {
        var control = RemoteControl(session: session, reported: playing)
        let pause = control.ask(.pause, at: now.addingTimeInterval(10))
        #expect(pause.number == 1)
        #expect(pause.song == juno)
        let shown = try #require(control.shown)
        #expect(!shown.isPlaying)
        #expect(shown.position(at: now.addingTimeInterval(60)) == 50)

        let truth = NearbyState(title: "Juno", artist: "Sabrina Carpenter", isPlaying: false, position: 50.4, duration: 223, sentAt: now.addingTimeInterval(10.3))
        control.answer(RemoteReply(id: pause.id, outcome: .done, state: truth))
        #expect(!control.isWaiting)
        #expect(control.shown == truth)
    }

    @Test("quick clicks on play and pause end where the last one left them")
    func rapidToggles() throws {
        var control = RemoteControl(session: session, reported: playing)
        let first = control.ask(.pause, at: now.addingTimeInterval(1))
        let second = control.ask(.play, at: now.addingTimeInterval(1.2))
        _ = control.ask(.pause, at: now.addingTimeInterval(1.4))
        #expect(first.number < second.number)
        #expect(try #require(control.shown).isPlaying == false)

        // The first answer arrives, still paused from it, with two asks to go.
        control.answer(RemoteReply(id: first.id, outcome: .done, state: NearbyState(title: "Juno", artist: "Sabrina Carpenter", isPlaying: false, position: 41, duration: 223, sentAt: now.addingTimeInterval(1.1))))
        #expect(try #require(control.shown).isPlaying == false)
        #expect(control.isWaiting)
    }

    @Test("a seek shows where it was asked, moving on from there while playing")
    func seek() throws {
        var control = RemoteControl(session: session, reported: playing)
        _ = control.ask(.seek(500), at: now.addingTimeInterval(2))
        #expect(try #require(control.shown).position(at: now.addingTimeInterval(2)) == 223)
        var fresh = RemoteControl(session: session, reported: playing)
        _ = fresh.ask(.seek(100), at: now.addingTimeInterval(2))
        #expect(try #require(fresh.shown).position(at: now.addingTimeInterval(5)) == 103)
    }

    @Test("an ask unanswered past patience says the device can't be reached, until it speaks again")
    func timeout() throws {
        var control = RemoteControl(session: session, reported: playing)
        _ = control.ask(.next, at: now)
        #expect(control.isSkipping)
        let expired460 = control.expire(at: now.addingTimeInterval(RemoteControl.patience - 0.1))
        #expect(!expired460)
        #expect(!control.isUnreachable)
        let expired608 = control.expire(at: now.addingTimeInterval(RemoteControl.patience))
        #expect(expired608)
        #expect(control.isUnreachable)
        #expect(!control.isSkipping)
        #expect(control.shown == playing)

        var later = playing
        later.sentAt = now.addingTimeInterval(8)
        control.report(later)
        #expect(!control.isUnreachable)
    }

    @Test("news older than what's shown is let go")
    func staleReport() {
        var control = RemoteControl(session: session, reported: playing)
        var older = playing
        older.sentAt = now.addingTimeInterval(-5)
        older.title = "Espresso"
        control.report(older)
        #expect(control.reported == playing)
    }

    @Test("a stale answer still brings the truth with it")
    func staleAnswer() throws {
        var control = RemoteControl(session: session, reported: playing)
        let seek = control.ask(.seek(120), at: now.addingTimeInterval(1))
        let moved = NearbyState(title: "Espresso", artist: "Sabrina Carpenter", isPlaying: true, position: 2, duration: 175, sentAt: now.addingTimeInterval(1.5))
        control.answer(RemoteReply(id: seek.id, outcome: .stale, state: moved))
        #expect(control.shown == moved)
    }
}

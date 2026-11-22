import Testing
@testable import TracksCore

@MainActor
@Suite("What a play request asks of the player on the way")
struct PlayStartTests {
    @Test func `the queued answer comes once, however often the player says so`() async {
        var calls = 0
        await PlayStart.run(whenQueued: { calls += 1 }) {
            PlayStart.queued()
            PlayStart.queued()
        }
        #expect(calls == 1)
    }

    @Test func `access is asked for unless the request says not to`() async {
        #expect(PlayStart.mayAskForAccess)
        let inside = await PlayStart.run(mayAskForAccess: false) { PlayStart.mayAskForAccess }
        #expect(!inside)
    }

    @Test func `work that outlives the request doesn't carry its wishes on`() async {
        var calls = 0
        let (go, letGo) = AsyncStream<Void>.makeStream()
        let outliving = await PlayStart.run(mayAskForAccess: false, whenQueued: { calls += 1 }) {
            Task { @MainActor () -> Bool in
                for await _ in go { break }
                PlayStart.queued()
                return PlayStart.mayAskForAccess
            }
        }
        letGo.yield()
        #expect(await outliving.value)
        #expect(calls == 0)
    }
}

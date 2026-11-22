import Testing
@testable import TracksMusic

@MainActor
@Suite("Player work waits out scene changes", .serialized)
struct PlayerQuietTests {
    @Test("work asked for during a scene change runs once it settles, in order, before anyone waiting", .timeLimit(.minutes(1)))
    func holdsThenRunsInOrder() async {
        var ran: [Int] = []
        PlayerQuiet.hold()
        #expect(PlayerQuiet.isQuiet)
        PlayerQuiet.run { ran.append(1) }
        PlayerQuiet.run { ran.append(2) }
        #expect(ran.isEmpty)

        await PlayerQuiet.settled()
        #expect(ran == [1, 2])
        #expect(!PlayerQuiet.isQuiet)

        PlayerQuiet.run { ran.append(3) }
        #expect(ran == [1, 2, 3], "settled, work runs straight away")
    }

    @Test("each scene joined holds player work, not only the first, as the car connecting after launch does", .timeLimit(.minutes(1)))
    func everySceneHolds() async {
        PlayerQuiet.followScenes()
        await PlayerQuiet.settled()
        #expect(!PlayerQuiet.isQuiet)

        PlayerQuiet.followScenes()
        #expect(PlayerQuiet.isQuiet)
        await PlayerQuiet.settled()
    }
}

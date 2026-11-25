import Testing
@testable import TracksCore

@Suite("Following a player through its part of a queue")
struct QueueRunPositionTests {
    let queue = ["a", "b", "c", "d"]

    @Test("on to the next song")
    func next() {
        #expect(QueueRunPosition.follow(identities: queue, run: 0..<4, current: 1, playing: "c", left: 1) == 2)
    }

    @Test("back a song within the run, which the queue used to keep on the song left")
    func previous() {
        #expect(QueueRunPosition.follow(identities: queue, run: 0..<4, current: 2, playing: "b", left: 2) == 1)
        // A run that starts part way through the queue.
        #expect(QueueRunPosition.follow(identities: ["x", "a", "b", "c"], run: 1..<4, current: 2, playing: "a", left: 2) == 1)
    }

    @Test("the same song twice in a row moves on by how many are left")
    func sameSongTwice() {
        let twice = ["a", "b", "b", "c"]
        #expect(QueueRunPosition.follow(identities: twice, run: 0..<4, current: 1, playing: "b", left: 1) == 2)
        #expect(QueueRunPosition.follow(identities: twice, run: 0..<4, current: 2, playing: "b", left: 2) == 1)
    }

    @Test("stays put while the songs after it are handed over again")
    func handingOver() {
        #expect(QueueRunPosition.follow(identities: queue, run: 0..<4, current: 1, playing: "b", left: 0) == 1)
    }

    @Test("a song its player names differently is placed by how many are left")
    func namedDifferently() {
        #expect(QueueRunPosition.follow(identities: queue, run: 0..<4, current: 0, playing: "b (remastered)", left: 1) == 2)
        #expect(QueueRunPosition.follow(identities: queue, run: 0..<4, current: 0, playing: "?", left: 99) == 0)
    }
}

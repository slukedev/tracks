import Foundation
import Testing
@testable import MotifStats

struct ListenQueueTests {
    private func listen(duration: Double?, playedFor: Double) -> Listen {
        Listen(title: "Moth", artist: "Wren & Ivy", startedAt: 1_790_000_000, duration: duration, playedFor: playedFor, songID: "tr-1", serverID: "home")
    }

    @Test func halfASongCounts() {
        #expect(listen(duration: 200, playedFor: 100).counts)
        #expect(!listen(duration: 200, playedFor: 60).counts)
    }

    @Test func fourMinutesOfALongSongCounts() {
        #expect(listen(duration: 900, playedFor: 240).counts)
    }

    @Test func aJingleNeverCounts() {
        #expect(!listen(duration: 25, playedFor: 25).counts)
    }

    @Test func withoutALengthThirtySecondsCounts() {
        #expect(listen(duration: nil, playedFor: 30).counts)
        #expect(!listen(duration: nil, playedFor: 12).counts)
    }

    @Test func onlyListensThatCountBecomePlays() {
        let plays = ListenQueue.plays(from: [listen(duration: 200, playedFor: 150), listen(duration: 200, playedFor: 20)])
        #expect(plays.count == 1)
        #expect(plays.first?.songID == "tr-1")
        #expect(plays.first?.stat.songID == "tr-1")
    }

    @Test func readsTheServicesLinesAndSkipsBrokenOnes() {
        let text = """
        {"title":"Moth","artist":"Wren & Ivy","startedAt":1790000000.5,"duration":212.4,"playedFor":120,"songID":"tr-1","serverID":"home"}
        not json
        {"title":"Open Ocean","artist":"Mara Solis","album":"Tides","startedAt":1790000300,"playedFor":45}
        """
        let listens = ListenQueue.parse(text)
        #expect(listens.count == 2)
        #expect(listens[1].album == "Tides")
        #expect(listens[1].duration == nil)
    }

    @Test func drainingEmptiesTheQueue() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent(ListenQueue.fileName)
        try Data(#"{"title":"Moth","artist":"Wren & Ivy","startedAt":1790000000,"playedFor":120}"#.utf8).write(to: url)

        #expect(ListenQueue.drain(at: url).count == 1)
        #expect(ListenQueue.drain(at: url).isEmpty)
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
    }
}

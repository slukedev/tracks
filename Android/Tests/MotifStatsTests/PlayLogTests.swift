import Foundation
import Testing
@testable import MotifStats

struct PlayLogTests {
    private let noon = Date(timeIntervalSince1970: 1_790_000_000)

    private func play(_ title: String, at offset: TimeInterval, artist: String = "Mara Solis") -> Play {
        Play(title: title, artistName: artist, playedAt: noon.addingTimeInterval(offset))
    }

    @Test func aSongReportedTwiceIsOnePlay() {
        var log = PlayLog()
        #expect(log.add([play("Open Ocean", at: 0)]) == 1)
        #expect(log.add([play("Open Ocean", at: 30)]) == 0)
        #expect(log.plays.count == 1)
    }

    @Test func aSongPlayedAgainLaterIsTwoPlays() {
        var log = PlayLog()
        log.add([play("Open Ocean", at: 0), play("Open Ocean", at: 240)])
        #expect(log.plays.count == 2)
    }

    @Test func differentSongsAtTheSameTimeAreBothKept() {
        var log = PlayLog()
        log.add([play("Open Ocean", at: 0), play("Slow Burn", at: 0)])
        #expect(log.plays.count == 2)
    }

    @Test func playsStayOldestFirst() {
        var log = PlayLog()
        log.add([play("Later", at: 600)])
        log.add([play("Earlier", at: 0)])
        #expect(log.plays.map(\.title) == ["Earlier", "Later"])
    }

    @Test func survivesARoundTripThroughTheFile() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("plays.json")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }

        var log = PlayLog()
        log.add([
            Play(title: "Moth", artistName: "Wren & Ivy", albumTitle: "Lanterns", playedAt: noon, songID: "tr-9", serverID: "home"),
            Play(title: "Weightless", artistName: "Paper Moons", playedAt: noon.addingTimeInterval(300), kind: .lastFM),
        ])
        try log.save(to: url)

        let loaded = try PlayLog.load(from: url)
        #expect(loaded.plays == log.plays)
        #expect(loaded.plays.last?.kind == .lastFM)
    }

    @Test func aMissingFileIsAnEmptyLog() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json")
        #expect(try PlayLog.load(from: url).plays.isEmpty)
    }

    @Test func samplesFeedTheSameStatisticsAsTheOtherApps() {
        let now = Date(timeIntervalSince1970: 1_790_000_000)
        let log = PlayLog(plays: Play.samples(endingAt: now))
        let summary = StatsCalculator.summary(range: .month, history: log.history(), sessions: [], now: now)
        #expect(summary.captureCount > 100)
        #expect(!summary.topArtists.isEmpty)
        #expect(summary.streak.current >= 14)
    }
}

import Foundation
import Testing
@testable import TracksCore

/// The store keeps exact copies of a play on purpose (one history brought over on two devices
/// at once), and the Swift calculator counts each. The engine's charts must count them too.
@Suite("History engine copies of a play")
struct HistoryEngineCopiesTests {
    private static let heard = Date(timeIntervalSince1970: 1_790_000_000)

    private static func play(_ title: String, at offset: TimeInterval = 0) -> CaptureStat {
        CaptureStat(
            songKey: title,
            songID: "",
            title: title,
            artistName: "Mara Solís",
            albumTitle: "Home",
            artworkURL: nil,
            capturedAt: heard.addingTimeInterval(offset),
            kind: .onDemand,
            source: .appleMusic
        )
    }

    private static func counts(_ engine: HistoryEngine) throws -> [String: Int] {
        let chart = try engine.songChart(in: ChartPeriod(span: .allTime, interval: nil), scope: .all)
        return Dictionary(uniqueKeysWithValues: chart.map { ($0.item.title, $0.item.count) })
    }

    @Test("a full sync counts every exact copy, as the Swift calculator does")
    func fullSyncCountsCopies() throws {
        let engine = try #require(HistoryEngine.inMemory())
        let captures = [Self.play("Glow"), Self.play("Glow"), Self.play("Glow"), Self.play("Tide", at: 300)]

        engine.sync(captures)

        let swift = StatsCalculator.songChart(in: ChartPeriod(span: .allTime, interval: nil), history: ListeningHistory(captures))
        #expect(try Self.counts(engine) == Dictionary(uniqueKeysWithValues: swift.map { ($0.item.title, $0.item.count) }))
        #expect(try Self.counts(engine) == ["Glow": 3, "Tide": 1])
    }

    @Test("a sync of the same copies again writes nothing")
    func resyncIsUnchanged() throws {
        let engine = try #require(HistoryEngine.inMemory())
        let captures = [Self.play("Glow"), Self.play("Glow")]
        engine.sync(captures)
        engine.sync(captures)
        #expect(try Self.counts(engine) == ["Glow": 2])
        #expect(try engine.playCount() == 2)
    }

    @Test("a copy that arrives in a step after the index holds several is counted")
    func stepAddsAnotherCopy() throws {
        let engine = try #require(HistoryEngine.inMemory())
        engine.sync([Self.play("Glow"), Self.play("Glow")])

        let current = engine.apply(.steps([.init(added: [Self.play("Glow")], changed: [])], sessions: []))

        #expect(current)
        #expect(try Self.counts(engine) == ["Glow": 3])
    }

    @Test("renaming one copy moves only that copy")
    func renamingOneCopy() throws {
        let engine = try #require(HistoryEngine.inMemory())
        engine.sync([Self.play("Glow"), Self.play("Glow"), Self.play("Glow")])

        engine.apply(.steps([.init(added: [], changed: [(old: Self.play("Glow"), new: Self.play("Glow (Live)"))])], sessions: []))

        #expect(try Self.counts(engine) == ["Glow": 2, "Glow (Live)": 1])
    }
}

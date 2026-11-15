import Testing
import Foundation
@testable import TracksCore

@Suite("Restoring the history from iCloud")
struct HistoryRestoreTests {
    private let start = Date(timeIntervalSince1970: 1_000_000)

    private func at(_ seconds: TimeInterval) -> Date { start.addingTimeInterval(seconds) }

    @Test("with no count from another device, Tracks looks for a while, then takes it as new")
    func lookingThenNothing() {
        #expect(HistoryRestore.resolve(expected: nil, restored: 0, startedAt: start, lastArrival: nil, blocked: nil, now: at(5)) == .looking)
        #expect(HistoryRestore.resolve(expected: nil, restored: 0, startedAt: start, lastArrival: nil, blocked: nil, now: at(25)) == .nothingToRestore)
    }

    @Test("restoring counts toward the other device's number")
    func restoringProgress() throws {
        let phase = HistoryRestore.resolve(expected: 12_400, restored: 3_100, startedAt: start, lastArrival: at(10), blocked: nil, now: at(12))
        #expect(phase == .restoring(restored: 3_100, expected: 12_400))
        #expect(phase.title == "Restoring 12,400 plays from iCloud")
        #expect(phase.detail == "3,100 of 12,400")
        #expect(try #require(phase.fraction) == 0.25)
    }

    @Test("nothing arriving for a while reads as slow, not stuck")
    func slow() {
        let phase = HistoryRestore.resolve(expected: 12_400, restored: 3_100, startedAt: start, lastArrival: at(10), blocked: nil, now: at(70))
        #expect(phase == .slow(restored: 3_100, expected: 12_400))
        #expect(!phase.isSettled)
    }

    /// Two devices' counts differ by the duplicate rows sync folds away, so the last few
    /// percent may never arrive.
    @Test("close to the count is all of it", arguments: [(12_200, 12_400, 15.0), (11_500, 12_400, 200.0)])
    func finishesNearTheCount(restored: Int, expected: Int, quiet: TimeInterval) {
        let phase = HistoryRestore.resolve(expected: expected, restored: restored, startedAt: start, lastArrival: at(10), blocked: nil, now: at(10 + quiet))
        #expect(phase == .finished(restored: restored))
        #expect(phase.isSettled)
    }

    @Test("a short restore that stops short isn't called done", arguments: [3_000])
    func farShortStaysSlow(restored: Int) {
        let phase = HistoryRestore.resolve(expected: 12_400, restored: restored, startedAt: start, lastArrival: at(10), blocked: nil, now: at(500))
        #expect(phase == .slow(restored: restored, expected: 12_400))
    }

    @Test("a reason iCloud can't restore wins over the counts")
    func blocked() {
        let phase = HistoryRestore.resolve(expected: 12_400, restored: 0, startedAt: start, lastArrival: nil, blocked: "Sign in to iCloud to sync your history.", now: at(1))
        #expect(phase == .unavailable("Sign in to iCloud to sync your history."))
        #expect(phase.fraction == nil)
    }

    @Test("plays arriving with no count to aim for show no bar, then finish when they stop")
    func arrivingWithoutCount() {
        let arriving = HistoryRestore.resolve(expected: nil, restored: 800, startedAt: start, lastArrival: at(30), blocked: nil, now: at(40))
        #expect(arriving == .arriving(restored: 800))
        #expect(arriving.fraction == nil)
        #expect(arriving.detail == "800 plays so far")
        #expect(HistoryRestore.resolve(expected: nil, restored: 800, startedAt: start, lastArrival: at(30), blocked: nil, now: at(90)) == .finished(restored: 800))
    }

    @Test("one play reads in the singular")
    func singular() {
        #expect(HistoryRestore.restoring(restored: 0, expected: 1).title == "Restoring 1 play from iCloud")
        #expect(HistoryRestore.finished(restored: 1).title == "1 play restored")
    }

    @Test("a device notes its count only when it has moved")
    func beaconNotes() {
        let cloud = InMemoryKeyValueStore()
        let beacon = HistoryCountBeacon(cloud: cloud)
        beacon.note(0)
        #expect(beacon.noted == nil)
        beacon.note(12_400, now: start)
        #expect(beacon.noted == 12_400)
        beacon.note(12_410, now: at(60))
        #expect(beacon.noted == 12_400)
        beacon.note(12_600, now: at(120))
        #expect(beacon.noted == 12_600)
    }
}

@Suite("Getting started's words")
struct OnboardingWordsTests {
    @Test("Apple Music says where access stands, and when the account can't play the catalog")
    func appleMusic() {
        #expect(OnboardingWords.appleMusic(.notAsked, canPlayCatalog: nil) == "Play Apple Music in Tracks")
        #expect(OnboardingWords.appleMusic(.allowed, canPlayCatalog: nil) == "Allowed")
        #expect(OnboardingWords.appleMusic(.allowed, canPlayCatalog: true) == "Allowed")
        #expect(OnboardingWords.appleMusic(.allowed, canPlayCatalog: false) == "Allowed · No subscription")
        #expect(OnboardingWords.appleMusic(.denied, canPlayCatalog: nil) == "Access is off")
    }

    @Test("Last.fm names the account, and a failure in its own words")
    func lastFM() {
        #expect(OnboardingWords.lastFM(connectedAs: "marasolis", isWaiting: false, failure: nil) == "Connected as marasolis")
        #expect(OnboardingWords.lastFM(connectedAs: nil, isWaiting: true, failure: nil) == "Waiting for Last.fm…")
        #expect(OnboardingWords.lastFM(connectedAs: nil, isWaiting: false, failure: "Last.fm didn’t answer.") == "Last.fm didn’t answer.")
        #expect(OnboardingWords.lastFM(connectedAs: nil, isWaiting: false, failure: nil) == "Scrobble what you play")
    }

    @Test("Your Music names the servers connected")
    func yourMusic() {
        #expect(OnboardingWords.yourMusic(servers: []) == "Play music from your own server")
        #expect(OnboardingWords.yourMusic(servers: ["Living Room"]) == "Connected to Living Room")
        #expect(OnboardingWords.yourMusic(servers: ["Living Room", "Studio"]) == "Connected to Living Room and Studio")
    }

    /// The record is read by later builds, and written by earlier ones.
    @Test("a record with fields missing or unknown still reads")
    func recordReadsAcrossBuilds() throws {
        let older = try JSONDecoder().decode(OnboardingRecord.self, from: Data(#"{"completedVersion":1}"#.utf8))
        #expect(older == OnboardingRecord(completedVersion: 1, restoreFinished: false))
        let newer = try JSONDecoder().decode(OnboardingRecord.self, from: Data(#"{"completedVersion":2,"restoreFinished":true,"seenTips":3}"#.utf8))
        #expect(!newer.needsWelcome)
        #expect(newer.restoreFinished)
    }
}

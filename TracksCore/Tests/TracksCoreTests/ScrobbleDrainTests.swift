import Testing
import Foundation
import SwiftData
@testable import TracksCore

/// Reading what Last.fm did with each scrobble. It answers 200 even when it ignored some.
@Suite("Last.fm scrobble response")
struct ScrobbleResponseTests {

    /// A two-scrobble batch as Last.fm sends it: an array, codes as strings, the second
    /// ignored for being too old.
    static let batchWithOneIgnored = #"""
    {"scrobbles":{"scrobble":[{"artist":{"corrected":"0","#text":"BANKS"},"album":{"corrected":"0","#text":"Off With Her Head"},"track":{"corrected":"0","#text":"Love Is Unkind"},"ignoredMessage":{"code":"0","#text":""},"albumArtist":{"corrected":"0","#text":""},"timestamp":"1700000000"},{"artist":{"corrected":"0","#text":"Mk.gee"},"album":{"corrected":"0","#text":""},"track":{"corrected":"0","#text":"You"},"ignoredMessage":{"code":"3","#text":"Timestamp too old"},"albumArtist":{"corrected":"0","#text":""},"timestamp":"1700000060"}],"@attr":{"ignored":1,"accepted":1}}}
    """#

    /// One scrobble comes back as a lone object, not an array of one.
    static let single = #"""
    {"scrobbles":{"scrobble":{"artist":{"corrected":"0","#text":"BANKS"},"album":{"corrected":"0","#text":""},"track":{"corrected":"0","#text":"Love Is Unkind"},"ignoredMessage":{"code":"0","#text":""},"albumArtist":{"corrected":"0","#text":""},"timestamp":"1700000000"},"@attr":{"ignored":0,"accepted":1}}}
    """#

    private func client(answering body: String) -> LastFMClient {
        LastFMClient(apiKey: "KEY", secret: "SECRET") { request in
            (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
    }

    private let session = LastFMSession(username: "example", sessionKey: "sk")
    private let played = Date(timeIntervalSince1970: 1_700_000_000)

    @Test("each entry in a batch gets its own outcome, in the order sent")
    func readsABatch() async throws {
        let outcomes = try await client(answering: Self.batchWithOneIgnored).submit(
            [
                Scrobble(artist: "BANKS", track: "Love Is Unkind", playedAt: played),
                Scrobble(artist: "Mk.gee", track: "You", playedAt: played.addingTimeInterval(60)),
            ],
            session: session
        )
        #expect(outcomes == [.accepted, .ignored(code: 3, message: "Timestamp too old")])
    }

    @Test("a single scrobble's object is read like a batch of one")
    func readsASingleObject() async throws {
        let outcomes = try await client(answering: Self.single).submit(
            [Scrobble(artist: "BANKS", track: "Love Is Unkind", playedAt: played)],
            session: session
        )
        #expect(outcomes == [.accepted])
    }

    /// `scrobble` used to report everything it sent as accepted.
    @Test("the count only includes what was accepted")
    func countsAccepted() async throws {
        let accepted = try await client(answering: Self.batchWithOneIgnored).scrobble(
            [
                Scrobble(artist: "BANKS", track: "Love Is Unkind", playedAt: played),
                Scrobble(artist: "Mk.gee", track: "You", playedAt: played.addingTimeInterval(60)),
            ],
            session: session
        )
        #expect(accepted == 1)
    }

    @Test("codes sent as numbers are read too")
    func numericCodes() throws {
        let json = try #require(try JSONSerialization.jsonObject(with: Data(#"""
        {"scrobbles":{"scrobble":[{"ignoredMessage":{"code":0,"#text":""}},{"ignoredMessage":{"code":1,"#text":"Artist name failed filter"}}],"@attr":{"ignored":1,"accepted":1}}}
        """#.utf8)) as? [String: Any])
        let outcomes = try LastFMClient.scrobbleOutcomes(from: json, sent: 2)
        #expect(outcomes == [.accepted, .ignored(code: 1, message: "Artist name failed filter")])
    }

    @Test("entries that can't be lined up are fine when none were ignored")
    func unalignedButNothingIgnored() throws {
        let json: [String: Any] = ["scrobbles": ["@attr": ["ignored": "0", "accepted": "2"]]]
        #expect(try LastFMClient.scrobbleOutcomes(from: json, sent: 2) == [.accepted, .accepted])
    }

    /// Last.fm took the batch. Treating it as unreadable sent it again, scrobbling every
    /// accepted entry twice.
    @Test("entries that can't be lined up, with some ignored, still count as taken")
    func unalignedWithIgnored() throws {
        let json: [String: Any] = ["scrobbles": ["scrobble": [String: Any](), "@attr": ["ignored": 1, "accepted": 1]]]
        #expect(try LastFMClient.scrobbleOutcomes(from: json, sent: 2) == [.accepted, .accepted])
    }

    /// A 200 that doesn't mention the scrobbles used to mark them all sent.
    @Test("a response without a scrobbles report is unreadable")
    func missingReport() {
        #expect(throws: LastFMError.malformedResponse) {
            try LastFMClient.scrobbleOutcomes(from: [:], sent: 2)
        }
    }

    @Test("a 200 that isn't JSON is unreadable, not a success")
    func htmlBody() async {
        await #expect(throws: LastFMError.malformedResponse) {
            try await client(answering: "<html><body>Sign in to Wi-Fi</body></html>").submit(
                [Scrobble(artist: "BANKS", track: "Love Is Unkind", playedAt: played)],
                session: session
            )
        }
    }

    @Test("only the daily limit and a future timestamp are worth sending again")
    func retryableOutcomes() {
        #expect(ScrobbleOutcome.ignored(code: 5, message: "").isRetryable)
        #expect(ScrobbleOutcome.ignored(code: 4, message: "").isRetryable)
        #expect(!ScrobbleOutcome.ignored(code: 3, message: "").isRetryable)
        #expect(!ScrobbleOutcome.ignored(code: 1, message: "").isRetryable)
        #expect(!ScrobbleOutcome.accepted.isRetryable)
    }
}

/// The scrobble queue against a fake Last.fm: what a failure costs the rows, and that drains
/// don't overlap.
@MainActor
@Suite("Scrobble drain", .timeLimit(.minutes(1)))
struct ScrobbleDrainTests {
    let store: TracksStore
    let lastFM = FakeLastFM()
    private let scratch = ScratchDefaults()
    let base = Date(timeIntervalSince1970: 1_700_000_000)
    let clock = TestClock(Date(timeIntervalSince1970: 1_700_000_000))

    init() throws {
        store = try TracksStore(inMemory: true)
    }

    func service(predecessors: [String] = []) -> ScrobbleService {
        let lastFM = lastFM
        let service = ScrobbleService(
            store: store,
            settings: scratch.settings,
            makeClient: { lastFM.client() },
            currentSession: { LastFMSession(username: "example", sessionKey: "sk") },
            predecessors: { predecessors },
            inFlightDefaults: scratch.defaults
        )
        let clock = clock
        service.now = { clock.now }
        return service
    }

    @discardableResult
    func owed(_ titles: String..., by deviceID: String = DeviceIdentity.current) throws -> [Capture] {
        try owed(titles, by: deviceID)
    }

    @discardableResult
    func owed(_ titles: [String], by deviceID: String = DeviceIdentity.current, startingAt start: Date? = nil) throws -> [Capture] {
        let start = start ?? base
        let rows = titles.enumerated().map { index, title in
            Capture(
                songID: title,
                title: title,
                artistName: "An Artist",
                capturedAt: start.addingTimeInterval(Double(index) * 60),
                deviceID: deviceID
            )
        }
        for row in rows { store.context.insert(row) }
        try store.context.save()
        return rows
    }

    /// Every one of these used to cost each of fifty rows an attempt, and three of them lost
    /// the rows for good.
    @Test(
        "a failure that isn't the scrobbles' fault leaves them untouched",
        arguments: [
            FakeLastFM.Failure.status(503, body: "{}"),
            .status(429, body: "{}"),
            .status(200, body: #"{"error":11,"message":"Service Offline"}"#),
            .status(200, body: #"{"error":16,"message":"There was a temporary error processing your request."}"#),
            .status(200, body: #"{"error":29,"message":"Rate Limit Exceeded"}"#),
            // Last.fm's own "try again", which it sends during partial outages.
            .status(200, body: #"{"error":8,"message":"Operation failed - Most likely the backend service failed. Please try again."}"#),
            // A code Tracks doesn't know says nothing about these rows either.
            .status(400, body: #"{"error":99,"message":"Something new"}"#),
            .status(400, body: #"{"error":3,"message":"Invalid Method"}"#),
            .status(403, body: #"{"error":9,"message":"Invalid session key - Please re-authenticate"}"#),
            // A captive portal, a proxy or an edge error page, none of which are Last.fm.
            .status(200, body: "<html>Sign in to Wi-Fi</html>"),
            .status(200, body: "{}"),
            .status(403, body: "<html>Forbidden</html>"),
            .status(404, body: ""),
            .offline,
        ]
    )
    func transientFailuresCostNothing(failure: FakeLastFM.Failure) async throws {
        let rows = try owed("One", "Two")
        lastFM.fail(with: failure)
        let service = service()

        #expect(await service.drain() == 0)

        for row in try store.stored(rows) {
            #expect(row.scrobbledAt == nil)
            #expect(row.scrobbleAttempts == 0)
            #expect(row.lastScrobbleError == nil)
        }
        #expect(service.lastError != nil)
        #expect(try store.context.fetch(TracksStore.pendingScrobbles(includingImported: true)).count == 2)
    }

    @Test("a request Last.fm rejects outright charges the rows an attempt")
    func permanentFailureCharges() async throws {
        var rows = try owed("One", "Two")
        lastFM.fail(with: .status(400, body: #"{"error":6,"message":"Invalid parameters"}"#))

        await service().drain()
        rows = try store.stored(rows)

        #expect(rows.map(\.scrobbleAttempts) == [1, 1])
        // In Tracks' words, without Last.fm's code and message.
        #expect(rows.allSatisfy { $0.lastScrobbleError == "Something went wrong with Last.fm. Try again." })
    }

    /// A server that's down shouldn't get a request from every ten-second poll.
    @Test("after a transient failure the queue waits before asking again")
    func backsOff() async throws {
        let rows = try owed("One")
        lastFM.fail(with: .status(503, body: "{}"))
        let service = service()

        await service.drain()
        await service.drain()
        #expect(lastFM.requests.count == 1)

        // Each failure in a row doubles the wait: 30, then 60, then 120 seconds.
        for (failure, wait) in [(2, 30.0), (3, 60.0)] {
            clock.advance(by: wait - 0.5)
            await service.drain()
            #expect(lastFM.requests.count == failure - 1)
            clock.advance(by: 0.5)
            await service.drain()
            #expect(lastFM.requests.count == failure)
        }
        clock.advance(by: 119.5)
        await service.drain()
        #expect(lastFM.requests.count == 3)

        lastFM.acceptEverything()
        clock.advance(by: 0.5)
        #expect(await service.drain() == 1)
        #expect(try store.stored(rows[0]).scrobbledAt != nil)
        #expect(service.lastError == nil)

        // A success clears the wait, so the next failure waits 30 seconds again.
        try owed("Two")
        lastFM.fail(with: .status(503, body: "{}"))
        await service.drain()
        #expect(lastFM.requests.count == 5)
        clock.advance(by: 30)
        await service.drain()
        #expect(lastFM.requests.count == 6)
    }

    /// Too old (3) is about the scrobble and will never pass. It used to be marked scrobbled.
    @Test("an ignored scrobble is given up on with the reason, and the rest are marked sent")
    func ignoredEntries() async throws {
        var rows = try owed("One", "Two", "Three")
        lastFM.answer { count in FakeLastFM.outcomes([0, 3, 0].prefix(count).map { $0 }) }

        #expect(await service().drain() == 2)
        rows = try store.stored(rows)

        #expect(rows[0].scrobbledAt != nil)
        #expect(rows[2].scrobbledAt != nil)
        #expect(rows[1].scrobbledAt == nil)
        #expect(rows[1].scrobbleAttempts >= TracksStore.maxScrobbleAttempts)
        #expect(rows[1].lastScrobbleError?.contains("Too old") == true)
        #expect(try store.context.fetch(TracksStore.pendingScrobbles(includingImported: true)).isEmpty)
    }

    @Test("a scrobble held back by the daily limit stays owed without an attempt")
    func dailyLimit() async throws {
        var rows = try owed("One", "Two")
        lastFM.answer { _ in FakeLastFM.outcomes([0, 5]) }
        let service = service()

        await service.drain()
        rows = try store.stored(rows)

        #expect(rows[0].scrobbledAt != nil)
        #expect(rows[1].scrobbledAt == nil)
        #expect(rows[1].scrobbleAttempts == 0)
        #expect(service.lastError?.contains("daily") == true)
        // And it waits rather than asking again straight away.
        await service.drain()
        #expect(lastFM.requests.count == 1)
    }

    /// Launch, the capture pump and the housekeeping timer all drain, and used to overlap
    /// while the first was waiting on Last.fm, sending everything twice.
    @Test("overlapping drains send each scrobble once")
    func drainsDontOverlap() async throws {
        let rows = try owed("One", "Two")
        lastFM.acceptEverything()
        lastFM.delay = .milliseconds(50)
        let service = service()

        async let first = service.drain()
        async let second = service.drain()
        async let third = service.drain()
        _ = await (first, second, third)

        let sentTitles = lastFM.requests.flatMap { fields in
            fields.filter { $0.key.hasPrefix("track[") }.map(\.value)
        }
        #expect(sentTitles.sorted() == ["One", "Two"])
        #expect(try store.stored(rows).allSatisfy { $0.scrobbledAt != nil })
    }

    @Test("a row added while a drain is out is sent by the drain that was joined")
    func joinedDrainPicksUpNewRows() async throws {
        try owed("One")
        lastFM.acceptEverything()
        let gate = Gate()
        lastFM.gate = gate
        let service = service()

        let first = Task { await service.drain() }
        try await waitUntil { lastFM.requests.count == 1 }
        let late = Capture(
            songID: "Late", title: "Late", artistName: "An Artist",
            capturedAt: base.addingTimeInterval(600), deviceID: DeviceIdentity.current
        )
        store.context.insert(late)
        try store.context.save()

        async let joined = service.drain()
        gate.open()
        _ = await (first.value, joined)

        #expect(try store.stored(late).scrobbledAt != nil)
    }

    /// Apple's recently-played list covers the whole account, so the iPhone imported songs
    /// the Mac had already scrobbled and sent them again.
    @Test("an import Last.fm already has from another device is not sent again")
    func importAlreadyScrobbled() async throws {
        let found = base.addingTimeInterval(3 * 60 * 60)
        let imported = Capture(
            songID: "1", title: "Love Is Unkind", artistName: "BANKS",
            kind: .imported, capturedAt: found, deviceID: DeviceIdentity.current
        )
        let fresh = Capture(
            songID: "2", title: "You", artistName: "Mk.gee",
            kind: .imported, capturedAt: found, deviceID: DeviceIdentity.current
        )
        store.context.insert(imported)
        store.context.insert(fresh)
        try store.context.save()
        lastFM.history = [(title: "Love Is Unkind", artist: "BANKS", playedAt: base)]
        lastFM.acceptEverything()

        #expect(await service().drain() == 1)

        let sentTitles = lastFM.scrobbleRequests.flatMap { fields in
            fields.filter { $0.key.hasPrefix("track[") }.map(\.value)
        }
        #expect(sentTitles == ["You"])
        #expect(try store.stored(imported).scrobbledAt != nil)
        #expect(try store.stored(fresh).scrobbledAt != nil)
    }

    @Test("Spotify's and other services' plays are scrobbled, but not one Spotify already sent")
    func servicePlaysAreScrobbled() async throws {
        let rows = [
            Capture(songID: "1", title: "Sent by Spotify", artistName: "An Artist", capturedAt: base, source: .spotify),
            Capture(songID: "2", title: "Not Sent", artistName: "An Artist", capturedAt: base.addingTimeInterval(60), source: .spotify),
            Capture(songID: "3", title: "From Tidal", artistName: "An Artist", capturedAt: base.addingTimeInterval(120), source: .unknown("tidal")),
        ]
        for row in rows { store.context.insert(row) }
        try store.context.save()
        // Spotify's own scrobble, a moment after the play began here.
        lastFM.history = [(title: "Sent by Spotify", artist: "An Artist", playedAt: base.addingTimeInterval(20))]
        lastFM.acceptEverything()

        #expect(await service().drain() == 2)

        #expect(lastFM.sentTitles == ["Not Sent", "From Tidal"])
        #expect(try store.stored(rows).allSatisfy { $0.scrobbledAt != nil })
    }

    @Test("Spotify's plays an earlier version left to Spotify are sent now, unless too old for Last.fm")
    func heldSpotifyPlaysAreReleased() async throws {
        let recent = Capture(songID: "1", title: "Held", artistName: "An Artist", capturedAt: base.addingTimeInterval(-60 * 60), source: .spotify)
        let old = Capture(songID: "2", title: "Long Ago", artistName: "An Artist", capturedAt: base.addingTimeInterval(-20 * 24 * 60 * 60), source: .spotify)
        for row in [recent, old] {
            row.holdFromScrobbling(reason: ScrobblePolicy.spotifyReason)
            store.context.insert(row)
        }
        try store.context.save()
        lastFM.acceptEverything()

        #expect(await service().drain() == 1)

        #expect(lastFM.sentTitles == ["Held"])
        // Looked for in the history first, in case Spotify sent it.
        #expect(lastFM.methods.first == "user.getRecentTracks")
        #expect(try store.stored(recent).scrobbledAt != nil)
        #expect(try store.stored(old).isHeldFromScrobbling)
    }

    @Test("an import is held when Last.fm's history can't be read")
    func importWaitsForHistory() async throws {
        let imported = Capture(
            songID: "1", title: "Love Is Unkind", artistName: "BANKS",
            kind: .imported, capturedAt: base, deviceID: DeviceIdentity.current
        )
        store.context.insert(imported)
        try store.context.save()
        lastFM.fail(with: .offline)

        await service().drain()

        #expect(lastFM.scrobbleRequests.isEmpty)
        #expect(try store.stored(imported).scrobbledAt == nil)
        #expect(try store.stored(imported).scrobbleAttempts == 0)
    }

    @Test("a scrobble only accounts for one import of the same song")
    func oneScrobbleOneImport() throws {
        let store = try TracksStore(inMemory: true)
        let rows = (0..<2).map { _ in
            Capture(songID: "1", title: "You", artistName: "Mk.gee", kind: .imported, capturedAt: base)
        }
        for row in rows { store.context.insert(row) }
        try store.context.save()
        func checks(window: TimeInterval) -> [ScrobbleService.HistoryCheck] {
            rows.map {
                ScrobbleService.HistoryCheck(
                    id: $0.persistentModelID, title: $0.title, artistName: $0.artistName,
                    at: base, reach: .foundAfter(window)
                )
            }
        }
        let history = [ScrobbledTrack(title: "you", artistName: "MK.GEE", playedAt: base.addingTimeInterval(-60))]

        #expect(ScrobbleService.alreadyScrobbled(checks(window: 24 * 60 * 60), history: history).count == 1)
        // Outside the window, or after the import found it, is a different play.
        #expect(ScrobbleService.alreadyScrobbled(checks(window: 30), history: history).isEmpty)
        let later = [ScrobbledTrack(title: "You", artistName: "Mk.gee", playedAt: base.addingTimeInterval(60))]
        #expect(ScrobbleService.alreadyScrobbled(checks(window: 24 * 60 * 60), history: later).isEmpty)
    }

    /// Sync can merge a row away while its scrobble is out. Writing to the deleted row trapped.
    @Test("a row deleted while its scrobble is out is skipped")
    func deletedWhileOut() async throws {
        let rows = try owed("Kept", "Merged")
        lastFM.acceptEverything()
        let gate = Gate()
        lastFM.gate = gate
        let service = service()

        let drain = Task { await service.drain() }
        try await waitUntil { lastFM.requests.count == 1 }
        store.context.delete(rows[1])
        try store.context.save()
        gate.open()
        _ = await drain.value

        #expect(try store.stored(rows[0]).scrobbledAt != nil)
        #expect(try store.context.fetch(FetchDescriptor<Capture>()).count == 1)
    }

    // MARK: One bad row

    /// One row Last.fm refuses used to cost every row in its batch an attempt, three times
    /// over, so fifty good plays were given up on.
    @Test("one row Last.fm refuses in a full batch costs only that row")
    func oneBadRowInAFullBatch() async throws {
        let titles = (0..<LastFMClient.batchLimit).map { "Song \($0)" }
        let rows = try owed(titles)
        lastFM.refuseRequests(carrying: "Song 37")

        #expect(await service().drain() == LastFMClient.batchLimit - 1)

        let stored = try store.stored(rows)
        #expect(stored.filter { $0.scrobbledAt == nil }.map(\.title) == ["Song 37"])
        #expect(stored.filter { $0.scrobbleAttempts > 0 }.map(\.title) == ["Song 37"])
        #expect(stored.first { $0.title == "Song 37" }?.scrobbleAttempts == 1)
        // Oldest first still, and nowhere near a request per row.
        #expect(lastFM.taken == titles.filter { $0 != "Song 37" })
        #expect(lastFM.scrobbleRequests.count <= 13)
    }

    @Test("a refused row waits before it's tried alone again, and is given up on after three")
    func refusedRowBacksOffAndIsGivenUp() async throws {
        let rows = try owed("Bad", "Good")
        lastFM.refuseRequests(carrying: "Bad")
        let service = service()

        await service.drain()
        let requests = lastFM.scrobbleRequests.count
        await service.drain()
        #expect(lastFM.scrobbleRequests.count == requests)

        for _ in 0..<2 {
            clock.advance(by: 15 * 60)
            await service.drain()
        }
        let bad = try store.stored(rows[0])
        #expect(bad.scrobbleAttempts == TracksStore.maxScrobbleAttempts)
        #expect(bad.scrobbledAt == nil)
        #expect(try store.stored(rows[1]).scrobbledAt != nil)
        #expect(try store.context.fetch(TracksStore.pendingScrobbles(includingImported: true)).isEmpty)
    }

    @Test("a row with no title or artist is given up on without a request")
    func blankRowIsGivenUp() async throws {
        let blank = Capture(songID: "1", title: "  ", artistName: "An Artist", capturedAt: base, deviceID: DeviceIdentity.current)
        store.context.insert(blank)
        try store.context.save()
        let rows = try owed("Good")
        lastFM.acceptEverything()

        #expect(await service().drain() == 1)

        #expect(lastFM.sentTitles == ["Good"])
        let stored = try store.stored(blank)
        #expect(stored.scrobbledAt == nil)
        #expect(stored.lastScrobbleError == ScrobbleService.unsendableReason)
        #expect(try store.stored(rows[0]).scrobbledAt != nil)
    }

    @Test("an outage partway through splitting a batch charges nothing more")
    func outageWhileSplitting() async throws {
        let rows = try owed("Bad", "Good", "Also Good")
        lastFM.refuseRequests(carrying: "Bad", thenFailWith: .status(200, body: #"{"error":11,"message":"Service Offline"}"#))

        await service().drain()

        #expect(lastFM.scrobbleRequests.count == 2)
        #expect(try store.stored(rows).allSatisfy { $0.scrobbleAttempts == 0 && $0.scrobbledAt == nil })
    }

    // MARK: Answers that never arrived

    /// A timeout after Last.fm stored the batch, as on a cellular handoff, sent it again.
    @Test("a batch Last.fm took but whose answer was lost isn't sent twice")
    func lostAnswerIsNotSentAgain() async throws {
        let rows = try owed("One", "Two")
        lastFM.acceptEverything()
        lastFM.keepsWhatItTakes = true
        lastFM.losesAnswers = true
        let service = service()

        #expect(await service.drain() == 0)
        lastFM.losesAnswers = false
        clock.advance(by: 30)
        await service.drain()

        #expect(lastFM.sentTitles == ["One", "Two"])
        #expect(try store.stored(rows).allSatisfy { $0.scrobbledAt != nil })
        #expect(try store.context.fetch(TracksStore.pendingScrobbles(includingImported: true)).isEmpty)
    }

    /// What was in flight is kept in defaults, so it survives the app being quit or killed
    /// before the answer is recorded.
    @Test("after a relaunch, a batch whose answer was lost is checked before it's sent")
    func lostAnswerSurvivesRelaunch() async throws {
        let rows = try owed("Taken", "Not Taken")
        lastFM.acceptEverything()
        lastFM.losesAnswers = true
        await service().drain()
        // Last.fm only kept the first.
        lastFM.history = [(title: "Taken", artist: "An Artist", playedAt: rows[0].capturedAt)]
        lastFM.losesAnswers = false

        await service().drain()

        #expect(lastFM.sentTitles == ["Taken", "Not Taken", "Not Taken"])
        #expect(try store.stored(rows).allSatisfy { $0.scrobbledAt != nil })
    }

    @Test("a batch that never left the device is sent again without a history check")
    func neverSentIsNotChecked() async throws {
        try owed("One")
        lastFM.fail(with: .offline)
        let service = service()
        await service.drain()
        lastFM.acceptEverything()
        clock.advance(by: 30)

        #expect(await service.drain() == 1)
        #expect(!lastFM.methods.contains("user.getRecentTracks"))
    }

    /// Last.fm sometimes corrects a name, and the history then lists it corrected.
    @Test("a lost answer is matched by its time even when Last.fm corrected the artist")
    func lostAnswerMatchesCorrectedName() async throws {
        let rows = try owed("One")
        lastFM.acceptEverything()
        lastFM.losesAnswers = true
        let service = service()
        await service.drain()
        lastFM.history = [(title: "One", artist: "An Artiste", playedAt: rows[0].capturedAt)]
        lastFM.losesAnswers = false
        clock.advance(by: 30)

        await service.drain()

        #expect(lastFM.sentTitles == ["One"])
        #expect(try store.stored(rows[0]).scrobbledAt != nil)
    }

    // MARK: History reach

    /// The check read only the newest 200 scrobbles, so a busy account's match on page two
    /// went unseen and the import was sent again.
    @Test("an import's match further down the history than one page is found")
    func importCheckReadsEveryPage() async throws {
        let found = base.addingTimeInterval(23 * 60 * 60)
        let imported = Capture(
            songID: "1", title: "Love Is Unkind", artistName: "BANKS",
            kind: .imported, capturedAt: found, deviceID: DeviceIdentity.current
        )
        store.context.insert(imported)
        try store.context.save()
        lastFM.history = [(title: "Love Is Unkind", artist: "BANKS", playedAt: base)]
            + (1...250).map { (title: "Other \($0)", artist: "Someone", playedAt: base.addingTimeInterval(Double($0) * 60)) }
        lastFM.acceptEverything()

        await service().drain()

        #expect(lastFM.scrobbleRequests.isEmpty)
        #expect(try store.stored(imported).scrobbledAt != nil)
    }

    // MARK: Restored and migrated devices

    @Test("a predecessor's rows are left to it for a day, then checked and sent if missing")
    func predecessorRows() async throws {
        let old = try owed(["Sent By Old Phone", "Never Sent"], by: "old-phone")
        let recent = try owed(["Just Now"], by: "old-phone", startingAt: base.addingTimeInterval(-60))
        lastFM.history = [(title: "Sent By Old Phone", artist: "An Artist", playedAt: old[0].capturedAt)]
        lastFM.acceptEverything()
        clock.advance(by: DeviceIdentity.predecessorDelay + 3_600)
        // Back to an hour ago, so "Just Now" is still within the old phone's day.
        for row in recent { row.capturedAt = clock.now.addingTimeInterval(-3_600) }
        try store.context.save()

        await service(predecessors: ["old-phone"]).drain()

        #expect(lastFM.sentTitles == ["Never Sent"])
        #expect(try store.stored(old).allSatisfy { $0.scrobbledAt != nil })
        #expect(try store.stored(recent[0]).scrobbledAt == nil)
    }

    @Test("another device's rows that aren't a predecessor's are never sent")
    func otherDevicesRowsStayTheirs() async throws {
        let rows = try owed(["Mac's Song"], by: "the-mac")
        lastFM.acceptEverything()
        clock.advance(by: DeviceIdentity.predecessorDelay * 2)

        await service(predecessors: ["old-phone"]).drain()

        #expect(lastFM.requests.isEmpty)
        #expect(try store.stored(rows[0]).scrobbledAt == nil)
    }

    // MARK: Retrying

    /// Rows in each state Settings has to tell apart, as the drain leaves them.
    func givenUpRows() throws -> (failed: Capture, tooOld: Capture, ignored: Capture, held: Capture, stale: Capture, elsewhere: Capture) {
        let failed = try owed("Failed")[0]
        failed.scrobbleAttempts = TracksStore.maxScrobbleAttempts
        failed.lastScrobbleError = ScrobbleService.describe(LastFMError.service(code: 6, message: "Invalid parameters"))
        let tooOld = try owed("Too Old")[0]
        tooOld.scrobbleAttempts = TracksStore.maxScrobbleAttempts
        tooOld.lastScrobbleError = ScrobbleService.describeIgnored(code: 3, message: "")
        let ignored = try owed("Ignored")[0]
        ignored.scrobbleAttempts = TracksStore.maxScrobbleAttempts
        ignored.lastScrobbleError = ScrobbleService.describeIgnored(code: 1, message: "")
        let held = try owed("Held")[0]
        held.holdFromScrobbling(reason: ScrobblePolicy.notPlayedEnoughReason)
        let stale = try owed(["Stale"], startingAt: base.addingTimeInterval(-ScrobbleService.lastFMReach - 60))[0]
        stale.scrobbleAttempts = TracksStore.maxScrobbleAttempts
        stale.lastScrobbleError = failed.lastScrobbleError
        let elsewhere = try owed("Elsewhere", by: "another-device")[0]
        elsewhere.scrobbleAttempts = TracksStore.maxScrobbleAttempts
        try store.context.save()
        return (failed, tooOld, ignored, held, stale, elsewhere)
    }

    /// Too old by Last.fm's answer or by its date: no retry gets either through.
    @Test("Settings counts what a retry can send apart from what's too old for Last.fm")
    func failedCounts() async throws {
        _ = try givenUpRows()
        #expect(await service().failedScrobbleCounts() == UnsentScrobbles(retryable: 1, tooOld: 2))
    }

    @Test("a retry sends the plays it can, and leaves the rest as they were")
    func retrySendsWhatItCan() async throws {
        let rows = try givenUpRows()
        lastFM.acceptEverything()
        let service = service()

        #expect(await service.retryFailed() == 1)
        #expect(lastFM.sentTitles == ["Failed"])
        #expect(try store.stored(rows.failed).scrobbledAt != nil)
        for row in try store.stored([rows.tooOld, rows.ignored, rows.held, rows.stale, rows.elsewhere]) {
            #expect(row.scrobbledAt == nil)
            #expect(row.scrobbleAttempts == TracksStore.maxScrobbleAttempts)
        }
        #expect(await service.failedScrobbleCounts() == UnsentScrobbles(retryable: 0, tooOld: 2))
    }

    /// Someone pressing Retry wants it tried now, not after the wait an outage left.
    @Test("a retry doesn't wait out the backoff an earlier failure left")
    func retrySkipsBackoff() async throws {
        let waiting = try owed("Waiting")
        lastFM.fail(with: .status(503, body: "{}"))
        let service = service()
        await service.drain()
        let failed = try owed(["Failed"], startingAt: base.addingTimeInterval(60))[0]
        failed.scrobbleAttempts = TracksStore.maxScrobbleAttempts
        try store.context.save()
        lastFM.acceptEverything()

        #expect(await service.retryFailed() == 1)
        #expect(try store.stored(waiting[0]).scrobbledAt != nil)
        #expect(try store.stored(failed).scrobbledAt != nil)
    }

    @Test("a retry with nothing to send asks nothing of Last.fm")
    func retryWithNothing() async throws {
        let sent = try owed("Sent")[0]
        sent.scrobbledAt = base
        try store.context.save()
        #expect(await service().retryFailed() == 0)
        #expect(lastFM.requests.isEmpty)
    }

    @Test("a predecessor's failed plays are counted and retried here too")
    func retryCoversPredecessors() async throws {
        let row = try owed(["Old Phone"], by: "old-phone", startingAt: base.addingTimeInterval(-DeviceIdentity.predecessorDelay - 60))[0]
        row.scrobbleAttempts = TracksStore.maxScrobbleAttempts
        try store.context.save()
        lastFM.acceptEverything()
        let service = service(predecessors: ["old-phone"])

        #expect(await service.failedScrobbleCounts() == UnsentScrobbles(retryable: 1, tooOld: 0))
        #expect(await service.retryFailed() == 1)
        #expect(try store.stored(row).scrobbledAt != nil)
    }

    // MARK: Clock

    @Test("a wait set while the clock was far ahead doesn't hold the queue once it's put back")
    func clockPutBack() async throws {
        let rows = try owed("One")
        lastFM.fail(with: .status(503, body: "{}"))
        let service = service()
        clock.advance(by: 30 * 24 * 60 * 60)
        await service.drain()
        clock.advance(by: -30 * 24 * 60 * 60)
        lastFM.acceptEverything()

        #expect(await service.drain() == 1)
        #expect(try store.stored(rows[0]).scrobbledAt != nil)
    }
}

// MARK: - Fakes

/// A scriptable Last.fm, answering from the form it was sent.
final class FakeLastFM: @unchecked Sendable {
    enum Failure: Sendable, CustomTestStringConvertible {
        case status(Int, body: String)
        case offline

        var testDescription: String {
            switch self {
            case .status(let status, let body): "HTTP \(status) \(body)"
            case .offline: "offline"
            }
        }
    }

    private let lock = NSLock()
    private var _requests: [[String: String]] = []
    private var respond: @Sendable (Int) throws -> (Int, String) = { _ in (200, "{}") }
    private var _gate: Gate?
    private var _delay: Duration?
    private var _history: [(title: String, artist: String, playedAt: Date)] = []
    private var _refusing: String?
    private var _afterRefusal: Failure?
    private var _taken: [String] = []
    private var _keepsWhatItTakes = false
    private var _losesAnswers = false

    /// What `user.getRecentTracks` lists, newest first.
    var history: [(title: String, artist: String, playedAt: Date)] {
        get { lock.withLock { _history } }
        set { lock.withLock { _history = newValue } }
    }

    /// Refuses, as Last.fm does with error 6, any request carrying a scrobble of `title`,
    /// and takes every other.
    ///
    /// - Parameter failure: what every request after the first refusal meets instead.
    func refuseRequests(carrying title: String, thenFailWith failure: Failure? = nil) {
        lock.withLock {
            _refusing = title
            _afterRefusal = failure
        }
        acceptEverything()
    }

    /// The titles Last.fm took, in order.
    var taken: [String] { lock.withLock { _taken } }

    /// Adds what it takes to its history, as Last.fm does.
    var keepsWhatItTakes: Bool {
        get { lock.withLock { _keepsWhatItTakes } }
        set { lock.withLock { _keepsWhatItTakes = newValue } }
    }

    /// Takes each batch and then times out before the answer arrives, as a cellular
    /// handoff does.
    var losesAnswers: Bool {
        get { lock.withLock { _losesAnswers } }
        set { lock.withLock { _losesAnswers = newValue } }
    }

    /// The methods asked for, in order.
    var methods: [String] { requests.compactMap { $0["method"] } }

    /// The titles sent to `track.scrobble`, in order.
    var sentTitles: [String] {
        scrobbleRequests.flatMap { fields in
            fields.keys.filter { $0.hasPrefix("track[") }
                .sorted { Self.index($0) < Self.index($1) }
                .compactMap { fields[$0] }
        }
    }

    private static func index(_ key: String) -> Int {
        Int(key.drop { $0 != "[" }.dropFirst().prefix { $0 != "]" }) ?? 0
    }

    /// The `track.scrobble` requests, leaving out history lookups.
    var scrobbleRequests: [[String: String]] {
        requests.filter { $0["method"] == "track.scrobble" }
    }

    /// The decoded form of every request, in order.
    var requests: [[String: String]] { lock.withLock { _requests } }
    /// Holds each request until opened.
    var gate: Gate? {
        get { lock.withLock { _gate } }
        set { lock.withLock { _gate = newValue } }
    }
    var delay: Duration? {
        get { lock.withLock { _delay } }
        set { lock.withLock { _delay = newValue } }
    }

    /// Answers with a body built from how many scrobbles were sent.
    func answer(_ body: @escaping @Sendable (Int) -> String) {
        lock.withLock { respond = { count in (200, body(count)) } }
    }

    func acceptEverything() {
        answer { count in Self.outcomes(Array(repeating: 0, count: count)) }
    }

    func fail(with failure: Failure) {
        lock.withLock {
            respond = { _ in
                switch failure {
                case .status(let status, let body): return (status, body)
                case .offline: throw URLError(.notConnectedToInternet)
                }
            }
        }
    }

    func client() -> LastFMClient {
        LastFMClient(apiKey: "KEY", secret: "SECRET") { [self] request in
            let fields = Self.decode(request.httpBody ?? Data())
            let (respond, gate, delay, refusing) = lock.withLock {
                _requests.append(fields)
                return (self.respond, _gate, _delay, _refusing)
            }
            if let delay { try? await Task.sleep(for: delay) }
            await gate?.wait()
            if fields["method"] == "user.getRecentTracks" {
                // Fails the way a scrobble would, otherwise lists the history.
                let (status, body) = try respond(0)
                let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
                return (Data((status == 200 ? recentTracks(fields) : body).utf8), response)
            }
            let sent = fields.keys.filter { $0.hasPrefix("track[") }.compactMap { key -> (title: String, artist: String, playedAt: Date)? in
                let index = key.dropFirst("track".count)
                guard let title = fields[key], let artist = fields["artist\(index)"],
                      let seconds = fields["timestamp\(index)"].flatMap(TimeInterval.init)
                else { return nil }
                return (title, artist, Date(timeIntervalSince1970: seconds))
            }
            let response = { (status: Int) in
                HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
            }
            if let refusing, sent.contains(where: { $0.title == refusing }) {
                let after = lock.withLock { _afterRefusal }
                if let after {
                    lock.withLock { _refusing = nil }
                    fail(with: after)
                }
                return (Data(#"{"error":6,"message":"Invalid parameters"}"#.utf8), response(400))
            }
            let (status, body) = try respond(sent.count)
            let (keeps, loses) = lock.withLock { (_keepsWhatItTakes, _losesAnswers) }
            if status == 200 {
                let titles = fields.keys.filter { $0.hasPrefix("track[") }
                    .sorted { Self.index($0) < Self.index($1) }
                    .compactMap { fields[$0] }
                lock.withLock {
                    _taken.append(contentsOf: titles)
                    if keeps { _history.append(contentsOf: sent) }
                }
            }
            if loses { throw URLError(.timedOut) }
            return (Data(body.utf8), response(status))
        }
    }

    /// One page of the history, newest first, within the request's `from` and `to`.
    private func recentTracks(_ fields: [String: String]) -> String {
        let from = fields["from"].flatMap(TimeInterval.init).map(Date.init(timeIntervalSince1970:)) ?? .distantPast
        let to = fields["to"].flatMap(TimeInterval.init).map(Date.init(timeIntervalSince1970:)) ?? .distantFuture
        let limit = fields["limit"].flatMap(Int.init) ?? 200
        let page = fields["page"].flatMap(Int.init) ?? 1
        let matching = history
            .filter { $0.playedAt >= from && $0.playedAt <= to }
            .sorted { $0.playedAt > $1.playedAt }
        let pages = max(1, (matching.count + limit - 1) / limit)
        let shown = Array(matching.dropFirst((page - 1) * limit).prefix(limit))
        return Self.recentTracks(shown, total: matching.count, pages: pages)
    }

    /// A `track.scrobble` response in Last.fm's shape, one entry per code (0 is accepted):
    /// an array for several, a lone object for one.
    static func outcomes(_ codes: [Int]) -> String {
        let entries = codes.map { code in
            ##"{"artist":{"corrected":"0","#text":"An Artist"},"album":{"corrected":"0","#text":""},"track":{"corrected":"0","#text":"T"},"ignoredMessage":{"code":"\##(code)","#text":""},"albumArtist":{"corrected":"0","#text":""},"timestamp":"1700000000"}"##
        }
        let scrobble = entries.count == 1 ? entries[0] : "[\(entries.joined(separator: ","))]"
        let ignored = codes.filter { $0 != 0 }.count
        return ##"{"scrobbles":{"scrobble":\##(scrobble),"@attr":{"ignored":\##(ignored),"accepted":\##(codes.count - ignored)}}}"##
    }

    static func recentTracks(_ tracks: [(title: String, artist: String, playedAt: Date)], total: Int, pages: Int) -> String {
        let entries = tracks.map { track in
            ##"{"name":"\##(track.title)","artist":{"#text":"\##(track.artist)"},"date":{"uts":"\##(Int(track.playedAt.timeIntervalSince1970))"}}"##
        }
        return ##"{"recenttracks":{"track":[\##(entries.joined(separator: ","))],"@attr":{"total":"\##(total)","totalPages":"\##(pages)"}}}"##
    }

    private static func decode(_ body: Data) -> [String: String] {
        var fields: [String: String] = [:]
        for pair in String(decoding: body, as: UTF8.self).split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let name = String(parts[0]).removingPercentEncoding ?? String(parts[0])
            let value = parts.count > 1 ? (String(parts[1]).removingPercentEncoding ?? "") : ""
            fields[name] = value
        }
        return fields
    }
}

/// Holds whatever waits on it until opened, or until its task is cancelled, so a test that
/// times out or fails never leaves a request parked behind it.
final class Gate: @unchecked Sendable {
    private let lock = NSLock()
    private var isOpen = false

    func open() { lock.withLock { isOpen = true } }

    func wait() async {
        while !Task.isCancelled, !lock.withLock({ isOpen }) {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }
}

/// A clock a test moves by hand.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ start: Date) { current = start }

    var now: Date { lock.withLock { current } }

    func advance(by seconds: TimeInterval) {
        lock.withLock { current = current.addingTimeInterval(seconds) }
    }
}

/// Polls rather than sleeping a fixed time, which a slow Simulator outlasts. It has no
/// deadline of its own, since a wall clock runs out on a busy machine while the work is still
/// on its way; the calling suite's `.timeLimit` bounds it instead, and cancelling the test
/// ends the wait.
@MainActor
func waitUntil(_ condition: () -> Bool) async throws {
    while !condition() {
        try await Task.sleep(for: .milliseconds(5))
    }
}

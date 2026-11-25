import Foundation
import Testing
@testable import TracksCore

/// The same vectors as Android's `MigrationTest`, so both platforms give a play one id.
@Suite struct PlayIDVectorTests {
    @Test func uuidV5MatchesTheStandard() {
        let dns = UUID(uuidString: "6ba7b810-9dad-11d1-80b4-00c04fd430c8")!
        #expect(PlayID.uuidV5(namespace: dns, name: "python.org").uuidString.lowercased() == "886313e1-3b8a-5372-9b90-0c9aee199e5d")
    }

    @Test func derivedIDsMatchAndroid() {
        let key = PlayID.contentKey(capturedAt: Date(timeIntervalSince1970: 1_700_000_000), kind: "radio", source: "appleMusic", songIdentity: "song\u{1F}artist")
        #expect(PlayID.derived(fromKey: key) == "ec212ee6-e655-5934-993d-33b155df2eb2")
        #expect(PlayID.derived(fromKey: "") == "25edc9e6-7801-5a5d-8a25-be94988f72d8")
    }

    @Test func lastFMImportsMatchAndroid() {
        #expect(PlayID.lastFM(playedAt: Date(timeIntervalSince1970: 1_794_645_000), title: "Halo", artistName: "Beyoncé") == "d3a4a4e8-7fd8-5e1b-bf73-5aef3f249756")
        #expect(PlayID.lastFM(playedAt: Date(timeIntervalSince1970: 1_700_000_000.6), title: "Don't Stop Me Now", artistName: "Queen") == "c3e0da57-17d7-536b-b178-b452bed54b30")
    }
}

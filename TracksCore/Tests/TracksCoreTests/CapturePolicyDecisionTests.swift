import Testing
import Foundation
@testable import TracksCore

/// Which observations become captures.
@Suite("Capture decisions")
struct CaptureDecisionTests {
    let policy = CapturePolicy()
    let now = Date(timeIntervalSince1970: 3_000_000)

    /// Tracks' Apple Music player, which says whether it queued a station.
    func observation(
        title: String = "Dai Dai",
        artist: String = "Shakira & Burna Boy",
        isStation: Bool? = true,
        station: String? = nil
    ) -> NowPlayingObservation {
        NowPlayingObservation(
            title: title,
            artistName: artist,
            catalogSongID: "6768469976",
            duration: 223,
            stationName: station,
            isStation: isStation
        )
    }

    @Test("a song on a station is kept as radio, and anything else as on demand", arguments: [
        (isStation: true as Bool?, kind: CaptureKind.radio),
        (isStation: false, kind: .onDemand),
        // A player that doesn't say, as in tests, is taken as on demand.
        (isStation: nil, kind: .onDemand),
    ])
    func kindFollowsThePlayer(isStation: Bool?, kind: CaptureKind) {
        let decision = policy.decide(observation(isStation: isStation), lastSeen: nil, now: now)
        guard case .capture(let capturable) = decision else {
            Issue.record("Expected a capture, got \(decision)")
            return
        }
        #expect(capturable.kind == kind)
    }

    @Test("with on-demand capture off, an on-demand song is ignored and radio still kept")
    func honoursTheOnDemandSwitch() {
        let stationsOnly = CapturePolicy(capturesOnDemand: false)
        #expect(stationsOnly.decide(observation(isStation: false), lastSeen: nil, now: now) == .ignore(.onDemand))
        #expect(stationsOnly.decide(observation(), lastSeen: nil, now: now).isCapture)
    }

    @Test("an excluded station is skipped, and doesn't suppress on-demand plays")
    func exclusionOnlyAppliesToRadio() {
        let excluding = CapturePolicy(excludedStations: ["Apple Music Chill"])
        let onDemand = observation(isStation: false, station: "Apple Music Chill")
        #expect(excluding.decide(onDemand, lastSeen: nil, now: now).isCapture)

        let radio = observation(station: "Apple Music Chill")
        #expect(
            excluding.decide(radio, lastSeen: nil, now: now)
                == .ignore(.excludedStation(name: "Apple Music Chill"))
        )
    }

    @Test("a paused observation is rejected")
    func rejectsNotPlaying() {
        let paused = NowPlayingObservation(
            title: "Dai Dai", artistName: "Shakira", playbackState: .paused
        )
        #expect(policy.decide(paused, lastSeen: nil, now: now) == .ignore(.notPlaying))
    }

    @Test("a repeat inside the dedupe window is a duplicate, and after it a new play")
    func dedupeWindow() {
        #expect(
            policy.decide(observation(), lastSeen: now.addingTimeInterval(-16), now: now)
                == .ignore(.duplicate(secondsSinceLast: 16))
        )
        #expect(policy.decide(observation(), lastSeen: now.addingTimeInterval(-601), now: now).isCapture)
    }

    /// One of your own files: no catalog id, but title and artist are enough.
    @Test("an observation with no catalog id is still capturable")
    func noCatalogID() {
        let observation = NowPlayingObservation(
            title: "Nostalgia",
            artistName: "Lossapardo",
            albumTitle: "Nostalgia - Single",
            catalogSongID: nil,
            isStation: false
        )
        let decision = policy.decide(observation, lastSeen: nil, now: now)
        #expect(decision.isCapture)
        if case .capture(let capturable) = decision {
            #expect(capturable.catalogSongID == nil)
        }
    }

    @Test("an observation with neither id nor metadata is rejected")
    func unidentifiable() {
        let observation = NowPlayingObservation(title: "", artistName: "")
        #expect(policy.decide(observation, lastSeen: nil, now: now) == .ignore(.unidentifiable))
    }
}

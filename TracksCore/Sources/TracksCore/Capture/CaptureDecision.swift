import Foundation

/// What to do about one observation, decided before anything is written or awaited.
///
/// Kept apart from the store so the policy can be tested on its own. Every rejection
/// carries a reason, so "nothing was captured" can be traced.
public enum CaptureDecision: Sendable, Equatable {
    case capture(Capturable)
    case ignore(Reason)

    public struct Capturable: Sendable, Equatable {
        public let observation: NowPlayingObservation
        /// `.radio` or `.onDemand`. Only radio goes to the playlist.
        public let kind: CaptureKind
        /// Set when the platform gave an ID; nil means a catalog search is needed.
        public let catalogSongID: String?
        /// Present when this observation also names the station.
        public let stationName: String?

        public init(
            observation: NowPlayingObservation,
            kind: CaptureKind = .radio,
            catalogSongID: String?,
            stationName: String?
        ) {
            self.observation = observation
            self.kind = kind
            self.catalogSongID = catalogSongID
            self.stationName = stationName
        }
    }

    public enum Reason: Sendable, Equatable {
        /// Paused or stopped.
        case notPlaying
        /// Playing on demand, with on-demand capture switched off.
        case onDemand
        /// Not enough metadata to identify anything.
        case unidentifiable
        /// The user excluded this station in Settings.
        case excludedStation(name: String)
        /// Seen recently enough to be the same play.
        case duplicate(secondsSinceLast: TimeInterval)
        /// Playing, but not long enough to count yet. The caller uses `needs` to know when
        /// to check again.
        case tooShort(playedFor: TimeInterval, needs: TimeInterval)
    }

    public var isCapture: Bool {
        if case .capture = self { return true }
        return false
    }
}

/// Decides what happens to an observation. Pure, synchronous, and total.
public struct CapturePolicy: Sendable {
    public let dedupe: DedupePolicy
    public let excludedStations: Set<String>
    /// Whether to keep on-demand plays too. Off, only stations are recorded.
    public let capturesOnDemand: Bool

    public init(
        dedupe: DedupePolicy = .default,
        excludedStations: Set<String> = [],
        capturesOnDemand: Bool = true
    ) {
        self.dedupe = dedupe
        self.excludedStations = excludedStations
        self.capturesOnDemand = capturesOnDemand
    }

    /// - Parameter lastSeen: when this song was last captured, if ever. Read in the same
    ///   synchronous transaction as the insert, so two observations can't both pass.
    public func decide(
        _ observation: NowPlayingObservation,
        lastSeen: Date?,
        now: Date = .now
    ) -> CaptureDecision {
        guard observation.playbackState == .playing else { return .ignore(.notPlaying) }
        guard observation.isIdentifiable else { return .ignore(.unidentifiable) }

        let kind: CaptureKind = observation.isRadio ? .radio : .onDemand
        if kind == .onDemand, !capturesOnDemand { return .ignore(.onDemand) }

        // Only stations can be excluded.
        if kind == .radio, let station = observation.stationName,
           excludedStations.contains(station) {
            return .ignore(.excludedStation(name: station))
        }

        guard dedupe.shouldCapture(lastSeen: lastSeen, now: now) else {
            let elapsed = lastSeen.map { now.timeIntervalSince($0) } ?? 0
            return .ignore(.duplicate(secondsSinceLast: elapsed))
        }

        return .capture(
            .init(
                observation: observation,
                kind: kind,
                catalogSongID: observation.catalogSongID,
                stationName: observation.stationName
            )
        )
    }
}

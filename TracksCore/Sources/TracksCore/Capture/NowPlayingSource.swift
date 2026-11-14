import Foundation

/// The track Tracks is playing, from either of its players: Apple Music through MusicKit, or
/// your own files and servers. The second has no catalog song ID, so identity fields are
/// optional.
public struct NowPlayingObservation: Sendable, Equatable {
    public var title: String
    public var artistName: String
    public var albumTitle: String?
    public var artworkURL: String?

    /// Apple Music catalog ID, when the player has one. See ``CatalogQuery`` for the rest.
    public var catalogSongID: String?

    /// Track length. Also helps match catalog search results.
    public var duration: TimeInterval?

    public var playbackState: PlaybackState
    /// Advances on demand, stays at zero on a station. Nil when unknown.
    public var playerPosition: TimeInterval?
    /// Station name, when the player gives one.
    public var stationName: String?
    /// Whether this came from a station. Tracks queued the music itself, so its players say.
    /// Nil counts as on demand.
    public var isStation: Bool?
    public var observedAt: Date

    /// Everything the platform reported, verbatim, for the probe and diagnostics.
    public var rawFields: [String: String]

    public init(
        title: String,
        artistName: String,
        albumTitle: String? = nil,
        artworkURL: String? = nil,
        catalogSongID: String? = nil,
        duration: TimeInterval? = nil,
        playbackState: PlaybackState = .playing,
        playerPosition: TimeInterval? = nil,
        stationName: String? = nil,
        isStation: Bool? = nil,
        observedAt: Date = .now,
        rawFields: [String: String] = [:]
    ) {
        self.title = title
        self.artistName = artistName
        self.albumTitle = albumTitle
        self.artworkURL = artworkURL
        self.catalogSongID = catalogSongID
        self.duration = duration
        self.playbackState = playbackState
        self.playerPosition = playerPosition
        self.stationName = stationName
        self.isStation = isStation
        self.observedAt = observedAt
        self.rawFields = rawFields
    }

    /// Whether this played on a station.
    public var isRadio: Bool { isStation == true }

    /// Whether this observation carries enough to identify a track at all.
    public var isIdentifiable: Bool {
        catalogSongID != nil || (!title.isEmpty && !artistName.isEmpty)
    }

    /// Whether this is worth acting on at all.
    public var isCapturable: Bool {
        playbackState == .playing && isIdentifiable
    }

    /// The catalog search for this track. Nil if we already have an ID or can't search.
    public var catalogQuery: CatalogQuery? {
        guard catalogSongID == nil, !title.isEmpty, !artistName.isEmpty else { return nil }
        return CatalogQuery(
            title: title,
            artistName: artistName,
            duration: duration,
            albumTitle: albumTitle
        )
    }
}

public enum PlaybackState: String, Sendable, Equatable, CaseIterable {
    case playing, paused, stopped
}

/// A source of now-playing observations.
///
/// Serves one consumer at a time and can be stopped and started again, which is what
/// pausing and resuming capture does.
public protocol NowPlayingSource: AnyObject, Sendable {
    /// Starts observing and returns the observations from now on.
    ///
    /// Each call hands back a new stream and finishes the previous one. A finished
    /// `AsyncStream` can't be restarted, so a single stream made up front would leave a
    /// resumed consumer iterating one that has already ended.
    func start() -> AsyncStream<NowPlayingObservation>
    /// Stops observing and finishes the stream the last ``start()`` returned.
    func stop()
}

import Foundation
import MusicKit
import TracksCore

/// Turns a queue entry of Tracks’ own `ApplicationMusicPlayer` into an observation.
enum MusicPlayerReading {
    /// How long to wait after a queue change before reading `currentEntry`, which hasn't
    /// updated yet when the change fires. Found by trial.
    static let settleDelay: Duration = .milliseconds(300)

    /// Builds an observation, or nil while the entry is unresolved.
    ///
    /// An entry arrives in two stages about a second apart. First the title appears with
    /// `item == nil`: at a station change it's the station name ("Apple Music 1"), otherwise
    /// the track name with no catalog id. Then `item` resolves to `.song` with the id.
    /// Capturing the first stage would save the station as a song.
    ///
    /// Takes the player's values rather than the player, so tests can supply their own.
    static func observation(
        from entry: MusicPlayer.Queue.Entry?,
        status: MusicPlayer.PlaybackStatus,
        playbackTime: TimeInterval
    ) -> NowPlayingObservation? {
        guard let entry else { return nil }

        var raw: [String: String] = [
            "entry.id": entry.id,
            "entry.title": entry.title,
            "entry.subtitle": ProbeFormat.value(entry.subtitle),
            "entry.isTransient": String(entry.isTransient),
        ]

        // Unresolved, so nothing to capture yet.
        guard case .song(let song) = entry.item else { return nil }

        // For diagnosis only.
        if let parameters = entry.item?.playParameters {
            raw["playParameters"] = ProbeFormat.json(parameters)
        }

        return NowPlayingObservation(
            title: song.title,
            artistName: song.artistName,
            albumTitle: song.albumTitle,
            // The entry carries the song, so its artwork comes free. Streams answer MusicKit's
            // private `musicKit://` scheme, which never loads and would make the artwork
            // backfill skip the row. See ``ArtworkURL``.
            artworkURL: ArtworkURL.loadable(song.artwork?.url(width: 300, height: 300)?.absoluteString),
            catalogSongID: song.id.rawValue,
            duration: song.duration,
            playbackState: playbackState(status),
            playerPosition: playbackTime.isFinite ? playbackTime : nil,
            stationName: nil,
            observedAt: .now,
            rawFields: raw
        )
    }

    static func playbackState(_ status: MusicPlayer.PlaybackStatus) -> PlaybackState {
        switch status {
        case .playing: .playing
        case .paused: .paused
        default: .stopped
        }
    }
}

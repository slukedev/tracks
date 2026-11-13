import Foundation

/// Stands in for TracksCore's `Capture/CaptureSettings.swift`, which brings the Mac's and
/// iPhone's whole settings layer with it. The shared scrobble policy needs only this.
public enum CaptureSettings {
    /// The length taken for a song whose own isn't known, such as one on a live stream.
    /// Keep in step with TracksCore.
    public static let assumedSongLength: TimeInterval = 240
}

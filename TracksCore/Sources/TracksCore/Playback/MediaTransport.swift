import Foundation

public enum TransportCommand: String, Sendable, Equatable, CaseIterable {
    case previous
    case playPause
    case next
}

/// What Music is doing. `state` is nil when it isn't running.
public struct PlayerPresence: Sendable, Equatable {
    public let state: PlaybackState?
    /// Whether it's playing a station.
    public let isRadio: Bool
    /// Seconds into the song, when Music said.
    public let position: Double?
    /// The song's length, when Music said. Never for a station.
    public let duration: Double?

    public init(state: PlaybackState?, isRadio: Bool = false, position: Double? = nil, duration: Double? = nil) {
        self.state = state
        self.isRadio = isRadio
        self.position = position
        self.duration = duration
    }

    /// Seconds as Music writes them as text: "212.5", or "212,5" where a comma is the
    /// decimal mark. Nil for anything else, or a time that can't be one.
    public static func seconds(_ text: String?) -> Double? {
        guard let text, let value = Double(text.trimmingCharacters(in: .whitespaces).replacing(",", with: ".")),
              value.isFinite, value >= 0 else { return nil }
        return value
    }

    /// Not running at all.
    public static let absent = PlayerPresence(state: nil)

    public var isRunning: Bool { state != nil }
    public var isPlaying: Bool { state == .playing }
}

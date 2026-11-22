import Foundation

/// The four buttons under Now Playing in the car, always the same four in the same places, so
/// a driver's hand learns where each one is. One that doesn't apply to what's on is disabled
/// rather than taken away, which would slide the others along.
public enum CarNowPlayingButton: Equatable, Sendable {
    case shuffle(isEnabled: Bool)
    /// Tracks Radio and the moods take Shuffle's place: they pick as they go.
    case moreLikeThis(isEnabled: Bool, isSelected: Bool)
    case autoplay(isEnabled: Bool, isSelected: Bool)
    case favorite(isEnabled: Bool, isSelected: Bool)
    case notForMe(isEnabled: Bool)

    /// What's on, as far as the buttons care.
    public enum Playing: Equatable, Sendable {
        case nothing
        /// An album, playlist, mix or songs: a queue that can be shuffled and run on.
        case queue
        /// Tracks Radio or a mood, picking each song as it goes.
        case live(canAskForMore: Bool, askedForMore: Bool)
        /// An Apple Music station, which picks for itself.
        case station
    }

    public static func row(
        for playing: Playing,
        autoplay: Bool,
        canFavorite: Bool,
        isFavorite: Bool
    ) -> [CarNowPlayingButton] {
        let first: CarNowPlayingButton = switch playing {
        case .live(let canAsk, let asked): .moreLikeThis(isEnabled: canAsk, isSelected: canAsk && asked)
        case .queue: .shuffle(isEnabled: true)
        case .nothing, .station: .shuffle(isEnabled: false)
        }
        let isQueue = playing == .queue
        let hasSong = playing != .nothing
        return [
            first,
            .autoplay(isEnabled: isQueue, isSelected: isQueue && autoplay),
            .favorite(isEnabled: hasSong && canFavorite, isSelected: hasSong && canFavorite && isFavorite),
            .notForMe(isEnabled: hasSong),
        ]
    }

    public var isEnabled: Bool {
        switch self {
        case .shuffle(let isEnabled), .notForMe(let isEnabled): isEnabled
        case .moreLikeThis(let isEnabled, _), .autoplay(let isEnabled, _), .favorite(let isEnabled, _): isEnabled
        }
    }

    public var isSelected: Bool {
        switch self {
        case .shuffle, .notForMe: false
        case .moreLikeThis(_, let isSelected), .autoplay(_, let isSelected), .favorite(_, let isSelected): isSelected
        }
    }
}

import SwiftUI
import TracksCore

struct RoomStyle {
    /// How much room the page gives each thing, and how much of a list it shows at once.
    enum Pace {
        case calm, steady, bright

        var sectionSpacing: CGFloat {
            #if os(macOS)
            switch self {
            case .calm: 52
            case .steady: 40
            case .bright: 32
            }
            #else
            switch self {
            case .calm: 40
            case .steady: 32
            case .bright: 26
            }
            #endif
        }

        /// Rows shown before Show More. On the Mac they fill columns, so a multiple of three.
        var rows: Int {
            #if os(macOS)
            switch self {
            case .calm: 9
            case .steady, .bright: 12
            }
            #else
            switch self {
            case .calm: 5
            case .steady: 6
            case .bright: 8
            }
            #endif
        }
    }

    let base: Color
    let symbol: String
    let pace: Pace

    /// Text and small glyphs in the room's colour on the page, readable in light and dark.
    var ink: LegibleInk { .ink(base) }
}

// MARK: - Moods

extension Mood {
    /// What its page is built from.
    var style: RoomStyle {
        let pace: RoomStyle.Pace = switch self {
        case .focus, .chill, .heartbreak, .sleep: .calm
        case .love, .drive: .steady
        case .feelGood, .energy, .workout, .party: .bright
        }
        return RoomStyle(base: base, symbol: symbol, pace: pace)
    }
}

// MARK: - Genres

extension MusicGenre {
    /// What its page is built from: its name set as its music sounds, from the era and the
    /// instruments it's known for.
    var style: RoomStyle {
        let pace: RoomStyle.Pace = switch id {
        case "pop", "hip-hop", "rock", "electronic", "latin", "k-pop", "metal", "afrobeats": .bright
        case "jazz", "singer-songwriter", "classical", "blues", "christian": .calm
        default: .steady
        }
        return RoomStyle(base: color, symbol: symbol, pace: pace)
    }
}

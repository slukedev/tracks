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

    /// One line on what the genre is, under its name.
    var tagline: String {
        switch id {
        case "pop": String(localized: "Big hooks and choruses.")
        case "hip-hop": String(localized: "Beats and bars.")
        case "r-b": String(localized: "Smooth, soulful songs.")
        case "rock": String(localized: "Guitars and loud drums.")
        case "alternative": String(localized: "Indie and everything off the charts.")
        case "electronic": String(localized: "Synths and drum machines.")
        case "country": String(localized: "Steel guitar and a story.")
        case "latin": String(localized: "Reggaeton, salsa and more.")
        case "k-pop": String(localized: "Pop from Seoul.")
        case "metal": String(localized: "Heavy riffs.")
        case "jazz": String(localized: "Horns, keys and swing.")
        case "singer-songwriter": String(localized: "One voice, one guitar.")
        case "classical": String(localized: "Orchestras and soloists.")
        case "afrobeats": String(localized: "Afrobeats, amapiano and highlife.")
        case "reggae": String(localized: "Reggae, dancehall and dub.")
        case "blues": String(localized: "Twelve bars and a feeling.")
        case "soundtrack": String(localized: "Music from films and shows.")
        case "christian": String(localized: "Worship and gospel.")
        default: String(localized: "What you play most, and what everyone's playing.")
        }
    }
}

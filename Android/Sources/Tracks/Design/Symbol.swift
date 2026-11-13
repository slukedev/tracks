import SwiftUI

/// Every icon Tracks shows, named for what it means.
///
/// Android draws Material Symbols, which is what every other app on the phone uses; SF Symbols
/// would look borrowed. Previews on a Mac fall back to the nearest SF Symbol.
enum Symbol: String, CaseIterable {
    case summary, history, charts, search, settings
    case listening, artist, song, album, newToYou, streak
    case trendingUp, trendingDown, flat, rankUp, rankDown
    case clock, calendar, chevron, close, delete, back
    case library, notifications, download, theme, info, sample
    case nightOwl, earlyBird, trophy, onRepeat, radio, genre, weekend, hourglass
    case play, apps, sessions, check, warning, refresh
    case home, pause, skipNext, skipPrevious, shuffle, repeatAll, repeatOne, queue, collapse
    case server, signOut, person, stats, favourite, alphabetical

    /// The Material Symbols icon, outlined or filled, as named in
    /// `androidx.compose.material.icons`.
    var materialName: String {
        switch self {
        case .summary: "Insights"
        case .history: "History"
        case .charts: "Leaderboard"
        case .search: "Search"
        case .settings: "Settings"
        case .listening: "Headphones"
        case .artist: "Mic"
        case .song: "MusicNote"
        case .album: "Album"
        case .newToYou: "AutoAwesome"
        case .streak: "LocalFireDepartment"
        case .trendingUp: "TrendingUp"
        case .trendingDown: "TrendingDown"
        case .flat: "TrendingFlat"
        case .rankUp: "ArrowDropUp"
        case .rankDown: "ArrowDropDown"
        case .clock: "Schedule"
        case .calendar: "CalendarMonth"
        case .chevron: "KeyboardArrowRight"
        case .close: "Close"
        case .delete: "Delete"
        case .back: "ArrowBack"
        case .library: "LibraryMusic"
        case .notifications: "NotificationsActive"
        case .download: "CloudDownload"
        case .theme: "Palette"
        case .info: "Info"
        case .sample: "Science"
        case .nightOwl: "NightsStay"
        case .earlyBird: "WbTwilight"
        case .trophy: "EmojiEvents"
        case .onRepeat: "Repeat"
        case .radio: "Radio"
        case .genre: "Category"
        case .weekend: "WbSunny"
        case .hourglass: "HourglassBottom"
        case .play: "PlayArrow"
        case .apps: "Apps"
        case .sessions: "GraphicEq"
        case .check: "CheckCircle"
        case .warning: "Warning"
        case .refresh: "Refresh"
        case .home: "Home"
        case .pause: "Pause"
        case .skipNext: "SkipNext"
        case .skipPrevious: "SkipPrevious"
        case .shuffle: "Shuffle"
        case .repeatAll: "Repeat"
        case .repeatOne: "RepeatOne"
        case .queue: "QueueMusic"
        case .collapse: "KeyboardArrowDown"
        case .server: "Dns"
        case .signOut: "Logout"
        case .person: "Person"
        case .stats: "Insights"
        case .favourite: "Star"
        case .alphabetical: "SortByAlpha"
        }
    }

    var sfSymbol: String {
        switch self {
        case .summary: "chart.bar.xaxis"
        case .history: "clock.arrow.circlepath"
        case .charts: "list.number"
        case .search: "magnifyingglass"
        case .settings: "gearshape"
        case .listening: "headphones"
        case .artist: "music.microphone"
        case .song: "music.note"
        case .album: "square.stack"
        case .newToYou: "sparkles"
        case .streak: "flame"
        case .trendingUp: "chart.line.uptrend.xyaxis"
        case .trendingDown: "chart.line.downtrend.xyaxis"
        case .flat: "arrow.right"
        case .rankUp: "arrowtriangle.up.fill"
        case .rankDown: "arrowtriangle.down.fill"
        case .clock: "clock"
        case .calendar: "calendar"
        case .chevron: "chevron.right"
        case .close: "xmark"
        case .delete: "trash"
        case .back: "chevron.left"
        case .library: "music.note.list"
        case .notifications: "bell.badge"
        case .download: "icloud.and.arrow.down"
        case .theme: "paintpalette"
        case .info: "info.circle"
        case .sample: "flask"
        case .nightOwl: "moon.stars"
        case .earlyBird: "sunrise"
        case .trophy: "trophy"
        case .onRepeat: "repeat"
        case .radio: "dot.radiowaves.left.and.right"
        case .genre: "guitars"
        case .weekend: "sun.max"
        case .hourglass: "hourglass"
        case .play: "play.fill"
        case .apps: "square.grid.2x2"
        case .sessions: "waveform"
        case .check: "checkmark.circle"
        case .warning: "exclamationmark.triangle"
        case .refresh: "arrow.clockwise"
        case .home: "house"
        case .pause: "pause.fill"
        case .skipNext: "forward.fill"
        case .skipPrevious: "backward.fill"
        case .shuffle: "shuffle"
        case .repeatAll: "repeat"
        case .repeatOne: "repeat.1"
        case .queue: "list.bullet"
        case .collapse: "chevron.down"
        case .server: "server.rack"
        case .signOut: "rectangle.portrait.and.arrow.right"
        case .person: "person"
        case .stats: "chart.bar.xaxis"
        case .favourite: "star"
        case .alphabetical: "textformat.abc"
        }
    }
}

/// A ``Symbol`` at a size, in a colour.
///
/// Material icons are drawn on a 24 dp grid, so that's the default, as in Compose's `Icon`.
struct SymbolImage: View {
    let symbol: Symbol
    var size: CGFloat = 24
    var filled = false
    var tint: Color = .primary
    /// Takes the place of `tint` when set.
    var role: MaterialRole?

    init(_ symbol: Symbol, size: CGFloat = 24, filled: Bool = false, tint: Color = .primary) {
        self.symbol = symbol
        self.size = size
        self.filled = filled
        self.tint = tint
    }

    init(_ symbol: Symbol, size: CGFloat = 24, filled: Bool = false, role: MaterialRole) {
        self.symbol = symbol
        self.size = size
        self.filled = filled
        self.role = role
    }

    var body: some View {
        #if os(Android)
        ComposeView {
            MaterialIconComposer(name: symbol.materialName, filled: filled, size: Double(size), tint: tint, role: role?.rawValue ?? "")
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
        #else
        Image(systemName: symbol.sfSymbol)
            .font(.system(size: size * 0.75))
            .foregroundStyle(role?.fallback ?? tint)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
        #endif
    }
}

#if SKIP
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.__
import androidx.compose.material.icons.automirrored.outlined.__
import androidx.compose.material.icons.filled.__
import androidx.compose.material.icons.outlined.__
import androidx.compose.ui.graphics.vector.ImageVector

struct MaterialIconComposer : ContentComposer {
    let name: String
    let filled: Bool
    let size: Double
    let tint: Color
    let role: String

    @Composable func Compose(context: ComposeContext) {
        androidx.compose.material3.Icon(
            imageVector: MaterialIcons.vector(name, filled: filled),
            contentDescription: nil,
            modifier: context.modifier.size(size.dp),
            tint: role.isEmpty ? tint.asComposeColor() : MaterialColors.color(role)
        )
    }
}

// SKIP @nobridge
struct MaterialIcons {
/// The Material icon for a ``Symbol/materialName``. A table rather than reflection, so R8 keeps
/// exactly these icons out of the thousands in the extended set.
static func vector(_ name: String, filled: Bool) -> ImageVector {
    if filled {
        switch name {
        case "Insights": return Icons.Filled.Insights
        case "History": return Icons.Filled.History
        case "Leaderboard": return Icons.Filled.Leaderboard
        case "Search": return Icons.Filled.Search
        case "Settings": return Icons.Filled.Settings
        case "Headphones": return Icons.Filled.Headphones
        case "Mic": return Icons.Filled.Mic
        case "MusicNote": return Icons.Filled.MusicNote
        case "Album": return Icons.Filled.Album
        case "AutoAwesome": return Icons.Filled.AutoAwesome
        case "LocalFireDepartment": return Icons.Filled.LocalFireDepartment
        case "PlayArrow": return Icons.Filled.PlayArrow
        case "CheckCircle": return Icons.Filled.CheckCircle
        case "Home": return Icons.Filled.Home
        case "LibraryMusic": return Icons.Filled.LibraryMusic
        case "Pause": return Icons.Filled.Pause
        case "SkipNext": return Icons.Filled.SkipNext
        case "SkipPrevious": return Icons.Filled.SkipPrevious
        case "Star": return Icons.Filled.Star
        case "Shuffle": return Icons.Filled.Shuffle
        case "Repeat": return Icons.Filled.Repeat
        case "SortByAlpha": return Icons.Filled.SortByAlpha
        default: break
        }
    }
    switch name {
    case "Insights": return Icons.Outlined.Insights
    case "History": return Icons.Outlined.History
    case "Leaderboard": return Icons.Outlined.Leaderboard
    case "Search": return Icons.Outlined.Search
    case "Settings": return Icons.Outlined.Settings
    case "Headphones": return Icons.Outlined.Headphones
    case "Mic": return Icons.Outlined.Mic
    case "MusicNote": return Icons.Outlined.MusicNote
    case "Album": return Icons.Outlined.Album
    case "AutoAwesome": return Icons.Outlined.AutoAwesome
    case "LocalFireDepartment": return Icons.Outlined.LocalFireDepartment
    case "TrendingUp": return Icons.AutoMirrored.Outlined.TrendingUp
    case "TrendingDown": return Icons.AutoMirrored.Outlined.TrendingDown
    case "TrendingFlat": return Icons.AutoMirrored.Outlined.TrendingFlat
    case "ArrowDropUp": return Icons.Filled.ArrowDropUp
    case "ArrowDropDown": return Icons.Filled.ArrowDropDown
    case "Schedule": return Icons.Outlined.Schedule
    case "CalendarMonth": return Icons.Outlined.CalendarMonth
    case "KeyboardArrowRight": return Icons.AutoMirrored.Outlined.KeyboardArrowRight
    case "Close": return Icons.Outlined.Close
    case "Delete": return Icons.Outlined.Delete
    case "ArrowBack": return Icons.AutoMirrored.Outlined.ArrowBack
    case "LibraryMusic": return Icons.Outlined.LibraryMusic
    case "NotificationsActive": return Icons.Outlined.NotificationsActive
    case "CloudDownload": return Icons.Outlined.CloudDownload
    case "Palette": return Icons.Outlined.Palette
    case "Info": return Icons.Outlined.Info
    case "Science": return Icons.Outlined.Science
    case "NightsStay": return Icons.Outlined.NightsStay
    case "WbTwilight": return Icons.Outlined.WbTwilight
    case "EmojiEvents": return Icons.Outlined.EmojiEvents
    case "Repeat": return Icons.Outlined.Repeat
    case "Radio": return Icons.Outlined.Radio
    case "Category": return Icons.Outlined.Category
    case "WbSunny": return Icons.Outlined.WbSunny
    case "HourglassBottom": return Icons.Outlined.HourglassBottom
    case "PlayArrow": return Icons.Outlined.PlayArrow
    case "Apps": return Icons.Outlined.Apps
    case "GraphicEq": return Icons.Outlined.GraphicEq
    case "CheckCircle": return Icons.Outlined.CheckCircle
    case "Warning": return Icons.Outlined.Warning
    case "Refresh": return Icons.Outlined.Refresh
    case "Home": return Icons.Outlined.Home
    case "Pause": return Icons.Outlined.Pause
    case "SkipNext": return Icons.Outlined.SkipNext
    case "SkipPrevious": return Icons.Outlined.SkipPrevious
    case "Shuffle": return Icons.Outlined.Shuffle
    case "Repeat": return Icons.Outlined.Repeat
    case "RepeatOne": return Icons.Outlined.RepeatOne
    case "QueueMusic": return Icons.AutoMirrored.Outlined.QueueMusic
    case "KeyboardArrowDown": return Icons.Outlined.KeyboardArrowDown
    case "Dns": return Icons.Outlined.Dns
    case "Logout": return Icons.AutoMirrored.Outlined.Logout
    case "Person": return Icons.Outlined.Person
    case "Star": return Icons.Outlined.StarOutline
    case "SortByAlpha": return Icons.Outlined.SortByAlpha
    default: return Icons.Outlined.MusicNote
    }
}
}
#endif

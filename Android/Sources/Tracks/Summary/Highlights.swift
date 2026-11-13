import SwiftUI
import TracksStats

extension Insight {
    /// The sentence, worded as on the iPhone and Mac.
    var sentence: String {
        switch self {
        case .milestone(let count, let date):
            return "You passed \(count.formatted()) songs on \(Format.shortDate(date))."
        case .streak(let days):
            return "You've listened \(days == 1 ? "1 day" : "\(days) days") in a row."
        case .listeningTrend(let percent, let range):
            let then = Format.previous(range)
            return percent >= 0
                ? "You're listening \(percent)% more than \(then)."
                : "You're listening \(abs(percent))% less than \(then)."
        case .onRepeat(let title, let artist, let count, _):
            return "On repeat: you played \(title) by \(artist) \(count) times in one day."
        case .topArtistShare(let name, let percent, _):
            return "\(name) made up \(percent)% of everything you played."
        case .topGenre(let name, let percent):
            return "Mostly \(name): \(percent)% of your plays were in that genre."
        case .favouriteDecade(let decade, let percent):
            return "\(percent)% of what you played came out in the \(Format.decade(decade))."
        case .newArtists(let count):
            return count == 1 ? "You discovered 1 new artist." : "You discovered \(count) new artists."
        case .allNew(let count):
            return "All \(count) songs were new to you."
        case .mostlyNew(let percent):
            return "\(percent)% of what you played was new to you."
        case .persona(.nightOwl, let percent):
            return "Night owl: \(percent)% of your listening happens after 10 PM."
        case .persona(.earlyBird, let percent):
            return "Early bird: \(percent)% of your listening happens before 9 AM."
        case .busiestHour(let hour, let count):
            return "You listen most around \(Format.hour(hour)): \(count == 1 ? "1 song" : "\(count.formatted()) songs")."
        case .weekendListener(let percent):
            return "Weekends are for music: \(percent)% of your listening falls on them."
        case .busiestDay(let weekday, let count):
            let name = Calendar.current.weekdaySymbols[weekday - 1]
            return "\(name) is your biggest day, with \(count) songs."
        case .dominantStation(let name, let count, let total):
            return "\(name) played \(count) of your \(total) radio songs."
        case .repeatedSong(let title, let artist, let count):
            return "Your most played song is \(title) by \(artist), \(count) times."
        case .longestSession(let minutes):
            return "Your longest session ran \(minutes) minutes."
        case .playedBack(let count, let total):
            return "You've played back \(count) of \(total) radio songs."
        }
    }

    /// Radio and play-back are Apple Music ideas with nothing to say on Android.
    var appliesToAndroid: Bool {
        switch self {
        case .dominantStation, .playedBack, .longestSession: false
        default: true
        }
    }

    var androidSymbol: Symbol {
        switch self {
        case .milestone: .trophy
        case .streak: .streak
        case .listeningTrend(let percent, _): percent >= 0 ? .trendingUp : .trendingDown
        case .onRepeat, .repeatedSong: .onRepeat
        case .topArtistShare: .artist
        case .topGenre: .genre
        case .favouriteDecade: .calendar
        case .newArtists, .allNew, .mostlyNew: .newToYou
        case .persona(let persona, _): persona == .nightOwl ? .nightOwl : .earlyBird
        case .busiestHour: .clock
        case .weekendListener: .weekend
        case .busiestDay: .calendar
        case .dominantStation, .playedBack: .radio
        case .longestSession: .hourglass
        }
    }

    var categoryTitle: String {
        switch category {
        case .achievement: "Milestone"
        case .trend: "Trend"
        case .favourites: "Favourites"
        case .discovery: "Discovery"
        case .rhythm: "Rhythm"
        case .radio: "Sessions"
        }
    }
}

/// A highlight as a card: a tonal symbol, its kind, and the sentence.
struct HighlightCard: View {
    let insight: Insight

    var body: some View {
        MaterialCard(role: .surfaceContainerLow) {
            HStack(alignment: .top, spacing: Space.iconGap) {
                IconBadge(symbol: insight.androidSymbol, container: .tertiaryContainer, content: .onTertiaryContainer, size: 40, shape: .cookie6)
                VStack(alignment: .leading, spacing: 4) {
                    Text(insight.categoryTitle)
                        .materialType(.labelLarge)
                        .materialForeground(.tertiary)
                    Text(insight.sentence)
                        .materialType(.bodyLarge)
                        .materialForeground(.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .combinedAccessibility()
    }
}

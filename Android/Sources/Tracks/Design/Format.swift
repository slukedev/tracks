import Foundation
import TracksStats

/// How numbers and dates read, matching the iPhone and Mac apps.
enum Format {
    /// "42m", "3h 5m", "912h". Under a minute reads "0m"; the estimate isn't that precise.
    static func listening(_ seconds: TimeInterval) -> String {
        let (hours, minutes) = listeningParts(seconds)
        if hours >= 100 { return "\(hours.formatted())h" }
        if hours == 0 { return "\(minutes)m" }
        return minutes == 0 ? "\(hours)h" : "\(hours)h \(minutes)m"
    }

    /// Hours and minutes as two parts, for the big number on a card ("28" "hr" "39" "min").
    static func listeningParts(_ seconds: TimeInterval) -> (hours: Int, minutes: Int) {
        let minutes = Int((seconds / 60).rounded())
        return (minutes / 60, minutes % 60)
    }

    /// "+12%", "−8%".
    static func percentChange(_ fraction: Double) -> String {
        let value = Int((fraction * 100).rounded())
        return value > 0 ? "+\(value)%" : value < 0 ? "−\(abs(value))%" : "0%"
    }

    /// "42%".
    static func percent(_ fraction: Double) -> String {
        "\(Int((fraction * 100).rounded()))%"
    }

    static func count(_ value: Int) -> String {
        value.formatted()
    }

    /// "1 play", "12 plays".
    static func plays(_ count: Int) -> String {
        count == 1 ? "1 play" : "\(count.formatted()) plays"
    }

    /// "1990s".
    static func decade(_ decade: Int) -> String {
        "\(decade)s"
    }

    /// "Today", "Yesterday", "Tuesday" within the week, else "Sat, Sep 6", with the year only
    /// for past years. A History section heading.
    static func day(_ date: Date, calendar: Calendar = .current, now: Date = .now) -> String {
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        if let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day,
           days < 7 {
            return date.formatted(.dateTime.weekday(.wide))
        }
        if calendar.isDate(date, equalTo: now, toGranularity: .year) {
            return date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
        }
        return date.formatted(.dateTime.day().month(.abbreviated).year())
    }

    /// "9:24 PM".
    static func time(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }

    /// "Sep 13", with the year only for past years.
    static func shortDate(_ date: Date, calendar: Calendar = .current, now: Date = .now) -> String {
        if calendar.isDate(date, equalTo: now, toGranularity: .year) {
            return date.formatted(.dateTime.month(.abbreviated).day())
        }
        return date.formatted(.dateTime.month(.abbreviated).day().year())
    }

    /// "9 AM".
    static func hour(_ hour: Int, calendar: Calendar = .current) -> String {
        guard let date = calendar.date(from: DateComponents(hour: hour % 24)) else { return "\(hour)" }
        return date.formatted(.dateTime.hour())
    }

    /// The period a summary covers: "This week", "September 2026", "2026", "All time".
    static func period(_ summary: StatsSummary, calendar: Calendar = .current) -> String {
        guard let interval = summary.interval else { return "All time" }
        switch summary.range {
        case .week:
            let end = interval.end.addingTimeInterval(-1)
            return "\(interval.start.formatted(.dateTime.month(.abbreviated).day())) – \(end.formatted(.dateTime.month(.abbreviated).day()))"
        case .month: return interval.start.formatted(.dateTime.month(.wide).year())
        case .year: return interval.start.formatted(.dateTime.year())
        case .allTime: return "All time"
        }
    }

    /// "last week", "last month", "last year": what the listening is compared with.
    static func previous(_ range: StatsRange) -> String {
        switch range {
        case .week: "this time last week"
        case .month: "this time last month"
        case .year: "this time last year"
        case .allTime: ""
        }
    }
}

extension StatsRange {
    var title: String {
        switch self {
        case .week: "Week"
        case .month: "Month"
        case .year: "Year"
        case .allTime: "All time"
        }
    }
}

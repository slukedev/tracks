import Foundation
import SwiftData

/// What the Last Played and Today widgets read from the store. The app runs the same reading
/// to decide when to reload them, so the two can't disagree about what's on screen.
public struct WidgetReading: Sendable {
    /// The most recent song kept, from any day.
    public let lastPlayed: CaptureSnapshot?
    /// Today's listening, newest first.
    public let today: [CaptureSnapshot]

    /// The most rows any widget shows. Widget capacities must stay within these, since the
    /// app only looks this far down when deciding whether to reload.
    public static let mostTodayRows = 6
    /// The Top Songs widget's most covers, and the days it counts back over.
    public static let mostTopSongs = 4
    public static let topSongDays = 7

    public var allSongs: [CaptureSnapshot] {
        [lastPlayed].compactMap(\.self) + today
    }
}

public extension TracksStore {
    /// Reads what a widget with this much room would show.
    func widgetReading(
        todayLimit: Int,
        now: Date = .now,
        calendar: Calendar = .current
    ) throws -> WidgetReading {
        try Self.widgetReading(in: context, todayLimit: todayLimit, now: now, calendar: calendar)
    }

    /// Everything the largest widgets could show, for comparing before and after a save.
    func widgetFaces(
        now: Date = .now,
        calendar: Calendar = .current
    ) throws -> WidgetFaces {
        try Self.widgetFaces(in: context, now: now, calendar: calendar)
    }

    /// ``widgetFaces(now:calendar:)`` read in the background, on a context of its own, so the
    /// app can compare after a save without the main actor doing the reads.
    @concurrent
    nonisolated func widgetFacesInBackground(
        now: Date = .now,
        calendar: Calendar = .current
    ) async throws -> WidgetFaces {
        try Self.widgetFaces(in: ModelContext(container), now: now, calendar: calendar)
    }

    /// The most played songs of the last `days` days, today included, most played first.
    ///
    /// Reads only those days, and only the columns a tally needs, so it fits in a widget
    /// extension's memory however long the history is.
    func widgetTopSongs(
        limit: Int,
        days: Int = WidgetReading.topSongDays,
        now: Date = .now,
        calendar: Calendar = .current
    ) throws -> [SongTally] {
        try Self.widgetTopSongs(in: context, limit: limit, days: days, now: now, calendar: calendar)
    }

    // MARK: - On any context

    nonisolated static func widgetReading(
        in context: ModelContext,
        todayLimit: Int,
        now: Date,
        calendar: Calendar
    ) throws -> WidgetReading {
        let lastPlayed = try context.fetch(allCaptures(limit: 1)).first
        // A fetch limit of zero means no limit, so skip the fetch instead.
        let today = todayLimit > 0
            ? try context.fetch(allCaptures(
                since: calendar.startOfDay(for: now),
                limit: todayLimit
            ))
            : []
        return WidgetReading(
            lastPlayed: lastPlayed?.snapshot,
            today: today.map(\.snapshot)
        )
    }

    nonisolated static func widgetFaces(
        in context: ModelContext,
        now: Date,
        calendar: Calendar
    ) throws -> WidgetFaces {
        let reading = try widgetReading(
            in: context,
            todayLimit: WidgetReading.mostTodayRows,
            now: now,
            calendar: calendar
        )
        return WidgetFaces(
            reading,
            topSongs: try widgetTopSongs(in: context, limit: WidgetReading.mostTopSongs, days: WidgetReading.topSongDays, now: now, calendar: calendar),
            captureCount: try context.fetchCount(FetchDescriptor<Capture>())
        )
    }

    nonisolated static func widgetTopSongs(
        in context: ModelContext,
        limit: Int,
        days: Int,
        now: Date,
        calendar: Calendar
    ) throws -> [SongTally] {
        guard limit > 0, days > 0 else { return [] }
        let today = calendar.startOfDay(for: now)
        let start = calendar.date(byAdding: .day, value: -(days - 1), to: today) ?? today
        var descriptor = allCaptures(since: start)
        descriptor.propertiesToFetch = [
            \.songKey, \.songID, \.title, \.artistName, \.albumTitle, \.artworkURL, \.capturedAt, \.kindRawValue,
        ]
        let rows = try context.fetch(descriptor).filter { $0.capturedAt <= now }
        let history = ListeningHistory(rows.map { row in
            CaptureStat(
                songKey: row.songKey, songID: row.songID, title: row.title, artistName: row.artistName,
                albumTitle: row.albumTitle, artworkURL: row.artworkURL, capturedAt: row.capturedAt, kind: row.kind
            )
        })
        return Array(StatsCalculator.tallySongs(history.captures.indices, in: history).prefix(limit))
    }
}

/// The visible parts of each widget, for comparing before and after a save.
///
/// Most saves (scrobbles, playlist writes, catalog lookups, session updates) change nothing
/// a widget shows. WidgetKit limits how often a background app can reload its widgets, and
/// the Mac app is nearly always in the background, so only visible changes should reload.
public struct WidgetFaces: Equatable, Sendable {
    /// A song as a widget draws it. The row id and capture time aren't shown, so they're
    /// left out.
    struct Song: Equatable, Sendable {
        let title: String
        let artistName: String
        let artworkURL: String?

        init(_ snapshot: CaptureSnapshot) {
            title = snapshot.title
            artistName = snapshot.artistName
            artworkURL = snapshot.artworkURL
        }

        init(_ tally: SongTally) {
            title = tally.title
            artistName = tally.artistName
            artworkURL = tally.artworkURL
        }
    }

    let lastPlayed: Song?
    let today: [Song]
    /// The Top Songs widget, in rank order.
    let topSongs: [Song]
    /// Stands in for the Listening widget. Its time, bars and streak only move when a row is
    /// added or removed, and counting is much cheaper than working them out.
    let captureCount: Int

    init(_ reading: WidgetReading, topSongs: [SongTally], captureCount: Int) {
        lastPlayed = reading.lastPlayed.map(Song.init)
        self.topSongs = topSongs.map(Song.init)
        today = reading.today.map(Song.init)
        self.captureCount = captureCount
    }

    /// The widget kinds that would look different now than at `previous`.
    public func kindsChanged(since previous: WidgetFaces) -> [String] {
        var kinds: [String] = []
        if lastPlayed != previous.lastPlayed { kinds.append(WidgetKind.lastPlayed) }
        if today != previous.today { kinds.append(WidgetKind.today) }
        if captureCount != previous.captureCount { kinds.append(WidgetKind.listening) }
        // Most plays move no song past another, so this reloads far less often than Listening.
        if topSongs != previous.topSongs { kinds.append(WidgetKind.topSongs) }
        return kinds
    }
}

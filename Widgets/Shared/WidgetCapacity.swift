import WidgetKit
import TracksCore

/// The most songs a widget could fit.
///
/// A ceiling: the views show as many whole rows as fit at the current text size (see
/// ``WidgetSongList``). The provider fetches no more than this, so it doesn't download
/// covers it can't draw, and the placeholder uses the same numbers.
struct WidgetCapacity: Sendable, Equatable {
    /// Rows of today's listening.
    let today: Int

    /// Last Played only shows `lastPlayed`, which is always fetched.
    static let lastPlayed = WidgetCapacity(today: 0)

    /// The Today widget's room for its size: what the largest device fits at the default text
    /// size. Keep it within `WidgetReading.mostTodayRows`, since the app only looks that far
    /// down when deciding whether to reload.
    static func today(family: WidgetFamily) -> WidgetCapacity {
        family == .systemLarge ? WidgetCapacity(today: WidgetReading.mostTodayRows) : WidgetCapacity(today: 3)
    }
}

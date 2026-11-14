import Foundation

/// The words for Settings ▸ History: what Tracks keeps, and when a play counts.
///
/// Pure, so tests pin every sentence and the root row, the page's status line and the Mac
/// pane can never disagree.
public enum HistorySettingsWords {
    /// The root row's value: "All Music", "Radio Only" or "Paused".
    public static func short(keepsOnDemand: Bool, isPaused: Bool = false) -> String {
        if isPaused { return String(localized: "Paused") }
        return keepsOnDemand ? String(localized: "All Music") : String(localized: "Radio Only")
    }

    /// The status line under the page's name.
    public static func status(keepsOnDemand: Bool, isPaused: Bool) -> String {
        if isPaused { return String(localized: "Paused") }
        return keepsOnDemand
            ? String(localized: "Keeping everything you play")
            : String(localized: "Keeping radio only")
    }

    /// "50%". Whole percents, since the slider moves in fives.
    public static func percent(_ share: Double) -> String {
        share.formatted(.percent.precision(.fractionLength(0)))
    }

    /// The Counts After row's value: "Straight Away" or "50%".
    public static func countsAfterValue(_ share: Double) -> String {
        guard share > 0 else { return String(localized: "Straight Away") }
        return percent(share)
    }

    /// "10 min", for the Same Song Within row.
    public static func windowValue(minutes: Double) -> String {
        Duration.seconds((minutes * 60).rounded())
            .formatted(.units(allowed: [.minutes], width: .abbreviated))
    }

    /// The counting group's footer: what the repeat window means, after a warning when every
    /// skip would be kept.
    public static func countingFooter(share: Double, windowMinutes: Double) -> String {
        let length = Duration.seconds((windowMinutes * 60).rounded())
            .formatted(.units(allowed: [.minutes], width: .wide))
        let window = String(localized: "Hearing the same song again within \(length) counts once.")
        guard share <= 0 else { return window }
        return String(localized: "Songs you skip are kept too. \(window)")
    }
}

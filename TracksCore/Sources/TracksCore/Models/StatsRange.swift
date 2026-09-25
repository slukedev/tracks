import Foundation

public enum StatsRange: String, Codable, Sendable, CaseIterable {
    case week, month, year, allTime

    /// Week and month are cheap enough to compute per render; the others are cached.
    public var isCached: Bool {
        switch self {
        case .week, .month: false
        case .year, .allTime: true
        }
    }
}

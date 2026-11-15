import Foundation

/// CloudKit doesn't say how much it has left to send, so the size of the history comes from
/// ``HistoryCountBeacon``: every settled device notes in iCloud's key-value store how many
/// plays it holds, and that small value arrives long before the plays themselves.
public enum HistoryRestore: Sendable, Equatable {
    case looking
    case nothingToRestore
    case restoring(restored: Int, expected: Int)
    case arriving(restored: Int)
    case slow(restored: Int, expected: Int)
    case finished(restored: Int)
    case unavailable(String)

    public static let lookingTimeout: TimeInterval = 20
    public static let slowAfter: TimeInterval = 45
    /// How long a restore that has stopped short of the count can sit still before it's
    /// taken as done. Two devices' counts can differ by the duplicates sync folds away.
    public static let settleAfter: TimeInterval = 120
    static let completeShare = 0.98

    public static func resolve(
        expected: Int?,
        restored: Int,
        startedAt: Date,
        lastArrival: Date?,
        blocked: String?,
        now: Date = .now
    ) -> HistoryRestore {
        if let blocked { return .unavailable(blocked) }
        let quietSince = lastArrival ?? startedAt
        let quiet = now.timeIntervalSince(quietSince)

        guard let expected, expected > 0 else {
            if restored > 0 {
                return quiet >= slowAfter ? .finished(restored: restored) : .arriving(restored: restored)
            }
            return now.timeIntervalSince(startedAt) >= lookingTimeout ? .nothingToRestore : .looking
        }
        if Double(restored) >= Double(expected) * completeShare { return .finished(restored: restored) }
        if restored > 0, quiet >= settleAfter, Double(restored) >= Double(expected) * 0.9 {
            return .finished(restored: restored)
        }
        return quiet >= slowAfter
            ? .slow(restored: restored, expected: expected)
            : .restoring(restored: restored, expected: expected)
    }

    public var isSettled: Bool {
        switch self {
        case .nothingToRestore, .finished: true
        case .looking, .restoring, .arriving, .slow, .unavailable: false
        }
    }

    public var isUnderWay: Bool {
        switch self {
        case .restoring, .arriving, .slow: true
        case .looking, .nothingToRestore, .finished, .unavailable: false
        }
    }

    public var fraction: Double? {
        switch self {
        case .restoring(let restored, let expected), .slow(let restored, let expected):
            min(0.99, Double(restored) / Double(max(expected, 1)))
        case .finished: 1
        case .looking, .arriving, .nothingToRestore, .unavailable: nil
        }
    }

    public var title: String {
        switch self {
        case .looking:
            String(localized: "Looking for your history in iCloud…")
        case .nothingToRestore:
            String(localized: "Your history will sync with iCloud")
        case .restoring(_, let expected), .slow(_, let expected):
            expected == 1
                ? String(localized: "Restoring 1 play from iCloud")
                : String(localized: "Restoring \(expected.formatted()) plays from iCloud")
        case .arriving:
            String(localized: "Restoring your history from iCloud")
        case .finished(let restored):
            restored == 1
                ? String(localized: "1 play restored")
                : String(localized: "\(restored.formatted()) plays restored")
        case .unavailable(let reason):
            reason
        }
    }

    public var detail: String? {
        switch self {
        case .restoring(let restored, let expected) where restored > 0:
            String(localized: "\(restored.formatted()) of \(expected.formatted())")
        case .arriving(let restored):
            restored == 1
                ? String(localized: "1 play so far")
                : String(localized: "\(restored.formatted()) plays so far")
        case .slow(let restored, let expected):
            restored > 0
                ? String(localized: "\(restored.formatted()) of \(expected.formatted()) · the rest arrives while you listen")
                : String(localized: "Your plays arrive while you listen")
        default:
            nil
        }
    }
}

/// One key for every device: they all hold the same synced history, so whichever wrote last
/// is as good as any. A device only writes once its own restore has finished, so a new
/// install can't lower the number another one is waiting for.
public struct HistoryCountBeacon {
    static let key = "TracksHistoryPlays"
    static let countField = "count"
    static let dateField = "at"

    private let cloud: KeyValueSyncStore

    public init(cloud: KeyValueSyncStore = NSUbiquitousKeyValueStore.default) {
        self.cloud = cloud
    }

    public var noted: Int? {
        (cloud.object(forKey: Self.key) as? [String: Any])?[Self.countField] as? Int
    }

    public func note(_ count: Int, now: Date = .now) {
        guard count > 0 else { return }
        let stored = cloud.object(forKey: Self.key) as? [String: Any]
        let previous = stored?[Self.countField] as? Int ?? 0
        let notedAt = (stored?[Self.dateField] as? Double).map(Date.init(timeIntervalSince1970:)) ?? .distantPast
        let movedEnough = abs(count - previous) >= max(1, previous / 100)
        guard movedEnough || now.timeIntervalSince(notedAt) > 86_400, count != previous else { return }
        cloud.set([Self.countField: count, Self.dateField: now.timeIntervalSince1970], forKey: Self.key)
    }
}

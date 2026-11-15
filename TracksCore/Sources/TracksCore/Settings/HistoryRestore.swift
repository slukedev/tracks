import Foundation

/// How far bringing the history down from iCloud has got, on a device Tracks was just
/// installed on, and how to say it.
///
/// CloudKit doesn't say how much it has left to send, so the size of the history comes from
/// ``HistoryCountBeacon``: every settled device notes in iCloud's key-value store how many
/// plays it holds, and that small value arrives long before the plays themselves.
public enum HistoryRestore: Sendable, Equatable {
    /// iCloud has said nothing yet about a history to bring down.
    case looking
    /// Nothing in iCloud: someone new to Tracks.
    case nothingToRestore
    case restoring(restored: Int, expected: Int)
    /// Plays are arriving, but no other device has said how many to expect: one still on a
    /// build from before devices noted their counts.
    case arriving(restored: Int)
    /// Restoring, but nothing has arrived for a while.
    case slow(restored: Int, expected: Int)
    case finished(restored: Int)
    /// The history can't come down from iCloud on this device, for the reason given.
    case unavailable(String)

    /// How long to look for a history before taking it that there isn't one.
    public static let lookingTimeout: TimeInterval = 20
    /// How long without a new play before restoring counts as slow.
    public static let slowAfter: TimeInterval = 45
    /// How long a restore that has stopped short of the count can sit still before it's
    /// taken as done. Two devices' counts can differ by the duplicates sync folds away.
    public static let settleAfter: TimeInterval = 120
    /// The share of the expected count that counts as all of it.
    static let completeShare = 0.98

    /// Where a restore stands.
    /// - Parameters:
    ///   - expected: the play count another device noted, if any has.
    ///   - restored: plays this device holds now.
    ///   - startedAt: when this device began looking.
    ///   - lastArrival: when the count here last went up.
    ///   - blocked: why iCloud can't bring the history down, if it can't.
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

    /// Whether there's nothing more to wait for.
    public var isSettled: Bool {
        switch self {
        case .nothingToRestore, .finished: true
        case .looking, .restoring, .arriving, .slow, .unavailable: false
        }
    }

    /// Whether plays are on their way, for showing the restore outside the welcome.
    public var isUnderWay: Bool {
        switch self {
        case .restoring, .arriving, .slow: true
        case .looking, .nothingToRestore, .finished, .unavailable: false
        }
    }

    /// How much has arrived, for a progress bar; nil while there's nothing to measure.
    public var fraction: Double? {
        switch self {
        case .restoring(let restored, let expected), .slow(let restored, let expected):
            min(0.99, Double(restored) / Double(max(expected, 1)))
        case .finished: 1
        case .looking, .arriving, .nothingToRestore, .unavailable: nil
        }
    }

    /// The line that says where it stands.
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

    /// The detail under it, if there is one.
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

/// The play count each settled device notes in iCloud's key-value store, so a device that's
/// just been set up knows how much history to expect.
///
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

    /// The count another device noted, if any has.
    public var noted: Int? {
        (cloud.object(forKey: Self.key) as? [String: Any])?[Self.countField] as? Int
    }

    /// Notes this device's count, when it has moved enough to matter.
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

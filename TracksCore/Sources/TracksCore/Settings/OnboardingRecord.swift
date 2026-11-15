import Foundation

/// Kept on this device. A new iPhone still needs its own Apple Music access and its own
/// restore, even when the Mac finished both long ago.
public struct OnboardingRecord: Codable, Sendable, Equatable {
    public static let storageKey = "TracksOnboarding"

    /// The welcome this build shows. A later build that adds a step it must ask about can
    /// raise it, and only people who saw an older one see it again.
    public static let currentVersion = 1

    public var completedVersion: Int
    public var restoreFinished: Bool

    public init(completedVersion: Int = 0, restoreFinished: Bool = false) {
        self.completedVersion = completedVersion
        self.restoreFinished = restoreFinished
    }

    /// Each field is optional on the way in, so a record written by an older or newer build
    /// still reads rather than starting the welcome over.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        completedVersion = try container.decodeIfPresent(Int.self, forKey: .completedVersion) ?? 0
        restoreFinished = try container.decodeIfPresent(Bool.self, forKey: .restoreFinished) ?? false
    }

    public static let settled = OnboardingRecord(completedVersion: currentVersion, restoreFinished: true)

    public var needsWelcome: Bool { completedVersion < Self.currentVersion }

    public static func load(from defaults: UserDefaults = CaptureSettings.sharedDefaults) -> OnboardingRecord {
        defaults.data(forKey: storageKey)
            .flatMap { try? JSONDecoder().decode(OnboardingRecord.self, from: $0) }
            ?? OnboardingRecord()
    }

    public func save(to defaults: UserDefaults = CaptureSettings.sharedDefaults) {
        guard let data = try? JSONEncoder().encode(self) else { return }
        defaults.set(data, forKey: Self.storageKey)
    }
}

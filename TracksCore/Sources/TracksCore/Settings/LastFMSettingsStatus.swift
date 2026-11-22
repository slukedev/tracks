import Foundation

/// Where a Last.fm connection stands, and how Settings says it.
public enum LastFMSettingsStatus: Sendable, Equatable {
    case notSetUp
    /// Waiting for the person to press Allow on Last.fm's page.
    case connecting
    case connected(username: String, scrobbles: Bool)
    case failed(String)

    /// The status line under the name.
    public var line: String {
        switch self {
        case .notSetUp:
            String(localized: "Scrobble what you play in Tracks.")
        case .connecting:
            String(localized: "Approve Tracks on Last.fm, then come back here.")
        case .connected(let username, true):
            String(localized: "Scrobbling as \(username)")
        case .connected(let username, false):
            String(localized: "Connected as \(username) · scrobbling is off")
        case .failed(let message):
            message
        }
    }

    /// The root row's value.
    public var short: String {
        switch self {
        case .notSetUp, .failed: String(localized: "Not Set Up")
        case .connecting: String(localized: "Connecting…")
        case .connected(let username, true): username
        case .connected(_, false): String(localized: "Off")
        }
    }

    public var tone: SettingsTone {
        if case .failed = self { return .failure }
        return .plain
    }

    public var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }
}

extension UnsentScrobbles {
    /// The Scrobbling group's row, beside its Retry button: "12 Not Sent".
    public var notSentValue: String {
        String(localized: "\(retryable) Not Sent")
    }

    /// The Scrobbling group's footer, when some plays are past what Last.fm takes.
    public var tooOldLine: String? {
        guard tooOld > 0 else { return nil }
        return String(AttributedString(
            localized: "Last.fm takes only the last two weeks, so ^[\(tooOld) play](inflect: true) can’t be sent."
        ).characters)
    }
}

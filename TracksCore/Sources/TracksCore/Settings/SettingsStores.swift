import Foundation

public struct SettingsStores {
    public let appGroup: UserDefaults
    public let app: UserDefaults

    public init(appGroup: UserDefaults, app: UserDefaults) {
        self.appGroup = appGroup
        self.app = app
    }

    /// Without an App Group (unit tests, previews, an unsigned build) both are the standard
    /// defaults.
    public static var live: SettingsStores {
        SettingsStores(appGroup: CaptureSettings.sharedDefaults, app: .standard)
    }

    public func defaults(for domain: StoredSetting.Domain) -> UserDefaults {
        switch domain {
        case .appGroup: appGroup
        case .app: app
        }
    }

    public func defaults(for setting: StoredSetting) -> UserDefaults {
        defaults(for: setting.domain)
    }

    /// Each distinct store once, so a change observer isn't added twice when both domains
    /// are the same defaults.
    var distinct: [UserDefaults] {
        appGroup === app ? [appGroup] : [appGroup, app]
    }

    /// Where the bookkeeping Tracks keeps about settings lives: when each key last changed,
    /// the migration version, a demo's backup. In the App Group, so every process agrees.
    var ledger: UserDefaults { appGroup }
}

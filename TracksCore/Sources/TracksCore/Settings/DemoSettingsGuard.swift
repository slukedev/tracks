import Foundation

/// A demo launch (`-TracksDemoData YES`, Debug builds only) shares the real app's sandbox
/// with sync off, so a switch flipped during it would change this device's settings without
/// ever reaching iCloud.
public struct DemoSettingsGuard {
    static let backupKey = "TracksDemoSettingsBackup"

    private let stores: SettingsStores
    private let registry: SettingsRegistry

    public init(stores: SettingsStores = .live, registry: SettingsRegistry) {
        self.stores = stores
        self.registry = registry
    }

    /// A backup an earlier demo left, with no real launch since, holds the person's values,
    /// not that demo's, so it's kept.
    public func protect() {
        guard !hasBackup else { return }
        var values: [String: Any] = [:]
        for setting in registry.settings {
            if let value = stores.defaults(for: setting).object(forKey: setting.key) {
                values[setting.key] = value
            }
        }
        stores.ledger.set(values, forKey: Self.backupKey)
    }

    @discardableResult
    public func restore() -> Bool {
        guard let values = stores.ledger.dictionary(forKey: Self.backupKey) else { return false }
        for setting in registry.settings {
            let defaults = stores.defaults(for: setting)
            if let value = values[setting.key] {
                defaults.set(value, forKey: setting.key)
            } else {
                defaults.removeObject(forKey: setting.key)
            }
        }
        stores.ledger.removeObject(forKey: Self.backupKey)
        return true
    }

    var hasBackup: Bool { stores.ledger.dictionary(forKey: Self.backupKey) != nil }
}

extension SettingsRegistry {
    /// runs this build's migrations. A demo launch instead keeps a copy to undo, and runs no
    /// migrations, which would otherwise be undone with it and never run again.
    @discardableResult
    public func prepareForLaunch(isDemo: Bool, stores: SettingsStores = .live) -> SettingsMigrator.Origin? {
        let guardian = DemoSettingsGuard(stores: stores, registry: self)
        if isDemo {
            guardian.protect()
            return nil
        }
        guardian.restore()
        return SettingsMigrator(stores: stores, registry: self).run()
    }
}

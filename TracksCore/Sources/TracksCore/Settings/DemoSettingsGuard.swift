import Foundation

/// Keeps a demo launch from changing the person's own settings.
///
/// A demo launch (`-TracksDemoData YES`, Debug builds only) runs in the same sandbox as the
/// real app, so every switch flipped while taking screenshots landed in the real defaults.
/// Sync was off during the demo, so nothing stamped the change, and the next real launch
/// kept it without ever sending it to iCloud: a setting that had quietly changed on one
/// device only. This keeps a copy of every registered setting when a demo starts and puts
/// it back on the next launch that isn't one, however the demo ended.
public struct DemoSettingsGuard {
    static let backupKey = "TracksDemoSettingsBackup"

    private let stores: SettingsStores
    private let registry: SettingsRegistry

    public init(stores: SettingsStores = .live, registry: SettingsRegistry) {
        self.stores = stores
        self.registry = registry
    }

    /// Remembers every setting as it is. A backup an earlier demo left, because nothing has
    /// launched for real since, is kept: it holds the person's values, not that demo's.
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

    /// Puts back what a demo changed, and forgets the backup.
    /// - Returns: whether there was a demo to undo.
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
    /// Gets settings ready before anything reads them: undoes a demo launch's changes, then
    /// runs this build's migrations. A demo launch instead keeps a copy to undo, and runs no
    /// migrations, which would otherwise be undone with it and never run again.
    /// - Returns: how this install began, the first time it's known.
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

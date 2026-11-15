import Foundation

/// Brings settings stored by an earlier build up to date, once per version, before anything
/// reads them.
///
/// Each step only fills in a value that's missing and never deletes the one it read, so a
/// step is safe to run again, and an older build, on another device or after a downgrade,
/// still finds the keys it knows. The version only ever goes up: a build that finds a newer
/// one than it knows runs nothing, and leaves the number for the newer build.
public struct SettingsMigrator {

    /// Which migrations this device has run, in the App Group.
    static let versionKey = "TracksSettingsVersion"
    /// The synced keys a fresh install had nothing of its own for. See
    /// ``SettingsSync`` and ``keysUnseeded(in:)``.
    static let unseededKeysKey = "TracksSettingsUnseeded"

    /// The newest migration this build knows.
    public static let currentVersion = 1

    /// How this install began, as the first build with the migrator found it.
    public enum Origin: Sendable, Equatable {
        /// Nothing of the person's was here: a first install, or a reinstall.
        case fresh
        /// An earlier build had already kept settings here.
        case upgrade
    }

    private let stores: SettingsStores
    private let registry: SettingsRegistry

    public init(stores: SettingsStores = .live, registry: SettingsRegistry) {
        self.stores = stores
        self.registry = registry
    }

    /// Runs whatever this device hasn't run yet.
    /// - Returns: how this install began, the first time; nil once it's known.
    @discardableResult
    public func run() -> Origin? {
        let ledger = stores.ledger
        let hasRunBefore = ledger.object(forKey: Self.versionKey) != nil
        let version = ledger.integer(forKey: Self.versionKey)
        guard version < Self.currentVersion else { return nil }

        var origin: Origin?
        if !hasRunBefore {
            let found = hasAnythingStored
            origin = found ? .upgrade : .fresh
            if found {
                OnboardingRecord.settled.save(to: ledger)
            } else {
                ledger.set(registry.synced.map(\.key).sorted(), forKey: Self.unseededKeysKey)
            }
        }

        if version < 1 {
            carryOverFormerKeys()
            carryOverStraightAway()
        }

        ledger.set(Self.currentVersion, forKey: Self.versionKey)
        return origin
    }

    /// Whether an earlier build left any setting, or the sync ledger, behind.
    private var hasAnythingStored: Bool {
        if stores.ledger.object(forKey: SettingsSync.modifiedAtKey) != nil { return true }
        if stores.appGroup.object(forKey: CaptureSettings.legacyMinimumListenKey) != nil { return true }
        // Set the first time any earlier build opened the history.
        if stores.appGroup.object(forKey: CloudSync.stampedKey) != nil { return true }
        return registry.settings.contains { setting in
            // The device's identity is minted on first use by any process, widgets included,
            // so it says nothing about whether the person ever ran Tracks here.
            setting.key != DeviceIdentity.defaultsKey
                && ([setting.key] + setting.formerKeys).contains {
                    stores.defaults(for: setting).object(forKey: $0) != nil
                }
        }
    }

    /// Copies each renamed setting's old value into its new key, where the new key is empty.
    private func carryOverFormerKeys() {
        for setting in registry.settings where !setting.formerKeys.isEmpty {
            let defaults = stores.defaults(for: setting)
            guard defaults.object(forKey: setting.key) == nil,
                  let value = setting.formerKeys.lazy.compactMap({ defaults.object(forKey: $0) }).first
            else { continue }
            defaults.set(value, forKey: setting.key)
        }
    }

    /// Keeps Straight Away for someone who chose it when a play counted after a set time.
    /// Any other time has no matching share, so it gives way to the default.
    private func carryOverStraightAway() {
        let defaults = stores.appGroup
        guard defaults.object(forKey: CaptureSettings.minimumListenShareKey) == nil,
              defaults.object(forKey: CaptureSettings.legacyMinimumListenKey) != nil,
              defaults.double(forKey: CaptureSettings.legacyMinimumListenKey) <= 0
        else { return }
        defaults.set(0.0, forKey: CaptureSettings.minimumListenShareKey)
    }

    /// The synced keys this install had nothing of its own for when it first ran, which
    /// ``SettingsSync`` doesn't seed. Empty for an install that began as an upgrade.
    static func keysUnseeded(in stores: SettingsStores) -> Set<String> {
        Set(stores.ledger.stringArray(forKey: unseededKeysKey) ?? [])
    }
}

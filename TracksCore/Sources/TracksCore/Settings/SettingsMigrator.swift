import Foundation

/// Each step only fills in a value that's missing and never deletes the one it read, so a
/// step is safe to run again, and an older build, on another device or after a downgrade,
/// still finds the keys it knows. The version only ever goes up: a build that finds a newer
/// one than it knows runs nothing, and leaves the number for the newer build.
public struct SettingsMigrator {

    static let versionKey = "TracksSettingsVersion"
    static let unseededKeysKey = "TracksSettingsUnseeded"

    public static let currentVersion = 1

    public enum Origin: Sendable, Equatable {
        case fresh
        case upgrade
    }

    private let stores: SettingsStores
    private let registry: SettingsRegistry

    public init(stores: SettingsStores = .live, registry: SettingsRegistry) {
        self.stores = stores
        self.registry = registry
    }

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

    private var hasAnythingStored: Bool {
        if stores.ledger.object(forKey: SettingsSync.modifiedAtKey) != nil { return true }
        if stores.appGroup.object(forKey: CaptureSettings.legacyMinimumListenKey) != nil { return true }
        // Set the first time any earlier build opened the history.
        if stores.appGroup.object(forKey: CloudSync.stampedKey) != nil { return true }
        return registry.settings.contains { setting in
            // The device's identity is minted on first use by any process, widgets included,
            // so it says nothing about whether the person ever ran Tracks here.
            !DeviceIdentity.storedKeys.contains(setting.key)
                && ([setting.key] + setting.formerKeys).contains {
                    stores.defaults(for: setting).object(forKey: $0) != nil
                }
        }
    }

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

    static func keysUnseeded(in stores: SettingsStores) -> Set<String> {
        Set(stores.ledger.stringArray(forKey: unseededKeysKey) ?? [])
    }
}

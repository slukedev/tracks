import Foundation

/// One setting Tracks keeps, where it's kept, and whether it follows the person to their
/// other devices.
///
/// Every setting is listed once in a ``SettingsRegistry``. ``SettingsSync`` mirrors the ones
/// that reach every device through iCloud's key-value store, ``SettingsMigrator`` carries
/// values over from the keys they used to have, and ``DemoSettingsGuard`` puts them back
/// after a demo launch has changed them.
public struct StoredSetting: Sendable, Hashable {

    /// Which `UserDefaults` holds the value.
    public enum Domain: Sendable, Hashable {
        /// The App Group's suite, which widgets, intents and the menu bar read without
        /// opening the model container. See ``CaptureSettings/sharedDefaults``.
        case appGroup
        /// The app's own `UserDefaults.standard`, where `@AppStorage` writes without a store.
        case app
    }

    /// How to settle a key that both devices have written.
    public enum Merge: Sendable, Hashable {
        /// The most recent write wins. Right for a value the user sets outright, where a
        /// later change is meant to replace the earlier one.
        case newestWins

        /// Both devices' entries are kept. Right for a list that only ever grows, where
        /// newest-wins would silently drop whatever the other device added. The result
        /// doesn't depend on which device merges first, so every device lands on the same
        /// set without having to agree on an order.
        case union
    }

    /// Whether the value travels.
    public enum Reach: Sendable, Hashable {
        /// The same on every device signed in to the same iCloud account.
        case everyDevice(Merge)
        /// Kept on this device only: what depends on the device itself (its speakers, its
        /// storage, its network, its window layout) or is the device's own identity.
        case thisDevice
    }

    /// What ``SettingsSync`` should do with one key, having compared the two copies.
    public enum Decision: Sendable, Hashable {
        case keepLocal
        case takeRemote
        case unionBoth
    }

    public let key: String
    public let domain: Domain
    public let reach: Reach
    /// Keys this setting was stored under before, newest first. ``SettingsMigrator`` copies
    /// the first one it finds into ``key`` when ``key`` has no value yet, and leaves the old
    /// key in place so an older build on another device, or after a downgrade, still reads it.
    public let formerKeys: [String]

    public init(
        key: String,
        domain: Domain = .appGroup,
        reach: Reach = .everyDevice(.newestWins),
        formerKeys: [String] = []
    ) {
        self.key = key
        self.domain = domain
        self.reach = reach
        self.formerKeys = formerKeys
    }

    /// A setting that follows the person to every device.
    public static func everyDevice(
        _ key: String,
        in domain: Domain = .appGroup,
        merge: Merge = .newestWins,
        formerKeys: [String] = []
    ) -> StoredSetting {
        StoredSetting(key: key, domain: domain, reach: .everyDevice(merge), formerKeys: formerKeys)
    }

    /// A setting kept on this device only.
    public static func thisDevice(
        _ key: String,
        in domain: Domain = .appGroup,
        formerKeys: [String] = []
    ) -> StoredSetting {
        StoredSetting(key: key, domain: domain, reach: .thisDevice, formerKeys: formerKeys)
    }

    public var syncs: Bool { merge != nil }

    public var merge: Merge? {
        switch reach {
        case .everyDevice(let merge): merge
        case .thisDevice: nil
        }
    }

    /// Which copy of this setting to keep.
    ///
    /// The timestamps are when each side last changed the value, not when it was synced, so
    /// a device that changed a setting offline still wins over one that only read it.
    /// A missing timestamp means that side has never written the key.
    ///
    /// A remote copy dated 1970 is a seed: a value another device had lying around when it
    /// first mirrored, not one anybody chose since. It fills a gap here but never replaces a
    /// value this device already holds. Replacing it was how an update reset the iPhone's
    /// settings to whatever the Mac happened to have, the first time a setting began to sync.
    public func decide(
        localModifiedAt: Date?,
        remoteModifiedAt: Date?,
        hasLocalValue: Bool = false
    ) -> Decision {
        switch reach {
        case .thisDevice:
            return .keepLocal
        case .everyDevice(.union):
            return .unionBoth
        case .everyDevice(.newestWins):
            guard let remoteModifiedAt else { return .keepLocal }
            guard let localModifiedAt else {
                let isSeed = remoteModifiedAt.timeIntervalSince1970 <= 0
                return isSeed && hasLocalValue ? .keepLocal : .takeRemote
            }
            // Ties keep the local copy: the values are then almost always equal anyway, and
            // this stops two devices handing one change back and forth.
            return remoteModifiedAt > localModifiedAt ? .takeRemote : .keepLocal
        }
    }
}

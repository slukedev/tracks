import Foundation
import Synchronization

/// The CloudKit container that mirrors the store between the user's devices.
///
/// Like ``AppGroup``, the identifier comes from Info.plist. It's derived from the bundle ID
/// in the xcconfig, and an unresolved build setting reads as nil.
public enum CloudSync {
    public static let infoPlistKey = "TracksCloudContainerIdentifier"

    /// Nil in unit tests, previews and unsigned builds.
    public static var containerIdentifier: String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: infoPlistKey) as? String,
              !value.isEmpty,
              !value.contains("$(")
        else { return nil }
        return value
    }

    /// Whether this build can reach iCloud at all.
    ///
    /// False in unit tests, previews and unsigned builds, where the container identifier is
    /// missing and the entitlements have been stripped.
    public static var isConfigured: Bool { containerIdentifier != nil }

    /// The App Group's defaults, so the app and its extensions agree.
    static var defaults: UserDefaults {
        AppGroup.identifier.flatMap { UserDefaults(suiteName: $0) } ?? .standard
    }

    // Renaming them would turn sync back on for anyone who turned it off, and run the
    // legacy stamp again.
    static let enabledKey = "TracksCloudSyncEnabled"
    static let stampedKey = "TracksCloudSyncDidStampLegacyCaptures"

    /// Whether to mirror the store to iCloud. On by default.
    ///
    /// Read once when the store opens (SwiftData fixes the CloudKit configuration when the
    /// container is created), so a change takes effect on the next launch.
    public static var isEnabled: Bool {
        get { defaults.object(forKey: enabledKey) as? Bool ?? true }
        set { defaults.set(newValue, forKey: enabledKey) }
    }

    /// Whether captures from before this device synced have its ID yet.
    /// See ``TracksStore/stampLegacyCaptures()``.
    static var hasStampedLegacyCaptures: Bool {
        get { defaults.bool(forKey: stampedKey) }
        set { defaults.set(newValue, forKey: stampedKey) }
    }
}

/// A stable, random identifier for this device.
///
/// Each capture records the device that saw it, and only that device writes it to the
/// playlist, scrobbles it and extends its session. Otherwise the Mac and iPhone would both
/// act on the same synced row. A random UUID is used instead of `identifierForVendor` or the
/// hardware UUID so iCloud learns nothing about the machine.
///
/// It lives in the Keychain as "this device only", which a backup restored onto another
/// device, Quick Start and Migration Assistant don't carry over, so a new device never
/// takes on an old one's identity. A copy stays in the App Group defaults for builds and
/// processes that can't read the Keychain.
public enum DeviceIdentity {
    // Don't rename any of these. A new key would mint a new identity and strand this device's
    // pending playlist writes and scrobbles.
    static let defaultsKey = "TracksDeviceIdentifier"
    /// Set once the identity in defaults is also in the Keychain. Defaults that carry it but
    /// have no Keychain item beside them were copied from another device.
    static let keychainKey = "TracksDeviceIdentifierInKeychain"
    /// Identities of devices this one was restored or migrated from, newest last.
    static let predecessorsKey = "TracksDeviceIdentifierPredecessors"
    /// Everything above that any process can write on first use, widgets included.
    static let storedKeys = [defaultsKey, keychainKey, predecessorsKey]
    static let keychainItem = KeychainItem(service: "com.luke.tracks.device", account: "identifier")

    private static let resolved = Mutex<String?>(nil)

    public static var current: String {
        resolved.withLock { cached in
            if let cached { return cached }
            let identifier = Resolver(keychain: SystemKeychain(), defaults: CloudSync.defaults).resolve()
            cached = identifier
            return identifier
        }
    }

    /// The identities of devices this one was restored or migrated from, whose rows came
    /// with the backup. See ``Resolver/resolve()``.
    public static var predecessors: [String] {
        CloudSync.defaults.stringArray(forKey: predecessorsKey) ?? []
    }

    static let predecessorLimit = 8

    /// How long a predecessor's rows and open sessions are left to it, since it may still be
    /// in use and only offline, before this device takes them over.
    public static let predecessorDelay: TimeInterval = 24 * 60 * 60

    struct Resolver {
        let keychain: any KeychainStoring
        let defaults: UserDefaults
        var mint: () -> String = { UUID().uuidString }

        /// Reads this device's identity, minting or moving it into the Keychain as needed.
        ///
        /// - In the Keychain: that's it, and defaults are brought in line.
        /// - Only in defaults, never moved: a device upgrading from a build that kept it
        ///   there. It moves to the Keychain and stays the same, so nothing it captured
        ///   changes hands.
        /// - Only in defaults, already moved: those defaults came from another device's
        ///   backup. This device gets its own, and remembers the old one as a predecessor.
        /// - The Keychain can't be read (before the first unlock, or a build without keychain
        ///   access): whatever defaults hold, without minting over it.
        func resolve() -> String {
            let stored = defaults.string(forKey: defaultsKey).flatMap(Self.valid)
            switch keychain.read(keychainItem) {
            case .found(let data):
                if let identifier = Self.valid(String(decoding: data, as: UTF8.self)) {
                    if stored != identifier {
                        if let stored { remember(predecessor: stored) }
                        defaults.set(identifier, forKey: defaultsKey)
                    }
                    defaults.set(true, forKey: keychainKey)
                    return identifier
                }
                return adopt(stored, wasMoved: defaults.bool(forKey: keychainKey))
            case .missing:
                return adopt(stored, wasMoved: defaults.bool(forKey: keychainKey))
            case .unavailable:
                if let stored { return stored }
                let identifier = mint()
                defaults.set(identifier, forKey: defaultsKey)
                return identifier
            }
        }

        private func adopt(_ stored: String?, wasMoved: Bool) -> String {
            let identifier: String
            if let stored, !wasMoved {
                identifier = stored
            } else {
                identifier = mint()
                if let stored { remember(predecessor: stored) }
            }
            defaults.set(identifier, forKey: defaultsKey)
            switch keychain.write(Data(identifier.utf8), to: keychainItem, accessibility: .afterFirstUnlockThisDeviceOnly) {
            case errSecSuccess:
                defaults.set(true, forKey: keychainKey)
            default:
                // Tried again on the next launch. Until then defaults hold it, unmoved.
                defaults.set(false, forKey: keychainKey)
            }
            return identifier
        }

        private func remember(predecessor: String) {
            var predecessors = defaults.stringArray(forKey: predecessorsKey) ?? []
            predecessors.removeAll { $0 == predecessor }
            predecessors.append(predecessor)
            defaults.set(Array(predecessors.suffix(predecessorLimit)), forKey: predecessorsKey)
        }

        private static func valid(_ value: String) -> String? {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
    }
}

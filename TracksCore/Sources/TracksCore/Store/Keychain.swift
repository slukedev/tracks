import Foundation
import Security

/// One generic password: a service and an account.
struct KeychainItem: Hashable, Sendable {
    let service: String
    let account: String
    /// The Mac's older file-based keychain, which earlier Mac builds used, rather than the
    /// data protection one. Only the data protection keychain honours "this device only".
    var isLegacyMacKeychain = false
}

enum KeychainAccessibility: Sendable {
    /// Readable once the device has been unlocked after starting up, and restored with an
    /// encrypted backup onto another device.
    case afterFirstUnlock
    /// The same, but never moved to another device by a backup, Quick Start or Migration
    /// Assistant.
    case afterFirstUnlockThisDeviceOnly
}

enum KeychainRead: Equatable, Sendable {
    case found(Data)
    case missing
    /// Couldn't be read right now: before the first unlock, or in a build without keychain
    /// access. Not the same as missing, so nothing is minted or deleted because of it.
    case unavailable(OSStatus)
}

/// The keychain calls Tracks makes, so code that keeps secrets and identities can be tested
/// without touching the developer's own keychain.
protocol KeychainStoring: Sendable {
    func read(_ item: KeychainItem) -> KeychainRead
    /// Replaces the item's data and accessibility, adding the item if it's missing.
    func write(_ data: Data, to item: KeychainItem, accessibility: KeychainAccessibility) -> OSStatus
    @discardableResult
    func delete(_ item: KeychainItem) -> OSStatus
}

struct SystemKeychain: KeychainStoring {
    func read(_ item: KeychainItem) -> KeychainRead {
        var query = Self.query(for: item)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data else { return .missing }
            return .found(data)
        case errSecItemNotFound:
            return .missing
        default:
            return .unavailable(status)
        }
    }

    func write(_ data: Data, to item: KeychainItem, accessibility: KeychainAccessibility) -> OSStatus {
        let query = Self.query(for: item)
        var attributes: [String: Any] = [kSecValueData as String: data]
        if !item.isLegacyMacKeychain {
            attributes[kSecAttrAccessible as String] = Self.value(for: accessibility)
        }
        let updated = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        guard updated == errSecItemNotFound else { return updated }
        let added = SecItemAdd(query.merging(attributes) { $1 } as CFDictionary, nil)
        // Another process added it between the two calls.
        guard added == errSecDuplicateItem else { return added }
        return SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    }

    @discardableResult
    func delete(_ item: KeychainItem) -> OSStatus {
        SecItemDelete(Self.query(for: item) as CFDictionary)
    }

    private static func query(for item: KeychainItem) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: item.service,
            kSecAttrAccount as String: item.account,
        ]
        if !item.isLegacyMacKeychain {
            query[kSecUseDataProtectionKeychain as String] = true
        }
        return query
    }

    private static func value(for accessibility: KeychainAccessibility) -> CFString {
        switch accessibility {
        case .afterFirstUnlock: kSecAttrAccessibleAfterFirstUnlock
        case .afterFirstUnlockThisDeviceOnly: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        }
    }
}

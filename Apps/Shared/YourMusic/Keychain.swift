import Foundation
import Security

/// Server passwords, kept in the Keychain on this device only, never in defaults or iCloud.
enum ServerKeychain {
    private static let service = "com.luke.tracks.music-server"
    /// Readable in the background once the phone has been unlocked, so downloads and playback
    /// carry on with the screen locked; never synced or restored to other devices.
    private static let accessible = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

    /// What reading a password found.
    enum Read: Equatable {
        case found(String)
        /// There's none: never saved, or removed.
        case missing
        /// The Keychain can't be read now, as before the first unlock after a restart. The
        /// password is still there.
        case unavailable
    }

    /// The item every query names. On the Mac, the data protection keychain, which is the one
    /// that honours "this device only"; the older file keychain would ignore it.
    private static func item(for serverID: String) -> [CFString: Any] {
        var item: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: serverID,
        ]
        #if os(macOS)
        item[kSecUseDataProtectionKeychain] = true
        #endif
        return item
    }

    static func read(for serverID: String) -> Read {
        var query = item(for: serverID)
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var result: CFTypeRef?
        switch SecItemCopyMatching(query as CFDictionary, &result) {
        case errSecSuccess:
            guard let data = result as? Data, let text = String(data: data, encoding: .utf8) else { return .missing }
            return .found(text)
        case errSecItemNotFound:
            return .missing
        default:
            return .unavailable
        }
    }

    static func password(for serverID: String) -> String? {
        if case .found(let password) = read(for: serverID) { return password }
        return nil
    }

    /// Updates the password in place, so a failure never leaves it gone, and moves an item
    /// saved by an older build to this device-only class as it does.
    @discardableResult
    static func setPassword(_ password: String, for serverID: String) -> Bool {
        let match = item(for: serverID)
        let data = Data(password.utf8)
        let update: [CFString: Any] = [kSecValueData: data, kSecAttrAccessible: accessible]
        switch SecItemUpdate(match as CFDictionary, update as CFDictionary) {
        case errSecSuccess:
            return true
        case errSecItemNotFound:
            var add = match
            add[kSecValueData] = data
            add[kSecAttrAccessible] = accessible
            return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
        default:
            return false
        }
    }

    static func removePassword(for serverID: String) {
        SecItemDelete(item(for: serverID) as CFDictionary)
    }
}

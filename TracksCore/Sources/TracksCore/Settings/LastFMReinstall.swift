import Foundation
import Security

extension LastFMSessionStore {
    /// Deleting the app empties its defaults, where the username lives, but the Keychain
    /// keeps the session key. With exactly one account's key there, this puts the username
    /// back, and the account is connected as it was without signing in to Last.fm again.
    /// More than one would mean guessing, so then it leaves the account to be connected.
    @discardableResult
    public static func recoverAfterReinstall() -> String? {
        guard defaults.string(forKey: usernameKey) == nil else { return nil }
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecUseDataProtectionKeychain as String: true,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var items: CFTypeRef?
        var status = SecItemCopyMatching(query as CFDictionary, &items)
        #if os(macOS)
        if status == errSecItemNotFound || status == errSecMissingEntitlement {
            query.removeValue(forKey: kSecUseDataProtectionKeychain as String)
            status = SecItemCopyMatching(query as CFDictionary, &items)
        }
        #endif
        guard status == errSecSuccess,
              let found = items as? [[String: Any]],
              found.count == 1,
              let username = found[0][kSecAttrAccount as String] as? String,
              !username.isEmpty,
              readKey(for: username) != nil
        else { return nil }
        defaults.set(username, forKey: usernameKey)
        return username
    }
}

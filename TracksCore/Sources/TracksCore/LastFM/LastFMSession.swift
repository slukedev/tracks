import Foundation
import Security

/// The user's Last.fm session key.
///
/// It never expires and is enough to scrobble as the user, so it's kept in the Keychain,
/// not in the App Group defaults every extension can read. Only the username goes there.
public struct LastFMSession: Sendable, Equatable {
    public let username: String
    public let sessionKey: String

    public init(username: String, sessionKey: String) {
        self.username = username
        self.sessionKey = sessionKey
    }
}

public enum LastFMSessionStore {
    // Renaming either signs every existing user out of Last.fm.
    static let service = "com.luke.tracks.lastfm"
    static let usernameKey = "TracksLastFMUsername"

    /// Where the username lives, so settings can show who's connected without the Keychain.
    static var defaults: UserDefaults {
        AppGroup.identifier.flatMap { UserDefaults(suiteName: $0) } ?? .standard
    }

    static var live: Vault { Vault(keychain: SystemKeychain(), defaults: defaults) }

    public static var current: LastFMSession? { live.current }

    /// - Returns: whether it was saved. When it wasn't, the account connected before, if
    ///   any, is still the one in use.
    @discardableResult
    public static func save(_ session: LastFMSession) -> Bool { live.save(session) }

    public static func clear() { live.clear() }

    static func readKey(for username: String) -> String? { live.readKey(for: username) }

    /// The session in the Keychain, and the username beside it in `defaults`.
    struct Vault {
        let keychain: any KeychainStoring
        let defaults: UserDefaults

        var current: LastFMSession? {
            guard let username = defaults.string(forKey: usernameKey),
                  let key = readKey(for: username)
            else { return nil }
            return LastFMSession(username: username, sessionKey: key)
        }

        /// Stores the key in place, then the username, then forgets the account it replaced.
        /// Nothing is deleted first, so a Keychain that refuses the write (locked, or a
        /// build without an access group) leaves the previous session working.
        func save(_ session: LastFMSession) -> Bool {
            let data = Data(session.sessionKey.utf8)
            // The app can launch at login before the first unlock.
            var status = keychain.write(data, to: Self.item(for: session.username), accessibility: .afterFirstUnlock)
            #if os(macOS)
            if status == errSecMissingEntitlement {
                // A build without a keychain access group can only use the file-based keychain.
                status = keychain.write(data, to: Self.legacyItem(for: session.username), accessibility: .afterFirstUnlock)
            } else if status == errSecSuccess {
                keychain.delete(Self.legacyItem(for: session.username))
            }
            #endif
            guard status == errSecSuccess else {
                Log.lastFM.error("Couldn't save the Last.fm session: \(status, privacy: .public)")
                return false
            }
            let previous = defaults.string(forKey: usernameKey)
            defaults.set(session.username, forKey: usernameKey)
            if let previous, previous != session.username { deleteKey(for: previous) }
            return true
        }

        func clear() {
            if let username = defaults.string(forKey: usernameKey) {
                deleteKey(for: username)
            }
            defaults.removeObject(forKey: usernameKey)
        }

        /// The data protection keychain, which on the Mac isn't tied to the code signature
        /// the way the file-based one is. iOS only has this one.
        static func item(for username: String) -> KeychainItem {
            KeychainItem(service: service, account: username)
        }

        /// Where earlier Mac builds kept the key.
        static func legacyItem(for username: String) -> KeychainItem {
            KeychainItem(service: service, account: username, isLegacyMacKeychain: true)
        }

        private func deleteKey(for username: String) {
            keychain.delete(Self.item(for: username))
            #if os(macOS)
            keychain.delete(Self.legacyItem(for: username))
            #endif
        }

        func readKey(for username: String) -> String? {
            if let key = key(in: keychain.read(Self.item(for: username))) { return key }
            #if os(macOS)
            // Moved across the first time it's read, so a Mac that connected before this keeps
            // its account. The old copy stays until the move has worked.
            guard let key = key(in: keychain.read(Self.legacyItem(for: username))) else { return nil }
            if keychain.write(Data(key.utf8), to: Self.item(for: username), accessibility: .afterFirstUnlock) == errSecSuccess {
                keychain.delete(Self.legacyItem(for: username))
            }
            return key
            #else
            return nil
            #endif
        }

        private func key(in read: KeychainRead) -> String? {
            guard case .found(let data) = read,
                  let key = String(data: data, encoding: .utf8),
                  !key.isEmpty
            else { return nil }
            return key
        }
    }
}

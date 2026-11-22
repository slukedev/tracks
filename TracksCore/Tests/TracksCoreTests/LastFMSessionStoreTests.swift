import Testing
import Foundation
import Security
@testable import TracksCore

/// Keeping the Last.fm session: a Keychain that refuses a write mustn't sign the person out
/// or leave Settings saying Connected with no key behind it.
@Suite("Last.fm session store")
struct LastFMSessionStoreTests {
    let keychain = FakeKeychain()
    private let scratch = ScratchDefaults()

    var vault: LastFMSessionStore.Vault {
        LastFMSessionStore.Vault(keychain: keychain, defaults: scratch.defaults)
    }

    let first = LastFMSession(username: "listener", sessionKey: "first-key")

    @Test("a saved session is read back")
    func roundTrip() {
        #expect(vault.save(first))
        #expect(vault.current == first)
    }

    /// The key used to be deleted before the new one was added, so a refused add (locked,
    /// or a build without an access group) signed the person out of the account they had.
    @Test("a refused save keeps the account that was connected", arguments: [errSecInteractionNotAllowed, errSecMissingEntitlement, errSecNotAvailable])
    func refusedSaveKeepsThePreviousSession(status: OSStatus) {
        #expect(vault.save(first))
        keychain.refusesWrites = status

        #expect(!vault.save(LastFMSession(username: "listener", sessionKey: "second-key")))
        #expect(!vault.save(LastFMSession(username: "someone-else", sessionKey: "other-key")))

        #expect(vault.current == first)
    }

    @Test("a refused first save leaves nothing connected")
    func refusedFirstSave() {
        keychain.refusesWrites = errSecInteractionNotAllowed
        #expect(!vault.save(first))
        #expect(vault.current == nil)
        #expect(scratch.defaults.string(forKey: LastFMSessionStore.usernameKey) == nil)
    }

    @Test("reconnecting the same account replaces its key")
    func reconnectReplacesKey() {
        #expect(vault.save(first))
        let renewed = LastFMSession(username: "listener", sessionKey: "renewed-key")
        #expect(vault.save(renewed))
        #expect(vault.current == renewed)
        #expect(keychain.items.count == 1)
    }

    /// A key left behind for the old account also stopped the reinstall recovery, which only
    /// restores an account when exactly one key is there.
    @Test("connecting another account forgets the old one's key")
    func switchingAccountsForgetsTheOldKey() {
        #expect(vault.save(first))
        let other = LastFMSession(username: "someone-else", sessionKey: "other-key")
        #expect(vault.save(other))
        #expect(vault.current == other)
        #expect(keychain.items.keys.map(\.account) == ["someone-else"])
    }

    @Test("a Keychain that can't be read yet shows no session and deletes nothing")
    func lockedKeychain() {
        #expect(vault.save(first))
        keychain.refusesReads = errSecInteractionNotAllowed
        #expect(vault.current == nil)
        keychain.refusesReads = nil
        #expect(vault.current == first)
    }

    @Test("signing out removes the key and the username")
    func clear() {
        #expect(vault.save(first))
        vault.clear()
        #expect(vault.current == nil)
        #expect(keychain.items.isEmpty)
    }
}

/// An in-memory Keychain that can be made to refuse reads or writes.
final class FakeKeychain: KeychainStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var _items: [KeychainItem: (data: Data, accessibility: KeychainAccessibility)] = [:]
    private var _refusesWrites: OSStatus?
    private var _refusesReads: OSStatus?

    var items: [KeychainItem: (data: Data, accessibility: KeychainAccessibility)] { lock.withLock { _items } }
    var refusesWrites: OSStatus? {
        get { lock.withLock { _refusesWrites } }
        set { lock.withLock { _refusesWrites = newValue } }
    }
    var refusesReads: OSStatus? {
        get { lock.withLock { _refusesReads } }
        set { lock.withLock { _refusesReads = newValue } }
    }

    func read(_ item: KeychainItem) -> KeychainRead {
        lock.withLock {
            if let status = _refusesReads { return .unavailable(status) }
            return _items[item].map { .found($0.data) } ?? .missing
        }
    }

    func write(_ data: Data, to item: KeychainItem, accessibility: KeychainAccessibility) -> OSStatus {
        lock.withLock {
            if let status = _refusesWrites { return status }
            _items[item] = (data, accessibility)
            return errSecSuccess
        }
    }

    @discardableResult
    func delete(_ item: KeychainItem) -> OSStatus {
        lock.withLock { _items.removeValue(forKey: item) == nil ? errSecItemNotFound : errSecSuccess }
    }
}

import Testing
import Foundation
import Security
@testable import TracksCore

/// The identity each capture is stamped with. It decides which device scrobbles a row,
/// writes it to the playlist and extends its session, so two devices must never share one,
/// and one device must keep its own.
@Suite("Device identity")
struct CaptureDeviceIdentityTests {
    let keychain = FakeKeychain()
    private let scratch = ScratchDefaults()
    private let minted = Counter()

    var resolver: DeviceIdentity.Resolver {
        var resolver = DeviceIdentity.Resolver(keychain: keychain, defaults: scratch.defaults)
        let minted = minted
        resolver.mint = { "minted-\(minted.next())" }
        return resolver
    }

    var predecessors: [String] { scratch.defaults.stringArray(forKey: DeviceIdentity.predecessorsKey) ?? [] }
    var inKeychain: String? {
        guard case .found(let data) = keychain.read(DeviceIdentity.keychainItem) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    @Test("a fresh install mints one identity and keeps it, in the Keychain for this device only")
    func freshInstall() {
        let identity = resolver.resolve()
        #expect(identity == "minted-1")
        #expect(resolver.resolve() == identity)
        #expect(inKeychain == identity)
        #expect(keychain.items[DeviceIdentity.keychainItem]?.accessibility == .afterFirstUnlockThisDeviceOnly)
        #expect(predecessors.isEmpty)
    }

    /// Every existing install has its identity in defaults only. Minting a new one would
    /// strand its pending scrobbles and playlist writes, and give its history a second name.
    @Test("an install from before the Keychain keeps its identity, however often it launches")
    func upgradeKeepsTheIdentity() {
        scratch.defaults.set("existing", forKey: DeviceIdentity.defaultsKey)

        #expect(resolver.resolve() == "existing")
        #expect(resolver.resolve() == "existing")

        #expect(inKeychain == "existing")
        #expect(predecessors.isEmpty)
        #expect(minted.count == 0)
    }

    /// An iPad restored from the iPhone's backup, or a new phone set up by Quick Start, has
    /// the iPhone's defaults but not its "this device only" Keychain item.
    @Test("defaults copied from another device get a new identity, and the old one is remembered")
    func restoredOntoAnotherDevice() {
        scratch.defaults.set("old-phone", forKey: DeviceIdentity.defaultsKey)
        scratch.defaults.set(true, forKey: DeviceIdentity.keychainKey)

        let identity = resolver.resolve()

        #expect(identity == "minted-1")
        #expect(resolver.resolve() == identity)
        #expect(inKeychain == identity)
        #expect(scratch.defaults.string(forKey: DeviceIdentity.defaultsKey) == identity)
        #expect(predecessors == ["old-phone"])
    }

    @Test("restored twice, both earlier devices are remembered, oldest first, without repeats")
    func restoredTwice() {
        scratch.defaults.set(["first-phone"], forKey: DeviceIdentity.predecessorsKey)
        scratch.defaults.set("second-phone", forKey: DeviceIdentity.defaultsKey)
        scratch.defaults.set(true, forKey: DeviceIdentity.keychainKey)

        _ = resolver.resolve()
        _ = resolver.resolve()

        #expect(predecessors == ["first-phone", "second-phone"])
    }

    @Test("the list of earlier devices stays bounded")
    func predecessorsAreBounded() {
        scratch.defaults.set((0..<20).map { "device-\($0)" }, forKey: DeviceIdentity.predecessorsKey)
        scratch.defaults.set("latest", forKey: DeviceIdentity.defaultsKey)
        scratch.defaults.set(true, forKey: DeviceIdentity.keychainKey)

        _ = resolver.resolve()

        #expect(predecessors.count == DeviceIdentity.predecessorLimit)
        #expect(predecessors.last == "latest")
    }

    /// Before the first unlock, or in a build without keychain access, nothing can be told
    /// apart, so nothing is minted over what defaults hold.
    @Test("a Keychain that can't be read uses the identity in defaults and writes nothing")
    func keychainUnavailable() {
        scratch.defaults.set("existing", forKey: DeviceIdentity.defaultsKey)
        scratch.defaults.set(true, forKey: DeviceIdentity.keychainKey)
        keychain.refusesReads = errSecInteractionNotAllowed

        #expect(resolver.resolve() == "existing")
        #expect(keychain.items.isEmpty)
        #expect(minted.count == 0)
    }

    @Test("an identity minted while the Keychain couldn't be read is moved in once it can")
    func mintedWhileUnavailableMovesLater() {
        keychain.refusesReads = errSecInteractionNotAllowed
        let identity = resolver.resolve()
        keychain.refusesReads = nil

        #expect(resolver.resolve() == identity)
        #expect(inKeychain == identity)
        #expect(predecessors.isEmpty)
    }

    @Test("a Keychain that refuses the write keeps the identity and moves it on a later launch")
    func refusedWriteIsRetried() {
        scratch.defaults.set("existing", forKey: DeviceIdentity.defaultsKey)
        keychain.refusesWrites = errSecInteractionNotAllowed

        #expect(resolver.resolve() == "existing")
        #expect(scratch.defaults.bool(forKey: DeviceIdentity.keychainKey) == false)

        keychain.refusesWrites = nil
        #expect(resolver.resolve() == "existing")
        #expect(inKeychain == "existing")
        #expect(minted.count == 0)
    }

    @Test("the Keychain's identity wins over different defaults, which are remembered")
    func keychainWins() {
        _ = keychain.write(Data("this-device".utf8), to: DeviceIdentity.keychainItem, accessibility: .afterFirstUnlockThisDeviceOnly)
        scratch.defaults.set("copied", forKey: DeviceIdentity.defaultsKey)

        #expect(resolver.resolve() == "this-device")
        #expect(scratch.defaults.string(forKey: DeviceIdentity.defaultsKey) == "this-device")
        #expect(predecessors == ["copied"])
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    var count: Int { lock.withLock { value } }

    func next() -> Int {
        lock.withLock {
            value += 1
            return value
        }
    }
}

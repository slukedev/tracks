import Testing
import Foundation
@testable import TracksCore

@MainActor
@Suite("Settings at launch")
struct SettingsLaunchTests {
    private let group = ScratchDefaults()
    private let app = ScratchDefaults()
    private let cloud = InMemoryKeyValueStore()

    private var stores: SettingsStores { SettingsStores(appGroup: group.defaults, app: app.defaults) }

    private let registry = SettingsRegistry.core + SettingsRegistry([
        .everyDevice("playAllowsExplicit", in: .app),
        .everyDevice("playLayoutV2", in: .app, formerKeys: ["playLayout"]),
        .thisDevice("musicSource", in: .app),
    ])

    private func envelope(_ value: Any, at stamp: Double) -> [String: Any] {
        [SettingsSync.valueField: value, SettingsSync.modifiedAtField: stamp]
    }

    // MARK: - Migrations

    @Test("a renamed setting keeps its value, and the old key stays for older builds")
    func formerKeyCarriesOver() {
        app.defaults.set("crate,mixes", forKey: "playLayout")
        registry.prepareForLaunch(isDemo: false, stores: stores)

        #expect(app.defaults.string(forKey: "playLayoutV2") == "crate,mixes")
        #expect(app.defaults.string(forKey: "playLayout") == "crate,mixes")
    }

    @Test("a renamed setting already set under its new key isn't overwritten")
    func formerKeyDoesNotOverwrite() {
        app.defaults.set("old", forKey: "playLayout")
        app.defaults.set("new", forKey: "playLayoutV2")
        registry.prepareForLaunch(isDemo: false, stores: stores)

        #expect(app.defaults.string(forKey: "playLayoutV2") == "new")
    }

    @Test("migrations run once, and a newer version is left alone")
    func migrationsRunOnce() {
        #expect(SettingsMigrator(stores: stores, registry: registry).run() == .fresh)
        #expect(SettingsMigrator(stores: stores, registry: registry).run() == nil)

        group.defaults.set(SettingsMigrator.currentVersion + 3, forKey: SettingsMigrator.versionKey)
        app.defaults.set("old", forKey: "playLayout")
        #expect(SettingsMigrator(stores: stores, registry: registry).run() == nil)
        #expect(app.defaults.object(forKey: "playLayoutV2") == nil)
        #expect(group.defaults.integer(forKey: SettingsMigrator.versionKey) == SettingsMigrator.currentVersion + 3)
    }

    @Test("an install an earlier build used is an upgrade, and skips the welcome")
    func upgradeSkipsWelcome() {
        app.defaults.set(false, forKey: "playAllowsExplicit")
        #expect(registry.prepareForLaunch(isDemo: false, stores: stores) == .upgrade)
        #expect(!OnboardingRecord.load(from: group.defaults).needsWelcome)
        #expect(OnboardingRecord.load(from: group.defaults).restoreFinished)
    }

    /// Widgets mint the device's identity the first time they draw, which can be before
    /// Tracks itself ever opens.
    @Test("an identity minted before the first launch still leaves it fresh")
    func identityAloneIsFresh() {
        group.defaults.set("A-DEVICE", forKey: DeviceIdentity.defaultsKey)
        #expect(registry.prepareForLaunch(isDemo: false, stores: stores) == .fresh)
        #expect(OnboardingRecord.load(from: group.defaults).needsWelcome)
    }

    // MARK: - A fresh install and iCloud

    @Test("a fresh install sends up nothing it didn't choose")
    func freshInstallSeedsNothing() {
        registry.prepareForLaunch(isDemo: false, stores: stores)
        group.defaults.set("Heard on Radio", forKey: CaptureSettings.playlistNameKey)

        let sync = SettingsSync(stores: stores, cloud: cloud, registry: registry)
        sync.reconcileAll()
        #expect(cloud.object(forKey: CaptureSettings.playlistNameKey) == nil)

        cloud.set(envelope("Late Night", at: 500), forKey: CaptureSettings.playlistNameKey)
        sync.pull(keys: [CaptureSettings.playlistNameKey])
        #expect(group.defaults.string(forKey: CaptureSettings.playlistNameKey) == "Late Night")
    }

    @Test("a reinstall takes back every synced setting, from both stores")
    func reinstallRestores() {
        cloud.set(envelope(false, at: 300), forKey: "playAllowsExplicit")
        cloud.set(envelope(0.25, at: 0), forKey: CaptureSettings.minimumListenShareKey)
        cloud.set(envelope("yourMusic", at: 400), forKey: "musicSource")
        registry.prepareForLaunch(isDemo: false, stores: stores)

        SettingsSync(stores: stores, cloud: cloud, registry: registry).reconcileAll()

        #expect(app.defaults.object(forKey: "playAllowsExplicit") as? Bool == false)
        #expect(group.defaults.double(forKey: CaptureSettings.minimumListenShareKey) == 0.25)
        #expect(app.defaults.object(forKey: "musicSource") == nil)
    }

    @Test("an upgraded device keeps its own value against another device's seed")
    func seedDoesNotReplaceValue() {
        app.defaults.set(true, forKey: "playAllowsExplicit")
        registry.prepareForLaunch(isDemo: false, stores: stores)
        cloud.set(envelope(false, at: 0), forKey: "playAllowsExplicit")

        SettingsSync(stores: stores, cloud: cloud, registry: registry).reconcileAll()

        #expect(app.defaults.object(forKey: "playAllowsExplicit") as? Bool == true)
    }

    @Test("a setting changed in the app's own defaults is sent up")
    func appDomainPushes() throws {
        let sync = SettingsSync(stores: stores, cloud: cloud, registry: registry)
        app.defaults.set(false, forKey: "playAllowsExplicit")
        app.defaults.set("yourMusic", forKey: "musicSource")
        sync.pushLocalChanges()

        let sent = try #require(cloud.object(forKey: "playAllowsExplicit") as? [String: Any])
        #expect(sent[SettingsSync.valueField] as? Bool == false)
        #expect(cloud.object(forKey: "musicSource") == nil)
    }

    // MARK: - Demo launches

    @Test("a demo's changes are undone on the next real launch")
    func demoIsUndone() {
        app.defaults.set(true, forKey: "playAllowsExplicit")
        registry.prepareForLaunch(isDemo: true, stores: stores)
        app.defaults.set(false, forKey: "playAllowsExplicit")
        app.defaults.set("yourMusic", forKey: "musicSource")

        registry.prepareForLaunch(isDemo: false, stores: stores)

        #expect(app.defaults.object(forKey: "playAllowsExplicit") as? Bool == true)
        #expect(app.defaults.object(forKey: "musicSource") == nil)
    }

    @Test("a second demo keeps the first one's backup")
    func secondDemoKeepsBackup() {
        app.defaults.set(true, forKey: "playAllowsExplicit")
        registry.prepareForLaunch(isDemo: true, stores: stores)
        app.defaults.set(false, forKey: "playAllowsExplicit")
        registry.prepareForLaunch(isDemo: true, stores: stores)

        registry.prepareForLaunch(isDemo: false, stores: stores)
        #expect(app.defaults.object(forKey: "playAllowsExplicit") as? Bool == true)
        #expect(!DemoSettingsGuard(stores: stores, registry: registry).restore())
    }

    @Test("a demo runs no migrations")
    func demoDoesNotMigrate() {
        app.defaults.set("old", forKey: "playLayout")
        registry.prepareForLaunch(isDemo: true, stores: stores)
        #expect(app.defaults.object(forKey: "playLayoutV2") == nil)
        #expect(group.defaults.object(forKey: SettingsMigrator.versionKey) == nil)
    }

    // MARK: - The registry

    @Test("the registry lists each key once")
    func registryIsUnique() {
        #expect(registry.duplicateKeys.isEmpty)
        #expect(SettingsRegistry([.everyDevice("a"), .thisDevice("a")]).duplicateKeys == ["a"])
    }

    @Test("what widgets read lives in the App Group", arguments: [
        CaptureSettings.menuBarLabelStyleKey, CaptureSettings.scrobblesToLastFMKey, CaptureSettings.capturesOnDemandKey,
    ])
    func widgetKeysInAppGroup(key: String) throws {
        #expect(try #require(SettingsRegistry.core.setting(forKey: key)).domain == .appGroup)
    }
}

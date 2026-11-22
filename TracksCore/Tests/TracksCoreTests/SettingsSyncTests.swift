import Testing
import Foundation
@testable import TracksCore

@MainActor
@Suite("Settings mirrored between devices")
struct SettingsSyncTests {

    private let scratch = ScratchDefaults()
    private let cloud = InMemoryKeyValueStore()

    private var defaults: UserDefaults { scratch.defaults }

    private var stores: SettingsStores { SettingsStores(appGroup: defaults, app: defaults) }

    private func sync(_ registry: SettingsRegistry = .core) -> SettingsSync {
        SettingsSync(stores: stores, cloud: cloud, registry: registry)
    }

    /// When this device last changed `key`, as `SettingsSync` recorded it.
    private func localModifiedAt(_ key: String) -> TimeInterval? {
        (defaults.dictionary(forKey: SettingsSync.modifiedAtKey) as? [String: TimeInterval])?[key]
    }

    // MARK: - Which settings take part

    /// Older builds on the user's other devices still write the settings Tracks dropped when
    /// it stopped keeping songs played in other apps. Taking them in would bring back state
    /// nothing reads, under names a later setting mustn't share.
    @Test("a setting an older build still writes doesn't land here", arguments: [
        "autoPlayBack", "importsRecentlyPlayed", "scrobblesImported", "showsUpNextInWidget", "passedOverSongs",
    ])
    func retiredKeysAreIgnored(key: String) {
        cloud.set([SettingsSync.valueField: true, SettingsSync.modifiedAtField: 100.0], forKey: key)
        let sync = sync()
        sync.pull(keys: [key])
        sync.reconcileAll()

        #expect(defaults.object(forKey: key) == nil)
    }

    @Test("the settings the user actually sets are all mirrored")
    func userFacingSettingsAreIncluded() {
        let mirrored = Set(SettingsRegistry.core.synced.map(\.key))
        for key in [
            CaptureSettings.playlistNameKey,
            CaptureSettings.playlistIDKey,
            CaptureSettings.autoAddKey,
            CaptureSettings.capturesOnDemandKey,
            CaptureSettings.minimumListenShareKey,
            CaptureSettings.scrobblesToLastFMKey,
            CaptureSettings.excludedStationsKey,
            CaptureSettings.blockedArtistsKey,
            CaptureSettings.forgottenSongsKey,
            CaptureSettings.forgottenSongDatesKey,
        ] {
            #expect(mirrored.contains(key), "\(key) should mirror")
        }
    }

    @Test("no key is listed twice")
    func keysAreUnique() {
        #expect(SettingsRegistry.core.duplicateKeys.isEmpty)
    }

    @Test("what belongs to the device stays on it", arguments: [
        CloudSync.enabledKey, DeviceIdentity.defaultsKey, DeviceIdentity.keychainKey, DeviceIdentity.predecessorsKey,
        ScrobblesInFlight.key, LastFMSessionStore.usernameKey, OnboardingRecord.storageKey,
    ])
    func deviceSettingsStayPut(key: String) throws {
        let setting = try #require(SettingsRegistry.core.setting(forKey: key))
        #expect(!setting.syncs)
    }

    /// `swift test` has no iCloud container in its Info.plist, so there is nothing to mirror
    /// to. The same holds for an unsigned build, where the entitlement has been stripped and
    /// the key-value store would take writes and sync none of them.
    @Test("a build that cannot reach iCloud gets no mirror")
    func unconfiguredBuildHasNoMirror() {
        #expect(!CloudSync.isConfigured)
        #expect(SettingsSync.forCurrentBuild() == nil)
    }

    /// The switch in Settings has to mean both halves of sync, or it would only be half
    /// true: the history would stop travelling and the settings would carry on.
    @Test("settings mirror only when sync is on and iCloud is reachable", arguments: [
        (enabled: true, configured: true, mirrors: true),
        (enabled: false, configured: true, mirrors: false),
        (enabled: true, configured: false, mirrors: false),
        (enabled: false, configured: false, mirrors: false),
    ])
    func mirrorsOnlyWhenBothHold(enabled: Bool, configured: Bool, mirrors: Bool) {
        #expect(SettingsSync.shouldMirror(enabled: enabled, configured: configured) == mirrors)
    }

    // MARK: - Choosing a copy

    @Test("the newer copy wins", arguments: [
        (local: 100.0, remote: 200.0, expected: StoredSetting.Decision.takeRemote),
        (local: 200.0, remote: 100.0, expected: .keepLocal),
        // A tie keeps what's here, so two devices can't hand one change back and forth.
        (local: 100.0, remote: 100.0, expected: .keepLocal),
    ])
    func newestWins(local: Double, remote: Double, expected: StoredSetting.Decision) {
        let setting = StoredSetting.everyDevice("k")
        #expect(setting.decide(
            localModifiedAt: Date(timeIntervalSince1970: local),
            remoteModifiedAt: Date(timeIntervalSince1970: remote)
        ) == expected)
    }

    /// A device that has never written the key has nothing to defend, so iCloud wins. This is
    /// what makes a fresh install adopt the user's settings instead of pushing its defaults.
    @Test("a device that has never set the key takes iCloud's copy")
    func untouchedDeviceTakesRemote() {
        let setting = StoredSetting.everyDevice("k")
        #expect(setting.decide(
            localModifiedAt: nil,
            remoteModifiedAt: Date(timeIntervalSince1970: 100)
        ) == .takeRemote)
    }

    @Test("a key iCloud has never seen is left alone")
    func unknownRemoteKeepsLocal() {
        let setting = StoredSetting.everyDevice("k")
        #expect(setting.decide(
            localModifiedAt: Date(timeIntervalSince1970: 100),
            remoteModifiedAt: nil
        ) == .keepLocal)
    }

    @Test("a union key never discards either side, whatever the timestamps say")
    func unionIgnoresTimestamps() {
        let setting = StoredSetting.everyDevice("k", merge: .union)
        #expect(setting.decide(
            localModifiedAt: Date(timeIntervalSince1970: 900),
            remoteModifiedAt: Date(timeIntervalSince1970: 100)
        ) == .unionBoth)
    }

    // MARK: - Pushing and pulling

    @Test("a setting changed here is sent up")
    func localChangeIsPushed() throws {
        let sync = sync()
        defaults.set("Late Night", forKey: CaptureSettings.playlistNameKey)
        sync.pushLocalChanges()

        let envelope = try #require(
            cloud.object(forKey: CaptureSettings.playlistNameKey) as? [String: Any]
        )
        #expect(envelope[SettingsSync.valueField] as? String == "Late Night")
        #expect(envelope[SettingsSync.modifiedAtField] as? Double != nil)
    }

    @Test("a setting changed on another device arrives here")
    func remoteChangeIsPulled() {
        cloud.set(
            [SettingsSync.valueField: "Late Night", SettingsSync.modifiedAtField: 200.0],
            forKey: CaptureSettings.playlistNameKey
        )
        let sync = sync()
        let changed = sync.pull(keys: [CaptureSettings.playlistNameKey])

        #expect(changed == [CaptureSettings.playlistNameKey])
        #expect(defaults.string(forKey: CaptureSettings.playlistNameKey) == "Late Night")
    }

    /// The whole point of the timestamps. A Mac that has been shut in a drawer must not
    /// overwrite a change made on the phone yesterday just because it syncs first.
    ///
    /// The stale copy lands in iCloud *after* the local change was pushed, as it would when
    /// the Mac finally reconnects, so the pull really has two different values to choose
    /// between and only the timestamps can decide it.
    @Test("a stale copy from iCloud does not overwrite a newer local change")
    func staleRemoteIsIgnored() throws {
        let sync = sync()
        defaults.set("New", forKey: CaptureSettings.playlistNameKey)
        sync.pushLocalChanges()
        let localStamp = try #require(localModifiedAt(CaptureSettings.playlistNameKey))

        // The drawer Mac's copy: a different value, dated long before the local change.
        cloud.set(
            [SettingsSync.valueField: "Old", SettingsSync.modifiedAtField: 100.0],
            forKey: CaptureSettings.playlistNameKey
        )
        #expect(localStamp > 100)

        #expect(sync.pull(keys: [CaptureSettings.playlistNameKey]).isEmpty)
        #expect(defaults.string(forKey: CaptureSettings.playlistNameKey) == "New")
        #expect(localModifiedAt(CaptureSettings.playlistNameKey) == localStamp)
    }

    /// Pulling stamps the local copy with iCloud's time, not the time of the pull. Stamping
    /// it "now" would make this device's copy look newer than the change it came from, so it
    /// would win against the device that really made it: a later change there, dated before
    /// this pull's "now", would be refused here as stale and the two would drift apart.
    @Test("pulling a setting stamps it with iCloud's time, so it doesn't push it back")
    func pullingDoesNotEcho() throws {
        cloud.set(
            [SettingsSync.valueField: "Late Night", SettingsSync.modifiedAtField: 200.0],
            forKey: CaptureSettings.playlistNameKey
        )
        let sync = sync()
        #expect(sync.pull(keys: [CaptureSettings.playlistNameKey]) == [CaptureSettings.playlistNameKey])
        #expect(localModifiedAt(CaptureSettings.playlistNameKey) == 200.0)

        sync.pushLocalChanges()
        let envelope = try #require(
            cloud.object(forKey: CaptureSettings.playlistNameKey) as? [String: Any]
        )
        #expect(envelope[SettingsSync.modifiedAtField] as? Double == 200.0)

        // The other device changes it again, still long before this test's wall clock. With
        // the pull stamped "now" this would look stale and be dropped.
        cloud.set(
            [SettingsSync.valueField: "Early Morning", SettingsSync.modifiedAtField: 300.0],
            forKey: CaptureSettings.playlistNameKey
        )
        #expect(sync.pull(keys: [CaptureSettings.playlistNameKey]) == [CaptureSettings.playlistNameKey])
        #expect(defaults.string(forKey: CaptureSettings.playlistNameKey) == "Early Morning")
    }

    /// With the mirror running, writing the pulled value into the defaults fires the change
    /// observer, which saw a setting it didn't know about yet and sent it straight back up,
    /// stamped now. The other device then took its own change back as a newer one.
    @Test("a setting pulled while mirroring isn't sent back up as a new change")
    func runningPullDoesNotEcho() throws {
        cloud.set(
            [SettingsSync.valueField: "Late Night", SettingsSync.modifiedAtField: 200.0],
            forKey: CaptureSettings.playlistNameKey
        )
        let sync = sync()
        sync.start()

        #expect(defaults.string(forKey: CaptureSettings.playlistNameKey) == "Late Night")
        let envelope = try #require(
            cloud.object(forKey: CaptureSettings.playlistNameKey) as? [String: Any]
        )
        #expect(envelope[SettingsSync.modifiedAtField] as? Double == 200.0)
        #expect(localModifiedAt(CaptureSettings.playlistNameKey) == 200.0)
    }

    @Test("reconciling twice changes nothing the second time")
    func reconcileIsIdempotent() {
        cloud.set(
            [SettingsSync.valueField: 0.25, SettingsSync.modifiedAtField: 200.0],
            forKey: CaptureSettings.minimumListenShareKey
        )
        let sync = sync()
        sync.reconcileAll()
        #expect(defaults.double(forKey: CaptureSettings.minimumListenShareKey) == 0.25)

        #expect(sync.pull(keys: SettingsRegistry.core.synced.map(\.key)).isEmpty)
        #expect(defaults.double(forKey: CaptureSettings.minimumListenShareKey) == 0.25)
    }

    // MARK: - The first time a device mirrors

    /// The upgrade path. Someone who has been using Tracks for months has settings but has
    /// never changed one *since mirroring existed*, so there is no local change to push and
    /// iCloud would stay empty, which is indistinguishable from sync being broken.
    @Test("settings chosen before mirroring existed are still sent up")
    func existingSettingsAreSeeded() throws {
        defaults.set("Late Night", forKey: CaptureSettings.playlistNameKey)
        let sync = sync()
        sync.reconcileAll()

        let envelope = try #require(
            cloud.object(forKey: CaptureSettings.playlistNameKey) as? [String: Any]
        )
        #expect(envelope[SettingsSync.valueField] as? String == "Late Night")
    }

    /// Seeds are dated 1970 so a setting the user actually chose, whenever they chose it,
    /// always wins over one that was simply lying around on another device.
    @Test("a seeded setting never overrides one a device has really set")
    func seedsLoseToRealChanges() throws {
        cloud.set(
            [SettingsSync.valueField: "Chosen", SettingsSync.modifiedAtField: 500.0],
            forKey: CaptureSettings.playlistNameKey
        )
        defaults.set("Lying Around", forKey: CaptureSettings.playlistNameKey)

        let sync = sync()
        sync.reconcileAll()

        #expect(defaults.string(forKey: CaptureSettings.playlistNameKey) == "Chosen")
    }

    @Test("a setting the user has never touched is not seeded")
    func untouchedSettingsAreNotSeeded() {
        let sync = sync()
        sync.reconcileAll()
        // Nothing was ever written to these defaults, so there is no value to claim and both
        // devices fall back to the same built-in default anyway.
        #expect(cloud.object(forKey: CaptureSettings.playlistNameKey) == nil)
    }

    @Test("seeding does not claim a key another device already owns")
    func seedingLeavesClaimedKeysAlone() throws {
        cloud.set(
            [SettingsSync.valueField: 0.25, SettingsSync.modifiedAtField: 500.0],
            forKey: CaptureSettings.minimumListenShareKey
        )
        defaults.set(0.75, forKey: CaptureSettings.minimumListenShareKey)

        let sync = sync()
        sync.reconcileAll()

        let envelope = try #require(
            cloud.object(forKey: CaptureSettings.minimumListenShareKey) as? [String: Any]
        )
        #expect(envelope[SettingsSync.modifiedAtField] as? Double == 500.0)
        #expect(defaults.double(forKey: CaptureSettings.minimumListenShareKey) == 0.25)
    }

    // MARK: - Song statuses

    /// The bug that started this: forgetting a song on the iPhone deleted its rows, which
    /// synced, but the "forgotten" mark didn't, so the Mac's next import read the song back
    /// out of Apple's recently-played list and it reappeared on both devices.
    @Test("forgotten songs from both devices are kept")
    func forgottenSongsUnion() {
        cloud.set(
            [SettingsSync.valueField: ["their song"], SettingsSync.modifiedAtField: 100.0],
            forKey: CaptureSettings.forgottenSongsKey
        )
        let sync = sync()
        defaults.set(["my song"], forKey: CaptureSettings.forgottenSongsKey)
        sync.pull(keys: [CaptureSettings.forgottenSongsKey])

        #expect(
            defaults.stringArray(forKey: CaptureSettings.forgottenSongsKey) == ["my song", "their song"]
        )
    }

    @Test("a union sends the other device what it was missing")
    func unionIsPushedBack() throws {
        cloud.set(
            [SettingsSync.valueField: ["their song"], SettingsSync.modifiedAtField: 100.0],
            forKey: CaptureSettings.forgottenSongsKey
        )
        let sync = sync()
        defaults.set(["my song"], forKey: CaptureSettings.forgottenSongsKey)
        sync.pull(keys: [CaptureSettings.forgottenSongsKey])

        let envelope = try #require(
            cloud.object(forKey: CaptureSettings.forgottenSongsKey) as? [String: Any]
        )
        #expect(envelope[SettingsSync.valueField] as? [String] == ["my song", "their song"])
    }

    /// Excluding a station is a list the user curates, so taking one back out has to travel.
    /// A union would put it straight back.
    @Test("un-excluding a station is not undone by a merge")
    func excludedStationsReplaceRatherThanUnion() {
        cloud.set(
            [SettingsSync.valueField: ["Chill", "Hits"], SettingsSync.modifiedAtField: 100.0],
            forKey: CaptureSettings.excludedStationsKey
        )
        let sync = sync()
        defaults.set(["Chill"], forKey: CaptureSettings.excludedStationsKey)
        sync.pushLocalChanges()
        sync.pull(keys: [CaptureSettings.excludedStationsKey])

        #expect(defaults.stringArray(forKey: CaptureSettings.excludedStationsKey) == ["Chill"])
    }

    /// Unblocking an artist on one device has to reach the other, not be joined back in.
    @Test("an unblocked artist stays unblocked")
    func unblockingTravels() {
        cloud.set(
            [SettingsSync.valueField: ["Mara Solis", "Umbra"], SettingsSync.modifiedAtField: 100.0],
            forKey: CaptureSettings.blockedArtistsKey
        )
        let sync = sync()
        defaults.set(["Umbra"], forKey: CaptureSettings.blockedArtistsKey)
        sync.pushLocalChanges()
        sync.pull(keys: [CaptureSettings.blockedArtistsKey])

        #expect(defaults.stringArray(forKey: CaptureSettings.blockedArtistsKey) == ["Umbra"])
    }

    // MARK: - Two devices

    /// Both sides of a real exchange, against one shared key-value store.
    @Test("two devices end up with the same settings")
    func twoDevicesConverge() throws {
        let phone = ScratchDefaults()
        let mac = ScratchDefaults()
        let phoneSync = SettingsSync(stores: SettingsStores(appGroup: phone.defaults, app: phone.defaults), cloud: cloud)
        let macSync = SettingsSync(stores: SettingsStores(appGroup: mac.defaults, app: mac.defaults), cloud: cloud)

        phone.defaults.set("Late Night", forKey: CaptureSettings.playlistNameKey)
        phone.defaults.set(["phone song"], forKey: CaptureSettings.forgottenSongsKey)
        phoneSync.pushLocalChanges()

        mac.defaults.set(["mac song"], forKey: CaptureSettings.forgottenSongsKey)
        macSync.reconcileAll()

        #expect(mac.defaults.string(forKey: CaptureSettings.playlistNameKey) == "Late Night")
        #expect(
            mac.defaults.stringArray(forKey: CaptureSettings.forgottenSongsKey)
                == ["mac song", "phone song"]
        )

        // And the phone catches up with what the Mac had forgotten.
        phoneSync.reconcileAll()
        #expect(
            phone.defaults.stringArray(forKey: CaptureSettings.forgottenSongsKey)
                == ["mac song", "phone song"]
        )
    }
}

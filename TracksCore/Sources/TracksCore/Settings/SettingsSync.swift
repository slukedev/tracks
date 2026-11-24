import Foundation

/// Keeps the user's settings the same on every device they use Tracks on.
///
/// The listening history syncs as SwiftData over CloudKit (see ``CloudSync``). Settings can't
/// ride along: widgets, App Intents and the Mac menu bar read them out of the App Group's
/// defaults without opening the model container, which is why ``CaptureSettings`` lives in
/// `UserDefaults`. This mirrors those defaults through iCloud's key-value store instead, so
/// every device keeps reading them the same cheap way and still sees the same values.
///
/// Which settings take part is ``SettingsRegistry``'s call: every ``StoredSetting`` that
/// reaches every device, from either of the two `UserDefaults` it can live in. The key-value
/// store is sized for exactly this: a megabyte and a thousand keys overall, against a few
/// dozen small values.
///
/// Both directions are idempotent: a pull writes the local copy only when it differs, and a
/// push compares against what it last saw, so the two devices settle instead of handing one
/// change back and forth.
@MainActor
public final class SettingsSync {

    /// Posted after settings arrive from another device, with the changed keys in
    /// `userInfo[changedKeysKey]`.
    ///
    /// `@AppStorage` redraws on its own, since this writes through `UserDefaults`. This is
    /// for the views that copy a setting into `@State` and would otherwise keep showing the
    /// old value.
    public nonisolated static let didChangeNotification = Notification.Name("TracksSettingsDidChangeRemotely")
    public nonisolated static let changedKeysKey = "keys"

    /// Where each key was last changed, as `[key: timeIntervalSince1970]`.
    ///
    /// Kept next to the values so a device that changed a setting offline still wins when it
    /// reconnects, rather than being overwritten by a device that only ever read it.
    nonisolated static let modifiedAtKey = "TracksSettingModifiedAt"

    /// The fields of the envelope each value travels in.
    nonisolated static let valueField = "value"
    nonisolated static let modifiedAtField = "modifiedAt"

    private let stores: SettingsStores
    private let cloud: KeyValueSyncStore
    private let registry: SettingsRegistry
    private let settings: [StoredSetting]
    private let unseededKeys: Set<String>

    public private(set) var isOverQuota = false

    /// The mirrored values as this last saw them, so a local write can be spotted without
    /// `UserDefaults` saying which key moved.
    private var snapshot: [String: Any] = [:]
    private var observers: [NSObjectProtocol] = []
    /// Set while ``pull(keys:)`` writes iCloud's values into the defaults.
    private var isPulling = false

    /// The mirror for this process, or nil when there is nothing to mirror to.
    ///
    /// Answers to the same switch as the store: turning "Sync with iCloud" off has to stop
    /// the settings travelling as well as the history, or the toggle would only be half
    /// true. Like the store, the switch is read once here, so a change takes effect on the
    /// next launch.
    ///
    /// With Trackside Cloud chosen, settings travel through it instead
    /// (``TracksideCloudSettings``), the same way, by the same rules.
    public static func forCurrentBuild(registry: SettingsRegistry = .core) -> SettingsSync? {
        if CloudSync.tracksideServerID != nil {
            return SettingsSync(cloud: TracksideCloudSettings.shared, registry: registry)
        }
        guard shouldMirror(enabled: CloudSync.mirrorsToICloud, configured: CloudSync.isConfigured)
        else { return nil }
        return SettingsSync(registry: registry)
    }

    /// Whether to mirror settings at all, given the user's switch and whether this build can
    /// reach iCloud. Split out from ``forCurrentBuild()`` so it can be tested: under
    /// `swift test` neither condition holds, so the factory alone can't tell them apart.
    static func shouldMirror(enabled: Bool, configured: Bool) -> Bool {
        enabled && configured
    }

    public init(
        stores: SettingsStores = .live,
        cloud: KeyValueSyncStore = NSUbiquitousKeyValueStore.default,
        registry: SettingsRegistry = .core
    ) {
        self.stores = stores
        self.cloud = cloud
        self.registry = registry
        self.settings = registry.synced
        self.unseededKeys = SettingsMigrator.keysUnseeded(in: stores)
        self.snapshot = Self.read(settings, from: stores)
    }

    isolated deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    // MARK: - Lifecycle

    /// Starts mirroring: reconciles once with whatever iCloud already has, then follows
    /// changes from either side.
    ///
    /// Safe to call more than once; later calls only reconcile.
    public func start() {
        if observers.isEmpty {
            observers.append(NotificationCenter.default.addObserver(
                forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
                object: cloud,
                // Posted from iCloud's queue; a `.main` observer would make it wait for the
                // main thread.
                queue: nil
            ) { [weak self] notification in
                // `Notification` isn't `Sendable`, so the two values that matter are read
                // out here rather than carried into the isolated call.
                let reason = notification.userInfo?[NSUbiquitousKeyValueStoreChangeReasonKey] as? Int
                let keys = notification.userInfo?[NSUbiquitousKeyValueStoreChangedKeysKey] as? [String]
                Task { @MainActor in self?.cloudChanged(reason: reason, keys: keys) }
            })

            // `UserDefaults` doesn't say which key changed, so this diffs against `snapshot`.
            for defaults in stores.distinct {
                observers.append(NotificationCenter.default.addObserver(
                    forName: UserDefaults.didChangeNotification,
                    object: defaults,
                    // Never `.main`: that makes whichever thread wrote the default wait for
                    // the main thread, and a framework writing from a queue the main thread
                    // is waiting on deadlocks (see CarPlaySceneDelegate's settings observer).
                    queue: nil
                ) { [weak self] _ in
                    Task { @MainActor in self?.pushLocalChanges() }
                })
            }
        }

        cloud.synchronize()
        reconcileAll()
    }

    /// Pulls every mirrored key, then pushes anything this device holds a newer copy of.
    ///
    /// The pull runs first so a fresh install takes iCloud's settings rather than pushing the
    /// defaults it started with.
    public func reconcileAll() {
        let changed = pull(keys: settings.map(\.key))
        seedSettingsNeverSynced()
        pushLocalChanges()
        announce(changed)
    }

    // MARK: - Pulling

    private func cloudChanged(reason: Int?, keys: [String]?) {
        switch reason {
        case NSUbiquitousKeyValueStoreQuotaViolationChange:
            isOverQuota = true
            Log.iCloud.error("iCloud's key-value store is full; settings aren't syncing")
            return
        case NSUbiquitousKeyValueStoreAccountChange:
            // A different iCloud account, so the remote side is another person's settings or
            // nothing at all. Reconcile from scratch rather than trusting the changed list.
            reconcileAll()
            return
        default:
            break
        }

        announce(pull(keys: keys ?? settings.map(\.key)))
    }

    /// Brings the named keys down from iCloud where iCloud's copy should win.
    /// - Returns: the keys whose local value changed.
    @discardableResult
    func pull(keys: [String]) -> [String] {
        // Each write below fires the defaults' change observer before the snapshot and stamp
        // have caught up, and the push it runs would send the pulled value back up as a
        // change made here, now.
        isPulling = true
        defer { isPulling = false }
        var changed: [String] = []
        var stamps = localModifiedAt

        for key in keys {
            guard let setting = registry.setting(forKey: key),
                  setting.syncs,
                  let envelope = cloud.object(forKey: key) as? [String: Any]
            else { continue }

            let defaults = stores.defaults(for: setting)
            let remoteValue = envelope[Self.valueField]
            let remoteModifiedAt = (envelope[Self.modifiedAtField] as? Double)
                .map(Date.init(timeIntervalSince1970:))
            let localValue = defaults.object(forKey: key)

            switch setting.decide(
                localModifiedAt: stamps[key].map(Date.init(timeIntervalSince1970:)),
                remoteModifiedAt: remoteModifiedAt,
                hasLocalValue: localValue != nil
            ) {
            case .keepLocal:
                continue

            case .takeRemote:
                guard !Self.isEqual(localValue, remoteValue) else { continue }
                defaults.set(remoteValue, forKey: key)
                snapshot[key] = remoteValue
                // Stamped with iCloud's time, not now, so this doesn't then look like a
                // local change and get pushed straight back.
                stamps[key] = remoteModifiedAt?.timeIntervalSince1970
                changed.append(key)

            case .unionBoth:
                let merged = Self.union(localValue, remoteValue)
                let stamp = max(
                    stamps[key] ?? 0,
                    remoteModifiedAt?.timeIntervalSince1970 ?? 0
                )
                if !Self.isEqual(localValue, merged) {
                    defaults.set(merged, forKey: key)
                    snapshot[key] = merged
                    changed.append(key)
                }
                stamps[key] = stamp
                // This device knew entries iCloud didn't, so send the whole set back up.
                if !Self.isEqual(remoteValue, merged) {
                    write(merged, forKey: key, modifiedAt: stamp)
                }
            }
        }

        if !changed.isEmpty || stamps != localModifiedAt { localModifiedAt = stamps }
        return changed
    }

    // MARK: - Pushing

    /// Sends up every mirrored key whose local value has moved since this last looked.
    ///
    /// Driven by `UserDefaults.didChangeNotification`, which fires for any key in the suite
    /// and doesn't say which, so the work is the diff rather than the notification.
    func pushLocalChanges() {
        guard !isPulling else { return }
        let current = Self.read(settings, from: stores)
        let moved = settings.filter { !Self.isEqual(current[$0.key], snapshot[$0.key]) }
        guard !moved.isEmpty else { return }

        var stamps = localModifiedAt
        let now = Date.now.timeIntervalSince1970

        for setting in moved {
            let key = setting.key
            let value: Any?
            switch setting.merge {
            case .newestWins, nil:
                value = current[key]
            case .union:
                // A union key's local copy may be missing entries another device added and
                // this one hasn't pulled yet, so push the union rather than the local set.
                let remote = (cloud.object(forKey: key) as? [String: Any])?[Self.valueField]
                value = Self.union(current[key], remote)
            }
            stamps[key] = now
            write(value, forKey: key, modifiedAt: now)
        }

        snapshot = current
        localModifiedAt = stamps
        cloud.synchronize()
    }

    /// Sends up the settings this device already had, the first time it mirrors anything.
    ///
    /// Pushing is driven by local *changes*, and an install that predates mirroring has none
    /// to make: its settings were chosen long ago and have been sitting in `UserDefaults`
    /// ever since. Without this, an upgraded device puts nothing into iCloud until the user
    /// happens to change a setting, and a second device would find the store empty and keep
    /// its own defaults, which looks exactly like sync not working.
    ///
    /// Seeded values are dated 1970, so a real change made on any device at any time beats
    /// them, and a seed fills a gap on another device without replacing what it has (see
    /// ``StoredSetting/decide(localModifiedAt:remoteModifiedAt:hasLocalValue:)``).
    ///
    /// A fresh install seeds nothing it already knew about when it first ran. Its values are
    /// defaults, or ones the app wrote for itself, and iCloud's key-value store can still be
    /// downloading the person's real settings: a seed sent up then could land on top of them.
    private func seedSettingsNeverSynced() {
        var stamps = localModifiedAt
        for setting in settings where stamps[setting.key] == nil && !unseededKeys.contains(setting.key) {
            // Only keys iCloud has never heard of. A key already up there has an owner.
            guard cloud.object(forKey: setting.key) == nil,
                  let value = stores.defaults(for: setting).object(forKey: setting.key)
            else { continue }
            write(value, forKey: setting.key, modifiedAt: 0)
            stamps[setting.key] = 0
        }
        guard stamps != localModifiedAt else { return }
        localModifiedAt = stamps
        cloud.synchronize()
    }

    private func write(_ value: Any?, forKey key: String, modifiedAt: TimeInterval) {
        guard let value else {
            // A cleared setting is removed rather than sent as null, so a device that has
            // never seen the key doesn't treat "nothing" as a value worth taking.
            cloud.set(nil, forKey: key)
            return
        }
        cloud.set(
            [Self.valueField: value, Self.modifiedAtField: modifiedAt],
            forKey: key
        )
    }

    private func announce(_ keys: [String]) {
        guard !keys.isEmpty else { return }
        NotificationCenter.default.post(
            name: Self.didChangeNotification,
            object: self,
            userInfo: [Self.changedKeysKey: keys]
        )
    }

    // MARK: - Stored timestamps

    private var localModifiedAt: [String: TimeInterval] {
        get { stores.ledger.dictionary(forKey: Self.modifiedAtKey) as? [String: TimeInterval] ?? [:] }
        set { stores.ledger.set(newValue, forKey: Self.modifiedAtKey) }
    }

    // MARK: - Values

    private static func read(
        _ settings: [StoredSetting],
        from stores: SettingsStores
    ) -> [String: Any] {
        var values: [String: Any] = [:]
        for setting in settings {
            if let value = stores.defaults(for: setting).object(forKey: setting.key) {
                values[setting.key] = value
            }
        }
        return values
    }

    /// Whether two property-list values are the same. `UserDefaults` hands back `Any`, so
    /// this goes through `NSObject`'s equality rather than `==`.
    static func isEqual(_ lhs: Any?, _ rhs: Any?) -> Bool {
        switch (lhs, rhs) {
        case (nil, nil): true
        case let (lhs?, rhs?): (lhs as AnyObject).isEqual(rhs)
        default: false
        }
    }

    /// Both sides of a ``StoredSetting/Merge/union`` key, sorted so every device stores the
    /// same array and neither sees the other's copy as a change.
    static func union(_ lhs: Any?, _ rhs: Any?) -> [String] {
        let left = lhs as? [String] ?? []
        let right = rhs as? [String] ?? []
        return Set(left).union(right).sorted()
    }
}

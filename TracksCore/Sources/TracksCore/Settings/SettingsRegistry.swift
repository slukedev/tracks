import Foundation

/// Every setting Tracks keeps, in one list.
///
/// TracksCore lists its own in ``core``; the app adds the ones its views own and passes the
/// whole registry to ``SettingsSync``, ``SettingsMigrator`` and ``DemoSettingsGuard``. A new
/// setting is one line in one of those lists:
///
///     .everyDevice(PlayPreferences.someKey, in: .app),
///     .thisDevice(SomeFeature.storageKey, in: .app),
///
/// Keys never change once shipped. A renamed setting keeps its old key in `formerKeys`, and
/// a key taken out of the registry stays out of use: older builds on the person's other
/// devices still write it.
public struct SettingsRegistry: Sendable {
    public let settings: [StoredSetting]
    public let byKey: [String: StoredSetting]

    public init(_ settings: [StoredSetting]) {
        self.settings = settings
        self.byKey = Dictionary(settings.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
    }

    public static func + (lhs: SettingsRegistry, rhs: SettingsRegistry) -> SettingsRegistry {
        SettingsRegistry(lhs.settings + rhs.settings)
    }

    /// The settings that follow the person to their other devices.
    public var synced: [StoredSetting] { settings.filter(\.syncs) }

    /// Keys listed more than once, which would make two settings share one value.
    public var duplicateKeys: [String] {
        Dictionary(grouping: settings, by: \.key).filter { $0.value.count > 1 }.keys.sorted()
    }

    public func setting(forKey key: String) -> StoredSetting? { byKey[key] }
}

extension SettingsRegistry {
    /// The settings TracksCore owns. All in the App Group, which widgets and intents read.
    ///
    /// Kept on this device: whether this device syncs at all (turning it off on one device
    /// mustn't turn it off everywhere), the device's identity, the Last.fm username (its
    /// session key is in this device's Keychain and doesn't travel, so a synced name would
    /// claim an account the device can't scrobble to), and caches and progress that the
    /// history itself already carries between devices.
    public static let core = SettingsRegistry([
        // Playlist. The ID is shared so a second device adds to the playlist the first one
        // made, instead of creating another with the same name.
        .everyDevice(CaptureSettings.playlistNameKey),
        .everyDevice(CaptureSettings.playlistIDKey),
        .everyDevice(CaptureSettings.autoAddKey),

        // History
        .everyDevice(CaptureSettings.dedupeWindowKey),
        .everyDevice(CaptureSettings.minimumListenShareKey),
        .everyDevice(CaptureSettings.capturesOnDemandKey),
        // The user curates this list and can take a station back out, so a removal has to
        // travel as well as an addition.
        .everyDevice(CaptureSettings.excludedStationsKey),
        .everyDevice(SourceScope.separatesKey, in: .app),
        .thisDevice(SourceScope.storageKey, in: .app),

        // Blocked on the Mac is blocked on the iPhone; and unblocking is as much a change as
        // blocking, so the newest list wins rather than the two joining.
        .everyDevice(CaptureSettings.blockedArtistsKey),

        // Song statuses. Nothing ever un-forgets a song, so the sets only grow and a union is
        // both safe and order-independent. Newest-wins would undo a removal made on the
        // other device the next time this one forgot anything.
        .everyDevice(CaptureSettings.forgottenSongsKey, merge: .union),
        .everyDevice(CaptureSettings.forgottenSongDatesKey, merge: .union),

        // Last.fm
        .everyDevice(CaptureSettings.scrobblesToLastFMKey),
        .thisDevice(LastFMSessionStore.usernameKey),
        .thisDevice(CaptureSettings.lastFMHistoryKey),

        // Menu bar
        .everyDevice(CaptureSettings.menuBarLabelStyleKey),
        .everyDevice(CaptureSettings.menuBarLabelFormatKey),
        .everyDevice(CaptureSettings.animatesMenuBarKey),
        .everyDevice(CaptureSettings.showsMenuBarProgressKey),

        // Charts
        .thisDevice(ChartSpan.storageKey, in: .app),

        // This device
        .thisDevice(CloudSync.enabledKey),
        .thisDevice(DeviceIdentity.defaultsKey),
        .thisDevice(CaptureSettings.artworkMissesKey),
        .thisDevice(OnboardingRecord.storageKey),
    ])
}

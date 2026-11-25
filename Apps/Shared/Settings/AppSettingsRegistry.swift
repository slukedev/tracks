import Foundation
import TracksCore

extension SettingsRegistry {
    static let tracks = core + app

    /// Kept on each device: what depends on the device itself (its storage, network, speakers
    /// and servers), where Play's music comes from, and how each window or screen was left
    /// (sort orders, the chart on show, the Play layout and Stage's orientation).
    static let app = SettingsRegistry(shared + platform)

    private static let shared: [StoredSetting] = [
        // Play
        .everyDevice(PlayPreferences.allowsExplicitKey, in: .app),
        .everyDevice(PlayPreferences.transitionKey, in: .app),
        .everyDevice(PlayPreferences.crossfadeSecondsKey, in: .app),
        .everyDevice(PlayPreferences.autoplayKey, in: .app),
        .everyDevice(PlayPreferences.songDestinationKey, in: .app),
        .everyDevice(PlayPreferences.shakeToPlayKey, in: .app),
        .everyDevice(PlayPreferences.volumeFollowsSpeedKey, in: .app),
        .everyDevice(PlayPreferences.speedVolumeAmountKey, in: .app),
        .everyDevice(OpeningTab.storageKey, in: .app),
        .everyDevice(PlayFromOrder.storageKey, in: .app),
        .everyDevice(SuggestionMode.storageKey, in: .app),
        .everyDevice(LastSessionStore.resumesKey, in: .app),
        .everyDevice(LastSessionStore.windowKey, in: .app),
        .thisDevice(PlayPreferences.layoutKey, in: .app),
        .thisDevice(PartyVibe.storageKey, in: .app),

        // Tracks Radio
        .everyDevice(PlayPreferences.tracksRadioKey, in: .app),
        .everyDevice(PlayPreferences.radioTuningKey, in: .app),
        .everyDevice(PlayPreferences.radioFollowsTimeKey, in: .app),
        .everyDevice(PlayPreferences.radioDayKey, in: .app),
        .everyDevice(PlayPreferences.radioNoticesDrivingKey, in: .app),

        // Now Playing and Stage
        .everyDevice(NowPlayingBackground.storageKey, in: .app),
        .everyDevice(BackdropChange.storageKey, in: .app),
        .everyDevice(MotionCovers.storageKey, in: .app),
        .everyDevice(LivingCoverOption.movesWithMusic, in: .app),
        .everyDevice(AudioQualityBadge.showsKey, in: .app),
        .everyDevice(StageLayout.storageKey, in: .app),
        .everyDevice(StageVisualizer.storageKey, in: .app),
        .everyDevice(StageOption.background, in: .app),
        .everyDevice(StageOption.showsAlbum, in: .app),
        .everyDevice(StageOption.showsProgress, in: .app),
        .everyDevice(StageOption.showsNext, in: .app),
        .everyDevice(StageOption.showsClock, in: .app),
        .everyDevice(StageOption.keepsScreenOn, in: .app),
        .thisDevice(StageOrientation.storageKey, in: .app),
        .thisDevice(StageOption.hintsShown, in: .app),

        // Devices
        .everyDevice(NearbyDevices.storageKey, in: .app),
        .everyDevice(NearbyDevices.bringsQueueKey, in: .app),
        .everyDevice(NowPlayingHandoff.storageKey, in: .app),

        // Sources. Servers, their passwords and what's downloaded belong to the device.
        .everyDevice(PlaylistMerge.storageKey, in: .app),
        .everyDevice(DownloadChoice.isOnKey, in: .app),
        .everyDevice(DownloadChoice.defaultKey, in: .app),
        .thisDevice(DownloadChoice.lastKey, in: .app),
        .thisDevice(MusicSource.storageKey, in: .app),
        .thisDevice(SourceSelection.storageKey, in: .app),
        .thisDevice(AutomaticDownloads.storageKey, in: .app),
        .thisDevice(StreamQuality.wiFiKey, in: .app),
        .thisDevice(StreamQuality.cellularKey, in: .app),
        .thisDevice(StreamQuality.downloadKey, in: .app),
        .thisDevice(Downloads.cellularKey, in: .app),
        .thisDevice(PlayPreferences.radioDownloadsFirstKey, in: .app),
        .thisDevice(PlayPreferences.radioDeletesAfterPlayingKey, in: .app),

        // History
        .thisDevice(CaptureService.pausedKey, in: .app),
        .thisDevice(ChartKind.storageKey, in: .app),
        .thisDevice("statsRange", in: .app),
    ]

    #if os(macOS)
    private static let platform: [StoredSetting] = [
        .everyDevice("showMenuBarExtra", in: .app),
        .everyDevice(MacAppBehaviour.showsDockIconKey, in: .app),
        .thisDevice(MacSettingsPane.storageKey, in: .app),
        // Whether macOS lets this Mac hear Music is asked and answered per Mac.
        .thisDevice(MusicHearing.choiceKey, in: .app),
        .thisDevice(MusicHearing.refusedKey, in: .app),
    ]
    #else
    private static let platform: [StoredSetting] = [
        .thisDevice(OfflineMode.storageKey, in: .app),
    ]
    #endif
}

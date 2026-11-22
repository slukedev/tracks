import SwiftUI
import TracksCore

/// What Tracks keeps, when a play counts, and mending covers. An iPhone page, and the Mac's
/// History pane.
struct HistorySettingsPage: View {
    @AppStorage(CaptureSettings.capturesOnDemandKey, store: CaptureSettings.sharedDefaults)
    private var keepsOnDemand = true
    @AppStorage(CaptureSettings.minimumListenShareKey, store: CaptureSettings.sharedDefaults)
    private var minimumListenShare = CaptureSettings.defaultMinimumListenShare
    @AppStorage(CaptureSettings.dedupeWindowKey, store: CaptureSettings.sharedDefaults)
    private var dedupeSeconds: Double = DedupePolicy.default.window
    @AppStorage(SourceScope.separatesKey) private var separatesSources = false
    /// Nil in a preview, where there's no history to ask.
    @Environment(AppModel.self) private var model: AppModel?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Nil where capture isn't running at all, as in a preview.
    @Environment(CaptureService.self) private var capture: CaptureService?
    /// The switch's side of the pause, followed both ways with the service's.
    @State private var isPaused = UserDefaults.standard.bool(forKey: CaptureService.pausedKey)

    var body: some View {
        Form {
            SettingsHero(
                "History",
                subtitle: Text(HistorySettingsWords.status(keepsOnDemand: keepsOnDemand, isPaused: isPaused)),
                systemImage: "clock.fill",
                tint: .teal
            )
            if let keptAside = model.flatMap(LibraryKeptAside.init(model:)) {
                LibraryKeptAsideRow(keptAside: keptAside)
            }
            pauseSection
            keptSection
            countingSection
            statisticsSection
            ArtworkRepairSection()
        }
        .animation(SettingsMotion.row(reduceMotion: reduceMotion), value: isPaused)
        .onChange(of: isPaused) { _, paused in
            guard let capture, paused != capture.isPaused else { return }
            if paused { capture.pause() } else { Task { await capture.resume() } }
        }
        .onChange(of: capture?.isPaused) { _, paused in
            // Paused or resumed elsewhere, as from the Mac's menu bar.
            if let paused, paused != isPaused { isPaused = paused }
        }
        #if os(macOS)
        .settingsPane()
        #else
        .settingsPage("History")
        #endif
    }

    // MARK: - Pausing

    private var pauseSection: some View {
        Section {
            SettingsSwitch("Pause History", isOn: $isPaused)
        } footer: {
            // Easy to forget it's on, and what plays meanwhile is gone for good.
            if isPaused {
                Text("Songs you play aren’t kept or scrobbled until you turn this off.")
                    .contentTransition(.opacity)
            }
        }
    }

    // MARK: - What's kept

    private var keptSection: some View {
        Section {
            SettingsSwitch("Keep On-Demand Songs", isOn: $keepsOnDemand)
        }
    }

    // MARK: - Counting plays

    private var countingSection: some View {
        Section {
            SettingsSliderRow(
                title: "Counts After",
                value: $minimumListenShare,
                range: 0...CaptureSettings.maximumListenShare,
                step: 0.05,
                minimumLabel: Text(HistorySettingsWords.percent(0)),
                maximumLabel: Text(HistorySettingsWords.percent(CaptureSettings.maximumListenShare)),
                describe: HistorySettingsWords.countsAfterValue
            )
            SettingsSliderRow(
                title: "Same Song Within",
                value: dedupeMinutes,
                range: 1...60,
                step: 1,
                minimumLabel: Text("1 min"),
                maximumLabel: Text("60 min"),
                describe: { HistorySettingsWords.windowValue(minutes: $0) }
            )
        } header: {
            Text("Counting Plays")
        } footer: {
            Text(HistorySettingsWords.countingFooter(share: minimumListenShare, windowMinutes: dedupeMinutes.wrappedValue))
                .contentTransition(.opacity)
        }
    }

    // MARK: - Statistics

    @ViewBuilder
    private var statisticsSection: some View {
        // Nothing to tell apart until something plays from Your Music. Left on, it stays, so
        // it can still be turned off.
        if model?.library.history.hasYourMusic == true || separatesSources {
            Section {
                SettingsSwitch("Separate Your Music", isOn: $separatesSources)
            } header: {
                Text("Statistics")
            }
        }
    }

    /// Stored in seconds, shown in minutes. A stored zero means the default, as
    /// `CaptureSettings.dedupePolicy` reads it.
    private var dedupeMinutes: Binding<Double> {
        Binding(
            get: { (dedupeSeconds > 0 ? dedupeSeconds : DedupePolicy.default.window) / 60 },
            set: { dedupeSeconds = $0 * 60 }
        )
    }
}

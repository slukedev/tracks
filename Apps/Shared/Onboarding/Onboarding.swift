import SwiftUI
import MusicKit
import TracksCore

/// Getting started on this device: the welcome, the few things worth setting up, and the
/// history coming down from iCloud.
///
/// Shown once per device, the first time Tracks opens there, and never to someone who was
/// using Tracks here before. Nothing on it is required: Start Listening is always there.
/// The restore keeps being followed after the welcome closes, until the history has arrived.
@MainActor
@Observable
final class Onboarding {
    enum Page: Hashable { case welcome, setup }

    var page: Page
    private(set) var isPresented: Bool
    private(set) var restore: HistoryRestore
    /// The size of the history in iCloud, as another device noted it.
    private(set) var expectedPlays: Int?
    let lastFM: LastFMConnection
    /// A pretend state for screenshots, in a demo launch only.
    let demo: OnboardingDemo?

    @ObservationIgnored private var record: OnboardingRecord
    @ObservationIgnored private let isDemoLaunch: Bool
    @ObservationIgnored private let startedAt = Date.now
    @ObservationIgnored private var lastCount: Int?
    @ObservationIgnored private var lastArrival: Date?
    @ObservationIgnored private var following: Task<Void, Never>?
    @ObservationIgnored private let beacon = HistoryCountBeacon()

    static let shared = Onboarding(model: .shared)

    init(model: AppModel) {
        let isDemoLaunch = model.isDemoLaunch
        let demo = isDemoLaunch ? OnboardingDemo.requested : nil
        let record = isDemoLaunch ? OnboardingRecord() : OnboardingRecord.load()
        if !isDemoLaunch, record.needsWelcome {
            LastFMSessionStore.recoverAfterReinstall()
        }
        let noted = isDemoLaunch ? nil : HistoryCountBeacon().noted
        self.isDemoLaunch = isDemoLaunch
        self.demo = demo
        self.record = record
        self.lastFM = LastFMConnection()
        self.expectedPlays = noted
        self.restore = demo?.restore ?? .looking
        self.page = demo?.page ?? ((noted ?? 0) > 0 ? .setup : .welcome)
        self.isPresented = demo != nil || (!isDemoLaunch && model.store != nil && record.needsWelcome)
    }

    /// iCloud already holds a history from another device or an earlier install.
    var isReturning: Bool {
        if let demo { return demo.isReturning }
        return (expectedPlays ?? 0) > 0
    }

    /// Closes the welcome. The restore carries on behind it.
    func finish() {
        isPresented = false
        guard demo == nil else { return }
        record.completedVersion = OnboardingRecord.currentVersion
        record.save()
    }

    // MARK: - Following the restore

    /// Follows the history arriving until there's nothing more to wait for, then notes this
    /// device's count for the next device to be set up. Safe to call again; one runs at a time.
    func follow(_ model: AppModel, cloud: CloudSyncSettings?) {
        guard demo == nil, !isDemoLaunch, following == nil else { return }
        following = Task { [weak self] in
            await cloud?.refresh()
            while let self, !Task.isCancelled {
                self.update(model, cloud: cloud)
                if self.record.restoreFinished { break }
                try? await Task.sleep(for: .seconds(2))
            }
            self?.following = nil
        }
    }

    /// The history changed: a capture, a sync, a forgotten song.
    func historyChanged(_ model: AppModel) {
        guard demo == nil, !isDemoLaunch, model.library.isLoaded else { return }
        // Only once iCloud has delivered this launch, so a device still catching up can't
        // lower the count another one is waiting for.
        if record.restoreFinished, model.syncMonitor?.lastSynced != nil {
            beacon.note(model.library.history.captures.count)
        }
    }

    private func update(_ model: AppModel, cloud: CloudSyncSettings?) {
        guard model.library.isLoaded else { return }
        let count = model.library.history.captures.count
        if let lastCount, count > lastCount { lastArrival = .now }
        lastCount = count
        let noted = beacon.noted
        if noted != expectedPlays { expectedPlays = noted }

        let phase = record.restoreFinished
            ? .finished(restored: count)
            : HistoryRestore.resolve(
                expected: noted,
                restored: count,
                startedAt: startedAt,
                lastArrival: lastArrival,
                blocked: cloud.flatMap { Self.blockedReason($0.status) }
            )
        if phase != restore { restore = phase }

        guard phase.isSettled, !record.restoreFinished else { return }
        record.restoreFinished = true
        record.save()
        if model.syncMonitor?.lastSynced != nil { beacon.note(count) }
    }

    /// Why the history can't come down here, or nil while it can or might.
    static func blockedReason(_ status: CloudSyncStatus) -> String? {
        switch status {
        case .unavailable, .off, .stopsNextLaunch:
            String(localized: "iCloud sync is off")
        case .notSignedIn, .restricted, .couldNotStart, .accountError, .failed(.setup, _), .failed(.import, _):
            status.line()
        case .startsNextLaunch, .checking, .waiting, .failed(.export, _), .on:
            nil
        }
    }
}

extension View {
    /// Shows getting started over the window the first time Tracks opens on this device,
    /// and follows the history arriving from iCloud.
    func onboarding(_ model: AppModel) -> some View {
        modifier(OnboardingPresenter(model: model))
    }
}

private struct OnboardingPresenter: ViewModifier {
    let model: AppModel
    @State private var cloud: CloudSyncSettings?
    @Environment(\.scenePhase) private var scenePhase

    private var onboarding: Onboarding { .shared }

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(get: { onboarding.isPresented }, set: { if !$0 { onboarding.finish() } })) {
                OnboardingView(onboarding: onboarding, cloud: cloud)
                    .environment(model)
                    .environment(model.yourMusic)
                    .interactiveDismissDisabled()
            }
            .task {
                if cloud == nil, let store = model.store {
                    cloud = CloudSyncSettings(store: store, monitor: model.syncMonitor)
                }
                onboarding.follow(model, cloud: cloud)
            }
            .onChange(of: model.library.revision) { onboarding.historyChanged(model) }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { onboarding.follow(model, cloud: cloud) }
            }
    }
}

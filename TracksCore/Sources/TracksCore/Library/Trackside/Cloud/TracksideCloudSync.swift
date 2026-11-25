import Foundation
import Observation
import SwiftData

/// Syncs the history, the hidden lists and the settings through Trackside Cloud on one of the
/// person's Trackside servers, while that's the backend chosen (``CloudSync/backend``).
///
/// Syncs when the server is reached, when it says something changed (its `cloud` event), and
/// a little after anything is saved here. The first sync with a server is a full merge: what
/// the server holds comes down, everything held here goes up, and the reconciler folds the
/// duplicates together as it does iCloud's. iCloud keeps what it had, untouched.
@MainActor
@Observable
public final class TracksideCloudSync {
    public enum State: Equatable, Sendable {
        case waiting
        case syncing
        case synced(Date)
        /// What went wrong and what to do, in a sentence.
        case failed(String)
    }

    public private(set) var state: State = .waiting
    public let serverID: String

    @ObservationIgnored private let api: TracksideCloudAPI
    @ObservationIgnored private let engine: CloudSyncEngine
    @ObservationIgnored private let store: TracksStore
    @ObservationIgnored private let deviceName: String?
    @ObservationIgnored private let platform: String?
    @ObservationIgnored private var isRegistered = false
    @ObservationIgnored private var scheduled: Task<Void, Never>?
    @ObservationIgnored private var running: Task<Void, Never>?
    @ObservationIgnored private var runAgain = false
    @ObservationIgnored private var saves: (any NSObjectProtocol)?

    /// - Parameter extraStores: what the app keeps beyond the history, such as its playlists
    ///   and their entries, synced after the history in ``CloudSyncEngine/order``.
    public init(serverID: String, client: TracksideClient, store: TracksStore, deviceName: String?, platform: String?, extraStores: [any CloudEntityStore] = []) {
        self.serverID = serverID
        self.store = store
        self.deviceName = deviceName
        self.platform = platform
        let api = TracksideCloudAPI(client: client)
        self.api = api
        let folder = TracksideCloudFolder.url.appending(path: serverID, directoryHint: .isDirectory)
        let hidden: [any CloudEntityStore] = [CloudForgottenSongsStore(), CloudBlockedArtistsStore(), CloudExcludedStationsStore()]
        engine = CloudSyncEngine(
            api: api,
            stores: store.cloudStores + hidden + extraStores,
            deviceID: DeviceIdentity.current,
            folder: folder
        )
        if Self.forgetOtherServers(keeping: serverID) {
            TracksideCloudSettings.shared.startOver()
        }
        TracksideCloudSettings.shared.attach(api, deviceID: DeviceIdentity.current)
        saves = NotificationCenter.default.addObserver(forName: ModelContext.didSave, object: nil, queue: nil) { [weak self] _ in
            Task { @MainActor in self?.syncSoon(after: .seconds(10)) }
        }
    }

    isolated deinit {
        if let saves { NotificationCenter.default.removeObserver(saves) }
        scheduled?.cancel()
    }

    /// Stops syncing: the backend changed, or the server was removed.
    public func stop() {
        scheduled?.cancel()
        scheduled = nil
        running?.cancel()
        if let saves { NotificationCenter.default.removeObserver(saves) }
        saves = nil
        TracksideCloudSettings.shared.detach()
    }

    /// Syncs after a moment, so a burst of changes goes up as one.
    public func syncSoon(after delay: Duration = .seconds(2)) {
        scheduled?.cancel()
        scheduled = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.sync()
        }
    }

    /// Syncs now, or once more after the sync running.
    public func sync() async {
        if let running {
            runAgain = true
            await running.value
            return
        }
        let task = Task { [weak self] in
            guard let self else { return }
            repeat {
                runAgain = false
                await syncOnce()
            } while runAgain && !Task.isCancelled
        }
        running = task
        await task.value
        running = nil
    }

    private func syncOnce() async {
        guard store.isOnDisk else {
            state = .failed(String(localized: "Tracks couldn’t open your history. Restart Tracks to sync it."))
            return
        }
        state = .syncing
        do {
            if !isRegistered {
                try await api.register(deviceID: DeviceIdentity.current, predecessors: DeviceIdentity.predecessors, name: deviceName, platform: platform)
                isRegistered = true
            }
            let report = try await engine.sync()
            try await TracksideCloudSettings.shared.sync()
            if report.pulled + report.removed > 0 {
                // Folds what came down into what was here, as iCloud's arrivals are.
                let merged = await SyncReconciler.merge(store)
                NotificationCenter.default.post(name: SyncReconciler.didReconcileNotification, object: nil, userInfo: [SyncReconciler.mergedCountKey: merged])
            }
            state = .synced(.now)
        } catch is CancellationError {
            state = .waiting
        } catch CloudSyncError.deviceMismatch {
            state = .failed(String(localized: "Your server knows this device by another name. Pair it again to keep syncing."))
        } catch TracksideError.featureUnavailable, TracksideError.notFound {
            state = .failed(String(localized: "This server doesn’t offer Trackside Cloud. Update it, or choose iCloud."))
        } catch TracksideError.quotaExceeded, TracksideError.insufficientStorage {
            state = .failed(String(localized: "Your server is out of space for synced history. Free some up, then try again."))
        } catch {
            state = .failed(String(localized: "Couldn’t reach your server. Tracks will try again."))
        }
    }

    /// Starts the next sync over from a full merge, as a move to this server from elsewhere
    /// does.
    public func startOver() async {
        await engine.startOver()
        TracksideCloudSettings.shared.startOver()
    }

    /// Keeps only this server's state: a move from another server starts over with this one.
    /// - Returns: whether anything was forgotten.
    private static func forgetOtherServers(keeping serverID: String) -> Bool {
        let marker = TracksideCloudFolder.url.appending(path: "server")
        let previous = try? String(contentsOf: marker, encoding: .utf8)
        guard previous != serverID else { return false }
        TracksideCloudFolder.clear()
        try? FileManager.default.createDirectory(at: TracksideCloudFolder.url, withIntermediateDirectories: true)
        try? Data(serverID.utf8).write(to: marker, options: .atomic)
        return true
    }
}

extension TracksideCloudSync.State {
    /// The page's status line.
    public func line(now: Date = .now) -> String {
        switch self {
        case .waiting: String(localized: "Waiting for your server")
        case .syncing: String(localized: "Syncing…")
        case .synced(let date):
            now.timeIntervalSince(date) < 60
                ? String(localized: "Up to date")
                : String(localized: "Synced \(date.formatted(.relative(presentation: .named)))")
        case .failed(let reason): reason
        }
    }

    /// The root row's value.
    public var short: String {
        switch self {
        case .waiting: String(localized: "Waiting")
        case .syncing: String(localized: "Syncing")
        case .synced: String(localized: "On")
        case .failed: String(localized: "Not Syncing")
        }
    }

    public var tone: SettingsTone {
        switch self {
        case .failed: .failure
        case .waiting: .attention
        case .syncing, .synced: .plain
        }
    }
}

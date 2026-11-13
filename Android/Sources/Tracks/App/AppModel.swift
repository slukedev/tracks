import Foundation
import Observation
import SkipFuse
import TracksStats

/// Everything the screens share: the plays, and the history the statistics are worked out from.
@MainActor @Observable
final class AppModel {
    private(set) var log = PlayLog()
    /// Rebuilt whenever ``log`` changes. Sorting and estimating listening once here keeps every
    /// screen's calculations to a few milliseconds.
    private(set) var history = ListeningHistory([])
    /// Bumps on every change, so screens can recalculate with `.task(id:)`.
    private(set) var revision = 0
    private(set) var isLoaded = false
    private var isLoading = false
    /// Why the history couldn't be read or saved, to show rather than lose plays silently.
    private(set) var storageError: String?

    private let fileURL: URL
    private let defaults: UserDefaults

    init(fileURL: URL = AppModel.defaultFileURL, defaults: UserDefaults = .standard) {
        self.fileURL = fileURL
        self.defaults = defaults
    }

    /// Application Support, which Skip points at the app's private `files` folder on Android.
    static var defaultFileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("plays.json")
    }

    // MARK: - Loading

    func load() async {
        guard !isLoaded, !isLoading else { return }
        isLoading = true
        defer { isLoading = false }
        let url = fileURL
        do {
            let loaded = try await Task.detached(priority: .userInitiated) { try PlayLog.load(from: url) }.value
            apply(loaded)
        } catch {
            storageError = error.localizedDescription
        }
        isLoaded = true
    }

    // MARK: - Changes

    var hasSamples: Bool { log.plays.contains { $0.isSample == true } }
    var hasRealPlays: Bool { log.plays.contains { $0.isSample != true } }
    var isEmpty: Bool { log.plays.isEmpty }

    /// Fills the history with the sample listening the iPhone and Mac show, to look around with.
    func addSamples() {
        var next = log
        next.add(Play.samples())
        commit(next)
    }

    func removeSamples() {
        var next = log
        next.removeAll { $0.isSample == true }
        commit(next)
    }

    /// Adds plays heard or imported, and returns how many were new.
    @discardableResult
    func add(_ plays: [Play]) -> Int {
        var next = log
        let added = next.add(plays)
        if added > 0 { commit(next) }
        return added
    }

    func delete(_ play: Play) {
        var next = log
        next.remove(id: play.id)
        commit(next)
    }

    func deleteEverything() {
        commit(PlayLog())
    }

    private func commit(_ next: PlayLog) {
        apply(next)
        let (url, snapshot) = (fileURL, next)
        Task.detached(priority: .utility) {
            do {
                try snapshot.save(to: url)
            } catch {
                await MainActor.run { self.storageError = error.localizedDescription }
            }
        }
    }

    private func apply(_ next: PlayLog) {
        log = next
        history = next.history()
        var counts: [String: Int] = [:]
        for play in next.plays { counts[play.songKey, default: 0] += 1 }
        playCounts = counts
        revision += 1
    }

    /// Plays of each song, by its folded title and artist.
    private(set) var playCounts: [String: Int] = [:]

    func playCount(of song: QueueSong) -> Int {
        playCounts[HistoryImport.key(title: song.title, artistName: song.artist)] ?? 0
    }

    // MARK: - Listening

    /// Keeps the songs the player counted since the last look, and returns the ones that were
    /// new, to scrobble to the server.
    @discardableResult
    func takeListens() -> [Play] {
        let url = fileURL.deletingLastPathComponent().appendingPathComponent(ListenQueue.fileName)
        let plays = ListenQueue.plays(from: ListenQueue.drain(at: url))
        guard !plays.isEmpty else { return [] }
        let before = Set(log.plays.map(\.id))
        add(plays)
        let after = Set(log.plays.map(\.id))
        // Kept now and not before: a duplicate of a play already kept is neither.
        return plays.filter { !before.contains($0.id) && after.contains($0.id) }
    }

    // MARK: - Lookups

    /// Every play, newest first, as History lists them.
    var newestFirst: [Play] { log.plays.reversed() }

    /// Songs played lately that can be played again, newest first, each once.
    func recentlyPlayed(limit: Int = 12) -> [Play] {
        var seen = Set<String>()
        var result: [Play] = []
        for play in log.plays.reversed() where play.songID != nil && play.serverID != nil && play.isSample != true {
            guard seen.insert(play.songKey).inserted else { continue }
            result.append(play)
            if result.count == limit { break }
        }
        return result
    }
}


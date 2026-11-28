import AVFoundation
import MusicKit
import SwiftUI
import TracksCore

@MainActor
@Observable
final class HandpickedPreview {
    enum Phase: Equatable {
        case loading
        case playing
        case paused
    }

    struct Progress: Equatable {
        var elapsed: TimeInterval = 0
        var length: TimeInterval = HandpickedPreview.length
        var runningSince: Date?

        func fraction(at date: Date) -> Double {
            let running = runningSince.map { date.timeIntervalSince($0) } ?? 0
            return min(max((elapsed + running) / max(length, 1), 0), 1)
        }
    }

    nonisolated static let length: TimeInterval = 30

    private(set) var current: String?
    private(set) var phase: Phase = .loading
    private(set) var progress = Progress()
    private(set) var unavailable: Set<String> = []
    private(set) var failed: String?

    @ObservationIgnored private var handoff = PreviewHandoff()
    @ObservationIgnored private let audio = AVPlayer()
    @ObservationIgnored private var statusObservation: NSKeyValueObservation?
    @ObservationIgnored private var itemObservation: NSKeyValueObservation?
    @ObservationIgnored private var endObserver: (any NSObjectProtocol)?
    @ObservationIgnored private var loading: Task<Void, Never>?
    @ObservationIgnored private var pretending: Task<Void, Never>?
    @ObservationIgnored private var clearingFailure: Task<Void, Never>?
    @ObservationIgnored private var startOffset: TimeInterval = 0
    @ObservationIgnored private weak var player: PlayerModel?

    init() {
        audio.automaticallyWaitsToMinimizeStalling = true
        #if os(iOS)
        audio.allowsExternalPlayback = false
        #endif
        statusObservation = audio.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.statusChanged() }
        }
    }

    func isCurrent(_ identity: String) -> Bool {
        current == identity
    }

    func canPreview(_ pick: Handpick, source: MusicSource, music: YourMusic, isDemo: Bool) -> Bool {
        guard !unavailable.contains(pick.identity) else { return false }
        if isDemo { return true }
        switch source {
        case .appleMusic:
            return music.network.isOnline && MusicAuthorization.currentStatus == .authorized
        case .yourMusic:
            guard let track = music.track(for: HistorySong(pick.mixSong)) else { return false }
            return music.isPlayable(track)
        }
    }

    func toggle(_ pick: Handpick, source: MusicSource, music: YourMusic, player: PlayerModel) {
        if current == pick.identity {
            switch phase {
            case .playing: pause()
            case .paused: playOn()
            case .loading: stop()
            }
            return
        }
        start(pick, source: source, music: music, player: player)
    }

    func stop(resumingSong: Bool = true) {
        let effects = handoff.stop(resumingSong: resumingSong)
        reset()
        apply(effects)
    }

    func songStartedElsewhere() {
        guard current != nil else { return }
        handoff.songStartedElsewhere()
        reset()
    }

    // MARK: Playing

    private func start(_ pick: Handpick, source: MusicSource, music: YourMusic, player: PlayerModel) {
        self.player = player
        let effects = handoff.start(pick.identity, songIsPlaying: player.isPlaying)
        reset(keepingCurrent: true)
        current = pick.identity
        phase = .loading
        apply(effects)
        let turn = handoff.turn
        if player.isDemo {
            pretend(turn: turn)
            return
        }
        loading = Task {
            let found = await Self.address(of: pick, source: source, music: music)
            guard !Task.isCancelled, handoff.isLatest(turn) else { return }
            guard let found else {
                unavailable.insert(pick.identity)
                couldNotPlay()
                return
            }
            play(found)
        }
    }

    private func play(_ found: Address) {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        if session.category != .playback {
            try? session.setCategory(.playback, mode: .default)
        }
        try? session.setActive(true)
        #endif
        let item = AVPlayerItem(url: found.url)
        startOffset = found.start
        if let end = found.end {
            item.forwardPlaybackEndTime = CMTime(seconds: end, preferredTimescale: 600)
        }
        progress = Progress(length: found.end.map { $0 - found.start } ?? Self.length)
        itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let failed = item.status == .failed
            Task { @MainActor in if failed { self?.couldNotPlay() } }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.stop() }
        }
        audio.replaceCurrentItem(with: item)
        if found.start > 0 {
            audio.seek(to: CMTime(seconds: found.start, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .positiveInfinity)
        }
        audio.play()
    }

    private func pause() {
        if pretending != nil {
            pretending?.cancel()
            pretending = nil
            progress.elapsed = progress.fraction(at: .now) * progress.length
            progress.runningSince = nil
            phase = .paused
            return
        }
        audio.pause()
        phase = .paused
    }

    private func playOn() {
        if player?.isDemo == true {
            pretend(turn: handoff.turn)
            return
        }
        audio.play()
    }

    private func pretend(turn: Int) {
        phase = .playing
        progress.runningSince = .now
        let remaining = (1 - progress.fraction(at: .now)) * progress.length
        pretending = Task {
            try? await Task.sleep(for: .seconds(remaining))
            guard !Task.isCancelled, handoff.isLatest(turn) else { return }
            stop()
        }
    }

    private func statusChanged() {
        guard current != nil, pretending == nil, audio.currentItem != nil else { return }
        let elapsed = max(audio.currentTime().seconds - startOffset, 0)
        switch audio.timeControlStatus {
        case .playing:
            if let duration = audio.currentItem?.duration.seconds, duration.isFinite, startOffset == 0, audio.currentItem?.forwardPlaybackEndTime.isValid != true {
                progress.length = min(duration, Self.length)
            }
            progress.elapsed = elapsed.isFinite ? elapsed : 0
            progress.runningSince = .now
            phase = .playing
        case .paused:
            progress.elapsed = elapsed.isFinite ? elapsed : progress.elapsed
            progress.runningSince = nil
            if phase == .playing { phase = .paused }
        case .waitingToPlayAtSpecifiedRate:
            progress.elapsed = elapsed.isFinite ? elapsed : progress.elapsed
            progress.runningSince = nil
        @unknown default:
            break
        }
    }

    private func couldNotPlay() {
        guard let identity = current else { return }
        stop()
        failed = identity
        clearingFailure?.cancel()
        clearingFailure = Task {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            if failed == identity { failed = nil }
        }
    }

    private func reset(keepingCurrent: Bool = false) {
        loading?.cancel()
        loading = nil
        pretending?.cancel()
        pretending = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        itemObservation = nil
        audio.pause()
        audio.replaceCurrentItem(with: nil)
        startOffset = 0
        progress = Progress()
        phase = .loading
        if !keepingCurrent { current = nil }
    }

    private func apply(_ effects: [PreviewHandoff.Effect]) {
        guard let player else { return }
        for effect in effects {
            switch effect {
            case .pauseSong: if player.isPlaying { player.togglePlayPause() }
            case .resumeSong: if !player.isPlaying { player.togglePlayPause() }
            }
        }
    }

    // MARK: Finding the preview

    struct Address: Sendable {
        let url: URL
        var start: TimeInterval = 0
        var end: TimeInterval?
    }

    private static func address(of pick: Handpick, source: MusicSource, music: YourMusic) async -> Address? {
        switch source {
        case .yourMusic:
            guard let track = music.track(for: HistorySong(pick.mixSong)) else { return nil }
            let found = music.needsDecision(track) ? await music.decidedPlayback(for: track) : music.playback(for: track)
            guard let found else { return nil }
            let window = previewWindow(duration: track.duration)
            return Address(url: found.url, start: window.start, end: window.end)
        case .appleMusic:
            if let url = pick.previewURL { return Address(url: url) }
            return await catalogPreview(of: pick).map { Address(url: $0) }
        }
    }

    /// Half a minute from a third of the way in, where a song's usually got going; a short
    /// song from its start.
    nonisolated static func previewWindow(duration: TimeInterval?) -> (start: TimeInterval, end: TimeInterval?) {
        guard let duration, duration.isFinite, duration > length * 1.5 else { return (0, duration.map { min($0, length) }) }
        let start = (duration * 0.33).rounded()
        return (start, min(start + length, duration))
    }

    /// The catalog's preview of a song: by its id for a catalog song, by its name for one from
    /// your library or history, which has another id.
    @concurrent
    nonisolated private static func catalogPreview(of pick: Handpick) async -> URL? {
        if pick.songID.allSatisfy(\.isNumber),
           let song = try? await MusicCatalogResourceRequest<Song>(matching: \.id, equalTo: MusicItemID(pick.songID)).response().items.first,
           let url = song.previewAssets?.first?.url {
            return url
        }
        var search = MusicCatalogSearchRequest(term: "\(pick.title) \(pick.artistName)", types: [Song.self])
        search.limit = 5
        guard let songs = try? await search.response().songs else { return nil }
        let wanted = pick.identity
        let match = songs.first { SongIdentity.key(title: $0.title, artistName: $0.artistName) == wanted } ?? songs.first {
            StatsCalculator.folded($0.artistName) == StatsCalculator.folded(pick.artistName)
        }
        return match?.previewAssets?.first?.url
    }
}

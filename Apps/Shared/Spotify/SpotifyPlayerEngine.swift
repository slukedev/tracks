#if SPOTIFY
import Foundation
import AVFoundation
import MediaPlayer
import MusicKit
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import TracksCore
import TracksEngine

/// Plays Spotify inside Tracks. The engine decodes each song to 44.1 kHz stereo PCM; a thread
/// of its own (``SpotifyFeeder``) pulls that into a lock-free ring, and an `AVAudioEngine`
/// source node plays from the ring on the real-time thread. The next song is decoded into
/// the ring behind the one playing, so albums play without a gap.
///
/// Songs from anywhere in Tracks play by name: a mix, a chart or an Apple Music page is
/// found on Spotify a song at a time, the first before playing and the rest as it plays.
/// The lock screen, Control Center and headphones control it, and capture keeps its plays
/// as Spotify's.
@MainActor
final class SpotifyPlayerEngine: PlayerEngine {
    var onChange: (() -> Void)?
    /// A moment's message for the person, shown by the player.
    var onNotice: ((String) -> Void)?
    var onRemoteNext: (() -> Void)?
    var onRemotePrevious: (() -> Void)?
    var onAutoSkip: (() -> Void)?

    private(set) var status: PlayerStatus = .stopped
    private(set) var isShuffled = false
    private(set) var repeatMode: PlayerRepeat = .off

    private struct Entry {
        let id: String
        let lookup: SpotifyLookup
        var track: SpotifyTrack?
    }

    private weak var service: SpotifyService?
    private let capture = PushedNowPlayingSource.yourMusic
    private let ring = SpotifyRing(seconds: 6)
    private var output: SpotifyAudioOutput
    private var feeder: SpotifyFeeder?

    private var entries: [Entry] = []
    private var unshuffled: [Entry] = []
    private var index = 0
    /// Entries Spotify couldn't open, skipped from then on.
    private var unplayable: Set<String> = []

    /// Each time a song plays it has a token of its own, so a song repeated, or the same song
    /// twice in a row, is told apart in the ring.
    private var serial = 0
    /// The token whose audio is being heard.
    private var heard: String?
    /// Asked for, not decoding yet: the player shows it loading, at `waitingTime`.
    private var waiting: String?
    private var waitingTime: TimeInterval = 0
    /// What the feeder was last told comes next.
    private var nextToken: String?
    private var tokens: [String: String] = [:]

    private var isMeantToPlay = false
    private var isActive = false
    private var ranOut = false
    private var failuresInARow = 0
    private var retried: (entryID: String, tries: Int)?
    private var resolving: Task<Void, Never>?
    private var polling: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    private var notifications: [any NSObjectProtocol] = []
    private var configurationObserver: (any NSObjectProtocol)?
    private var generation = 0
    private var level: Float = 1

    init(service: SpotifyService) {
        self.service = service
        output = SpotifyAudioOutput(ring: ring)
        followConfiguration()
        let center = NotificationCenter.default
        #if os(iOS)
        notifications.append(center.addObserver(forName: AVAudioSession.didBecomeInactiveNotification, object: nil, queue: nil) { [weak self] note in
            let context = note.userInfo?[AVAudioSession.deactivationContextKey] as? AVAudioSession.DeactivationContext
            guard context?.source == .system else { return }
            Task { @MainActor in self?.interruptionBegan() }
        })
        notifications.append(center.addObserver(forName: AVAudioSession.resumptionRecommendationNotification, object: nil, queue: nil) { [weak self] note in
            let context = note.userInfo?[AVAudioSession.resumptionContextKey] as? AVAudioSession.ResumptionContext
            let shouldResume = context?.recommendation == .shouldResume
            Task { @MainActor in self?.interruptionEnded(shouldResume: shouldResume) }
        })
        notifications.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
            guard reason == .oldDeviceUnavailable else { return }
            let previous = (note.userInfo?[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription)?.outputs.map(\.portType) ?? []
            let current = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portType)
            guard RoutePause.shouldPause(previous: previous, current: current, isPrivate: Self.privateOutputs.contains) else { return }
            Task { @MainActor in self?.pause() }
        })
        notifications.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil) { [weak self] _ in
            Task { @MainActor in self?.mediaServicesReset() }
        })
        #endif
    }

    /// The account signed in, out or again: a new feeder for it, and nothing left playing
    /// from the old one.
    func accountChanged(signingOut: Bool = false) {
        feeder?.shutDown()
        feeder = nil
        if signingOut || !entries.isEmpty {
            stopAll()
        }
    }

    private func makeFeeder() -> SpotifyFeeder? {
        if let feeder { return feeder }
        guard let service, service.isConnected, let account = service.account else { return nil }
        let feeder = SpotifyFeeder(account: account, ring: ring, options: service.streamOptions) { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
        self.feeder = feeder
        return feeder
    }

    // MARK: - What's on

    var current: PlayerTrack? {
        entries.indices.contains(index) ? playerTrack(entries[index]) : nil
    }

    var upNext: [PlayerTrack] {
        guard entries.indices.contains(index) else { return [] }
        return entries[(index + 1)...].filter { !unplayable.contains($0.id) }.map(playerTrack)
    }

    var playsByName: Bool { true }

    var playbackTime: TimeInterval {
        if waiting != nil { return waitingTime }
        guard let feeder, let heard else { return waitingTime }
        let read = ring.consumedCount
        let segment = feeder.timeline.withLock { timeline in timeline.segments.last { $0.start <= read && $0.token == heard } }
        guard let segment else { return waitingTime }
        var time = segment.startSeconds + SpotifyRing.seconds(fromSamples: read - segment.start)
        #if os(iOS)
        // What's rendered reaches the ear a little later, a lot later over AirPlay.
        time -= AVAudioSession.sharedInstance().outputLatency
        #endif
        return max(segment.startSeconds, time)
    }

    func canPlayNow(_ song: TracksCore.MixSong) -> Bool {
        service?.isConnected == true
    }

    private func playerTrack(_ entry: Entry) -> PlayerTrack {
        let track = entry.track
        let album = track?.album?.title ?? entry.lookup.album
        return PlayerTrack(
            id: entry.id,
            title: track?.title ?? entry.lookup.title,
            artistName: track?.artistLine ?? entry.lookup.artist,
            albumTitle: album,
            cover: .url(track?.artworkUrl, seed: album ?? entry.lookup.title),
            duration: track?.duration,
            isExplicit: track?.explicit ?? false,
            song: nil
        )
    }

    // MARK: - Playing

    func play(_ request: PlayRequest, context: PlayContext, shuffled: Bool) async throws {
        guard let service, service.isConnected else { throw Self.signedOut }
        let (lookups, start) = try Self.lookups(for: request)
        guard !lookups.isEmpty else { throw Self.notOnSpotify }
        generation += 1
        let asked = generation
        var queue = lookups.map { Entry(id: UUID().uuidString, lookup: $0, track: $0.uri.flatMap(service.knownTrack)) }
        var first = min(max(0, start), queue.count - 1)
        if shuffled {
            let lead = queue.remove(at: first)
            queue = [lead] + FreshShuffle.order(queue, artist: { $0.lookup.artist.lowercased() }, seed: .random(in: 0...UInt64.max))
            first = 0
        }
        // The first song that Spotify has.
        while queue.indices.contains(first), queue[first].track == nil {
            let found = await service.track(for: queue[first].lookup)
            guard asked == generation else { throw CancellationError() }
            if let found {
                queue[first].track = found
            } else {
                queue.remove(at: first)
            }
        }
        guard queue.indices.contains(first) else { throw Self.notOnSpotify }
        unshuffled = queue
        entries = queue
        index = first
        isShuffled = shuffled
        unplayable = []
        failuresInARow = 0
        activate()
        load(startPlaying: true)
        resolveTheRest()
    }

    func enqueue(_ request: PlayRequest, next: Bool) async throws {
        guard let service, service.isConnected else { throw Self.signedOut }
        let (lookups, _) = try Self.lookups(for: request)
        let added = lookups.map { Entry(id: UUID().uuidString, lookup: $0, track: $0.uri.flatMap(service.knownTrack)) }
        guard !added.isEmpty else { return }
        guard !entries.isEmpty else {
            entries = added
            unshuffled = added
            index = 0
            activate()
            await resolve(at: 0)
            load(startPlaying: true)
            resolveTheRest()
            return
        }
        let position = next ? index + 1 : entries.count
        entries.insert(contentsOf: added, at: position)
        if next, let at = unshuffled.firstIndex(where: { $0.id == entries[index].id }) {
            unshuffled.insert(contentsOf: added, at: at + 1)
        } else {
            unshuffled += added
        }
        resolveTheRest()
        if ranOut, entries.indices.contains(position) {
            index = position
            load(startPlaying: true)
            return
        }
        refreshNext()
        onChange?()
    }

    /// The songs a request stands for, to find on Spotify.
    private static func lookups(for request: PlayRequest) throws -> ([SpotifyLookup], Int) {
        switch request {
        case .history(let songs, let start):
            return (songs.map(SpotifyLookup.init), start)
        case .songs(let songs, let start):
            return (songs.map { SpotifyLookup(title: $0.title, artist: $0.artistName, album: $0.albumTitle, isrc: $0.isrc) }, start)
        case .local(let tracks, let start):
            return (tracks.map { SpotifyLookup(title: $0.title, artist: $0.artist, album: $0.album) }, start)
        case .album, .playlist, .station, .demoStation:
            throw PlayerProblem.failed(
                String(localized: "Choose Apple Music in the source filter to play it."),
                title: String(localized: "This Is Apple Music")
            )
        }
    }

    private static var signedOut: PlayerProblem {
        .failed(String(localized: "Sign in to Spotify in Settings to play it."), title: String(localized: "Spotify Is Signed Out"))
    }

    private static var notOnSpotify: PlayerProblem {
        .failed(String(localized: "Spotify doesn't have these songs."), title: String(localized: "Couldn't Find These Songs"))
    }

    /// Finds the rest of the queue on Spotify, in play order, so Up Next fills in as it goes.
    /// A song Spotify doesn't have leaves the queue.
    private func resolveTheRest() {
        resolving?.cancel()
        resolving = Task { [weak self] in
            while let self, !Task.isCancelled {
                guard let position = self.entries.indices.first(where: { $0 > self.index && self.entries[$0].track == nil && !self.unplayable.contains(self.entries[$0].id) })
                        ?? self.entries.indices.first(where: { self.entries[$0].track == nil && !self.unplayable.contains(self.entries[$0].id) })
                else { return }
                await self.resolve(at: position)
            }
        }
    }

    /// Looks one entry up, and drops it if Spotify doesn't have it.
    private func resolve(at position: Int) async {
        guard let service, entries.indices.contains(position) else { return }
        let id = entries[position].id
        let found = await service.track(for: entries[position].lookup)
        guard let now = entries.firstIndex(where: { $0.id == id }) else { return }
        if let found {
            entries[now].track = found
            if let kept = unshuffled.firstIndex(where: { $0.id == id }) { unshuffled[kept].track = found }
            if now == index + 1 { refreshNext() }
        } else {
            unplayable.insert(id)
            if now == index + 1 { refreshNext() }
        }
        onChange?()
    }

    func pause() {
        isMeantToPlay = false
        output.pause()
        updateStatus()
    }

    func resume() async throws {
        guard !entries.isEmpty else { throw PlayerProblem.nothingToPlay }
        guard service?.isConnected == true else { throw Self.signedOut }
        activate()
        if ranOut || heard == nil && waiting == nil {
            load(startPlaying: true, at: ranOut ? 0 : waitingTime)
            return
        }
        isMeantToPlay = true
        startOutput()
        updateStatus()
    }

    func skipToNext() async throws {
        guard !entries.isEmpty else { return }
        if let next = nextIndex(after: index, wrapping: repeatMode == .all) {
            index = next
            load(startPlaying: true)
        } else {
            // Past the end: stop on the last song, back at its start.
            isMeantToPlay = false
            output.pause()
            load(startPlaying: false)
            ranOut = true
        }
    }

    func skipToPrevious() async throws {
        guard !entries.isEmpty else { return }
        if playbackTime > 3 || index == 0 {
            seek(to: 0)
            return
        }
        index -= 1
        load(startPlaying: true)
    }

    func seek(to time: TimeInterval) {
        guard time.isFinite, entries.indices.contains(index), let feeder = makeFeeder(), let song = song(for: entries[index], fresh: false) else { return }
        ranOut = false
        waiting = song.token
        waitingTime = max(0, time)
        ring.dropAll()
        feeder.seek(song, to: waitingTime)
        updateStatus()
        updateNowPlayingInfo()
    }

    func setShuffled(_ shuffled: Bool) {
        guard shuffled != isShuffled, entries.indices.contains(index) else {
            isShuffled = shuffled
            return
        }
        let playing = entries[index]
        if shuffled {
            unshuffled = entries
            let rest = Array(entries[(index + 1)...]) + Array(entries[..<index])
            entries = [playing] + FreshShuffle.order(rest, artist: { $0.lookup.artist.lowercased() }, seed: .random(in: 0...UInt64.max))
            index = 0
        } else {
            entries = unshuffled
            index = entries.firstIndex { $0.id == playing.id } ?? 0
        }
        isShuffled = shuffled
        refreshNext()
        onChange?()
    }

    func setRepeat(_ mode: PlayerRepeat) {
        repeatMode = mode
        refreshNext()
        onChange?()
    }

    func jump(toUpNext offset: Int) async throws {
        let shown = upNextPositions
        guard shown.indices.contains(offset) else { return }
        index = shown[offset]
        load(startPlaying: true)
    }

    func removeUpNext(at offsets: IndexSet) {
        let shown = upNextPositions
        let removed = Set(offsets.compactMap { shown.indices.contains($0) ? entries[shown[$0]].id : nil })
        entries.removeAll { removed.contains($0.id) }
        unshuffled.removeAll { removed.contains($0.id) }
        refreshNext()
        onChange?()
    }

    func moveUpNext(from offsets: IndexSet, to destination: Int) {
        guard entries.indices.contains(index) else { return }
        var rest = entries[(index + 1)...].filter { !unplayable.contains($0.id) }
        guard let last = offsets.last, last < rest.count, (0...rest.count).contains(destination) else { return }
        rest.move(fromOffsets: offsets, toOffset: destination)
        entries = Array(entries[...index]) + rest
        if !isShuffled { unshuffled = entries }
        refreshNext()
        onChange?()
    }

    /// Where each song in ``upNext`` is in the queue.
    private var upNextPositions: [Int] {
        guard entries.indices.contains(index) else { return [] }
        return entries.indices.filter { $0 > index && !unplayable.contains(entries[$0].id) }
    }

    func setLevel(_ level: Float) {
        self.level = level
        output.volume = level
    }

    // MARK: - Decoding

    /// A new token for an entry, or the one it's playing under.
    private func song(for entry: Entry, fresh: Bool) -> SpotifyFeeder.Song? {
        guard let track = entry.track else { return nil }
        if !fresh, let heard = heard ?? waiting, tokens[heard] == entry.id {
            return SpotifyFeeder.Song(token: heard, uri: track.uri)
        }
        serial += 1
        let token = "\(entry.id)#\(serial)"
        tokens[token] = entry.id
        return SpotifyFeeder.Song(token: token, uri: track.uri)
    }

    /// Starts the current entry from `time`, finding it first if it hasn't been yet.
    private func load(startPlaying: Bool, at time: TimeInterval = 0) {
        ranOut = false
        isMeantToPlay = startPlaying
        heard = nil
        waitingTime = time
        guard entries.indices.contains(index), let feeder = makeFeeder() else {
            waiting = nil
            updateStatus()
            return
        }
        let entry = entries[index]
        guard entry.track != nil else {
            waiting = entry.id
            updateStatus()
            Task { [weak self] in
                guard let self else { return }
                await self.resolve(at: self.index)
                guard self.entries.indices.contains(self.index), self.entries[self.index].id == entry.id else { return }
                if self.entries[self.index].track != nil {
                    self.load(startPlaying: self.isMeantToPlay, at: time)
                } else {
                    self.skipPastFailure()
                }
            }
            return
        }
        guard let song = song(for: entry, fresh: true) else { return }
        if retried?.entryID != entry.id { retried = nil }
        let queued = Set(entries.map(\.id))
        tokens = tokens.filter { queued.contains($0.value) }
        waiting = song.token
        // Silent at once, rather than once the feeder gets to it.
        ring.dropAll()
        feeder.setOptions(service?.streamOptions ?? SpotifyStreamOptions(bitrate: .normal, normalise: true))
        feeder.load(song, at: time)
        nextToken = nil
        refreshNext()
        if startPlaying { startOutput() } else { output.pause() }
        startPolling()
        onChange?()
        updateStatus()
        publish()
        updateNowPlayingInfo()
    }

    /// Tells the feeder what follows the song playing, so it's ready without a gap.
    private func refreshNext() {
        guard let feeder, entries.indices.contains(index) else { return }
        let following: Int? = repeatMode == .one ? index : nextIndex(after: index, wrapping: repeatMode == .all)
        guard let following, let song = song(forNext: entries[following]) else {
            nextToken = nil
            feeder.setNext(nil)
            return
        }
        nextToken = song.token
        feeder.setNext(song)
    }

    private func song(forNext entry: Entry) -> SpotifyFeeder.Song? {
        if let nextToken, tokens[nextToken] == entry.id, let track = entry.track {
            return SpotifyFeeder.Song(token: nextToken, uri: track.uri)
        }
        return song(for: entry, fresh: true)
    }

    private func nextIndex(after position: Int, wrapping: Bool) -> Int? {
        let later = entries.indices.filter { $0 > position && !unplayable.contains(entries[$0].id) }
        if let first = later.first { return first }
        guard wrapping else { return nil }
        return entries.indices.first { !unplayable.contains(entries[$0].id) }
    }

    private func startOutput() {
        #if os(iOS)
        PlaybackAudioSession.activateRetryingLater()
        #endif
        output.volume = level
        if (try? output.start()) != nil { return }
        // The session may not be active yet, as when the car starts Tracks: once more, shortly.
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(600))
            guard let self, self.isMeantToPlay, !self.output.isRunning else { return }
            if (try? self.output.start()) == nil {
                self.onNotice?(String(localized: "Couldn't Play These Songs"))
            }
            self.updateStatus()
        }
    }

    private func handle(_ event: SpotifyFeeder.Event) {
        switch event {
        case .ready(let token):
            guard token == waiting else { return }
            failuresInARow = 0
            updateStatus()
        case .failed(let token, let error):
            failed(token, error: error)
        }
    }

    /// A song that wouldn't open or stopped part way.
    private func failed(_ token: String, error: SpotifyError?) {
        guard let entryID = tokens[token], let position = entries.firstIndex(where: { $0.id == entryID }) else { return }
        let problem = error.map { SpotifyProblem($0) } ?? .network
        switch problem {
        case .refused, .premium, .signIn:
            service?.playbackFailed(problem)
            isMeantToPlay = false
            output.pause()
            updateStatus()
            onNotice?(problem.message)
            return
        case .network:
            break
        }
        let isPlaying = position == index && (token == waiting || token == heard)
        guard isPlaying else {
            // The next song: skipped when its turn comes.
            if case .Unavailable = error { unplayable.insert(entryID) }
            if position == index + 1 || token == nextToken { refreshNext() }
            return
        }
        if case .Unavailable = error {
            unplayable.insert(entryID)
            skipPastFailure()
            return
        }
        // Dropped part way: again from where it got to, a couple of times, then on.
        let tries = retried?.entryID == entryID ? retried?.tries ?? 0 : 0
        guard tries < 2 else {
            onNotice?(problem.message)
            skipPastFailure()
            return
        }
        retried = (entryID, tries + 1)
        let time = playbackTime
        waiting = token
        waitingTime = time
        updateStatus()
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(2 * (tries + 1)))
            guard let self, self.entries.indices.contains(self.index), self.entries[self.index].id == entryID else { return }
            self.load(startPlaying: self.isMeantToPlay, at: time)
        }
    }

    /// On to the next song, unless a run of them wouldn't play either.
    private func skipPastFailure() {
        failuresInARow += 1
        guard failuresInARow < 10, nextIndex(after: index, wrapping: repeatMode == .all) != nil else {
            isMeantToPlay = false
            output.pause()
            updateStatus()
            onNotice?(String(localized: "Couldn't Play These Songs"))
            return
        }
        onAutoSkip?()
        Task { try? await skipToNext() }
    }

    // MARK: - Following the ring

    private func startPolling() {
        guard polling == nil else { return }
        polling = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self else { return }
                self.poll()
            }
        }
    }

    /// Which song is being heard, from where the render callback has got to.
    private func poll() {
        guard let feeder, entries.indices.contains(index) else { return }
        let read = ring.consumedCount
        let timeline = feeder.timeline.withLock { $0 }
        guard let segment = timeline.segments.last(where: { $0.start <= read }) else {
            updateStatus()
            return
        }
        if segment.token == waiting {
            waiting = nil
            heard = segment.token
            updateStatus()
            publish()
            updateNowPlayingInfo()
        } else if waiting == nil, segment.token != heard {
            arrived(at: segment.token)
        }
        if let end = timeline.end, read >= end, waiting == nil {
            ranOutOfSongs()
            return
        }
        updateStatus()
    }

    /// The ring moved on to the next song by itself.
    private func arrived(at token: String) {
        heard = token
        guard let entryID = tokens[token], let position = entries.firstIndex(where: { $0.id == entryID }) else { return }
        // A queue changed after the next song was decoded: what was decoded isn't what's next.
        let expected = repeatMode == .one ? index : nextIndex(after: index, wrapping: repeatMode == .all)
        guard position == expected || position == index else {
            if let expected {
                index = expected
                load(startPlaying: isMeantToPlay)
            }
            return
        }
        index = position
        failuresInARow = 0
        retried = nil
        nextToken = nil
        refreshNext()
        onChange?()
        publish()
        updateNowPlayingInfo()
    }

    /// The last song ended: stay on it, back at its start, as the other players do.
    private func ranOutOfSongs() {
        if let next = nextIndex(after: index, wrapping: repeatMode == .all), next != index || repeatMode == .one {
            // Found after the ring had already run dry.
            index = next
            load(startPlaying: isMeantToPlay)
            return
        }
        isMeantToPlay = false
        output.pause()
        load(startPlaying: false)
        ranOut = true
    }

    private func updateStatus() {
        let next: PlayerStatus
        if entries.isEmpty {
            next = .stopped
        } else if !isMeantToPlay {
            next = .paused
        } else if waiting != nil {
            next = .loading
        } else {
            let buffered = ring.writtenCount - ring.consumedCount
            let isDry = buffered < Int(SpotifyRing.sampleRate / 4) * SpotifyRing.channels
            let hasEnded = feeder?.timeline.withLock { $0.end != nil } ?? false
            next = isDry && !hasEnded ? .loading : (output.isRunning ? .playing : .paused)
        }
        guard next != status else { return }
        status = next
        if next == .playing { failuresInARow = 0 }
        onChange?()
        publish()
        updateNowPlayingInfo()
    }

    private func stopAll() {
        generation += 1
        resolving?.cancel()
        resolving = nil
        polling?.cancel()
        polling = nil
        feeder?.stop()
        output.pause()
        entries = []
        unshuffled = []
        unplayable = []
        index = 0
        heard = nil
        waiting = nil
        waitingTime = 0
        nextToken = nil
        tokens = [:]
        isMeantToPlay = false
        ranOut = false
        status = .stopped
        onChange?()
        publish()
    }

    // MARK: - Interruptions and the system

    private func followConfiguration() {
        if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
        // The output changed (a route, a sample rate): the engine stops, and starts again here.
        configurationObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: output.engine, queue: nil) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isMeantToPlay else { return }
                self.startOutput()
            }
        }
    }

    #if os(iOS)
    private var wasPlayingBeforeInterruption = false

    private func interruptionBegan() {
        wasPlayingBeforeInterruption = isMeantToPlay
    }

    private func interruptionEnded(shouldResume: Bool) {
        if wasPlayingBeforeInterruption, isMeantToPlay, shouldResume {
            startOutput()
        }
        wasPlayingBeforeInterruption = false
        updateStatus()
    }

    /// Every player the system had is gone: a new output carries on from the ring.
    private func mediaServicesReset() {
        output.stop()
        output = SpotifyAudioOutput(ring: ring)
        output.volume = level
        followConfiguration()
        guard isActive, isMeantToPlay else { return }
        startOutput()
    }

    nonisolated private static let privateOutputs: Set<AVAudioSession.Port> = [.headphones, .bluetoothA2DP, .bluetoothLE, .bluetoothHFP]
    #endif

    // MARK: - Being the player

    func activate() {
        #if os(iOS)
        PlaybackAudioSession.activateRetryingLater()
        #endif
        guard !isActive else { return }
        isActive = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            Self.onMain { self?.playFromRemote() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Self.onMain { self?.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Self.onMain {
                guard let self else { return }
                if self.isMeantToPlay { self.pause() } else { self.playFromRemote() }
            }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Self.onMain {
                guard let self else { return }
                if let onRemoteNext = self.onRemoteNext { onRemoteNext() } else { Task { try? await self.skipToNext() } }
            }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Self.onMain {
                guard let self else { return }
                if let onRemotePrevious = self.onRemotePrevious { onRemotePrevious() } else { Task { try? await self.skipToPrevious() } }
            }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let time = event.positionTime
            Self.onMain { self?.seek(to: time) }
            return .success
        }
    }

    private func playFromRemote() {
        Task { try? await resume() }
    }

    /// Stops and hands everything back for another player: the remote controls and the Now
    /// Playing entry. The audio session stays active (see ``PlaybackAudioSession``).
    func deactivate() {
        stopAll()
        artworkTask?.cancel()
        artworkTask = nil
        let wasActive = isActive
        isActive = false
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        MPNowPlayingInfoCenter.default().playbackState = .stopped
        guard wasActive else { return }
        let center = MPRemoteCommandCenter.shared()
        for command in [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand, center.nextTrackCommand, center.previousTrackCommand, center.changePlaybackPositionCommand] {
            command.removeTarget(nil)
        }
    }

    nonisolated private static func onMain(_ body: @escaping @MainActor @Sendable () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated(body)
        } else {
            Task { @MainActor in body() }
        }
    }

    // MARK: - Telling others

    /// Tells capture what's playing, marked as Spotify's, so History counts it as Spotify.
    private func publish() {
        guard entries.indices.contains(index), let track = entries[index].track else {
            capture.publish(nil)
            return
        }
        let token = heard ?? waiting ?? entries[index].id
        capture.publish(NowPlayingObservation(
            title: track.title,
            artistName: track.artistLine,
            albumTitle: track.album?.title,
            duration: track.duration,
            // Loading isn't playing: a song that never starts mustn't be counted.
            playbackState: status == .playing ? .playing : (status == .stopped ? .stopped : .paused),
            playerPosition: playbackTime,
            isStation: false,
            rawFields: [
                TracksCore.PlaySource.observationKey: TracksCore.PlaySource.spotifyMarker,
                PlayID.observationKey: playID(for: token),
            ]
        ))
    }

    /// One id for each time a song plays, so it's counted once however often it's reported.
    private var currentPlay: (token: String, id: String)?

    private func playID(for token: String) -> String {
        if let currentPlay, currentPlay.token == token { return currentPlay.id }
        let id = PlayID.make()
        currentPlay = (token, id)
        return id
    }

    nonisolated private static func nowPlayingArtwork(_ picture: CGImage) -> MPMediaItemArtwork {
        let size = CGSize(width: picture.width, height: picture.height)
        return MPMediaItemArtwork(boundsSize: size) { @Sendable _ in
            #if canImport(UIKit)
            UIImage(cgImage: picture)
            #else
            NSImage(cgImage: picture, size: size)
            #endif
        }
    }

    /// The lock screen's entry. Spotify asks that its songs always show their cover.
    private func updateNowPlayingInfo() {
        guard isActive else { return }
        #if os(macOS)
        let state: MPNowPlayingPlaybackState = switch status {
        case .playing: .playing
        case .loading: isMeantToPlay ? .playing : .paused
        case .paused: .paused
        case .stopped: .stopped
        }
        MPNowPlayingInfoCenter.default().playbackState = state
        #endif
        guard entries.indices.contains(index) else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        let entry = entries[index]
        let title = entry.track?.title ?? entry.lookup.title
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: entry.track?.artistLine ?? entry.lookup.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: playbackTime,
            MPNowPlayingInfoPropertyPlaybackRate: status == .playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let album = entry.track?.album?.title ?? entry.lookup.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if let duration = entry.track?.duration { info[MPMediaItemPropertyPlaybackDuration] = duration }
        let previous = MPNowPlayingInfoCenter.default().nowPlayingInfo
        if previous?[MPMediaItemPropertyTitle] as? String == title, let artwork = previous?[MPMediaItemPropertyArtwork] {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        guard info[MPMediaItemPropertyArtwork] == nil, let address = entry.track?.artworkUrl, let url = URL(string: address) else { return }
        artworkTask?.cancel()
        artworkTask = Task { [weak self] in
            guard let image = await ArtworkImages.shared.image(for: ArtworkImages.Key(url: url, pixels: 600)), !Task.isCancelled else { return }
            let artwork = Self.nowPlayingArtwork(image)
            guard var current = MPNowPlayingInfoCenter.default().nowPlayingInfo,
                  current[MPMediaItemPropertyTitle] as? String == title,
                  self?.isActive == true
            else { return }
            current[MPMediaItemPropertyArtwork] = artwork
            MPNowPlayingInfoCenter.default().nowPlayingInfo = current
        }
    }
}
#endif

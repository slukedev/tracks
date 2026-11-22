import Foundation
import AVFoundation
import MediaPlayer
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import TracksCore

/// Plays your own music: files, downloads and songs streamed from your servers, through
/// AVFoundation. FLAC plays as it is, bit for bit.
///
/// The next song is always queued behind the one playing, so an album flows from track to
/// track without a gap. The lock screen, Control Center and headphone buttons control it, and
/// every change is handed to capture, so Tracks keeps these plays as it keeps Apple Music's.
@MainActor
final class LocalPlayerEngine: NSObject, PlayerEngine {
    var onChange: (() -> Void)?

    private(set) var status: PlayerStatus = .stopped
    private(set) var isShuffled = false
    private(set) var repeatMode: PlayerRepeat = .off

    private struct Entry {
        let id: String
        let track: LocalTrack
    }

    private let music: YourMusic
    private let capture: PushedNowPlayingSource
    private let player = AVQueuePlayer()
    private var entries: [Entry] = []
    /// The queue as it was before shuffling, to go back to.
    private var unshuffled: [Entry] = []
    private var index = 0
    /// Which entry each queued player item plays.
    private var itemEntries: [ObjectIdentifier: String] = [:]
    /// Each queued item's load status, watched so one that can't play is skipped.
    private var itemObservations: [ObjectIdentifier: NSKeyValueObservation] = [:]
    /// The item playing, by identity: the same song queued twice is two items.
    private var currentItemID: ObjectIdentifier?
    /// The last item that failed, so a failure reported twice is handled once. Weak, as an
    /// identifier could match a new item made at a freed one's address.
    private weak var lastFailedItem: AVPlayerItem?
    /// The song that couldn't stream but that its server is getting: the player waits on it,
    /// loading, rather than moving on, and plays it when a sync brings it in.
    private var awaitingArrival: String?
    /// A moment's message for the person, shown by the player.
    var onNotice: ((String) -> Void)?
    /// Next and Previous from the lock screen, CarPlay, the steering wheel or headphones. Set
    /// by the player, which does what its own buttons do: waits for a live mix's next pick,
    /// and doesn't take going back for a skip. Unset, the engine skips by itself.
    var onRemoteNext: (() -> Void)?
    var onRemotePrevious: (() -> Void)?
    /// Called as a song that couldn't play is skipped, so the skip isn't taken for yours.
    var onAutoSkip: (() -> Void)?
    /// The queue ran out: the last song ended, or Next went past it. A song added now plays,
    /// rather than waiting behind a song that's over.
    private var ranOut = false
    private var observations: [NSKeyValueObservation] = []
    private var notifications: [any NSObjectProtocol] = []
    private var timeObserver: Any?
    private var isActive = false
    /// Songs in a row that wouldn't play, so a queue of missing files stops rather than spins.
    private var failuresInARow = 0
    /// The entry told to the server as playing, when it started, and whether its play has
    /// been counted.
    private var serverScrobble: (entryID: String, startedAt: Date, counted: Bool)?
    /// The song whose stream a Trackside server is deciding, before it can go in the player:
    /// the player shows it loading meanwhile.
    private var deciding: (entryID: String, task: Task<Void, Never>)?
    /// The next song, from Trackside, waiting to be asked for until shortly before the one
    /// playing ends, by when the server has its copy ready and it follows without a gap.
    private var nextWaiting: String?
    private var nextDeciding: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    /// Whether the person means it to be playing: set by playing, cleared by pausing. A call or
    /// a stalled stream pauses the player without changing it, so playing carries on after.
    private var isMeantToPlay = false
    /// The song whose stream dropped, being loaded again, and how often it has been.
    private var retried: (entryID: String, tries: Int)?
    /// Waiting a moment before loading it again: the player shows it loading meanwhile.
    private var recovering: (entryID: String, task: Task<Void, Never>)?
    /// Waits for a song loaded again to be ready, to carry on from where it was.
    private var resumeObservation: NSKeyValueObservation?

    init(music: YourMusic, capture: PushedNowPlayingSource) {
        self.music = music
        self.capture = capture
        super.init()
        music.onSongsArrived = { [weak self] in self?.songsArrived($0) }
        player.actionAtItemEnd = .advance
        player.automaticallyWaitsToMinimizeStalling = true
        // KVO calls back on whichever thread made the change, which AVFoundation sometimes
        // does on its own queue: assuming the main actor there would stop the app.
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Self.onMain { self?.updateStatus() }
        })
        observations.append(player.observe(\.currentItem, options: [.new]) { [weak self] _, _ in
            Self.onMain { self?.currentItemChanged() }
        })
        let center = NotificationCenter.default
        notifications.append(center.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: nil) { [weak self] note in
            let item = (note.object as AnyObject?).map(ObjectIdentifier.init)
            Task { @MainActor in self?.itemFinished(item) }
        })
        notifications.append(center.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: nil, queue: nil) { [weak self] note in
            let item = note.object as? AVPlayerItem
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
            Task { @MainActor in self?.itemFailed(item, error: error) }
        })
        #if os(iOS)
        notifications.append(center.addObserver(forName: AVAudioSession.didBecomeInactiveNotification, object: nil, queue: nil) { [weak self] note in
            // Only the system's: a session Tracks let go of itself isn't an interruption.
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
            // Headphones out: stop, as every player does, rather than play from the speaker. Not
            // for a car or an AirPlay speaker dropping out for a moment.
            let previous = (note.userInfo?[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription)?.outputs.map(\.portType) ?? []
            let current = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portType)
            guard RoutePause.shouldPause(previous: previous, current: current, isPrivate: Self.privateOutputs.contains) else { return }
            Task { @MainActor in self?.pause() }
        })
        #endif
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1, preferredTimescale: 10), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    // MARK: - What's on

    var current: PlayerTrack? {
        entries.indices.contains(index) ? playerTrack(entries[index]) : nil
    }

    var upNext: [PlayerTrack] {
        guard entries.indices.contains(index) else { return [] }
        return entries[(index + 1)...].map(playerTrack)
    }

    var playbackTime: TimeInterval {
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? max(0, seconds) : 0
    }

    var playsByName: Bool { true }

    private func playerTrack(_ entry: Entry) -> PlayerTrack {
        let track = entry.track
        return PlayerTrack(
            id: entry.id,
            title: track.title,
            artistName: track.artist,
            albumTitle: track.album,
            cover: .url(music.artworkURL(track.artwork)?.absoluteString, seed: track.album ?? track.title),
            duration: track.duration,
            isExplicit: false,
            song: nil,
            local: track
        )
    }

    // MARK: - Playing

    func play(_ request: PlayRequest, context: PlayContext, shuffled: Bool) async throws {
        let (tracks, start) = try resolve(request)
        var queue = tracks.map { Entry(id: UUID().uuidString, track: $0) }
        var first = min(max(0, start), queue.count - 1)
        unshuffled = queue
        if shuffled {
            let lead = queue.remove(at: first)
            queue = [lead] + FreshShuffle.order(queue, artist: { $0.track.artistKey }, seed: .random(in: 0...UInt64.max))
            first = 0
        }
        isShuffled = shuffled
        entries = queue
        index = first
        failuresInARow = 0
        activate()
        loadItems(startPlaying: true)
    }

    func canPlayNow(_ song: MixSong) -> Bool {
        music.canPlay(song)
    }

    func enqueue(_ request: PlayRequest, next: Bool) async throws {
        let (tracks, _) = try resolve(request)
        let added = tracks.map { Entry(id: UUID().uuidString, track: $0) }
        guard !entries.isEmpty else {
            entries = added
            unshuffled = added
            index = 0
            activate()
            loadItems(startPlaying: true)
            return
        }
        let position = next ? index + 1 : entries.count
        entries.insert(contentsOf: added, at: position)
        if let currentID = entries.indices.contains(index) ? entries[index].id : nil,
           let at = unshuffled.firstIndex(where: { $0.id == currentID }), next {
            unshuffled.insert(contentsOf: added, at: at + 1)
        } else {
            unshuffled += added
        }
        // The queue had run out: carry on with what's just been added.
        if ranOut, entries.indices.contains(position) {
            index = position
            failuresInARow = 0
            loadItems(startPlaying: true)
            return
        }
        refreshNext()
        onChange?()
    }

    /// The songs a request stands for, in your music and playable now.
    private func resolve(_ request: PlayRequest) throws -> ([LocalTrack], Int) {
        switch request {
        case .local(let tracks, let start):
            return try keepPlayable(tracks.enumerated().map { ($0.offset, $0.element) }, start: start)
        case .history(let songs, let start):
            return try keepPlayable(songs.enumerated().compactMap { offset, song in music.track(for: song).map { (offset, $0) } }, start: start)
        case .songs(let songs, let start):
            let found = songs.enumerated().compactMap { offset, song in
                music.track(for: HistorySong(songID: song.id.rawValue, title: song.title, artistName: song.artistName)).map { (offset, $0) }
            }
            return try keepPlayable(found, start: start)
        case .album, .playlist, .station, .demoStation:
            throw PlayerProblem.needsAppleMusic
        }
    }

    /// Drops what can't play now, keeping the asked-for start where it was, or the next one on.
    private func keepPlayable(_ tracks: [(offset: Int, track: LocalTrack)], start: Int) throws -> ([LocalTrack], Int) {
        let playable = tracks.filter { music.isPlayable($0.track) }
        guard !playable.isEmpty else { throw PlayerProblem.notInYourMusic }
        let first = playable.firstIndex { $0.offset >= start } ?? 0
        return (playable.map(\.track), first)
    }

    func pause() {
        isMeantToPlay = false
        // Paused while waiting for a song to arrive: it doesn't start by itself when it does.
        if awaitingArrival != nil {
            awaitingArrival = nil
            updateStatus()
        }
        player.pause()
    }

    func resume() async throws {
        guard !entries.isEmpty else { throw PlayerProblem.nothingToPlay }
        ranOut = false
        activate()
        // Its stream still being decided: it plays once it's in.
        if let deciding, entries.indices.contains(index), deciding.entryID == entries[index].id {
            isMeantToPlay = true
            return
        }
        // Loaded afresh, which moves on if the song can't be reached now.
        guard player.currentItem != nil else {
            loadItems(startPlaying: true)
            return
        }
        isMeantToPlay = true
        player.play()
    }

    func skipToNext() async throws {
        guard !entries.isEmpty else { return }
        if index + 1 < entries.count {
            index += 1
        } else if repeatMode == .all {
            index = 0
        } else {
            // Past the end: stop on the last song, back at its start.
            isMeantToPlay = false
            player.pause()
            // Before the seek's wait: set after it, it marked a queue played in the meantime as
            // run out, and the next song added jumped away from the one playing.
            ranOut = true
            await player.seek(to: .zero)
            return
        }
        loadItems(startPlaying: true)
    }

    func skipToPrevious() async throws {
        guard !entries.isEmpty else { return }
        if playbackTime > 3 || index == 0 {
            ranOut = false
            await player.seek(to: .zero)
            publish()
            return
        }
        index -= 1
        loadItems(startPlaying: true)
    }

    func seek(to time: TimeInterval) {
        // A drag across a bar with no width gives NaN, and an invalid time can make AVPlayer
        // raise.
        guard time.isFinite else { return }
        ranOut = false
        player.seek(to: CMTime(seconds: max(0, time), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in self?.updateNowPlayingInfo() }
        }
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
            entries = [playing] + FreshShuffle.order(rest, artist: { $0.track.artistKey }, seed: .random(in: 0...UInt64.max))
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
        player.actionAtItemEnd = mode == .one ? .none : .advance
        refreshNext()
        onChange?()
    }

    func jump(toUpNext offset: Int) async throws {
        let target = index + 1 + offset
        guard entries.indices.contains(target) else { return }
        index = target
        loadItems(startPlaying: true)
    }

    func removeUpNext(at offsets: IndexSet) {
        let removed = Set(offsets.compactMap { entries.indices.contains(index + 1 + $0) ? entries[index + 1 + $0].id : nil })
        entries.removeAll { removed.contains($0.id) }
        unshuffled.removeAll { removed.contains($0.id) }
        refreshNext()
        onChange?()
    }

    func moveUpNext(from offsets: IndexSet, to destination: Int) {
        // A list drawn before the queue changed can ask for songs no longer there.
        guard entries.indices.contains(index) else { return }
        var rest = Array(entries[(index + 1)...])
        guard let last = offsets.last, last < rest.count, (0...rest.count).contains(destination) else { return }
        rest.move(fromOffsets: offsets, toOffset: destination)
        entries = Array(entries[...index]) + rest
        if !isShuffled { unshuffled = entries }
        refreshNext()
        onChange?()
    }

    // MARK: - The player's queue

    /// Puts the current song, and the one after it, in the player.
    private func loadItems(startPlaying: Bool) {
        awaitingArrival = nil
        ranOut = false
        isMeantToPlay = startPlaying
        stopRecovering()
        stopDeciding()
        if retried?.entryID != (entries.indices.contains(index) ? entries[index].id : nil) { retried = nil }
        player.removeAllItems()
        itemEntries = [:]
        itemObservations = [:]
        currentItemID = nil
        guard entries.indices.contains(index) else {
            updateStatus()
            return
        }
        let entry = entries[index]
        withItem(for: entry) { [weak self] item in
            guard let self else { return }
            guard let item else {
                // Can't be reached right now (a server's song, offline): on to the next that can,
                // rather than waiting on nothing.
                self.onChange?()
                self.publish()
                self.updateNowPlayingInfo()
                if self.isMeantToPlay { self.skipPastFailure() }
                return
            }
            self.currentItemID = ObjectIdentifier(item)
            self.player.insert(item, after: nil)
            self.refreshNext()
            if self.isMeantToPlay {
                self.player.play()
                self.music.didStartPlaying(entry.track)
            }
            self.serverScrobble = nil
            self.onChange?()
            self.publish()
            self.updateNowPlayingInfo()
        }
    }

    /// Makes the player item for an entry, and hands it to `use`: at once for a file, a
    /// download or a Subsonic stream, and once the server has decided for a Trackside one,
    /// if the entry is still the one wanted then. Nil when it can't play now.
    private func withItem(for entry: Entry, _ use: @escaping @MainActor (AVPlayerItem?) -> Void) {
        if let (url, playback) = music.playback(for: entry.track) {
            use(makeItem(entry, url: url, playback: playback))
            return
        }
        guard music.needsDecision(entry.track) else {
            use(nil)
            return
        }
        let task = Task { [weak self, music] in
            let decided = await music.decidedPlayback(for: entry.track)
            guard let self, !Task.isCancelled, self.deciding?.entryID == entry.id else { return }
            self.deciding = nil
            use(decided.map { self.makeItem(entry, url: $0.url, playback: $0.playback) })
            self.updateStatus()
        }
        deciding = (entry.id, task)
        updateStatus()
        onChange?()
        publish()
    }

    private func stopDeciding() {
        deciding?.task.cancel()
        deciding = nil
    }

    /// Replaces whatever is queued behind the current song with the song that comes next.
    /// A Trackside song is asked to be made ready now, and queued shortly before the current
    /// one ends.
    private func refreshNext() {
        defer { forgetQualities() }
        nextWaiting = nil
        nextDeciding?.cancel()
        nextDeciding = nil
        for item in player.items().dropFirst() {
            itemEntries[ObjectIdentifier(item)] = nil
            itemObservations[ObjectIdentifier(item)] = nil
            player.remove(item)
        }
        guard repeatMode != .one, let next = nextIndex(after: index) else { return }
        let entry = entries[next]
        // A song the server doesn't have is only asked for once it's the one playing.
        guard !UnownedSong.isUnowned(entry.track) else { return }
        if music.needsDecision(entry.track) {
            let after = nextIndex(after: next).map { [entries[$0].track] } ?? []
            music.prepareUpcoming([entry.track] + after)
            nextWaiting = entry.id
            queueWaitingNextIfDue()
            return
        }
        guard let (url, playback) = music.playback(for: entry.track) else { return }
        insertNext(makeItem(entry, url: url, playback: playback))
    }

    private func insertNext(_ item: AVPlayerItem) {
        if player.canInsert(item, after: player.items().last) {
            player.insert(item, after: player.items().last)
        }
    }

    /// How long before the current song ends the next Trackside song is asked for.
    private static let nextLead: TimeInterval = 30

    /// Queues the waiting next song once the current one is near its end, or has no known
    /// length, so the server has had time to make it ready.
    private func queueWaitingNextIfDue() {
        guard let waiting = nextWaiting, nextDeciding == nil, entries.indices.contains(index) else { return }
        if let duration = entries[index].track.duration, duration - playbackTime > Self.nextLead { return }
        guard let position = entries.firstIndex(where: { $0.id == waiting }) else { return }
        let entry = entries[position]
        nextDeciding = Task { [weak self, music] in
            let decided = await music.decidedPlayback(for: entry.track)
            guard let self, !Task.isCancelled, self.nextWaiting == entry.id else { return }
            self.nextWaiting = nil
            self.nextDeciding = nil
            // Only behind the song it was meant to follow, which is all that's queued.
            guard let decided, self.player.items().count == 1 else { return }
            self.insertNext(self.makeItem(entry, url: decided.url, playback: decided.playback))
        }
    }

    private func nextIndex(after position: Int) -> Int? {
        if position + 1 < entries.count { return position + 1 }
        return repeatMode == .all && !entries.isEmpty ? 0 : nil
    }

    func setLevel(_ level: Float) {
        player.volume = level
    }

    private func makeItem(_ entry: Entry, url: URL, playback: LocalPlayback) -> AVPlayerItem {
        let asset = AVURLAsset(url: url)
        let item = AVPlayerItem(asset: asset)
        noteQuality(playback, of: asset, for: entry.id)
        // Stage's visualizer listens in; it costs nothing while Stage is closed.
        AudioLevelMeter.attach(to: item, asset: asset)
        // FLAC is decoded as it plays; a little buffer keeps a stream steady.
        item.preferredForwardBufferDuration = 20
        let id = ObjectIdentifier(item)
        itemEntries[id] = entry.id
        itemObservations[id] = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Task { @MainActor [weak item] in self?.itemFailed(item) }
        }
        return item
    }

    /// Notes how a song is reaching the player, for the quality badge, then listens to what
    /// arrived: a server asked for a smaller copy may send the original instead. A file's own
    /// tags already say what it is.
    private func noteQuality(_ playback: LocalPlayback, of asset: AVURLAsset, for entryID: String) {
        music.playbacks[entryID] = playback
        guard playback.route != .file, playback.actual == nil else { return }
        Task { [weak self] in
            guard let found = await AudioQualityProbe.format(of: asset),
                  let self, var noted = self.music.playbacks[entryID], noted.route == playback.route
            else { return }
            noted.actual = found
            self.music.playbacks[entryID] = noted
        }
    }

    /// Forgets how songs no longer queued in the player were reaching it.
    private func forgetQualities() {
        let queued = Set(itemEntries.values)
        music.playbacks = music.playbacks.filter { queued.contains($0.key) }
    }

    /// The player moved on to the next item by itself, gaplessly: follow it. By the item, not
    /// the place in the queue, which stays the same when a one-song queue repeats.
    private func currentItemChanged() {
        guard let item = player.currentItem, ObjectIdentifier(item) != currentItemID,
              let entryID = itemEntries[ObjectIdentifier(item)],
              let position = entries.firstIndex(where: { $0.id == entryID })
        else { return }
        currentItemID = ObjectIdentifier(item)
        awaitingArrival = nil
        stopRecovering()
        retried = nil
        // Items that have played are gone from the player; forget them.
        let queued = Set(player.items().map(ObjectIdentifier.init))
        itemEntries = itemEntries.filter { queued.contains($0.key) }
        itemObservations = itemObservations.filter { queued.contains($0.key) }
        index = position
        failuresInARow = 0
        serverScrobble = nil
        stopDeciding()
        music.didStartPlaying(entries[index].track)
        refreshNext()
        onChange?()
        publish()
        updateNowPlayingInfo()
        // Queued behind the last song, it failed while it waited, when nothing was listening
        // for it: it would sit here, never playing.
        if item.status == .failed { itemFailed(item) }
    }

    private func itemFinished(_ item: ObjectIdentifier?) {
        // Only the song playing: word of one that ended just before can arrive after the next began.
        guard let item, item == currentItemID else { return }
        if repeatMode == .one {
            player.seek(to: .zero)
            player.play()
            serverScrobble = nil
            return
        }
        guard let next = nextIndex(after: index) else {
            // The last song ended: stay on it, back at the start.
            player.pause()
            loadItems(startPlaying: false)
            ranOut = true
            return
        }
        // The next song couldn't be queued behind this one, as it couldn't be reached then, or
        // it failed while it waited: move on to it now, which skips it if it still can't play.
        let isQueued = player.items().contains { itemEntries[ObjectIdentifier($0)] == entries[next].id && $0.status != .failed }
        if !isQueued {
            index = next
            loadItems(startPlaying: true)
        }
    }

    private func itemFailed(_ item: AVPlayerItem?, error: NSError? = nil) {
        // Word of a failure can arrive after the queue was cleared or replaced.
        guard let item, ObjectIdentifier(item) == currentItemID, item !== lastFailedItem,
              entries.indices.contains(index)
        else { return }
        lastFailedItem = item
        if retry(item, error: error ?? item.error) { return }
        // A song found for you that its server couldn't preview, often because it couldn't find
        // it anywhere to stream, but is fetching the file for: wait here for it, not skip past.
        let track = entries[index].track
        if track.isFromServer, !music.isInYourMusic(track), music.servers.isWaitingToKeep(track) {
            awaitingArrival = entries[index].id
            updateStatus()
            onNotice?(String(localized: "\u{201C}\(track.title)\u{201D} Will Play Once Your Server Has It"))
            return
        }
        skipPastFailure()
    }

    /// A song streaming from a server that stopped part way, its connection dropped or the
    /// server slow to answer: loaded again after a moment, carrying on from where it got to,
    /// rather than skipped. False when it's been tried enough, or won't play however often.
    private func retry(_ item: AVPlayerItem, error: (any Error)?) -> Bool {
        let entry = entries[index]
        let tries = retried?.entryID == entry.id ? retried?.tries ?? 0 : 0
        let isStream = entry.track.isFromServer && !music.downloads.isDownloaded(entry.track.id)
        guard case .retry(let delay) = StreamRetry.step(after: error, isStream: isStream, tried: tries) else { return false }
        retried = (entry.id, tries + 1)
        let time = item.currentTime()
        stopRecovering()
        let task = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self, self.recovering?.entryID == entry.id,
                  self.entries.indices.contains(self.index), self.entries[self.index].id == entry.id
            else { return }
            self.reload(from: time)
        }
        recovering = (entry.id, task)
        updateStatus()
        return true
    }

    private func stopRecovering() {
        recovering?.task.cancel()
        recovering = nil
        resumeObservation = nil
    }

    /// Loads the current song again and carries on from `time`, playing if it's meant to be.
    /// A Trackside song is decided afresh, which goes to the address that answers now.
    private func reload(from time: CMTime) {
        recovering = nil
        player.removeAllItems()
        itemEntries = [:]
        itemObservations = [:]
        currentItemID = nil
        withItem(for: entries[index]) { [weak self] item in
            guard let self else { return }
            guard let item else {
                // Still out of reach: on to the next that can play.
                self.onChange?()
                self.publish()
                if self.isMeantToPlay { self.skipPastFailure() } else { self.updateStatus() }
                return
            }
            self.carryOn(item, from: time)
        }
    }

    private func carryOn(_ item: AVPlayerItem, from time: CMTime) {
        let id = ObjectIdentifier(item)
        currentItemID = id
        player.insert(item, after: nil)
        refreshNext()
        if time.isNumeric, time.seconds > 1 {
            // Once it's ready, to where it was, then on: seeking sooner isn't allowed.
            resumeObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
                guard item.status == .readyToPlay else { return }
                Self.onMain {
                    guard let self, let current = self.player.currentItem, ObjectIdentifier(current) == id, self.resumeObservation != nil else { return }
                    self.resumeObservation = nil
                    current.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                        Self.onMain {
                            guard let self, self.currentItemID == id, self.isMeantToPlay else { return }
                            self.player.play()
                        }
                    }
                }
            }
        } else if isMeantToPlay {
            player.play()
        }
        updateStatus()
        onChange?()
        publish()
        updateNowPlayingInfo()
    }

    /// A song that couldn't play: on to the next, unless a run of them couldn't either.
    private func skipPastFailure() {
        failuresInARow += 1
        guard failuresInARow < entries.count, failuresInARow < 10 else {
            isMeantToPlay = false
            player.pause()
            onNotice?(String(localized: "Couldn't Play These Songs"))
            return
        }
        onAutoSkip?()
        Task { try? await skipToNext() }
    }

    /// Songs a sync brought in after being asked for: the one being waited on plays, as the
    /// copy the server kept.
    private func songsArrived(_ tracks: [LocalTrack]) {
        guard let waiting = awaitingArrival, entries.indices.contains(index), entries[index].id == waiting,
              let arrived = tracks.first(where: { $0.identity == entries[index].track.identity })
        else { return }
        awaitingArrival = nil
        lastFailedItem = nil
        entries[index] = Entry(id: waiting, track: arrived)
        if let position = unshuffled.firstIndex(where: { $0.id == waiting }) {
            unshuffled[position] = entries[index]
        }
        loadItems(startPlaying: true)
    }

    private func updateStatus() {
        let next: PlayerStatus = if entries.isEmpty {
            .stopped
        } else if let awaitingArrival, entries.indices.contains(index), entries[index].id == awaitingArrival {
            .loading
        } else if let recovering, entries.indices.contains(index), entries[index].id == recovering.entryID {
            .loading
        } else if let deciding, entries.indices.contains(index), entries[index].id == deciding.entryID {
            .loading
        } else {
            switch player.timeControlStatus {
            case .playing: .playing
            case .waitingToPlayAtSpecifiedRate: .loading
            case .paused: .paused
            @unknown default: .paused
            }
        }
        guard next != status else { return }
        status = next
        // A song playing ends a run of ones that wouldn't.
        if next == .playing { failuresInARow = 0 }
        onChange?()
        publish()
        updateNowPlayingInfo()
    }

    private func tick() {
        guard status == .playing, entries.indices.contains(index) else { return }
        scrobbleToServerIfDue()
        queueWaitingNextIfDue()
    }

    /// Runs `body` straight away on the main thread, in order, or hops there from any other.
    nonisolated private static func onMain(_ body: @escaping @MainActor @Sendable () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated(body)
        } else {
            Task { @MainActor in body() }
        }
    }

    // MARK: - Interruptions

    #if os(iOS)
    private var wasPlayingBeforeInterruption = false

    private func interruptionBegan() {
        // Meant, not what the player says: it may have paused already by the time word comes.
        wasPlayingBeforeInterruption = isMeantToPlay
    }

    private func interruptionEnded(shouldResume: Bool) {
        // Paused by hand during the interruption: it stays paused.
        if wasPlayingBeforeInterruption, isMeantToPlay, shouldResume {
            PlaybackAudioSession.activateRetryingLater()
            player.play()
        }
        wasPlayingBeforeInterruption = false
    }

    /// Outputs heard by the listener alone, which going away pauses the music.
    nonisolated private static let privateOutputs: Set<AVAudioSession.Port> = [.headphones, .bluetoothA2DP, .bluetoothLE, .bluetoothHFP]
    #endif

    // MARK: - Being the player

    /// Takes over the audio session, the lock screen and the remote controls.
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
                if self.status == .playing { self.pause() } else { self.playFromRemote() }
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

    /// Play from the lock screen, CarPlay or headphones: the song on plays, from where it is.
    private func playFromRemote() {
        ranOut = false
        guard player.currentItem != nil || entries.isEmpty else {
            loadItems(startPlaying: true)
            return
        }
        isMeantToPlay = true
        player.play()
    }

    /// Hands everything back, for Apple Music to take over: the remote controls, and a Now
    /// Playing entry of its own, which CarPlay would otherwise keep showing, and send its
    /// buttons to, instead of Apple Music's. The audio session stays active for Apple Music
    /// (see ``PlaybackAudioSession``).
    func deactivate() {
        isMeantToPlay = false
        stopRecovering()
        stopDeciding()
        nextDeciding?.cancel()
        nextDeciding = nil
        nextWaiting = nil
        retried = nil
        player.pause()
        player.removeAllItems()
        itemEntries = [:]
        itemObservations = [:]
        currentItemID = nil
        lastFailedItem = nil
        awaitingArrival = nil
        music.playbacks = [:]
        entries = []
        unshuffled = []
        index = 0
        ranOut = false
        status = .stopped
        publish()
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

    /// The Mac's media keys and Now Playing follow this rather than the playback rate, so it's
    /// set on every start and stop. iOS ignores it.
    private func updateNowPlayingState() {
        #if os(macOS)
        guard isActive else { return }
        let state: MPNowPlayingPlaybackState = switch status {
        case .playing: .playing
        case .loading: isMeantToPlay ? .playing : .paused
        case .paused: .paused
        case .stopped: .stopped
        }
        MPNowPlayingInfoCenter.default().playbackState = state
        #endif
    }

    // MARK: - Telling others

    /// Tells capture what's playing, so these plays are kept like any other.
    private func publish() {
        guard entries.indices.contains(index) else {
            capture.publish(nil)
            return
        }
        let track = entries[index].track
        capture.publish(NowPlayingObservation(
            title: track.title,
            artistName: track.artist,
            albumTitle: track.album,
            duration: track.duration,
            // Loading isn't playing: a song that never starts mustn't be counted.
            playbackState: status == .playing ? .playing : (status == .stopped ? .stopped : .paused),
            playerPosition: playbackTime,
            isStation: false,
            rawFields: ["source": "Your Music", "origin": track.isFromServer ? "server" : "file"]
        ))
    }

    /// Tells a server its song is playing, and once it's played long enough, that it counts.
    /// A Subsonic server is told there and then; a play for Trackside is kept until it's sent,
    /// so one heard offline still counts.
    private func scrobbleToServerIfDue() {
        let entry = entries[index]
        guard case .server(let serverID, let songID) = entry.track.origin, !UnownedSong.isUnowned(entry.track),
              // History paused: the server isn't told what played either.
              !CaptureService.isHistoryPaused,
              music.servers.reportsPlays(to: serverID)
        else { return }
        let subsonic = music.servers.subsonicClient(for: serverID)
        let isTrackside = music.servers.isTrackside(serverID)
        guard subsonic != nil || isTrackside else { return }
        if serverScrobble?.entryID != entry.id {
            serverScrobble = (entry.id, Date.now.addingTimeInterval(-playbackTime), false)
            if let subsonic {
                Task.detached { try? await subsonic.scrobble(songID: songID, submission: false) }
            } else {
                music.servers.nowPlaying(songID: songID, on: serverID)
            }
        }
        // Counted by Last.fm's rule, which the server passes plays on to, whatever Tracks's own
        // threshold for keeping a song is: Straight Away would otherwise count every skip.
        guard let needs = ScrobblePolicy().requiredPlayback(forDuration: entry.track.duration),
              let started = serverScrobble, !started.counted, playbackTime >= needs
        else { return }
        serverScrobble = (entry.id, started.startedAt, true)
        if let subsonic {
            Task.detached { try? await subsonic.scrobble(songID: songID, submission: true) }
        } else {
            let track = entry.track
            music.servers.recordListen(ServerListen(
                id: UUID().uuidString,
                songID: songID,
                startedAt: started.startedAt,
                played: playbackTime,
                title: track.title,
                artist: track.artist,
                album: track.album,
                duration: track.duration
            ), on: serverID)
        }
    }

    /// The lock screen's picture. Made off the main actor: MediaPlayer asks for it on a queue
    /// of its own, and a closure made on the main actor checks it's there and stops the app.
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

    /// Only while it's the player in use: a seek or a song arriving after Apple Music took over
    /// mustn't bring its Now Playing entry back.
    private func updateNowPlayingInfo() {
        guard isActive else { return }
        updateNowPlayingState()
        guard entries.indices.contains(index) else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        let track = entries[index].track
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: playbackTime,
            MPNowPlayingInfoPropertyPlaybackRate: status == .playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let album = track.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if let duration = track.duration { info[MPMediaItemPropertyPlaybackDuration] = duration }
        let previous = MPNowPlayingInfoCenter.default().nowPlayingInfo
        if previous?[MPMediaItemPropertyTitle] as? String == track.title, let artwork = previous?[MPMediaItemPropertyArtwork] {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        guard info[MPMediaItemPropertyArtwork] == nil, let url = music.artworkURL(track.artwork) else { return }
        artworkTask?.cancel()
        artworkTask = Task { [weak self] in
            guard let image = await ArtworkImages.shared.image(for: ArtworkImages.Key(url: url, pixels: 600)), !Task.isCancelled else { return }
            let artwork = Self.nowPlayingArtwork(image)
            guard var current = MPNowPlayingInfoCenter.default().nowPlayingInfo,
                  current[MPMediaItemPropertyTitle] as? String == track.title,
                  self?.isActive == true
            else { return }
            current[MPMediaItemPropertyArtwork] = artwork
            MPNowPlayingInfoCenter.default().nowPlayingInfo = current
        }
    }
}

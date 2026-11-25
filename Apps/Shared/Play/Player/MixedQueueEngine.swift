import Foundation
import MusicKit
import TracksCore

/// One queue across sources: Apple Music, your files and servers, and Spotify, song by song.
///
/// Each song keeps the source it was queued from. Songs in a row from one source are handed to
/// that source's player together, so they follow each other as that player plays them, without
/// a gap. Where the source changes, the player that finished lets go of the lock screen, the
/// remote controls and capture before the next one takes them, so Now Playing and History
/// always follow the song actually playing. The cut there is clean: no crossfade between two
/// players.
///
/// ``PlayerModel`` sees one player; only songs a request couldn't play from one source bring
/// this in (see ``AppModel/mixedRequest(_:)``).
@MainActor
final class MixedQueueEngine: PlayerEngine {
    /// What a song is to the player it plays on.
    enum Payload {
        case local(LocalTrack)
        case song(Song)
        case history(HistorySong)

        var localTrack: LocalTrack? {
            if case .local(let track) = self { return track }
            return nil
        }
    }

    /// A song in the queue, and where it plays from.
    struct Item {
        let id: String
        let source: MusicSource
        let payload: Payload
        /// How it shows until its player has it.
        let track: PlayerTrack
    }

    var onChange: (() -> Void)?
    var onNotice: ((String) -> Void)?
    var onSkipped: ((_ count: Int, _ reason: String?) -> Void)?
    var onRemoteNext: (() -> Void)?
    var onRemotePrevious: (() -> Void)?
    var onAutoSkip: (() -> Void)?

    /// The player for a source.
    private let engineFor: (MusicSource) -> any PlayerEngine
    /// A request's songs as items, each from its best copy, and where the one asked for is
    /// among them. Empty when none has a copy.
    private let resolve: (PlayRequest) async throws -> (items: [Item], start: Int)
    /// Whether any source can play a live mix's song now.
    private let canPlayAnywhere: (MixSong) -> Bool
    /// Why a run of songs from one source couldn't start, for the skip message.
    private let reasonUnavailable: (MusicSource, [Item]) -> String?

    private var items: [Item] = []
    /// The order before shuffling, to go back to.
    private var unshuffled: [Item] = []
    private var index = 0
    /// The player playing the run of songs it was handed, and their ids, in order.
    private var active: (any PlayerEngine)?
    private var handed: [String] = []
    /// Set while a run is being handed over, so the change looks like one song loading.
    private var isHanding = false
    /// Counts hand-overs, so a slow one can't land over a newer one.
    private var generation = 0
    private var isMeantToPlay = false
    /// The last song of the queue ended and nothing came after it.
    private var ranOut = false
    private var context = PlayContext.songs("")
    private var level: Float = 1

    /// Whether the active player last reported playing, and how far into the run's last song
    /// it had got: an ended song is told from a paused one by where it stopped.
    private var wasPlaying = false
    private var lastTime: TimeInterval = 0
    private var watcher: Task<Void, Never>?
    private var tailTask: Task<Void, Never>?

    private(set) var isShuffled = false
    private(set) var repeatMode: PlayerRepeat = .off

    init(
        engineFor: @escaping (MusicSource) -> any PlayerEngine,
        resolve: @escaping (PlayRequest) async throws -> (items: [Item], start: Int),
        canPlayAnywhere: @escaping (MixSong) -> Bool,
        reasonUnavailable: @escaping (MusicSource, [Item]) -> String?
    ) {
        self.engineFor = engineFor
        self.resolve = resolve
        self.canPlayAnywhere = canPlayAnywhere
        self.reasonUnavailable = reasonUnavailable
    }

    // MARK: - State

    var status: PlayerStatus {
        if isHanding { return .loading }
        guard let active else { return items.isEmpty ? .stopped : .paused }
        if active.current == nil { return items.isEmpty ? .stopped : .paused }
        return active.status
    }

    var current: PlayerTrack? {
        guard items.indices.contains(index) else { return nil }
        let item = items[index]
        guard !isHanding, let playing = active?.current else { return item.track }
        return Self.relabel(playing, as: item.id)
    }

    var upNext: [PlayerTrack] {
        guard items.indices.contains(index) else { return [] }
        let ahead = items[(index + 1)...]
        // The run's own songs as its player has them (their covers resolved), where it has
        // them all; the rest as queued.
        let end = runEnd(from: index)
        let inRun = ahead.prefix(end - index - 1)
        if !isHanding, let theirs = active?.upNext, theirs.count == inRun.count {
            return zip(inRun, theirs).map { Self.relabel($1, as: $0.id) } + ahead.dropFirst(inRun.count).map(\.track)
        }
        return ahead.map(\.track)
    }

    var playbackTime: TimeInterval { isHanding ? 0 : active?.playbackTime ?? 0 }
    var lastKnownPlaybackTime: TimeInterval { isHanding ? 0 : active?.lastKnownPlaybackTime ?? 0 }
    var playsByName: Bool { true }

    /// The source of a song in the queue, by its id there.
    func source(of trackID: String) -> MusicSource? {
        items.first { $0.id == trackID }?.source
    }

    /// The source of the song on.
    var currentSource: MusicSource? {
        items.indices.contains(index) ? items[index].source : nil
    }

    /// Whether the songs ahead come from more than one source, so Up Next names them.
    var mixesSources: Bool {
        Set(items.map(\.source)).count > 1
    }

    /// Whether the player playing sets its own level, as Apple Music's doesn't.
    var setsOwnLevel: Bool { !(active is MusicKitPlayerEngine) }

    func canPlayNow(_ song: MixSong) -> Bool { canPlayAnywhere(song) }

    // MARK: - Taking over

    /// Takes over a player's queue as it is, without stopping the song on: what's added next
    /// can then come from any source.
    func adopt(_ engine: any PlayerEngine, as source: MusicSource, context: PlayContext?) {
        generation += 1
        let tracks = [engine.current].compactMap(\.self) + engine.upNext
        items = tracks.map { Item(id: $0.id, source: source, payload: Self.payload(of: $0), track: $0) }
        unshuffled = items
        index = 0
        active = engine
        handed = items.map(\.id)
        isHanding = false
        ranOut = false
        isMeantToPlay = engine.status == .playing || engine.status == .loading
        isShuffled = false
        repeatMode = engine.repeatMode == .one ? .one : .off
        if let context { self.context = context }
        attach(engine)
        followTime()
    }

    // MARK: - Playing

    func play(_ request: PlayRequest, context: PlayContext, shuffled: Bool) async throws {
        generation += 1
        let asked = generation
        let found = try await resolve(request)
        guard asked == generation else { throw CancellationError() }
        var resolved = found.items
        guard !resolved.isEmpty else { throw PlayerProblem.nothingToPlay }
        var first = min(max(0, found.start), resolved.count - 1)
        unshuffled = resolved
        if shuffled {
            let lead = resolved.remove(at: first)
            resolved = [lead] + FreshShuffle.order(resolved, artist: { StatsCalculator.folded($0.track.artistName) }, seed: .random(in: 0...UInt64.max))
            first = 0
        }
        items = resolved
        isShuffled = shuffled
        self.context = context
        ranOut = false
        try await startRun(at: first, playing: true)
    }

    func enqueue(_ request: PlayRequest, next: Bool) async throws {
        let added = try await resolve(request).items
        guard !added.isEmpty else { throw PlayerProblem.nothingToPlay }
        guard !items.isEmpty else {
            items = added
            unshuffled = added
            ranOut = false
            try await startRun(at: 0, playing: true)
            return
        }
        let position = next ? index + 1 : items.count
        items.insert(contentsOf: added, at: position)
        if next, let at = unshuffled.firstIndex(where: { $0.id == items[index].id }) {
            unshuffled.insert(contentsOf: added, at: at + 1)
        } else {
            unshuffled += added
        }
        // The queue had run out: what's just been added plays.
        if ranOut {
            ranOut = false
            try await startRun(at: position, playing: true)
            return
        }
        await handTail()
        onChange?()
    }

    func pause() {
        isMeantToPlay = false
        active?.pause()
        stopWatching()
    }

    func resume() async throws {
        guard !items.isEmpty else { throw PlayerProblem.nothingToPlay }
        isMeantToPlay = true
        guard !isHanding else { return }
        guard let active, active.current != nil, !ranOut else {
            ranOut = false
            try await startRun(at: index, playing: true)
            return
        }
        active.activate()
        try await active.resume()
        followTime()
    }

    func skipToNext() async throws {
        guard !items.isEmpty else { return }
        if let active, !isHanding, !active.upNext.isEmpty, index + 1 < runEnd(from: index) {
            try await active.skipToNext()
            return
        }
        if let next = nextIndex(after: index) {
            try await startRun(at: next, playing: true)
        } else {
            // Past the end: stop on the last song, back at its start.
            isMeantToPlay = false
            active?.pause()
            active?.seek(to: 0)
            ranOut = true
            onChange?()
        }
    }

    func skipToPrevious() async throws {
        guard !items.isEmpty, let active, !isHanding else { return }
        let runStart = runStart(of: index)
        if playbackTime > 3 || index == 0 || index > runStart {
            try await active.skipToPrevious()
            return
        }
        try await startRun(at: index - 1, playing: true)
    }

    func seek(to time: TimeInterval) {
        guard !isHanding else { return }
        active?.seek(to: time)
        lastTime = time
    }

    func setShuffled(_ shuffled: Bool) {
        guard shuffled != isShuffled, items.indices.contains(index) else {
            isShuffled = shuffled
            return
        }
        let playing = items[index]
        if shuffled {
            unshuffled = items
            let rest = Array(items[(index + 1)...]) + Array(items[..<index])
            items = [playing] + FreshShuffle.order(rest, artist: { StatsCalculator.folded($0.track.artistName) }, seed: .random(in: 0...UInt64.max))
            index = 0
        } else {
            items = unshuffled
            index = items.firstIndex { $0.id == playing.id } ?? 0
        }
        isShuffled = shuffled
        handed = handed.filter { id in items.contains { $0.id == id } }
        Task { await handTail() }
        onChange?()
    }

    func setRepeat(_ mode: PlayerRepeat) {
        repeatMode = mode
        // Repeat One stays with the song's own player; the queue wraps here.
        active?.setRepeat(mode == .one ? .one : .off)
        onChange?()
    }

    func jump(toUpNext offset: Int) async throws {
        let target = index + 1 + offset
        guard items.indices.contains(target) else { return }
        try await startRun(at: target, playing: true)
    }

    func removeUpNext(at offsets: IndexSet) {
        let removed = Set(offsets.compactMap { items.indices.contains(index + 1 + $0) ? items[index + 1 + $0].id : nil })
        guard !removed.isEmpty else { return }
        items.removeAll { removed.contains($0.id) }
        unshuffled.removeAll { removed.contains($0.id) }
        Task { await handTail() }
        onChange?()
    }

    func moveUpNext(from offsets: IndexSet, to destination: Int) {
        guard items.indices.contains(index) else { return }
        var rest = Array(items[(index + 1)...])
        guard let last = offsets.last, last < rest.count, (0...rest.count).contains(destination) else { return }
        rest.move(fromOffsets: offsets, toOffset: destination)
        items = Array(items[...index]) + rest
        if !isShuffled { unshuffled = items }
        Task { await handTail() }
        onChange?()
    }

    /// Puts another copy of a song ahead in its place: the queue checks ahead and finds this
    /// one's source can't play it now.
    func replace(upNext offset: Int, with copy: Item) {
        let position = index + 1 + offset
        guard items.indices.contains(position) else { return }
        let old = items[position].id
        items[position] = copy
        if let kept = unshuffled.firstIndex(where: { $0.id == old }) { unshuffled[kept] = copy }
        Task { await handTail() }
        onChange?()
    }

    /// Plays the song on from another copy, from its start; the rest of the queue stays. It
    /// keeps its place and id in the queue, so it counts as the same song going on.
    func replaceCurrent(with copy: Item, playing: Bool) async throws {
        guard items.indices.contains(index) else { return }
        let id = items[index].id
        let kept = Item(id: id, source: copy.source, payload: copy.payload, track: Self.relabel(copy.track, as: id))
        items[index] = kept
        if let place = unshuffled.firstIndex(where: { $0.id == id }) { unshuffled[place] = kept }
        try await startRun(at: index, playing: playing)
    }

    /// The item behind a song ahead, for finding another copy of it.
    func item(upNext offset: Int) -> Item? {
        let position = index + 1 + offset
        return items.indices.contains(position) ? items[position] : nil
    }

    func setLevel(_ level: Float) {
        self.level = level
        active?.setLevel(level)
    }

    func activate() {
        active?.activate()
    }

    /// Stops and lets go of everything, for another player to take over.
    func deactivate() {
        generation += 1
        stopWatching()
        if let active { detach(active, stopping: true) }
        active = nil
        items = []
        unshuffled = []
        handed = []
        index = 0
        isHanding = false
        isMeantToPlay = false
        ranOut = false
    }

    // MARK: - Runs

    /// Where the run of songs from one source that starts at `position` ends.
    private func runEnd(from position: Int) -> Int {
        guard items.indices.contains(position) else { return position }
        let source = items[position].source
        var end = position + 1
        while end < items.count, items[end].source == source { end += 1 }
        return end
    }

    /// Where the run of songs `position` is in starts, as it was handed to its player.
    private func runStart(of position: Int) -> Int {
        guard items.indices.contains(position), let first = handed.first,
              let start = items.firstIndex(where: { $0.id == first }), start <= position
        else { return position }
        return start
    }

    private func nextIndex(after position: Int) -> Int? {
        if position + 1 < items.count { return position + 1 }
        return repeatMode == .all && !items.isEmpty ? 0 : nil
    }

    /// Hands the run of songs from `position` to its source's player and plays it. A run that
    /// can't start is left out, with a quiet word, and the next run is tried.
    private func startRun(at position: Int, playing: Bool) async throws {
        generation += 1
        let asked = generation
        var position = position
        var skipped: [Item] = []
        var reasons: Set<String> = []
        defer {
            if !skipped.isEmpty {
                onSkipped?(skipped.count, reasons.count == 1 ? reasons.first : nil)
            }
        }
        while items.indices.contains(position) {
            let end = runEnd(from: position)
            let run = Array(items[position..<end])
            let source = run[0].source
            let incoming = engineFor(source)
            index = position
            isHanding = true
            isMeantToPlay = playing
            ranOut = false
            onChange?()
            // The player leaving lets go first: its Now Playing, remote controls and what it
            // told capture, so the one coming in owns them all.
            if let active, active !== incoming {
                detach(active, stopping: true)
            } else if let active {
                detach(active, stopping: false)
            }
            active = incoming
            handed = run.map(\.id)
            incoming.setLevel(level)
            incoming.setRepeat(repeatMode == .one ? .one : .off)
            do {
                try await incoming.play(Self.request(for: run), context: context, shuffled: false)
                guard asked == generation else { return }
                attach(incoming)
                isHanding = false
                if !playing { incoming.pause() }
                lastTime = 0
                wasPlaying = false
                followTime()
                onChange?()
                return
            } catch {
                guard asked == generation else { return }
                skipped += run
                if let reason = reasonUnavailable(source, run) { reasons.insert(reason) }
                onAutoSkip?()
                position = end
            }
        }
        // Nothing from here on could start.
        guard asked == generation else { return }
        isHanding = false
        isMeantToPlay = false
        index = max(0, min(index, items.count - 1))
        if let active { detach(active, stopping: true) }
        active = nil
        ranOut = true
        onChange?()
        if skipped.isEmpty { throw PlayerProblem.nothingToPlay }
    }

    /// Gives the active player what now follows its song in the same source, where that has
    /// changed: songs added, removed, moved or shuffled.
    /// One at a time: a second edit made while the first is still being handed over waits,
    /// then looks again, so no song is handed over twice.
    private func handTail() async {
        let previous = tailTask
        let task = Task { [weak self] in
            await previous?.value
            await self?.handTailNow()
        }
        tailTask = task
        await task.value
        if tailTask == task { tailTask = nil }
    }

    private func handTailNow() async {
        guard let active, !isHanding, items.indices.contains(index) else { return }
        let end = runEnd(from: index)
        let wanted = items[(index + 1)..<end].map(\.id)
        let current = items[index].id
        let had = handed.firstIndex(of: current).map { Array(handed[($0 + 1)...]) } ?? []
        guard wanted != had else { return }
        let asked = generation
        handed = Array(handed.prefix { $0 != current }) + [current] + wanted
        let theirs = active.upNext.count
        if theirs > 0 { active.removeUpNext(at: IndexSet(0..<theirs)) }
        guard !wanted.isEmpty, asked == generation else { return }
        let tail = items[(index + 1)..<end]
        try? await active.enqueue(Self.request(for: Array(tail)), next: false)
    }

    // MARK: - Following the active player

    private func attach(_ engine: any PlayerEngine) {
        engine.onChange = { [weak self] in self?.activeChanged() }
        if let local = engine as? LocalPlayerEngine {
            local.onNotice = { [weak self] in self?.onNotice?($0) }
            local.onSkipped = { [weak self] in self?.onSkipped?($0, $1) }
            local.onRemoteNext = { [weak self] in self?.onRemoteNext?() }
            local.onRemotePrevious = { [weak self] in self?.onRemotePrevious?() }
            local.onAutoSkip = { [weak self] in self?.onAutoSkip?() }
            local.onRanOut = { [weak self] in self?.runEnded() }
        }
        #if SPOTIFY
        if let spotify = engine as? SpotifyPlayerEngine {
            spotify.onNotice = { [weak self] in self?.onNotice?($0) }
            spotify.onRemoteNext = { [weak self] in self?.onRemoteNext?() }
            spotify.onRemotePrevious = { [weak self] in self?.onRemotePrevious?() }
            spotify.onAutoSkip = { [weak self] in self?.onAutoSkip?() }
            spotify.onRanOut = { [weak self] in self?.runEnded() }
        }
        #endif
    }

    /// Stops following a player, and with `stopping`, stops it and has it let go.
    private func detach(_ engine: any PlayerEngine, stopping: Bool) {
        engine.onChange = nil
        if let local = engine as? LocalPlayerEngine { local.onRanOut = nil }
        #if SPOTIFY
        if let spotify = engine as? SpotifyPlayerEngine { spotify.onRanOut = nil }
        #endif
        guard stopping else { return }
        engine.pause()
        engine.deactivate()
    }

    private func activeChanged() {
        guard let active, !isHanding else { return }
        // Moved within its run by itself, on or back: follow it to the song now on.
        if let playing = active.current, items.indices.contains(index) {
            let start = runStart(of: index)
            let found = QueueRunPosition.follow(
                identities: items.map(\.track.songIdentity),
                run: start..<runEnd(from: index),
                current: index,
                playing: playing.songIdentity,
                left: active.upNext.count
            )
            if found != index {
                index = found
                lastTime = 0
            }
        }
        let playing = active.status == .playing
        // Played again from the lock screen or headphones, which reach its player directly.
        if playing { isMeantToPlay = true }
        // Apple Music's player has no word for a queue ending: it stops on the last song,
        // at or near its end.
        if wasPlaying, !playing, active is MusicKitPlayerEngine, isMeantToPlay, active.upNext.isEmpty,
           index == runEnd(from: index) - 1 {
            let duration = active.current?.duration ?? items[index].track.duration
            if active.current == nil || duration.map({ lastTime >= $0 - 3 }) == true {
                runEnded()
                return
            }
        }
        wasPlaying = playing
        followTime()
        onChange?()
    }

    /// The active player played its last song through: on to the next run, or the start of
    /// the queue on Repeat.
    private func runEnded() {
        guard !isHanding else { return }
        let end = runEnd(from: index)
        let next = end < items.count ? end : (repeatMode == .all ? 0 : nil)
        guard let next, isMeantToPlay || active is MusicKitPlayerEngine else {
            ranOut = true
            wasPlaying = false
            stopWatching()
            onChange?()
            return
        }
        let musicKitEnded = active is MusicKitPlayerEngine
        let asked = generation
        // Paused in the moment before the next run starts: it's made ready, paused.
        Task { [weak self] in
            guard let self, asked == self.generation else { return }
            try? await self.startRun(at: next, playing: self.isMeantToPlay || musicKitEnded)
        }
    }

    /// Samples the position once a second while a run's last song plays, which is when the
    /// end of a run has to be told from a pause.
    private func followTime() {
        guard let active, active.status == .playing, items.indices.contains(index),
              index == runEnd(from: index) - 1
        else {
            stopWatching()
            return
        }
        lastTime = max(lastTime, active.playbackTime)
        guard watcher == nil else { return }
        watcher = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, let active = self.active, active.status == .playing else { break }
                self.lastTime = active.playbackTime
            }
            self?.watcher = nil
        }
    }

    private func stopWatching() {
        watcher?.cancel()
        watcher = nil
    }

    // MARK: - Requests

    /// A run's songs, as its player takes them.
    private static func request(for run: [Item]) -> PlayRequest {
        var locals: [LocalTrack] = []
        var songs: [Song] = []
        var history: [HistorySong] = []
        for item in run {
            switch item.payload {
            case .local(let track): locals.append(track)
            case .song(let song):
                songs.append(song)
                history.append(HistorySong(songID: song.id.rawValue, title: song.title, artistName: song.artistName, albumTitle: song.albumTitle))
            case .history(let song): history.append(song)
            }
        }
        if !locals.isEmpty, locals.count == run.count { return .local(locals) }
        if !songs.isEmpty, songs.count == run.count { return .songs(songs) }
        return .history(history)
    }

    private static func payload(of track: PlayerTrack) -> Payload {
        if let local = track.local { return .local(local) }
        if let song = track.song { return .song(song) }
        return .history(HistorySong(songID: "", title: track.title, artistName: track.artistName, albumTitle: track.albumTitle))
    }

    /// A player's song shown under the queue's own id for it, so the song on keeps one id as
    /// its player takes it over.
    private static func relabel(_ track: PlayerTrack, as id: String) -> PlayerTrack {
        guard track.id != id else { return track }
        return PlayerTrack(
            id: id,
            title: track.title,
            artistName: track.artistName,
            albumTitle: track.albumTitle,
            cover: track.cover,
            duration: track.duration,
            isExplicit: track.isExplicit,
            song: track.song,
            local: track.local
        )
    }
}

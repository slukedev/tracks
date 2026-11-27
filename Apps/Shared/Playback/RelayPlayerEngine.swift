import Foundation
import AVFoundation
import MediaPlayer
import Network
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import TracksCore

/// Plays a queue with a music service's tracks in it through the server's relay: the server
/// plays it as this device's own player and serves what it hears as HLS live, which AVPlayer
/// plays. Every control goes to the relay's speaker, and shows here at once: the stream
/// catches up about four seconds later.
///
/// Each seek, skip, resume and tier change starts a new generation with a playlist of its
/// own, and the player moves to it (see ``RelayStreamFollower``) rather than playing out what
/// it buffered. Until the new audio arrives the old is silent, so a skip never plays a moment
/// of the song being left. A song change the stream brings by itself shows when it's heard,
/// not when the server sends it.
///
/// The lock screen, Control Center, headphones and AirPlay follow it as they follow any of
/// Tracks' players, and capture hears each song as it's audible, under its service's name.
@MainActor
final class RelayPlayerEngine: NSObject, PlayerEngine {
    var onChange: (() -> Void)?
    /// A moment's message for the person, shown by the player.
    var onNotice: ((String) -> Void)?
    /// Next and Previous from the lock screen, CarPlay or headphones, set by the player.
    var onRemoteNext: (() -> Void)?
    var onRemotePrevious: (() -> Void)?

    private(set) var status: PlayerStatus = .stopped
    private(set) var isShuffled = false
    private(set) var repeatMode: PlayerRepeat = .off

    private struct Entry {
        let id: String
        var relay: RelayEntry
        /// The server's id for it in the queue, once the queue has been read.
        var itemID: String?
    }

    private let music: YourMusic
    private let capture: PushedNowPlayingSource
    private var player = AVPlayer()
    private let pinnedTrust = PinnedMediaLoader()

    /// The server whose relay this is, while there's a queue.
    private(set) var serverID: String?
    private var session: TracksideRelaySession?
    private var follower = RelayStreamFollower()
    private var entries: [Entry] = []
    private var index = 0
    private var context: TracksideSpeaker.Context?

    /// What the relay's speaker plays as this device hears it, and the newer state the
    /// server has sent that isn't audible yet.
    private var heard: TracksideSpeaker?
    private var pending: TracksideSpeaker?
    /// A position asked for here, shown until the stream has caught up with it.
    private var shown: (time: TimeInterval, at: Date, until: Date)?
    /// Silent from a skip or seek until the new generation's audio plays, and since when.
    private var isDucked = false
    private var duckedAt = Date.distantPast
    private var level: Float = 1

    private var isMeantToPlay = false
    private var isActive = false
    /// Counts starts, so a slow one can't land over a newer one.
    private var starts = 0
    private var recoveries: (generation: Int, tries: Int) = (0, 0)
    private var recovering: Task<Void, Never>?
    private var queueRead: Task<Void, Never>?
    private var tierChange: Task<Void, Never>?
    private var artworkTask: Task<Void, Never>?
    private var currentPlay: (entryID: String, id: String)?

    private var observations: [NSKeyValueObservation] = []
    private var itemObservation: NSKeyValueObservation?
    private var notifications: [any NSObjectProtocol] = []
    private var timeObserver: Any?
    private let pathMonitor = NWPathMonitor()
    /// The network as last seen: whether it costs, or is held back.
    private var path: (expensive: Bool, constrained: Bool, cellular: Bool) = (false, false, false)

    init(music: YourMusic, capture: PushedNowPlayingSource) {
        self.music = music
        self.capture = capture
        super.init()
        follow(player)
        let center = NotificationCenter.default
        notifications.append(center.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: nil) { [weak self] note in
            let item = (note.object as AnyObject?).map(ObjectIdentifier.init)
            Task { @MainActor in self?.itemEnded(item) }
        })
        notifications.append(center.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: nil, queue: nil) { [weak self] note in
            let item = (note.object as AnyObject?).map(ObjectIdentifier.init)
            Task { @MainActor in self?.itemFailed(item) }
        })
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
        notifications.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) { [weak self] _ in
            // Events stop in the background: what changed meanwhile is read on return.
            Task { @MainActor in self?.catchUp() }
        })
        #endif
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let seen = (expensive: path.isExpensive, constrained: path.isConstrained, cellular: path.usesInterfaceType(.cellular))
            Task { @MainActor in self?.networkChanged(seen) }
        }
        pathMonitor.start(queue: DispatchQueue(label: "com.luke.tracks.relay-network"))
    }

    private func follow(_ player: AVPlayer) {
        player.automaticallyWaitsToMinimizeStalling = true
        player.allowsExternalPlayback = true
        // KVO calls back on whichever thread made the change.
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Self.onMain { self?.playerChanged() }
        })
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 10), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    // MARK: - What's on

    var current: PlayerTrack? {
        if let playing = heard?.nowPlaying, !entries.indices.contains(index) || !entries[index].relay.isSame(as: playing) {
            // Something the queue here doesn't know yet: as the server names it.
            return track(Entry(id: "relay.\(playing.itemID ?? playing.title)", relay: RelayEntry(playing, local: localTrack(playing.songID)), itemID: playing.itemID))
        }
        return entries.indices.contains(index) ? track(entries[index]) : nil
    }

    var upNext: [PlayerTrack] {
        guard entries.indices.contains(index) else { return [] }
        return entries[(index + 1)...].map(track)
    }

    var playbackTime: TimeInterval {
        let now = Date.now
        if let shown, now < shown.until {
            return shown.time + (isMeantToPlay && status == .playing ? max(0, now.timeIntervalSince(shown.at)) : 0)
        }
        guard let heard, let serverID else { return shown?.time ?? 0 }
        return heard.position(at: audibleNow(on: serverID)) ?? 0
    }

    var playsByName: Bool { true }

    /// The song on, as the relay's queue knows it: for sending it on to a speaker.
    var speakerID: String? { session?.hasEnded == false ? session?.speakerID : nil }

    /// Whether a request is one this player takes: a relay queue, or library songs of the
    /// server whose relay is playing.
    func takes(_ request: PlayRequest) -> Bool {
        switch request {
        case .relay(let queue): queue.serverID == serverID || serverID == nil
        case .local(let tracks, _): serverID.map { id in !tracks.isEmpty && tracks.allSatisfy { RelayEntry($0, on: id) != nil } } ?? false
        default: false
        }
    }

    private func track(_ entry: Entry) -> PlayerTrack {
        let relay = entry.relay
        let artwork: String? = if let local = relay.local {
            music.artworkURL(local.artwork)?.absoluteString
        } else {
            artworkURL(relay.artworkID)?.absoluteString
        }
        return PlayerTrack(
            id: entry.id,
            title: relay.title,
            artistName: relay.artist,
            albumTitle: relay.album,
            cover: .url(artwork, seed: relay.album ?? relay.title),
            duration: relay.duration,
            isExplicit: relay.isExplicit,
            song: nil,
            local: relay.local
        )
    }

    private func artworkURL(_ id: String?) -> URL? {
        guard let id, let serverID else { return nil }
        return music.servers.client(for: serverID)?.artworkURL(id: id, size: 600)
    }

    private func localTrack(_ songID: String?) -> LocalTrack? {
        guard let songID, let serverID else { return nil }
        return music.index.track(id: LocalTrack.id(for: .server(serverID: serverID, songID: songID)))
    }

    private var client: TracksideClient? { serverID.flatMap { music.servers.tracksideClient(for: $0) } }

    // MARK: - Playing

    func play(_ request: PlayRequest, context: PlayContext, shuffled: Bool) async throws {
        guard var queue = relayQueue(for: request) else { throw PlayerProblem.nothingToPlay }
        if queue.context == nil { queue.context = Self.speakerContext(context) }
        if shuffled, !queue.entries.isEmpty {
            var rest = queue.entries
            let lead = rest.remove(at: queue.start)
            queue.entries = [lead] + FreshShuffle.order(rest, artist: { StatsCalculator.folded($0.artist) }, seed: .random(in: 0...UInt64.max))
            queue.start = 0
        }
        isShuffled = shuffled
        try await start(queue.trimmed)
    }

    /// Takes over a relay the server already made for this device, as a speaker's music
    /// moves here: the queue is read from the server.
    func takeOver(_ session: TracksideRelaySession, on serverID: String, showing speaker: TracksideSpeaker?) {
        starts += 1
        if self.serverID != serverID { follower = RelayStreamFollower() }
        self.serverID = serverID
        entries = speaker?.nowPlaying.map { [Entry(id: UUID().uuidString, relay: RelayEntry($0, local: localTrack($0.songID)), itemID: $0.itemID)] } ?? []
        index = 0
        heard = speaker
        pending = nil
        isShuffled = speaker?.playback?.shuffle ?? false
        repeatMode = Self.repeatMode(speaker?.playback?.repeatMode)
        isMeantToPlay = true
        status = .loading
        activate()
        adopt(session)
        readQueue()
        onChange?()
        publish()
    }

    private func relayQueue(for request: PlayRequest) -> RelayQueue? {
        switch request {
        case .relay(let queue):
            return queue.entries.isEmpty ? nil : queue
        case .local(let tracks, let start):
            guard let serverID else { return nil }
            let entries = tracks.compactMap { RelayEntry($0, on: serverID) }
            return entries.isEmpty ? nil : RelayQueue(serverID: serverID, entries: entries, start: start)
        default:
            return nil
        }
    }

    /// Plays a queue: on the relay this device has, or a new one, in one round trip.
    private func start(_ queue: RelayQueue) async throws {
        starts += 1
        let asked = starts
        guard let client = music.servers.tracksideClient(for: queue.serverID) else {
            throw PlayerProblem.failed(String(localized: "Couldn't reach your server."))
        }
        if serverID != queue.serverID {
            session = nil
            follower = RelayStreamFollower()
        }
        serverID = queue.serverID
        context = queue.context
        entries = queue.entries.map { Entry(id: UUID().uuidString, relay: $0) }
        index = queue.start
        heard = nil
        pending = nil
        shown = (queue.position, Date.now, Date.now.addingTimeInterval(Self.catchUp))
        isMeantToPlay = true
        status = .loading
        duck()
        activate()
        onChange?()
        publish()
        updateNowPlayingInfo()
        let request = playRequest(queue.entries, start: queue.start, position: queue.position)
        do {
            if let session, !session.hasEnded {
                let command = try await client.play(on: session.speakerID, request, key: UUID().uuidString)
                guard asked == starts else { throw CancellationError() }
                accept(command)
            } else {
                let started = try await startRelay(client, play: request)
                guard asked == starts else { throw CancellationError() }
                adopt(started)
            }
            // The speaker as it plays it, and the queue as the services expanded it.
            catchUp()
        } catch TracksideError.conflict(let reason) where reason == "relay_ended" {
            // Ended under us: once more, on a new one.
            session = nil
            let started = try await startRelay(client, play: request)
            guard asked == starts else { throw CancellationError() }
            adopt(started)
            catchUp()
        } catch {
            guard asked == starts else { throw CancellationError() }
            isMeantToPlay = false
            unduck()
            status = .paused
            onChange?()
            publish()
            throw PlayerProblem.failed(Self.words(for: error), title: Self.title(for: error))
        }
    }

    /// Starts the relay at this network's tier, or lossless where the server can't make the
    /// smaller one.
    private func startRelay(_ client: TracksideClient, play: TracksideSpeakerPlayRequest?) async throws -> TracksideRelaySession {
        let tier = wantedTier
        do {
            return try await client.startRelay(TracksideRelayStart(play: play, name: TracksideDeviceInfo.thisDevice.name, tier: tier), key: UUID().uuidString)
        } catch TracksideError.unsupported(let capability) where capability == "relay_cellular" && tier == .cellular {
            return try await client.startRelay(TracksideRelayStart(play: play, name: TracksideDeviceInfo.thisDevice.name, tier: .lossless), key: UUID().uuidString)
        }
    }

    private func playRequest(_ queue: [RelayEntry], start: Int, position: TimeInterval) -> TracksideSpeakerPlayRequest {
        TracksideSpeakerPlayRequest(items: queue.map(\.item), mode: .replace, startIndex: start, position: position, shuffle: false, repeatMode: Self.wire(repeatMode), context: context)
    }

    func enqueue(_ request: PlayRequest, next: Bool) async throws {
        guard let queue = relayQueue(for: request) else {
            throw PlayerProblem.failed(String(localized: "Only your server's songs and its music services' can join this queue."), title: String(localized: "Can't Add These Songs"))
        }
        guard let client, let session, !session.hasEnded, !entries.isEmpty else {
            try await start(queue)
            return
        }
        let added = queue.entries.map { Entry(id: UUID().uuidString, relay: $0) }
        let position = next ? min(index + 1, entries.count) : entries.count
        entries.insert(contentsOf: added, at: position)
        onChange?()
        do {
            let command = try await client.play(on: session.speakerID, TracksideSpeakerPlayRequest(items: queue.entries.map(\.item), mode: next ? .next : .add), key: UUID().uuidString)
            accept(command)
            readQueue()
        } catch {
            entries.removeAll { entry in added.contains { $0.id == entry.id } }
            onChange?()
            throw PlayerProblem.failed(Self.words(for: error), title: Self.title(for: error))
        }
    }

    func pause() {
        isMeantToPlay = false
        player.pause()
        follower.paused()
        shown = (playbackTime, Date.now, Date.distantFuture)
        status = entries.isEmpty ? .stopped : .paused
        onChange?()
        publish()
        updateNowPlayingInfo()
        send(.pause)
    }

    func resume() async throws {
        guard entries.indices.contains(index) else { throw PlayerProblem.nothingToPlay }
        let position = playbackTime
        guard let session, !session.hasEnded else {
            // Ended while paused, by the server's idle rules or a restart: a new relay picks
            // the queue up where it was.
            let rest = Array(entries[index...].map(\.relay))
            try await start(RelayQueue(serverID: serverID ?? "", entries: rest, start: 0, position: position, context: context))
            return
        }
        isMeantToPlay = true
        status = .loading
        shown = (position, Date.now, Date.now.addingTimeInterval(Self.catchUp))
        activate()
        onChange?()
        if case .open(let path) = follower.resume() { open(path) }
        player.play()
        send(.play)
    }

    func skipToNext() async throws {
        guard !entries.isEmpty else { return }
        if index + 1 < entries.count { index += 1 }
        moved()
        send(.next)
    }

    func skipToPrevious() async throws {
        guard !entries.isEmpty else { return }
        if playbackTime > 3 {
            seek(to: 0)
            return
        }
        if index > 0 { index -= 1 }
        moved()
        send(.previous)
    }

    func seek(to time: TimeInterval) {
        guard !entries.isEmpty else { return }
        shown = (max(0, time), Date.now, Date.now.addingTimeInterval(Self.catchUp))
        if isMeantToPlay { duck() }
        onChange?()
        updateNowPlayingInfo()
        send(.seek(time))
    }

    func setShuffled(_ isShuffled: Bool) {
        guard isShuffled != self.isShuffled else { return }
        self.isShuffled = isShuffled
        onChange?()
        change(TracksideSpeakerChange(shuffle: isShuffled))
    }

    func setRepeat(_ mode: PlayerRepeat) {
        repeatMode = mode
        onChange?()
        change(TracksideSpeakerChange(repeatMode: Self.wire(mode)))
    }

    func jump(toUpNext offset: Int) async throws {
        let target = index + 1 + offset
        guard entries.indices.contains(target) else { return }
        if let itemID = entries[target].itemID {
            index = target
            moved()
            send(.jump(itemID: itemID))
        } else {
            // Not read from the server yet: the rest of the queue again, from there.
            let rest = entries[target...].map(\.relay)
            try await start(RelayQueue(serverID: serverID ?? "", entries: Array(rest), context: context))
        }
    }

    /// The server's queue has no way to take songs out or move them, so Up Next keeps them.
    func removeUpNext(at offsets: IndexSet) {
        onNotice?(String(localized: "This Queue Can't Be Changed"))
        onChange?()
    }

    func moveUpNext(from offsets: IndexSet, to destination: Int) {
        onNotice?(String(localized: "This Queue Can't Be Changed"))
        onChange?()
    }

    func setLevel(_ level: Float) {
        self.level = level
        if !isDucked { player.volume = level }
    }

    /// A skip or a jump, shown at once: the song it lands on, from its start.
    private func moved() {
        heard = nil
        pending = nil
        shown = (0, Date.now, Date.now.addingTimeInterval(Self.catchUp))
        if isMeantToPlay {
            duck()
            status = .loading
        }
        onChange?()
        publish()
        updateNowPlayingInfo()
    }

    // MARK: - Commands

    /// Sends a control to the relay's speaker, once more with a key after a dropped
    /// connection, and takes the session it hands back.
    private func send(_ action: TracksideSpeakerAction) {
        guard let client, let speakerID = speakerID else { return }
        let asked = starts
        Task { [weak self] in
            do {
                let command: TracksideSpeakerCommand
                do {
                    command = try await client.control(speaker: speakerID, action)
                } catch let error as URLError where error.code == .networkConnectionLost || error.code == .timedOut {
                    command = try await client.control(speaker: speakerID, action, retryKey: UUID().uuidString)
                }
                guard let self, asked == self.starts else { return }
                self.accept(command)
            } catch {
                guard let self, asked == self.starts else { return }
                self.commandFailed(error)
            }
        }
    }

    private func change(_ change: TracksideSpeakerChange) {
        guard let client, let speakerID = speakerID else { return }
        let asked = starts
        Task { [weak self] in
            do {
                let command = try await client.change(speaker: speakerID, change)
                guard let self, asked == self.starts else { return }
                self.accept(command)
            } catch {
                guard let self, asked == self.starts else { return }
                self.commandFailed(error)
            }
        }
    }

    /// The server's expectation of the speaker, and the session, from a command's answer.
    private func accept(_ command: TracksideSpeakerCommand) {
        if let relay = command.relay { adopt(relay) }
        hear(command.speaker, isExpected: true)
    }

    private func commandFailed(_ error: any Error) {
        if case TracksideError.conflict(let reason) = error, reason == "relay_ended" {
            sessionEnded(.unknown)
            return
        }
        onNotice?(Self.words(for: error))
        refresh()
    }

    // MARK: - The stream

    /// Takes the session as an answer or event gave it, moving the player to a new
    /// generation's playlist when there is one.
    private func adopt(_ session: TracksideRelaySession) {
        guard self.session == nil || self.session?.id == session.id || !session.hasEnded else { return }
        self.session = session
        switch follower.take(session, wantsToPlay: isMeantToPlay) {
        case .stay:
            break
        case .open(let path):
            open(path)
        case .end(let reason):
            sessionEnded(reason)
        }
    }

    /// Moves the player to a playlist, from its first segment.
    private func open(_ path: String) {
        guard let client, let url = client.mediaURL(path) else { return }
        let asset = AVURLAsset(url: url)
        if TracksideMediaTrust.shared.isPinned(url) {
            asset.resourceLoader.setDelegate(pinnedTrust, queue: PinnedMediaLoader.queue)
        }
        let item = AVPlayerItem(asset: asset)
        // Live and real time: the server holds a few seconds, and so does the player.
        item.preferredForwardBufferDuration = 4
        item.automaticallyPreservesTimeOffsetFromLive = false
        let id = ObjectIdentifier(item)
        itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            Self.onMain { self?.itemFailed(id) }
        }
        player.replaceCurrentItem(with: item)
        if isMeantToPlay { player.play() }
    }

    private func itemEnded(_ item: ObjectIdentifier?) {
        guard let item, let current = player.currentItem, ObjectIdentifier(current) == item else { return }
        // A generation ended while meant to play: the next is named by now, or soon.
        if isMeantToPlay { recover(after: .milliseconds(300)) }
    }

    private func itemFailed(_ item: ObjectIdentifier?) {
        guard let item, let current = player.currentItem, ObjectIdentifier(current) == item else { return }
        recover(after: .milliseconds(500))
    }

    /// The playlist failed or ran out, gone from the server, or the network moved: the
    /// session is read again and the newest playlist opened, a few times at most for one
    /// generation.
    private func recover(after delay: Duration) {
        guard recovering == nil, let session else { return }
        let generation = follower.generation
        let tries = recoveries.generation == generation ? recoveries.tries : 0
        guard tries < 4 else {
            isMeantToPlay = false
            status = .paused
            onNotice?(String(localized: "Couldn't Keep Playing"))
            onChange?()
            publish()
            return
        }
        recoveries = (generation, tries + 1)
        recovering = Task { [weak self] in
            try? await Task.sleep(for: delay * (tries + 1))
            guard let self, !Task.isCancelled else { return }
            defer { self.recovering = nil }
            do {
                guard let client = self.client else { return }
                let fresh = try await client.relaySession(id: session.id)
                guard self.session?.id == session.id else { return }
                let before = self.follower.opened
                self.adopt(fresh)
                if self.isMeantToPlay, !fresh.hasEnded, self.follower.opened == before, case .open(let path) = self.follower.reopen() {
                    self.open(path)
                }
            } catch TracksideError.notFound {
                self.sessionEnded(.unknown)
            } catch {
                // Out of reach for now: tried again from the next failure or the next return.
            }
        }
    }

    /// The server ended the relay: idle, restarted, revoked or the services gone. The queue
    /// stays, paused, and plays again on a new relay from where it was.
    private func sessionEnded(_ reason: TracksideRelaySession.EndedReason) {
        let position = playbackTime
        let wasPlaying = isMeantToPlay
        session = nil
        follower = RelayStreamFollower()
        player.replaceCurrentItem(with: nil)
        isMeantToPlay = false
        unduck()
        shown = (position, Date.now, Date.distantFuture)
        status = entries.isEmpty ? .stopped : .paused
        if wasPlaying {
            switch reason {
            case .assistantUnavailable: onNotice?(String(localized: "Music services aren't available right now."))
            case .revoked: onNotice?(String(localized: "This device was signed out of your server."))
            case .idle, .closed, .replaced, .serverRestart, .failed, .unknown: onNotice?(String(localized: "Paused"))
            }
        }
        onChange?()
        publish()
        updateNowPlayingInfo()
    }

    // MARK: - The relay's speaker

    /// A server event: the relay's own are followed; the rest are someone else's.
    func handle(_ event: TracksideEvent, from serverID: String) {
        guard serverID == self.serverID else { return }
        switch event {
        case .relay(let session):
            guard self.session == nil || session.id == self.session?.id else { return }
            adopt(session)
        case .speakerChanged(let speaker):
            guard speaker.id == self.session?.speakerID else { return }
            hear(speaker, isExpected: false)
        case .speakerProgress(let progress):
            guard progress.id == self.session?.speakerID, var target = pending ?? heard, var playback = target.playback else { return }
            playback.positionMS = progress.positionMS
            playback.positionAt = progress.positionAt
            playback.state = progress.state
            target.playback = playback
            if pending != nil { pending = target } else { heard = target }
        case .speakerQueue(let id, _):
            guard id == self.session?.speakerID else { return }
            readQueue()
        case .hello:
            catchUp()
        default:
            break
        }
    }

    /// This device's server is gone: removed, paired again or its key refused.
    func serverLost(_ serverID: String) {
        guard serverID == self.serverID else { return }
        sessionEnded(.revoked)
        entries = []
        index = 0
        heard = nil
        self.serverID = nil
        status = .stopped
        onChange?()
        publish()
    }

    /// What the speaker does now. A change the server sent by itself, the next song as the
    /// last one ended, is shown once it's heard here; what a command here expected, at once.
    private func hear(_ speaker: TracksideSpeaker, isExpected: Bool) {
        let changesSong = speaker.nowPlaying?.itemID != (heard?.nowPlaying?.itemID ?? speaker.nowPlaying?.itemID)
        if !isExpected, changesSong, heard != nil, let changedAt = speaker.playback?.positionAt, let serverID,
           !RelayAudibility.isAudible(changedAt, audibleNow: audibleNow(on: serverID)) {
            pending = speaker
            return
        }
        pending = nil
        apply(speaker)
    }

    private func apply(_ speaker: TracksideSpeaker) {
        heard = speaker
        if let playing = speaker.nowPlaying, let found = entries.firstIndex(where: { $0.itemID == playing.itemID && playing.itemID != nil }) ?? entries.indices.first(where: { $0 >= index && entries[$0].relay.isSame(as: playing) }) {
            if found != index { shown = nil }
            index = found
        }
        if let shuffle = speaker.playback?.shuffle { isShuffled = shuffle }
        if let mode = speaker.playback?.repeatMode { repeatMode = Self.repeatMode(mode) }
        onChange?()
        publish()
        updateNowPlayingInfo()
    }

    /// Reads the server's queue, from the song on, and takes it as the queue here: its ids
    /// for jumping, and what the services expanded albums and playlists into.
    private func readQueue() {
        guard let client, let speakerID = speakerID, let serverID else { return }
        queueRead?.cancel()
        queueRead = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let queue = try? await client.speakerQueue(id: speakerID, cursor: nil, limit: 500) else { return }
            guard let self, !Task.isCancelled, self.serverID == serverID else { return }
            self.take(queue)
        }
    }

    private func take(_ queue: TracksideSpeakerQueue) {
        guard !queue.items.isEmpty else { return }
        let start = queue.currentIndex.flatMap { current in queue.items.firstIndex { $0.index == current } } ?? 0
        var unclaimed = entries
        var taken: [Entry] = []
        for item in queue.items[start...] {
            // The same song as one here keeps that one's id, so nothing reads as a new song.
            let id: String
            if let match = unclaimed.firstIndex(where: { $0.itemID == item.itemID || ($0.itemID == nil && $0.relay.isSame(as: item)) }) {
                id = unclaimed[match].id
                unclaimed.remove(at: match)
            } else {
                id = "relay.\(item.itemID)"
            }
            taken.append(Entry(id: id, relay: RelayEntry(item, local: localTrack(item.songID)), itemID: item.itemID))
        }
        entries = taken
        index = 0
        if let shuffle = queue.shuffle { isShuffled = shuffle }
        if let mode = queue.repeatMode { repeatMode = Self.repeatMode(mode) }
        onChange?()
        publish()
    }

    /// Back in front, or reconnected: the session, the speaker and the queue as they are.
    private func catchUp() {
        guard let client, let session else { return }
        let speakerID = session.speakerID
        Task { [weak self] in
            do {
                let fresh = try await client.relaySession(id: session.id)
                self?.adopt(fresh)
            } catch TracksideError.notFound {
                self?.sessionEnded(.unknown)
                return
            } catch {
                return
            }
            if let speaker = try? await client.speaker(id: speakerID) { self?.hear(speaker, isExpected: true) }
            self?.readQueue()
        }
    }

    private func refresh() {
        catchUp()
    }

    // MARK: - Time

    /// The server's time at what's audible here.
    private func audibleNow(on serverID: String) -> Date {
        let serverNow = music.servers.speakers.serverNow(serverID)
        return RelayAudibility.audibleNow(programDate: player.currentItem?.currentDate(), serverNow: serverNow, latency: session?.latency ?? 4)
    }

    private func tick() {
        guard let serverID else {
            if isDucked { unduck() }
            return
        }
        if let pending, let changedAt = pending.playback?.positionAt,
           RelayAudibility.isAudible(changedAt, audibleNow: audibleNow(on: serverID)) {
            self.pending = nil
            apply(pending)
        }
        if let shown, Date.now >= shown.until, shown.until != .distantFuture {
            self.shown = nil
        }
        // Never silent for longer than the stream takes to catch up, whatever word was lost.
        if isDucked, Date.now.timeIntervalSince(duckedAt) > Self.catchUp {
            unduck()
        }
    }

    private func playerChanged() {
        let next: PlayerStatus
        switch player.timeControlStatus {
        case .playing:
            next = .playing
            unduck()
            // The stream has caught up with what was asked: its own position from here.
            if let shown, shown.until != .distantFuture { self.shown = (shown.time, shown.at, Date.now.addingTimeInterval(0.5)) }
        case .waitingToPlayAtSpecifiedRate:
            next = .loading
        case .paused:
            next = isMeantToPlay ? .loading : (entries.isEmpty ? .stopped : .paused)
        @unknown default:
            next = .paused
        }
        guard next != status else { return }
        status = next
        onChange?()
        publish()
        updateNowPlayingInfo()
    }

    private func duck() {
        isDucked = true
        duckedAt = .now
        player.volume = 0
    }

    private func unduck() {
        guard isDucked else { return }
        isDucked = false
        player.volume = level
    }

    // MARK: - The network

    private var wantedTier: TracksideRelaySession.Tier {
        serverID.map(preferredTier(on:)) ?? .lossless
    }

    /// The tier this network calls for: lossless on Wi-Fi and wired, the smaller one on a
    /// costly or constrained network where the server can make it.
    func preferredTier(on serverID: String) -> TracksideRelaySession.Tier {
        let serverHasCellular = music.servers.streaming.playback[serverID]?.relayCellular ?? false
        return RelayTierChoice.tier(isExpensive: path.expensive, isConstrained: path.constrained, usesCellular: path.cellular, serverHasCellular: serverHasCellular)
    }

    /// Moves the relay to the tier the network now calls for, once the move has settled.
    private func networkChanged(_ seen: (expensive: Bool, constrained: Bool, cellular: Bool)) {
        guard seen != path else { return }
        path = seen
        tierChange?.cancel()
        tierChange = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self, !Task.isCancelled, let client = self.client, let session = self.session, !session.hasEnded else { return }
            let tier = self.wantedTier
            guard tier != session.tier else { return }
            if self.isMeantToPlay { self.duck() }
            do {
                let moved = try await client.setRelayTier(tier, session: session.id)
                self.adopt(moved)
            } catch {
                self.unduck()
            }
        }
    }

    // MARK: - Interruptions

    #if os(iOS)
    private var wasPlayingBeforeInterruption = false

    private func interruptionBegan() {
        wasPlayingBeforeInterruption = isMeantToPlay
    }

    private func interruptionEnded(shouldResume: Bool) {
        if wasPlayingBeforeInterruption, isMeantToPlay, shouldResume {
            PlaybackAudioSession.activateRetryingLater()
            Task { try? await resume() }
        }
        wasPlayingBeforeInterruption = false
    }

    private func mediaServicesReset() {
        observations = []
        itemObservation = nil
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        player = AVPlayer()
        player.volume = isDucked ? 0 : level
        follow(player)
        guard isActive else { return }
        PlaybackAudioSession.activateRetryingLater()
        if isMeantToPlay, case .open(let path) = follower.reopen() { open(path) }
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
            Self.onMain { Task { try? await self?.resume() } }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            Self.onMain { self?.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Self.onMain {
                guard let self else { return }
                if self.isMeantToPlay { self.pause() } else { Task { try? await self.resume() } }
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

    /// Hands everything back for another player. The relay is paused, not ended: it's quick
    /// to come back to, and the server ends it itself after a while.
    func deactivate() {
        if session?.hasEnded == false, isMeantToPlay { send(.pause) }
        starts += 1
        isMeantToPlay = false
        recovering?.cancel()
        recovering = nil
        queueRead?.cancel()
        queueRead = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        follower.paused()
        entries = []
        index = 0
        heard = nil
        pending = nil
        shown = nil
        unduck()
        status = .stopped
        publish()
        artworkTask?.cancel()
        artworkTask = nil
        let wasActive = isActive
        isActive = false
        guard wasActive else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        MPNowPlayingInfoCenter.default().playbackState = .stopped
        let center = MPRemoteCommandCenter.shared()
        for command in [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand, center.nextTrackCommand, center.previousTrackCommand, center.changePlaybackPositionCommand] {
            command.removeTarget(nil)
        }
    }

    /// Ends the relay on the server, for a sign-out: nothing of it is left playing there.
    func end() {
        guard let client, let session, !session.hasEnded else { return }
        Task.detached { try? await client.endRelay(session: session.id) }
    }

    // MARK: - Telling others

    /// Tells capture what's audible, so these plays are kept like any other, under the
    /// service they came from.
    private func publish() {
        guard let track = current else {
            capture.publish(nil)
            return
        }
        let entry = entries.indices.contains(index) ? entries[index] : nil
        let marker: String = if let relay = entry?.relay, relay.isFromService {
            PlaySource.serviceMarker(key: serviceKey(relay.serviceID))
        } else {
            PlaySource.yourMusicMarker
        }
        capture.publish(NowPlayingObservation(
            title: track.title,
            artistName: track.artistName,
            albumTitle: track.albumTitle,
            duration: track.duration,
            playbackState: status == .playing ? .playing : (status == .stopped ? .stopped : .paused),
            playerPosition: playbackTime,
            isStation: false,
            rawFields: [PlaySource.observationKey: marker, "origin": "relay", PlayID.observationKey: playID(for: track.id)]
        ))
    }

    private func serviceKey(_ serviceID: String?) -> String? {
        guard let serviceID, let serverID else { return nil }
        return music.servers.streaming.services[serverID]?.first { $0.id == serviceID }?.logoKey
    }

    private func playID(for entryID: String) -> String {
        if let currentPlay, currentPlay.entryID == entryID { return currentPlay.id }
        let id = PlayID.make()
        currentPlay = (entryID, id)
        return id
    }

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
        guard let track = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artistName,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: playbackTime,
            MPNowPlayingInfoPropertyPlaybackRate: status == .playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            // Live underneath, but each song has its own place and length to show.
            MPNowPlayingInfoPropertyIsLiveStream: false,
        ]
        if let album = track.albumTitle { info[MPMediaItemPropertyAlbumTitle] = album }
        if let duration = track.duration { info[MPMediaItemPropertyPlaybackDuration] = duration }
        let previous = MPNowPlayingInfoCenter.default().nowPlayingInfo
        if previous?[MPMediaItemPropertyTitle] as? String == track.title, let artwork = previous?[MPMediaItemPropertyArtwork] {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        guard info[MPMediaItemPropertyArtwork] == nil, case .url(let address?, _) = track.cover, let url = URL(string: address) else { return }
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

    // MARK: - Words and values

    /// How long a position asked for here is shown before the stream's own takes over.
    private static let catchUp: TimeInterval = 8

    static func words(for error: any Error) -> String {
        switch error {
        case TracksideError.conflict(let reason):
            switch reason {
            case "relay_limit": String(localized: "Too many devices are playing music services. Stop one, then try again.")
            case "service_signed_out": String(localized: "Sign in to the service again on your server.")
            case "not_playable": String(localized: "This isn't available to play right now.")
            default: String(localized: "Try again in a moment.")
            }
        case TracksideError.unsupported(let capability) where capability == "relay" || capability == "relay_cellular":
            String(localized: "Your server can't play music services on this device yet.")
        case TracksideError.unavailable:
            String(localized: "Music services aren't available right now.")
        case TracksideError.rateLimited:
            String(localized: "Too many changes at once. Try again in a moment.")
        case TracksideError.notFound:
            String(localized: "This is no longer available.")
        case is URLError:
            String(localized: "Couldn't reach your server.")
        default:
            String(localized: "Try again in a moment.")
        }
    }

    static func title(for error: any Error) -> String? {
        switch error {
        case TracksideError.conflict(let reason) where reason == "relay_limit": String(localized: "Too Many Playing")
        case TracksideError.conflict(let reason) where reason == "service_signed_out": String(localized: "Signed Out")
        case TracksideError.unavailable: String(localized: "Not Available Right Now")
        default: nil
        }
    }

    static func wire(_ mode: PlayerRepeat) -> TracksideSpeaker.Repeat {
        switch mode {
        case .off: .off
        case .all: .all
        case .one: .one
        }
    }

    static func repeatMode(_ mode: TracksideSpeaker.Repeat?) -> PlayerRepeat {
        switch mode {
        case .all: .all
        case .one: .one
        case .off, .unknown, nil: .off
        }
    }

    static func speakerContext(_ context: PlayContext) -> TracksideSpeaker.Context {
        let kind: TracksideSpeaker.Context.Kind = switch context.kind {
        case .station: .station
        case .mix, .endless: .mix
        case .album: .album
        case .playlist: .playlist
        case .artist: .artist
        case .songs: .catalog
        }
        return TracksideSpeaker.Context(kind: kind, name: context.title.isEmpty ? nil : context.title)
    }

    nonisolated private static func onMain(_ body: @escaping @MainActor @Sendable () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated(body)
        } else {
            Task { @MainActor in body() }
        }
    }
}

import Foundation
import MediaPlayer
import SwiftUI
import TracksCore

/// A player that only pretends, for sample data: the songs are made up, so nothing may reach
/// Apple Music. Time runs and songs change, so the screens can be seen as they'd really look.
@MainActor
final class DemoPlayerEngine: PlayerEngine {
    var onChange: (() -> Void)?

    private(set) var status: PlayerStatus = .stopped
    private(set) var isShuffled = false
    private(set) var repeatMode: PlayerRepeat = .off

    private var queue: [PlayerTrack] = []
    /// Your own music's songs, by id, so the pretend player can show their format.
    private var localTracks: [String: LocalTrack] = [:]
    private var index = 0
    private var isStation = false
    /// Played before the last resume, plus the time since it.
    private var elapsedBeforeResume: TimeInterval = 0
    private var resumedAt: Date?
    private var ticker: Task<Void, Never>?
    /// Only ever goes up, so no two queue entries share an id.
    private var nextEntryNumber = 0

    /// Whether this pretend player fills Now Playing for the lock screen and CarPlay. Only in a
    /// demo launch: a preview makes one too, and mustn't take over the device's Now Playing.
    private let publishesNowPlaying = DemoMode.isRequestedAtLaunch
    private var hasRemoteCommands = false
    /// The song whose artwork Now Playing has, or is being drawn.
    private var artworkFor: String?
    private var artworkTask: Task<Void, Never>?

    /// Sample songs a station draws from, taken from the sample history.
    private let songsForStations: () -> [HistorySong]

    init(songsForStations: @escaping () -> [HistorySong]) {
        self.songsForStations = songsForStations
    }

    var current: PlayerTrack? { queue.indices.contains(index) ? queue[index] : nil }
    var playsByName: Bool { true }
    var upNext: [PlayerTrack] { isStation || queue.isEmpty ? [] : Array(queue[(index + 1)...]) }

    var playbackTime: TimeInterval {
        elapsedBeforeResume + (resumedAt.map { Date.now.timeIntervalSince($0) } ?? 0)
    }

    func play(_ request: PlayRequest, context: PlayContext, shuffled: Bool) async throws {
        var songs: [HistorySong]
        var start = 0
        switch request {
        case .history(let history, let first):
            songs = history
            start = first
        case .demoStation:
            songs = Array(songsForStations().shuffled().prefix(30))
        case .local(let tracks, let first):
            songs = tracks.map(HistorySong.init)
            start = first
            localTracks = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        default:
            // Apple Music items never appear with sample data.
            throw PlayerProblem.nothingToPlay
        }
        guard !songs.isEmpty else { throw PlayerProblem.nothingToPlay }
        if shuffled {
            songs = FreshShuffle.order(songs, artist: { StatsCalculator.folded($0.artistName) }, seed: .random(in: 0...UInt64.max))
            start = 0
        }
        isStation = context.isStation
        if isStation { repeatMode = .off }
        let isLive = request.isLiveBroadcast
        queue = songs.map { makeTrack($0, isLive: isLive) }
        index = min(max(0, start), queue.count - 1)
        restartClock(playing: true)
    }

    func enqueue(_ request: PlayRequest, next: Bool) async throws {
        let songs: [HistorySong]
        switch request {
        case .history(let history, _): songs = history
        case .local(let tracks, _): songs = tracks.map(HistorySong.init)
        default: throw PlayerProblem.nothingToPlay
        }
        guard !isStation else { throw PlayerProblem.nothingToPlay }
        let tracks = songs.map { makeTrack($0, isLive: false) }
        if queue.isEmpty {
            queue = tracks
            index = 0
            restartClock(playing: true)
            return
        }
        queue.insert(contentsOf: tracks, at: next ? index + 1 : queue.count)
        changed()
    }

    func pause() {
        guard status == .playing else { return }
        elapsedBeforeResume = playbackTime
        resumedAt = nil
        status = .paused
        ticker?.cancel()
        changed()
    }

    func resume() async throws {
        guard current != nil, status != .playing else { return }
        resumedAt = .now
        status = .playing
        startTicker()
        changed()
    }

    func skipToNext() async throws {
        advance(by: 1)
    }

    func skipToPrevious() async throws {
        if playbackTime > 3 || index == 0 {
            seek(to: 0)
        } else {
            advance(by: -1)
        }
    }

    func seek(to time: TimeInterval) {
        elapsedBeforeResume = max(0, time)
        resumedAt = status == .playing ? .now : nil
        changed()
    }

    func setShuffled(_ isShuffled: Bool) {
        self.isShuffled = isShuffled
        guard isShuffled, index + 1 < queue.count else {
            changed()
            return
        }
        queue[(index + 1)...].shuffle()
        changed()
    }

    func setRepeat(_ mode: PlayerRepeat) {
        repeatMode = mode
        changed()
    }

    func jump(toUpNext offset: Int) async throws {
        guard queue.indices.contains(index + 1 + offset) else { return }
        index += 1 + offset
        restartClock(playing: true)
    }

    func removeUpNext(at offsets: IndexSet) {
        let places = IndexSet(offsets.map { $0 + index + 1 }.filter(queue.indices.contains))
        guard !places.isEmpty else { return }
        queue.remove(atOffsets: places)
        changed()
    }

    func moveUpNext(from offsets: IndexSet, to destination: Int) {
        let places = IndexSet(offsets.map { $0 + index + 1 }.filter(queue.indices.contains))
        guard !places.isEmpty else { return }
        queue.move(fromOffsets: places, toOffset: min(max(destination + index + 1, index + 1), queue.count))
        changed()
    }

    // MARK: - Now Playing

    /// Tells the screens, and Now Playing on the lock screen and in the car.
    private func changed() {
        publishNowPlaying()
        onChange?()
    }

    private func publishNowPlaying() {
        guard publishesNowPlaying else { return }
        let center = MPNowPlayingInfoCenter.default()
        guard let current, status != .stopped else {
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            artworkTask?.cancel()
            artworkFor = nil
            return
        }
        addRemoteCommands()
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: current.title,
            MPMediaItemPropertyArtist: current.artistName,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: playbackTime,
            MPNowPlayingInfoPropertyPlaybackRate: status == .playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let album = current.albumTitle { info[MPMediaItemPropertyAlbumTitle] = album }
        if let duration = current.duration { info[MPMediaItemPropertyPlaybackDuration] = duration }
        if artworkFor == current.id, let artwork = center.nowPlayingInfo?[MPMediaItemPropertyArtwork] {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        center.nowPlayingInfo = info
        center.playbackState = status == .playing ? .playing : .paused
        guard artworkFor != current.id else { return }
        artworkFor = current.id
        artworkTask?.cancel()
        let cover = current.cover
        let id = current.id
        artworkTask = Task { [weak self] in
            guard let picture = await Self.picture(for: cover), !Task.isCancelled, self?.current?.id == id,
                  var info = MPNowPlayingInfoCenter.default().nowPlayingInfo
            else { return }
            info[MPMediaItemPropertyArtwork] = Self.nowPlayingArtwork(picture)
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    /// The sample song's cover: its address, or the drawn cover the sample screens show for it.
    private static func picture(for cover: CoverArt) async -> CGImage? {
        guard case .url(let address, let seed) = cover else { return nil }
        if let address, let url = URL(string: address), let image = await ArtworkImages.shared.image(for: ArtworkImages.Key(url: url, pixels: 600)) {
            return image
        }
        let renderer = ImageRenderer(content: GeneratedCover(seed: seed).frame(width: 300, height: 300))
        renderer.scale = 2
        return renderer.cgImage
    }

    /// Made off the main actor: MediaPlayer asks for the picture on a queue of its own.
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

    /// Play, pause and skip from the lock screen and the car, for the pretend player.
    private func addRemoteCommands() {
        guard !hasRemoteCommands else { return }
        hasRemoteCommands = true
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
                if self.status == .playing { self.pause() } else { Task { try? await self.resume() } }
            }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            Self.onMain { self?.advance(by: 1) }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            Self.onMain { Task { try? await self?.skipToPrevious() } }
            return .success
        }
    }

    nonisolated private static func onMain(_ body: @escaping @MainActor @Sendable () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated(body)
        } else {
            Task { @MainActor in body() }
        }
    }

    // MARK: - The pretend clock

    private func advance(by step: Int) {
        let next = index + step
        if repeatMode == .one, step > 0, status == .playing, playbackTime >= (current?.duration ?? 0) {
            restartClock(playing: true)
        } else if queue.indices.contains(next) {
            index = next
            restartClock(playing: status != .paused)
        } else if repeatMode == .all, !queue.isEmpty {
            index = 0
            restartClock(playing: true)
        } else {
            // The end of the queue: stop on the last song, as a real player does.
            elapsedBeforeResume = 0
            resumedAt = nil
            status = .paused
            ticker?.cancel()
            changed()
        }
    }

    private func restartClock(playing: Bool) {
        elapsedBeforeResume = 0
        resumedAt = playing ? .now : nil
        status = playing ? .playing : .paused
        if playing { startTicker() } else { ticker?.cancel() }
        changed()
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, !Task.isCancelled else { return }
                if let duration = self.current?.duration ?? (self.isStation ? 200 : nil),
                   self.playbackTime >= duration {
                    self.advance(by: 1)
                }
            }
        }
    }

    /// Songs for a pretend station: the sample history's, newest first, each once.
    static func stationSongs(from history: ListeningHistory) -> [HistorySong] {
        var seen = Set<String>()
        return history.captures.reversed().compactMap { capture in
            guard seen.insert(capture.songIdentity).inserted else { return nil }
            return HistorySong(
                songID: capture.songID,
                title: capture.title,
                artistName: capture.artistName,
                albumTitle: capture.albumTitle,
                artworkURL: capture.artworkURL
            )
        }
    }

    private func makeTrack(_ song: HistorySong, isLive: Bool) -> PlayerTrack {
        nextEntryNumber += 1
        var track = Self.track(song, number: nextEntryNumber, isLive: isLive)
        track.local = localTracks[song.songID]
        return track
    }

    private static func track(_ song: HistorySong, number: Int, isLive: Bool) -> PlayerTrack {
        // Believable lengths that stay the same for a song.
        let seconds = 150 + Double(abs(GeneratedCover.hash(song.title)) % 120)
        return PlayerTrack(
            id: "demo.\(number).\(song.title)",
            title: song.title,
            artistName: song.artistName,
            albumTitle: song.albumTitle,
            cover: demoCover(number: number) ?? .url(song.artworkURL, seed: song.albumTitle ?? song.title),
            duration: SongLength.shown(seconds, isLiveBroadcast: isLive),
            isExplicit: false,
            song: nil
        )
    }

    /// `-TracksDemoCovers "https://…|https://…"`, in a Debug build: real covers for the sample
    /// songs, one after another, to see the player's backgrounds with real artwork.
    private static func demoCover(number: Int) -> CoverArt? {
        #if DEBUG
        guard let list = UserDefaults.standard.string(forKey: "TracksDemoCovers") else { return nil }
        let covers = list.split(separator: "|").map(String.init)
        guard !covers.isEmpty else { return nil }
        return .url(covers[(number - 1) % covers.count], seed: "demo")
        #else
        return nil
        #endif
    }
}

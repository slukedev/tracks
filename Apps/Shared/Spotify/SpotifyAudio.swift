#if SPOTIFY
import Foundation
import AVFoundation
import Synchronization
import TracksEngine

/// Decoded Spotify audio on its way to the speaker: written by the thread that decodes, read
/// by the render callback. One writer and one reader, so no locks, and nothing on the reading
/// side allocates. The counters only grow; a sample's place is its count masked to the size.
nonisolated final class SpotifyRing: @unchecked Sendable {
    static let sampleRate: Double = 44_100
    static let channels = 2

    private let capacity: Int
    private let mask: Int
    private let samples: UnsafeMutablePointer<Float>
    private let written = Atomic<Int>(0)
    private let consumed = Atomic<Int>(0)
    /// Everything before this is dropped unheard: set by the writer for a seek or a new song.
    private let dropBefore = Atomic<Int>(0)

    init(seconds: Double) {
        let wanted = Int(seconds * Self.sampleRate) * Self.channels
        var size = 1024
        while size < wanted { size <<= 1 }
        capacity = size
        mask = size - 1
        samples = .allocate(capacity: size)
        samples.initialize(repeating: 0, count: size)
    }

    deinit {
        samples.deallocate()
    }

    static func seconds(fromSamples count: Int) -> Double {
        Double(count / channels) / sampleRate
    }

    // MARK: Writing (the decoding thread only)

    var writtenCount: Int { written.load(ordering: .acquiring) }

    /// Room for more. Counted from what's still to be heard, so a seek while paused can fill
    /// the ring before the render callback has caught up with the drop.
    var freeSpace: Int {
        let heard = max(consumed.load(ordering: .acquiring), dropBefore.load(ordering: .relaxed))
        return capacity - (written.load(ordering: .relaxed) - heard)
    }

    /// Copies in whole frames of interleaved little-endian floats, as many as fit, byte for
    /// byte, and returns how many bytes that was.
    func write(bytes source: UnsafeRawBufferPointer) -> Int {
        guard let base = source.baseAddress else { return 0 }
        let size = MemoryLayout<Float>.size
        let start = written.load(ordering: .relaxed)
        var count = min(source.count / size, freeSpace)
        count -= count % Self.channels
        guard count > 0 else { return 0 }
        let offset = start & mask
        let first = min(count, capacity - offset)
        UnsafeMutableRawPointer(samples + offset).copyMemory(from: base, byteCount: first * size)
        if first < count {
            UnsafeMutableRawPointer(samples).copyMemory(from: base + first * size, byteCount: (count - first) * size)
        }
        written.store(start + count, ordering: .releasing)
        return count * size
    }

    /// Drops everything written so far; what's written next plays straight away. Returns
    /// where that is. The player may call it too, to fall silent before the writer catches
    /// up: the mark only ever moves forward, so a drop that lands late changes nothing.
    @discardableResult
    func dropAll() -> Int {
        let at = written.load(ordering: .acquiring)
        var known = dropBefore.load(ordering: .relaxed)
        while known < at {
            let (exchanged, original) = dropBefore.compareExchange(expected: known, desired: at, ordering: .acquiringAndReleasing)
            if exchanged { return at }
            known = original
        }
        return known
    }

    // MARK: Reading (the render callback only)

    var consumedCount: Int { consumed.load(ordering: .acquiring) }

    /// Fills both channels, with silence after whatever has arrived. Returns the frames that
    /// were music.
    func render(left: UnsafeMutablePointer<Float>, right: UnsafeMutablePointer<Float>?, frames: Int) -> Int {
        var position = consumed.load(ordering: .relaxed)
        let drop = dropBefore.load(ordering: .acquiring)
        if position < drop { position = drop }
        let available = max(0, written.load(ordering: .acquiring) - position) / Self.channels
        let count = min(frames, available)
        var frame = 0
        while frame < count {
            // Even, and the capacity is even, so the right sample never wraps on its own.
            let index = (position + frame * Self.channels) & mask
            left[frame] = samples[index]
            right?[frame] = samples[index + 1]
            frame += 1
        }
        if count < frames {
            (left + count).update(repeating: 0, count: frames - count)
            if let right { (right + count).update(repeating: 0, count: frames - count) }
        }
        consumed.store(position + count * Self.channels, ordering: .releasing)
        return count
    }
}

/// The sound itself: an `AVAudioEngine` pulling from the ring through a source node. Made
/// again when the system resets its media services.
nonisolated final class SpotifyAudioOutput {
    let engine = AVAudioEngine()
    private let node: AVAudioSourceNode

    init(ring: SpotifyRing) {
        let format = AVAudioFormat(standardFormatWithSampleRate: SpotifyRing.sampleRate, channels: AVAudioChannelCount(SpotifyRing.channels))!
        node = AVAudioSourceNode(format: format, renderBlock: Self.renderBlock(ring))
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        engine.prepare()
    }

    /// Runs on the real-time audio thread: reads the ring and nothing else.
    private static func renderBlock(_ ring: SpotifyRing) -> AVAudioSourceNodeRenderBlock {
        { isSilence, _, frameCount, bufferList in
            let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
            guard buffers.count > 0, let left = buffers[0].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            let right = buffers.count > 1 ? buffers[1].mData?.assumingMemoryBound(to: Float.self) : nil
            let played = ring.render(left: left, right: right, frames: Int(frameCount))
            isSilence.pointee = ObjCBool(played == 0)
            return noErr
        }
    }

    var isRunning: Bool { engine.isRunning }

    func start() throws {
        guard !engine.isRunning else { return }
        try engine.start()
    }

    func pause() {
        engine.pause()
    }

    func stop() {
        engine.stop()
    }

    var volume: Float {
        get { node.volume }
        set { node.volume = newValue }
    }
}

/// Pulls decoded audio from Spotify into the ring on a thread of its own, since a read blocks
/// while the song downloads. The player says what to play and what comes next; the next song
/// is opened shortly before the one playing ends and follows it in the ring without a gap.
nonisolated final class SpotifyFeeder: @unchecked Sendable {
    /// A song to decode, by the player's own token for this one time it plays.
    struct Song: Sendable, Equatable {
        let token: String
        let uri: String
    }

    /// Where each song starts in the ring, so the player knows which one is being heard.
    struct Segment: Sendable {
        let token: String
        let start: Int
        let startSeconds: Double
    }

    struct Timeline: Sendable {
        var segments: [Segment] = []
        /// Where the audio stops, with nothing to follow.
        var end: Int?
    }

    enum Event: Sendable {
        /// A song (or a seek in it) is decoding and about to be heard.
        case ready(String)
        case failed(String, SpotifyError?)
    }

    private enum Command: Sendable {
        case load(Song, milliseconds: UInt32)
        case seek(Song, milliseconds: UInt32)
        case stop
    }

    private struct Shared: Sendable {
        var commands: [Command] = []
        var next: Song?
        var options: SpotifyStreamOptions
        var quits = false
    }

    private static let chunkFrames: UInt32 = 2048
    /// How long before a song's end the next one is opened: enough for its key and first bytes.
    private static let openNextLead: Int = 25_000

    let ring: SpotifyRing
    let timeline = Mutex(Timeline())
    private let shared: Mutex<Shared>
    private let wake = DispatchSemaphore(value: 0)
    private let account: SpotifyAccount
    private let report: @Sendable (Event) -> Void

    // Touched only on the feeding thread.
    private var current: (song: Song, stream: SpotifyStream)?
    private var currentEnded = false
    private var upcoming: (song: Song, stream: SpotifyStream)?
    private var failedNext: String?
    /// Decoded audio not yet in the ring, as the engine hands it over, and how many of its
    /// bytes are in already.
    private var pending = Data()
    private var pendingOffset = 0

    init(account: SpotifyAccount, ring: SpotifyRing, options: SpotifyStreamOptions, report: @escaping @Sendable (Event) -> Void) {
        self.account = account
        self.ring = ring
        self.report = report
        shared = Mutex(Shared(options: options))
        let thread = Thread { [self] in run() }
        thread.name = "Spotify audio"
        thread.qualityOfService = .userInitiated
        thread.start()
    }

    // MARK: Asked by the player

    func load(_ song: Song, at seconds: TimeInterval) {
        send(.load(song, milliseconds: Self.milliseconds(seconds)))
    }

    func seek(_ song: Song, to seconds: TimeInterval) {
        send(.seek(song, milliseconds: Self.milliseconds(seconds)))
    }

    func stop() {
        send(.stop)
    }

    func setNext(_ song: Song?) {
        shared.withLock { $0.next = song }
        wake.signal()
    }

    func setOptions(_ options: SpotifyStreamOptions) {
        shared.withLock { $0.options = options }
    }

    /// Ends the thread once it's next awake. The feeder is done with after this.
    func shutDown() {
        shared.withLock { $0.quits = true }
        wake.signal()
    }

    private func send(_ command: Command) {
        shared.withLock { $0.commands.append(command) }
        wake.signal()
    }

    private static func milliseconds(_ seconds: TimeInterval) -> UInt32 {
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return UInt32(min(seconds * 1000, Double(UInt32.max)))
    }

    // MARK: The feeding thread

    private func run() {
        while true {
            let (commands, next, options, quits) = shared.withLock { state in
                let commands = state.commands
                state.commands = []
                return (commands, state.next, state.options, state.quits)
            }
            if quits {
                closeAll()
                return
            }
            handle(commands, options: options)
            followNext(next, options: options)
            if currentEnded { finishCurrent(next: next) }
            if !feed() {
                if current == nil, upcoming == nil {
                    wake.wait()
                } else {
                    _ = wake.wait(timeout: .now() + .milliseconds(15))
                }
            }
        }
    }

    /// Only the last load or stop counts, and the seeks after it.
    private func handle(_ commands: [Command], options: SpotifyStreamOptions) {
        guard !commands.isEmpty else { return }
        let from = commands.lastIndex { if case .seek = $0 { false } else { true } } ?? commands.startIndex
        for command in commands[from...] {
            switch command {
            case .stop:
                closeAll()
                startOver()
            case .load(let song, let milliseconds):
                open(song, at: milliseconds, options: options)
            case .seek(let song, let milliseconds):
                if let current, current.song == song {
                    seek(current.stream, of: song, to: milliseconds)
                } else {
                    open(song, at: milliseconds, options: options)
                }
            }
        }
    }

    private func startOver() {
        pending = Data()
        pendingOffset = 0
        ring.dropAll()
        timeline.withLock { $0 = Timeline() }
    }

    private func open(_ song: Song, at milliseconds: UInt32, options: SpotifyStreamOptions) {
        closeAll()
        startOver()
        do {
            let stream = try account.openStream(uri: song.uri, options: options)
            let landed = milliseconds > 0 ? try stream.seek(positionMs: milliseconds) : 0
            current = (song, stream)
            currentEnded = false
            failedNext = nil
            begin(song, atSeconds: Double(landed) / 1000, replacing: true)
            report(.ready(song.token))
        } catch {
            report(.failed(song.token, error as? SpotifyError))
        }
    }

    private func seek(_ stream: SpotifyStream, of song: Song, to milliseconds: UInt32) {
        pending = Data()
        pendingOffset = 0
        ring.dropAll()
        timeline.withLock { $0 = Timeline() }
        do {
            let landed = try stream.seek(positionMs: milliseconds)
            currentEnded = false
            begin(song, atSeconds: Double(landed) / 1000, replacing: true)
            report(.ready(song.token))
        } catch {
            stream.close()
            current = nil
            report(.failed(song.token, error as? SpotifyError))
        }
    }

    private func begin(_ song: Song, atSeconds seconds: Double, replacing: Bool) {
        let segment = Segment(token: song.token, start: ring.writtenCount, startSeconds: seconds)
        timeline.withLock { timeline in
            if replacing { timeline = Timeline() }
            timeline.segments.append(segment)
            timeline.end = nil
        }
    }

    /// Opens the next song near the end of this one, and lets go of one no longer next.
    private func followNext(_ next: Song?, options: SpotifyStreamOptions) {
        if let upcoming, upcoming.song != next {
            upcoming.stream.close()
            self.upcoming = nil
        }
        guard let next, upcoming == nil, failedNext != next.token, let current else { return }
        let left = Int(current.stream.durationMs()) - Int(current.stream.positionMs())
        guard currentEnded || left < Self.openNextLead else { return }
        do {
            upcoming = (next, try account.openStream(uri: next.uri, options: options))
        } catch {
            failedNext = next.token
            report(.failed(next.token, error as? SpotifyError))
        }
    }

    /// The song decoding has run out: the next follows straight on, or the audio ends here.
    private func finishCurrent(next: Song?) {
        guard let ended = current else { return }
        if let upcoming {
            ended.stream.close()
            current = upcoming
            self.upcoming = nil
            currentEnded = false
            begin(upcoming.song, atSeconds: 0, replacing: false)
        } else if next == nil || next?.token == failedNext {
            ended.stream.close()
            current = nil
            currentEnded = false
            let end = ring.writtenCount
            timeline.withLock { $0.end = end }
        }
    }

    /// Moves audio along. False when there's nothing to do for now: the ring is full, or
    /// there's nothing to decode.
    private func feed() -> Bool {
        if pendingOffset < pending.count {
            let wrote = pending.withUnsafeBytes { buffer in
                ring.write(bytes: UnsafeRawBufferPointer(rebasing: buffer[pendingOffset...]))
            }
            pendingOffset += wrote
            // Less than a frame left over can never go in.
            if pending.count - pendingOffset < MemoryLayout<Float>.size * SpotifyRing.channels { pendingOffset = pending.count }
            return pendingOffset >= pending.count
        }
        guard let current, !currentEnded, ring.freeSpace >= Int(Self.chunkFrames) * SpotifyRing.channels else { return false }
        do {
            let bytes = try current.stream.readF32Bytes(maxFrames: Self.chunkFrames)
            if bytes.isEmpty {
                currentEnded = true
            } else {
                pending = bytes
                pendingOffset = 0
            }
            return true
        } catch {
            // The player decides: again from where it got to, or the next song.
            current.stream.close()
            self.current = nil
            report(.failed(current.song.token, error as? SpotifyError))
            return false
        }
    }

    private func closeAll() {
        current?.stream.close()
        upcoming?.stream.close()
        current = nil
        upcoming = nil
        currentEnded = false
    }
}
#endif

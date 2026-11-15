import Accelerate
import CoreAudio
import Synchronization
import TracksCore
import os

/// MusicKit doesn't play in Tracks: on the Mac, MediaPlayer starts a helper for it,
/// RemotePlayerService, and the song is heard from there. Only that helper is tapped, by its
/// bundle ID, and the tap picks the helper up again when the system starts a new one.
///
/// A tap macOS hasn't let Tracks use hears silence rather than failing, so while the helper
/// plays and nothing comes through, it reports ``Access/blocked``.
///
/// The audio thread only copies the sound into a ring and notes when it was last loud:
/// no allocating, locking or waiting. Everything else happens on `queue`.
nonisolated final class AppleMusicListener: @unchecked Sendable {
    static let shared = AppleMusicListener()
    static let playerBundleID = "com.apple.MediaPlayer.RemotePlayerService"

    enum Access: Sendable, Equatable {
        case hearing
        case blocked
    }

    private let current = Mutex<Session?>(nil)
    private let queue = DispatchQueue(label: "Tracks.AppleMusicListener", qos: .userInteractive)
    private let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Tracks", category: "AppleMusicListener")

    // Only touched on `queue`.
    private var isWanted = false
    private var isAllowed = false
    private var tap: AudioHardwareTap?
    private var aggregate: AudioHardwareAggregateDevice?
    private var proc: AudioDeviceIOProcID?
    private var watched: [(object: AudioObjectID, address: AudioObjectPropertyAddress, block: AudioObjectPropertyListenerBlock)] = []
    private var poll: DispatchSourceTimer?
    private var stopping: DispatchWorkItem?
    private var rebuilding: DispatchWorkItem?
    private var failures = 0
    private var playingFor = 0
    private var access: Access?
    private var onAccess: (@MainActor @Sendable (Access) -> Void)?

    /// A fresh tap is given this long to hear Apple Music before it counts as refused: a song
    /// can open on a few seconds of silence.
    private static let refusedAfter = 8

    private init() {}

    var heard: HeardSound? {
        current.withLock { session in session.flatMap { $0.isHearing(at: Self.now()) ? $0.sound : nil } }
    }

    func setWanted(_ wanted: Bool) {
        queue.async { [self] in
            isWanted = wanted
            reconcile()
        }
    }

    func setAllowed(_ allowed: Bool) {
        queue.async { [self] in
            isAllowed = allowed
            reconcile()
        }
    }

    func observeAccess(_ handler: @escaping @MainActor @Sendable (Access) -> Void) {
        queue.async { [self] in onAccess = handler }
    }

    func restart() {
        queue.async { [self] in
            guard aggregate != nil else { return }
            scheduleRebuild()
        }
    }

    /// Now, in host seconds: the clock the tap's sound is timed by.
    static func now() -> Double {
        Double(AudioConvertHostTimeToNanos(AudioGetCurrentHostTime())) / 1e9
    }

    // MARK: - Running

    private func reconcile() {
        if isWanted && isAllowed {
            stopping?.cancel()
            stopping = nil
            if aggregate == nil { begin() }
        } else if aggregate != nil || rebuilding != nil {
            guard isAllowed else {
                end()
                return
            }
            guard stopping == nil else { return }
            let item = DispatchWorkItem { [self] in
                stopping = nil
                end()
            }
            stopping = item
            queue.asyncAfter(deadline: .now() + 1, execute: item)
        }
    }

    private func begin() {
        let description = CATapDescription(stereoMixdownOfProcesses: [])
        description.bundleIDs = [Self.playerBundleID]
        description.isProcessRestoreEnabled = true
        description.name = "Tracks Visualizer"
        description.isPrivate = true
        description.muteBehavior = .unmuted
        let system = AudioHardwareSystem.shared
        do {
            guard let tap = try system.makeProcessTap(description: description) else { throw AudioHardwareError(kAudioHardwareUnspecifiedError) }
            self.tap = tap
            let format = try tap.format
            // The speakers keep the device's clock, so the tap's sound arrives in step with
            // what's heard.
            var composition: [String: Any] = [
                kAudioAggregateDeviceNameKey: "Tracks Visualizer",
                kAudioAggregateDeviceUIDKey: UUID().uuidString,
                kAudioAggregateDeviceIsPrivateKey: true,
                kAudioAggregateDeviceIsStackedKey: false,
                kAudioAggregateDeviceTapAutoStartKey: true,
                kAudioAggregateDeviceTapListKey: [[
                    kAudioSubTapDriftCompensationKey: true,
                    kAudioSubTapUIDKey: description.uuid.uuidString,
                ]],
            ]
            if let output = try? system.defaultOutputDevice?.uid {
                composition[kAudioAggregateDeviceMainSubDeviceKey] = output
                composition[kAudioAggregateDeviceSubDeviceListKey] = [[kAudioSubDeviceUIDKey: output]]
            }
            guard let aggregate = try system.makeAggregateDevice(description: composition) else { throw AudioHardwareError(kAudioHardwareUnspecifiedError) }
            self.aggregate = aggregate

            // The tap hears sound as it's sent out; it's heard this much later. Timed by
            // when it's heard, the beat lands with the speakers rather than ahead of them: a
            // fifth of a second ahead, on Bluetooth headphones.
            let session = Session(latency: Self.outputLatency())
            session.sound.prepare(format)
            log.notice("Listening at \(format.mSampleRate, format: .fixed(precision: 0)) Hz, \(session.latency * 1000, format: .fixed(precision: 1)) ms to the speakers")
            var proc: AudioDeviceIOProcID?
            // On the device's own real-time thread, so no queue: see `Session.hear`.
            let made = AudioDeviceCreateIOProcIDWithBlock(&proc, aggregate.id, nil) { _, input, inputTime, _, _ in
                session.hear(input, at: inputTime)
            }
            guard made == noErr, let proc else { throw AudioHardwareError(made) }
            self.proc = proc
            try aggregate.start(IOProcID: proc)
            current.withLock { $0 = session }
            watch(tap: tap.id, aggregate: aggregate.id)
            startPolling()
            failures = 0
        } catch {
            log.error("Couldn't listen to Apple Music: \(error.localizedDescription, privacy: .public)")
            end()
            retryLater()
        }
    }

    private func end() {
        rebuilding?.cancel()
        rebuilding = nil
        poll?.cancel()
        poll = nil
        unwatch()
        current.withLock { $0 = nil }
        let system = AudioHardwareSystem.shared
        if let aggregate {
            if let proc {
                try? aggregate.stop(IOProcID: proc)
                AudioDeviceDestroyIOProcID(aggregate.id, proc)
            }
            try? system.destroyAggregateDevice(aggregate)
        }
        proc = nil
        aggregate = nil
        if let tap { try? system.destroyProcessTap(tap) }
        tap = nil
        playingFor = 0
    }

    private func retryLater() {
        failures += 1
        guard failures <= 4, isWanted, isAllowed else { return }
        scheduleRebuild(after: Double(failures) * 2)
    }

    private func scheduleRebuild(after delay: Double = 0.25) {
        rebuilding?.cancel()
        let item = DispatchWorkItem { [self] in
            rebuilding = nil
            end()
            if isWanted && isAllowed { begin() }
        }
        rebuilding = item
        queue.asyncAfter(deadline: .now() + delay, execute: item)
    }

    // MARK: - Changes underneath

    private func watch(tap: AudioObjectID, aggregate: AudioObjectID) {
        let rebuild: AudioObjectPropertyListenerBlock = { [weak self] _, _ in self?.scheduleRebuild() }
        let changes = [
            (AudioObjectID(kAudioObjectSystemObject), PropertyAddress(kAudioHardwarePropertyDefaultOutputDevice)),
            (tap, PropertyAddress(kAudioTapPropertyFormat)),
            (aggregate, PropertyAddress(kAudioDevicePropertyDeviceIsAlive)),
            (aggregate, PropertyAddress(kAudioDevicePropertyNominalSampleRate)),
        ]
        for (object, address) in changes {
            var address = address
            if AudioObjectAddPropertyListenerBlock(object, &address, queue, rebuild) == noErr {
                watched.append((object, address, rebuild))
            }
        }
    }

    private func unwatch() {
        for (object, address, block) in watched {
            var address = address
            AudioObjectRemovePropertyListenerBlock(object, &address, queue, block)
        }
        watched.removeAll()
    }

    private func startPolling() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1, leeway: .milliseconds(250))
        timer.setEventHandler { [weak self] in self?.check() }
        poll = timer
        timer.resume()
    }

    private func check() {
        guard let session = current.withLock({ $0 }) else { return }
        let players = ((try? AudioHardwareSystem.shared.processes) ?? []).filter { (try? $0.bundleID) == Self.playerBundleID }
        let playing = players.filter { (try? $0.isRunningOutput) == true }
        if let tapped = try? tap?.description.processes, playing.contains(where: { !tapped.contains($0.id) }) {
            log.notice("Apple Music's player changed; listening again")
            scheduleRebuild()
            return
        }
        playingFor = playing.isEmpty ? 0 : playingFor + 1
        if session.hasSound(since: Self.now() - Double(Self.refusedAfter)) {
            report(.hearing)
        } else if playingFor >= Self.refusedAfter {
            report(.blocked)
        }
    }

    private func report(_ found: Access) {
        guard found != access else { return }
        access = found
        log.notice("Apple Music is \(found == .hearing ? "heard" : "silent while playing", privacy: .public)")
        guard let onAccess else { return }
        Task { @MainActor in onAccess(found) }
    }

    // MARK: - The speakers

    /// Seconds from the tap to the speakers: the output's own latency, its safety offset, a
    /// buffer, and its stream's latency.
    static func outputLatency() -> Double {
        guard let device = try? AudioHardwareSystem.shared.defaultOutputDevice else { return 0 }
        func value<T>(_ selector: AudioObjectPropertySelector, of object: AudioObjectID, scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeOutput, _ empty: T) -> T {
            var address = AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
            var found = empty
            var size = UInt32(MemoryLayout<T>.size)
            return AudioObjectGetPropertyData(object, &address, 0, nil, &size, &found) == noErr ? found : empty
        }
        let rate = value(kAudioDevicePropertyNominalSampleRate, of: device.id, scope: kAudioObjectPropertyScopeGlobal, Float64(0))
        guard rate > 0 else { return 0 }
        var frames = value(kAudioDevicePropertyLatency, of: device.id, UInt32(0))
            + value(kAudioDevicePropertySafetyOffset, of: device.id, UInt32(0))
            + value(kAudioDevicePropertyBufferFrameSize, of: device.id, UInt32(0))
        let stream = value(kAudioDevicePropertyStreams, of: device.id, AudioObjectID(kAudioObjectUnknown))
        if stream != kAudioObjectUnknown {
            frames += value(kAudioStreamPropertyLatency, of: stream, scope: kAudioObjectPropertyScopeGlobal, UInt32(0))
        }
        let seconds = Double(frames) / rate
        return seconds.isFinite ? min(max(seconds, 0), 3) : 0
    }
}

private nonisolated final class Session: Sendable {
    let sound = HeardSound()
    let latency: Double
    /// When it was last loud enough to draw, in host nanoseconds.
    private let lastHeard = Atomic<UInt64>(0)
    /// When any sound at all last came through: a refused tap hears only zeros.
    private let lastSound = Atomic<UInt64>(0)

    init(latency: Double) {
        self.latency = latency
    }

    func isHearing(at now: Double) -> Bool {
        let last = lastHeard.load(ordering: .relaxed)
        return last > 0 && now - Double(last) / 1e9 < 0.75
    }

    func hasSound(since time: Double) -> Bool {
        let last = lastSound.load(ordering: .relaxed)
        return last > 0 && Double(last) / 1e9 >= time
    }

    /// On the device's real-time thread.
    func hear(_ input: UnsafePointer<AudioBufferList>, at time: UnsafePointer<AudioTimeStamp>) {
        let list = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input))
        guard let first = list.first, first.mDataByteSize > 0, let data = first.mData else { return }
        let channels = list.count > 1 ? 1 : max(1, Int(first.mNumberChannels))
        let count = Int(first.mDataByteSize) / MemoryLayout<Float>.size
        let frames = count / channels
        let nanos = time.pointee.mFlags.contains(.hostTimeValid)
            ? AudioConvertHostTimeToNanos(time.pointee.mHostTime)
            : AudioConvertHostTimeToNanos(AudioGetCurrentHostTime())
        var loudest: Float = 0
        vDSP_maxmgv(data.assumingMemoryBound(to: Float.self), 1, &loudest, vDSP_Length(count))
        if loudest > 0 { lastSound.store(nanos, ordering: .relaxed) }
        if loudest > 1e-4 { lastHeard.store(nanos, ordering: .relaxed) }
        sound.hear(UnsafeMutablePointer(mutating: input), frames: frames, at: Double(nanos) / 1e9 + latency)
    }
}

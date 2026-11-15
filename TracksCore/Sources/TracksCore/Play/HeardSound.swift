import CoreAudio
import Synchronization

/// One song's sound, or Apple Music's: the last few seconds of it, both channels mixed to one,
/// and where in the song they are.
///
/// Written on the audio thread without allocating, locking or waiting: the samples go into a
/// ring, then how far it's written is published, so a frame being drawn reads a moment that's
/// all there, and finds out if it was written over while it copied. Only ``prepare(_:)``,
/// before any sound arrives, swaps the ring, and it waits for a copy in progress to finish.
public nonisolated final class HeardSound: @unchecked Sendable {
    private struct Ring: @unchecked Sendable {
        let samples: UnsafeMutablePointer<Float>
        let capacity: Int
        let sampleRate: Double
        let channels: Int
        let isInterleaved: Bool
        let isFloat: Bool
    }

    /// What a frame reads with, swapped only by `prepare`.
    private let reading = Mutex<Ring?>(nil)
    /// The same ring, for the audio thread, which never waits for it: set only by `prepare`,
    /// before sound arrives.
    private var writing: Ring?
    // Only the audio thread's, between preparing and the next.
    private var writtenSoFar = 0
    private var endSoFar: Double = 0
    private var runStartSoFar = 0

    /// Odd while the three below are being published.
    private let sequence = Atomic<UInt64>(0)
    /// Samples written, all told; the newest is at `written - 1`.
    private let written = Atomic<Int>(0)
    /// The song's time just after the newest sample, as its bits.
    private let end = Atomic<UInt64>(0)
    /// The first sample of the current run: before it, the song was somewhere else.
    private let runStart = Atomic<Int>(0)

    public init() {}

    deinit {
        writing?.samples.deallocate()
    }

    public var sampleRate: Double? {
        reading.withLock { $0?.sampleRate }
    }

    /// Sets aside about three seconds: enough for what's handed over ahead of the speakers,
    /// and AirPlay's longer wait. Never while sound is arriving.
    public func prepare(_ format: AudioStreamBasicDescription) {
        let rate = format.mSampleRate > 0 ? format.mSampleRate : 44_100
        var capacity = 1 << 16
        while Double(capacity) < rate * 3 { capacity <<= 1 }
        let samples = UnsafeMutablePointer<Float>.allocate(capacity: capacity)
        samples.initialize(repeating: 0, count: capacity)
        let ring = Ring(
            samples: samples,
            capacity: capacity,
            sampleRate: rate,
            channels: max(1, Int(format.mChannelsPerFrame)),
            isInterleaved: format.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0,
            isFloat: format.mFormatFlags & kAudioFormatFlagIsFloat != 0 && format.mBitsPerChannel == 32
        )
        let old = writing
        writing = ring
        (writtenSoFar, endSoFar, runStartSoFar) = (0, 0, 0)
        publish()
        reading.withLock { $0 = ring }
        old?.samples.deallocate()
    }

    /// Copies a moment of sound in, its channels mixed to one, timed in seconds by the clock
    /// `copy(around:into:)` is asked with; nil carries on from the last. On the audio thread.
    public func hear(_ buffers: UnsafeMutablePointer<AudioBufferList>, frames: Int, at start: Double?) {
        guard frames > 0, let ring = writing, ring.isFloat, frames <= ring.capacity else { return }
        let list = UnsafeMutableAudioBufferListPointer(buffers)
        let time = start ?? endSoFar
        // A seek, or the first sound after a while unheard: a new run.
        if abs(time - endSoFar) * ring.sampleRate > 2 {
            runStartSoFar = writtenSoFar
        }
        let out = ring.samples
        let mask = ring.capacity - 1
        let channels = ring.channels
        let written = writtenSoFar
        // Plain loops over pointers: this runs for every buffer of sound, even in a Debug
        // build, where a range's iterator costs more than the copying.
        if ring.isInterleaved, let data = list.first?.mData {
            let input = data.assumingMemoryBound(to: Float.self)
            let share = 1 / Float(channels)
            var frame = 0
            while frame < frames {
                var sum: Float = 0
                var channel = 0
                while channel < channels {
                    sum += input[frame * channels + channel]
                    channel += 1
                }
                out[(written + frame) & mask] = sum * share
                frame += 1
            }
        } else {
            let present = list.reduce(0) { $0 + ($1.mData == nil ? 0 : 1) }
            let share = 1 / Float(max(1, present))
            var isFirst = true
            for buffer in list {
                guard let data = buffer.mData else { continue }
                let input = data.assumingMemoryBound(to: Float.self)
                var frame = 0
                while frame < frames {
                    let index = (written + frame) & mask
                    out[index] = (isFirst ? 0 : out[index]) + input[frame] * share
                    frame += 1
                }
                isFirst = false
            }
            if isFirst {
                var frame = 0
                while frame < frames {
                    out[(written + frame) & mask] = 0
                    frame += 1
                }
            }
        }
        writtenSoFar += frames
        endSoFar = time + Double(frames) / ring.sampleRate
        publish()
    }

    private func publish() {
        let next = sequence.load(ordering: .relaxed) &+ 1
        sequence.store(next, ordering: .relaxed)
        atomicMemoryFence(ordering: .releasing)
        written.store(writtenSoFar, ordering: .relaxed)
        end.store(endSoFar.bitPattern, ordering: .relaxed)
        runStart.store(runStartSoFar, ordering: .relaxed)
        sequence.store(next &+ 1, ordering: .releasing)
    }

    private func published() -> (written: Int, end: Double, runStart: Int)? {
        for _ in 0..<4 {
            let before = sequence.load(ordering: .acquiring)
            guard before & 1 == 0 else { continue }
            let found = (
                written.load(ordering: .relaxed),
                Double(bitPattern: end.load(ordering: .relaxed)),
                runStart.load(ordering: .relaxed)
            )
            atomicMemoryFence(ordering: .acquiring)
            if sequence.load(ordering: .relaxed) == before { return found }
        }
        return nil
    }

    /// Fills `window` with the sound around `time` in the song, and says the song's time at
    /// its centre. Nil if that moment isn't here: not yet handed over, long gone, from before
    /// a seek, or written over while it was copied.
    public func copy(around time: Double, into window: inout [Float]) -> Double? {
        reading.withLock { ring -> Double? in
            guard let ring, let (written, end, runStart) = published(), written > 0 else { return nil }
            let rate = ring.sampleRate
            let size = window.count
            let capacity = ring.capacity
            guard size <= capacity else { return nil }
            let oldest = max(runStart, written - capacity)
            // The sample being heard, then the window around it, slid back to what's arrived.
            let heard = written - Int(((end - time) * rate).rounded())
            var first = min(heard - size / 2, written - size)
            if first < oldest {
                // Over a quarter of a second off, it's not this moment any more.
                guard oldest - first < Int(rate / 4), written - oldest >= size else { return nil }
                first = oldest
            }
            guard heard > oldest - Int(rate / 4), heard < written + Int(rate / 4) else { return nil }
            let mask = capacity - 1
            // In at most two runs, as the ring wraps: copied whole, since this is asked for
            // every frame something moves with the music.
            let start = first & mask
            let head = min(size, capacity - start)
            window.withUnsafeMutableBufferPointer { out in
                guard let to = out.baseAddress else { return }
                to.update(from: ring.samples + start, count: head)
                if head < size { (to + head).update(from: ring.samples, count: size - head) }
            }
            // The audio thread carries on meanwhile; had it come round to this moment again,
            // what was copied is partly newer sound.
            atomicMemoryFence(ordering: .acquiring)
            guard self.written.load(ordering: .relaxed) - first <= capacity else { return nil }
            return end - Double(written - (first + size / 2)) / rate
        }
    }
}

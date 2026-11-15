import CoreAudio
import Foundation
import Synchronization
import Testing
@testable import TracksCore

@Suite("Heard sound")
struct HeardSoundTests {
    static let rate = 48_000.0

    static func format(channels: UInt32 = 2, interleaved: Bool = true, float: Bool = true) -> AudioStreamBasicDescription {
        var flags = kAudioFormatFlagIsPacked
        if float { flags |= kAudioFormatFlagIsFloat }
        if !interleaved { flags |= kAudioFormatFlagIsNonInterleaved }
        let bytes = UInt32(MemoryLayout<Float>.size) * (interleaved ? channels : 1)
        return AudioStreamBasicDescription(
            mSampleRate: rate, mFormatID: kAudioFormatLinearPCM, mFormatFlags: flags,
            mBytesPerPacket: bytes, mFramesPerPacket: 1, mBytesPerFrame: bytes,
            mChannelsPerFrame: channels, mBitsPerChannel: 32, mReserved: 0
        )
    }

    static func hear(_ sound: HeardSound, left: [Float], right: [Float], at time: Double?) {
        var interleaved = zip(left, right).flatMap { [$0, $1] }
        interleaved.withUnsafeMutableBytes { bytes in
            var list = AudioBufferList(mNumberBuffers: 1, mBuffers: AudioBuffer(mNumberChannels: 2, mDataByteSize: UInt32(bytes.count), mData: bytes.baseAddress))
            sound.hear(&list, frames: left.count, at: time)
        }
    }

    static func hearRamp(_ sound: HeardSound, from first: Int, count: Int, at time: Double?) {
        let ramp = (first..<first + count).map(Float.init)
        hear(sound, left: ramp, right: ramp, at: time)
    }

    static func prepared(_ format: AudioStreamBasicDescription = Self.format()) -> HeardSound {
        let sound = HeardSound()
        sound.prepare(format)
        return sound
    }

    @Test("Nothing heard yet, nothing to copy")
    func empty() {
        let sound = HeardSound()
        var window = [Float](repeating: 0, count: 1024)
        #expect(sound.sampleRate == nil)
        #expect(sound.copy(around: 0, into: &window) == nil)
        sound.prepare(Self.format())
        #expect(sound.sampleRate == Self.rate)
        #expect(sound.copy(around: 0, into: &window) == nil)
    }

    @Test("The moment asked for, centred, with the time at its centre")
    func centred() throws {
        let sound = Self.prepared()
        for block in 0..<20 {
            Self.hearRamp(sound, from: block * 512, count: 512, at: Double(block * 512) / Self.rate)
        }
        var window = [Float](repeating: 0, count: 1024)
        let asked = 4000 / Self.rate
        let centre = try #require(sound.copy(around: asked, into: &window))
        #expect(window[512] == 4000)
        #expect(window.first == 3488)
        #expect(zip(window, window.dropFirst()).allSatisfy { $1 - $0 == 1 })
        #expect(abs(centre - asked) < 1 / Self.rate)
    }

    @Test("A moment still arriving slides back to what's here")
    func slidesBack() throws {
        let sound = Self.prepared()
        Self.hearRamp(sound, from: 0, count: 4096, at: 0)
        var window = [Float](repeating: 0, count: 1024)
        let centre = try #require(sound.copy(around: 4090 / Self.rate, into: &window))
        #expect(window.last == 4095)
        #expect(abs(centre - 3584 / Self.rate) < 1 / Self.rate)
    }

    @Test("Two channels, interleaved or apart, mixed to one")
    func mixesChannels() throws {
        let interleaved = Self.prepared()
        Self.hear(interleaved, left: Array(repeating: 1, count: 2048), right: Array(repeating: 0, count: 2048), at: 0)
        var window = [Float](repeating: -1, count: 1024)
        _ = try #require(interleaved.copy(around: 1024 / Self.rate, into: &window))
        #expect(window.allSatisfy { $0 == 0.5 })

        let apart = Self.prepared(Self.format(interleaved: false))
        var left = [Float](repeating: 1, count: 2048)
        var right = [Float](repeating: 0.5, count: 2048)
        left.withUnsafeMutableBytes { leftBytes in
            right.withUnsafeMutableBytes { rightBytes in
                let list = AudioBufferList.allocate(maximumBuffers: 2)
                defer { free(list.unsafeMutablePointer) }
                list[0] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(leftBytes.count), mData: leftBytes.baseAddress)
                list[1] = AudioBuffer(mNumberChannels: 1, mDataByteSize: UInt32(rightBytes.count), mData: rightBytes.baseAddress)
                apart.hear(list.unsafeMutablePointer, frames: 2048, at: 0)
            }
        }
        window = [Float](repeating: -1, count: 1024)
        _ = try #require(apart.copy(around: 1024 / Self.rate, into: &window))
        #expect(window.allSatisfy { $0 == 0.75 })
    }

    @Test("Only 32-bit float sound is kept")
    func ignoresIntegers() {
        let sound = Self.prepared(Self.format(float: false))
        Self.hearRamp(sound, from: 0, count: 4096, at: 0)
        var window = [Float](repeating: 0, count: 1024)
        #expect(sound.copy(around: 2048 / Self.rate, into: &window) == nil)
    }

    @Test("After a seek, the moment before it is gone and nothing straddles it")
    func seek() throws {
        let sound = Self.prepared()
        Self.hearRamp(sound, from: 0, count: 8192, at: 0)
        Self.hearRamp(sound, from: 100_000, count: 8192, at: 60)
        var window = [Float](repeating: 0, count: 1024)
        #expect(sound.copy(around: 4096 / Self.rate, into: &window) == nil)
        _ = try #require(sound.copy(around: 60 + 100 / Self.rate, into: &window))
        #expect(window.first == 100_000)
        #expect(zip(window, window.dropFirst()).allSatisfy { $1 - $0 == 1 })
    }

    @Test("Untimed sound carries on from the last")
    func untimed() throws {
        let sound = Self.prepared()
        Self.hearRamp(sound, from: 0, count: 4096, at: 10)
        Self.hearRamp(sound, from: 4096, count: 4096, at: nil)
        var window = [Float](repeating: 0, count: 1024)
        _ = try #require(sound.copy(around: 10 + 4096 / Self.rate, into: &window))
        #expect(window[512] == 4096)
    }

    @Test("Long gone or not yet come, there's nothing")
    func outOfReach() {
        let sound = Self.prepared()
        let block = 4096
        for index in 0..<100 {
            Self.hearRamp(sound, from: index * block, count: block, at: Double(index * block) / Self.rate)
        }
        let end = Double(100 * block) / Self.rate
        var window = [Float](repeating: 0, count: 1024)
        #expect(sound.copy(around: 1, into: &window) == nil)
        #expect(sound.copy(around: end + 0.5, into: &window) == nil)
        #expect(sound.copy(around: end - 0.5, into: &window) != nil)
    }

    @Test("Read across the ring's wrap, it comes out whole")
    func wraps() throws {
        let sound = Self.prepared()
        let block = 1000
        var first = 0
        while first < 1_000_000 {
            Self.hearRamp(sound, from: first, count: block, at: Double(first) / Self.rate)
            first += block
        }
        var window = [Float](repeating: 0, count: 4096)
        for back in stride(from: 0.05, to: 2.5, by: 0.05) {
            _ = try #require(sound.copy(around: Double(first) / Self.rate - back, into: &window))
            #expect(zip(window, window.dropFirst()).allSatisfy { $1 - $0 == 1 })
        }
    }

    @Test("Prepared again, it starts afresh")
    func preparedAgain() {
        let sound = Self.prepared()
        Self.hearRamp(sound, from: 0, count: 8192, at: 0)
        sound.prepare(Self.format())
        var window = [Float](repeating: 0, count: 1024)
        #expect(sound.copy(around: 4096 / Self.rate, into: &window) == nil)
    }

    @Test("Read while it's written, a moment is never torn", .timeLimit(.minutes(1)))
    func concurrent() async {
        let sound = Self.prepared()
        let latest = Atomic<UInt64>(0)
        let isDone = Atomic<Bool>(false)
        let block = 256
        let writer = Thread {
            var first = 0
            // About ninety seconds of sound, rising in whole numbers a Float keeps exactly.
            while first + block < 1 << 22 {
                Self.hearRamp(sound, from: first, count: block, at: Double(first) / Self.rate)
                first += block
                latest.store((Double(first) / Self.rate).bitPattern, ordering: .releasing)
            }
            isDone.store(true, ordering: .releasing)
        }
        writer.start()
        var window = [Float](repeating: 0, count: 4096)
        var read = 0
        var torn = 0
        while !isDone.load(ordering: .acquiring) {
            let now = Double(bitPattern: latest.load(ordering: .acquiring))
            guard now > 0.2 else { continue }
            if sound.copy(around: now - 0.04, into: &window) != nil {
                read += 1
                if !zip(window, window.dropFirst()).allSatisfy({ $1 - $0 == 1 }) { torn += 1 }
            }
        }
        #expect(read > 100)
        #expect(torn == 0)
    }
}

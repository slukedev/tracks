package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.wav.WavExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A song of [frames] frames whose every sample is its frame number scaled into -1..1, so any
 * byte of the WAV says which frame it came from. Seeks land [seekSlopMs] early (or late, when
 * negative), as a decoder landing on a packet boundary does.
 */
private class RampSource(
    val frames: Int,
    override val sampleRate: Int = 1000,
    override val channels: Int = 2,
    private val seekSlopMs: Long = 0,
    /** Frames actually decodable: fewer than the length claims, or more. */
    private val decodable: Int = frames,
    private val failOnRead: IOException? = null,
) : PcmSource {
    override val durationMs: Long = frames * 1000L / sampleRate
    var at = 0
    val seeks = mutableListOf<Long>()
    var closed = false

    override fun readPcm16(maxFrames: Int): ByteArray {
        failOnRead?.let { throw it }
        val n = minOf(maxFrames, decodable - at).coerceAtLeast(0)
        val out = ByteArray(n * channels * 2)
        for (i in 0 until n * channels) {
            val s = pcm16(at + i / channels).toInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        at += n
        return out
    }

    override fun seek(positionMs: Long): Long {
        seeks += positionMs
        val landed = (positionMs - seekSlopMs).coerceAtLeast(0)
        at = (landed * sampleRate / 1000).toInt()
        return landed
    }

    override fun close() {
        closed = true
    }

    companion object {
        fun pcm16(frame: Int): Short = frame.toShort()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PcmWavDataSourceTest {
    private val uri = Uri.parse("spotify:track:ramp")

    private fun open(source: PcmSource, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): Pair<PcmWavDataSource, Long> {
        val data = PcmWavDataSource { source }
        val opened = data.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
        return data to opened
    }

    private fun PcmWavDataSource.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(3001)
        while (true) {
            val n = read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun ByteArray.short(at: Int): Short = ((this[at].toInt() and 0xFF) or (this[at + 1].toInt() shl 8)).toShort()
    private fun ByteArray.int(at: Int): Long = (0..3).sumOf { (this[at + it].toLong() and 0xFF) shl (8 * it) }

    @Test fun theHeaderDescribes16BitPcmOfTheSongsLength() {
        val (data, length) = open(RampSource(frames = 2000))
        val bytes = data.readAll()
        assertEquals(44L + 2000 * 4, length)
        assertEquals(length.toInt(), bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(36L + 8000, bytes.int(4))
        assertEquals(1, bytes.short(20).toInt())
        assertEquals(2, bytes.short(22).toInt())
        assertEquals(1000L, bytes.int(24))
        assertEquals(4000L, bytes.int(28))
        assertEquals(16, bytes.short(34).toInt())
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(8000L, bytes.int(40))
    }

    @Test fun samplesArriveInOrderAsLittleEndian16Bit() {
        val (data, _) = open(RampSource(frames = 9000, sampleRate = 3000))
        val bytes = data.readAll()
        for (frame in listOf(0, 1, 4095, 4096, 8999)) {
            val at = 44 + frame * 4
            assertEquals(RampSource.pcm16(frame), bytes.short(at))
            assertEquals("both channels", RampSource.pcm16(frame), bytes.short(at + 2))
        }
    }

    @Test fun media3sWavExtractorRecognisesIt() {
        val (data, length) = open(RampSource(frames = 500))
        assertTrue(WavExtractor().sniff(DefaultExtractorInput(data, 0, length)))
    }

    @Test fun aSeekByByteOffsetStartsAtThatFrame() {
        val source = RampSource(frames = 4000, seekSlopMs = 0)
        val frame = 2500
        val (data, length) = open(source, position = 44L + frame * 4)
        assertEquals(44L + 4000 * 4 - (44L + frame * 4), length)
        assertEquals(listOf(2500L), source.seeks)
        val bytes = data.readAll()
        assertEquals(RampSource.pcm16(frame), bytes.short(0))
        assertEquals(RampSource.pcm16(3999), bytes.short(bytes.size - 4))
    }

    @Test fun aSeekThatLandsEarlyDropsFramesUntilTheRightOne() {
        // 44.1 kHz frames don't fall on whole milliseconds, and decoders land on packet starts.
        val source = RampSource(frames = 44_100, sampleRate = 44_100, seekSlopMs = 20)
        val frame = 30_001
        val (data, _) = open(source, position = 44L + frame * 4)
        assertEquals(listOf(30_001L * 1000 / 44_100), source.seeks)
        val bytes = data.readAll()
        assertEquals(RampSource.pcm16(frame), bytes.short(0))
        assertEquals((44_100 - frame) * 4, bytes.size)
    }

    @Test fun aSeekThatLandsLateIsMadeUpWithSilence() {
        val source = RampSource(frames = 4000, seekSlopMs = -3)
        val (data, _) = open(source, position = 44L + 1000 * 4)
        val bytes = data.readAll()
        assertArrayEquals(ByteArray(12), bytes.copyOfRange(0, 12))
        assertEquals(RampSource.pcm16(1003), bytes.short(12))
        assertEquals(3000 * 4, bytes.size)
    }

    @Test fun anOffsetPartWayIntoAFrameStartsPartWayIntoIt() {
        val source = RampSource(frames = 4000)
        val (data, _) = open(source, position = 44L + 1000 * 4 + 2)
        val bytes = data.readAll()
        assertEquals("the right channel of frame 1000", RampSource.pcm16(1000), bytes.short(0))
        assertEquals(RampSource.pcm16(1001), bytes.short(2))
    }

    @Test fun anOffsetInsideTheHeaderReadsTheRestOfIt() {
        val source = RampSource(frames = 100)
        val (data, length) = open(source, position = 40)
        val bytes = data.readAll()
        assertEquals(4L + 400, length)
        assertEquals(400L, bytes.int(0))
        assertEquals(RampSource.pcm16(0), bytes.short(4))
        assertTrue("no seek for the start", source.seeks.isEmpty())
    }

    @Test fun aBoundedRequestStopsAtItsLength() {
        val (data, length) = open(RampSource(frames = 1000), position = 44, length = 10)
        assertEquals(10L, length)
        assertEquals(10, data.readAll().size)
    }

    @Test fun aDecoderThatEndsEarlyIsPaddedToTheLengthPromised() {
        val (data, length) = open(RampSource(frames = 3000, decodable = 2000))
        val bytes = data.readAll()
        assertEquals(length.toInt(), bytes.size)
        assertEquals(RampSource.pcm16(1999), bytes.short(44 + 1999 * 4))
        assertArrayEquals(ByteArray(1000 * 4), bytes.copyOfRange(44 + 2000 * 4, bytes.size))
    }

    @Test fun aDecoderThatRunsLongIsCutAtTheLengthPromised() {
        val (data, length) = open(RampSource(frames = 1000, decodable = 1500))
        assertEquals(length.toInt(), data.readAll().size)
    }

    @Test fun aPositionPastTheEndIsOutOfRange() {
        try {
            open(RampSource(frames = 10), position = 44L + 41)
            fail("opened past the end")
        } catch (error: DataSourceException) {
            assertTrue(DataSourceException.isCausedByPositionOutOfRange(error))
        }
    }

    @Test fun readErrorsReachMedia3AndClosingClosesTheSource() {
        val source = RampSource(frames = 1000, failOnRead = IOException("dropped"))
        val (data, _) = open(source)
        val buffer = ByteArray(100)
        assertEquals(44, data.read(buffer, 0, 100))
        try {
            data.read(buffer, 0, 100)
            fail("read past a failure")
        } catch (error: IOException) {
            assertEquals("dropped", error.message)
        }
        data.close()
        assertTrue(source.closed)
    }

    @Test fun theFramingSumsAgree() {
        assertEquals(44_100L * 3, WavLayout.frames(3000, 44_100))
        assertEquals(1000L, WavLayout.msAtOrBefore(44_100, 44_100))
        assertEquals(22L, WavLayout.msAtOrBefore(1000, 44_100))
        assertTrue(WavLayout.frameAt(WavLayout.msAtOrBefore(1000, 44_100), 44_100) <= 1000)
        assertEquals(250L to 3, WavLayout.locate(44L + 250 * 4 + 3, channels = 2))
    }
}

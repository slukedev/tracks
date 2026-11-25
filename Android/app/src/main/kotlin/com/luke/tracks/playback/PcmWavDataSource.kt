package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.Closeable
import java.io.IOException
import kotlin.math.min

/**
 * Decoded audio a host pulls a block at a time, as interleaved little-endian 16-bit frames.
 * Reads block while audio downloads; callers are Media3's loading threads, never the audio one.
 */
interface PcmSource : Closeable {
    val sampleRate: Int
    val channels: Int
    val durationMs: Long

    /** Up to [maxFrames] frames; fewer only at the end, none once it has ended. */
    @Throws(IOException::class)
    fun readPcm16(maxFrames: Int): ByteArray

    /** Moves to [positionMs] and returns where it landed. */
    @Throws(IOException::class)
    fun seek(positionMs: Long): Long
}

/** Opens the audio at an address, or throws an [IOException] saying why it can't. */
fun interface PcmOpener {
    @Throws(IOException::class)
    fun open(uri: Uri): PcmSource
}

/**
 * The byte layout of a canonical 44-byte WAV holding 16-bit PCM, and the sums that map a byte
 * offset into it back to a frame. Media3's WavExtractor seeks by asking for a byte offset.
 */
object WavLayout {
    const val HEADER_BYTES = 44
    const val BYTES_PER_SAMPLE = 2

    fun frameBytes(channels: Int) = channels * BYTES_PER_SAMPLE

    fun frames(durationMs: Long, sampleRate: Int): Long = durationMs * sampleRate / 1000

    /** The last millisecond at or before [frame], so a seek there never lands past it. */
    fun msAtOrBefore(frame: Long, sampleRate: Int): Long = frame * 1000 / sampleRate

    fun frameAt(ms: Long, sampleRate: Int): Long = ms * sampleRate / 1000

    /** The frame a data offset falls in, and how many bytes into that frame it is. */
    fun locate(position: Long, channels: Int): Pair<Long, Int> {
        val data = position - HEADER_BYTES
        require(data >= 0) { "position $position is inside the header" }
        val bytes = frameBytes(channels)
        return data / bytes to (data % bytes).toInt()
    }

    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val size = dataBytes.coerceAtMost(0xFFFF_FFFFL - 36)
        val out = ByteArray(HEADER_BYTES)
        fun ascii(at: Int, text: String) = text.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun le(at: Int, value: Long, bytes: Int) = repeat(bytes) { out[at + it] = (value ushr (8 * it)).toByte() }
        ascii(0, "RIFF")
        le(4, 36 + size, 4)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le(16, 16, 4)
        le(20, 1, 2)
        le(22, channels.toLong(), 2)
        le(24, sampleRate.toLong(), 4)
        le(28, sampleRate.toLong() * frameBytes(channels), 4)
        le(32, frameBytes(channels).toLong(), 2)
        le(34, 8L * BYTES_PER_SAMPLE, 2)
        ascii(36, "data")
        le(40, size, 4)
        return out
    }
}

/**
 * Presents a [PcmSource] as a WAV file, so Media3 plays it like any other song: the same
 * renderers, audio focus, notification, Android Auto, speed and gapless joins. The file's
 * length comes from the song's duration; a decoder that ends early is padded with silence and
 * one that runs long is cut, so the length Media3 was told always holds.
 */
class PcmWavDataSource(private val opener: PcmOpener) : BaseDataSource(/* isNetwork = */ true) {
    private var source: PcmSource? = null
    private var uri: Uri? = null
    private var opened = false

    private var header = ByteArray(0)
    private var channels = 2
    private var totalBytes = 0L
    private var position = 0L
    private var remaining = 0L

    /** Bytes waiting to be handed out, from [pendingAt]: the decoder's own block, or [silence]. */
    private var pending = ByteArray(0)
    /** Zeros, never written to. */
    private var silence = ByteArray(0)
    private var pendingAt = 0
    private var pendingEnd = 0

    /** Frames to drop after a seek that landed early, or to make up with silence after one that landed late. */
    private var discardFrames = 0L
    private var silenceFrames = 0L
    /** Bytes of the next frame already behind the position asked for. */
    private var skipBytes = 0
    private var ended = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val pcm = opener.open(dataSpec.uri)
        source = pcm
        if (pcm.durationMs <= 0 || pcm.sampleRate <= 0 || pcm.channels <= 0) {
            throw DataSourceException("No audio", PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        channels = pcm.channels
        val frames = WavLayout.frames(pcm.durationMs, pcm.sampleRate)
        val dataBytes = frames * WavLayout.frameBytes(channels)
        header = WavLayout.header(pcm.sampleRate, channels, dataBytes)
        totalBytes = WavLayout.HEADER_BYTES + dataBytes
        if (dataSpec.position > totalBytes) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        remaining = totalBytes - position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = min(remaining, dataSpec.length)
        if (position > WavLayout.HEADER_BYTES) moveTo(pcm, position)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun moveTo(pcm: PcmSource, position: Long) {
        val (frame, into) = WavLayout.locate(position, channels)
        if (frame > 0) {
            val landed = WavLayout.frameAt(pcm.seek(WavLayout.msAtOrBefore(frame, pcm.sampleRate)), pcm.sampleRate)
            if (landed < frame) discardFrames = frame - landed else silenceFrames = landed - frame
        }
        // Part-way into a frame: the frame's leading bytes are skipped once it's converted.
        skipBytes = into
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = min(length.toLong(), remaining).toInt()
        val n = if (position < WavLayout.HEADER_BYTES) {
            val take = min(wanted, (WavLayout.HEADER_BYTES - position).toInt())
            System.arraycopy(header, position.toInt(), buffer, offset, take)
            take
        } else {
            if (pendingAt == pendingEnd) fill()
            val take = min(wanted, pendingEnd - pendingAt)
            System.arraycopy(pending, pendingAt, buffer, offset, take)
            pendingAt += take
            take
        }
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    /** Refills [pending] with the next block: silence owed, decoded audio, or silence past its end. */
    private fun fill() {
        val pcm = source ?: throw IOException("Not open")
        val frameBytes = WavLayout.frameBytes(channels)
        var from: Int
        var end: Int
        while (true) {
            from = 0
            if (silenceFrames > 0 || ended) {
                val owed = silenceFrames > 0
                val frames = if (owed) min(silenceFrames, BLOCK_FRAMES.toLong()).toInt() else BLOCK_FRAMES
                if (owed) silenceFrames -= frames
                end = frames * frameBytes
                if (silence.size < end) silence = ByteArray(BLOCK_FRAMES * frameBytes)
                pending = silence
                break
            }
            val block = pcm.readPcm16(BLOCK_FRAMES)
            if (block.isEmpty()) {
                ended = true
                continue
            }
            var count = block.size / frameBytes
            if (discardFrames > 0) {
                val drop = min(discardFrames, count.toLong()).toInt()
                discardFrames -= drop
                from = drop * frameBytes
                count -= drop
                if (count == 0) continue
            }
            pending = block
            end = from + count * frameBytes
            break
        }
        pendingAt = from + skipBytes.coerceAtMost(end - from)
        skipBytes = 0
        pendingEnd = end
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            source?.close()
        } finally {
            source = null
            pending = ByteArray(0)
            pendingAt = 0
            pendingEnd = 0
            discardFrames = 0
            silenceFrames = 0
            skipBytes = 0
            ended = false
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private companion object {
        /** About 93 ms at 44.1 kHz: small enough to start fast, large enough to keep calls few. */
        const val BLOCK_FRAMES = 4096
    }
}

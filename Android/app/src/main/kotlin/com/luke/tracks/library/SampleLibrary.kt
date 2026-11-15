package com.luke.tracks.library

import android.net.Uri
import com.luke.tracks.engine.Engine
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.luke.tracks.data.SampleData
import uniffi.tracks_engine.SourceFilter
import uniffi.tracks_engine.demoSongMetadata
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A library for sample data mode: the sample history's most played songs, each a few seconds
 * of a soft chord of its own, so every mix, the radio and the player work without real music.
 */
class SampleLibrary(private val engine: Engine, private val folder: File) {
    suspend fun tracks(count: Int = 40): List<LibraryTrack> {
        val chart = runCatching { engine.read { songChart(Engine.allTime, null, SourceFilter.ALL, count.toUInt()) } }.getOrNull()
            ?: return emptyList()
        return withContext(Dispatchers.IO) {
            folder.mkdirs()
            // Genres and years, so Tracks Radio has directions to try and decades to steer by.
            val details = demoSongMetadata(SampleData.SEED).associateBy { it.identity }
            chart.rows.map { row ->
                val song = row.song
                val file = File(folder, "${Integer.toHexString(song.identity.hashCode())}.wav")
                if (!file.exists() || file.length() == 0L) Tones.writeChord(file, seed = song.identity.hashCode())
                LibraryTrack(
                    song = PlayableSong(
                        mediaId = "sample:${song.identity}",
                        uri = Uri.fromFile(file),
                        title = song.title,
                        artistName = song.artistName,
                        albumTitle = song.albumTitle,
                        artworkUri = null,
                        durationMs = (Tones.SECONDS * 1000).toLong(),
                        identity = song.identity,
                        year = details[song.identity]?.releaseYear,
                        genre = details[song.identity]?.genre,
                    ),
                    source = LibrarySourceKind.Device,
                    albumKey = "sample:album:" + (song.albumTitle ?: song.identity),
                )
            }
        }
    }
}

/** Soft synthesized chords written as 16-bit mono WAV. */
object Tones {
    const val SECONDS = 8.0
    const val SAMPLE_RATE = 16_000

    /** Major and minor sevenths and sixths, in semitones above the root. */
    private val chords = listOf(
        intArrayOf(0, 4, 7, 11), intArrayOf(0, 3, 7, 10), intArrayOf(0, 4, 7, 9),
        intArrayOf(0, 5, 7, 12), intArrayOf(0, 3, 7, 12), intArrayOf(0, 4, 9, 14),
    )

    /** The samples, -1 to 1: the notes of a chord coming in one after another, then fading. */
    fun chord(seed: Int, seconds: Double = SECONDS, rate: Int = SAMPLE_RATE): FloatArray {
        val shape = chords[Math.floorMod(seed, chords.size)]
        val root = 196.0 * 2.0.pow(Math.floorMod(seed / 7, 12) / 12.0) // around G3
        val total = (seconds * rate).toInt()
        val out = FloatArray(total)
        shape.forEachIndexed { n, semitones ->
            val frequency = root * 2.0.pow(semitones / 12.0)
            val start = (n * 0.35 * rate).toInt()
            for (i in start until total) {
                val t = (i - start).toDouble() / rate
                val attack = min(1.0, t / 0.08)
                val decay = exp(-t / 3.2)
                // A touch of the octave for warmth, and a slow shimmer.
                val tone = sin(2 * PI * frequency * t) + 0.25 * sin(4 * PI * frequency * t) + 0.08 * sin(6 * PI * frequency * t + 0.3 * sin(2 * PI * 0.4 * t))
                out[i] += (tone * attack * decay * 0.16).toFloat()
            }
        }
        // A gentle fade at the end so there's no click between songs.
        val fade = (0.6 * rate).toInt()
        for (i in 0 until fade) out[total - 1 - i] *= i.toFloat() / fade
        return out
    }

    fun writeChord(file: File, seed: Int) {
        val samples = chord(seed)
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
        val temp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(temp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header(samples.size * 2))
            raf.write(data.array())
        }
        temp.renameTo(file)
    }

    fun header(dataBytes: Int, rate: Int = SAMPLE_RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes)
        return header.array()
    }
}

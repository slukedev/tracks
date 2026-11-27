package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.luke.tracks.applemusic.AppleMusicException
import com.luke.tracks.applemusic.AppleMusicProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceDataSourceTest {
    private val silence = object : PcmSource {
        override val sampleRate = 44_100
        override val channels = 2
        override val durationMs = 1000L
        override fun readPcm16(maxFrames: Int) = ByteArray(maxFrames * 4)
        override fun seek(positionMs: Long) = positionMs
        override fun close() = Unit
    }

    @Test fun appleMusicAddressesPlayAsWavAndTheRestGoToTheDefault() {
        val file = byteArrayOf(1, 2, 3)
        val opened = mutableListOf<Uri>()
        val source = SourceDataSource(ByteArrayDataSource(file)) { PcmOpener { opened += it; silence } }

        val stand = source.open(DataSpec(Uri.parse("applemusic:song:1613600188")))
        assertEquals(44L + 44_100 * 4, stand)
        assertEquals(listOf(Uri.parse("applemusic:song:1613600188")), opened)
        val header = ByteArray(4)
        source.read(header, 0, 4)
        assertEquals("RIFF", String(header))
        source.close()

        assertEquals(3L, source.open(DataSpec(Uri.parse("https://example.invalid/song.flac"))))
        assertEquals(1, opened.size)
        source.close()
    }

    @Test fun withoutAppleMusicItSaysSignIn() {
        val source = SourceDataSource(ByteArrayDataSource(byteArrayOf(0))) { null }
        try {
            source.open(DataSpec(Uri.parse("applemusic:song:1")))
            fail("opened without Apple Music")
        } catch (error: AppleMusicException) {
            assertEquals(AppleMusicProblem.SignedOut, error.problem)
        }
    }

    @Test fun onlyTheNetworkIsWorthRetrying() {
        val policy = SourceLoadErrorPolicy(retries = 6)
        fun delay(error: IOException) = policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(0, DataSpec(Uri.EMPTY), 0),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                error,
                1,
            ),
        )
        assertEquals(C.TIME_UNSET, delay(AppleMusicException(AppleMusicProblem.SignedOut)))
        assertTrue(delay(IOException("dropped")) >= 0)
    }
}

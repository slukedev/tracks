package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.luke.tracks.spotify.SpotifyPlaybackException
import com.luke.tracks.spotify.SpotifyProblem
import com.luke.tracks.spotify.SpotifyUris
import com.luke.tracks.spotify.spotifyProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        override fun read(maxFrames: Int) = FloatArray(maxFrames * 2)
        override fun seek(positionMs: Long) = positionMs
        override fun close() = Unit
    }

    @Test fun spotifyAddressesPlayAsWavAndTheRestGoToTheDefault() {
        val file = byteArrayOf(1, 2, 3)
        val opened = mutableListOf<Uri>()
        val source = SourceDataSource(ByteArrayDataSource(file)) { PcmOpener { opened += it; silence } }

        val spotify = source.open(DataSpec(Uri.parse("spotify:track:abc")))
        assertEquals(44L + 44_100 * 4, spotify)
        assertEquals(listOf(Uri.parse("spotify:track:abc")), opened)
        val header = ByteArray(4)
        source.read(header, 0, 4)
        assertEquals("RIFF", String(header))
        source.close()

        assertEquals(3L, source.open(DataSpec(Uri.parse("https://example.invalid/song.flac"))))
        assertEquals(1, opened.size)
        source.close()
    }

    @Test fun aBuildOrSessionWithoutSpotifySaysSignIn() {
        val source = SourceDataSource(ByteArrayDataSource(byteArrayOf(0))) { null }
        try {
            source.open(DataSpec(Uri.parse("spotify:track:abc")))
            fail("opened without Spotify")
        } catch (error: SpotifyPlaybackException) {
            assertEquals(SpotifyProblem.SignedOut, error.problem)
            assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, error.reason)
        }
    }

    @Test fun theProblemIsFoundThroughMedia3sWrapping() {
        val wrapped = IOException("load", RuntimeException("outer", SpotifyPlaybackException(SpotifyProblem.Refused)))
        assertEquals(SpotifyProblem.Refused, wrapped.spotifyProblem())
        assertNull(IOException("plain").spotifyProblem())
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
        assertEquals(C.TIME_UNSET, delay(SpotifyPlaybackException(SpotifyProblem.Refused)))
        assertEquals(C.TIME_UNSET, delay(SpotifyPlaybackException(SpotifyProblem.NeedsPremium)))
        assertEquals(C.TIME_UNSET, delay(SpotifyPlaybackException(SpotifyProblem.SignedOut)))
        assertTrue(delay(SpotifyPlaybackException(SpotifyProblem.Offline)) >= 0)
        assertTrue(delay(IOException("dropped")) >= 0)
    }

    @Test fun theWordsAreTheSpecsAndAccountWideOnesAreMarked() {
        assertEquals("Spotify Premium is needed to play here.", SpotifyProblem.NeedsPremium.message)
        assertEquals("Spotify can't play this account in Tracks.", SpotifyProblem.Refused.message)
        assertEquals("Can't reach Spotify. Check your connection.", SpotifyProblem.Offline.message)
        assertTrue(SpotifyProblem.Refused.accountWide)
        assertFalse(SpotifyProblem.Offline.accountWide)
        SpotifyProblem.entries.forEach { assertFalse("no em dashes: ${it.name}", it.message.contains('—')) }
    }

    @Test fun spotifySongsCarryAWavHintAndSayWhereTheyComeFrom() {
        val song = PlayableSong("spotify:track:abc", Uri.parse("spotify:track:abc"), "Song", "Artist", null, null, 1000, "id")
        val item = song.toMediaItem()
        assertEquals("audio/wav", item.localConfiguration?.mimeType)
        assertEquals("Spotify · Artist", item.mediaMetadata.subtitle)
        val stripped = item.buildUpon().setUri(null as Uri?).build()
        assertEquals("audio/wav", stripped.restored()?.localConfiguration?.mimeType)
        assertTrue(SpotifyUris.isSpotify(song.mediaId))
        assertEquals("https://open.spotify.com/album/xyz", SpotifyUris.webPage("spotify:album:xyz"))
        assertNull(SpotifyUris.webPage("https://example.invalid"))
    }
}

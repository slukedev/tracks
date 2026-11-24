package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.luke.tracks.spotify.SpotifyPlaybackException
import com.luke.tracks.spotify.SpotifyProblem
import com.luke.tracks.spotify.SpotifyUris
import com.luke.tracks.spotify.spotifyProblem

/**
 * Hands each address to what can read it: `spotify:` songs to their decoded audio, presented
 * as WAV, and everything else (files, servers, artwork) to [fallback].
 */
class SourceDataSource(
    private val fallback: DataSource,
    private val spotify: () -> PcmOpener?,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == SpotifyUris.SCHEME) {
            val opener = spotify() ?: throw SpotifyPlaybackException(SpotifyProblem.SignedOut)
            PcmWavDataSource(opener).also { pcm -> listeners.forEach(pcm::addTransferListener) }
        } else {
            fallback
        }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = current ?: return
        current = null
        source.close()
    }

    class Factory(private val fallback: DataSource.Factory, private val spotify: () -> PcmOpener?) : DataSource.Factory {
        override fun createDataSource(): DataSource = SourceDataSource(fallback.createDataSource(), spotify)
    }
}

/**
 * Media3's retries for a dropped connection, except where retrying can't help: Spotify saying
 * no to the account or the song.
 */
class SourceLoadErrorPolicy(retries: Int) : DefaultLoadErrorHandlingPolicy(retries) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val problem = loadErrorInfo.exception.spotifyProblem()
        if (problem != null && !problem.retryable) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

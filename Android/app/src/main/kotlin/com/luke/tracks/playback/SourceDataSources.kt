package com.luke.tracks.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.luke.tracks.applemusic.AppleMusicException
import com.luke.tracks.applemusic.AppleMusicProblem
import com.luke.tracks.applemusic.AppleMusicUris
import com.luke.tracks.applemusic.appleMusicProblem

/**
 * Hands each address to what can read it: `applemusic:` songs to their silent stand-in
 * ([com.luke.tracks.applemusic.AppleMusicPlayback]), presented as WAV, and everything else
 * (files, servers, artwork) to [fallback].
 */
class SourceDataSource(
    private val fallback: DataSource,
    private val appleMusic: () -> PcmOpener?,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = when (dataSpec.uri.scheme) {
            AppleMusicUris.SCHEME -> {
                val opener = appleMusic() ?: throw AppleMusicException(AppleMusicProblem.SignedOut)
                PcmWavDataSource(opener).also { pcm -> listeners.forEach(pcm::addTransferListener) }
            }
            else -> fallback
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

    class Factory(
        private val fallback: DataSource.Factory,
        private val appleMusic: () -> PcmOpener?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SourceDataSource(fallback.createDataSource(), appleMusic)
    }
}

/**
 * Media3's retries for a dropped connection, except where retrying can't help: MusicKit not
 * starting an Apple Music song.
 */
class SourceLoadErrorPolicy(retries: Int) : DefaultLoadErrorHandlingPolicy(retries) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (loadErrorInfo.exception.appleMusicProblem() != null) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

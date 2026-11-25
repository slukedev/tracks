package com.luke.tracks.spotify

import com.luke.tracks.playback.PcmSource
import uniffi.tracks_engine.Disposable
import uniffi.tracks_engine.SpotifyException
import uniffi.tracks_engine.SpotifyStreamInterface

/** What the app says for each of the engine's Spotify errors. */
fun Throwable.toSpotifyProblem(): SpotifyProblem = when (this) {
    is SpotifyException.NotPremium -> SpotifyProblem.NeedsPremium
    is SpotifyException.AudioKeyRefused -> SpotifyProblem.Refused
    is SpotifyException.Network -> SpotifyProblem.Offline
    is SpotifyException.RateLimited -> SpotifyProblem.Busy
    is SpotifyException.Auth -> SpotifyProblem.SignedOut
    is SpotifyException.Unavailable -> SpotifyProblem.Unavailable
    is SpotifyPlaybackException -> problem
    else -> SpotifyProblem.Unavailable
}

/** Runs an engine call, turning its errors into ones Media3 carries to the player. */
internal inline fun <T> spotifyCall(block: () -> T): T = try {
    block()
} catch (error: SpotifyPlaybackException) {
    throw error
} catch (error: Exception) {
    throw SpotifyPlaybackException(error.toSpotifyProblem(), error)
}

/** A song's decoded audio from the engine, for [com.luke.tracks.playback.PcmWavDataSource]. */
class SpotifyPcm(private val stream: SpotifyStreamInterface) : PcmSource {
    override val sampleRate: Int = stream.sampleRate().toInt()
    override val channels: Int = stream.channels().toInt()
    override val durationMs: Long = stream.durationMs().toLong()

    override fun readPcm16(maxFrames: Int): ByteArray =
        spotifyCall { stream.readPcm16(maxFrames.coerceAtLeast(1).toUInt()) }

    override fun seek(positionMs: Long): Long =
        spotifyCall { stream.seek(positionMs.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt()).toLong() }

    override fun close() {
        runCatching { stream.stop() }
        (stream as? Disposable)?.destroy()
    }
}

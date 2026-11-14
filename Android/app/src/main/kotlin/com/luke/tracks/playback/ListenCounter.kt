package com.luke.tracks.playback

import uniffi.tracks_engine.lastfmRequiredPlayback

/**
 * How long one play of a song has actually played, and whether that's enough to keep it:
 * Last.fm's rule (half the song or four minutes, for songs over 30 seconds), shared with the
 * Swift app through the engine. Time is added as it plays, so seeking ahead doesn't count.
 */
class ListenCounter(val durationMs: Long?, required: (Double?) -> Double? = ::lastfmRequiredPlayback) {
    /** How long it must play, or null when it never can (too short). Two minutes when the length isn't known. */
    val requiredMs: Long? = required(durationMs?.takeIf { it > 0 }?.let { it / 1000.0 })?.let { (it * 1000).toLong() }

    var listenedMs: Long = 0
        private set

    var kept: Boolean = false
        private set

    val progress: Float
        get() = requiredMs?.let { (listenedMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

    /** Adds time played; true exactly once, when the play first earns its keep. */
    fun add(ms: Long): Boolean {
        if (ms <= 0) return false
        listenedMs += ms
        val need = requiredMs ?: return false
        if (!kept && listenedMs >= need) {
            kept = true
            return true
        }
        return false
    }
}

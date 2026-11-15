package com.luke.tracks.playback

import uniffi.tracks_engine.lastfmRequiredPlayback

/**
 * How long one play of a song has actually played, and whether that's enough to keep it:
 * Last.fm's rule (half the song or four minutes, for songs over 30 seconds), shared with the
 * Swift app through the engine. Time is added as it plays, so seeking ahead doesn't count.
 */
class ListenCounter(durationMs: Long?, required: (Double?) -> Double? = ::lastfmRequiredPlayback) {
    /** The song's length, or null when it isn't known. */
    val durationMs: Long? = knownLength(durationMs)

    /** How long it must play, or null when it never can (too short). Two minutes when the length isn't known. */
    val requiredMs: Long? = required(this.durationMs?.let { it / 1000.0 })?.let { (it * 1000).toLong() }?.takeIf { it > 0 }

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

    companion object {
        /** Longer than any song: a length past this is a broken header, not a real one. */
        private const val LONGEST_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * A length that can be trusted, or null for unknown: Media3 says unknown as
         * C.TIME_UNSET, Bluetooth and some files as 0, and broken headers as absurd values.
         */
        fun knownLength(durationMs: Long?): Long? = durationMs?.takeIf { it in 1..LONGEST_MS }

        /** Whether a play of a song this long can ever be kept: not for songs of 30 seconds or less. */
        fun canKeep(durationMs: Long?): Boolean = ListenCounter(durationMs).requiredMs != null
    }
}

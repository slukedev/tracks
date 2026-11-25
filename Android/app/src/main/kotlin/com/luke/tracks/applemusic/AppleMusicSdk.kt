package com.luke.tracks.applemusic

import android.content.Intent

/**
 * Apple's MusicKit for Android SDK as the app uses it: sign-in, which runs through the Apple
 * Music app, and a player for catalog songs. Only builds with Apple's AARs have one
 * (`AppleMusicSdkFlavor`); everything else in Apple Music works without it.
 */
interface AppleMusicSdk {
    /** The sign-in screen, which hands over to the Apple Music app. */
    fun signInIntent(developerToken: String): Intent

    /** What the sign-in screen came back with. */
    fun signInResult(data: Intent?): SignInResult

    /** A player that asks [tokens] for the tokens it sends. */
    fun player(tokens: Tokens): AppleMusicPlayer

    /** The tokens MusicKit asks for on its own threads. */
    interface Tokens {
        fun developerToken(): String?
        fun userToken(): String?
    }
}

sealed interface SignInResult {
    data class Token(val musicUserToken: String) : SignInResult
    data object Cancelled : SignInResult
    data class Failed(val problem: AppleMusicProblem) : SignInResult
}

/**
 * MusicKit's player, one song at a time. Calls come on the main thread and so do the
 * listener's, as MusicKit's local controller has them.
 */
interface AppleMusicPlayer {
    /** Loads a catalog song and starts it at [positionMs], playing at once when [play]. */
    fun load(catalogId: String, positionMs: Long, play: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()
    fun release()

    var listener: Listener?

    interface Listener {
        /** Audio is coming out: loaded, buffered and playing. */
        fun onPlaying()

        /** Paused, whoever asked: Tracks, or the system taking audio focus. */
        fun onPaused()

        fun onError(problem: AppleMusicProblem)
    }
}

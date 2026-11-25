package com.luke.tracks.applemusic

import android.content.Context
import android.content.Intent
import com.apple.android.music.playback.controller.MediaPlayerController
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory
import com.apple.android.music.playback.model.MediaItemType
import com.apple.android.music.playback.model.MediaPlayerException
import com.apple.android.music.playback.model.PlaybackState
import com.apple.android.music.playback.model.PlayerQueueItem
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider
import com.apple.android.sdk.authentication.AuthenticationFactory
import com.apple.android.sdk.authentication.AuthenticationManager
import com.apple.android.sdk.authentication.TokenProvider
import com.luke.tracks.net.Logs

/**
 * Builds with Apple's MusicKit for Android AARs (1.1.4 / MusicKitAuth 1.1.5).
 *
 * Unverified: written against Apple's documentation and the forum threads about 1.1.4, never
 * run here. What's known to be fragile: a controller made before its tokens are ready throws, so
 * it's made on first use after sign-in; songs only in the library don't play from a catalog
 * queue, so only catalog ids come here; there's about 2 s before each song starts.
 */
object AppleMusicSdkFlavor {
    fun create(context: Context): AppleMusicSdk? {
        val loaded = runCatching {
            System.loadLibrary("c++_shared")
            System.loadLibrary("appleMusicSDK")
        }
        if (loaded.isFailure) {
            Logs.w("MusicKit's native libraries didn't load", loaded.exceptionOrNull())
            return null
        }
        return MusicKitSdk(context.applicationContext)
    }
}

private class MusicKitSdk(private val context: Context) : AppleMusicSdk {
    private val auth: AuthenticationManager by lazy { AuthenticationFactory.createAuthenticationManager(context) }

    override fun signInIntent(developerToken: String): Intent =
        auth.createIntentBuilder(developerToken)
            .setHideStartScreen(true)
            .build()

    override fun signInResult(data: Intent?): SignInResult {
        if (data == null) return SignInResult.Cancelled
        val result = auth.handleTokenResult(data)
        if (!result.isError) {
            return result.musicUserToken?.takeIf { it.isNotBlank() }?.let(SignInResult::Token) ?: SignInResult.Cancelled
        }
        // TokenError's names, as strings, so a renamed constant can't break the build.
        val error = result.error?.toString().orEmpty()
        Logs.w("Apple Music sign-in: $error")
        return when {
            error.contains("CANCEL", ignoreCase = true) -> SignInResult.Cancelled
            error.contains("SUBSCRIPTION", ignoreCase = true) -> SignInResult.Failed(AppleMusicProblem.NoSubscription)
            else -> SignInResult.Failed(AppleMusicProblem.Offline)
        }
    }

    override fun player(tokens: AppleMusicSdk.Tokens): AppleMusicPlayer = MusicKitPlayer(context, tokens)
}

private class MusicKitPlayer(context: Context, tokens: AppleMusicSdk.Tokens) : AppleMusicPlayer, MediaPlayerController.Listener {
    private val controller: MediaPlayerController = MediaPlayerControllerFactory.createLocalController(
        context,
        object : TokenProvider {
            override fun getDeveloperToken(): String = tokens.developerToken().orEmpty()
            override fun getUserToken(): String = tokens.userToken().orEmpty()
        },
    ).also { it.addListener(this) }

    override var listener: AppleMusicPlayer.Listener? = null

    /** Where to start the song being loaded, once MusicKit has it. */
    private var startAt = 0L

    override fun load(catalogId: String, positionMs: Long, play: Boolean) {
        startAt = positionMs
        val queue = CatalogPlaybackQueueItemProvider.Builder()
            .items(MediaItemType.SONG, catalogId)
            .build()
        controller.prepare(queue, play)
    }

    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun seekTo(positionMs: Long) = controller.seekToPosition(positionMs)

    override fun stop() = controller.stop()

    override fun release() {
        controller.removeListener(this)
        controller.release()
    }

    private fun report() {
        when (controller.playbackState) {
            PlaybackState.PLAYING -> if (!controller.isBuffering) listener?.onPlaying()
            PlaybackState.PAUSED -> listener?.onPaused()
            else -> Unit
        }
    }

    override fun onPlayerStateRestored(playerController: MediaPlayerController) = Unit

    override fun onPlaybackStateChanged(playerController: MediaPlayerController, previousState: Int, currentState: Int) = report()

    override fun onPlaybackStateUpdated(playerController: MediaPlayerController) = Unit

    override fun onBufferingStateChanged(playerController: MediaPlayerController, buffering: Boolean) = report()

    override fun onCurrentItemChanged(playerController: MediaPlayerController, previousItem: PlayerQueueItem?, currentItem: PlayerQueueItem?) {
        if (currentItem != null && startAt > 0) {
            controller.seekToPosition(startAt)
            startAt = 0
        }
    }

    override fun onItemEnded(playerController: MediaPlayerController, queueItem: PlayerQueueItem, endPosition: Long) = Unit

    override fun onMetadataUpdated(playerController: MediaPlayerController, currentItem: PlayerQueueItem) = Unit

    override fun onPlaybackQueueChanged(playerController: MediaPlayerController, playbackQueueItems: MutableList<PlayerQueueItem>) = Unit

    override fun onPlaybackQueueItemsAdded(playerController: MediaPlayerController, queueInsertionType: Int, containerType: Int, itemType: Int) = Unit

    override fun onPlaybackError(playerController: MediaPlayerController, error: MediaPlayerException) {
        Logs.w("MusicKit playback error", error)
        listener?.onError(
            when {
                error.message?.contains("subscription", ignoreCase = true) == true -> AppleMusicProblem.NoSubscription
                error.message?.contains("network", ignoreCase = true) == true -> AppleMusicProblem.Offline
                error.message?.contains("token", ignoreCase = true) == true -> AppleMusicProblem.SignedOut
                else -> AppleMusicProblem.Unavailable
            },
        )
    }

    override fun onPlaybackRepeatModeChanged(playerController: MediaPlayerController, currentRepeatMode: Int) = Unit

    override fun onPlaybackShuffleModeChanged(playerController: MediaPlayerController, currentShuffleMode: Int) = Unit
}

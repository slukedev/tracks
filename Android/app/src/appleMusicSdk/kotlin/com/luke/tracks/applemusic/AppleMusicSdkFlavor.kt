package com.luke.tracks.applemusic

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.apple.android.music.playback.controller.MediaPlayerController
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory
import com.apple.android.music.playback.model.MediaItemType
import com.apple.android.music.playback.model.MediaPlayerException
import com.apple.android.music.playback.model.PlaybackState
import com.apple.android.music.playback.model.PlayerQueueItem
import com.apple.android.music.playback.player.MediaPlayerContextFactory
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider
import com.apple.android.music.playback.queue.PlaybackModeContainer
import com.apple.android.music.playback.queue.PlaybackQueueItemProvider
import com.apple.android.music.playback.queue.PlaybackQueueItemsAdded
import com.apple.android.sdk.authentication.AuthenticationFactory
import com.apple.android.sdk.authentication.AuthenticationManager
import com.apple.android.sdk.authentication.TokenError
import com.apple.android.sdk.authentication.TokenProvider
import com.apple.android.sdk.authentication.TokenRefreshListener
import com.apple.android.sdk.authentication.TokenResult
import com.luke.tracks.net.Logs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Builds with Apple's MusicKit for Android: MediaPlayback 1.1.4 and MusicKitAuth 1.1.5, checked
 * against their classes. MusicKit's native code is arm64-v8a and armeabi-v7a only, so on other
 * ABIs (the x86_64 emulator) this returns null and Apple Music stays out.
 */
object AppleMusicSdkFlavor {
    fun create(context: Context): AppleMusicSdk? {
        val loaded = runCatching {
            // Apple's sample sets these so JavaCPP doesn't mistake MusicKit's native buffers for a leak.
            System.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "0")
            System.setProperty("org.bytedeco.javacpp.maxbytes", "0")
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
        return MusicKitMapping.signIn(auth.handleTokenResult(data))
    }

    override suspend fun refreshUserToken(tokens: AppleMusicSdk.Tokens): SignInResult = suspendCancellableCoroutine { done ->
        auth.refreshTokenAsync(
            MusicKitTokens(tokens),
            object : TokenRefreshListener {
                override fun onTokenRefreshed(musicUserToken: String?) {
                    val result = musicUserToken?.takeIf { it.isNotBlank() }?.let { SignInResult.Token(it, tokens.playbackStorefront()) }
                        ?: SignInResult.Failed(AppleMusicProblem.SignedOut)
                    if (done.isActive) done.resume(result)
                }

                override fun onTokenError(error: TokenError?) {
                    Logs.w("Apple Music token refresh: $error")
                    if (done.isActive) done.resume(MusicKitMapping.refreshFailed(error))
                }
            },
        )
    }

    override fun player(tokens: AppleMusicSdk.Tokens): AppleMusicPlayer {
        val provider = MusicKitTokens(tokens)
        // Callbacks on the main thread, where AppleMusicPlayback expects them.
        val controller = MediaPlayerControllerFactory.createLocalController(context, Handler(Looper.getMainLooper()), provider)
        return MusicKitPlayer(
            controller = controller,
            tokens = tokens,
            notifyTokens = { controller.playbackStoreConfiguration.notifyTokenChanged(context, provider) },
        )
    }

    override fun forget() {
        runCatching { MediaPlayerContextFactory.createPlayerContext(context).clearData() }
            .onFailure { Logs.w("MusicKit's data wasn't cleared", it) }
    }
}

/** What MusicKit asks for, on its own threads. Null while signed out, as MusicKit expects. */
internal class MusicKitTokens(private val tokens: AppleMusicSdk.Tokens) : TokenProvider {
    override fun getDeveloperToken(): String? = tokens.developerToken()
    override fun getUserToken(): String? = tokens.userToken()
    override fun getAppleMusicStoreFrontId(): String? = tokens.playbackStorefront()
}

/** MusicKit's results and errors as Tracks says them. */
internal object MusicKitMapping {
    fun signIn(result: TokenResult): SignInResult {
        if (!result.isError) {
            val token = result.musicUserToken?.takeIf { it.isNotBlank() } ?: return SignInResult.Failed(AppleMusicProblem.Offline)
            return SignInResult.Token(token, result.appleMusicStorefrontId?.takeIf { it.isNotBlank() })
        }
        Logs.w("Apple Music sign-in: ${result.error}")
        return when (result.error) {
            TokenError.USER_CANCELLED -> SignInResult.Cancelled
            TokenError.NO_SUBSCRIPTION, TokenError.SUBSCRIPTION_EXPIRED -> SignInResult.Failed(AppleMusicProblem.NoSubscription)
            else -> SignInResult.Failed(AppleMusicProblem.Offline)
        }
    }

    fun refreshFailed(error: TokenError?): SignInResult = when (error) {
        TokenError.NO_SUBSCRIPTION, TokenError.SUBSCRIPTION_EXPIRED -> SignInResult.Failed(AppleMusicProblem.NoSubscription)
        TokenError.TOKEN_FETCH_ERROR -> SignInResult.Failed(AppleMusicProblem.Offline)
        else -> SignInResult.Failed(AppleMusicProblem.SignedOut)
    }

    /** The problem behind a playback error, or null for one MusicKit retries by itself. */
    fun playbackProblem(type: Int): AppleMusicProblem? = when (type) {
        MediaPlayerException.TYPE_IO -> null
        MediaPlayerException.TYPE_FAILED_CONNECTION -> AppleMusicProblem.Offline
        MediaPlayerException.TYPE_USER_SUBSCRIPTION_INVALID -> AppleMusicProblem.NoSubscription
        MediaPlayerException.TYPE_MUSIC_USER_TOKEN_ERROR -> AppleMusicProblem.SignedOut
        MediaPlayerException.TYPE_PLAYBACK_LEASE -> AppleMusicProblem.OtherDevice
        MediaPlayerException.TYPE_EXPLICIT_VERIFY_AGE -> AppleMusicProblem.AgeRestricted
        else -> AppleMusicProblem.Unavailable
    }
}

/**
 * MusicKit's controller, one catalog song at a time. It reports playing only once audio is
 * coming out (playing and not buffering), and each change once. A start position is held until
 * MusicKit has the song and can seek, since a seek before that is dropped.
 */
internal class MusicKitPlayer(
    private val controller: MediaPlayerController,
    private val tokens: AppleMusicSdk.Tokens,
    private val notifyTokens: () -> Unit,
    private val queueFor: (catalogId: String) -> PlaybackQueueItemProvider = { id ->
        CatalogPlaybackQueueItemProvider.Builder().items(MediaItemType.SONG, id).build()
    },
) : AppleMusicPlayer, MediaPlayerController.Listener {
    override var listener: AppleMusicPlayer.Listener? = null

    private var released = false
    private var hasItem = false
    private var pendingSeek: Long? = null
    private var reported: Int? = null
    private var notified: Triple<String?, String?, String?>? = null

    init {
        controller.addListener(this)
        notifyIfChanged()
    }

    override fun load(catalogId: String, positionMs: Long, play: Boolean) {
        if (released) return
        notifyIfChanged()
        hasItem = false
        reported = null
        pendingSeek = positionMs.takeIf { it > 0 }
        controller.prepare(queueFor(catalogId), play)
    }

    override fun play() {
        if (!released) controller.play()
    }

    override fun pause() {
        if (!released) controller.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        if (!hasItem || !controller.canSeek()) {
            pendingSeek = positionMs
            return
        }
        pendingSeek = null
        controller.seekToPosition(positionMs)
    }

    override fun stop() {
        if (!released) controller.stop()
    }

    override fun tokensChanged() {
        if (released) return
        notified = null
        notifyIfChanged()
    }

    override fun release() {
        if (released) return
        released = true
        listener = null
        controller.removeListener(this)
        controller.release()
    }

    /** MusicKit keeps its own copy of the tokens; a new developer token from the server goes to it before the next song. */
    private fun notifyIfChanged() {
        val now = Triple(tokens.developerToken(), tokens.userToken(), tokens.playbackStorefront())
        if (now == notified) return
        notified = now
        runCatching(notifyTokens).onFailure { Logs.w("MusicKit didn't take the new tokens", it) }
    }

    private fun applyPendingSeek() {
        val target = pendingSeek ?: return
        if (!hasItem || !controller.canSeek()) return
        pendingSeek = null
        controller.seekToPosition(target)
    }

    private fun report() {
        if (released) return
        val state = controller.playbackState
        when {
            state == PlaybackState.PLAYING && !controller.isBuffering -> {
                // Playing before it could seek (live, or never seekable): it starts where it is.
                if (pendingSeek != null && hasItem && !controller.canSeek()) pendingSeek = null
                if (pendingSeek != null) return
                if (reported != PlaybackState.PLAYING) {
                    reported = PlaybackState.PLAYING
                    listener?.onPlaying()
                }
            }
            state == PlaybackState.PAUSED -> if (reported != PlaybackState.PAUSED) {
                reported = PlaybackState.PAUSED
                listener?.onPaused()
            }
            else -> Unit
        }
    }

    override fun onPlayerStateRestored(playerController: MediaPlayerController) = Unit

    override fun onPlaybackStateChanged(playerController: MediaPlayerController, previousState: Int, currentState: Int) {
        if (released) return
        if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED) applyPendingSeek()
        report()
    }

    override fun onPlaybackStateUpdated(playerController: MediaPlayerController) {
        if (released) return
        applyPendingSeek()
        report()
    }

    override fun onBufferingStateChanged(playerController: MediaPlayerController, buffering: Boolean) = report()

    override fun onCurrentItemChanged(playerController: MediaPlayerController, previousItem: PlayerQueueItem?, currentItem: PlayerQueueItem?) {
        if (released || currentItem == null) return
        hasItem = true
        applyPendingSeek()
        report()
    }

    override fun onItemEnded(playerController: MediaPlayerController, queueItem: PlayerQueueItem?, endPosition: Long) = Unit

    override fun onMetadataUpdated(playerController: MediaPlayerController, currentItem: PlayerQueueItem?) = Unit

    override fun onPlaybackQueueChanged(playerController: MediaPlayerController, playbackQueueItems: List<PlayerQueueItem>?) = Unit

    override fun onPlaybackQueueItemsAdded(playerController: MediaPlayerController, playbackQueueItemsAdded: PlaybackQueueItemsAdded?) = Unit

    override fun onPlaybackError(playerController: MediaPlayerController, error: MediaPlayerException?) {
        if (released) return
        val type = error?.type ?: MediaPlayerException.TYPE_UNKNOWN
        Logs.w("MusicKit playback error, type $type", error)
        val problem = MusicKitMapping.playbackProblem(type) ?: return
        listener?.onError(problem)
    }

    override fun onPlaybackModeChanged(playerController: MediaPlayerController, playbackMode: PlaybackModeContainer?) = Unit

    override fun onPlaybackRepeatModeChanged(playerController: MediaPlayerController, currentRepeatMode: Int) = Unit

    override fun onPlaybackShuffleModeChanged(playerController: MediaPlayerController, currentShuffleMode: Int) = Unit
}

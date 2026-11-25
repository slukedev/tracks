package com.luke.tracks.applemusic

import com.apple.android.music.playback.controller.MediaPlayerController
import com.apple.android.music.playback.model.MediaPlayerException
import com.apple.android.music.playback.model.PlaybackState
import com.apple.android.music.playback.model.PlayerQueueItem
import com.apple.android.music.playback.queue.PlaybackQueueItemProvider
import com.apple.android.sdk.authentication.TokenError
import com.apple.android.sdk.authentication.TokenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** A stand-in for MusicKit's controller: records what it's asked and answers from its fields. */
private class FakeController {
    var state = PlaybackState.STOPPED
    var buffering = false
    var canSeek = true
    var listener: MediaPlayerController.Listener? = null
    val calls = mutableListOf<String>()

    val controller: MediaPlayerController = proxy { name, args ->
        when (name) {
            "addListener" -> listener = args[0] as MediaPlayerController.Listener
            "removeListener" -> listener = null
            "getPlaybackState" -> return@proxy state
            "isBuffering" -> return@proxy buffering
            "canSeek" -> return@proxy canSeek
            "seekToPosition" -> calls += "seek ${args[0]}"
            "prepare" -> calls += "prepare play=${args.last()}"
            "play", "pause", "stop", "release" -> calls += name
        }
        null
    }

    fun changeState(to: Int) {
        val from = state
        state = to
        listener?.onPlaybackStateChanged(controller, from, to)
    }

    fun buffer(now: Boolean) {
        buffering = now
        listener?.onBufferingStateChanged(controller, now)
    }

    fun arrive() = listener?.onCurrentItemChanged(controller, null, item)

    fun fail(type: Int) = listener?.onPlaybackError(controller, MediaPlayerException(type, null))

    companion object {
        val item: PlayerQueueItem = proxy { _, _ -> null }
        val queue: PlaybackQueueItemProvider = proxy { _, _ -> null }
    }
}

private inline fun <reified T> proxy(crossinline answer: (name: String, args: Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.get(0)
            "toString" -> T::class.java.simpleName
            else -> answer(method.name, args ?: emptyArray()) ?: when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        }
    } as T

private class Heard : AppleMusicPlayer.Listener {
    val events = mutableListOf<String>()
    override fun onPlaying() {
        events += "playing"
    }
    override fun onPaused() {
        events += "paused"
    }
    override fun onError(problem: AppleMusicProblem) {
        events += "error $problem"
    }
}

private class Tokens(var developer: String? = "dev", var user: String? = "user", var storefront: String? = "143444-2,31") : AppleMusicSdk.Tokens {
    override fun developerToken() = developer
    override fun userToken() = user
    override fun playbackStorefront() = storefront
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MusicKitPlayerTest {
    private val fake = FakeController()
    private val tokens = Tokens()
    private val heard = Heard()
    private var notified = 0
    private val loaded = mutableListOf<String>()
    private val player = MusicKitPlayer(fake.controller, tokens, notifyTokens = { notified++ }, queueFor = { loaded += it; FakeController.queue })
        .also { it.listener = heard }

    @Test fun itListensToTheControllerAndHandsItTheTokens() {
        assertTrue(fake.listener === player)
        assertEquals(1, notified)
    }

    @Test fun playingIsReportedOnceAudioComesOut() {
        player.load("1613600188", positionMs = 0, play = true)
        assertEquals(listOf("1613600188"), loaded)
        assertEquals(listOf("prepare play=true"), fake.calls)

        fake.arrive()
        fake.buffer(true)
        fake.changeState(PlaybackState.PLAYING)
        assertEquals("buffering isn't playing", emptyList<String>(), heard.events)
        fake.buffer(false)
        fake.listener?.onPlaybackStateUpdated(fake.controller)
        fake.buffer(false)
        assertEquals(listOf("playing"), heard.events)

        fake.changeState(PlaybackState.PAUSED)
        fake.changeState(PlaybackState.PLAYING)
        assertEquals(listOf("playing", "paused", "playing"), heard.events)
    }

    @Test fun aStartPositionWaitsUntilMusicKitHasTheSong() {
        player.load("1", positionMs = 42_000, play = true)
        fake.changeState(PlaybackState.PLAYING)
        assertEquals("no seek, and no playing from the start", listOf("prepare play=true"), fake.calls)
        assertEquals(emptyList<String>(), heard.events)

        fake.arrive()
        assertEquals(listOf("prepare play=true", "seek 42000"), fake.calls)
        assertEquals(listOf("playing"), heard.events)
    }

    @Test fun aStartPositionWaitsUntilItCanSeek() {
        fake.canSeek = false
        player.load("1", positionMs = 30_000, play = false)
        fake.arrive()
        fake.changeState(PlaybackState.PAUSED)
        assertEquals(listOf("prepare play=false"), fake.calls)

        fake.canSeek = true
        fake.listener?.onPlaybackStateUpdated(fake.controller)
        assertEquals(listOf("prepare play=false", "seek 30000"), fake.calls)
    }

    @Test fun whatNeverSeeksStartsWhereItIs() {
        fake.canSeek = false
        player.load("1", positionMs = 30_000, play = true)
        fake.arrive()
        fake.changeState(PlaybackState.PLAYING)
        assertEquals(listOf("playing"), heard.events)
        assertEquals(listOf("prepare play=true"), fake.calls)
    }

    @Test fun aSeekWhileLoadingIsHeldForTheSong() {
        player.load("1", positionMs = 0, play = true)
        player.seekTo(10_000)
        assertEquals(listOf("prepare play=true"), fake.calls)
        fake.arrive()
        assertEquals(listOf("prepare play=true", "seek 10000"), fake.calls)
        player.seekTo(20_000)
        assertEquals("seek 20000", fake.calls.last())
    }

    @Test fun errorsSayWhatWentWrong() {
        player.load("1", 0, true)
        fake.fail(MediaPlayerException.TYPE_IO)
        assertEquals("MusicKit retries I/O errors itself", emptyList<String>(), heard.events)
        fake.fail(MediaPlayerException.TYPE_MUSIC_USER_TOKEN_ERROR)
        fake.fail(MediaPlayerException.TYPE_USER_SUBSCRIPTION_INVALID)
        fake.fail(MediaPlayerException.TYPE_PLAYBACK_LEASE)
        fake.fail(MediaPlayerException.TYPE_EXPLICIT_VERIFY_AGE)
        fake.fail(MediaPlayerException.TYPE_FAILED_CONNECTION)
        fake.fail(MediaPlayerException.TYPE_DRM)
        assertEquals(
            listOf(
                "error SignedOut",
                "error NoSubscription",
                "error OtherDevice",
                "error AgeRestricted",
                "error Offline",
                "error Unavailable",
            ),
            heard.events,
        )
    }

    @Test fun newTokensGoToMusicKitBeforeTheNextSong() {
        player.load("1", 0, true)
        assertEquals("unchanged, not sent again", 1, notified)
        tokens.developer = "rotated"
        player.load("2", 0, true)
        assertEquals(2, notified)
        player.tokensChanged()
        assertEquals("asked to, it sends them even if they look the same", 3, notified)
    }

    @Test fun releasedItHearsNothingMore() {
        player.release()
        assertNull(fake.listener)
        assertEquals(listOf("release"), fake.calls)
        player.play()
        player.load("1", 0, true)
        player.release()
        assertEquals(listOf("release"), fake.calls)
    }

    @Test fun signInResultsMapToTracksTerms() {
        val signedIn = TokenResult("music-user-token").apply { e = "143444-2,31" }
        assertEquals(SignInResult.Token("music-user-token", "143444-2,31"), MusicKitMapping.signIn(signedIn))
        assertEquals(SignInResult.Cancelled, MusicKitMapping.signIn(TokenResult(TokenError.USER_CANCELLED)))
        assertEquals(SignInResult.Failed(AppleMusicProblem.NoSubscription), MusicKitMapping.signIn(TokenResult(TokenError.SUBSCRIPTION_EXPIRED)))
        assertEquals(SignInResult.Failed(AppleMusicProblem.Offline), MusicKitMapping.signIn(TokenResult(TokenError.TOKEN_FETCH_ERROR)))
        assertEquals(SignInResult.Failed(AppleMusicProblem.SignedOut), MusicKitMapping.refreshFailed(TokenError.UNKNOWN))
        assertEquals(SignInResult.Failed(AppleMusicProblem.NoSubscription), MusicKitMapping.refreshFailed(TokenError.NO_SUBSCRIPTION))
    }

    @Test fun musicKitReadsTheTokensAsTheyAreNow() {
        val provider = MusicKitTokens(tokens)
        assertEquals("dev", provider.developerToken)
        assertEquals("143444-2,31", provider.appleMusicStoreFrontId)
        tokens.user = null
        assertNull("signed out, MusicKit gets null", provider.userToken)
    }
}

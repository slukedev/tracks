package com.luke.tracks.applemusic

import android.content.Intent
import com.luke.tracks.sources.FakeSources
import com.luke.tracks.sources.PlayFromOrder
import com.luke.tracks.sources.PlayFromPlace
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.SourceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.concurrent.thread

class MemoryVault : AppleMusicVault {
    var token: String? = null
    override fun userToken() = token
    override fun save(userToken: String) {
        token = userToken
    }
    override var storefront: String? = null
    override var playbackStorefront: String? = null
    override fun forgetToken() {
        token = null
    }
    override fun clear() {
        token = null
        storefront = null
        playbackStorefront = null
    }
}

class FakeSdk(var result: SignInResult = SignInResult.Token("music-user-token")) : AppleMusicSdk {
    var opened = 0
    var refreshed: SignInResult = SignInResult.Failed(AppleMusicProblem.SignedOut)
    var refreshes = 0
    var forgotten = 0
    override fun signInIntent(developerToken: String): Intent {
        opened++
        return Intent("sign-in").putExtra("developerToken", developerToken)
    }
    override fun signInResult(data: Intent?) = result
    override suspend fun refreshUserToken(tokens: AppleMusicSdk.Tokens): SignInResult {
        refreshes++
        return refreshed
    }
    override fun player(tokens: AppleMusicSdk.Tokens): AppleMusicPlayer = error("not in these tests")
    override fun forget() {
        forgotten++
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppleMusicServiceTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val vault = MemoryVault()
    private val sdk = FakeSdk()
    private var installed = true
    private val now = System.currentTimeMillis() / 1000
    private lateinit var cache: File

    @Before fun start() {
        server.start()
        cache = File.createTempFile("apple-music", ".json").also { it.delete() }
    }

    @After fun stop() {
        server.close()
        scope.cancel()
        cache.delete()
    }

    private fun service(tokens: DeveloperTokenProvider = BuildTimeDeveloperToken(jwt(now, now + 86_400))): AppleMusicService {
        return AppleMusicService(
            tokens = tokens,
            vault = vault,
            sdk = sdk,
            scope = scope,
            appInstalled = { installed },
            identity = { title, artist -> "$title|$artist" },
            cache = cache,
            apiFor = { user -> AppleMusicApi(OkHttpClient(), tokens, user, server.url("/")) },
        )
    }

    private fun <T> await(flow: kotlinx.coroutines.flow.Flow<T>, until: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { flow.first(until) } }

    @Test fun signingInChecksTheAccountThenLoadsTheLibrary() = runBlocking {
        server.enqueue(MockResponse(body = fixture("storefront.json")))
        server.enqueue(MockResponse(body = fixture("library-songs.json")))
        server.enqueue(MockResponse(body = fixture("library-songs-last.json")))
        val music = service()
        assertEquals(AppleMusicState.SignedOut, music.state.value)

        val intent = music.beginSignIn()!!
        assertEquals(music.tokens.cached()!!.jwt, intent.getStringExtra("developerToken"))
        assertEquals(AppleMusicState.SigningIn, music.state.value)

        music.signInFinished(null as Intent?)
        assertEquals(AppleMusicState.Connected("gb"), await(music.state) { it is AppleMusicState.Connected })
        assertEquals("music-user-token", vault.token)
        assertEquals("gb", vault.storefront)

        val library = await(music.library) { it.isNotEmpty() }
        assertEquals("the upload can't play, so it isn't a copy", 3, library.size)
        assertEquals("applemusic:song:1622045954", library.first().song.mediaId)
        val deadline = System.currentTimeMillis() + 5_000
        while (!cache.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("kept for the next start", cache.exists())

        val source = AppleMusicSourceProvider.sources(music.state.value, library.size).single()
        assertEquals(SourceId.AppleMusic, source.id)
        assertEquals("Apple Music", source.name)
        assertEquals(SourceStatus.Connected, source.status)
    }

    @Test fun withoutTheAppSignInSaysToGetIt() = runBlocking {
        installed = false
        val music = service()
        assertNull(music.beginSignIn())
        assertEquals(AppleMusicState.Failed(AppleMusicProblem.NeedsApp), music.state.value)
        assertEquals(0, sdk.opened)
        music.dismissProblem()
        assertEquals(AppleMusicState.SignedOut, music.state.value)
    }

    @Test fun cancellingIsntAnErrorAndNoSubscriptionIs() = runBlocking {
        val music = service()
        sdk.result = SignInResult.Cancelled
        music.beginSignIn()
        music.signInFinished(null as Intent?)
        assertEquals(AppleMusicState.SignedOut, music.state.value)

        sdk.result = SignInResult.Failed(AppleMusicProblem.NoSubscription)
        music.beginSignIn()
        music.signInFinished(null as Intent?)
        assertEquals(AppleMusicState.Failed(AppleMusicProblem.NoSubscription), music.state.value)
        assertNull("nothing is kept", vault.token)
    }

    @Test fun aRefusedTokenKeepsTheSourceAskingToSignIn() = runBlocking {
        vault.token = "old"
        vault.storefront = "gb"
        server.enqueue(MockResponse(code = 403))
        val music = service()
        assertEquals(AppleMusicState.Connected("gb"), music.state.value)
        music.start()
        val state = await(music.state) { it is AppleMusicState.NeedsSignIn }
        assertEquals(AppleMusicState.NeedsSignIn(AppleMusicProblem.SignedOut), state)
        assertNull(vault.token)
        assertEquals(SourceStatus.SignIn, AppleMusicSourceProvider.sources(state).single().status)
    }

    @Test fun offlineAtStartStaysConnected() = runBlocking {
        vault.token = "token"
        vault.storefront = "gb"
        server.enqueue(MockResponse(code = 503))
        val music = service()
        music.start()
        Thread.sleep(200)
        assertEquals(AppleMusicState.Connected("gb"), music.state.value)
    }

    @Test fun signingOutForgetsEverything() = runBlocking {
        vault.token = "token"
        vault.storefront = "gb"
        val music = service()
        music.signOut()
        assertEquals(AppleMusicState.SignedOut, music.state.value)
        assertNull(vault.token)
        assertNull(vault.storefront)
        assertTrue(AppleMusicSourceProvider.sources(music.state.value).isEmpty())
        assertEquals("MusicKit's kept queue goes too", 1, sdk.forgotten)
    }

    @Test fun signingInKeepsTheStorefrontMusicKitPlaysFrom() = runBlocking {
        server.enqueue(MockResponse(body = fixture("storefront.json")))
        server.enqueue(MockResponse(body = fixture("library-songs-last.json")))
        sdk.result = SignInResult.Token("music-user-token", playbackStorefront = "143444-2,31")
        val music = service()
        music.beginSignIn()
        music.signInFinished(null as Intent?)
        await(music.state) { it is AppleMusicState.Connected }
        assertEquals("143444-2,31", vault.playbackStorefront)
    }

    @Test fun withoutADeveloperTokenAppleMusicIsStillOfferedAndSaysWhatsMissing() = runBlocking {
        val music = service(BuildTimeDeveloperToken(""))
        assertFalse(music.keyAvailable.value)
        assertTrue(AppleMusicSourceProvider(music, scope).offersAdd.value)
        assertNull(music.beginSignIn())
        assertEquals(AppleMusicState.Failed(AppleMusicProblem.NoKey), music.state.value)
        assertEquals(0, sdk.opened)
        music.findKey()
        assertEquals(AppleMusicState.SignedOut, music.state.value)
    }

    @Test fun aRefusedUserTokenIsRenewedBeforeAskingToSignInAgain() = runBlocking {
        vault.token = "old"
        vault.storefront = "gb"
        sdk.refreshed = SignInResult.Token("renewed")
        val music = service()
        music.playbackFailed(AppleMusicProblem.SignedOut)
        assertEquals(1, sdk.refreshes)
        assertEquals("renewed", vault.token)
        assertEquals(AppleMusicState.Connected("gb"), music.state.value)

        sdk.refreshed = SignInResult.Failed(AppleMusicProblem.SignedOut)
        music.playbackFailed(AppleMusicProblem.SignedOut)
        assertEquals(AppleMusicState.NeedsSignIn(AppleMusicProblem.SignedOut), await(music.state) { it is AppleMusicState.NeedsSignIn })
        assertNull(vault.token)
    }

    @Test fun aLapsedSubscriptionFoundWhileRenewingSaysSo() = runBlocking {
        vault.token = "old"
        vault.storefront = "gb"
        sdk.refreshed = SignInResult.Failed(AppleMusicProblem.NoSubscription)
        val music = service()
        music.playbackFailed(AppleMusicProblem.SignedOut)
        assertEquals(AppleMusicState.NeedsSignIn(AppleMusicProblem.NoSubscription), await(music.state) { it is AppleMusicState.NeedsSignIn })
        assertEquals("the token itself wasn't refused", "old", vault.token)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppleMusicSourcesTest {
    @Test fun appleMusicCanGoAnywhereInPlayFrom() {
        val here = listOf(SourceId.Device, SourceId.Server("s1"), SourceId.AppleMusic).map { PlayFromPlace.From(it) }
        val chosen = listOf(SourceId.AppleMusic, SourceId.Device, SourceId.Server("s1")).map { PlayFromPlace.From(it) }
        assertEquals(chosen, PlayFromOrder.Standard.reordered(chosen).arranged(here))
        assertFalse(PlayFromPlace.From(SourceId.AppleMusic) in PlayFromOrder(here).arranged(here - PlayFromPlace.From(SourceId.AppleMusic)))
    }

    @Test fun appleMusicSongsAreKnownByTheirMediaIdAndRankedInPlayFrom() {
        val apple = com.luke.tracks.sources.Source(SourceId.AppleMusic, "Apple Music", SourceKind.AppleMusic, SourceStatus.Connected)
        val registry = FakeSources.registry(FakeSources.device, FakeSources.home, apple, name = "apple-sources")
        assertEquals(SourceId.AppleMusic, registry.sourceOf("applemusic:song:1613600188"))
        val copies = listOf("applemusic:song:1", "subsonic:s1:b", "local:7")
        assertEquals(listOf("local:7", "subsonic:s1:b", "applemusic:song:1"), registry.ranked(copies) { it })
        assertTrue(PlayFromPlace.From(SourceId.AppleMusic) in registry.arrangedPlayFrom())

        registry.setPlayFrom(listOf(SourceId.AppleMusic, SourceId.Device, SourceId.Server("s1")).map { PlayFromPlace.From(it) })
        assertEquals("applemusic:song:1", registry.ranked(copies) { it }.first())
    }
}

/** The stand-in waits for MusicKit before its first audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartGateTest {
    @Test fun theFirstAudioWaitsUntilMusicKitPlaysThatSong() {
        val gate = StartGate { 0L }
        gate.reset(asked = true)
        var released = false
        val reader = thread { gate.await("1"); released = true }
        Thread.sleep(100)
        gate.open("2")
        Thread.sleep(100)
        assertFalse("another song playing doesn't count", released)
        gate.open("1")
        reader.join(2_000)
        assertTrue(released)
    }

    @Test fun aSongMusicKitCantPlayFailsWithWhy() {
        val gate = StartGate { 0L }
        gate.reset(asked = true)
        val failure = arrayOfNulls<AppleMusicProblem>(1)
        val reader = thread {
            try {
                gate.await("1")
            } catch (error: AppleMusicException) {
                failure[0] = error.problem
            }
        }
        gate.fail("1", AppleMusicProblem.NoSubscription)
        reader.join(2_000)
        assertEquals(AppleMusicProblem.NoSubscription, failure[0])
    }

    @Test fun itGivesUpOnlyWhileAskedToPlay() {
        var clock = 0L
        val gate = StartGate { clock }
        gate.reset(asked = false)
        clock = StartGate.START_TIMEOUT_MS * 3
        var done = false
        val reader = thread {
            try {
                gate.await("1")
            } catch (_: AppleMusicException) {
            }
            done = true
        }
        Thread.sleep(400)
        assertFalse("paused, it waits for as long as it takes", done)
        gate.asked(play = true)
        clock += StartGate.START_TIMEOUT_MS + 1
        reader.join(2_000)
        assertTrue(done)
    }

    @Test fun theStandInIsSilenceOfTheSongsLength() {
        val playback = AppleMusicPlayback(player = { NoopPlayer })
        val pcm = playback.open(AppleMusicUris.song("1613600188", 221_000))
        assertEquals(221_000L, pcm.durationMs)
        assertEquals(AppleMusicPlayback.STAND_IN_RATE, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        try {
            AppleMusicPlayback(player = { null }).open(AppleMusicUris.song("1", 1000))
            fail("opened without MusicKit")
        } catch (error: AppleMusicException) {
            assertEquals(AppleMusicProblem.SignedOut, error.problem)
        }
    }
}

private object NoopPlayer : AppleMusicPlayer {
    override fun load(catalogId: String, positionMs: Long, play: Boolean) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun stop() = Unit
    override fun tokensChanged() = Unit
    override fun release() = Unit
    override var listener: AppleMusicPlayer.Listener? = null
}

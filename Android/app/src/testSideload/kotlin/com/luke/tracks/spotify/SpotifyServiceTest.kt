package com.luke.tracks.spotify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.sources.SourceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import uniffi.tracks_engine.NoHandle
import uniffi.tracks_engine.SpotifyAccount
import uniffi.tracks_engine.SpotifyAlbumPage
import uniffi.tracks_engine.SpotifyConfig
import uniffi.tracks_engine.SpotifyException
import uniffi.tracks_engine.SpotifyLoginInterface
import uniffi.tracks_engine.SpotifyStream
import uniffi.tracks_engine.SpotifyStreamOptions
import uniffi.tracks_engine.SpotifyTrack
import uniffi.tracks_engine.SpotifyTrackPage

private class MemoryVault : SpotifyVault {
    var saved: ByteArray? = null
    override var name: String? = null
    override fun credentials() = saved
    override fun save(credentials: ByteArray, name: String) {
        saved = credentials
        this.name = name
    }
    override fun forgetCredentials() {
        saved = null
    }
    override fun clear() {
        saved = null
        name = null
    }
}

private fun songAt(uri: String) = SpotifyTrack(uri, "Song", emptyList(), null, 200_000u, false, null, null, 1u, 1u)

/** An account with no Rust behind it: what Spotify would say, scripted. */
private open class FakeAccount(
    val premium: Boolean? = true,
    val streamError: SpotifyException? = null,
    val saved: List<String> = listOf("spotify:track:one"),
) : SpotifyAccount(NoHandle) {
    var signedOut = false
    val opened = mutableListOf<String>()
    override fun connect() = Unit
    override fun isPremium() = premium
    override fun username() = "luke"
    override fun credentials() = byteArrayOf(7, 7)
    override fun signOut() {
        signedOut = true
    }
    override fun savedTracks(offset: UInt, limit: UInt) = SpotifyTrackPage(saved.map(::songAt), saved.size.toUInt(), null)
    override fun openStream(uri: String, options: SpotifyStreamOptions): SpotifyStream {
        opened += uri
        streamError?.let { throw it }
        return object : SpotifyStream(NoHandle) {
            override fun sampleRate() = 44_100u
            override fun channels(): UShort = 2u
            override fun durationMs() = 1000u
            override fun stop() = Unit
        }
    }
}

private class FakeLogin(private val account: SpotifyAccount, private val redirect: () -> String) : SpotifyLoginInterface {
    var cancelled = false
    override fun url() = "https://accounts.spotify.example/authorize"
    override fun waitForRedirect(timeoutSecs: UInt): String = redirect()
    override fun cancel() {
        cancelled = true
    }
    override fun finish(callbackUrl: String, config: SpotifyConfig): SpotifyAccount = account
}

private class FakeEngine(var login: SpotifyLoginInterface? = null, var saved: SpotifyAccount? = null) : SpotifyEngine {
    val redirects = mutableListOf<String>()
    override fun open(credentials: ByteArray, config: SpotifyConfig) = saved ?: throw SpotifyException.Auth("revoked")
    override fun login(redirectUri: String): SpotifyLoginInterface {
        redirects += redirectUri
        return login!!
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpotifyServiceTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("spotify-test", Context.MODE_PRIVATE)
    private val config = SpotifyConfig(osVersion = "16", tempDir = null)

    private fun TestScope.service(engine: FakeEngine, vault: SpotifyVault = MemoryVault(), scope: CoroutineScope = this) =
        SpotifyService(engine, vault, prefs, config, scope, io = UnconfinedTestDispatcher(testScheduler), freePort = { 43_210 })

    @Test fun signingInSavesTheCredentialsAndListsTheSource() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault()
        val engine = FakeEngine(FakeLogin(FakeAccount()) { "http://127.0.0.1:43210/login?code=c&state=s" })
        val service = service(engine, vault)
        assertEquals("https://accounts.spotify.example/authorize", service.beginSignIn())
        assertEquals(listOf("http://127.0.0.1:43210/login"), engine.redirects)
        assertEquals(SpotifyState.Connected("luke"), service.state.value)
        assertArrayEquals(byteArrayOf(7, 7), vault.saved)
        assertEquals(SourceStatus.Connected, SpotifySourceProvider.sources(service.state.value).single().status)
    }

    @Test fun withoutPremiumNothingIsKept() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault()
        val account = FakeAccount(premium = false)
        val service = service(FakeEngine(FakeLogin(account) { "http://127.0.0.1/login?code=c" }), vault)
        service.beginSignIn()
        assertEquals(SpotifyState.Failed(SpotifyProblem.NeedsPremium), service.state.value)
        assertNull(vault.saved)
        assertTrue(account.signedOut)
        assertTrue(SpotifySourceProvider.sources(service.state.value).isEmpty())
        service.dismissProblem()
        assertEquals(SpotifyState.SignedOut, service.state.value)
    }

    @Test fun anAccountWhoseKeysAreRefusedStaysForBrowsingAndAsksToTryAgain() = runTest(UnconfinedTestDispatcher()) {
        val account = FakeAccount(streamError = SpotifyException.AudioKeyRefused())
        val service = service(FakeEngine(FakeLogin(account) { "http://127.0.0.1/login?code=c" }))
        service.beginSignIn()
        assertEquals(SpotifyState.Connected("luke", refused = true), service.state.value)
        assertEquals(listOf("spotify:track:one"), account.opened)
        assertEquals(SourceStatus.SignIn, SpotifySourceProvider.sources(service.state.value).single().status)
    }

    @Test fun aNetworkFailureAfterTheBrowserOffersTryAgain() = runTest(UnconfinedTestDispatcher()) {
        val account = object : FakeAccount() {
            override fun connect() = throw SpotifyException.Network("offline")
        }
        val service = service(FakeEngine(FakeLogin(account) { "http://127.0.0.1/login?code=c" }))
        service.beginSignIn()
        assertEquals(SpotifyState.Failed(SpotifyProblem.Offline), service.state.value)
    }

    @Test fun closingTheBrowserWithoutSigningInIsACancelNotAnError() = runTest {
        val released = CountDownLatch(1)
        var cancelled = false
        val login = object : SpotifyLoginInterface {
            override fun url() = "https://accounts.spotify.example/authorize"
            override fun waitForRedirect(timeoutSecs: UInt): String {
                released.await(5, TimeUnit.SECONDS)
                throw SpotifyException.Auth("sign-in was cancelled")
            }
            override fun cancel() {
                cancelled = true
                released.countDown()
            }
            override fun finish(callbackUrl: String, config: SpotifyConfig): SpotifyAccount = error("never redirected")
        }
        val service = SpotifyService(FakeEngine(login), MemoryVault(), prefs, config, backgroundScope, io = Dispatchers.IO, freePort = { 1 })
        service.beginSignIn()
        assertEquals(SpotifyState.SigningIn, service.state.value)
        service.browserClosed()
        advanceTimeBy(SpotifyService.CANCEL_GRACE_MS / 2)
        assertEquals("a redirect may still be on its way", SpotifyState.SigningIn, service.state.value)
        advanceTimeBy(SpotifyService.CANCEL_GRACE_MS)
        assertTrue(cancelled)
        assertEquals(SpotifyState.SignedOut, service.state.value)
    }

    @Test fun signingOutForgetsEverything() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault().apply { save(byteArrayOf(1), "luke") }
        val account = FakeAccount()
        val service = service(FakeEngine(saved = account), vault)
        assertEquals(SpotifyState.Connected("luke"), service.state.value)
        service.account()
        service.signOut()
        assertEquals(SpotifyState.SignedOut, service.state.value)
        assertNull(vault.saved)
        assertNull(vault.name)
        assertTrue(account.signedOut)
    }

    @Test fun aSavedSignInSpotifyNoLongerTakesAsksToSignInAgain() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault().apply { save(byteArrayOf(1), "luke") }
        val service = service(FakeEngine(saved = null), vault)
        try {
            service.account()
            fail("opened a revoked sign-in")
        } catch (error: SpotifyPlaybackException) {
            assertEquals(SpotifyProblem.SignedOut, error.problem)
        }
        assertEquals(SpotifyState.NeedsSignIn("luke"), service.state.value)
        assertNull(vault.saved)
        assertEquals(SourceStatus.SignIn, SpotifySourceProvider.sources(service.state.value).single().status)
    }

    @Test fun thePlayerOpensAudioThroughTheSavedAccount() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault().apply { save(byteArrayOf(1), "luke") }
        val account = FakeAccount()
        val service = service(FakeEngine(saved = account), vault)
        val pcm = service.audio.open(android.net.Uri.parse("spotify:track:two"))
        assertEquals(44_100, pcm.sampleRate)
        assertEquals(1000L, pcm.durationMs)
        assertEquals(listOf("spotify:track:two"), account.opened)
        pcm.close()
    }

    @Test fun refusedAudioReachesThePlayerAsItsProblem() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault().apply { save(byteArrayOf(1), "luke") }
        val service = service(FakeEngine(saved = FakeAccount(streamError = SpotifyException.AudioKeyRefused())), vault)
        try {
            service.audio.open(android.net.Uri.parse("spotify:track:two"))
            fail("opened refused audio")
        } catch (error: SpotifyPlaybackException) {
            assertEquals(SpotifyProblem.Refused, error.problem)
        }
        service.playbackFailed(SpotifyProblem.Refused)
        assertEquals(SpotifyState.Connected("luke", refused = true), service.state.value)
    }

    @Test fun aRefusedRequestKeepsTheSignInAndConnectsAgain() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault().apply { save(byteArrayOf(1), "luke") }
        var connects = 0
        val account = object : FakeAccount() {
            override fun connect() {
                connects++
            }
            override fun savedAlbums(offset: UInt, limit: UInt): SpotifyAlbumPage = throw SpotifyException.Auth("Spotify does not allow this")
        }
        val service = service(FakeEngine(saved = account), vault)
        try {
            service.savedAlbums(0)
            fail("loaded a refused page")
        } catch (error: SpotifyPlaybackException) {
            assertEquals(SpotifyProblem.NotAllowed, error.problem)
        }
        assertEquals("connected again once", 2, connects)
        assertArrayEquals(byteArrayOf(7, 7), vault.saved)
        assertEquals(SpotifyState.Connected("luke"), service.state.value)
    }

    @Test fun aSongRefusedWithASignInErrorIsTriedOnAFreshConnection() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault().apply { save(byteArrayOf(1), "luke") }
        val account = FakeAccount(streamError = SpotifyException.Auth("403"))
        val service = service(FakeEngine(saved = account), vault)
        try {
            service.audio.open(android.net.Uri.parse("spotify:track:two"))
            fail("opened refused audio")
        } catch (error: SpotifyPlaybackException) {
            assertEquals(SpotifyProblem.NotAllowed, error.problem)
            assertTrue(!error.problem.accountWide)
        }
        assertEquals(listOf("spotify:track:two", "spotify:track:two"), account.opened)
        assertArrayEquals(byteArrayOf(7, 7), vault.saved)
    }

    @Test fun signingOutDoesNotWaitForTheAccount() = runTest(UnconfinedTestDispatcher()) {
        val vault = MemoryVault().apply { save(byteArrayOf(1), "luke") }
        val service = service(FakeEngine(saved = FakeAccount()), vault)
        service.signOut()
        assertEquals(SpotifyState.SignedOut, service.state.value)
        try {
            service.account()
            fail("opened an account after signing out")
        } catch (error: SpotifyPlaybackException) {
            assertEquals(SpotifyProblem.SignedOut, error.problem)
        }
        assertNull(vault.saved)
    }

    @Test fun everyEngineErrorHasItsWords() {
        assertEquals(SpotifyProblem.NeedsPremium, SpotifyException.NotPremium().toSpotifyProblem())
        assertEquals(SpotifyProblem.Refused, SpotifyException.AudioKeyRefused().toSpotifyProblem())
        assertEquals(SpotifyProblem.Offline, SpotifyException.Network("x").toSpotifyProblem())
        assertEquals(SpotifyProblem.Busy, SpotifyException.RateLimited(60u).toSpotifyProblem())
        assertEquals(SpotifyProblem.SignedOut, SpotifyException.Auth("x").toSpotifyProblem())
        assertEquals(SpotifyProblem.Unavailable, SpotifyException.Unavailable("x").toSpotifyProblem())
        assertEquals(SpotifyProblem.Unavailable, IllegalStateException().toSpotifyProblem())
    }

    @Test fun qualityUsesSpotifysNamesAndIsKept() = runTest(UnconfinedTestDispatcher()) {
        val service = service(FakeEngine())
        assertEquals(SpotifyQuality.VeryHigh to SpotifyQuality.High, service.quality.value)
        service.setQuality(SpotifyQuality.High, SpotifyQuality.Normal)
        assertEquals(SpotifyQuality.High to SpotifyQuality.Normal, service(FakeEngine()).quality.value)
        assertEquals(listOf("Normal", "High", "Very High"), SpotifyQuality.entries.map { it.label })
    }
}

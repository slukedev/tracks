package com.luke.tracks.streaming

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.RelayPlayer
import com.luke.tracks.playback.toMediaItem
import com.luke.tracks.playback.toPlayableSong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The relay's queue as Play From and Tracks Radio use it: songs to look up wait on the device
 * and go to the server as each comes due, so a pick can still make way and nothing is dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class RelayQueueTest {
    private fun song(mediaId: String, title: String) =
        PlayableSong(mediaId, Uri.parse("https://example.net/x"), title, "Artist", null, null, 200_000, "${title.lowercase()}|artist")

    private val apple = song(CatalogMedia.mediaId(SERVER, "am.1"), "One")
    private val onServer = song(ServerLibrary.mediaId(SERVER, "s2"), "Two")
    private val onDevice = song("local:3", "Three")
    private val elsewhere = song(ServerLibrary.mediaId("other", "s4"), "Four")
    private val pick = song(CatalogMedia.mediaId(SERVER, "am.5"), "Five")

    private class Setup(scope: TestScope) {
        val relay = FakeRelay()
        val passed = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val engine = RelayEngine(scope.backgroundScope, { relay }, { "TV" }, now = { Instant.ofEpochMilli(scope.testScheduler.currentTime) }, io = dispatcher, work = dispatcher)
        val player = RelayPlayer(ApplicationProvider.getApplicationContext(), engine, scope.backgroundScope, http = { okhttp3.OkHttpClient() }, playNatively = { _, _, _ -> })
    }

    private fun TestScope.settle() {
        repeat(3) {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun TestScope.setup(): Setup = Setup(this).apply {
        // A song this device has is on your server through the relay too; one on another server isn't anywhere here.
        player.waits = { serverId, item -> !RelayPlayer.playsOn(serverId, item.mediaId) }
        player.resolve = { serverId, item ->
            when (item.mediaId) {
                onDevice.mediaId -> song(ServerLibrary.mediaId(serverId, "s3"), "Three").toMediaItem()
                else -> item.takeIf { RelayPlayer.playsOn(serverId, it.mediaId) }
            }
        }
        player.passedOver = { passed += it.mediaId }
    }

    private fun items(vararg songs: PlayableSong): List<MediaItem> = songs.map { it.toMediaItem() }

    @Test fun aMixedQueueOnOneServerPlaysFully() = runTest {
        val s = setup()
        s.player.setMediaItems(items(apple, onServer, onDevice, elsewhere), 0, 0)
        settle()
        assertEquals("the songs the server has go at once", listOf("start:lossless:2"), s.relay.sent)
        assertEquals("the rest wait, shown in Up Next", 4, s.player.mediaItemCount)
        s.player.sendNext()
        settle()
        assertEquals("the song on this device plays from your server", "play:add:1", s.relay.sent.last())
        s.player.sendNext()
        settle()
        assertEquals("one nothing here can play is passed over, and said", listOf(elsewhere.mediaId), s.passed)
        assertFalse(s.player.holding)
    }

    @Test fun radioOnTheRelayTopsUpAndAPickCanStillMakeWay() = runTest {
        val s = setup()
        s.player.setMediaItems(items(apple), 0, 0)
        settle()
        s.player.hold(items(pick))
        settle()
        assertEquals(2, s.player.mediaItemCount)
        assertEquals(pick.mediaId, s.player.getMediaItemAt(1).mediaId)
        // More Like This or Hear Less: the waiting pick is taken out, never sent.
        s.player.removeMediaItem(1)
        settle()
        assertEquals(1, s.player.mediaItemCount)
        assertEquals(listOf("start:lossless:1"), s.relay.sent)
        // The next pick goes to the server once it's due, to follow on without a gap.
        s.player.hold(items(onServer))
        s.player.sendNext()
        settle()
        assertEquals("play:add:1", s.relay.sent.last())
        assertTrue("it keeps what it is, for the station", s.player.getMediaItemAt(1).toPlayableSong().identity.isNotEmpty())
    }

    @Test fun skippingToAPickNotSentYetSendsItAndPlaysIt() = runTest {
        val s = setup()
        s.player.setMediaItems(items(apple), 0, 0)
        settle()
        s.player.hold(items(pick))
        settle()
        s.player.seekToNext()
        settle()
        assertEquals(listOf("start:lossless:1", "play:next:1", "next"), s.relay.sent)
        assertEquals(1, s.player.currentMediaItemIndex)
        assertEquals("Five", s.player.currentMediaItem!!.mediaMetadata.title)
    }
}

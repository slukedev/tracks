package com.luke.tracks.play

import android.net.Uri
import com.luke.tracks.playback.NoRadio
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.radio.RadioOnRelay
import com.luke.tracks.playback.radio.RadioPicks
import com.luke.tracks.streaming.RelayStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tracks Radio's picks, each played from the first place in Play From the player can play. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class RadioPicksTest {
    private class Station(vararg songs: PlayableSong) : NoRadio() {
        val waiting = ArrayDeque(songs.toList())
        val picked = mutableListOf<String>()
        val released = mutableListOf<String>()
        val playingAs = mutableListOf<Pair<String, String>>()
        override suspend fun next(): PlayableSong? = waiting.removeFirstOrNull()?.also { picked += it.mediaId }
        override fun release(song: PlayableSong) { released += song.mediaId }
        override fun playsAs(picked: PlayableSong, song: PlayableSong) { playingAs += picked.mediaId to song.mediaId }
    }

    private fun song(id: String, title: String = id) = PlayableSong(id, Uri.parse("https://example.net/$id"), title, "Artist", null, null, 200_000, "$title|artist")

    /** Your server's songs are on Apple Music too, except "Lost"; songs on this device are nowhere else. */
    private val appleFirst: suspend (PlayableSong, (PlayableSong) -> Boolean) -> PlayableSong? = { song, accept ->
        listOfNotNull(
            song.takeIf { it.mediaId.startsWith("subsonic:") && it.title != "Lost" }?.let { song("catalog:home:am.${it.title}", it.title) },
            song,
        ).firstOrNull(accept)
    }

    private val onRelay: (PlayableSong) -> Boolean = { it.mediaId.startsWith("catalog:home:") || it.mediaId.startsWith("subsonic:home:") }
    private val onPhone: (PlayableSong) -> Boolean = { !it.mediaId.startsWith("catalog:") }

    @Test fun aPickPlaysFromTheServiceFirstInPlayFrom() = runTest {
        val station = Station(song("subsonic:home:1", "One"), song("subsonic:home:2", "Two"))
        val picks = RadioPicks(backgroundScope, station, appleFirst)
        assertEquals("catalog:home:am.One", picks.next("relay", onRelay)?.mediaId)
        assertEquals(listOf("subsonic:home:1" to "catalog:home:am.One"), station.playingAs)
    }

    @Test fun aPickTheRelayCantPlayMakesWayForTheNext() = runTest {
        val station = Station(song("local:1", "Here"), song("subsonic:home:2", "Lost"))
        val picks = RadioPicks(backgroundScope, station, appleFirst)
        // A song only on this device can't play on the relay; one with no match plays from your server through it.
        assertEquals("subsonic:home:2", picks.next("relay", onRelay)?.mediaId)
        assertEquals(listOf("local:1"), station.released)
    }

    @Test fun theNextPickIsReadyBeforeItsAskedFor() = runTest {
        var lookups = 0
        val slow: suspend (PlayableSong, (PlayableSong) -> Boolean) -> PlayableSong? = { song, accept -> lookups++; delay(2_000); appleFirst(song, accept) }
        val station = Station(song("subsonic:home:1", "One"), song("subsonic:home:2", "Two"))
        val picks = RadioPicks(backgroundScope, station, slow)
        picks.next("relay", onRelay)
        runCurrent()
        assertEquals("the one after is being looked up already", 2, lookups)
        delay(2_000)
        val started = testScheduler.currentTime
        assertEquals("catalog:home:am.Two", picks.next("relay", onRelay)?.mediaId)
        assertEquals("no wait for it", started, testScheduler.currentTime)
    }

    @Test fun aSkipOrMoreLikeThisLetsTheReadyPickGo() = runTest {
        val station = Station(song("subsonic:home:1", "One"), song("subsonic:home:2", "Two"), song("subsonic:home:3", "Three"))
        val picks = RadioPicks(backgroundScope, station, appleFirst)
        picks.next("relay", onRelay)
        runCurrent()
        picks.steered()
        assertEquals("catalog:home:am.Three", picks.next("relay", onRelay)?.mediaId)
        assertEquals("the pick made before is let go", listOf("catalog:home:am.Two"), station.released)
    }

    @Test fun aPickReadyForTheRelayIsntPlayedHere() = runTest {
        val station = Station(song("subsonic:home:1", "One"), song("subsonic:home:2", "Two"), song("subsonic:home:3", "Three"))
        val picks = RadioPicks(backgroundScope, station, appleFirst)
        picks.next("relay", onRelay)
        runCurrent()
        // The relay went: the pick looked up for it makes way, and the next plays from your copy.
        assertEquals("subsonic:home:3", picks.next("here", onPhone)?.mediaId)
        assertEquals(listOf("catalog:home:am.Two"), station.released)
    }

    @Test fun getOffAndNothingElseMeansNoPick() = runTest {
        // A new find nothing can play but Get, with Get off: it makes way, a few times, then none.
        val station = Station(song("trackside-new:home:a"), song("trackside-new:home:b"), song("trackside-new:home:c"), song("trackside-new:home:d"))
        val picks = RadioPicks(backgroundScope, station, { _, _ -> null })
        assertNull(picks.next("here", onPhone))
        assertEquals(3, station.released.size)
        assertEquals("one left untouched", 1, station.waiting.size)
    }

    @Test fun theRelayStoppingForGoodHandsTheStationBack() {
        assertTrue(RadioOnRelay.stopped(RelayStatus(serverId = "home", phase = RelayStatus.Phase.Failed)))
        assertTrue(RadioOnRelay.stopped(RelayStatus(serverId = "home", phase = RelayStatus.Phase.Ended, endedReason = "assistant_unavailable")))
        assertTrue(RadioOnRelay.stopped(RelayStatus(serverId = "home", phase = RelayStatus.Phase.Ended, endedReason = "revoked")))
        // Paused too long: playing again starts a new session, so it stays on the relay.
        assertFalse(RadioOnRelay.stopped(RelayStatus(serverId = "home", phase = RelayStatus.Phase.Ended, endedReason = "idle")))
        assertFalse(RadioOnRelay.stopped(RelayStatus(serverId = "home", phase = RelayStatus.Phase.Live)))
    }
}

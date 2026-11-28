package com.luke.tracks.streaming

import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.CatalogFeatures
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideEvent
import com.luke.tracks.library.trackside.TracksideSpeakerCommand
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.library.trackside.TracksideSpeakerQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The relay with what a newer server can do: take a stream over, a service's radio, a held start, a song taken out. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayServiceFeaturesTest {
    /** Keeps every play body the engine sends, and answers the queue with real item ids. */
    private class Recording(val inner: FakeRelay = FakeRelay()) : RelayClient by inner {
        val plays = mutableListOf<TracksideSpeakerPlayRequest>()
        /** Plays sent with a new session. */
        val started = mutableListOf<TracksideSpeakerPlayRequest>()
        val removed = mutableListOf<String>()
        var refuse: Throwable? = null
        var queue = TracksideSpeakerQueue(id = "o1")

        override suspend fun play(speakerId: String, request: TracksideSpeakerPlayRequest): TracksideSpeakerCommand {
            refuse?.let { throw it }
            plays += request
            return inner.play(speakerId, request)
        }

        override suspend fun queue(speakerId: String, limit: Int, history: Int) = queue

        override suspend fun start(request: com.luke.tracks.library.trackside.TracksideRelayRequest): com.luke.tracks.library.trackside.TracksideRelaySession {
            request.play?.let { started += it }
            return inner.start(request)
        }

        override suspend fun remove(speakerId: String, itemId: String): TracksideSpeakerCommand {
            removed += itemId
            return inner.patch(speakerId, com.luke.tracks.library.trackside.TracksideSpeakerPatch())
        }
    }

    private fun TestScope.engine(relay: RelayClient, features: CatalogFeatures): RelayEngine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RelayEngine(backgroundScope, { relay }, { "Pixel" }, features = { features }, now = { Instant.ofEpochMilli(testScheduler.currentTime) }, io = dispatcher, work = dispatcher)
    }

    private val seed = CatalogMedia.mediaId(SERVER, "mc1.seed")
    private val kept = CatalogMedia.request(listOf(seed, CatalogMedia.mediaId(SERVER, "mc1.b")), 0, SERVER)!!
    private val preview = listOf(
        RelayItem("", seed, "Seed", "X", null, null, 200_000, "b1"),
        RelayItem("", CatalogMedia.mediaId(SERVER, "mc1.b"), "B", "X", null, null, 200_000, "b1"),
    )
    private val spotify = mapOf("b1" to "Spotify")

    private fun failed(reason: String, holder: String? = null) = TracksideEvent(
        "speaker_command",
        """{"command_id":"j1","speaker_id":"o1","outcome":"failed","error":{"code":"command_failed","reason":"$reason","service_id":"b1"${holder?.let { ",\"holder_id\":\"k1\",\"holder_name\":\"$it\"" }.orEmpty()}}}""",
    )

    @Test fun aServiceStreamIsTakenOverOnlyWhereTheServerCan() = runTest {
        val can = Recording()
        val engine = engine(can, CatalogFeatures(takeover = true))
        engine.play(SERVER, kept, preview, spotify)
        runCurrent()
        engine.play(SERVER, kept, preview, spotify)
        runCurrent()
        assertEquals(listOf(true, true), (can.started + can.plays).map { it.takeover })

        val cannot = Recording()
        engine(cannot, CatalogFeatures()).play(SERVER, kept, preview, spotify)
        runCurrent()
        assertNull(cannot.started.single().takeover)
    }

    @Test fun aBusyServiceSaysWhereItsPlaying() = runTest {
        val relay = Recording()
        val engine = engine(relay, CatalogFeatures(takeover = true))
        val said = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Unconfined) { engine.messages.collect { said += it } }
        engine.play(SERVER, kept, preview, spotify)
        runCurrent()
        engine.handle(SERVER, failed("service_busy", holder = "Kitchen"))
        runCurrent()
        assertEquals("Spotify is playing on Kitchen.", said.single())
    }

    @Test fun aRadioTheServiceCantMakeGivesWayQuietly() = runTest {
        val relay = Recording()
        val engine = engine(relay, CatalogFeatures(radio = true))
        val said = mutableListOf<String>()
        val fell = mutableListOf<String>()
        backgroundScope.launch(Dispatchers.Unconfined) { engine.messages.collect { said += it } }
        backgroundScope.launch(Dispatchers.Unconfined) { engine.radioFailed.collect { fell += it } }
        val radio = CatalogMedia.request(listOf(seed), 0, SERVER)!!.let { it.copy(request = it.request.copy(radio = true)) }
        engine.play(SERVER, radio, preview.take(1), spotify, radioOf = seed)
        runCurrent()
        engine.handle(SERVER, failed("radio_unavailable"))
        runCurrent()
        assertEquals(listOf(seed), fell)
        assertTrue("nothing said: Tracks Radio takes over", said.isEmpty())

        // Refused outright (an older service without similar songs): the same.
        engine.play(SERVER, kept, preview, spotify)
        runCurrent()
        relay.refuse = TracksideError.Server(422, "unsupported", "", capability = "similar", serviceId = "b1")
        engine.play(SERVER, radio, preview.take(1), spotify, radioOf = seed)
        runCurrent()
        assertEquals(listOf(seed, seed), fell)
        assertTrue(said.isEmpty())
    }

    @Test fun aHeldQueueStartsOnCueAndAStaleOneIsPlayedAfresh() = runTest {
        val relay = Recording()
        val engine = engine(relay, CatalogFeatures(hold = true))
        engine.hold(SERVER, kept)
        runCurrent()
        assertEquals(true, relay.plays.single().hold)
        assertNull("nothing shows while it's held", engine.status.value.serverId)

        engine.play(SERVER, kept, preview, spotify)
        runCurrent()
        assertEquals("started, not queued again", 1, relay.plays.size)
        assertEquals("play", relay.inner.sent.last())

        // Held, then left past the music system's pause timeout: queued afresh.
        val later = Recording()
        val other = engine(later, CatalogFeatures(hold = true))
        other.hold(SERVER, kept)
        runCurrent()
        advanceTimeBy(RelayEngine.HELD_FOR_MS + 1)
        other.play(SERVER, kept, preview, spotify)
        runCurrent()
        assertEquals(2, later.plays.size)
        assertNull(later.plays.last().hold)

        // A server that can't hold asks nothing ahead.
        val old = Recording()
        engine(old, CatalogFeatures()).hold(SERVER, kept)
        runCurrent()
        assertTrue(old.plays.isEmpty())
    }

    @Test fun songsAlreadySentAreTakenOutWhereTheServerCan() = runTest {
        val relay = Recording().apply {
            queue = TracksideSpeakerQueue(
                id = "o1",
                currentIndex = 0,
                items = listOf(
                    TracksideSpeakerQueue.Item(0, "i1", "Seed", ref = "mc1.seed", serviceId = "b1"),
                    TracksideSpeakerQueue.Item(1, "i2", "B", ref = "mc1.b", serviceId = "b1"),
                    TracksideSpeakerQueue.Item(2, "i3", "Mine", songId = "s9"),
                ),
            )
        }
        val engine = engine(relay, CatalogFeatures(queueRemove = true))
        engine.play(SERVER, kept, preview, spotify)
        runCurrent()
        advanceTimeBy(RelayEngine.SETTLE_READ_MS + 1)
        runCurrent()
        assertEquals(3, engine.status.value.items.size)
        assertTrue(engine.removesQueued)
        relay.queue = relay.queue.copy(items = relay.queue.items.filter { it.itemId != "i2" })
        // The one playing stays, whatever's asked.
        engine.remove(0, 2)
        runCurrent()
        assertEquals(listOf("i2"), relay.removed)
        assertEquals(listOf(seed, ServerLibrary.mediaId(SERVER, "s9")), engine.status.value.items.map { it.mediaId })
        assertEquals(0, engine.status.value.index)
    }

    @Test fun eachServiceReasonHasItsLine() {
        assertEquals("Spotify is playing somewhere else.", RelayWords.command("command_failed", "service_busy", "Spotify", null))
        assertEquals("Sign in to Spotify again on your server.", RelayWords.command("command_failed", "service_signed_out", "Spotify", null))
        assertEquals("That song isn't available in your country.", RelayWords.command("command_failed", "region_unavailable", "Spotify", null))
        assertEquals("Your Spotify plan can't play that.", RelayWords.command("command_failed", "subscription_required", "Spotify", null))
        assertEquals("That song can't play here.", RelayWords.command("command_failed", "drm_failed", null, null))
        assertEquals("Your server couldn't play that.", RelayWords.command("command_failed", "something_new", "Spotify", null))
        assertEquals(
            "Spotify is playing somewhere else.",
            RelayWords.of(TracksideError.Server(409, "conflict", "", reason = "service_busy", serviceId = "b1"), spotify),
        )
    }
}

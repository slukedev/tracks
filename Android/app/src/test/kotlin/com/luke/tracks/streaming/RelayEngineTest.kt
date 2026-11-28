package com.luke.tracks.streaming

import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.TracksideCatalogSearch
import com.luke.tracks.library.trackside.TracksideCatalogServices
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideEvent
import com.luke.tracks.library.trackside.TracksideJson
import com.luke.tracks.library.trackside.TracksideRelayRequest
import com.luke.tracks.library.trackside.TracksideRelaySession
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.library.trackside.TracksideSpeakerCommand
import com.luke.tracks.library.trackside.TracksideSpeakerPatch
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.library.trackside.TracksideSpeakerPlayback
import com.luke.tracks.library.trackside.TracksideSpeakerQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun fixture(name: String): String =
    requireNotNull(RelayEngineTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

private const val SERVER = "srv1"

/** A relay server in memory: every generation has its own playlist path, as the contract says. */
private class FakeRelay : RelayClient {
    val sent = mutableListOf<String>()
    var generation = 0L
    var state = "starting"
    var tier = "lossless"
    var failStart: Throwable? = null
    /** Whether a command's 202 carries the new session (else only a later read or event does). */
    var answersWithRelay = true
    var ended: String? = null

    fun session(): TracksideRelaySession {
        if (ended != null) return TracksideRelaySession(id = "h1", speakerId = "o1", state = "ended", endedReason = ended, generation = generation, tier = tier)
        // Before audio and while paused, the playlist named is the next generation's.
        val named = if (state == "playing") generation else generation + 1
        return TracksideRelaySession(id = "h1", speakerId = "o1", state = state, generation = generation, tier = tier, stream = TracksideRelaySession.Stream(hls = "/api/v1/relay/h1/$named/master.m3u8"))
    }

    private fun answer(action: String) = TracksideSpeakerCommand(action, "j${sent.size}", null, TracksideSpeaker(id = "o1", kind = "phone"), session().takeIf { answersWithRelay })

    override suspend fun start(request: TracksideRelayRequest): TracksideRelaySession {
        failStart?.let { throw it }
        sent += "start:${request.tier}:${request.play?.items?.size ?: 0}"
        ended = null
        if (request.play != null) { generation += 1; state = "playing" } else state = "ready"
        tier = request.tier
        return session()
    }
    override suspend fun session(id: String) = session()
    override suspend fun setTier(id: String, tier: String): TracksideRelaySession {
        sent += "tier:$tier"
        this.tier = tier
        if (state == "playing") generation += 1
        return session()
    }
    override suspend fun end(id: String) { sent += "end" }
    override suspend fun playback(speakerId: String, command: TracksideSpeakerPlayback): TracksideSpeakerCommand {
        if (ended != null) throw TracksideError.Server(409, "conflict", "", reason = "relay_ended")
        sent += command.action + (command.positionMs?.let { ":$it" } ?: "") + (command.itemId?.let { ":$it" } ?: "")
        when (command.action) {
            "pause" -> state = "paused"
            else -> { generation += 1; state = "playing" }
        }
        return answer(command.action)
    }
    override suspend fun patch(speakerId: String, patch: TracksideSpeakerPatch): TracksideSpeakerCommand { sent += "patch"; return answer("patch") }
    override suspend fun play(speakerId: String, request: TracksideSpeakerPlayRequest): TracksideSpeakerCommand {
        sent += "play:${request.mode}:${request.items.size}"
        if (request.mode == "replace") { generation += 1; state = "playing" }
        return answer("play")
    }
    override suspend fun transfer(speakerId: String, to: String): TracksideSpeakerCommand { sent += "transfer:$to"; state = "paused"; return answer("transfer") }
    override suspend fun speaker(speakerId: String) = TracksideSpeaker(id = "o1", kind = "phone")
    override suspend fun queue(speakerId: String, limit: Int, history: Int) = TracksideSpeakerQueue(id = "o1")
    override suspend fun streamUrl(hls: String, fresh: Boolean) = "http://home$hls?mt=${if (fresh) "new" else "t"}"
    override val clockSkewMs: Long = 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class RelayEngineTest {
    private fun TestScope.engine(relay: FakeRelay): RelayEngine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RelayEngine(backgroundScope, { relay }, { "Pixel" }, now = { Instant.ofEpochMilli(testScheduler.currentTime) }, io = dispatcher, work = dispatcher)
    }

    private val kept = CatalogMedia.request(
        listOf(CatalogMedia.mediaId(SERVER, "mc1.a"), ServerLibrary.mediaId(SERVER, "s1"), CatalogMedia.mediaId(SERVER, "mc1.b")),
        startIndex = 0, serverId = SERVER,
    )!!

    private val preview = listOf(
        RelayItem("", CatalogMedia.mediaId(SERVER, "mc1.a"), "A", "X", null, null, 200_000, "b1"),
        RelayItem("", ServerLibrary.mediaId(SERVER, "s1"), "S", "X", null, null, 200_000, null),
        RelayItem("", CatalogMedia.mediaId(SERVER, "mc1.b"), "B", "X", null, null, 200_000, "b1"),
    )

    @Test fun playingStartsASessionAndPlaysItsFirstGeneration() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, mapOf("b1" to "Spotify"))
        runCurrent()
        val status = engine.status.value
        assertEquals(listOf("start:lossless:3"), relay.sent)
        assertTrue(status.wantsToPlay)
        assertEquals("o1", status.speakerId)
        assertEquals("http://home/api/v1/relay/h1/1/master.m3u8?mt=t", status.stream!!.url)
        assertEquals("A", status.current!!.title)
    }

    @Test fun aSkipNeverPlaysWhatWasBufferedBefore() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        val first = engine.status.value.stream!!.hls
        relay.answersWithRelay = false
        engine.next()
        runCurrent()
        // At once: the next song shows, and the old playlist is let go before any answer.
        assertEquals("S", engine.status.value.current!!.title)
        assertNull("nothing plays until the server names a new playlist", engine.status.value.stream)
        advanceTimeBy(1_600)
        runCurrent()
        val second = engine.status.value.stream!!.hls
        assertNotEquals(first, second)
        assertEquals("/api/v1/relay/h1/2/master.m3u8", second)
    }

    @Test fun pauseStopsAtOnceAndResumeTakesTheNextGeneration() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        engine.pause()
        runCurrent()
        assertFalse(engine.status.value.wantsToPlay)
        assertNull(engine.status.value.stream)
        assertEquals("pause", relay.sent.last())
        engine.resume()
        runCurrent()
        assertTrue(engine.status.value.wantsToPlay)
        // The paused session named generation 2 already; the resumed one plays it, never generation 1 again.
        assertEquals("/api/v1/relay/h1/2/master.m3u8", engine.status.value.stream!!.hls)
        assertEquals("play", relay.sent.last())
    }

    @Test fun playStraightAfterANewQueueStartsOneSession() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        val shown = engine.play(SERVER, kept, preview, emptyMap())
        engine.resume()
        runCurrent()
        assertTrue("shown before the network answered", shown.isCompleted)
        assertEquals(listOf("start:lossless:3"), relay.sent)
    }

    @Test fun pausedWhileStartingTellsTheServerAndPlaysNothing() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, emptyMap())
        engine.pause()
        runCurrent()
        assertEquals(listOf("start:lossless:3", "pause"), relay.sent)
        assertNull(engine.status.value.stream)
        assertFalse(engine.status.value.wantsToPlay)
    }

    @Test fun aSeekSendsThePositionAndShowsItAtOnce() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        engine.seek(90_000)
        runCurrent()
        assertEquals("seek:90000", relay.sent.last())
        assertEquals(90_000L, engine.status.value.positionMs)
        assertEquals("/api/v1/relay/h1/2/master.m3u8", engine.status.value.stream!!.hls)
    }

    @Test fun anIdleSessionStartsAgainWhenPlayed() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        engine.pause()
        runCurrent()
        relay.ended = "idle"
        engine.handle(SERVER, TracksideEvent("relay", TracksideJson.encodeToString(TracksideRelaySession.serializer(), relay.session())))
        runCurrent()
        assertEquals(RelayStatus.Phase.Ended, engine.status.value.phase)
        engine.resume()
        runCurrent()
        assertEquals(listOf("start:lossless:3", "pause", "start:lossless:0", "play"), relay.sent)
        assertTrue(engine.status.value.wantsToPlay)
        assertTrue(engine.status.value.stream != null)
    }

    @Test fun aNetworkChangeMovesTheTierOnceItSettles() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        val before = engine.status.value.stream!!.hls
        engine.preferTier(cellular = true, serverSendsCellular = true)
        engine.preferTier(cellular = false, serverSendsCellular = true)
        engine.preferTier(cellular = true, serverSendsCellular = true)
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(listOf("tier:cellular"), relay.sent.filter { it.startsWith("tier") })
        assertEquals("cellular", engine.status.value.tier)
        assertNotEquals("a tier change is a new generation", before, engine.status.value.stream!!.hls)

        // A server without an encoder is never asked for cellular.
        val plain = FakeRelay()
        val other = engine(plain)
        other.preferTier(cellular = true, serverSendsCellular = false)
        advanceTimeBy(2_100)
        other.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        assertEquals("start:lossless:3", plain.sent.first())
    }

    @Test fun aRefusedPlaylistIsLoadedAgainWithAFreshToken() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        val stream = engine.status.value.stream!!
        engine.streamFailed(stream.url, 403)
        runCurrent()
        assertEquals(stream.hls, engine.status.value.stream!!.hls)
        assertEquals("http://home${stream.hls}?mt=new", engine.status.value.stream!!.url)
        assertEquals(1, engine.status.value.stream!!.attempt)
    }

    @Test fun theServerSayingNoIsShownInPlainWords() = runTest {
        val relay = FakeRelay().apply { failStart = TracksideError.Server(409, "conflict", "", reason = "relay_limit", limit = 3) }
        val engine = engine(relay)
        val said = mutableListOf<String>()
        backgroundScope.launchCollect(engine) { said += it }
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        assertEquals(RelayStatus.Phase.Failed, engine.status.value.phase)
        assertFalse(engine.status.value.wantsToPlay)
        assertTrue(said.single(), said.single().contains("as many phones"))
        relay.failStart = TracksideError.CatalogUnavailable(10)
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        assertEquals("Music services aren't available right now.", said.last())
    }

    @Test fun movingToASpeakerStopsThePhone() = runTest {
        val relay = FakeRelay()
        val engine = engine(relay)
        engine.play(SERVER, kept, preview, emptyMap())
        runCurrent()
        val moved = backgroundScope.async { engine.transfer("o4j8") }
        runCurrent()
        assertTrue(moved.await())
        assertEquals("transfer:o4j8", relay.sent.last())
        assertFalse(engine.status.value.wantsToPlay)
        assertNull(engine.status.value.stream)
    }

    @Test fun theHeardPositionIsTheServersLessTheLatency() {
        val state = RelayStatus(serverId = SERVER, wantsToPlay = true, positionMs = 10_000, positionAtMs = 1_000_000, latencyMs = 4_000, items = preview)
        assertEquals(10_000L + 6_000 - 4_000, RelayClock.position(state, serverNowMs = 1_006_000))
        assertEquals("the program date wins when it's known", 10_000L + 5_500, RelayClock.position(state, 1_006_000, audibleAtMs = 1_005_500))
        assertEquals(0L, RelayClock.position(state.copy(positionMs = 0), serverNowMs = 1_001_000))
        assertEquals(7_000L, RelayClock.position(state.copy(wantsToPlay = false, positionMs = 7_000), serverNowMs = 2_000_000))
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCollect(engine: RelayEngine, block: (String) -> Unit) =
    launch(kotlinx.coroutines.Dispatchers.Unconfined) { engine.messages.collect { block(it) } }


class CatalogMediaTest {
    @Test fun mediaIdsKeepTheRefExactly() {
        val ref = "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0"
        val id = CatalogMedia.mediaId(SERVER, ref)
        assertEquals(SERVER to ref, CatalogMedia.parse(id))
        assertTrue(CatalogMedia.isCatalog(id))
        assertNull(CatalogMedia.parse(ServerLibrary.mediaId(SERVER, "s1")))
        assertNull(CatalogMedia.parse("catalog:$SERVER:"))
    }

    @Test fun aQueueWithAServicesSongPlaysThroughTheRelay() {
        val ids = listOf("local:12", ServerLibrary.mediaId(SERVER, "s1"), CatalogMedia.mediaId(SERVER, "mc1.a"), ServerLibrary.mediaId("other", "s9"))
        assertEquals(SERVER, CatalogMedia.relayServer(ids))
        assertNull("library songs alone stay native", CatalogMedia.relayServer(ids.filterNot(CatalogMedia::isCatalog)))
        val kept = CatalogMedia.request(ids, startIndex = 0, serverId = SERVER, positionMs = 5_000)!!
        assertEquals(listOf(TracksideSpeakerPlayRequest.Item(songId = "s1"), TracksideSpeakerPlayRequest.Item(ref = "mc1.a")), kept.request.items)
        assertEquals(listOf(1, 2), kept.positions)
        assertEquals("a phone file isn't on the server: it starts at the next song kept", 0, kept.request.startIndex)
        assertEquals(0L, kept.request.positionMs)
        val fromThird = CatalogMedia.request(ids, startIndex = 2, serverId = SERVER, positionMs = 5_000)!!
        assertEquals(1, fromThird.request.startIndex)
        assertEquals(5_000L, fromThird.request.positionMs)
    }

    @Test fun servicesPlaysKeepTheirSource() {
        assertEquals(com.luke.tracks.data.PlaySourceValue.Spotify to null, CatalogPlays.source("spotify"))
        assertEquals(com.luke.tracks.data.PlaySourceValue.Streaming to null, CatalogPlays.source("apple_music"))
        assertEquals(com.luke.tracks.data.PlaySourceValue.Other to "tidal", CatalogPlays.source("tidal"))
        assertEquals("{\"source\":\"tidal\"}", CatalogPlays.cloudFields("tidal"))
    }
}

class ServiceResultsMergeTest {
    private val services = TracksideJson.decodeFromString(TracksideCatalogServices.serializer(), fixture("catalog-services.json")).services
    private val server = StreamingServer(SERVER, "Home", TracksideClient.CatalogAccess.Play(true), StreamingServer.Status.Ok, services)

    @Test fun ownedSongsFoldIntoTheLibraryAndTheRestGroupByService() {
        val search = TracksideJson.decodeFromString(TracksideCatalogSearch.serializer(), fixture("catalog-search.json"))
        val owned = ServiceResultsMerge.merge(listOf(server to search), owned = { _, id -> id == "sdjcz08vf67h7cx28snq6" }, libraryMediaId = ServerLibrary::mediaId)
        assertTrue("Halo is yours: it isn't listed again", owned.sections.single().tracks.isEmpty())
        assertEquals(listOf("Spotify"), owned.folded[ServerLibrary.mediaId(SERVER, "sdjcz08vf67h7cx28snq6")])
        assertEquals("Spotify", owned.sections.single().service.name)
        assertEquals(1, owned.sections.single().albums.size)
        assertFalse(owned.complete)
        assertEquals(listOf("Tidal"), owned.missing)

        val notOwned = ServiceResultsMerge.merge(listOf(server to search), owned = { _, _ -> false }, libraryMediaId = ServerLibrary::mediaId)
        assertEquals(1, notOwned.sections.single().tracks.size)
        assertTrue(notOwned.folded.isEmpty())
    }

    @Test fun keysSurviveBeingSaved() {
        val key = ServiceKey(SERVER, "bqtyy4kcexcsg6wa0y4w5")
        assertEquals(key, ServiceKey.parse(key.raw))
        assertNull(ServiceKey.parse("nonsense"))
    }

    @Test fun onlySignedInServicesThatSearchAreSearched() {
        val state = StreamingState(listOf(server))
        assertEquals(listOf("Spotify", "Tidal"), state.searchable.map { it.second.name })
        assertTrue(state.offered)
        assertFalse(StreamingState().offered)
    }
}

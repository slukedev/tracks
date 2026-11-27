package com.luke.tracks.library.trackside

import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.ServerEndpoints
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun fixture(name: String): String =
    requireNotNull(SpeakerWireTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

/** The speaker fixtures of the wire contract, read as Tracks reads them. */
class SpeakerWireTest {
    @Test fun theSpeakerList() {
        val list = TracksideJson.decodeFromString(TracksideSpeakerList.serializer(), fixture("speakers.json"))
        assertEquals("ok", list.state)
        assertEquals(listOf("Kitchen", "Living Room", "Bedroom", "Office"), list.speakers.map { it.name })

        val kitchen = list.speakers[0]
        assertEquals("o4j8fccgajqsqxancbfcz", kitchen.id)
        assertEquals(TracksideSpeaker.Kind.Speaker, kitchen.kindValue)
        assertEquals(TracksideSpeaker.Availability.Available, kitchen.availabilityValue)
        assertTrue(kitchen.isLeader)
        assertEquals(listOf("o4j8fccgajqsqxancbfcz", "o7sny3kfnvsjbbhx01f2j"), kitchen.group!!.memberIds)
        assertEquals(40, kitchen.groupVolume)
        assertEquals(35, kitchen.volume)
        assertEquals(false, kitchen.muted)
        assertEquals(TracksideSpeaker.State.Playing, kitchen.stateValue)
        assertEquals(81_000L, kitchen.playback!!.positionMs)
        assertEquals("2026-11-14T09:30:00.000Z", kitchen.playback!!.positionAt)
        assertEquals("Halo", kitchen.nowPlaying!!.title)
        assertEquals("halo\u001fbeyonce", kitchen.nowPlaying!!.songKey)
        assertEquals("sdjcz08vf67h7cx28snq6", kitchen.nowPlaying!!.songId)
        assertEquals("album", kitchen.context!!.kind)
        assertEquals(1042L, kitchen.revision)
        assertTrue(kitchen.can(SpeakerCapability.SEEK) && kitchen.can(SpeakerCapability.GROUP))

        val living = list.speakers[1]
        assertTrue(living.isMember)
        assertEquals("o4j8fccgajqsqxancbfcz", living.group!!.leaderId)
        assertNull(living.groupVolume)

        val bedroom = list.speakers[2]
        assertEquals(TracksideSpeaker.Availability.PoweredOff, bedroom.availabilityValue)
        assertEquals(listOf("play", "power"), bedroom.capabilities)
        assertEquals(false, bedroom.powered)
        assertNull(bedroom.volume)
        assertNull("nothing loaded", bedroom.playback)

        val office = list.speakers[3]
        assertEquals(TracksideSpeaker.Availability.Unavailable, office.availabilityValue)
        assertTrue("an unreachable speaker lists no capabilities", office.capabilities.isEmpty())
        assertEquals(TracksideSpeaker.State.Unknown, office.stateValue)
    }

    @Test fun aCatalogItemAndTheRelay() {
        val bedroom = TracksideJson.decodeFromString(TracksideSpeaker.serializer(), fixture("speaker.json"))
        assertEquals("Don't Stop Me Now", bedroom.nowPlaying!!.title)
        assertNull(bedroom.nowPlaying!!.songId)
        assertEquals("mc1.dHJhY2sfc3BvdGlmeR81VDhFRFVEcUtjczZPU093RXNmcUc3", bedroom.nowPlaying!!.ref)
        assertEquals("bqtyy4kcexcsg6wa0y4w5", bedroom.nowPlaying!!.serviceId)
        assertEquals("all", bedroom.playback!!.repeat)
        assertEquals(true, bedroom.playback!!.shuffle)
        assertNull("no id for a catalog context", bedroom.context!!.id)

        val relay = TracksideJson.decodeFromString(TracksideSpeaker.serializer(), fixture("relay-speaker.json"))
        assertEquals(TracksideSpeaker.Kind.Phone, relay.kindValue)
        assertEquals(true, relay.relay!!.thisDevice)
        assertFalse(relay.can(SpeakerCapability.VOLUME))
    }

    @Test fun whatTheServerCantReach() {
        val list = TracksideJson.decodeFromString(TracksideSpeakerList.serializer(), fixture("speakers-unavailable.json"))
        assertEquals("unavailable", list.state)
        assertTrue(list.speakers.isEmpty())
        assertEquals("2026-11-14T09:28:00.000Z", list.since)
        val event = TracksideJson.decodeFromString(SpeakerEvents.State.serializer(), fixture("speakers-event.json"))
        assertEquals("unavailable", event.state)

        val unavailable = TracksideClient.error(TracksideAnswer(503, fixture("error-speakers-unavailable.json"), retryAfter = "10"))
        assertTrue("$unavailable", unavailable is TracksideError.SpeakersUnavailable)
        assertEquals(10L, (unavailable as TracksideError.SpeakersUnavailable).retryAfterSeconds)
        val unsupported = TracksideClient.error(TracksideAnswer(422, fixture("error-speaker-unsupported.json")))
        assertTrue("$unsupported", unsupported is TracksideError.Server)
        assertEquals("unsupported", (unsupported as TracksideError.Server).code)
        assertEquals("seek", unsupported.capability)
        val offline = TracksideClient.error(TracksideAnswer(409, """{"error":{"code":"conflict","message":"x","details":{"reason":"speaker_offline"}}}"""))
        assertEquals("speaker_offline", (offline as TracksideError.Server).reason)
    }

    @Test fun commandsAndEvents() {
        val command = TracksideJson.decodeFromString(TracksideSpeakerCommand.serializer(), fixture("speaker-command.json"))
        assertEquals("pause", command.action)
        assertEquals("j7bwksg3pg57nf95cwz0b", command.commandId)
        assertEquals("2026-11-14T09:30:05.000Z", command.settleBy)
        assertEquals(TracksideSpeaker.State.Paused, command.speaker.stateValue)
        assertEquals("the expectation keeps the revision it's based on", 980L, command.speaker.revision)

        val failed = TracksideJson.decodeFromString(SpeakerEvents.Command.serializer(), fixture("speaker-command-event.json"))
        assertEquals("failed", failed.outcome)
        assertEquals("command_failed", failed.error!!.code)
        assertEquals("o0jjvqt740t3wm3jvetx2", failed.speakerId)

        val progress = TracksideJson.decodeFromString(SpeakerEvents.Progress.serializer(), fixture("speaker-progress-event.json"))
        assertEquals(111_000L, progress.positionMs)
        assertEquals(1042L, progress.revision)
        assertEquals("playing", progress.state)
        assertEquals("osm579gnwm00ysy1hbvhq", TracksideJson.decodeFromString(SpeakerEvents.Removed.serializer(), fixture("speaker-removed-event.json")).id)
        assertEquals(1043L, TracksideJson.decodeFromString(SpeakerEvents.Queue.serializer(), fixture("speaker-queue-event.json")).revision)

        val queue = TracksideJson.decodeFromString(TracksideSpeakerQueue.serializer(), fixture("speaker-queue.json"))
        assertEquals(0, queue.currentIndex)
        assertEquals(12, queue.total)
        assertEquals("eyJvIjoyfQ", queue.nextCursor)
        assertEquals("sdjcz08vf67h7cx28snq6", queue.items[0].songId)
        assertEquals("bqtyy4kcexcsg6wa0y4w5", queue.items[1].serviceId)
    }

    @Test fun thePlayRequestIsWrittenAsTheContractWritesIt() {
        val request = TracksideSpeakerPlayRequest(
            items = listOf(
                TracksideSpeakerPlayRequest.Item(songId = "sdjcz08vf67h7cx28snq6"),
                TracksideSpeakerPlayRequest.Item(ref = "mc1.dHJhY2sfc3BvdGlmeR8wV3FkMHpBNXdOcFFuUUJHRVVIMGY0"),
                TracksideSpeakerPlayRequest.Item(songId = "sqfnw07xdpvnbah038cz8"),
            ),
            mode = "replace",
            startIndex = 0,
            positionMs = 81_000,
            shuffle = false,
            repeat = "off",
            context = TracksideSpeaker.Context(kind = "album", id = "a3pqh4wnvzkjak75rxbft", name = "I Am... Sasha Fierce"),
        )
        val written = TracksideJson.parseToJsonElement(TracksideJson.encodeToString(TracksideSpeakerPlayRequest.serializer(), request))
        assertEquals(TracksideJson.parseToJsonElement(fixture("speaker-play-request.json")), written)
        assertEquals(request, TracksideJson.decodeFromString(TracksideSpeakerPlayRequest.serializer(), fixture("speaker-play-request.json")))
    }

    @Test fun unknownValuesReadAsUnknownAndNewFieldsAreIgnored() {
        val speaker = TracksideJson.decodeFromString(
            TracksideSpeaker.serializer(),
            """{"id":"oabc","name":"Den","kind":"soundbar","availability":"sleeping","capabilities":["play","teleport"],"playback":{"state":"rewinding","position_ms":5,"lyrics":true},"future":{"a":1},"revision":3}""",
        )
        assertEquals(TracksideSpeaker.Kind.Unknown, speaker.kindValue)
        assertEquals(TracksideSpeaker.Availability.Unavailable, speaker.availabilityValue)
        assertEquals(TracksideSpeaker.State.Unknown, speaker.stateValue)
        assertTrue(speaker.can("play"))
        assertFalse(speaker.can(SpeakerCapability.SEEK))
    }

    @Test fun featuresSayWhetherToLookForSpeakers() {
        val caps = TracksideJson.decodeFromString(TracksideCapabilities.serializer(), fixture("capabilities.json"))
        assertEquals(true, caps.features?.speakers)
        val me = TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me.json"))
        assertTrue(me.offersSpeakers)
        assertTrue(me.controlsSpeakers)
        assertEquals(true, me.user.permissions?.canUseSpeakers)
        val old = TracksideJson.decodeFromString(TracksideMe.serializer(), fixture("me-without-acquisition.json"))
        assertFalse(old.offersSpeakers)
    }
}

class SpeakerClientTest {
    private val key = "tsk_9pQvL2mN6tY0wB4kD9hJ1sF5gA7cE3uI8oP2zR6x"
    private val home = Endpoint("http://192.168.1.20:4747".toHttpUrl(), Endpoint.Kind.Lan)

    @Test fun anOldServerIsNeverAskedForSpeakers() = runTest {
        val asked = mutableListOf<String>()
        val transport = TracksideTransport { request, _ ->
            asked += request.url.encodedPath
            when (request.url.pathSegments.last()) {
                "capabilities" -> TracksideAnswer(200, fixture("capabilities.json").replace("\"speakers\": true,", ""))
                else -> TracksideAnswer(404, fixture("error-not-found.json"))
            }
        }
        val client = TracksideClient(ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true }), key, "d1", null, transport)
        assertEquals(TracksideClient.SpeakerAccess.None, client.speakerAccess())
        assertEquals(listOf("/api/v1/capabilities"), asked)
    }

    @Test fun aServerWithoutSpeakersSetUpForYouIsTheSame() = runTest {
        val transport = TracksideTransport { request, _ ->
            when (request.url.pathSegments.last()) {
                "capabilities" -> TracksideAnswer(200, fixture("capabilities.json"))
                "me" -> TracksideAnswer(200, fixture("me-without-acquisition.json"))
                else -> TracksideAnswer(404, fixture("error-not-found.json"))
            }
        }
        val client = TracksideClient(ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true }), key, "d1", null, transport)
        assertEquals(TracksideClient.SpeakerAccess.None, client.speakerAccess())
    }

    @Test fun commandsCarryAKeyAndARetryKeepsIt() = runTest {
        val sent = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        var lose = true
        val transport = TracksideTransport { request, _ ->
            sent += request
            bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
            if (lose) {
                lose = false
                throw java.io.IOException("reset")
            }
            TracksideAnswer(202, fixture("speaker-command.json"), date = "Sat, 14 Nov 2026 09:30:00 GMT")
        }
        val client = TracksideClient(
            ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true }), key, "d1", null, transport,
            now = { java.time.Instant.parse("2026-11-14T09:29:50Z") },
        )
        val command = client.speakerPlayback("o0jjvqt740t3wm3jvetx2", TracksideSpeakerPlayback("pause"))
        assertEquals("j7bwksg3pg57nf95cwz0b", command.commandId)
        assertEquals(2, sent.size)
        assertEquals("/api/v1/speakers/o0jjvqt740t3wm3jvetx2/playback", sent[0].url.encodedPath)
        assertEquals("POST", sent[0].method)
        assertEquals(sent[0].header("Idempotency-Key"), sent[1].header("Idempotency-Key"))
        assertEquals("""{"action":"pause"}""", bodies[0])
        assertEquals("the server's clock is ten seconds ahead", 10_000L, client.clockSkewMs)

        // A toggle is never sent twice.
        lose = true
        try {
            client.speakerPlayback("o0jjvqt740t3wm3jvetx2", TracksideSpeakerPlayback("toggle"))
            fail("a lost toggle isn't retried")
        } catch (_: java.io.IOException) {
        }

        lose = false
        client.patchSpeaker("o0jjvqt740t3wm3jvetx2", TracksideSpeakerPatch(volume = 30))
        assertEquals("PATCH", sent.last().method)
        assertEquals("""{"volume":30}""", bodies.last())
        client.setSpeakerMembers("o4j8fccgajqsqxancbfcz", listOf("o7sny3kfnvsjbbhx01f2j"))
        assertEquals("PUT", sent.last().method)
        assertEquals("""{"member_ids":["o7sny3kfnvsjbbhx01f2j"]}""", bodies.last())
        client.leaveSpeakerGroup("o7sny3kfnvsjbbhx01f2j")
        assertEquals("DELETE", sent.last().method)
        assertEquals("/api/v1/speakers/o7sny3kfnvsjbbhx01f2j/group", sent.last().url.encodedPath)
        sent.forEach { assertEquals("Bearer $key", it.header("Authorization")) }
    }

    @Test fun theListIsAskedOnlyForChanges() = runTest {
        var etag: String? = null
        val transport = TracksideTransport { request, _ ->
            etag = request.header("If-None-Match")
            if (etag == "W/\"1042\"") TracksideAnswer(304, "") else TracksideAnswer(200, fixture("speakers.json"), etag = "W/\"1042\"")
        }
        val client = TracksideClient(ServerEndpoints(listOf(home), home.url, backgroundScope, { _, _ -> true }), key, "d1", null, transport)
        val (list, version) = client.speakers()!!
        assertEquals(4, list.speakers.size)
        assertNull(etag)
        assertNull("unchanged", client.speakers(version))
        assertEquals("W/\"1042\"", etag)
    }
}

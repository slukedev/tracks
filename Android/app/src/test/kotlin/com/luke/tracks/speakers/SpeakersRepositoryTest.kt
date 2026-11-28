package com.luke.tracks.speakers

import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideEvent
import com.luke.tracks.library.trackside.TracksideJson
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.library.trackside.TracksideSpeakerCommand
import com.luke.tracks.library.trackside.TracksideSpeakerList
import com.luke.tracks.library.trackside.TracksideSpeakerPatch
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.library.trackside.TracksideSpeakerPlayback
import com.luke.tracks.library.trackside.TracksideSpeakerQueue
import com.luke.tracks.library.trackside.TracksideSpeakerTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun fixture(name: String): String =
    requireNotNull(SpeakersRepositoryTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

private const val SERVER = "s1"
private val KITCHEN = SpeakerKey(SERVER, "o4j8fccgajqsqxancbfcz")
private val LIVING = SpeakerKey(SERVER, "o7sny3kfnvsjbbhx01f2j")
private val BEDROOM = SpeakerKey(SERVER, "o0jjvqt740t3wm3jvetx2")

/** The list from the fixtures, with Bedroom on and playing a catalog track (speaker.json). */
private fun fixtureList(): TracksideSpeakerList {
    val all = TracksideJson.decodeFromString(TracksideSpeakerList.serializer(), fixture("speakers.json"))
    val bedroom = TracksideJson.decodeFromString(TracksideSpeaker.serializer(), fixture("speaker.json"))
    return all.copy(speakers = all.speakers.map { if (it.id == bedroom.id) bedroom else it })
}

/** A server as a test wants it: each command answered at once, or when [gate] opens, or refused. */
private class FakeClient(var list: TracksideSpeakerList = fixtureList()) : SpeakerClient {
    var access = TracksideClient.SpeakerAccess.Control
    var unreachable = false
    var refusal: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    var commandId = "j7bwksg3pg57nf95cwz0b"
    var settleBy: String? = "2026-11-14T09:30:05.000Z"
    var read: TracksideSpeaker? = null
    val sent = mutableListOf<String>()
    val volumes = mutableListOf<Int>()
    var listed = 0
    var reads = 0

    override suspend fun access(): TracksideClient.SpeakerAccess {
        if (unreachable) throw java.io.IOException("offline")
        return access
    }
    override suspend fun speakers(version: String?): Pair<TracksideSpeakerList, String?> {
        listed++
        return list to null
    }
    override suspend fun speaker(id: String): TracksideSpeaker {
        reads++
        return read ?: list.speakers.first { it.id == id }
    }
    override suspend fun queue(id: String, limit: Int) = TracksideJson.decodeFromString(TracksideSpeakerQueue.serializer(), fixture("speaker-queue.json"))

    private suspend fun answer(id: String, what: String, expect: (TracksideSpeaker) -> TracksideSpeaker): TracksideSpeakerCommand {
        sent += what
        gate?.await()
        refusal?.let { throw it }
        return TracksideSpeakerCommand(what, commandId, settleBy, expect(list.speakers.first { it.id == id }))
    }

    override suspend fun playback(id: String, command: TracksideSpeakerPlayback) = answer(id, command.action) { speaker ->
        when (command.action) {
            "pause" -> speaker.copy(playback = speaker.playback?.copy(state = "paused"))
            "play" -> speaker.copy(playback = speaker.playback?.copy(state = "playing"))
            "seek" -> speaker.copy(playback = speaker.playback?.copy(positionMs = command.positionMs ?: 0))
            else -> speaker
        }
    }
    override suspend fun patch(id: String, patch: TracksideSpeakerPatch) = answer(id, "patch") { speaker ->
        patch.volume?.let { volumes += it }
        speaker.copy(volume = patch.volume ?: speaker.volume, muted = patch.muted ?: speaker.muted, powered = patch.powered ?: speaker.powered)
    }
    override suspend fun members(id: String, memberIds: List<String>) = answer(id, "members") { speaker ->
        speaker.copy(group = TracksideSpeaker.Group(id, listOf(id) + memberIds, "leader"))
    }
    override suspend fun leaveGroup(id: String) = answer(id, "leave") { it.copy(group = null) }
    override suspend fun play(id: String, request: TracksideSpeakerPlayRequest) = answer(id, "play-on") { speaker ->
        speaker.copy(playback = TracksideSpeaker.Playback("playing", request.positionMs ?: 0, "2026-11-14T09:30:00.000Z"))
    }
    override suspend fun transfer(id: String, transfer: TracksideSpeakerTransfer) = answer(id, "transfer") { it }
    override val clockSkewMs = 0L
}

private class MemoryStore(var saved: SpeakerKey? = null) : ActiveSpeakerStore {
    override fun load() = saved
    override fun save(key: SpeakerKey?) {
        saved = key
    }
}

private class FakeHandoff : LocalHandoff {
    var playingHere = true
    var paused = 0
    var resumed = 0
    override fun pauseForSpeaker(): Boolean {
        paused++
        val was = playingHere
        playingHere = false
        return was
    }
    override fun resumeHere() {
        resumed++
        playingHere = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakersRepositoryTest {
    private val start = Instant.parse("2026-11-14T09:30:00Z")

    private fun TestScope.repository(client: FakeClient?, store: ActiveSpeakerStore = MemoryStore()): SpeakersRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SpeakersRepository(
            scope = backgroundScope,
            clientFor = { client },
            store = store,
            now = { start.plusMillis(testScheduler.currentTime) },
            io = dispatcher,
            work = dispatcher,
        ).also {
            it.watchServers(MutableStateFlow(listOf(SpeakerServer(SERVER, "Home"))))
            it.connected(SERVER)
            runCurrent()
        }
    }

    private fun SpeakersRepository.speaker(key: SpeakerKey) = state.value.speaker(key)!!

    private fun TestScope.messages(repository: SpeakersRepository): MutableList<SpeakerMessage> {
        val said = mutableListOf<SpeakerMessage>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { repository.messages.toList(said) }
        runCurrent()
        return said
    }

    private suspend fun SpeakersRepository.event(type: String, data: String) = handle(SERVER, TracksideEvent(type, data))

    @Test fun listsTheServersSpeakers() = runTest {
        val repository = repository(FakeClient())
        val server = repository.state.value.servers.single()
        assertEquals(ServerSpeakers.Status.Ok, server.status)
        assertTrue(server.canControl)
        assertEquals(listOf("Kitchen", "Living Room", "Bedroom", "Office"), server.speakers.map { it.name })
    }

    @Test fun anOldServerOrOneWithoutSpeakersShowsNothing() = runTest {
        val client = FakeClient().apply { access = TracksideClient.SpeakerAccess.None }
        val repository = repository(client)
        assertTrue(repository.state.value.servers.isEmpty())
        assertEquals("the list is never asked for", 0, client.listed)
    }

    @Test fun aPauseShowsAtOnceThenTheServersExpectationThenTheTruth() = runTest {
        val client = FakeClient().apply { gate = CompletableDeferred() }
        val repository = repository(client)
        repository.pause(BEDROOM)
        runCurrent()
        assertEquals("before the server answers", TracksideSpeaker.State.Paused, repository.speaker(BEDROOM).stateValue)
        assertEquals(listOf("pause"), client.sent)

        client.gate!!.complete(Unit)
        runCurrent()
        assertEquals(TracksideSpeaker.State.Paused, repository.speaker(BEDROOM).stateValue)

        repository.event("speaker_command", """{"command_id":"j7bwksg3pg57nf95cwz0b","speaker_id":"${BEDROOM.speakerId}","outcome":"done"}""")
        val confirmed = client.list.speakers.first { it.id == BEDROOM.speakerId }
        val paused = confirmed.copy(revision = 981, playback = confirmed.playback!!.copy(state = "paused", positionMs = 15_000))
        repository.event("speaker_updated", TracksideJson.encodeToString(TracksideSpeaker.serializer(), paused))
        runCurrent()
        assertEquals("the update is the truth", paused, repository.speaker(BEDROOM))

        // Nothing is left waiting to fall back later.
        advanceTimeBy(20_000)
        assertEquals(paused, repository.speaker(BEDROOM))
        assertEquals(0, client.reads)
    }

    @Test fun aCommandThatFailsFallsBackAndSaysSo() = runTest {
        val client = FakeClient().apply { commandId = "j8r94fwwwtm0mgm02w6gx" }
        val repository = repository(client)
        val said = messages(repository)
        repository.pause(BEDROOM)
        runCurrent()
        assertEquals(TracksideSpeaker.State.Paused, repository.speaker(BEDROOM).stateValue)

        repository.event("speaker_command", fixture("speaker-command-event.json"))
        runCurrent()
        assertEquals(TracksideSpeaker.State.Playing, repository.speaker(BEDROOM).stateValue)
        assertEquals(listOf("Bedroom couldn't do that."), said.map { it.text })
    }

    @Test fun anOutcomeArrivingBeforeTheAnswerStillCounts() = runTest {
        val client = FakeClient().apply {
            commandId = "j8r94fwwwtm0mgm02w6gx"
            gate = CompletableDeferred()
        }
        val repository = repository(client)
        repository.pause(BEDROOM)
        runCurrent()
        repository.event("speaker_command", fixture("speaker-command-event.json"))
        client.gate!!.complete(Unit)
        runCurrent()
        assertEquals(TracksideSpeaker.State.Playing, repository.speaker(BEDROOM).stateValue)
    }

    @Test fun aRefusalFallsBackAtOnce() = runTest {
        val client = FakeClient().apply { refusal = TracksideError.SpeakersUnavailable(10) }
        val repository = repository(client)
        val said = messages(repository)
        repository.setMuted(BEDROOM, true)
        runCurrent()
        assertEquals(false, repository.speaker(BEDROOM).muted)
        assertEquals(listOf("Speakers aren't available right now."), said.map { it.text })

        client.refusal = java.io.IOException("unreachable")
        repository.setMuted(BEDROOM, true)
        runCurrent()
        assertEquals("Couldn't reach your server.", said.last().text)
    }

    @Test fun pastSettleByWithoutAnOutcomeTheSpeakerIsReadAgain() = runTest {
        val client = FakeClient()
        val repository = repository(client)
        val said = messages(repository)
        repository.pause(BEDROOM)
        runCurrent()
        assertEquals(TracksideSpeaker.State.Paused, repository.speaker(BEDROOM).stateValue)

        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("still waiting", TracksideSpeaker.State.Paused, repository.speaker(BEDROOM).stateValue)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, client.reads)
        assertEquals("it never paused", TracksideSpeaker.State.Playing, repository.speaker(BEDROOM).stateValue)
        assertEquals(listOf("Bedroom didn't respond."), said.map { it.text })
    }

    @Test fun theVolumeAnswersAtOnceAndOnlyTheLatestIsSent() = runTest {
        val client = FakeClient()
        val repository = repository(client)
        listOf(10, 20, 30, 40).forEach { repository.setVolume(BEDROOM, it) }
        runCurrent()
        assertEquals(40, repository.speaker(BEDROOM).volume)
        assertEquals(listOf(40), client.volumes)

        repository.setVolume(BEDROOM, 55)
        repository.setVolume(BEDROOM, 60)
        runCurrent()
        assertEquals(60, repository.speaker(BEDROOM).volume)
        assertEquals("no more than ten a second", listOf(40), client.volumes)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf(40, 60), client.volumes)
    }

    @Test fun aNewerValueOnTheSameControlIsntUndoneByAnOlderFailure() = runTest {
        val client = FakeClient().apply { gate = CompletableDeferred() }
        val repository = repository(client)
        val said = messages(repository)
        repository.setMuted(BEDROOM, true)
        runCurrent()
        // The first is refused; the second, sent after, is still on its way.
        client.refusal = TracksideError.Server(409, "conflict", "", reason = "speaker_offline")
        val first = client.gate!!
        client.gate = CompletableDeferred()
        repository.setMuted(BEDROOM, true)
        runCurrent()
        first.complete(Unit)
        runCurrent()
        assertEquals("the newer mute still shows", true, repository.speaker(BEDROOM).muted)
        assertTrue("an overtaken failure says nothing", said.isEmpty())
    }

    @Test fun groupingMovesTheOtherSpeakersAtOnce() = runTest {
        val client = FakeClient().apply { gate = CompletableDeferred() }
        val repository = repository(client)
        repository.setMembers(KITCHEN, listOf(LIVING.speakerId, BEDROOM.speakerId))
        runCurrent()
        val bedroom = repository.speaker(BEDROOM)
        assertTrue(bedroom.isMember)
        assertEquals(KITCHEN.speakerId, bedroom.group!!.leaderId)
        assertEquals(listOf(KITCHEN.speakerId, LIVING.speakerId, BEDROOM.speakerId), repository.speaker(KITCHEN).group!!.memberIds)

        // Ungrouping lets them go.
        repository.setMembers(KITCHEN, emptyList())
        runCurrent()
        assertNull(repository.speaker(KITCHEN).group)
        assertNull(repository.speaker(LIVING).group)
        assertNull(repository.speaker(BEDROOM).group)
    }

    @Test fun castingPausesThePhoneAndPlaysItAgainIfTheSpeakerCantTakeIt() = runTest {
        val client = FakeClient().apply {
            gate = CompletableDeferred()
            commandId = "j8r94fwwwtm0mgm02w6gx"
        }
        val store = MemoryStore()
        val repository = repository(client, store)
        val handoff = FakeHandoff()
        val request = TracksideSpeakerPlayRequest(items = listOf(TracksideSpeakerPlayRequest.Item(songId = "sdjcz08vf67h7cx28snq6")), startIndex = 0, positionMs = 30_000)
        repository.play(BEDROOM, request, TracksideSpeaker.NowPlaying(title = "Halo", artist = "Beyoncé"), handoff)
        runCurrent()
        assertEquals(BEDROOM, repository.state.value.active)
        assertEquals("kept for after the system closes the app", BEDROOM, store.saved)
        assertTrue(BEDROOM in repository.state.value.connecting)
        assertEquals("Halo", repository.state.value.casting!!.nowPlaying!!.title)
        assertEquals("the phone keeps playing until the server takes it", 0, handoff.paused)

        client.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, handoff.paused)

        repository.event("speaker_command", fixture("speaker-command-event.json"))
        runCurrent()
        assertEquals(1, handoff.resumed)
        assertTrue(handoff.playingHere)
        assertFalse(BEDROOM in repository.state.value.connecting)
        assertNull("back to the phone", repository.state.value.active)
    }

    @Test fun aSpeakerDroppingMidCastLetsGoAndSaysSo() = runTest {
        val repository = repository(FakeClient())
        val said = messages(repository)
        repository.choose(BEDROOM)
        runCurrent()
        assertEquals("Bedroom", repository.state.value.casting!!.name)

        repository.event("speaker_removed", """{"id":"${BEDROOM.speakerId}"}""")
        runCurrent()
        assertNull(repository.state.value.active)
        assertNull(repository.state.value.speaker(BEDROOM))
        assertEquals(listOf("Bedroom isn't available anymore."), said.map { it.text })
    }

    @Test fun theServerLosingItsSpeakersIsACalmStateAndTheyComeBack() = runTest {
        val client = FakeClient()
        val repository = repository(client)
        repository.event("speakers", fixture("speakers-event.json"))
        runCurrent()
        val server = repository.state.value.servers.single()
        assertEquals(ServerSpeakers.Status.Unavailable, server.status)
        assertTrue(server.speakers.isEmpty())

        repository.event("speakers", """{"state":"ok"}""")
        runCurrent()
        assertEquals(2, client.listed)
        assertEquals(ServerSpeakers.Status.Ok, repository.state.value.servers.single().status)
        assertEquals(4, repository.state.value.servers.single().speakers.size)

        client.list = TracksideJson.decodeFromString(TracksideSpeakerList.serializer(), fixture("speakers-unavailable.json"))
        repository.connected(SERVER)
        runCurrent()
        assertEquals(ServerSpeakers.Status.Unavailable, repository.state.value.servers.single().status)
    }

    @Test fun progressMovesThePositionAndItRunsOnBetweenEvents() = runTest {
        val repository = repository(FakeClient())
        repository.event("speaker_progress", fixture("speaker-progress-event.json"))
        runCurrent()
        val kitchen = repository.speaker(KITCHEN)
        assertEquals(111_000L, kitchen.playback!!.positionMs)
        // 09:30:30 said 111 s; at 09:30:40 it's 121 s in, and never past the end.
        assertEquals(121_000L, SpeakerClock.position(kitchen, Instant.parse("2026-11-14T09:30:40Z")))
        assertEquals(261_640L, SpeakerClock.position(kitchen, Instant.parse("2026-11-14T10:30:00Z")))
        val paused = kitchen.copy(playback = kitchen.playback!!.copy(state = "paused"))
        assertEquals(111_000L, SpeakerClock.position(paused, Instant.parse("2026-11-14T09:31:00Z")))
    }

    @Test fun olderUpdatesDontUndoNewerOnes() = runTest {
        val repository = repository(FakeClient())
        val kitchen = repository.speaker(KITCHEN)
        repository.event("speaker_updated", TracksideJson.encodeToString(TracksideSpeaker.serializer(), kitchen.copy(revision = 1000, volume = 5)))
        runCurrent()
        assertEquals(35, repository.speaker(KITCHEN).volume)
    }

    @Test fun theChosenSpeakerOutlivesTheProcessUntilItsGone() = runTest {
        val store = MemoryStore(BEDROOM)
        val repository = repository(FakeClient(), store)
        assertEquals(BEDROOM, repository.state.value.active)

        val gone = MemoryStore(SpeakerKey(SERVER, "oremoved00000000000000"))
        val again = repository(FakeClient(), gone)
        assertNull(again.state.value.active)
        assertNull(gone.saved)
    }

    @Test fun signingOutTakesTheServersSpeakersAway() = runTest {
        val repository = repository(FakeClient(), MemoryStore(BEDROOM))
        repository.signedOut(SERVER)
        runCurrent()
        assertTrue(repository.state.value.servers.isEmpty())
        assertNull(repository.state.value.active)
    }

    @Test fun anUnreachableServerSaysSoQuietly() = runTest {
        val never = repository(FakeClient().apply { unreachable = true })
        assertTrue("never seen, so nothing shows", never.state.value.servers.isEmpty())

        // One already showing says it can't be reached, and keeps what it last saw.
        val client = FakeClient()
        val repository = repository(client)
        client.unreachable = true
        repository.refresh(SERVER)
        runCurrent()
        val server = repository.state.value.servers.single()
        assertEquals(ServerSpeakers.Status.Unreachable, server.status)
        assertEquals(4, server.speakers.size)
    }
}

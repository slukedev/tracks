package com.luke.tracks.lastfm

import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tracks_engine.ScrobbleOutcome
import java.io.IOException

class ScrobbleDrainTest {
    private fun play(n: Int, attempts: Int = 0) = PlayEntity(
        id = "p$n", title = "Song $n", artistName = "Artist", albumTitle = null, artworkUri = null,
        playedAt = 1_700_000_000_000L + n * 240_000L, durationMs = 200_000, listenedMs = 200_000,
        kind = PlayKindValue.OnDemand, source = PlaySourceValue.Streaming, appPackage = null, appName = null,
        stationName = null, mediaId = "", scrobbleAttempts = attempts,
    )

    /** An in-memory queue that behaves like the DAO's owed query. */
    private class FakeQueue(plays: List<PlayEntity>) : ScrobbleQueue {
        val rows = plays.associateBy { it.id }.toMutableMap()
        var reads = 0
        override suspend fun owed(limit: Int): List<PlayEntity> {
            reads++
            return rows.values.filter { it.scrobbledAt == null && it.scrobbleError == null }.sortedBy { it.playedAt }.take(limit)
        }
        override suspend fun markScrobbled(ids: List<String>, at: Long) = ids.forEach { rows[it] = rows.getValue(it).copy(scrobbledAt = at) }
        override suspend fun markFailed(id: String, reason: String) {
            rows[id] = rows.getValue(id).copy(scrobbleError = reason, scrobbleAttempts = rows.getValue(id).scrobbleAttempts + 1)
        }
        override suspend fun noteAttempt(ids: List<String>) = ids.forEach { rows[it] = rows.getValue(it).copy(scrobbleAttempts = rows.getValue(it).scrobbleAttempts + 1) }
        fun owedCount() = rows.values.count { it.scrobbledAt == null && it.scrobbleError == null }
    }

    private fun drain(queue: ScrobbleQueue, submit: suspend (List<PlayEntity>) -> List<ScrobbleOutcome>) = ScrobbleDrain(
        queue = queue,
        submit = submit,
        isRetryable = { it is IOException },
        isAccountProblem = { it is SecurityException },
        describe = { it.message ?: "error" },
        clock = { 42L },
    )

    @Test
    fun sendsInBatchesOfFifty() = runBlocking {
        val queue = FakeQueue((1..120).map { play(it) })
        val batches = mutableListOf<Int>()
        val result = drain(queue) { batch -> batches += batch.size; batch.map { ScrobbleOutcome.Accepted } }.run()
        assertEquals(listOf(50, 50, 20), batches)
        assertEquals(DrainResult.Done(120), result)
        assertEquals(0, queue.owedCount())
        assertTrue(queue.rows.values.all { it.scrobbledAt == 42L })
    }

    @Test
    fun sendsOldestFirst() = runBlocking {
        val queue = FakeQueue(listOf(play(3), play(1), play(2)))
        val order = mutableListOf<String>()
        drain(queue) { batch -> order += batch.map { it.id }; batch.map { ScrobbleOutcome.Accepted } }.run()
        assertEquals(listOf("p1", "p2", "p3"), order)
    }

    @Test
    fun nothingOwedSendsNothing() = runBlocking {
        var calls = 0
        val result = drain(FakeQueue(emptyList())) { calls++; emptyList() }.run()
        assertEquals(DrainResult.Done(0), result)
        assertEquals(0, calls)
    }

    @Test
    fun ignoredForGoodStopsBeingOwedWithItsReason() = runBlocking {
        val queue = FakeQueue((1..3).map { play(it) })
        val result = drain(queue) { batch ->
            listOf(ScrobbleOutcome.Accepted, ScrobbleOutcome.Ignored(3, "Timestamp too old", false), ScrobbleOutcome.Accepted)
                .take(batch.size)
        }.run()
        assertEquals(DrainResult.Done(2), result)
        assertEquals("Too old for Last.fm", queue.rows.getValue("p2").scrobbleError)
        assertEquals(null, queue.rows.getValue("p2").scrobbledAt)
        assertEquals(42L, queue.rows.getValue("p1").scrobbledAt)
    }

    @Test
    fun aDailyLimitHoldsTheRestBackForLater() = runBlocking {
        val queue = FakeQueue((1..60).map { play(it) })
        var calls = 0
        val result = drain(queue) { batch ->
            calls++
            batch.mapIndexed { i, _ -> if (i < 10) ScrobbleOutcome.Accepted else ScrobbleOutcome.Ignored(5, "Daily limit", true) }
        }.run()
        assertEquals(1, calls)
        assertTrue(result is DrainResult.RetryLater)
        assertEquals(10, result.sent)
        assertEquals(50, queue.owedCount())
        assertTrue(queue.rows.values.none { it.scrobbleError != null })
    }

    @Test
    fun offlineLeavesEverythingOwedUntouched() = runBlocking {
        val queue = FakeQueue((1..5).map { play(it) })
        val result = drain(queue) { throw IOException("offline") }.run()
        assertEquals(DrainResult.RetryLater(0, "offline"), result)
        assertEquals(5, queue.owedCount())
        assertTrue(queue.rows.values.all { it.scrobbleAttempts == 0 })
    }

    @Test
    fun aRevokedSessionAsksToReconnect() = runBlocking {
        val queue = FakeQueue((1..5).map { play(it) })
        val result = drain(queue) { throw SecurityException("revoked") }.run()
        assertTrue(result is DrainResult.NeedsReconnect)
        assertEquals(5, queue.owedCount())
    }

    @Test
    fun aRefusedRequestChargesThePlaysAndGivesUpEventually() = runBlocking {
        val queue = FakeQueue(listOf(play(1, attempts = ScrobbleDrain.MAX_ATTEMPTS - 1), play(2)))
        val result = drain(queue) { throw IllegalStateException("bad request") }.run()
        assertTrue(result is DrainResult.RetryLater)
        assertEquals("bad request", queue.rows.getValue("p1").scrobbleError)
        assertEquals(null, queue.rows.getValue("p2").scrobbleError)
        assertEquals(1, queue.rows.getValue("p2").scrobbleAttempts)
    }

    @Test
    fun anEmptyAnswerDoesNotLoopForever() = runBlocking {
        val queue = FakeQueue((1..5).map { play(it) })
        val result = drain(queue) { emptyList() }.run()
        assertTrue(result is DrainResult.RetryLater)
        assertEquals(1, queue.reads)
    }

    @Test
    fun aShortAnswerLeavesTheUnsentOwed() = runBlocking {
        val queue = FakeQueue((1..3).map { play(it) })
        var calls = 0
        drain(queue) { batch -> calls++; if (calls == 1) listOf(ScrobbleOutcome.Accepted) else batch.map { ScrobbleOutcome.Accepted } }.run()
        assertEquals(0, queue.owedCount())
    }

    @Test
    fun settlementSortsEachAnswer() {
        val batch = (1..4).map { play(it) }
        val settlement = ScrobbleDrain.settle(
            batch,
            listOf(
                ScrobbleOutcome.Accepted,
                ScrobbleOutcome.Ignored(1, "Artist ignored", false),
                ScrobbleOutcome.Ignored(5, "Daily limit", true),
                ScrobbleOutcome.Accepted,
            ),
        )
        assertEquals(listOf("p1", "p4"), settlement.accepted)
        assertEquals(listOf("p2" to "Last.fm ignored the artist"), settlement.rejected)
        assertEquals("Last.fm's daily limit was reached", settlement.heldBack)
    }
}

package com.luke.tracks.tv

import com.luke.tracks.ui.tv.link.LinkException
import com.luke.tracks.ui.tv.link.LinkOffer
import com.luke.tracks.ui.tv.link.LinkPoll
import com.luke.tracks.ui.tv.link.LinkProblem
import com.luke.tracks.ui.tv.link.LinkServer
import com.luke.tracks.ui.tv.link.LinkStep
import com.luke.tracks.ui.tv.link.TvLinker
import com.luke.tracks.ui.tv.link.linkSteps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** Linking a TV: what it shows, how often it asks, and when it stops. Virtual time throughout. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkSessionTest {
    private val start = Instant.parse("2026-11-27T20:00:00Z")
    private fun TestScope.clock(): () -> Instant = { start.plusMillis(testScheduler.currentTime) }

    /** Records when each poll came, in virtual milliseconds. */
    private class Timed(private val scope: TestScope, private val inner: TvLinker) : TvLinker by inner {
        val pollTimes = mutableListOf<Long>()
        override suspend fun poll(offer: LinkOffer): LinkPoll {
            pollTimes += scope.testScheduler.currentTime
            return inner.poll(offer)
        }
    }

    @Test fun showsTheCodeThenLinksWhenApproved() = runTest {
        val linker = FakeLinker(polls = listOf(LinkPoll.Pending, LinkPoll.Pending, LinkPoll.Linked("Living Room Server")))
        val steps = linkSteps(linker, TvLinkDemo.server, clock()).toList()
        assertEquals(LinkStep.Starting, steps[0])
        assertEquals("Q7RM-4X2K", (steps[1] as LinkStep.Showing).offer.userCode)
        assertEquals(LinkStep.Linked("Living Room Server"), steps.last())
        assertEquals(3, linker.pollCount)
    }

    @Test fun waitsTheIntervalThenLongerForEachSlowDown() = runTest {
        val linker = Timed(this, FakeLinker(polls = listOf(LinkPoll.Pending, LinkPoll.SlowDown(5), LinkPoll.Pending, LinkPoll.SlowDown(30), LinkPoll.Linked("S"))))
        linkSteps(linker, TvLinkDemo.server, clock()).toList()
        val gaps = linker.pollTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(5_000L, 10_000L, 10_000L, 30_000L), gaps)
        assertEquals(5_000L, linker.pollTimes.first())
    }

    @Test fun aCodeThatRunsOutIsReplacedWithAFreshOne() = runTest {
        val first = TvLinkDemo.offer("AAAA-1111", expiresAt = start.plusSeconds(12))
        val second = TvLinkDemo.offer("BBBB-2222", expiresAt = start.plusSeconds(600))
        val linker = FakeLinker(offers = listOf(first, second), polls = listOf(LinkPoll.Pending, LinkPoll.Pending, LinkPoll.Linked("S")))
        val codes = linkSteps(linker, TvLinkDemo.server, clock()).toList().filterIsInstance<LinkStep.Showing>().map { it.offer.userCode }
        assertEquals(listOf("AAAA-1111", "BBBB-2222"), codes)
        assertEquals(2, linker.starts)
    }

    @Test fun theServerSayingExpiredAlsoRenews() = runTest {
        val linker = FakeLinker(offers = listOf(TvLinkDemo.offer("AAAA-1111"), TvLinkDemo.offer("BBBB-2222")), polls = listOf(LinkPoll.Expired, LinkPoll.Linked("S")))
        val steps = linkSteps(linker, TvLinkDemo.server, clock()).toList()
        assertEquals(2, steps.count { it is LinkStep.Showing })
        assertTrue(steps.last() is LinkStep.Linked)
    }

    @Test fun deniedAndOldServersStopWithOneSentence() = runTest {
        val denied = linkSteps(FakeLinker(polls = listOf(LinkPoll.Denied)), TvLinkDemo.server, clock()).toList()
        assertEquals(LinkStep.Failed(LinkProblem.Denied), denied.last())
        val old = linkSteps(FakeLinker(startProblem = LinkProblem.NeedsUpdate), TvLinkDemo.server, clock()).toList()
        assertEquals(listOf(LinkStep.Starting, LinkStep.Failed(LinkProblem.NeedsUpdate)), old)
    }

    @Test fun aDroppedConnectionBacksOffAndKeepsTheCode() = runTest {
        var failures = 2
        val linker = Timed(this, object : TvLinker by FakeLinker(polls = listOf(LinkPoll.Linked("S"))) {
            private val inner = FakeLinker(polls = listOf(LinkPoll.Linked("S")))
            override suspend fun poll(offer: LinkOffer): LinkPoll {
                if (failures-- > 0) throw IOException("offline")
                return inner.poll(offer)
            }
        })
        val steps = linkSteps(linker, TvLinkDemo.server, clock()).toList()
        assertEquals(1, steps.count { it is LinkStep.Showing })
        assertTrue(steps.last() is LinkStep.Linked)
        assertEquals(listOf(10_000L, 20_000L), linker.pollTimes.zipWithNext { a, b -> b - a })
    }

    @Test fun anythingButTracksideShapedOffersIsRefused() = runTest {
        val bad = listOf(
            TvLinkDemo.offer(code = "not a code"),
            TvLinkDemo.offer(interval = 0),
            TvLinkDemo.offer(interval = 3_600),
            TvLinkDemo.offer(expiresAt = start.minusSeconds(1)),
        )
        bad.forEach { offer ->
            val steps = linkSteps(FakeLinker(offers = listOf(offer)), TvLinkDemo.server, clock()).toList()
            assertEquals(LinkStep.Failed(LinkProblem.Failed), steps.last())
        }
    }

    @Test fun leavingTheScreenStopsAsking() = runTest {
        val linker = FakeLinker(polls = listOf(LinkPoll.Pending))
        val job = launch { linkSteps(linker, TvLinkDemo.server, clock()).collect {} }
        advanceTimeBy(16_000)
        runCurrent()
        val asked = linker.pollCount
        job.cancel()
        advanceTimeBy(60_000)
        assertEquals(3, asked)
        assertEquals(asked, linker.pollCount)
        assertFalse(job.isActive)
    }

    @Test fun theDeviceCodeNeverShowsInText() {
        val offer = TvLinkDemo.offer()
        assertFalse(offer.toString().contains(offer.deviceCode))
        assertFalse(LinkException(LinkProblem.Failed).toString().contains(offer.deviceCode))
    }

    @Test fun aServerNameFromTheServerIsTrimmedAndCapped() = runTest {
        val long = "x".repeat(500)
        val steps = linkSteps(FakeLinker(polls = listOf(LinkPoll.Linked("  $long  "))), LinkServer(null, "Fallback", TvLinkDemo.server.url), clock()).toList()
        assertEquals(80, (steps.last() as LinkStep.Linked).serverName.length)
        val blank = linkSteps(FakeLinker(polls = listOf(LinkPoll.Linked("  "))), LinkServer(null, "Fallback", TvLinkDemo.server.url), clock()).toList()
        assertEquals(LinkStep.Linked("Fallback"), blank.last())
    }
}

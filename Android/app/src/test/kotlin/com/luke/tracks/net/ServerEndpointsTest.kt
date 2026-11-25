package com.luke.tracks.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerEndpointsTest {
    private val lan = Endpoint("http://192.168.1.20:4747".toHttpUrl(), Endpoint.Kind.Lan)
    private val tailnet = Endpoint("http://100.101.102.103:4747".toHttpUrl(), Endpoint.Kind.Tailnet)
    private val public = Endpoint("https://music.example.com".toHttpUrl(), Endpoint.Kind.Public)

    /** Each address answers (true), fails (false) or never answers (null) after its delay. */
    private fun probe(vararg answers: Pair<Endpoint, Pair<Long, Boolean?>>, asked: MutableList<Endpoint> = mutableListOf()): suspend (Endpoint, Long) -> Boolean {
        val table = answers.toMap()
        return { endpoint, timeout ->
            asked += endpoint
            val (after, answer) = table[endpoint] ?: (0L to false)
            delay(after)
            answer ?: run { delay(timeout * 10); false }
        }
    }

    private fun TestScope.endpoints(probe: suspend (Endpoint, Long) -> Boolean, vararg candidates: Endpoint, current: Endpoint = candidates.first()) =
        ServerEndpoints(candidates.toList(), current.url, backgroundScope, probe, now = { currentTime })

    @Test fun theLocalAddressWinsWhenItAnswersWithinTheGrace() = runTest {
        val picked = ServerEndpoints.pick(listOf(public, lan), 300, 2_000, probe(public to (50L to true), lan to (250L to true)))
        assertEquals(lan, picked)
        assertEquals("decided as soon as the local address answered", 250, currentTime)
    }

    @Test fun aSlowLocalAddressLosesToTheFirstAnswerAfterTheGrace() = runTest {
        val picked = ServerEndpoints.pick(listOf(lan, tailnet), 300, 2_000, probe(tailnet to (100L to true), lan to (900L to true)))
        assertEquals(tailnet, picked)
        assertEquals(400, currentTime)
    }

    @Test fun awayFromHomeTheLocalAddressFailingDecidesAtOnce() = runTest {
        // Cellular: the home address is refused fast, so there's no grace to wait out.
        val picked = ServerEndpoints.pick(listOf(lan, public), 300, 2_000, probe(lan to (20L to false), public to (80L to true)))
        assertEquals(public, picked)
        assertEquals(80, currentTime)
    }

    @Test fun nothingAnsweringGivesUpAtTheTimeout() = runTest {
        val picked = ServerEndpoints.pick(listOf(lan, public), 300, 2_000, probe(lan to (0L to null), public to (0L to null)))
        assertNull(picked)
        assertEquals(2_000, currentTime)
    }

    @Test fun plainHttpBeyondTheHomeNetworkIsNeverAsked() = runTest {
        val exposed = Endpoint("http://music.example.com:4533".toHttpUrl(), Endpoint.Kind.Public)
        val asked = mutableListOf<Endpoint>()
        assertNull(ServerEndpoints.pick(listOf(exposed), 300, 2_000, probe(exposed to (0L to true), asked = asked)))
        assertTrue(asked.isEmpty())
    }

    @Test fun aCaptivePortalAnsweringForTheHomeAddressIsNotTheServer() = runTest {
        // The portal answers everything instantly, but can't give the proof: the public address is used.
        val picked = ServerEndpoints.pick(listOf(lan, public), 300, 2_000, probe(lan to (5L to false), public to (120L to true)))
        assertEquals(public, picked)
    }

    @Test fun nothingIsSentUntilAnAddressProvesItself() = runTest {
        val endpoints = endpoints(probe(lan to (0L to false), public to (0L to false)), lan, public)
        try {
            endpoints.provenBaseUrl()
            fail("an unproven address must not be used")
        } catch (_: Unproven) {
        }
    }

    @Test fun switchesToTheAddressThatProvedItself() = runTest {
        val endpoints = endpoints(probe(lan to (0L to false), public to (10L to true)), lan, public)
        assertEquals(public.url, endpoints.provenBaseUrl())
        assertEquals(public.url, endpoints.baseUrl)
    }

    @Test fun plainHttpProofRunsOutButHttpsDoesnt() = runTest {
        val asked = mutableListOf<Endpoint>()
        val endpoints = endpoints(probe(lan to (0L to true), asked = asked), lan)
        endpoints.provenBaseUrl()
        endpoints.provenBaseUrl()
        assertEquals(1, asked.size)
        delay(600_001)
        endpoints.provenBaseUrl()
        assertEquals("a plain http proof is asked again after ten minutes", 2, asked.size)

        val tlsAsked = mutableListOf<Endpoint>()
        val secure = endpoints(probe(public to (0L to true), asked = tlsAsked), public)
        secure.provenBaseUrl()
        delay(3_600_000)
        secure.provenBaseUrl()
        assertEquals(1, tlsAsked.size)
    }

    @Test fun requestsArrivingDuringARaceJoinIt() = runTest {
        val asked = mutableListOf<Endpoint>()
        val endpoints = endpoints(probe(lan to (100L to true), asked = asked), lan)
        val first = async { endpoints.provenBaseUrl() }
        val second = async { endpoints.provenBaseUrl() }
        assertEquals(lan.url, first.await())
        assertEquals(lan.url, second.await())
        assertEquals(1, asked.size)
    }

    @Test fun twoFailuresInARowOrANetworkChangeRaceAgain() = runTest {
        var homeAnswers = true
        val asked = mutableListOf<Endpoint>()
        val endpoints = endpoints({ endpoint, _ ->
            asked += endpoint
            if (endpoint == lan) homeAnswers else true
        }, lan, public)
        assertEquals(lan.url, endpoints.provenBaseUrl())
        homeAnswers = false
        endpoints.recordFailure()
        runCurrent()
        assertEquals("one failure isn't enough", lan.url, endpoints.baseUrl)
        endpoints.recordFailure()
        runCurrent()
        assertEquals(public.url, endpoints.baseUrl)

        homeAnswers = true
        endpoints.networkChanged()
        runCurrent()
        assertEquals("back home, the home address again", lan.url, endpoints.baseUrl)
    }

    @Test fun cancellingTheCallerDoesntLeaveARaceStuck() = runTest {
        val gate = CompletableDeferred<Unit>()
        val endpoints = endpoints({ _, _ -> gate.await(); true }, lan)
        val waiting = async { endpoints.race() }
        delay(10)
        waiting.cancel()
        gate.complete(Unit)
        assertEquals(lan, endpoints.race())
    }
}

package com.luke.tracks.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.library.SecretBox
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.SubsonicTransport
import com.luke.tracks.library.trackside.TracksideAnswer
import com.luke.tracks.library.trackside.TracksideJson
import com.luke.tracks.library.trackside.TracksidePairResult
import com.luke.tracks.library.trackside.TracksideProof
import com.luke.tracks.library.trackside.TracksideTransport
import com.luke.tracks.ui.tv.link.LinkException
import com.luke.tracks.ui.tv.link.LinkPoll
import com.luke.tracks.ui.tv.link.LinkProblem
import com.luke.tracks.ui.tv.link.LinkRequest
import com.luke.tracks.ui.tv.link.LinkServer
import com.luke.tracks.ui.tv.link.TracksideTvLinker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** The app's linker against Trackside's own wire fixtures, through [ServerLibrary] as the app runs it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TracksideTvLinkerTest {
    private val app = ApplicationProvider.getApplicationContext<Context>() as TracksApplication
    private val settings get() = app.graph.settings
    private val home = "http://192.168.1.20:4747".toHttpUrl()
    private val sent = CopyOnWriteArrayList<Pair<String, String>>()

    /** Answers by path; a list answers once per ask, its last one repeating. */
    private val answers = mutableMapOf<String, List<TracksideAnswer>>()
    private val asked = mutableMapOf<String, Int>()

    private val plain = object : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("trackside/$name")) { name }.readText()

    private fun answer(status: Int, name: String) = TracksideAnswer(status, fixture(name))

    private val library by lazy {
        ServerLibrary(
            app, settings, plain, app.graph.scope, network = null,
            deviceId = { "tv-install" },
            tracksideTransport = {
                TracksideTransport { request, _ ->
                    val path = request.url.encodedPath.removePrefix("/api/v1/")
                    sent += path to (request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() } ?: "")
                    val challenge = request.url.queryParameter("challenge")
                    if (path == "ping" && challenge != null) return@TracksideTransport provenPing(request.url, challenge)
                    val list = answers[path] ?: throw IOException("nothing at $path")
                    val n = asked.merge(path, 1, Int::plus)!! - 1
                    list[minOf(n, list.lastIndex)]
                }
            },
            subsonicTransport = { _, _ -> SubsonicTransport { throw IOException("offline in tests") } },
            networkIdentity = { null },
            tracksideOnly = true,
        )
    }
    private val linker = TracksideTvLinker { library }

    /** The real server's answer to the endpoint proof, with the key the link handed out. */
    private fun provenPing(url: HttpUrl, challenge: String): TracksideAnswer {
        val result = TracksideJson.decodeFromString(TracksidePairResult.serializer(), fixture("link-poll-result.json"))
        val key = TracksideProof.Key.Device(result.device.id, result.apiKey)
        val proof = TracksideProof.expected(key, result.server.serverId, url, challenge)
        return TracksideAnswer(200, """{"server_id":"${result.server.serverId}","proof":"$proof"}""")
    }

    @Before fun setUp() {
        runBlocking {
            settings.update { it.copy(subsonicServers = "[]") }
            library.start()
            withTimeout(10_000) { library.loading.first { !it } }
        }
        answers["capabilities"] = listOf(answer(200, "capabilities.json"))
        answers["link/start"] = listOf(answer(201, "link-start.json"))
    }

    @Test fun aTvLinksThroughPendingAndSlowDownAndIsKeptAsAServer() = runBlocking {
        answers["link/poll"] = listOf(
            answer(428, "error-authorization-pending.json"),
            answer(429, "error-slow-down.json"),
            answer(200, "link-poll-result.json"),
        )
        val offer = linker.start(LinkServer(null, "Home", home))
        assertEquals("WDJB-MJHT", offer.userCode)
        assertEquals(5, offer.intervalSeconds)
        assertTrue(offer.linkUrl.startsWith("tracks://link?"))
        assertTrue("introduces itself as a TV", sent.single { it.first == "link/start" }.second.contains("\"platform\":\"android_tv\""))

        assertEquals(LinkPoll.Pending, linker.poll(offer))
        assertEquals(LinkPoll.SlowDown(10), linker.poll(offer))
        assertEquals(LinkPoll.Linked("Home"), linker.poll(offer))

        val saved = ServerLibrary.parse(settings.settings.first().subsonicServers).single()
        assertTrue(saved.isTrackside)
        assertEquals("5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b", saved.serverUuid)
        assertFalse("the key is sealed, never kept as given", saved.secret.isEmpty())
    }

    @Test fun aServerThatCantLinkAsksForAnUpdateWithoutStarting() = runBlocking {
        answers["capabilities"] = listOf(TracksideAnswer(200, """{"product":"trackside","server_id":"5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b","auth":{"pairing":true}}"""))
        assertEquals(LinkProblem.NeedsUpdate, problemOf { linker.start(LinkServer(null, "Home", home)) })
        assertEquals(LinkProblem.NeedsUpdate, problemOf { linker.start(LinkServer(null, "Home", home, deviceLink = false)) })
        assertTrue(sent.none { it.first == "link/start" })
    }

    @Test fun deniedAndExpiredCodesSaySo() = runBlocking {
        val offer = linker.start(LinkServer(null, "Home", home, deviceLink = true))
        assertTrue("capabilities already read aren't asked again", sent.none { it.first == "capabilities" })
        answers["link/poll"] = listOf(answer(403, "error-access-denied.json"))
        assertEquals(LinkPoll.Denied, linker.poll(offer))
        answers["link/poll"] = listOf(answer(410, "error-expired-token.json"))
        asked.clear()
        assertEquals(LinkPoll.Expired, linker.poll(offer))
    }

    @Test fun thePhoneLooksUpAndApprovesOnItsPairedServer() = runBlocking {
        answers["link/poll"] = listOf(answer(200, "link-poll-result.json"))
        linker.poll(linker.start(LinkServer(null, "Home", home, deviceLink = true)))
        withTimeout(10_000) { library.entries.first { it.isNotEmpty() } }

        answers["link/lookup"] = listOf(answer(200, "link-lookup.json"))
        val tv = LinkRequest("Living Room TV", "android_tv", nearby = true)
        assertEquals(tv, linker.lookUp("5F0C2B9E-8A3D-4C1E-9B7A-2D6E4F8A1C3B", "WDJB-MJHT"))
        assertTrue(sent.single { it.first == "link/lookup" }.second.contains("WDJB-MJHT"))

        answers["link/approve"] = listOf(answer(409, "error-link-used.json"))
        assertEquals(LinkProblem.Used, problemOf { linker.approve("5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b", "WDJB-MJHT") })
        answers["link/deny"] = listOf(answer(410, "error-link-locked.json"))
        assertEquals(LinkProblem.Locked, problemOf { linker.deny("5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b", "WDJB-MJHT") })
        answers["link/lookup"] = listOf(answer(404, "error-not-found.json"))
        asked.clear()
        assertEquals(LinkProblem.NotFound, problemOf { linker.lookUp("5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b", "ZZZZ-ZZZZ") })
        answers["link/lookup"] = listOf(answer(410, "error-link-expired.json"))
        asked.clear()
        assertEquals(LinkProblem.CodeExpired, problemOf { linker.lookUp("5f0c2b9e-8a3d-4c1e-9b7a-2d6e4f8a1c3b", "WDJB-MJHT") })
    }

    private suspend fun problemOf(call: suspend () -> Any): LinkProblem {
        try {
            call()
        } catch (error: LinkException) {
            return error.problem
        }
        fail("expected a LinkException")
        error("unreachable")
    }
}

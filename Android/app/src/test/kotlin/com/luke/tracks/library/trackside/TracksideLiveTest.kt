package com.luke.tracks.library.trackside

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.data.HistoryDatabase
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.ServerEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

private fun fixture(name: String): String =
    requireNotNull(TracksideLiveTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TracksideLiveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val home = Endpoint("http://192.168.1.20:4747/".toHttpUrl(), Endpoint.Kind.Lan)

    @After fun tearDown() = scope.cancel()

    private fun client(transport: TracksideTransport) =
        TracksideClient(ServerEndpoints(listOf(home), home.url, scope, { _, _ -> true }), "tsk_test", "d1", null, transport)

    private fun play(n: Int, server: String = "s1") = PlayEntity(
        id = "6F1D9C2A-3B4E-4F5A-8B7C-${"%012d".format(n)}", title = "Song $n", artistName = "Artist", albumTitle = null, artworkUri = null,
        playedAt = 1_794_600_000_000 + n, durationMs = 200_000, listenedMs = 150_000, kind = PlayKindValue.OnDemand,
        source = PlaySourceValue.YourMusic, appPackage = null, appName = "Tracks", stationName = null,
        mediaId = ServerLibrary.mediaId(server, "song$n"),
    )

    @Test fun aNewSongPlayedOnTracksRadioIsAListenWithoutASongId() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "outbox-${UUID.randomUUID()}.db")
        val db = Room.databaseBuilder(context, HistoryDatabase::class.java, file.path).allowMainThreadQueries().build()
        val sent = mutableListOf<String>()
        val transport = TracksideTransport { request, _ ->
            sent += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val ids = TracksideJson.parseToJsonElement(sent.last()).jsonObject["listens"]!!.jsonArray.map { it.jsonObject["client_listen_id"]!!.jsonPrimitive.content }
            TracksideAnswer(200, """{"results":[${ids.joinToString(",") { """{"client_listen_id":"$it","status":"accepted"}""" }}]}""")
        }
        val outbox = ListenOutbox(db.listens(), { client(transport) }, { it == "s1" }, { Instant.parse("2026-11-24T10:00:00Z") })
        outbox.record(listOf(play(1).copy(mediaId = ServerLibrary.newSongMediaId("s1", "irreplaceable\u001fbeyonce"))))
        assertEquals(ListenOutbox.Outcome.Done, outbox.drain())
        val listen = TracksideJson.parseToJsonElement(sent.single()).jsonObject["listens"]!!.jsonArray.single().jsonObject
        assertEquals(null, listen["song_id"])
        assertEquals("Song 1", listen["title"]!!.jsonPrimitive.content)
        db.close()
    }

    @Test fun listensWaitOnDiskUntilTheServerHasThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "outbox-${UUID.randomUUID()}.db")
        fun open() = Room.databaseBuilder(context, HistoryDatabase::class.java, file.path).allowMainThreadQueries().build()
        var online = false
        val sent = mutableListOf<String>()
        val transport = TracksideTransport { request, _ ->
            if (!online) throw IOException("offline")
            sent += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val ids = TracksideJson.parseToJsonElement(sent.last()).jsonObject["listens"]!!.jsonArray.map { it.jsonObject["client_listen_id"]!!.jsonPrimitive.content }
            TracksideAnswer(200, """{"results":[${ids.joinToString(",") { """{"client_listen_id":"$it","status":"accepted"}""" }}]}""")
        }
        val server = client(transport)
        val trackside = setOf("s1")

        var db = open()
        var outbox = ListenOutbox(db.listens(), { server }, { it in trackside }, { Instant.parse("2026-11-24T10:00:00Z") })
        // Only plays of a Trackside server's songs: a file on the phone isn't one.
        outbox.record(listOf(play(1), play(2), play(3, server = "other"), play(4).copy(mediaId = "local:9")))
        assertEquals(2, db.listens().count())
        assertEquals(ListenOutbox.Outcome.RetryLater, outbox.drain())
        db.close()

        // A new launch: the outbox is still there.
        db = open()
        outbox = ListenOutbox(db.listens(), { server }, { it in trackside }, { Instant.parse("2026-11-24T10:00:00Z") })
        assertEquals(1, db.listens().waiting("s1", 10).first().attempts)
        online = true
        assertEquals(ListenOutbox.Outcome.Done, outbox.drain())
        assertEquals(0, db.listens().count())
        val body = TracksideJson.parseToJsonElement(sent.single()).jsonObject
        assertEquals("2026-11-24T10:00:00Z", body["client_time"]!!.jsonPrimitive.content)
        val first = body["listens"]!!.jsonArray[0].jsonObject
        assertEquals("the play's own id, so sending it twice is one listen", play(1).id, first["client_listen_id"]!!.jsonPrimitive.content)
        assertEquals("song1", first["song_id"]!!.jsonPrimitive.content)
        assertEquals(150_000, first["played_ms"]!!.jsonPrimitive.content.toInt())
        db.close()
    }

    @Test fun eachAnswerFinishesItsListenAndAnUnansweredOneWaits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java).allowMainThreadQueries().build()
        val results = TracksideJson.decodeFromString(TracksideListenResults.serializer(), fixture("listen-results.json")).results
        assertEquals(listOf("accepted", "duplicate", "rejected"), results.map { it.status })
        val ids = results.map { it.clientListenId }
        val server = client { _, _ -> TracksideAnswer(200, fixture("listen-results.json")) }
        val outbox = ListenOutbox(db.listens(), { server }, { true })
        outbox.record((ids + "9C4A2F5D-6E7B-4C8D-9E0F-4A5B6C7D8E9F").mapIndexed { i, id -> play(i).copy(id = id) })
        assertEquals(ListenOutbox.Outcome.RetryLater, outbox.drain())
        assertEquals(listOf("9C4A2F5D-6E7B-4C8D-9E0F-4A5B6C7D8E9F"), db.listens().waiting("s1", 10).map { it.id })
        db.close()
    }

    @Test fun eventsAreReadAsTheStandardSays() {
        val reader = SseReader()
        val text = """
            : keep-alive
            event: hello
            id: 918300
            data: {"server_id":"5f0c2b9e","seq":918300}

            event: cloud
            id: 918301
            data: ${fixture("cloud-event.json").replace("\n", "").replace(" ", "")}

            data: one
            data: two

        """.trimIndent() + "\n"
        val events = text.lines().mapNotNull(reader::line)
        assertEquals(listOf("hello", "cloud", "message"), events.map { it.type })
        assertEquals("plays", events[1].cloudEntity)
        assertEquals("one\ntwo", events[2].data)
        assertEquals("918301", reader.lastEventId)
    }

    @Test fun reconnectsBackOffToAMinuteAndStartOverOnHello() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "5").build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body("event: hello\ndata: {}\n\nevent: server_urls\ndata: {\"version\":\"v8\"}\n\n").build())
        server.enqueue(MockResponse.Builder().code(401).build())
        server.start()
        val base = server.url("/")
        val client = TracksideClient(ServerEndpoints(listOf(Endpoint(base, Endpoint.Kind.Lan)), base, scope, { _, _ -> true }), "tsk_test", "d1", null)
        val waits = mutableListOf<Long>()
        val seen = mutableListOf<String>()
        var connected = 0
        val ending = withTimeout(10_000) {
            TracksideEvents(client, OkHttpClient(), pause = { waits += it }).run(onConnected = { connected++ }, onEvent = { seen += it.type })
        }
        assertEquals(TracksideEvents.Ending.SignedOut, ending)
        assertEquals(listOf("hello", "server_urls"), seen)
        assertEquals(1, connected)
        assertEquals(3, waits.size)
        assertTrue(waits[0] in 1_000..1_250)
        assertEquals("the server's Retry-After", 5_000L, waits[1])
        assertTrue("hello started the backoff over", waits[2] in 1_000..1_250)
        val first = server.takeRequest()
        assertEquals("Bearer tsk_test", first.headers["Authorization"])
        assertEquals("text/event-stream", first.headers["Accept"])
        server.close()

        assertTrue("never more than a minute and a quarter", TracksideEvents.backoff(30, Random(1)) in 60_000..75_000)
    }

    @Test fun aServerFoundNearbyIsReadFromItsTxtRecords() {
        val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        fun txt(vararg pairs: Pair<String, String>) = pairs.associate { it.first to it.second.toByteArray() }
        val found = NearbyServers.server("Home", listOf(address), 4747, txt("id" to "5f0c2b9e", "v" to "1", "tls" to "0", "path" to "/music"))!!
        assertEquals("http://192.168.1.20:4747/music", found.url.toString())
        assertEquals("5f0c2b9e", found.serverId)
        assertEquals("https", NearbyServers.server("Home", listOf(address), 443, txt("id" to "x", "v" to "1", "tls" to "1"))!!.url.scheme)
        assertNull("a version this build doesn't know", NearbyServers.server("Home", listOf(address), 4747, txt("id" to "x", "v" to "2")))
        assertNull(NearbyServers.server("Home", emptyList(), 4747, txt("id" to "x", "v" to "1")))
    }

    @Test fun cellularAsksForTheServersCellularProfile() = runBlocking {
        val capabilities = TracksideJson.decodeFromString(TracksideCapabilities.serializer(), fixture("capabilities.json"))
        assertEquals("aac-256", capabilities.media?.suggestedCellularProfile)
        assertTrue(capabilities.offersCloud)
        assertEquals("3f9a0c1d7e2b4a65", capabilities.cloud?.epoch)
        val server = client { request, _ -> TracksideAnswer(200, if (request.url.encodedPath.endsWith("capabilities")) fixture("capabilities.json") else "{}") }
        assertEquals("aac-256", server.cellularProfile())
        assertEquals(256, TracksideClient.bitRate("aac-256"))
        assertNull(TracksideClient.bitRate("flac"))
        val request = TracksidePlaybackRequest.exoPlayer("s1", 256, "cellular", "aac-256")
        assertEquals("aac-256", request.client.preferredProfile)
        assertEquals(256, request.client.maxBitrateKbps)
    }

    @Test fun pairingLinksAreKeptOnlyWhenTheyArePairingLinks() {
        assertTrue(!PairingLinks.offer("https://example.com"))
        assertTrue(PairingLinks.offer("trackside://pair?d=abc"))
        assertEquals("trackside://pair?d=abc", PairingLinks.pending.value)
        PairingLinks.clear()
        assertNull(PairingLinks.pending.value)
    }
}

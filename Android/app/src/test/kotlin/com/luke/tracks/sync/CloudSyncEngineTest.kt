package com.luke.tracks.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.data.HistoryDatabase
import com.luke.tracks.data.HistoryRepository
import com.luke.tracks.data.PlayEntity
import com.luke.tracks.data.PlayKindValue
import com.luke.tracks.data.PlaySourceValue
import com.luke.tracks.engine.Engine
import com.luke.tracks.library.trackside.CloudJson
import com.luke.tracks.library.trackside.OkHttpTracksideTransport
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideCloud
import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.ServerEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

internal fun fixture(name: String): String =
    requireNotNull(CloudSyncEngineTest::class.java.classLoader!!.getResource("trackside/$name")) { name }.readText()

private const val ME = "2c5e7a90-1b3d-4f5a-8c7e-9a1b3c5d7e9f"

/** Settings and likes as plain maps, standing in for the app's settings. */
class FakePreferences(
    var values: MutableMap<String, JsonElement> = mutableMapOf(),
    var liked: Set<String> = emptySet(),
) : SyncedPreferences {
    override suspend fun settings(): Map<String, JsonElement> = values.toMap()
    override suspend fun applySettings(values: Map<String, JsonElement>) { this.values.putAll(values) }
    override suspend fun likes() = liked
    override suspend fun setLikes(likes: Set<String>) { liked = likes }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudSyncEngineTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: HistoryDatabase
    private lateinit var history: HistoryRepository
    private val requests = CopyOnWriteArrayList<Pair<RecordedRequest, String>>()

    /** Answers by path; each entity's pages in turn. Anything unrouted is an empty page. */
    private val routes = mutableMapOf<String, MutableList<MockResponse>>()

    private fun route(key: String, vararg responses: MockResponse) {
        routes.getOrPut(key) { mutableListOf() } += responses
    }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun error(code: Int, name: String, field: String? = null) =
        json("""{"error":{"code":"$name","message":"","details":${if (field == null) "{}" else """{"field":"$field"}"""}}}""", code)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java).allowMainThreadQueries().build()
        history = HistoryRepository(db, Engine(null))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                requests += request to body
                val path = request.url.encodedPath.removePrefix("/api/v1/cloud/")
                val key = "${request.method} $path"
                synchronized(routes) {
                    routes[key]?.let { queue -> if (queue.isNotEmpty()) return if (queue.size == 1) queue[0] else queue.removeAt(0) }
                }
                return when {
                    request.method == "PUT" -> json("""{"device_id":"$ME","predecessors":[]}""")
                    path.endsWith(":batch") -> json(resultsFor(body))
                    path == "settings" && request.method == "PATCH" -> json(settingResultsFor(body))
                    path == "settings" -> json("""{"settings":{},"cursor":"e.1"}""")
                    path == "usage" -> json(fixture("cloud-usage.json"))
                    else -> json("""{"upserts":[],"deletes":[],"cursor":"e.1","has_more":false}""")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        server.close()
        db.close()
        scope.cancel()
    }

    /** Every setting taken as sent. */
    private fun settingResultsFor(body: String): String {
        val settings = CloudJson.parseToJsonElement(body).jsonObject["settings"]!!.jsonObject
        return """{"results":[${settings.entries.joinToString(",") { (key, value) -> """{"key":"$key","status":"accepted","current":$value}""" }}]}"""
    }

    /** Every item accepted at version 1. */
    private fun resultsFor(body: String): String {
        val items = CloudJson.parseToJsonElement(body).jsonObject["items"]!!.jsonArray
        return """{"results":[${items.joinToString(",") { """{"id":${it.jsonObject["id"]},"status":"accepted","version":1}""" }}]}"""
    }

    private fun engine(preferences: SyncedPreferences = FakePreferences(), batchItems: Int = 1_000, batchBytes: Int = 7 * 1024 * 1024): CloudSyncEngine {
        val home = Endpoint(server.url("/"), Endpoint.Kind.Lan)
        val endpoints = ServerEndpoints(listOf(home), home.url, scope, probe = { _, _ -> true })
        val client = TracksideClient(endpoints, "tsk_test", "d1", null, OkHttpTracksideTransport(OkHttpClient()))
        return CloudSyncEngine(TracksideCloud(client), history, preferences, ME, deviceName = "Pixel", batchItems = batchItems, batchBytes = batchBytes)
    }

    private fun play(n: Int, at: Long = 1_794_600_000_000 + n * 60_000L) = PlayEntity(
        id = "00000000-0000-4000-8000-${"%012d".format(n)}", title = "Song $n", artistName = "Artist", albumTitle = "Album",
        artworkUri = "content://media/1", playedAt = at, durationMs = 200_000, listenedMs = 180_000,
        kind = PlayKindValue.OnDemand, source = PlaySourceValue.YourMusic, appPackage = null, appName = "Tracks",
        stationName = null, mediaId = "local:$n",
    )

    private fun sent(method: String, path: String) = requests.filter { it.first.method == method && it.first.url.encodedPath == "/api/v1/cloud/$path" }

    @Test fun turningSyncOnPushesEverythingHereThenPulls() = runBlocking {
        history.keepAll(listOf(play(1), play(2), play(3)))
        route("GET plays", json(fixture("cloud-plays-page.json")))
        val preferences = FakePreferences(mutableMapOf("scrobblesToLastFM" to JsonPrimitive(true)), liked = setOf("halo\u001fbeyonce"))
        engine(preferences).sync()

        val order = requests.map { "${it.first.method} ${it.first.url.encodedPath.removePrefix("/api/v1/cloud/")}" }
        assertEquals("registers before anything goes up", "PUT devices/$ME", order.first())
        assertTrue(order.indexOf("POST plays:batch") < order.indexOf("GET plays"))
        val registration = CloudJson.parseToJsonElement(requests.first().second).jsonObject
        assertEquals("android", registration["platform"]!!.jsonPrimitive.content)

        val pushed = CloudJson.parseToJsonElement(sent("POST", "plays:batch").single().second).jsonObject["items"]!!.jsonArray
        assertEquals(3, pushed.size)
        val first = pushed[0].jsonObject
        assertEquals(ME, first["device_id"]!!.jsonPrimitive.content)
        val fields = first["fields"]!!.jsonObject
        assertEquals("yourMusic", fields["source"]!!.jsonPrimitive.content)
        assertEquals("onDemand", fields["kind"]!!.jsonPrimitive.content)
        assertEquals(ME, fields["recordedByDeviceID"]!!.jsonPrimitive.content)
        assertEquals(1_794_600_060_000, fields["playedAtMs"]!!.jsonPrimitive.content.toLong())
        assertNull("a file's cover stays on the phone", fields["artworkURL"])

        // The page came in: two plays, and the deleted one isn't here.
        val all = db.plays().all().associateBy { it.id }
        assertEquals(5, all.size)
        val halo = all.getValue("6A8C0E2F-4B6D-4F8A-9C1E-3B5D7F9A1C3E")
        assertEquals("Halo", halo.title)
        assertEquals(PlaySourceValue.Streaming, halo.source)
        assertEquals(1_794_645_300_000, halo.scrobbledAt)
        assertEquals("another phone's play is never scrobbled here", "6F1D9C2A-3B4E-4F5A-8B7C-1D2E3F4A5B6C", halo.recordedBy)
        assertEquals(0, halo.cloudPending)
        assertEquals(PlayKindValue.LastFm, all.getValue("lastfm:1700000000:5f2b9c1d").kind)
        assertTrue(all.values.all { it.cloudPending == 0 })
        assertEquals("3f9a0c1d7e2b4a65.918", db.cloud().state(CloudSyncEngine.cursorKey("plays")))
        assertEquals("the index has them too", 5UL, history.engine.read { playCount() })

        // Settings and likes went up as seeds dated 1970, so another device's choice stands.
        val patch = CloudJson.parseToJsonElement(sent("PATCH", "settings").single().second).jsonObject["settings"]!!.jsonObject
        assertEquals("1970-01-01T00:00:00Z", patch["scrobblesToLastFM"]!!.jsonObject["modified_at"]!!.jsonPrimitive.content)
        val like = CloudJson.parseToJsonElement(sent("POST", "likes:batch").single().second).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("halo\u001fbeyonce", like["id"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), like["fields"]!!.jsonObject["loved"])

        // Nothing changed since: the next sync sends nothing, and pulls from its cursor.
        requests.clear()
        engine(preferences).sync()
        assertTrue(sent("POST", "plays:batch").isEmpty())
        assertTrue(sent("PATCH", "settings").isEmpty())
        assertTrue(requests.none { it.first.method == "PUT" })
        assertEquals("3f9a0c1d7e2b4a65.918", sent("GET", "plays").single().first.url.queryParameter("cursor"))
    }

    @Test fun pagesUntilDoneSendingEachCursorBackAsItCame() = runBlocking {
        val page = CloudJson.parseToJsonElement(fixture("cloud-plays-page.json")).jsonObject
        fun withCursor(cursor: String, more: Boolean) = JsonObject(page + ("cursor" to JsonPrimitive(cursor)) + ("has_more" to JsonPrimitive(more))).toString()
        route("GET plays", json(withCursor("3f9a0c1d7e2b4a65.700", true)), json(withCursor("3f9a0c1d7e2b4a65.918", false)))
        engine().sync()
        val pulls = sent("GET", "plays")
        assertEquals(listOf(null, "3f9a0c1d7e2b4a65.700"), pulls.map { it.first.url.queryParameter("cursor") })
        assertEquals("3f9a0c1d7e2b4a65.918", db.cloud().state(CloudSyncEngine.cursorKey("plays")))
    }

    @Test fun aResetPullsEverythingThenPushesWhatTheServerLost() = runBlocking {
        history.keepAll(listOf(play(1), play(2)))
        engine().sync()
        requests.clear()
        // The server was restored from a backup: the old cursor is from another epoch.
        route("GET plays", error(410, "sync_reset"), json(fixture("cloud-plays-page.json")))
        engine().sync()
        val pulls = sent("GET", "plays")
        assertEquals(listOf("e.1", null), pulls.map { it.first.url.queryParameter("cursor") })
        val repushed = sent("POST", "plays:batch").flatMap { CloudJson.parseToJsonElement(it.second).jsonObject["items"]!!.jsonArray }
        assertEquals("everything here goes up again, the snapshot's plays included", 4, repushed.size)
        assertEquals(4, db.plays().all().size)
        assertEquals("3f9a0c1d7e2b4a65.918", db.cloud().state(CloudSyncEngine.cursorKey("plays")))
    }

    @Test fun anotherDevicesIdStopsSyncWithNothingLost() = runBlocking {
        history.keepAll(listOf(play(1)))
        route("POST plays:batch", json("""{"results":[{"id":"${play(1).id}","status":"rejected","reason":"device_mismatch"}]}"""))
        try {
            engine().sync()
            fail("device_mismatch stops sync")
        } catch (error: CloudSyncError.DeviceMismatch) {
            assertTrue(error.message!!.contains("Pair it again"))
        }
        assertEquals(1, db.plays().all().single().cloudPending)

        // Registering refused the same way stops it too.
        db.cloud().clearStates("cloud.")
        route("PUT devices/$ME", error(403, "device_mismatch"))
        try {
            engine().sync()
            fail("device_mismatch stops sync")
        } catch (_: CloudSyncError.DeviceMismatch) {
        }
    }

    @Test fun aServerThatFailsMidPushLosesNothingAndTheRetryFinishes() = runBlocking {
        history.keepAll(listOf(play(1), play(2)))
        route("POST plays:batch", error(503, "unavailable"), json(resultsFor("""{"items":[{"id":"${play(1).id}"},{"id":"${play(2).id}"}]}""")))
        try {
            engine().sync()
            fail("a 503 fails the sync")
        } catch (_: Exception) {
        }
        assertTrue(db.plays().all().all { it.cloudPending > 0 })
        engine().sync()
        assertTrue(db.plays().all().all { it.cloudPending == 0 })
        // The retry sent the same ids: idempotent on the server.
        val batches = sent("POST", "plays:batch").map { CloudJson.parseToJsonElement(it.second).jsonObject["items"]!!.jsonArray.map { i -> i.jsonObject["id"]!!.jsonPrimitive.content } }
        assertEquals(batches[0], batches[1])
    }

    @Test fun aConflictKeepsTheServersCoreAndWhatWasFilledInHere() = runBlocking {
        val mine = play(1).copy(scrobbledAt = 1_794_600_100_000)
        history.keepAll(listOf(mine))
        val current = """{"id":"${mine.id}","version":911,"device_id":"0C7B5E3A-9D1F-4E2B-8A6C-5F4E3D2C1B0A","client_modified_at":"2026-11-14T09:26:40.000Z",
            "fields":{"title":"Song 1 (Live)","artistName":"Artist","playedAtMs":${mine.playedAt},"kind":"onDemand","source":"yourMusic","recordedByDeviceID":"0C7B5E3A-9D1F-4E2B-8A6C-5F4E3D2C1B0A"}}"""
        route("POST plays:batch", json("""{"results":[{"id":"${mine.id}","status":"conflict","current":$current}]}"""))
        engine().sync()
        val kept = db.plays().byId(mine.id)!!
        assertEquals("Song 1", kept.title)
        assertEquals(1_794_600_100_000, kept.scrobbledAt)
        assertTrue("its scrobble goes up on top of the server's copy", kept.cloudPending > 0)
        assertEquals("Song 1 (Live)", CloudJson.parseToJsonElement(kept.cloudFields!!).jsonObject["title"]!!.jsonPrimitive.content)
        // Sent again, the core is the server's, so it can't conflict.
        engine().sync()
        val again = CloudJson.parseToJsonElement(sent("POST", "plays:batch").last().second).jsonObject["items"]!!.jsonArray.single().jsonObject["fields"]!!.jsonObject
        assertEquals("Song 1 (Live)", again["title"]!!.jsonPrimitive.content)
        assertEquals(1_794_600_100_000, again["scrobbledAtMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test fun deletionsTravelAsTombstonesAndATombstonedIdGoesHereToo() = runBlocking {
        history.keepAll(listOf(play(1), play(2), play(3)))
        engine().sync()
        history.remove(listOf(play(1).id))
        db.plays().markAllCloudPending()
        route(
            "POST plays:batch",
            json("""{"results":[{"id":"${play(2).id}","status":"rejected","reason":"deleted"},{"id":"${play(3).id}","status":"duplicate","version":1}]}"""),
            json("""{"results":[{"id":"${play(1).id}","status":"accepted","version":2}]}"""),
        )
        engine().sync()
        val tombstone = sent("POST", "plays:batch").map { CloudJson.parseToJsonElement(it.second).jsonObject["items"]!!.jsonArray }
            .flatten().map { it.jsonObject }.last { it["id"]!!.jsonPrimitive.content == play(1).id }
        assertNotNull(tombstone["deleted_at"])
        assertNull(tombstone["fields"])
        assertNull("deleted on another device", db.plays().byId(play(2).id))
        assertTrue("the tombstone is done once sent", db.cloud().items("plays").isEmpty())
    }

    @Test fun batchesHoldAtMostAThousandItemsOrTheByteLimit() = runBlocking {
        history.keepAll((1..2_500).map { play(it) })
        engine().sync()
        val sizes = sent("POST", "plays:batch").map { CloudJson.parseToJsonElement(it.second).jsonObject["items"]!!.jsonArray.size }
        assertEquals(listOf(1_000, 1_000, 500), sizes)

        val small = engine(batchBytes = 4_000)
        val items = (1..20).map { com.luke.tracks.library.trackside.CloudPushItem(id = "$it", fields = CloudPlays.fields(play(it), ME), clientModifiedAt = "2026-11-14T09:25:00Z", deviceId = ME) }
        val batches = small.batches(items)
        assertTrue(batches.size > 1)
        batches.forEach { batch -> assertTrue(CloudJson.encodeToString(com.luke.tracks.library.trackside.CloudBatch.serializer(), com.luke.tracks.library.trackside.CloudBatch(batch)).length <= 4_000) }
        assertEquals(20, batches.sumOf { it.size })
    }

    @Test fun settingsGoAsTheyAreWithClearedOnesAsExplicitNulls() = runBlocking {
        val preferences = FakePreferences(mutableMapOf("playlistName" to JsonPrimitive("Tracks"), "playlistID" to JsonPrimitive("p.1")))
        route("GET settings", json(fixture("cloud-settings.json")))
        engine(preferences).sync()
        // The server's newer choices came down; its seed (1970) didn't replace anything.
        assertEquals(JsonPrimitive("Heard on the radio"), preferences.values["playlistName"])
        assertEquals("cleared values come down as cleared", JsonNull, preferences.values["playlistID"])

        preferences.values["playlistID"] = JsonPrimitive("p.2")
        engine(preferences).sync()
        preferences.values["playlistID"] = JsonNull
        requests.clear()
        engine(preferences).sync()
        val body = sent("PATCH", "settings").single().second
        assertTrue(body, body.contains(""""playlistID":{"value":null"""))
        assertFalse("unchanged ones aren't sent", body.contains("playlistName"))
    }

    @Test fun aLikeFromElsewhereComesDownAndAnUnlikeHereGoesUp() = runBlocking {
        val preferences = FakePreferences(liked = setOf("a\u001fartist"))
        route(
            "GET likes",
            json("""{"upserts":[{"id":"b\u001fartist","version":3,"fields":{"loved":true},"device_id":"x","client_modified_at":"2026-11-14T09:25:00Z"}],"deletes":[],"cursor":"e.3","has_more":false}"""),
        )
        engine(preferences).sync()
        assertEquals(setOf("a\u001fartist", "b\u001fartist"), preferences.liked)
        preferences.liked = setOf("b\u001fartist")
        requests.clear()
        engine(preferences).sync()
        val sentLikes = CloudJson.parseToJsonElement(sent("POST", "likes:batch").single().second).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("a\u001fartist", sentLikes["id"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), sentLikes["fields"]!!.jsonObject["loved"])
    }

    @Test fun wireFixturesReadAndWriteAsTheServerSays() {
        val push = CloudJson.decodeFromString(com.luke.tracks.library.trackside.CloudBatch.serializer(), fixture("cloud-push-plays.json"))
        assertEquals(3, push.items.size)
        assertEquals(902L, push.items[2].baseVersion)
        assertEquals(
            CloudJson.parseToJsonElement(fixture("cloud-push-plays.json")),
            CloudJson.parseToJsonElement(CloudJson.encodeToString(com.luke.tracks.library.trackside.CloudBatch.serializer(), push)),
        )
        val results = CloudJson.decodeFromString(com.luke.tracks.library.trackside.CloudPushResults.serializer(), fixture("cloud-push-results.json")).results
        assertEquals(listOf("accepted", "duplicate", "conflict", "rejected"), results.map { it.status })
        assertEquals(911L, results[2].current!!.version)
        val patch = CloudJson.decodeFromString(com.luke.tracks.library.trackside.CloudSettingsPatch.serializer(), fixture("cloud-settings-patch.json"))
        assertNull(patch.settings.getValue("playlistID").value)
        assertEquals(
            CloudJson.parseToJsonElement(fixture("cloud-settings-patch.json")),
            CloudJson.parseToJsonElement(CloudJson.encodeToString(com.luke.tracks.library.trackside.CloudSettingsPatch.serializer(), patch)),
        )
        val settingResults = CloudJson.decodeFromString(com.luke.tracks.library.trackside.CloudSettingResults.serializer(), fixture("cloud-setting-results.json")).results
        assertEquals("encryption_required", settingResults.last().reason)
        val registration = CloudJson.decodeFromString(com.luke.tracks.library.trackside.CloudDeviceRegistration.serializer(), fixture("cloud-device-registration.json"))
        assertEquals(1, registration.predecessors.size)
        val usage = CloudJson.decodeFromString(com.luke.tracks.library.trackside.CloudUsage.serializer(), fixture("cloud-usage.json"))
        assertEquals(1_073_741_824L, usage.quota!!.bytes)
        val entries = CloudJson.decodeFromString(com.luke.tracks.library.trackside.CloudPage.serializer(), fixture("cloud-playlist-entries-page.json"))
        assertTrue(entries.hasMore)
        assertEquals("3f9a0c1d7e2b4a65.1204", entries.cursor)
        val event = com.luke.tracks.library.trackside.TracksideEvent("cloud", fixture("cloud-event.json"))
        assertEquals("plays", event.cloudEntity)
        assertEquals(JsonArray::class, CloudJson.parseToJsonElement(fixture("cloud-devices.json")).jsonObject["devices"]!!::class)
    }
}

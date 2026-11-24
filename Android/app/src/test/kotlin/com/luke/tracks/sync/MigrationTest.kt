package com.luke.tracks.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.data.HistoryDatabase
import com.luke.tracks.data.Migration1To2
import com.luke.tracks.data.PlayIds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A version 1 database exactly as the shipped schema made it. */
    private fun versionOne(file: File) {
        val schema = JSONObject(File("schemas/com.luke.tracks.data.HistoryDatabase/1.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val plays = schema.getJSONArray("entities").getJSONObject(0)
            db.execSQL(plays.getString("createSql").replace("\${TABLE_NAME}", "plays"))
            val indices = plays.getJSONArray("indices")
            for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "plays"))
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            fun play(id: String, title: String, artist: String, at: Long, kind: String) = db.execSQL(
                "INSERT INTO plays (id, title, artistName, playedAt, kind, source, mediaId, scrobbleAttempts, scrobbledAt) VALUES (?, ?, ?, ?, ?, 'Streaming', '', 0, ?)",
                arrayOf<Any?>(id, title, artist, at, kind, if (kind == "LastFm") at else null),
            )
            play("8b2f6f0e-4c1d-4a8e-9f3a-2d5c7b9e1a40", "Halo", "Beyoncé", 1_794_645_000_000, "OnDemand")
            play("lastfm-1700000000-aaaaaaaaaaaaaaaaaaaaaaaa", "Don't Stop Me Now", "Queen", 1_700_000_000_000, "LastFm")
            // The same scrobble imported again under a differently cased title: one play after.
            play("lastfm-1700000000-bbbbbbbbbbbbbbbbbbbbbbbb", "DON'T STOP ME NOW", "Queen", 1_700_000_000_000, "LastFm")
            play("lastfm-1700000300-cccccccccccccccccccccccc", "Bohemian Rhapsody", "Queen", 1_700_000_300_000, "LastFm")
            db.version = 1
        }
    }

    @Test fun versionTwoKeepsEveryPlayAndGivesScrobblesTheirLastingIds() = runBlocking {
        val file = File(context.cacheDir, "migrate-${UUID.randomUUID()}.db")
        versionOne(file)
        val db = Room.databaseBuilder(context, HistoryDatabase::class.java, file.path)
            .addMigrations(Migration1To2)
            .allowMainThreadQueries()
            .build()
        // Opening validates every table against version 2's schema.
        val plays = db.plays().all()
        assertEquals(3, plays.size)
        val ids = plays.map { it.id }.toSet()
        assertTrue("8b2f6f0e-4c1d-4a8e-9f3a-2d5c7b9e1a40" in ids)
        assertTrue(PlayIds.lastFm(1_700_000_000, "Don't Stop Me Now", "Queen") in ids)
        assertTrue(PlayIds.lastFm(1_700_000_300, "Bohemian Rhapsody", "Queen") in ids)
        assertTrue(plays.none { PlayIds.isOldLastFm(it.id) })
        assertTrue("everything goes up once sync is turned on", plays.all { it.cloudPending == 1 && it.cloudVersion == null })
        assertTrue(plays.all { it.recordedBy == null && it.cloudFields == null })
        assertEquals(1_700_000_000_000, plays.first { it.title.startsWith("Don") }.scrobbledAt)
        // The new tables work.
        db.cloud().setState("k", "v")
        assertEquals("v", db.cloud().state("k"))
        assertEquals(0, db.listens().count())
        assertNull(db.cloud().item("likes", "x"))
        db.close()
    }

    @Test fun idsMatchTheAppleApps() {
        // RFC 9562's DNS example, and the Apple apps' own vector for a derived play id.
        val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d", PlayIds.uuidV5(dns, "python.org").toString())
        val key = PlayIds.contentKey(1_700_000_000_000, "radio", "appleMusic", "song\u001Fartist")
        assertEquals("ec212ee6-e655-5934-993d-33b155df2eb2", PlayIds.derived(key))
        assertEquals("25edc9e6-7801-5a5d-8a25-be94988f72d8", PlayIds.derived(""))
        // A Last.fm import is the id an iPhone derives for the same scrobble.
        val identity = uniffi.tracks_engine.songIdentity("Halo", "Beyoncé")
        assertEquals(PlayIds.derived(PlayIds.contentKey(1_794_645_000_000, "lastFM", "appleMusic", identity)), PlayIds.lastFm(1_794_645_000, "Halo", "Beyoncé"))
    }
}

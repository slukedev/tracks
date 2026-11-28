package com.luke.tracks.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the scrobble queue owes Last.fm: every source's plays, each at most once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OwedScrobblesTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java)
        .allowMainThreadQueries().build()
    private val plays = db.plays()

    @After
    fun close() = db.close()

    private fun play(id: String, source: PlaySourceValue, at: Long, kind: PlayKindValue = PlayKindValue.OnDemand, recordedBy: String? = null) = PlayEntity(
        id = id, title = "Song $id", artistName = "Artist", albumTitle = null, artworkUri = null,
        playedAt = at, durationMs = 200_000, listenedMs = 200_000, kind = kind, source = source,
        appPackage = null, appName = null, stationName = null, mediaId = "catalog:s1:$id", recordedBy = recordedBy,
    )

    @Test
    fun servicesPlaysAreOwedLikeAnyOtherAndGoOnlyOnce() = runBlocking {
        plays.upsert(listOf(play("spotify", PlaySourceValue.Spotify, 3_000)))
        plays.upsert(listOf(play("apple", PlaySourceValue.Streaming, 2_000)))
        plays.upsert(listOf(play("other", PlaySourceValue.Other, 4_000)))
        plays.upsert(listOf(play("yours", PlaySourceValue.YourMusic, 1_000)))
        // Not this device's to send: another device's play, Last.fm's own, and one from before the cut-off.
        plays.upsert(listOf(play("phone", PlaySourceValue.Spotify, 5_000, recordedBy = "iphone")))
        plays.upsert(listOf(play("imported", PlaySourceValue.Spotify, 6_000, kind = PlayKindValue.LastFm)))
        plays.upsert(listOf(play("old", PlaySourceValue.Spotify, 10)))

        assertEquals(listOf("yours", "apple", "spotify", "other"), plays.owedScrobbles(since = 100, limit = 50).map { it.id })
        assertEquals(4, plays.owedScrobbleCount(since = 100).first())

        plays.markScrobbled(listOf("spotify", "apple"), at = 9_000)
        plays.markScrobbleFailed("other", "Ignored")
        assertEquals(listOf("yours"), plays.owedScrobbles(since = 100, limit = 50).map { it.id })
        assertEquals(1, plays.owedScrobbleCount(since = 100).first())
    }
}

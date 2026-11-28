package com.luke.tracks.streaming

import com.luke.tracks.library.trackside.TracksideCatalogMatch
import com.luke.tracks.library.trackside.TracksideCatalogTrack
import com.luke.tracks.sources.Streamer
import com.luke.tracks.sources.WantedSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.tracks_engine.songIdentity

/** A service's song that is a song asked for, kept so it isn't asked for again each time it plays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServiceMatchesTest {
    @get:Rule val folder = TemporaryFolder()

    private val streamer = Streamer("home", "am1", "apple_music")
    private val wanted = WantedSong("Karma Police", "Radiohead", null, null, songIdentity("Karma Police", "Radiohead"))
    private val track = TracksideCatalogTrack(ref = "am:1", serviceId = "am1", title = "Karma Police", artist = "Radiohead")

    private var clock = 1_000_000L
    private var searches = 0

    private fun matches(answer: () -> List<TracksideCatalogTrack>?) = ServiceMatches(
        search = { _, _ -> searches++; answer() },
        file = java.io.File(folder.root, "matches.json"),
        now = { clock },
        io = Dispatchers.Unconfined,
    )

    @Test fun aMatchIsKeptOnDiskAndNotAskedForAgain() = runBlocking {
        assertEquals("am:1", matches { listOf(track) }.find(streamer, wanted)?.ref)
        // A new launch reads it back: no search.
        val again = matches { error("not asked") }
        assertEquals("am:1", again.find(streamer, wanted)?.ref)
        assertEquals(1, searches)
    }

    @Test fun keptMatchesRunOut() = runBlocking {
        matches { listOf(track) }.find(streamer, wanted)
        clock += 8 * 24 * 60 * 60_000L
        assertEquals("am:1", matches { listOf(track) }.find(streamer, wanted)?.ref)
        assertEquals(2, searches)
    }

    @Test fun aSearchThatFailsIsntKept() = runBlocking {
        assertNull(matches { null }.find(streamer, wanted))
        assertEquals("am:1", matches { listOf(track) }.find(streamer, wanted)?.ref)
        assertEquals(2, searches)
    }

    @Test fun noSuchSongIsKeptForLess() = runBlocking {
        val other = TracksideCatalogTrack(ref = "am:2", serviceId = "am1", title = "Airbag", artist = "Radiohead")
        assertNull(matches { listOf(other) }.find(streamer, wanted))
        assertNull(matches { error("not asked") }.find(streamer, wanted))
        clock += 2 * 24 * 60 * 60_000L
        assertEquals("am:1", matches { listOf(track) }.find(streamer, wanted)?.ref)
        assertEquals(2, searches)
    }

    @Test fun aQueueIsMatchedInBatchesAndOnlySettledAnswersAreKept() = runBlocking {
        val songs = (1..60).map { WantedSong("Song $it", "Artist", null, null, songIdentity("Song $it", "Artist")) }
        val asked = mutableListOf<Int>()
        val batched = ServiceMatches(
            search = { _, _ -> searches++; emptyList() },
            batch = { key, request ->
                assertEquals(ServiceKey("home", "am1"), key)
                assertEquals(listOf("am1"), request.services)
                asked += request.items.size
                TracksideCatalogMatch(
                    results = request.items.map { item ->
                        val n = item.title.removePrefix("Song ").toInt()
                        val state = when {
                            n % 3 == 0 -> "none"
                            n % 10 == 0 -> "deferred"
                            else -> "found"
                        }
                        TracksideCatalogMatch.Result(
                            item.id,
                            listOf(TracksideCatalogMatch.Match("am1", state, TracksideCatalogTrack("am:$n", "am1", item.title, item.artist).takeIf { state == "found" })),
                        )
                    },
                )
            },
            now = { clock },
            io = Dispatchers.Unconfined,
        )
        batched.warm(streamer, songs + songs.take(5))
        assertEquals("fifty at a time, each song once", listOf(50, 10), asked)
        assertEquals("am:1", batched.find(streamer, songs[0])?.ref)
        assertNull("none is kept as none", batched.find(streamer, songs[2]))
        assertEquals("nothing searched for what the batch settled", 0, searches)
        // Put off: asked again one at a time.
        batched.find(streamer, songs[9])
        assertEquals(1, searches)
        // Known now: a second warm asks the server for nothing it settled.
        asked.clear()
        batched.warm(streamer, songs)
        assertEquals(listOf(songs.count { it.title.removePrefix("Song ").toInt().let { n -> n % 10 == 0 && n % 3 != 0 } } - 1), asked)
    }

    @Test fun aServerThatCantMatchManyIsntAsked() = runBlocking {
        val one = ServiceMatches(search = { _, _ -> searches++; listOf(track) }, now = { clock }, io = Dispatchers.Unconfined)
        one.warm(streamer, listOf(wanted))
        assertEquals(0, searches)
        assertEquals("am:1", one.find(streamer, wanted)?.ref)
    }
}

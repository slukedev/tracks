package com.luke.tracks.streaming

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
}

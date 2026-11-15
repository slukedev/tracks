package com.luke.tracks.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Albums and artists a server found, looked up for their pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerBrowseTest {
    @Test fun albumFromASongItFoundInTrackOrder() = runBlocking {
        val fake = FakeSubsonic()
        val page = fake.browse().album("Slow Signal", "Harbor Static", songId = "s-9")
        assertEquals(listOf("getSong", "getAlbum"), fake.asked.map { it.pathSegments.last() })
        assertEquals("s-9", fake.asked[0].queryParameter("id"))
        assertEquals("al-10", fake.asked[1].queryParameter("id"))
        // The library's own id for it, so a song of it you have plays as the same one.
        assertEquals(ServerLibrary.albumId("s1", "al-10"), page.album.id)
        assertEquals("ar-1", page.artistId)
        assertEquals(2024, page.album.year)
        assertEquals("Low Lamps", page.album.songs.first().title)
        assertEquals((1..8).toList(), page.album.songs.map { it.trackNumber })
        assertEquals(ServerLibrary.mediaId("s1", "t0"), page.album.songs.first().mediaId)
    }

    @Test fun albumByNameWhenThereIsNoSong() = runBlocking {
        val fake = FakeSubsonic()
        val page = fake.browse().album("paper coast", "HARBOR STATIC")
        assertEquals(listOf("search3", "getAlbum"), fake.asked.map { it.pathSegments.last() })
        assertEquals("Paper Coast", page.album.title)
        assertEquals(4, page.album.songs.size)
    }

    @Test fun albumTheServerDoesNotHave() = runBlocking {
        try {
            FakeSubsonic().browse().album("Somewhere Else", "Harbor Static")
            fail("expected NotFound")
        } catch (_: SubsonicError.NotFound) {
        }
    }

    @Test fun artistWithTopSongsAndAlbumsNewestFirst() = runBlocking {
        val fake = FakeSubsonic()
        val page = fake.browse().artist("Harbor Static", songId = "s-9")
        assertEquals(listOf("Slow Signal", "Paper Coast"), page.albums.map { it.title })
        assertEquals(5, page.songs.size)
        assertTrue("found through the song, no search", fake.asked.none { it.pathSegments.last() == "search3" })
        assertEquals("ar-1", fake.asked.first { it.pathSegments.last() == "getArtist" }.queryParameter("id"))
    }

    @Test fun octoArtistsAlbumsComeFromSearch() = runBlocking {
        val fake = FakeSubsonic(octo = true)
        val page = fake.browse().artist("Harbor Static", artistId = "ar-1")
        assertEquals(listOf("Slow Signal", "Paper Coast"), page.albums.map { it.title })
        assertEquals("Harbor Static", fake.asked.first { it.pathSegments.last() == "search3" }.queryParameter("query"))
    }

    @Test fun serverDownIsAnError() = runBlocking {
        try {
            FakeSubsonic(down = true).browse().artist("Harbor Static")
            fail("expected an error")
        } catch (error: Exception) {
            assertTrue(error !is SubsonicError.NotFound)
        }
    }
}

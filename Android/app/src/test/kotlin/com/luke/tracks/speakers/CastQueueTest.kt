package com.luke.tracks.speakers

import android.net.Uri
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.RepeatChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's queue, as a speaker on one server can play it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CastQueueTest {
    private fun server(id: String, server: String = "s1") =
        PlayableSong(ServerLibrary.mediaId(server, id), Uri.parse("https://tracks.link/$id"), "Song $id", "Artist", "Album", null, 200_000, "id-$id")

    private fun local(id: String) = PlayableSong("local:$id", Uri.parse("content://media/$id"), "File $id", "Artist", null, null, 100_000, "local-$id")

    @Test fun theQueueFromTheSongOnNowWithOtherSourcesLeftOut() {
        val state = PlayerState(
            queue = listOf(server("a"), local("1"), server("b"), server("c", server = "s2"), server("d")),
            index = 2,
            shuffle = true,
            repeat = RepeatChoice.All,
            origin = QueueOrigin("I Am... Sasha Fierce", QueueOrigin.Kind.Album),
        )
        val request = CastQueue.request(state, positionMs = 81_000, serverId = "s1")!!
        assertEquals(listOf("a", "b", "d"), request.items.map { it.songId })
        assertEquals("b is on now", 1, request.startIndex)
        assertEquals(81_000L, request.positionMs)
        assertEquals(true, request.shuffle)
        assertEquals("all", request.repeat)
        assertEquals("album", request.context!!.kind)
        assertEquals("I Am... Sasha Fierce", request.context!!.name)
        assertEquals("Song b", CastQueue.preview(state, request)!!.title)
    }

    @Test fun aSongTheServerDoesntHaveStartsAtTheNextOne() {
        val state = PlayerState(queue = listOf(server("a"), local("1"), server("b")), index = 1)
        val request = CastQueue.request(state, positionMs = 50_000, serverId = "s1")!!
        assertEquals(1, request.startIndex)
        assertEquals("not the file's position", 0L, request.positionMs)
        assertNull(CastQueue.request(PlayerState(queue = listOf(local("1"))), 0, "s1"))
        assertNull(CastQueue.request(PlayerState(), 0, "s1"))
    }

    @Test fun aLongQueueIsCutToTheLimitAroundTheSongOnNow() {
        val queue = (0 until 1_500).map { server("$it") }
        val request = CastQueue.request(PlayerState(queue = queue, index = 1_200), 0, "s1")!!
        assertEquals(1_000, request.items.size)
        assertEquals("1200", request.items[request.startIndex!!].songId)
        // Near the end, the window reaches back to stay full.
        assertEquals("500", request.items.first().songId)
        assertEquals("1499", request.items.last().songId)
        val early = CastQueue.request(PlayerState(queue = queue, index = 300), 0, "s1")!!
        assertEquals("a little before it, for going back", "250", early.items.first().songId)
        assertEquals(50, early.startIndex)
    }
}

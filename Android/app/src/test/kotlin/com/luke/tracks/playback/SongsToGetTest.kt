package com.luke.tracks.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.metadata.MetadataRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * A song tapped before it's in your library waits in the queue, loading, and is got as it's
 * reached: it plays in its own place once it can, or makes way for the next song.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SongsToGetTest {
    private fun toGet(title: String, cover: String? = "https://is1-ssl.mzstatic.com/$title.jpg") =
        SongsToGet.song("home", "$title|beyonce", title, "Beyoncé", null, 200_000, cover?.let(Uri::parse))

    private fun song(id: String, title: String = id, artwork: Uri? = null) =
        PlayableSong(id, Uri.parse("https://media.tracks-server.invalid/home/stream/$id"), title, "Beyoncé", null, artwork, 200_000, "$title|beyonce")

    /** The player's queue as [GetAsReached] sees it, telling it of every change as the service does. */
    private class FakeQueue(songs: List<PlayableSong>) : GetAsReached.Queue {
        val songs = songs.toMutableList()
        var index = 0
        override var playWhenReady = true
        var getting: GetAsReached? = null
        override val current get() = songs.getOrNull(index)
        override fun indexOf(mediaId: String): Int? =
            if (current?.mediaId == mediaId) index else songs.indexOfFirst { it.mediaId == mediaId }.takeIf { it >= 0 }
        override fun replace(index: Int, song: PlayableSong) {
            songs[index] = song
            getting?.check()
        }
        override fun remove(index: Int) {
            songs.removeAt(index)
            if (index < this.index) this.index--
            getting?.check()
        }
        fun moveTo(index: Int) {
            this.index = index
            getting?.check()
        }
    }

    @Test fun aTappedSongIsGotThenPlaysInItsOwnPlaceWithItsCover() = runTest {
        val halo = toGet("Halo")
        val queue = FakeQueue(listOf(halo, song("s2")))
        val status = MutableStateFlow<PlaybackBridge.Getting?>(null)
        val answer = CompletableDeferred<ToPlay>()
        val asked = mutableListOf<String>()
        val getting = GetAsReached(queue, backgroundScope, get = { s, say -> asked += s.mediaId; say("Downloading, 40%"); answer.await() }, failed = {}, status = status)
        queue.getting = getting
        getting.check()
        runCurrent()
        assertEquals(listOf(halo.mediaId), asked)
        assertEquals(PlaybackBridge.Getting(halo.mediaId, "Downloading, 40%"), status.value)
        // Asked again (a pause and play, the queue changing) doesn't ask twice.
        getting.check()
        runCurrent()
        assertEquals(1, asked.size)

        val streamed = song("trackside-new:home:halo", "Halo")
        answer.complete(ToPlay.Ready(streamed))
        runCurrent()
        assertEquals(streamed.copy(artworkUri = halo.artworkUri), queue.songs[0])
        assertEquals("the rest of the queue stays", "s2", queue.songs[1].mediaId)
        assertNull(status.value)
    }

    @Test fun aSongThatCantBeGotMakesWayAndTheNextOneToGetIsGotInTurn() = runTest {
        val halo = toGet("Halo")
        val ego = toGet("Ego")
        val queue = FakeQueue(listOf(halo, ego))
        val failures = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val egoReady = song("subsonic:home:ego", "Ego")
        val getting = GetAsReached(
            queue, backgroundScope,
            get = { s, _ -> asked += s.title; if (s == halo) ToPlay.Failed("Home couldn't find “Halo”.") else ToPlay.Ready(egoReady) },
            failed = { failures += it },
            status = MutableStateFlow(null),
        )
        queue.getting = getting
        getting.check()
        runCurrent()
        assertEquals(listOf("Home couldn't find “Halo”."), failures)
        assertEquals(listOf("Halo", "Ego"), asked)
        assertEquals(listOf(egoReady.copy(artworkUri = ego.artworkUri)), queue.songs)
    }

    @Test fun nothingIsAskedWhilePausedOrForSongsYouHave() = runTest {
        val halo = toGet("Halo")
        val queue = FakeQueue(listOf(song("s1"), halo))
        queue.playWhenReady = false
        val asked = mutableListOf<String>()
        val getting = GetAsReached(queue, backgroundScope, get = { s, _ -> asked += s.title; CompletableDeferred<ToPlay>().await() }, failed = {}, status = MutableStateFlow(null))
        queue.getting = getting
        getting.check()
        queue.moveTo(1)
        runCurrent()
        assertTrue("restored paused on it: nothing asked yet", asked.isEmpty())
        queue.playWhenReady = true
        getting.check()
        runCurrent()
        assertEquals(listOf("Halo"), asked)
    }

    @Test fun movingOnStopsWaitingAndComingBackWaitsAgain() = runTest {
        val halo = toGet("Halo")
        val queue = FakeQueue(listOf(halo, song("s2")))
        val status = MutableStateFlow<PlaybackBridge.Getting?>(null)
        var asks = 0
        val getting = GetAsReached(queue, backgroundScope, get = { _, _ -> asks++; CompletableDeferred<ToPlay>().await() }, failed = {}, status = status)
        queue.getting = getting
        getting.check()
        runCurrent()
        assertTrue(status.value != null)
        queue.moveTo(1)
        runCurrent()
        assertNull("no longer said to be getting", status.value)
        assertEquals("still queued, to get when it's reached again", halo, queue.songs[0])
        queue.moveTo(0)
        runCurrent()
        assertEquals(2, asks)
    }

    @Test fun aSongQueuedWithoutACoverGetsOneWhileItWaits() = runTest {
        val bare = toGet("Halo", cover = null)
        val queue = FakeQueue(listOf(bare))
        val answer = CompletableDeferred<ToPlay>()
        val cover = Uri.parse("https://is1-ssl.mzstatic.com/found.jpg")
        val getting = GetAsReached(queue, backgroundScope, get = { _, _ -> answer.await() }, cover = { cover }, failed = {}, status = MutableStateFlow(null))
        queue.getting = getting
        getting.check()
        runCurrent()
        assertEquals(cover, queue.songs[0].artworkUri)
        assertEquals(bare.mediaId, queue.songs[0].mediaId)
        answer.complete(ToPlay.Ready(song("subsonic:home:halo", "Halo")))
        runCurrent()
        assertEquals(cover, queue.songs[0].artworkUri)
    }

    @Test fun aSongToGetIsKnownByItsIdAndAddress() {
        val halo = toGet("Halo")
        assertEquals("home" to "Halo|beyonce", SongsToGet.parse(halo.mediaId))
        assertTrue(halo.isToGet && SongsToGet.isToGet(halo.toMediaItem()))
        assertFalse(song("subsonic:home:s1").isToGet)
        assertNull(SongsToGet.parse("subsonic:home:s1"))
        // Through Media3 and back, as the queue and its memory carry it.
        val back = halo.toMediaItem().restored()!!.toPlayableSong()
        assertEquals(halo, back)
    }

    // MARK: In ExoPlayer

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A short silent WAV, to play through to the song to get after it. */
    private fun silence(ms: Int): Uri {
        val rate = 8_000
        val samples = rate * ms / 1000
        val data = samples * 2
        val bytes = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
        val file = File.createTempFile("silence", ".wav").apply { writeBytes(bytes); deleteOnExit() }
        return Uri.fromFile(file)
    }

    /** ExoPlayer with no audio output (only a metadata renderer), so its own clock plays songs through. */
    private fun player(): ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(RenderersFactory { handler, _, _, _, metadata -> arrayOf(MetadataRenderer(metadata, handler.looper)) })
        .setMediaSourceFactory(WaitingMediaSource.Factory(DefaultMediaSourceFactory(context)))
        .build()

    private fun runUntil(what: String, condition: () -> Boolean) {
        val main = shadowOf(Looper.getMainLooper())
        repeat(2_000) {
            if (condition()) return
            main.idleFor(10, TimeUnit.MILLISECONDS)
            Thread.sleep(2)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test fun exoPlayerMovesOntoASongToGetAndWaitsOnItLoadingWithoutPlaying() {
        val exo = player()
        val transitions = mutableListOf<Int>()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions += reason
            }
        })
        val halo = toGet("Halo")
        exo.setMediaItems(listOf(song("s1").copy(uri = silence(300)).toMediaItem(), halo.toMediaItem()))
        exo.prepare()
        exo.play()
        // At the end of the song before, as it would be onto a slow stream.
        runUntil("the song to get") { exo.currentMediaItemIndex == 1 }
        assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, transitions.last())
        runUntil("buffering") { exo.playbackState == Player.STATE_BUFFERING }
        repeat(100) { shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(1) }
        assertEquals("waits on it", Player.STATE_BUFFERING, exo.playbackState)
        assertFalse("never playing, so never counted", exo.isPlaying)
        assertTrue(exo.playWhenReady)
        assertNull(exo.playerError)
        assertEquals("Halo", exo.currentMediaItem!!.mediaMetadata.title.toString())

        // Got: the song in its place plays from the start.
        exo.replaceMediaItem(1, song("s2").copy(uri = silence(300)).toMediaItem())
        runUntil("the song got to play") { exo.isPlaying && exo.currentMediaItem?.mediaId == "s2" }
        runUntil("the end") { exo.playbackState == Player.STATE_ENDED }
        exo.release()
    }

    @Test fun exoPlayerWaitsOnASongToGetChosenStraightAwayAndMovesOnWhenItsTakenOut() {
        val exo = player()
        exo.setMediaItems(listOf(toGet("Halo").toMediaItem(), song("s2").copy(uri = silence(200)).toMediaItem()))
        exo.prepare()
        exo.play()
        runUntil("buffering") { exo.playbackState == Player.STATE_BUFFERING }
        repeat(50) { shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS); Thread.sleep(1) }
        assertEquals(0, exo.currentMediaItemIndex)
        assertFalse(exo.isPlaying)
        // Couldn't be got: taken out, the next song plays.
        exo.removeMediaItem(0)
        runUntil("the next song") { exo.isPlaying && exo.currentMediaItem?.mediaId == "s2" }
        exo.release()
    }
}

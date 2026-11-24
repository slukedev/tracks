package com.luke.tracks.net

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueMemory
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.restored
import com.luke.tracks.playback.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What's kept on the phone, and what other apps' controllers see, holds no credential. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueMemoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val signedCover = "https://music.example.com/rest/getCoverArt?u=luke&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=Tracks&id=al-7&size=600"

    @Test fun anOlderQueueWithSignedAddressesComesBackClean() {
        // As an older version saved it: the signed stream and cover addresses.
        val signedStream = "https://music.example.com/rest/stream?u=luke&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&id=101&format=raw"
        val local = PlayableSong("local:5", Uri.parse("content://media/external/audio/media/5"), "Glasshouse", "Marigold", null, null, null, "id-2")
        val server = PlayableSong("subsonic:s1:101", Uri.parse(signedStream), "Undertow", "Low Tide Club", "Saltwater", Uri.parse(signedCover), 214_000, "id-1")
        val memory = QueueMemory(context)
        memory.save(listOf(server, local), 0, 42_000, QueueOrigin("Saltwater", QueueOrigin.Kind.Other))

        val raw = context.getSharedPreferences("playback", Context.MODE_PRIVATE).all.values.joinToString()
        listOf("26719a1196d2a940705a59634eb18eab", "c19b2d", "u=luke", "music.example.com").forEach { secret ->
            assertFalse("saved queue holds $secret", raw.contains(secret))
        }

        val restored = memory.restore()!!
        assertEquals(ServerLinks.Link("s1", ServerLinks.Kind.Stream, "101"), ServerLinks.parse(restored.songs[0].uri.toString()))
        assertEquals(ServerLinks.Link("s1", ServerLinks.Kind.Cover, "al-7", 600), ServerLinks.parse(restored.songs[0].artworkUri.toString()))
        assertEquals("content://media/external/audio/media/5", restored.songs[1].uri.toString())
        assertEquals(42_000, restored.positionMs)
    }

    @Test fun signedAddressesThatArentCoversAreDropped() {
        assertNull(QueueMemory.stableArtwork("subsonic:s1:101", "https://music.example.com/rest/stream?apiKey=k&id=101"))
        // Not a server's song: its artwork is left as it is.
        assertEquals("https://lastfm.freetls.fastly.net/i/u/300x300/a.png", QueueMemory.stableArtwork("lastfm:1", "https://lastfm.freetls.fastly.net/i/u/300x300/a.png"))
    }

    @Test fun aControllerCantPointAServerSongElsewhere() {
        // An item from Android Auto or another app with a server's id plays from that song's own link.
        val forged = PlayableSong("subsonic:s1:101", Uri.parse("https://evil.example.com/steal"), "Undertow", "Low Tide Club", null, null, null, "id-1")
            .toMediaItem().buildUpon().setUri(null as Uri?).build()
        val item = forged.restored()!!
        assertEquals(ServerLinks.Link("s1", ServerLinks.Kind.Stream, "101"), ServerLinks.parse(item.localConfiguration!!.uri.toString()))
        assertEquals(item.localConfiguration!!.uri, item.requestMetadata.mediaUri)
    }
}

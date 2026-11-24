package com.luke.tracks.library

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.luke.tracks.playback.FreshShufflePlayer
import com.luke.tracks.playback.ListenCounter
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.restored
import com.luke.tracks.playback.toMediaItem
import com.luke.tracks.playback.toPlayableSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.luke.tracks.net.Redact
import com.luke.tracks.net.ServerLinks
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.tracks_engine.songIdentity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The keep rule, with the engine's own Last.fm rule. */
class ListenCounterTest {
    @Test fun keepsAtHalfTheSong() {
        val counter = ListenCounter(durationMs = 200_000)
        assertEquals(100_000L, counter.requiredMs)
        assertFalse(counter.add(60_000))
        assertEquals(0.6f, counter.progress, 0.001f)
        assertTrue("kept once it has played half", counter.add(40_000))
        assertTrue(counter.kept)
        assertFalse("and only once", counter.add(60_000))
        assertEquals(160_000L, counter.listenedMs)
        assertEquals(1f, counter.progress)
    }

    @Test fun longSongsNeedFourMinutes() {
        assertEquals(240_000L, ListenCounter(durationMs = 20 * 60_000).requiredMs)
    }

    @Test fun shortSongsAreNeverKept() {
        val short = ListenCounter(durationMs = 25_000)
        assertNull(short.requiredMs)
        assertFalse(short.add(25_000))
        assertFalse(short.kept)
        assertEquals(0f, short.progress)
        // An unknown length counts after two minutes, as the engine says, until the length is known.
        assertEquals(120_000L, ListenCounter(durationMs = null).requiredMs)
    }

    /** Ringtones, streams and broken files: whatever length a player reports, nothing breaks. */
    @Test fun oddLengths() {
        // A three-second ringtone and the 30-second edge: never kept, no progress to show.
        for (length in listOf(1L, 3_000L, 30_000L)) {
            val counter = ListenCounter(length)
            assertNull("$length ms", counter.requiredMs)
            assertFalse(counter.add(60_000))
            assertEquals(0f, counter.progress)
            assertFalse(ListenCounter.canKeep(length))
        }
        assertTrue(ListenCounter.canKeep(31_000))
        // Unknown, however it's said: Media3's C.TIME_UNSET, 0 from Bluetooth, a negative, an
        // absurd header. All count as unknown (two minutes), so the real length can replace it.
        for (length in listOf(androidx.media3.common.C.TIME_UNSET, 0L, -1L, Long.MAX_VALUE, null)) {
            val counter = ListenCounter(length)
            assertNull("$length is unknown", counter.durationMs)
            assertEquals(120_000L, counter.requiredMs)
            assertTrue(ListenCounter.canKeep(length))
            counter.add(Long.MAX_VALUE / 2)
            assertEquals(1f, counter.progress)
        }
        // A rule that answers nothing usable never divides by zero.
        val zero = ListenCounter(200_000) { 0.0 }
        assertNull(zero.requiredMs)
        assertEquals(0f, zero.progress)
    }

    @Test fun onlyTimePlayedCounts() {
        val counter = ListenCounter(durationMs = 200_000)
        // Seeking ahead adds nothing: the service only adds wall-clock time while playing.
        assertFalse(counter.add(0))
        assertFalse(counter.add(-5_000))
        assertEquals(0L, counter.listenedMs)
    }
}

/** WAV tones for sample data mode. */
class TonesTest {
    @Test fun writesAPlayableWav() {
        val file = File.createTempFile("tone", ".wav").apply { deleteOnExit() }
        com.luke.tracks.library.Tones.writeChord(file, seed = 42)
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(16_000, header.getInt(24))
        val dataBytes = header.getInt(40)
        assertEquals(bytes.size - 44, dataBytes)
        assertEquals((Tones.SECONDS * Tones.SAMPLE_RATE).toInt() * 2, dataBytes)
    }

    @Test fun soundsDifferPerSongAndStayInRange() {
        val a = Tones.chord(1)
        val b = Tones.chord(2)
        assertNotEquals(a.toList(), b.toList())
        assertTrue(a.all { it in -1f..1f })
        assertTrue("ends quietly, no click", kotlin.math.abs(a.last()) < 0.001f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryIndexTest {
    private fun track(id: String, title: String, artist: String, album: String?, key: String, number: Int? = null, source: LibrarySourceKind = LibrarySourceKind.Device, disc: Int? = null) =
        LibraryTrack(
            song = PlayableSong(id, Uri.parse("content://x/$id"), title, artist, album, null, 200_000, songIdentity(title, artist), number),
            source = source,
            albumKey = key,
            disc = disc,
        )

    @Test fun mergesSourcesPreferringThePhone() {
        val index = LibraryIndex.build(
            listOf(
                track("local:1", "Undertow", "Low Tide Club", "Saltwater", "local:album:1", 2),
                track("local:2", "Harbor Lights", "Low Tide Club", "Saltwater", "local:album:1", 1),
                track("subsonic:s:9", "undertow", "LOW TIDE CLUB", "Saltwater", "subsonic:s:album:a", 2, LibrarySourceKind.Server),
                track("subsonic:s:10", "Glasshouse", "Marigold", "Bloom", "subsonic:s:album:b", 1, LibrarySourceKind.Server),
            ),
            hasPermission = true,
        )
        assertEquals(3, index.snapshot.songs.size)
        assertEquals("local:1", index.find(songIdentity("Undertow", "Low Tide Club"))?.mediaId)
        val saltwater = index.snapshot.albums.single { it.title == "Saltwater" }
        assertEquals(listOf("Harbor Lights", "Undertow"), saltwater.songs.map { it.title })
        assertEquals(saltwater.id, index.albumIdOf(index.song("local:1")!!))
        assertEquals(listOf("Low Tide Club", "Marigold"), index.snapshot.artists.map { it.name })
        assertEquals("artist:" + uniffi.tracks_engine.foldedName("Marigold"), index.artistIdOf("marigold"))
    }

    @Test fun ordersDiscsThenTracks() {
        val index = LibraryIndex.build(
            listOf(
                track("1", "B", "X", "Double", "k", 1, disc = 2),
                track("2", "A", "X", "Double", "k", 2, disc = 1),
                track("3", "C", "X", "Double", "k", 1, disc = 1),
            ),
            hasPermission = true,
        )
        assertEquals(listOf("C", "A", "B"), index.snapshot.albums.single().songs.map { it.title })
    }

    @Test fun searchesByEveryWord() {
        val index = LibraryIndex.build(
            listOf(
                track("1", "Undertow", "Low Tide Club", "Saltwater", "a"),
                track("2", "Glasshouse", "Marigold", "Bloom", "b"),
            ),
            hasPermission = true,
        )
        assertEquals(listOf("1"), index.search("tide under").map { it.mediaId })
        assertEquals(listOf("2"), index.search("BLOOM").map { it.mediaId })
        assertTrue(index.search("   ").isEmpty())
    }

    @Test fun serverSongsBecomeLinksWithoutCredentials() {
        val server = SubsonicServer("s1", "Home", "https://music.example.com", "luke")
        val tracks = ServerLibrary.tracks(
            server,
            listOf(
                SubsonicSong(id = "101", title = "Undertow", artist = "Low Tide Club", album = "Saltwater", albumId = "al-1", track = 3, duration = 214, coverArt = "al-1"),
                SubsonicSong(id = "102", title = "", artist = "Nobody"),
            ),
        )
        val song = tracks.single().song
        assertEquals("subsonic:s1:101", song.mediaId)
        assertEquals(ServerLinks.Link("s1", ServerLinks.Kind.Stream, "101"), ServerLinks.parse(song.uri.toString()))
        assertEquals(ServerLinks.Link("s1", ServerLinks.Kind.Cover, "al-1", 600), ServerLinks.parse(song.artworkUri.toString()))
        // Nothing in either address can be replayed against the server.
        listOf(song.uri, song.artworkUri!!).forEach { uri ->
            assertTrue(uri.queryParameterNames.none { it.lowercase() in Redact.SECRET_PARAMETERS })
            assertFalse(uri.toString().contains("music.example.com"))
        }
        assertEquals(214_000L, song.durationMs)
        assertEquals(songIdentity("Undertow", "Low Tide Club"), song.identity)
        assertEquals("subsonic:s1:album:al-1", tracks.single().albumKey)
        assertEquals("s1" to "101", ServerLibrary.parseMediaId(song.mediaId))
        assertNull(ServerLibrary.parseMediaId("local:5"))
    }

    @Test fun serversRoundTripWithoutThePassword() {
        val servers = listOf(SubsonicServer("s1", "Home", "https://music.example.com", "luke", secret = "c2VhbGVk"))
        val json = ServerLibrary.encode(servers)
        assertFalse(json.contains("password"))
        assertEquals(servers, ServerLibrary.parse(json))
        assertTrue(ServerLibrary.parse("not json").isEmpty())
    }

    @Test fun songsSurviveTheTripThroughMedia3() {
        val song = PlayableSong("local:1", Uri.parse("content://media/1"), "Undertow", "Low Tide Club", "Saltwater", Uri.parse("content://art/1"), 214_000, "id-1", 3, 2023, "Indie")
        val item = song.toMediaItem()
        // A controller's items arrive without their local configuration.
        val stripped = MediaItem.Builder().setMediaId(item.mediaId).setMediaMetadata(item.mediaMetadata).setRequestMetadata(item.requestMetadata).build()
        assertEquals(song, stripped.toPlayableSong())
        assertEquals(song.uri, stripped.restored()?.localConfiguration?.uri)
    }

    @Test fun freshShuffleKeepsEverySongAndSpreadsArtists() {
        val items = (0 until 12).map { i ->
            MediaItem.Builder().setMediaId("m$i").setMediaMetadata(MediaMetadata.Builder().setArtist(if (i < 6) "A" else "B").build()).build()
        }
        val shuffled = FreshShufflePlayer.shuffled(items, seed = 7u)
        assertEquals(items.map { it.mediaId }.sorted(), shuffled.map { it.mediaId }.sorted())
        val artists = shuffled.map { it.mediaMetadata.artist.toString() }
        assertTrue("no artist twice in a row: $artists", artists.zipWithNext().none { (a, b) -> a == b })
    }
}

package com.luke.tracks.playback

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.library.trackside.TracksideCatalogItem
import com.luke.tracks.streaming.CatalogMedia
import com.luke.tracks.ui.services.playStation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A service's station is a station, not Tracks Radio: it plays and comes back through the relay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StationResumptionTest {
    private class RecordingPlayer : PlayerController by IdlePlayer() {
        var origin: QueueOrigin? = null
        var songs: List<PlayableSong> = emptyList()
        override fun play(songs: List<PlayableSong>, startIndex: Int, origin: QueueOrigin?, shuffle: Boolean) {
            this.songs = songs
            this.origin = origin
        }
    }

    private val app get() = ApplicationProvider.getApplicationContext<TracksApplication>()
    private val player = RecordingPlayer()
    private lateinit var previous: PlayerController

    @Before fun setUp() {
        previous = app.graph.player
        app.graph.player = player
    }

    @After fun tearDown() {
        app.graph.player = previous
    }

    private fun song(mediaId: String) = PlayableSong(mediaId, Uri.parse("https://example.net/1"), "Song", "Artist", null, null, 1_000, "song|artist")

    @Test fun aServicesStationPlaysAsAStation() {
        playStation(app, "home", TracksideCatalogItem(kind = "radio", ref = "am:station:1", name = "Chill Mix"))
        assertEquals(QueueOrigin("Chill Mix", QueueOrigin.Kind.Station), player.origin)
        assertTrue(CatalogMedia.isCatalog(player.songs.single().mediaId))
        assertTrue(player.origin!!.isStation)
    }

    @Test fun tracksRadioStartsAgainOnlyFromYourOwnSongs() {
        val mine = listOf(song("subsonic:home:1"), song("local:2"))
        val station = listOf(song(CatalogMedia.mediaId("home", "am:station:1")))
        val radio = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio)
        assertTrue(Resumption.restartsTracksRadio(radio, mine))
        assertFalse(Resumption.restartsTracksRadio(radio, station))
        assertFalse(Resumption.restartsTracksRadio(QueueOrigin("Chill Mix", QueueOrigin.Kind.Station), station))
        assertFalse(Resumption.restartsTracksRadio(radio, mine + station))
    }

    @Test fun aStationSavedAsTracksRadioComesBackAsTheStationItIs() {
        val station = listOf(song(CatalogMedia.mediaId("home", "am:station:1")))
        assertEquals(QueueOrigin("Chill Mix", QueueOrigin.Kind.Station), Resumption.origin(QueueOrigin("Chill Mix", QueueOrigin.Kind.Radio), station))
        val radio = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio)
        assertEquals(radio, Resumption.origin(radio, listOf(song("local:2"))))
        assertTrue(Resumption.streams(station))
    }

    @Test fun tracksRadioPlayingFromAServiceStartsAgainAsTracksRadio() {
        val songs = listOf(song("subsonic:home:1"), song(CatalogMedia.mediaId("home", "am:song:1")))
        val radio = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio)
        assertTrue(Resumption.restartsTracksRadio(radio, songs, tracksRadio = true))
        assertEquals(radio, Resumption.origin(radio, songs, tracksRadio = true))
        // Saved and read back, it's known for Tracks Radio.
        val memory = QueueMemory(app)
        memory.save(songs, 1, 5_000, radio)
        val restored = memory.restore()!!
        assertTrue(restored.tracksRadio)
        assertTrue(Resumption.restartsTracksRadio(restored.origin, restored.songs, restored.tracksRadio))
        memory.save(songs, 1, 5_000, QueueOrigin("Chill Mix", QueueOrigin.Kind.Station))
        assertFalse(memory.restore()!!.tracksRadio)
    }
}

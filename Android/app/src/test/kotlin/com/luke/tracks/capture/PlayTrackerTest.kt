package com.luke.tracks.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayTrackerTest {
    private var now = 1_700_000_000_000L
    private val kept = mutableListOf<KeptSong>()
    private val started = mutableListOf<TrackedSong>()
    private var excluded = setOf<String>()
    private lateinit var tracker: PlayTracker

    private val spotify = "com.spotify.music"
    private val ytm = "com.google.android.apps.youtube.music"

    @Before
    fun setUp() {
        tracker = PlayTracker(
            ownPackage = "com.luke.tracks",
            clock = { now },
            isExcluded = { it in excluded },
            onKeep = { kept += it },
            onStart = { started += it },
        )
    }

    private fun song(title: String = "Saltwater", artist: String = "Mara Solis", durationMs: Long? = 200_000, album: String? = "Tides") =
        TrackMetadata(title = title, artist = artist, album = album, durationMs = durationMs)

    private fun advance(ms: Long) {
        now += ms
        tracker.tick()
    }

    @Test
    fun keepsOnceHalfHasPlayed() {
        tracker.onMetadata(spotify, song())
        tracker.onPlayback(spotify, playing = true, positionMs = 0)
        advance(99_000)
        assertTrue(kept.isEmpty())
        advance(1_000)
        assertEquals(1, kept.size)
        assertEquals("Saltwater", kept[0].song.title)
        assertEquals(100_000, kept[0].listenedMs)
        assertEquals(1_700_000_000_000L, kept[0].startedAtMs)
        assertFalse(kept[0].song.isRadio)
    }

    @Test
    fun longSongsNeedFourMinutes() {
        tracker.onMetadata(spotify, song(durationMs = 20 * 60_000))
        tracker.onPlayback(spotify, true, 0)
        advance(239_000)
        assertTrue(kept.isEmpty())
        advance(1_000)
        assertEquals(1, kept.size)
    }

    @Test
    fun pausedTimeDoesNotCount() {
        tracker.onMetadata(spotify, song())
        tracker.onPlayback(spotify, true, 0)
        advance(60_000)
        tracker.onPlayback(spotify, false, 60_000)
        advance(10 * 60_000)
        assertTrue(kept.isEmpty())
        tracker.onPlayback(spotify, true, 60_000)
        advance(39_000)
        assertTrue(kept.isEmpty())
        advance(1_000)
        assertEquals(1, kept.size)
        assertEquals(100_000, kept[0].listenedMs)
    }

    @Test
    fun skippedSongsAreNotKept() {
        tracker.onMetadata(spotify, song(title = "First"))
        tracker.onPlayback(spotify, true, 0)
        advance(20_000)
        tracker.onMetadata(spotify, song(title = "Second"))
        advance(100_000)
        assertEquals(listOf("Second"), kept.map { it.song.title })
    }

    @Test
    fun theSameSongCarryingOnIsOnePlay() {
        tracker.onMetadata(spotify, song())
        tracker.onPlayback(spotify, true, 0)
        advance(60_000)
        // Artwork arriving, or the app sending its metadata again.
        tracker.onMetadata(spotify, song().copy(artUri = "https://example.com/c.jpg"))
        tracker.onPlayback(spotify, true, 60_000)
        advance(40_000)
        advance(100_000)
        tracker.onMetadata(spotify, song())
        advance(100_000)
        assertEquals(1, kept.size)
        assertEquals("https://example.com/c.jpg", kept[0].song.artUri)
    }

    @Test
    fun repeatOneKeepsEachPlay() {
        tracker.onMetadata(spotify, song())
        tracker.onPlayback(spotify, true, 0)
        advance(200_000)
        assertEquals(1, kept.size)
        // The app jumps back to the top of the same song.
        tracker.onPlayback(spotify, true, 500)
        advance(100_000)
        assertEquals(2, kept.size)
    }

    @Test
    fun seekingBackALittleIsNotARestart() {
        tracker.onMetadata(spotify, song())
        tracker.onPlayback(spotify, true, 0)
        advance(30_000)
        tracker.onPlayback(spotify, true, 20_000)
        advance(70_000)
        assertEquals(1, kept.size)
        tracker.onPlayback(spotify, true, 75_000)
        advance(100_000)
        assertEquals(1, kept.size)
    }

    @Test
    fun twoAppsReportingOneSongKeepItOnce() {
        tracker.onMetadata(spotify, song())
        tracker.onMetadata(ytm, song(title = "saltwater ", artist = "MARA SOLIS"))
        tracker.onPlayback(spotify, true, 0)
        tracker.onPlayback(ytm, true, 0)
        advance(100_000)
        assertEquals(1, kept.size)
    }

    @Test
    fun ignoresTracksItself() {
        tracker.onMetadata("com.luke.tracks", song())
        tracker.onPlayback("com.luke.tracks", true, 0)
        advance(300_000)
        assertTrue(kept.isEmpty())
        assertEquals(Ignored.OwnApp, tracker.ignoredReason("com.luke.tracks"))
    }

    @Test
    fun ignoresExcludedApps() {
        excluded = setOf(spotify)
        tracker.onMetadata(spotify, song())
        tracker.onPlayback(spotify, true, 0)
        advance(300_000)
        assertTrue(kept.isEmpty())
        assertEquals(Ignored.ExcludedApp, tracker.ignoredReason(spotify))
    }

    @Test
    fun ignoresPodcastsByAppAndByHint() {
        tracker.onMetadata("au.com.shiftyjelly.pocketcasts", song())
        tracker.onPlayback("au.com.shiftyjelly.pocketcasts", true, 0)
        tracker.onMetadata(spotify, song().copy(mediaId = "spotify:episode:4rOoJ6Egrf8K2IrywzwOMk"))
        tracker.onPlayback(spotify, true, 0)
        advance(300_000)
        assertTrue(kept.isEmpty())
        assertEquals(Ignored.Podcast, tracker.ignoredReason(spotify))
    }

    @Test
    fun ignoresVeryLongItems() {
        tracker.onMetadata(spotify, song(durationMs = 90 * 60_000))
        tracker.onPlayback(spotify, true, 0)
        advance(30 * 60_000)
        assertTrue(kept.isEmpty())
        assertEquals(Ignored.TooLong, tracker.ignoredReason(spotify))
    }

    @Test
    fun ignoresVideoApps() {
        tracker.onMetadata("com.google.android.youtube", song())
        tracker.onPlayback("com.google.android.youtube", true, 0)
        advance(300_000)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun ignoresAdverts() {
        tracker.onMetadata(spotify, song(title = "Advertisement", artist = "Spotify", durationMs = 30_000))
        tracker.onPlayback(spotify, true, 0)
        advance(60_000)
        tracker.onMetadata(spotify, song(title = "Hear more", artist = "Brand", durationMs = 45_000).copy(mediaId = "spotify:ad:123"))
        advance(60_000)
        assertTrue(kept.isEmpty())
        assertEquals(Ignored.Advert, tracker.ignoredReason(spotify))
    }

    @Test
    fun shortTracksNeverCount() {
        tracker.onMetadata(spotify, song(durationMs = 25_000))
        tracker.onPlayback(spotify, true, 0)
        advance(25_000)
        assertTrue(kept.isEmpty())
        assertNull(tracker.msUntilDue())
    }

    @Test
    fun incompleteMetadataIsSkipped() {
        tracker.onMetadata(spotify, TrackMetadata(title = "Loading", artist = null))
        tracker.onPlayback(spotify, true, 0)
        advance(300_000)
        assertTrue(kept.isEmpty())
        assertEquals(Ignored.Incomplete, tracker.ignoredReason(spotify))
    }

    @Test
    fun liveRadioSplitsArtistAndTitle() {
        val tunein = "tunein.player"
        tracker.onMetadata(tunein, TrackMetadata(title = "Mara Solis - Saltwater", artist = "Coastline FM", durationMs = null))
        tracker.onPlayback(tunein, true, null)
        advance(29_000)
        assertTrue(kept.isEmpty())
        advance(1_000)
        assertEquals(1, kept.size)
        val song = kept[0].song
        assertTrue(song.isRadio)
        assertEquals("Saltwater", song.title)
        assertEquals("Mara Solis", song.artist)
        assertEquals("Coastline FM", song.stationName)
    }

    @Test
    fun liveStreamWithAStationLineIsRadio() {
        val app = "com.example.player"
        tracker.onMetadata(app, TrackMetadata(title = "Saltwater", artist = "Mara Solis", album = "Coastline FM", durationMs = 0))
        tracker.onPlayback(app, true, null)
        advance(30_000)
        assertEquals(1, kept.size)
        assertTrue(kept[0].song.isRadio)
        assertEquals("Coastline FM", kept[0].song.stationName)
        assertNull(kept[0].song.album)
    }

    @Test
    fun stationIdentsAreNotSongs() {
        val tunein = "tunein.player"
        tracker.onMetadata(tunein, TrackMetadata(title = "Coastline FM", artist = "Coastline FM", durationMs = null))
        tracker.onPlayback(tunein, true, null)
        advance(120_000)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun sessionEndingBanksWhatPlayed() {
        tracker.onMetadata(spotify, song())
        tracker.onPlayback(spotify, true, 0)
        now += 100_000
        tracker.onSessionGone(spotify)
        assertEquals(1, kept.size)
        tracker.onMetadata(spotify, song(title = "Other"))
        tracker.onPlayback(spotify, true, 0)
        now += 30_000
        tracker.onSessionGone(spotify)
        assertEquals(1, kept.size)
    }

    @Test
    fun reportsWhenTheNextKeepIsDue() {
        tracker.onMetadata(spotify, song())
        assertNull(tracker.msUntilDue())
        tracker.onPlayback(spotify, true, 0)
        assertEquals(100_000L, tracker.msUntilDue())
        now += 40_000
        assertEquals(60_000L, tracker.msUntilDue())
        tracker.onPlayback(spotify, false, 40_000)
        assertNull(tracker.msUntilDue())
    }

    @Test
    fun announcesEachSongAsItStarts() {
        tracker.onMetadata(spotify, song())
        assertTrue(started.isEmpty())
        tracker.onPlayback(spotify, true, 0)
        assertEquals(1, started.size)
        tracker.onMetadata(spotify, song(title = "Next"))
        assertEquals(listOf("Saltwater", "Next"), started.map { it.title })
        tracker.onPlayback(spotify, false, 1_000)
        tracker.onPlayback(spotify, true, 1_000)
        assertEquals(2, started.size)
    }

    @Test
    fun lastFmRuleMatchesTheEngine() {
        // The engine's rule is what the app uses; this one stands in on the JVM.
        val cases = listOf(200.0 to 99.0, 200.0 to 100.0, 30.0 to 30.0, 31.0 to 15.5, 1200.0 to 240.0, 1200.0 to 239.0)
        cases.forEach { (duration, played) ->
            assertEquals(
                "duration $duration played $played",
                uniffi.tracks_engine.lastfmShouldScrobble(duration, played),
                PlayTracker.lastFmRule(duration, played),
            )
        }
    }

    @Test
    fun splitsArtistTitle() {
        assertEquals("A" to "B - C", PlayTracker.splitArtistTitle("A - B - C"))
        assertNull(PlayTracker.splitArtistTitle("Just a title"))
        assertNull(PlayTracker.splitArtistTitle(" - B"))
    }
}

package com.luke.tracks.play

import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.data.SampleData
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.library.LibrarySnapshot
import com.luke.tracks.library.LibrarySourceKind
import com.luke.tracks.library.LibraryTrack
import com.luke.tracks.library.MusicLibrary
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlaybackBridge
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.radio.RadioWords
import com.luke.tracks.playback.radio.TracksRadio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.tracks_engine.Listen
import uniffi.tracks_engine.RadioDiscovery
import uniffi.tracks_engine.RadioReason
import uniffi.tracks_engine.RadioSteering
import uniffi.tracks_engine.demoSongMetadata
import uniffi.tracks_engine.radioListen
import java.io.File
import java.nio.file.Files

/** The sample library with the sample songs' genres and years, as sample data mode has them. */
class GenreLibrary(base: SampleLibrary = SampleLibrary()) : MusicLibrary {
    private val details = demoSongMetadata(SampleData.SEED).associateBy { it.identity }
    val songs: List<PlayableSong> = base.songs.map { song ->
        details[song.identity]?.let { song.copy(genre = it.genre, year = it.releaseYear) } ?: song.copy(genre = "Pop", year = 2021)
    }
    private val byIdentity = songs.associateBy { it.identity }
    override val snapshot: StateFlow<LibrarySnapshot> = MutableStateFlow(
        LibraryIndex.build(songs.map { LibraryTrack(it, LibrarySourceKind.Device, albumKey = "album:" + it.albumTitle) }, hasPermission = true).snapshot,
    )
    override suspend fun refresh() = Unit
    override fun find(identity: String): PlayableSong? = byIdentity[identity]
    override suspend fun search(text: String): List<PlayableSong> = emptyList()
}

/**
 * Tracks Radio wired to the real engine over the sample history and library: how it starts,
 * picks, hears skips and loves, retunes, and what it keeps for the next listen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TracksRadioTest {
    private val graph get() = ApplicationProvider.getApplicationContext<TracksApplication>().graph
    private lateinit var files: File
    private lateinit var library: GenreLibrary
    private lateinit var radio: TracksRadio

    @Before fun setUp() = runBlocking {
        graph.history.replaceAll(SampleData.plays())
        library = GenreLibrary()
        graph.library = library
        files = Files.createTempDirectory("radio").toFile()
        radio = TracksRadio(graph, files)
        PlaybackBridge.origin.value = QueueOrigin("Tracks Radio", QueueOrigin.Kind.Radio)
    }

    @After fun tearDown() {
        PlaybackBridge.origin.value = null
        files.deleteRecursively()
    }

    private fun genre(song: PlayableSong) = library.find(song.identity)?.genre

    @Test fun startsWithTwoSongsThatSayWhyAndProbesFromNothing() = runBlocking {
        val first = radio.start()
        assertEquals(2, first.size)
        assertEquals(first.map { it.mediaId }.toSet(), radio.state.value.picks.keys)
        // A big library of many genres opens by trying a few directions.
        assertEquals(RadioReason.Probing, radio.state.value.reason(first[0]))
        assertNotEquals(genre(first[0]), genre(first[1]))
        val next = radio.next()
        assertNotNull(next)
        assertTrue(radio.state.value.isPick(next!!))
        assertFalse(next.mediaId in first.map { it.mediaId })
    }

    @Test fun startedFromASongItOpensOnItAndDoesNotProbe() = runBlocking {
        val song = library.songs[10]
        val first = radio.start(startingWith = song)
        assertEquals(song, first[0])
        assertNotEquals(RadioReason.Probing, radio.state.value.reason(first[1]))
    }

    @Test fun twoSkipsInOneGenreTurnAwayFromIt() = runBlocking {
        radio.start(startingWith = library.songs[0])
        // Two of one genre, both the station's own, skipped one after another.
        val genre = genre(library.songs[0])!!
        val sameGenre = library.songs.filter { genre(it) == genre }.take(3)
        sameGenre.drop(1).forEach { radio.note(it, Listen.SKIPPED) }
        assertEquals(RadioSteering.AwayFromGenre(genre), radio.state.value.steering)
        repeat(5) {
            val pick = radio.next()!!
            assertNotEquals(genre, genre(pick))
        }
        assertTrue(RadioWords.line(radio.state.value.steering!!).startsWith("You skipped a few $genre songs"))
    }

    @Test fun moreLikeThisFollowsTheArtistAndShowsAsAsked() = runBlocking {
        val song = library.songs[3]
        radio.start(startingWith = song)
        radio.moreLikeThis(song)
        assertEquals(RadioSteering.TowardArtist(song.artistName), radio.state.value.steering)
        assertTrue(song.identity in radio.state.value.askedMoreLike)
    }

    @Test fun keepsWhatLandedAndWhatWasSkippedForNextTime() = runBlocking {
        val first = radio.start()
        radio.note(first[0], Listen.FINISHED)
        radio.recordSkip(first[1])
        // Kept on a background thread.
        repeat(50) { if (!File(files, "radio-skips.json").exists()) Thread.sleep(20) }
        val habits = File(files, "radio-habits.json").readText()
        assertTrue(habits, habits.contains("\"anytime\""))
        assertTrue(org.json.JSONObject(File(files, "radio-skips.json").readText()).getJSONObject("skips").has(first[1].identity))
    }

    @Test fun aNewTuningCarriesTheListenOver() = runBlocking {
        radio.start(startingWith = library.songs[0])
        radio.watch()
        radio.loved(library.songs[0])
        val steering = radio.state.value.steering
        graph.settings.update { it.copy(radioDiscovery = RadioDiscovery.ADVENTUROUS) }
        repeat(50) { Thread.sleep(20) }
        // Still steering where it was, and still picking.
        assertEquals(steering, radio.state.value.steering)
        assertNotNull(radio.next())
        graph.settings.update { it.copy(radioDiscovery = RadioDiscovery.BALANCED) }
    }

    @Test fun nothingSteersOnceSomethingElsePlays() = runBlocking {
        val first = radio.start()
        PlaybackBridge.origin.value = QueueOrigin("On Repeat", QueueOrigin.Kind.Mix)
        radio.note(first[0], Listen.SKIPPED)
        radio.note(first[1], Listen.SKIPPED)
        assertEquals(null, radio.state.value.steering)
    }

    /** A server that suggests [count] new songs and plays them as [play] says. */
    private fun newSongs(count: Int, play: (com.luke.tracks.discover.ServerSong) -> PlayableSong? = { found ->
        PlayableSong(
            com.luke.tracks.library.ServerLibrary.newSongMediaId("home", found.song.identity),
            android.net.Uri.parse("https://media.tracks-server.invalid/home/play/x"),
            found.song.title, found.song.artist, null, null, 200_000, found.song.identity,
        )
    }): com.luke.tracks.discover.ServerPicks = object : com.luke.tracks.discover.ServerPicks by com.luke.tracks.discover.NoServerPicks {
        override suspend fun newForRadio() = (1..count).map { com.luke.tracks.discover.ServerSong("home", com.luke.tracks.discover.newSong("New song $it", "New Artist $it")) }
        override suspend fun playNew(song: com.luke.tracks.discover.ServerSong) = play(song)
    }

    @Test fun newSongsTheServerSuggestsComeEveryFewSongsNeverTwoTogether() = runBlocking {
        radio = TracksRadio(graph, files, picks = { newSongs(5) })
        val played = radio.start() + List(20) { radio.next()!! }
        val isNew = played.map { com.luke.tracks.library.ServerLibrary.parseNewSongMediaId(it.mediaId) != null }
        assertEquals("one in four: $isNew", 5, isNew.count { it })
        assertTrue("never two together: $isNew", isNew.zipWithNext().none { (a, b) -> a && b })
        assertTrue("never first: $isNew", isNew.take(3).none { it })
        // Each once, said to be a new find, and still the station's pick in Up next.
        val news = played.filterIndexed { i, _ -> isNew[i] }
        assertEquals(news.size, news.map { it.identity }.toSet().size)
        assertTrue(news.all { radio.state.value.isPick(it) && radio.state.value.reason(it) == RadioReason.NewFind })
    }

    @Test fun newSongsThatCantPlayAreLeftOut() = runBlocking {
        radio = TracksRadio(graph, files, picks = { newSongs(5) { null } })
        val played = radio.start() + List(12) { radio.next()!! }
        assertTrue(played.none { com.luke.tracks.library.ServerLibrary.parseNewSongMediaId(it.mediaId) != null })
        assertTrue(played.all { library.find(it.identity) != null })
    }

    @Test fun howASongGaveWayIsToldByHowLongItPlayed() {
        assertEquals(Listen.SKIPPED, radioListen(8_000, 200_000))
        assertEquals(Listen.MOVED_ON, radioListen(70_000, 200_000))
        assertEquals(Listen.FINISHED, radioListen(170_000, 200_000))
        assertEquals(Listen.FINISHED, radioListen(8_000, null))
    }
}

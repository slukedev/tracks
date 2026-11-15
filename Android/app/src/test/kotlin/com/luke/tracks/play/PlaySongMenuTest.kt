package com.luke.tracks.play

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.TracksApplication
import com.luke.tracks.data.SampleData
import com.luke.tracks.discover.LocalDiscovery
import com.luke.tracks.discover.LocalServerPicks
import com.luke.tracks.discover.NoDiscovery
import com.luke.tracks.discover.ServerSong
import com.luke.tracks.ui.play.components.ref
import com.luke.tracks.ui.play.components.songLinks
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.ServerAlbumRoute
import com.luke.tracks.ui.navigation.ServerArtistRoute
import com.luke.tracks.ui.play.MixScreen
import com.luke.tracks.ui.play.PlayScreen
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.theme.TracksTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Every song on Play, wherever it sits, has Go to album and Go to artist a press and hold (or
 * a TalkBack swipe) away, and they lead to your music's pages for songs you have and to the
 * server's for songs it found that you don't.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h4600dp-xxhdpi")
class PlaySongMenuTest {
    @get:Rule val compose = createComposeRule()

    private val graph get() = (ApplicationProvider.getApplicationContext<TracksApplication>()).graph
    private val navigator = Navigator()

    private fun show(picks: FakeServerPicks? = null, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TracksTheme(dark = false, dynamic = false, seed = TracksBrand) {
                CompositionLocalProvider(LocalDiscovery provides NoDiscovery, LocalServerPicks provides picks, LocalNavigator provides navigator) { content() }
            }
        }
        repeat(60) {
            Thread.sleep(25)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(50)
        }
        compose.waitForIdle()
    }

    private fun held(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).fetchSemanticsNodes()

    private val SemanticsNode.text: String
        get() = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    private val SemanticsNode.goTo: List<String>
        get() = config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun go(title: String, label: String): Any = go(held().first { it.text.contains(title) }, label)

    private fun go(node: SemanticsNode, label: String): Any {
        compose.runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
        return navigator.backStack.last().also { compose.runOnIdle { navigator.back() } }
    }

    @Test
    fun everySongAlbumAndArtistOnPlayLeadsSomewhere() {
        runBlocking { graph.history.replaceAll(SampleData.plays()) }
        val library = SampleLibrary().also { graph.library = it }
        val picks = FakeServerPicks(library)
        show(picks) { PlayScreen() }

        val songs = picks.forYou.value.values.flatMap { it.shelves.picks + it.shelves.suggested }.map { it.title to it.artist } +
            picks.exploring.value.map { it.song.title to it.song.artist } +
            library.snapshot.value.songs.map { it.title to it.artistName }
        val artists = songs.map { it.second }.toSet()

        val nodes = held()
        // Mixes and Tracks Radio aside, everything you can hold is by someone.
        val bySomeone = nodes.filter { node -> artists.any { node.text.contains(it) } }
        val withoutArtist = bySomeone.filter { "Go to artist" !in it.goTo }.map { it.text }
        assertEquals("no Go to artist on $withoutArtist", emptyList<String>(), withoutArtist)

        val songNodes = bySomeone.filter { node -> songs.any { (title, artist) -> node.text == "$title $artist" } }
        assertTrue("songs from every shelf are on screen (${songNodes.size})", songNodes.size >= 20)
        val withoutAlbum = songNodes.filter { "Go to album" !in it.goTo }.map { it.text }
        assertEquals("no Go to album on $withoutAlbum", emptyList<String>(), withoutAlbum)

        // Waiting to be heard: your own songs open your music's pages.
        assertTrue(go("Paper Lanterns", "Go to album") is LibraryAlbumRoute)
        assertTrue(go("Paper Lanterns", "Go to artist") is LibraryArtistRoute)

        // A song the server found that you don't have opens the server's album and artist.
        val found = picks.forYou.value.values.first().shelves.suggested.first { library.find(it.identity) == null }
        val album = go(found.title, "Go to album") as ServerAlbumRoute
        assertEquals(ServerAlbumRoute("s1", found.album!!, found.artist, songId = found.id), album)
        assertEquals(ServerArtistRoute("s1", found.artist, songId = found.id), go(found.title, "Go to artist"))

        // One it found that you have opens yours (the shelf puts those further along, off screen).
        val owned = picks.forYou.value.values.first().shelves.picks.first { library.find(it.identity) != null }
        val yours = library.find(owned.identity)!!.ref()
        val links = songLinks(yours, library.index.value, ServerSong("s1", owned))
        assertEquals(library.index.value.albumIdOf(library.find(owned.identity)!!)?.let(::LibraryAlbumRoute), links.album)
        assertTrue(links.artist is LibraryArtistRoute)
    }

    @Test
    fun mixSongsLeadToYourMusicOrYourHistory() {
        runBlocking { graph.history.replaceAll(SampleData.plays()) }
        val library = SampleLibrary().also { graph.library = it }
        show { MixScreen("onRepeat") }

        val rows = held().filter { it.goTo.isNotEmpty() }
        assertTrue("the mix's songs are there (${rows.size})", rows.size >= 10)
        assertTrue(rows.all { "Go to artist" in it.goTo })
        val mine = library.snapshot.value.songs.first { song -> rows.any { it.text.contains(song.title + " " + song.artistName) } }
        assertTrue(go("${mine.title} ${mine.artistName}", "Go to artist") is LibraryArtistRoute)
        val elsewhere = rows.first { row -> library.snapshot.value.songs.none { row.text.contains(it.title + " " + it.artistName) } }
        assertTrue(go(elsewhere, "Go to artist") is ArtistRoute)
    }
}

package com.luke.tracks.play

import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.data.SampleData
import com.luke.tracks.discover.Fixtures
import com.luke.tracks.discover.LocalServerPicks
import com.luke.tracks.discover.ServerAlbum
import com.luke.tracks.discover.ServerAlbums
import com.luke.tracks.discover.ServerForYouState
import com.luke.tracks.discover.ServerPicks
import com.luke.tracks.discover.ServerSong
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.play.PlayScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.annotation.Config
import uniffi.tracks_engine.ForYouShelf
import uniffi.tracks_engine.ForYouShelves
import uniffi.tracks_engine.FoundSong
import uniffi.tracks_engine.catalogParse
import uniffi.tracks_engine.songIdentity

/**
 * A server's For You as the engine would leave it: Picked for you from the sample artists
 * (songs of theirs you don't have, among ones you do), Suggested songs by an artist new to
 * you (the recorded catalog's Radiohead), and the server's own album shelves.
 */
class FakeServerPicks(
    private val library: SampleLibrary,
    /** Octo in front of the server: songs not in your music can be added. */
    private val fetches: Boolean = true,
    state: Shape = Shape.Loaded,
) : ServerPicks {
    enum class Shape { Loaded, Loading, FoundNothing }

    val server = SubsonicServer("s1", "Octo at home", "http://192.168.1.20:4533", "luke", "salt")

    private fun found(title: String, artist: String, album: String?, id: String) =
        FoundSong(id, songIdentity(title, artist), title, artist, album, null, 240_000uL)

    /** Sample songs, half of them not in the library: what the server found by your artists. */
    private val picks: List<FoundSong> = SampleData.plays(days = 300).distinctBy { it.title to it.artistName }.take(40)
        .map { found(it.title, it.artistName, it.albumTitle, "p-${it.title}") }
        .sortedBy { library.find(it.identity) != null }
        .take(12)

    private val suggested: List<FoundSong> = (catalogParse(Fixtures.ok("itunes_artist_songs")).songs + catalogParse(Fixtures.ok("itunes_album_tracks")).songs)
        .map { found(it.title, it.artistName, it.albumTitle, "s-${it.id}") }
        .distinctBy { it.identity }
        .take(16)

    override val servers = MutableStateFlow(listOf(server))
    override val forYou = MutableStateFlow(
        mapOf(
            server.id to when (state) {
                Shape.Loaded -> ServerForYouState(ForYouShelves(picks, suggested.take(8), suggested.takeLast(4), listOf("Mara Solis", "Juniper Lane", "Nova Harbor"), true, 0))
                Shape.Loading -> ServerForYouState(ForYouShelves(emptyList(), emptyList(), emptyList(), listOf("Mara Solis"), true, 0), isFilling = true)
                Shape.FoundNothing -> ServerForYouState(ForYouShelves(emptyList(), emptyList(), emptyList(), listOf("Mara Solis"), false, 0))
            },
        ),
    )
    override val albums = MutableStateFlow(
        if (state == Shape.Loaded) {
            val all = library.snapshot.value.albums.map { ServerAlbum(it.id, it.title, it.artistName, null) }
            mapOf(server.id to ServerAlbums(mostPlayed = all.take(6), random = all.takeLast(6)))
        } else emptyMap(),
    )
    override val exploring = MutableStateFlow(if (state == Shape.Loaded) suggested.drop(8).map { ServerSong(server.id, it) } else emptyList())
    override val canExploreMore = MutableStateFlow(false)
    override val answers = MutableStateFlow(0)

    override suspend fun load(serverId: String, force: Boolean) = Unit
    override suspend fun loadAlbums(serverId: String) = Unit
    override suspend fun loadMore(serverId: String, shelf: ForYouShelf, want: Int) { awaitCancellation() }
    override suspend fun exploreMore() = Unit
    override suspend fun shuffleRandom(serverId: String) = Unit
    override fun playable(song: ServerSong): PlayableSong? = library.find(song.song.identity)
    override fun coverUrl(song: ServerSong): String? = null
    override fun canKeep(serverId: String) = fetches
    override suspend fun keep(song: ServerSong) = ""
}

class ServerPicksScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private fun snapPlay(name: String, picks: (SampleLibrary) -> ServerPicks) {
        withSampleHistory()
        val library = SampleLibrary().also { graph.library = it }
        val fake = picks(library)
        snap(name, settleMs = 3000) {
            CompositionLocalProvider(LocalNavigator provides Navigator(), LocalServerPicks provides fake) { PlayScreen() }
        }
    }

    /** Suggested songs under the crate, from Octo: Add on songs not in your music. */
    @Test fun withOcto() = snapPlay("play-server") { FakeServerPicks(it) }

    /** A plain server: the same shelves, nothing offers to add. */
    @Test fun withPlainServer() = snapPlay("play-server-plain") { FakeServerPicks(it, fetches = false) }

    @Test fun loading() = snapPlay("play-server-loading") { FakeServerPicks(it, state = FakeServerPicks.Shape.Loading) }

    /** The server found nothing to suggest: it says why, once. */
    @Test fun foundNothing() = snapPlay("play-server-nothing") { FakeServerPicks(it, state = FakeServerPicks.Shape.FoundNothing) }

    /** The whole page, past the fold, with Octo. */
    @Test
    @Config(qualifiers = "w412dp-h4600dp-xxhdpi")
    fun full() = snapPlay("play-server-full") { FakeServerPicks(it) }

    /** The whole page with no server: the shelves made from your music alone. */
    @Test
    @Config(qualifiers = "w412dp-h1900dp-xxhdpi")
    fun fullWithoutServer() = snapPlay("play-full") { com.luke.tracks.discover.NoServerPicks }
}

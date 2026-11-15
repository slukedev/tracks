package com.luke.tracks.play

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.discover.ServerSong
import com.luke.tracks.library.FakeSubsonic
import com.luke.tracks.library.ServerBrowse
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.ui.library.servers.LocalServerBrowse
import com.luke.tracks.ui.library.servers.ServerAlbumScreen
import com.luke.tracks.ui.library.servers.ServerArtistScreen
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.ServerAlbumRoute
import com.luke.tracks.ui.navigation.ServerArtistRoute
import com.luke.tracks.ui.play.components.SongActionsPreview
import com.luke.tracks.ui.play.components.SongRef
import com.luke.tracks.ui.play.components.rememberPlayActions
import com.luke.tracks.ui.play.components.songLinks
import org.junit.Test
import org.robolectric.annotation.Config

/** A song's press-and-hold sheet on Play, and the server pages its Go to album and Go to artist open. */
class SongMenuScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNavigator provides Navigator()) {
            Box(Modifier.padding(top = 300.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(top = 28.dp)) { content() }
                }
            }
        }
    }

    /** A song Octo found that you don't have: it plays from the server, and can be added. */
    @Test fun serverSong() {
        withSampleHistory()
        val library = SampleLibrary().also { graph.library = it }
        val found = FakeServerPicks(library).forYou.value.values.first().shelves.suggested.first { library.find(it.identity) == null }
        val stream = PlayableSong("subsonic:s1:${found.id}", Uri.parse("http://192.168.1.20:4533/rest/stream"), found.title, found.artist, found.album, null, 240_000, found.identity)
        val song = SongRef(found.identity, found.title, found.artist, found.album, null, null, stream)
        snap("song-menu") {
            Sheet { SongActionsPreview(song, rememberPlayActions(), songLinks(song, library.index.value, ServerSong("s1", found)), onAdd = {}) }
        }
    }

    /** A mix's song this phone can't play: no queueing, Open in another app instead. */
    @Test fun historySong() {
        withSampleHistory()
        val library = SampleLibrary().also { graph.library = it }
        val play = com.luke.tracks.data.SampleData.plays().first { library.find(uniffi.tracks_engine.songIdentity(it.title, it.artistName)) == null && it.albumTitle != null }
        val song = SongRef(uniffi.tracks_engine.songIdentity(play.title, play.artistName), play.title, play.artistName, play.albumTitle, null, 14, null)
        snap("song-menu-elsewhere") {
            Sheet { SongActionsPreview(song, rememberPlayActions(), songLinks(song, library.index.value)) }
        }
    }

    private fun server(name: String, fake: FakeSubsonic = FakeSubsonic(), page: @Composable () -> Unit) {
        val browse: (String) -> ServerBrowse? = { fake.browse() }
        snap(name, settleMs = 2500) {
            CompositionLocalProvider(LocalNavigator provides Navigator(), LocalServerBrowse provides browse) { page() }
        }
    }

    @Test @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    fun serverAlbum() = server("server-album") { ServerAlbumScreen(ServerAlbumRoute("s1", "Slow Signal", "Harbor Static", songId = "t2")) }

    @Test @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    fun serverArtist() = server("server-artist") { ServerArtistScreen(ServerArtistRoute("s1", "Harbor Static", songId = "t2")) }

    /** The server didn't answer: it says so, with Try again. */
    @Test fun serverDown() = server("server-album-down", FakeSubsonic(down = true)) {
        ServerAlbumScreen(ServerAlbumRoute("s1", "Slow Signal", "Harbor Static", songId = "t2"))
    }
}

package com.luke.tracks.library

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.data.SampleData
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.ui.library.ActionSheetPreview
import com.luke.tracks.ui.library.LibraryAlbumPage
import com.luke.tracks.ui.library.LibraryArtistPage
import com.luke.tracks.ui.library.LibraryContent
import com.luke.tracks.ui.library.LibraryTab
import com.luke.tracks.ui.library.LibraryTarget
import com.luke.tracks.ui.library.servers.ServerEditorForm
import com.luke.tracks.ui.library.servers.ServersPageContent
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import org.junit.Test
import uniffi.tracks_engine.songIdentity

/** A library made from the sample history's songs, as if they were files on the phone. */
object FakeLibrary {
    val index: LibraryIndex by lazy {
        val seen = HashSet<String>()
        val tracks = SampleData.plays(days = 120).filter { seen.add(it.title + it.artistName) }.take(160).mapIndexed { i, play ->
            LibraryTrack(
                song = PlayableSong(
                    mediaId = "local:$i",
                    uri = Uri.parse("content://media/external/audio/media/$i"),
                    title = play.title,
                    artistName = play.artistName,
                    albumTitle = play.albumTitle,
                    artworkUri = null,
                    durationMs = play.durationMs,
                    identity = songIdentity(play.title, play.artistName),
                    trackNumber = (i % 11) + 1,
                    year = 2015 + (play.albumTitle.hashCode().mod(10)),
                    genre = listOf("Indie", "Pop", "Electronic", "Folk")[play.artistName.hashCode().mod(4)],
                ),
                source = LibrarySourceKind.Device,
                albumKey = "local:album:" + play.albumTitle,
            )
        }
        LibraryIndex.build(tracks, hasPermission = true)
    }

    val servers = listOf(
        ServerEntry(SubsonicServer("s1", "Navidrome at home", "http://192.168.1.20:4533", "luke", "salt"), ServerStatus.Ready(2140)),
        ServerEntry(SubsonicServer("s2", "Music in the cloud", "https://music.example.com", "luke", "salt"), ServerStatus.Failed("The username or password isn't right.")),
        ServerEntry(SubsonicServer("s3", "Gonic", "https://gonic.example.net", "luke", "salt"), ServerStatus.Syncing),
    )
}

class LibraryScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNavigator provides Navigator()) { content() }
    }

    private fun library(name: String, tab: LibraryTab, snapshot: LibrarySnapshot = FakeLibrary.index.snapshot, query: String? = null) =
        snap(name) {
            Frame {
                LibraryContent(
                    snapshot = snapshot,
                    index = { FakeLibrary.index },
                    servers = FakeLibrary.servers,
                    currentMediaId = FakeLibrary.index.snapshot.songs[2].mediaId,
                    onAllowAccess = {},
                    initialTab = tab,
                    initialQuery = query,
                )
            }
        }

    @Test fun albums() = library("library-albums", LibraryTab.Albums)
    @Test fun songs() = library("library-songs", LibraryTab.Songs)
    @Test fun artists() = library("library-artists", LibraryTab.Artists)
    @Test fun servers() = library("library-servers", LibraryTab.Servers)
    @Test fun search() = library("library-search", LibraryTab.Albums, query = FakeLibrary.index.snapshot.artists.first().name.take(4))
    @Test fun noPermission() = library("library-permission", LibraryTab.Albums, LibrarySnapshot(hasPermission = false))
    @Test fun noMusic() = library("library-empty", LibraryTab.Songs, LibrarySnapshot(hasPermission = true))
    @Test fun loading() = library("library-loading", LibraryTab.Albums, LibrarySnapshot(isLoading = true))

    @Test fun album() = snap("library-album") {
        Frame {
            val album = FakeLibrary.index.snapshot.albums.maxBy { it.songs.size }
            LibraryAlbumPage(album, album.songs.first().mediaId, artistId = "x")
        }
    }

    @Test fun artist() = snap("library-artist") {
        Frame {
            val artist = FakeLibrary.index.snapshot.artists.maxBy { it.albums.size }
            LibraryArtistPage(artist, FakeLibrary.index.songsBy(artist), null)
        }
    }

    @Test fun serversPage() = snap("servers-page") {
        Frame { ServersPageContent(FakeLibrary.servers, onSync = {}) }
    }

    @Test fun serverEditor() = snap("server-editor") {
        Frame {
            Box(Modifier.padding(top = 120.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(top = 28.dp)) {
                        ServerEditorForm(
                            existing = FakeLibrary.servers.first().server,
                            onTest = { _, _, _ -> null },
                            onSave = { _, _, _, _ -> },
                            onCancel = {},
                            initialCheck = "",
                        )
                    }
                }
            }
        }
    }

    @Test fun actionSheet() = snap("library-actions") {
        Frame {
            Box(Modifier.padding(top = 360.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(top = 28.dp)) {
                        ActionSheetPreview(LibraryTarget.Album(FakeLibrary.index.snapshot.albums.maxBy { it.songs.size }))
                    }
                }
            }
        }
    }
}

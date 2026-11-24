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
import com.luke.tracks.ui.library.servers.ServerActions
import com.luke.tracks.ui.library.servers.TrustDialog
import com.luke.tracks.library.trackside.PairingPayload
import com.luke.tracks.library.trackside.TracksideCapabilities
import com.luke.tracks.library.trackside.TracksidePairRequest
import com.luke.tracks.library.trackside.TracksidePairResult
import com.luke.tracks.net.CertificateNeedsTrust
import com.luke.tracks.net.PinKey
import okhttp3.HttpUrl
import com.luke.tracks.sources.FakeSources
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import org.junit.Test
import org.robolectric.annotation.Config
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
        ServerEntry(SubsonicServer("s1", "Navidrome at home", "http://192.168.1.20:4533", "luke"), ServerStatus.Ready(2140)),
        ServerEntry(SubsonicServer("s2", "Music in the cloud", "https://music.example.com", "luke"), ServerStatus.Failed("The username or password isn't right.")),
        ServerEntry(SubsonicServer("s3", "Gonic", "https://gonic.example.net", "luke"), ServerStatus.Syncing),
    )
}

class LibraryScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNavigator provides Navigator()) { content() }
    }

    private fun library(
        name: String,
        tab: LibraryTab,
        snapshot: LibrarySnapshot = FakeLibrary.index.snapshot,
        sources: List<Source> = listOf(FakeSources.device),
        selection: SourceSelection = SourceSelection.All,
    ) = snap(name) {
        Frame {
            LibraryContent(
                snapshot = snapshot,
                index = { FakeLibrary.index },
                sources = sources,
                selection = selection,
                onSelect = {},
                sourceOf = { SourceId.ofMediaId(it) },
                currentMediaId = FakeLibrary.index.snapshot.songs[2].mediaId,
                initialTab = tab,
            )
        }
    }

    @Test fun albums() = library("library-albums", LibraryTab.Albums)
    @Test fun songs() = library("library-songs", LibraryTab.Songs)
    @Test fun artists() = library("library-artists", LibraryTab.Artists)
    @Test fun withSources() = library("library-sources", LibraryTab.Albums, sources = listOf(FakeSources.device, FakeSources.home, FakeSources.spotify))
    @Test fun filtered() = library(
        "library-filtered", LibraryTab.Albums,
        sources = listOf(FakeSources.device, FakeSources.home),
        selection = SourceSelection.Only(FakeSources.home.id),
    )
    @Test fun noSources() = library("library-empty", LibraryTab.Albums, LibrarySnapshot(hasPermission = false), sources = emptyList())
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

    @Test fun serverEditor() = snap("server-editor") {
        EditorFrame {
            ServerEditorForm(
                existing = FakeLibrary.servers.first().server,
                actions = FakeServerActions,
                onDone = {},
                onCancel = {},
                initialInspection = Inspection.Subsonic(acceptsApiKey = false),
                initialCheck = "",
            )
        }
    }

    @Test fun tracksideEditor() = snap("server-editor-trackside") {
        EditorFrame {
            ServerEditorForm(
                existing = null,
                actions = FakeServerActions,
                onDone = {},
                onCancel = {},
                initialInspection = Inspection.Trackside(TracksideCapabilities(product = "trackside", name = "Home")),
            )
        }
    }

    @Test fun trustDialog() = snap("server-trust") {
        Frame {
            TrustDialog(
                refusal = CertificateNeedsTrust(PinKey.of("192.168.1.20", 4747), "06298432e8066b29e2223bcc23aa9504b56ae508fabf3435508869b9c3190e22", emptySet()),
                onTrust = {},
                onDismiss = {},
            )
        }
    }

    @Composable
    private fun EditorFrame(content: @Composable () -> Unit) {
        Frame {
            Box(Modifier.padding(top = 120.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(top = 28.dp)) { content() }
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

    @Test @Config(qualifiers = "w412dp-h3000dp-xxhdpi") fun albumsFull() = library("full-library-albums", LibraryTab.Albums)
    @Test @Config(qualifiers = "w412dp-h3000dp-xxhdpi") fun songsFull() = library("full-library-songs", LibraryTab.Songs)
    @Test @Config(qualifiers = "w412dp-h3000dp-xxhdpi") fun artistsFull() = library("full-library-artists", LibraryTab.Artists)

    @Test @Config(qualifiers = "w412dp-h2200dp-xxhdpi")
    fun albumFull() = snap("full-library-album") {
        Frame {
            val album = FakeLibrary.index.snapshot.albums.maxBy { it.songs.size }
            LibraryAlbumPage(album, album.songs.first().mediaId, artistId = "x")
        }
    }

    @Test @Config(qualifiers = "w412dp-h2400dp-xxhdpi")
    fun artistFull() = snap("full-library-artist") {
        Frame {
            val artist = FakeLibrary.index.snapshot.artists.maxBy { it.albums.size }
            LibraryArtistPage(artist, FakeLibrary.index.songsBy(artist), null)
        }
    }
}

/** Answers the server form without a network. */
object FakeServerActions : ServerActions {
    override suspend fun inspect(url: HttpUrl): Inspection = Inspection.Subsonic(acceptsApiKey = true)
    override fun trustWhileAdding(refusal: CertificateNeedsTrust) {}
    override suspend fun test(url: HttpUrl, username: String, secret: String, auth: SubsonicAuth, existing: SubsonicServer?): String? = null
    override suspend fun save(existing: SubsonicServer?, name: String, url: HttpUrl, username: String, secret: String, auth: SubsonicAuth, others: List<HttpUrl>) {}
    override suspend fun pair(existing: SubsonicServer?, name: String, url: HttpUrl, pairing: ServerLibrary.Pairing) {}
    override suspend fun pair(payload: PairingPayload) {}
    override suspend fun requestApproval(url: HttpUrl): TracksidePairRequest = TracksidePairRequest("p1", "481 207", "secret", "2026-11-24T10:00:00Z")
    override suspend fun awaitApproval(url: HttpUrl, request: TracksidePairRequest): TracksidePairResult? = null
}

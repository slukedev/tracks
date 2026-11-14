package com.luke.tracks.discover

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.luke.tracks.ScreenshotTest
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.library.LibrarySourceKind
import com.luke.tracks.library.LibraryTrack
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.library.SubsonicSong
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.ui.discover.ArtistLinks
import com.luke.tracks.ui.discover.CatalogAlbumPage
import com.luke.tracks.ui.discover.CatalogArtistPage
import com.luke.tracks.ui.discover.LocalPreviewOverride
import com.luke.tracks.ui.discover.Remote
import com.luke.tracks.ui.library.LibraryArtistPage
import com.luke.tracks.ui.library.LibraryContent
import com.luke.tracks.ui.library.LibraryTab
import com.luke.tracks.ui.library.servers.GetMusicPageContent
import com.luke.tracks.ui.library.servers.LidarrPageContent
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.Navigator
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import uniffi.tracks_engine.AlbumOwnership
import uniffi.tracks_engine.CatalogAlbum
import uniffi.tracks_engine.CatalogResults
import uniffi.tracks_engine.CatalogSong
import uniffi.tracks_engine.LidarrMonitor
import uniffi.tracks_engine.LidarrProfile
import uniffi.tracks_engine.LidarrQueueItem
import uniffi.tracks_engine.LidarrRootFolder
import uniffi.tracks_engine.albumOwnership
import uniffi.tracks_engine.catalogParse
import uniffi.tracks_engine.foldedName
import uniffi.tracks_engine.songIdentity

/** Radiohead, some of whose songs are yours: four from OK Computer and one from The Bends. */
object Owned {
    private val songs = listOf(
        "Airbag" to "OK Computer", "Paranoid Android" to "OK Computer", "Karma Police" to "OK Computer",
        "No Surprises" to "OK Computer", "High and Dry" to "The Bends",
    )
    val index: LibraryIndex by lazy {
        LibraryIndex.build(
            songs.mapIndexed { i, (title, album) ->
                LibraryTrack(
                    PlayableSong("local:r$i", Uri.parse("content://media/$i"), title, "Radiohead", album, null, 240_000, songIdentity(title, "Radiohead"), trackNumber = i + 1, year = 1997),
                    LibrarySourceKind.Device,
                    albumKey = "local:album:$album",
                )
            },
            hasPermission = true,
        )
    }
}

/** A [Discovery] over the recorded catalog answers, with what can be got set by each test. */
class FakeDiscovery(
    fetch: FetchSources,
    private val failSearch: Boolean = false,
    private val waitForever: Boolean = false,
) : Discovery {
    /** Without covers, so screenshots never reach the network: stand-in covers show instead. */
    private fun parse(name: String) = catalogParse(Fixtures.ok(name)).let { r ->
        CatalogResults(r.songs.map { it.copy(artworkUrl = null) }, r.albums.map { it.copy(artworkUrl = null) }, r.artists)
    }
    private val songs = parse("itunes_song").songs + parse("itunes_artist_songs").songs + parse("itunes_album_tracks").songs
    private val albums = parse("itunes_artist_albums").albums
    override val sources = MutableStateFlow(fetch)
    override val waiting = MutableStateFlow(setOf(songIdentity("Creep", "Radiohead")))

    override suspend fun search(query: String): CatalogResults {
        if (waitForever) awaitCancellation()
        if (failSearch) error("offline")
        return CatalogResults(songs.distinctBy { it.identity }, albums, parse("itunes_artist").artists)
    }

    override suspend fun artist(name: String, catalogId: ULong?): ArtistCatalog {
        if (waitForever) awaitCancellation()
        return ArtistCatalog(parse("itunes_artist_albums").artists.first(), albums, songs.distinctBy { it.identity })
    }

    override suspend fun album(id: ULong): AlbumCatalog {
        val found = parse("itunes_album_tracks")
        return AlbumCatalog(found.albums.first(), found.songs)
    }

    override suspend fun findAlbum(title: String, artist: String): CatalogAlbum? = albums.firstOrNull { foldedName(it.title) == foldedName(title) }

    override suspend fun suggestions(force: Boolean): Suggestions {
        if (waitForever) awaitCancellation()
        return Suggestions(
            albums = albums.map { SuggestedAlbum(it, holding(it)) }.filter { it.holding.ownership != AlbumOwnership.OWNED },
            songs = songs.filter { owned(it) == null }.distinctBy { it.identity },
            from = listOf("Radiohead", "Björk", "Portishead"),
        )
    }

    override fun owned(song: CatalogSong): PlayableSong? = Owned.index.find(song.identity)

    override fun holding(album: CatalogAlbum): AlbumHolding {
        val have = Owned.index.snapshot.songs.count { foldedName(it.albumTitle.orEmpty()) == foldedName(album.title) }
        return AlbumHolding(albumOwnership(have.toUInt(), album.trackCount), have)
    }

    override suspend fun availability(song: CatalogSong): Availability {
        owned(song)?.let { return Availability.Owned(it) }
        if (song.identity in waiting.value) return Availability.Waiting
        if (!sources.value.canFetch) return Availability.Unavailable
        return if (sources.value.octo.isNotEmpty()) {
            Availability.OnServer(ServerFind("s1", "Octo", SubsonicSong(id = "x", title = song.title, artist = song.artistName, isExternal = true)))
        } else Availability.Requestable
    }

    override suspend fun get(song: CatalogSong) = ""
    override suspend fun getAlbum(album: CatalogAlbum) = ""
    override suspend fun followArtist(name: String): String? = null
}

class DiscoverScreenshots(dark: Boolean) : ScreenshotTest(dark) {
    private val octo = FetchSources(octo = listOf("Octo at home"))

    @Composable
    private fun Frame(discovery: Discovery, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNavigator provides Navigator(), LocalDiscovery provides discovery) { content() }
    }

    private fun search(name: String, discovery: Discovery, query: String = "radiohead") = snap(name, settleMs = 2500) {
        Frame(discovery) {
            LibraryContent(
                snapshot = Owned.index.snapshot,
                index = { Owned.index },
                servers = emptyList(),
                currentMediaId = null,
                onAllowAccess = {},
                initialQuery = query,
            )
        }
    }

    @Test fun searchWithAServerThatGetsMusic() = search("discover-search", FakeDiscovery(octo))
    @Test fun searchWithNothingToGetMusicWith() = search("discover-search-nofetch", FakeDiscovery(FetchSources()))
    @Test fun searchOffline() = search("discover-search-offline", FakeDiscovery(octo, failSearch = true))
    @Test fun searchOnlyBeyondYourMusic() = search("discover-search-catalog-only", FakeDiscovery(octo), query = "creep")

    @Test fun searchPreviewing() = snap("discover-search-preview", settleMs = 2500) {
        val previewing = catalogParse(Fixtures.ok("itunes_song")).songs.first()
        CompositionLocalProvider(LocalPreviewOverride provides PreviewState("song:${previewing.id}", progress = 0.4f)) {
            Frame(FakeDiscovery(octo)) {
                LibraryContent(Owned.index.snapshot, { Owned.index }, emptyList(), null, {}, initialQuery = "creep")
            }
        }
    }

    @Test fun moreFromAnArtist() = snap("discover-more-from-artist", settleMs = 2500) {
        Frame(FakeDiscovery(octo)) {
            androidx.compose.foundation.layout.Column { com.luke.tracks.ui.discover.MoreFromArtist("Radiohead", edge = 20.dp) }
        }
    }

    @Test fun missingFromAnAlbum() = snap("discover-missing-from-album", settleMs = 2500) {
        Frame(FakeDiscovery(octo)) {
            val album = Owned.index.snapshot.albums.first { it.title == "OK Computer" }
            com.luke.tracks.ui.library.LibraryAlbumPage(album, null, artistId = null)
        }
    }

    @Test fun suggestedForYou() = snap("discover-suggested", settleMs = 2500) {
        Frame(FakeDiscovery(octo)) {
            LibraryContent(Owned.index.snapshot, { Owned.index }, emptyList(), null, {}, initialTab = LibraryTab.Albums)
        }
    }

    @Test fun suggestedLoading() = snap("discover-suggested-loading") {
        Frame(FakeDiscovery(octo, waitForever = true)) {
            LibraryContent(Owned.index.snapshot, { Owned.index }, emptyList(), null, {}, initialTab = LibraryTab.Albums)
        }
    }

    @Test fun artistPageInYourMusic() = snap("discover-library-artist", settleMs = 2500) {
        Frame(FakeDiscovery(FetchSources(lidarr = true))) {
            val artist = Owned.index.snapshot.artists.first()
            LibraryArtistPage(artist, Owned.index.songsBy(artist), null)
        }
    }

    @Test fun catalogArtist() = snap("discover-catalog-artist", settleMs = 2500) {
        val discovery = FakeDiscovery(octo)
        Frame(discovery) {
            val page = kotlinx.coroutines.runBlocking { discovery.artist("Radiohead") }
            CatalogArtistPage("Radiohead", Remote.Loaded(page), ArtistLinks(libraryId = "artist:radiohead", historyIdentity = "radiohead"), onRetry = {})
        }
    }

    @Test fun catalogArtistFailed() = snap("discover-catalog-artist-failed") {
        Frame(FakeDiscovery(octo)) { CatalogArtistPage("Radiohead", Remote.Failed, ArtistLinks(), onRetry = {}) }
    }

    @Test fun catalogAlbumPartlyYours() = snap("discover-catalog-album", settleMs = 2500) {
        val discovery = FakeDiscovery(octo)
        Frame(discovery) {
            val album = kotlinx.coroutines.runBlocking { discovery.album(1u) }
            CatalogAlbumPage("OK Computer", "Radiohead", Remote.Loaded(album), onRetry = {})
        }
    }

    @Test fun catalogAlbumWithNothingToGetWith() = snap("discover-catalog-album-nofetch", settleMs = 2500) {
        val discovery = FakeDiscovery(FetchSources())
        Frame(discovery) {
            val album = kotlinx.coroutines.runBlocking { discovery.album(1u) }
            CatalogAlbumPage("OK Computer", "Radiohead", Remote.Loaded(album), onRetry = {})
        }
    }

    private val servers = listOf(
        ServerEntry(SubsonicServer("s1", "Octo at home", "http://192.168.1.20:4533", "luke", "salt"), ServerStatus.Ready(2140)),
        ServerEntry(SubsonicServer("s2", "Gonic", "https://gonic.example.net", "luke", "salt"), ServerStatus.Ready(800)),
        ServerEntry(SubsonicServer("s3", "Music in the cloud", "https://music.example.com", "luke", "salt"), ServerStatus.Failed("The username or password isn't right.")),
    )

    @Test fun getMusicPage() = snap("discover-getmusic") {
        Frame(NoDiscovery) {
            GetMusicPageContent(servers, mapOf("s1" to FetchCapability.Fetches, "s2" to FetchCapability.PlaysOnly), LidarrState.Connected("2.9.6"), onCheck = {})
        }
    }

    @Test fun getMusicPageEmpty() = snap("discover-getmusic-empty") {
        Frame(NoDiscovery) { GetMusicPageContent(emptyList(), emptyMap(), LidarrState.Off, onCheck = {}) }
    }

    @Test fun lidarrNotConnected() = snap("discover-lidarr-connect") {
        Frame(NoDiscovery) {
            LidarrPageContent(LidarrState.Off, null, emptyList(), { _, _ -> null }, { _, _ -> null }, { _, _, _, _, _ -> }, {}, {})
        }
    }

    @Test fun lidarrConnected() = snap("discover-lidarr") {
        val setup = LidarrSetup(
            url = "http://nas.local:8686",
            qualityProfiles = listOf(LidarrProfile(1, "Any"), LidarrProfile(2, "Lossless")),
            metadataProfiles = listOf(LidarrProfile(1, "Standard")),
            rootFolders = listOf(LidarrRootFolder(1, "/music", null)),
            qualityProfileId = 2,
            monitor = LidarrMonitor.FUTURE,
        )
        val queue = listOf(
            LidarrQueueItem(1, "Radiohead - Kid A", "downloading", 0.62, "00:03:20", null, "Kid A", "Radiohead", null),
            LidarrQueueItem(2, "Björk - Post", "warning", 0.0, null, "No files found are eligible for import", "Post", "Björk", null),
        )
        Frame(NoDiscovery) {
            LidarrPageContent(LidarrState.Connected("2.9.6.4552"), setup, queue, { _, _ -> null }, { _, _ -> null }, { _, _, _, _, _ -> }, {}, {})
        }
    }

    @Test fun lidarrUnreachable() = snap("discover-lidarr-failed") {
        Frame(NoDiscovery) {
            LidarrPageContent(
                LidarrState.Failed("Couldn't reach Lidarr. Check the address and your network."),
                LidarrSetup("http://nas.local:8686"), emptyList(), { _, _ -> null }, { _, _ -> null }, { _, _, _, _, _ -> }, {}, {},
            )
        }
    }
}

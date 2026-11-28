package com.luke.tracks.ui.tv.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.graph
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.library.LibrarySnapshot
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.sources.sourceList
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvChip
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSkeletonBlock
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.detail.RowArt
import com.luke.tracks.ui.tv.detail.RowArtPx
import com.luke.tracks.ui.tv.detail.TvPulse
import com.luke.tracks.ui.tv.detail.rememberCurrentMediaId
import com.luke.tracks.ui.tv.detail.rememberPendingPlay
import com.luke.tracks.ui.tv.detail.trackLength
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import com.luke.tracks.ui.tv.theme.TvLayout

enum class TvLibraryTab(val label: String) { Albums("Albums"), Artists("Artists"), Songs("Songs") }

/** Five covers across the content area at 1080p, with the shelf spacing between them. */
private const val Columns = 5
private val GridCard: Dp = 144.dp

/** Library on TV: your Trackside library's albums, artists and songs. */
@Composable
fun TvLibraryScreen() {
    val graph = LocalContext.current.graph
    val snapshot by graph.library.snapshot.collectAsStateWithLifecycle()
    val sources by sourceList()
    val navigator = LocalTvNavigator.current
    TvLibraryContent(
        snapshot = snapshot,
        linked = sources.isNotEmpty() || snapshot.songs.isNotEmpty(),
        // Settings > Trackside hosts the link flow while there's no server.
        onLink = { navigator.open(SettingsPageRoute("trackside")) },
    )
}

/** The page from plain values, so every state renders the same in screenshots. */
@Composable
fun TvLibraryContent(snapshot: LibrarySnapshot, linked: Boolean, onLink: () -> Unit, initialTab: TvLibraryTab = TvLibraryTab.Albums) {
    val memory = rememberFocusMemory()
    when {
        !linked -> {
            val link = remember { FocusRequester() }
            memory.ClaimFocus(link)
            TvEmptyState("Link Trackside", icon = Icons.Rounded.LibraryMusic, actionLabel = "Link", onAction = onLink, focusRequester = link)
        }
        snapshot.songs.isEmpty() && snapshot.isLoading -> {
            memory.ClaimFocus(null)
            LibrarySkeleton()
        }
        snapshot.songs.isEmpty() -> {
            memory.ClaimFocus(null)
            TvEmptyState("No Music Yet", icon = Icons.Rounded.LibraryMusic)
        }
        else -> Tabs(snapshot, memory, initialTab)
    }
}

@Composable
private fun Tabs(snapshot: LibrarySnapshot, memory: FocusMemory, initialTab: TvLibraryTab) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val firstKey = when (tab) {
        TvLibraryTab.Albums -> snapshot.albums.firstOrNull()?.let { "album:${it.id}" }
        TvLibraryTab.Artists -> snapshot.artists.firstOrNull()?.let { "artist:${it.id}" }
        TvLibraryTab.Songs -> snapshot.songs.firstOrNull()?.let { "song:${it.mediaId}" }
    }
    memory.ClaimFocus(memory.requester(firstKey ?: "tab:${tab.name}"))
    Column(Modifier.fillMaxSize().padding(top = TvLayout.SafeTop)) {
        Row(
            Modifier.padding(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = 4.dp).focusRestorer(memory.requester("tab:${tab.name}")),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvLibraryTab.entries.forEach { option ->
                TvChip(option.label, selected = option == tab, onClick = { tab = option }, modifier = Modifier.rememberFocus(memory, "tab:${option.name}"))
            }
        }
        when (tab) {
            TvLibraryTab.Albums -> AlbumGrid(snapshot.albums, memory)
            TvLibraryTab.Artists -> ArtistGrid(snapshot.artists, memory)
            TvLibraryTab.Songs -> SongGrid(snapshot.songs, memory)
        }
    }
}

private val GridPadding = PaddingValues(start = TvLayout.ContentStart, end = TvLayout.SafeEnd, top = 20.dp, bottom = TvLayout.SafeBottom + 16.dp)

@Composable
private fun AlbumGrid(albums: List<LibraryAlbum>, memory: FocusMemory) {
    val navigator = LocalTvNavigator.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(Columns),
        contentPadding = GridPadding,
        horizontalArrangement = Arrangement.spacedBy(TvLayout.ShelfSpacing),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        items(albums, key = { "album:${it.id}" }, contentType = { "album" }) { album ->
            TvCoverCard(
                album.artworkUri, album.title + album.artistName, album.title,
                onClick = { navigator.open(LibraryAlbumRoute(album.id)) },
                subtitle = album.artistName, width = GridCard,
                modifier = Modifier.rememberFocus(memory, "album:${album.id}"),
            )
        }
    }
}

@Composable
private fun ArtistGrid(artists: List<LibraryArtist>, memory: FocusMemory) {
    val navigator = LocalTvNavigator.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(Columns),
        contentPadding = GridPadding,
        horizontalArrangement = Arrangement.spacedBy(TvLayout.ShelfSpacing),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        items(artists, key = { "artist:${it.id}" }, contentType = { "artist" }) { artist ->
            TvCoverCard(
                artist.albums.firstNotNullOfOrNull { it.artworkUri }, artist.name, artist.name,
                onClick = { navigator.open(LibraryArtistRoute(artist.id)) },
                width = GridCard, circle = true,
                modifier = Modifier.rememberFocus(memory, "artist:${artist.id}"),
            )
        }
    }
}

/** Songs two to a row, so a long library reads at a glance and pages quickly. */
@Composable
private fun SongGrid(songs: List<PlayableSong>, memory: FocusMemory) {
    val pending = rememberPendingPlay()
    val playing by rememberCurrentMediaId()
    val origin = remember { QueueOrigin("Library", QueueOrigin.Kind.Library) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = TvLayout.ContentStart - 16.dp, end = TvLayout.SafeEnd - 16.dp, top = 16.dp, bottom = TvLayout.SafeBottom + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        items(songs.size, key = { "song:${songs[it].mediaId}" }, contentType = { "song" }) { i ->
            val song = songs[i]
            TvListRow(
                onClick = { pending.play("song:${song.mediaId}", songs, i, origin) },
                headline = song.title.ifBlank { "Untitled" },
                supporting = song.artistName,
                dense = true,
                selected = song.mediaId == playing,
                modifier = Modifier.rememberFocus(memory, "song:${song.mediaId}"),
                leading = { TvArtwork(song.artworkUri, (song.albumTitle ?: song.title) + song.artistName, Modifier.size(RowArt), sizePx = RowArtPx, showInitials = false) },
                trailing = trackLength(song.durationMs)?.let { length ->
                    { Text(length, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
            )
        }
    }
}

/** The tabs and a first screen of covers, at their real sizes, while the library first loads. */
@Composable
private fun LibrarySkeleton() {
    TvPulse(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = TvLayout.SafeTop + 4.dp, start = TvLayout.ContentStart, end = TvLayout.SafeEnd)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(92.dp, 92.dp, 84.dp).forEach { TvSkeletonBlock(Modifier.width(it).height(40.dp), CircleShape) }
            }
            Spacer(Modifier.height(20.dp))
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(TvLayout.ShelfSpacing), modifier = Modifier.padding(bottom = 24.dp)) {
                    repeat(Columns) {
                        Column(Modifier.width(GridCard)) {
                            TvSkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp))
                            Spacer(Modifier.height(16.dp))
                            TvSkeletonBlock(Modifier.fillMaxWidth(0.72f).height(12.dp), RoundedCornerShape(4.dp))
                            Spacer(Modifier.height(10.dp))
                            TvSkeletonBlock(Modifier.fillMaxWidth(0.48f).height(10.dp), RoundedCornerShape(4.dp))
                        }
                    }
                }
            }
        }
    }
}

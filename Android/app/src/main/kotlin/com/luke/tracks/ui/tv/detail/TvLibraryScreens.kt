package com.luke.tracks.ui.tv.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.insights.Format
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.tv.components.ClaimFocus
import com.luke.tracks.ui.tv.components.FocusMemory
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvCoverCard
import com.luke.tracks.ui.tv.components.TvEmptyState
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvSectionTitle
import com.luke.tracks.ui.tv.components.TvShelf
import com.luke.tracks.ui.tv.components.rememberFocus
import com.luke.tracks.ui.tv.components.rememberFocusMemory
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import uniffi.tracks_engine.foldedName

/*
 * Pages for what's in your library: an album with its tracks in order, and an artist with
 * their albums and songs.
 */

@Composable
fun TvLibraryAlbumScreen(albumId: String) {
    val memory = rememberFocusMemory()
    val index by rememberLibraryIndex()
    val album = index.album(albumId)
    when {
        album != null -> LibraryAlbumPage(album, index.artistIdOf(album.artistName), memory)
        index.snapshot.isLoading -> {
            memory.ClaimFocus(null)
            TvDetailSkeleton()
        }
        else -> Missing(memory, "Album Not Found")
    }
}

@Composable
fun TvLibraryArtistScreen(artistId: String) {
    val memory = rememberFocusMemory()
    val index by rememberLibraryIndex()
    val artist = index.artist(artistId)
    when {
        artist != null -> LibraryArtistPage(artist, remember(artist, index) { index.songsBy(artist) }, memory)
        index.snapshot.isLoading -> {
            memory.ClaimFocus(null)
            TvDetailSkeleton(circle = true)
        }
        else -> Missing(memory, "Artist Not Found")
    }
}

@Composable
private fun Missing(memory: FocusMemory, title: String) {
    val back = remember { FocusRequester() }
    val navigator = LocalTvNavigator.current
    memory.ClaimFocus(back)
    TvEmptyState(title, actionLabel = "Back", onAction = { navigator.back(railFocused = false) }, focusRequester = back)
}

@Composable
private fun LibraryAlbumPage(album: LibraryAlbum, artistId: String?, memory: FocusMemory) {
    val navigator = LocalTvNavigator.current
    val origin = remember(album) { QueueOrigin(album.title, QueueOrigin.Kind.Album) }
    val length = album.songs.sumOf { it.durationMs ?: 0 }
    SongsPage(
        memory = memory,
        art = { TvArtwork(album.artworkUri, album.title + album.artistName, Modifier.fillMaxWidth(), sizePx = TvArtwork.HeroPx, shape = RoundedCornerShape(16.dp)) },
        title = album.title,
        subtitle = album.artistName,
        facts = listOfNotNull(
            album.year?.toString(),
            Format.songs(album.songs.size.toLong()),
            length.takeIf { it > 0 }?.let(Format::compactDuration),
        ).joinToString(" · "),
        songs = album.songs,
        origin = origin,
        numbered = true,
        extra = listOfNotNull(artistId?.let { id -> Triple("Artist", Icons.Rounded.Person) { navigator.open(LibraryArtistRoute(id)) } }),
    )
}

@Composable
private fun LibraryArtistPage(artist: LibraryArtist, songs: List<PlayableSong>, memory: FocusMemory) {
    val navigator = LocalTvNavigator.current
    val origin = remember(artist) { QueueOrigin(artist.name, QueueOrigin.Kind.Artist) }
    val albumCount = artist.albums.size
    SongsPage(
        memory = memory,
        art = { TvArtwork(null, artist.name, Modifier.fillMaxWidth(), sizePx = TvArtwork.HeroPx, shape = CircleShape, showInitials = false) },
        title = artist.name,
        subtitle = null,
        facts = listOfNotNull(
            albumCount.takeIf { it > 0 }?.let { if (it == 1) "1 album" else "${Format.number(it.toLong())} albums" },
            Format.songs(songs.size.toLong()),
        ).joinToString(" · "),
        songs = songs,
        origin = origin,
        numbered = false,
        extra = listOf(Triple("Insights", Icons.Rounded.Insights) { navigator.open(ArtistRoute(foldedName(artist.name), artist.name)) }),
        before = {
            if (artist.albums.isNotEmpty()) item(key = "albums") {
                TvShelf(
                    "Albums", artist.albums, key = { "album:${it.id}" }, focusMemory = memory,
                    contentPadding = PaddingValues(start = RowInset), modifier = Modifier.padding(bottom = 8.dp),
                ) { album ->
                    TvCoverCard(
                        album.artworkUri, album.title + album.artistName, album.title,
                        onClick = { navigator.open(LibraryAlbumRoute(album.id)) },
                        subtitle = album.year?.toString(), width = 160.dp,
                    )
                }
            }
        },
        songsTitle = "Songs",
    )
}

@Composable
private fun SongsPage(
    memory: FocusMemory,
    art: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    facts: String,
    songs: List<PlayableSong>,
    origin: QueueOrigin,
    numbered: Boolean,
    extra: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>,
    before: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
    songsTitle: String? = null,
) {
    val pending = rememberPendingPlay()
    val playing by rememberCurrentMediaId()
    memory.ClaimFocus(memory.requester(if (songs.isNotEmpty()) "play" else "extra:0"))
    TvDetailLayout(art = art) {
        item(key = "heading") { TvDetailHeading(title, subtitle, facts) }
        item(key = "actions") {
            Row(Modifier.padding(start = RowInset, top = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (songs.isNotEmpty()) {
                    TvButton(
                        "Play", { pending.play("play", songs, 0, origin) },
                        Modifier.rememberFocus(memory, "play"), icon = Icons.Rounded.PlayArrow, style = TvButtonStyle.Primary, pending = pending.isPending("play"),
                    )
                    if (songs.size > 1) TvButton(
                        "Shuffle", { pending.play("shuffle", songs, 0, origin, shuffle = true) },
                        Modifier.rememberFocus(memory, "shuffle"), icon = Icons.Rounded.Shuffle, pending = pending.isPending("shuffle"),
                    )
                }
                extra.forEachIndexed { i, (label, icon, go) -> TvButton(label, go, Modifier.rememberFocus(memory, "extra:$i"), icon = icon) }
            }
        }
        before()
        if (songsTitle != null && songs.isNotEmpty()) item(key = "songsTitle") { TvSectionTitle(songsTitle, Modifier.padding(start = RowInset, top = 8.dp, bottom = 4.dp)) }
        itemsIndexed(songs, key = { _, s -> "song:${s.mediaId}" }) { i, song ->
            TvListRow(
                onClick = { pending.play("song:${song.mediaId}", songs, i, origin) },
                headline = song.title.ifBlank { "Untitled" },
                supporting = if (numbered) song.artistName.takeIf { it != subtitle } else song.albumTitle,
                dense = true,
                selected = song.mediaId == playing,
                modifier = Modifier.rememberFocus(memory, "song:${song.mediaId}"),
                leading = if (numbered) {
                    {
                        Text(
                            "${song.trackNumber ?: i + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.size(28.dp, 24.dp),
                        )
                    }
                } else {
                    { TvArtwork(song.artworkUri, (song.albumTitle ?: song.title) + song.artistName, Modifier.size(RowArt), sizePx = RowArtPx, showInitials = false) }
                },
                trailing = trackLength(song.durationMs)?.let { length ->
                    { Text(length, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
            )
        }
    }
}

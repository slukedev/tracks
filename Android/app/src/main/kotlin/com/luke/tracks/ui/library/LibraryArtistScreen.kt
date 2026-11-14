package com.luke.tracks.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.sharedElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An artist in Your Music, by library id. */
@Composable
fun LibraryArtistScreen(artistId: String) {
    val graph = LocalContext.current.graph
    val index by remember { (graph.library as? CombinedLibrary)?.index ?: MutableStateFlow(LibraryIndex.Empty) }.collectAsStateWithLifecycle()
    val current by remember { graph.player.state.map { it.current?.mediaId } }.collectAsStateWithLifecycle(null)
    val artist = index.artist(artistId)
    if (artist == null) {
        MissingPage(if (index.snapshot.isLoading) null else "This artist isn't in Your Music anymore")
        return
    }
    val songs = remember(artist, index) { index.songsBy(artist) }
    LibraryArtistPage(artist, songs, current)
}

@Composable
fun LibraryArtistPage(artist: LibraryArtist, songs: List<PlayableSong>, currentMediaId: String?) {
    val navigator = LocalNavigator.current
    val player = LocalContext.current.graph.player
    val origin = remember(artist.id) { QueueOrigin(artist.name, QueueOrigin.Kind.Artist) }
    WithLibraryActions {
        val actions = LocalLibraryActions.current
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                LibraryPageBar(
                    title = artist.name,
                    list = list,
                    moreLabel = "More for ${artist.name}",
                    onBack = { navigator.back() },
                    onMore = { actions.open(LibraryTarget.Artist(artist, songs)) },
                )
            },
        ) { padding ->
            val tint = MaterialTheme.colorScheme.tertiaryContainer
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint.copy(alpha = 0.5f), 0.4f to MaterialTheme.colorScheme.surface)),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = MiniPlayerClearance),
            ) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ArtistAvatar(artist, 208.dp, Modifier.sharedElement(artistAvatarKey(artist.id)))
                        Spacer(Modifier.height(24.dp))
                        Text(artist.name, style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        PlayShuffleButtons(
                            onPlay = { player.play(songs, origin = origin) },
                            onShuffle = { player.play(songs, origin = origin, shuffle = true) },
                        )
                    }
                }
                if (artist.albums.isNotEmpty()) {
                    item(key = "albums-h") { SectionHeader("Albums", Modifier.padding(top = 8.dp)) }
                    item(key = "albums") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                            items(artist.albums, key = { it.id }) { album ->
                                AlbumCell(
                                    album = album,
                                    onClick = { navigator.open(LibraryAlbumRoute(album.id)) },
                                    onLongClick = { actions.open(LibraryTarget.Album(album)) },
                                    showArtist = false,
                                    modifier = Modifier.width(160.dp).animateItem(),
                                )
                            }
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    item(key = "songs-h") { SectionHeader("Songs") }
                    itemsIndexed(songs, key = { _, song -> song.mediaId }) { i, song ->
                        SongRow(
                            song = song,
                            isCurrent = song.mediaId == currentMediaId,
                            onClick = { player.play(songs, i, origin) },
                            onMore = { actions.open(LibraryTarget.Song(song)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                item(key = "more") { com.luke.tracks.ui.discover.MoreFromArtist(artist.name, edge = 20.dp) }
            }
        }
    }
}

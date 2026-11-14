package com.luke.tracks.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.sharedElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An album in Your Music, by its library id. */
@Composable
fun LibraryAlbumScreen(albumId: String) {
    val context = LocalContext.current
    val graph = context.graph
    val index by remember { (graph.library as? CombinedLibrary)?.index ?: MutableStateFlow(LibraryIndex.Empty) }.collectAsStateWithLifecycle()
    val current by remember { graph.player.state.map { it.current?.mediaId } }.collectAsStateWithLifecycle(null)
    val album = index.album(albumId)
    if (album == null) {
        MissingPage(if (index.snapshot.isLoading) null else "This album isn't in Your Music anymore")
        return
    }
    LibraryAlbumPage(album, current, artistId = index.artistIdOf(album.artistName))
}

@Composable
fun LibraryAlbumPage(album: LibraryAlbum, currentMediaId: String?, artistId: String?) {
    val navigator = LocalNavigator.current
    val player = LocalContext.current.graph.player
    val origin = remember(album.id) { QueueOrigin(album.title, QueueOrigin.Kind.Album) }
    WithLibraryActions {
        val actions = LocalLibraryActions.current
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = { IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                    actions = {
                        IconButton(onClick = { actions.open(LibraryTarget.Album(album)) }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${album.title}") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            },
        ) { padding ->
            val tint = MaterialTheme.colorScheme.primaryContainer
            LazyColumn(
                Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint.copy(alpha = 0.55f), 0.45f to MaterialTheme.colorScheme.surface)),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = MiniPlayerClearance),
            ) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(
                            url = album.artworkUri,
                            seed = album.title + album.artistName,
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier.size(248.dp).sharedElement(albumCoverKey(album.id)),
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(album.title, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            album.artistName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .then(if (artistId != null) Modifier.clickable { navigator.open(LibraryArtistRoute(artistId)) } else Modifier)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                        val details = listOfNotNull(album.year?.toString(), album.songs.firstNotNullOfOrNull { it.genre }).joinToString(" · ")
                        if (details.isNotEmpty()) {
                            Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(24.dp))
                        PlayShuffleButtons(
                            onPlay = { player.play(album.songs, origin = origin) },
                            onShuffle = { player.play(album.songs, origin = origin, shuffle = true) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                itemsIndexed(album.songs, key = { _, song -> song.mediaId }) { i, song ->
                    SongRow(
                        song = song,
                        isCurrent = song.mediaId == currentMediaId,
                        onClick = { player.play(album.songs, i, origin) },
                        onMore = { actions.open(LibraryTarget.Song(song)) },
                        trackNumber = song.trackNumber ?: (i + 1),
                        showAlbum = false,
                        albumArtist = album.artistName,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** For a page whose album or artist is still loading, or has gone. */
@Composable
fun MissingPage(message: String?) {
    val navigator = LocalNavigator.current
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(title = {}, navigationIcon = { IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (message == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.LoadingIndicator() }
            else EmptyState(Icons.Rounded.Album, message, "It may have been moved or removed.")
        }
    }
}

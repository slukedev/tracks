package com.luke.tracks.ui.library.servers

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.LibraryAlbum
import com.luke.tracks.library.LibraryArtist
import com.luke.tracks.library.ServerBrowse
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.SubsonicError
import com.luke.tracks.ui.library.EmptyState
import com.luke.tracks.ui.library.LibraryAlbumPage
import com.luke.tracks.ui.library.LibraryArtistPage
import com.luke.tracks.ui.library.MissingPage
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.ServerAlbumRoute
import com.luke.tracks.ui.navigation.ServerArtistRoute
import com.luke.tracks.ui.play.components.rememberLibraryIndex
import kotlinx.coroutines.flow.map

/** Finds the server a page looks things up on; screenshots provide their own. */
val LocalServerBrowse = staticCompositionLocalOf<((String) -> ServerBrowse?)?> { null }

@Composable
private fun rememberBrowse(serverId: String): ServerBrowse? {
    val provided = LocalServerBrowse.current
    val context = LocalContext.current
    return remember(serverId, provided) {
        provided?.invoke(serverId) ?: (context.graph.library as? CombinedLibrary)?.servers?.browse(serverId)
    }
}

@Composable
private fun rememberCurrentMediaId(): String? {
    val player = LocalContext.current.graph.player
    val current by remember(player) { player.state.map { it.current?.mediaId } }.collectAsStateWithLifecycle(null)
    return current
}

/**
 * Asks [browse] with [load], again on Try again: null while it's asking, then what it found or
 * why it couldn't.
 */
@Composable
private fun <T> rememberLookup(browse: ServerBrowse?, key: Any, load: suspend ServerBrowse.() -> T): Pair<Result<T>?, () -> Unit> {
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Result<T>?>(null, browse, key, attempt) {
        value = null
        value = browse?.let { runCatching { it.load() } } ?: Result.failure(SubsonicError.NotFound)
    }
    return result to { attempt++ }
}

/** An album on one of your servers, opened from a song it found that you don't have. */
@Composable
fun ServerAlbumScreen(route: ServerAlbumRoute) {
    val browse = rememberBrowse(route.serverId)
    val index = rememberLibraryIndex()
    val current = rememberCurrentMediaId()
    val (result, retry) = rememberLookup(browse, route) { album(route.title, route.artistName, route.albumId, route.songId) }
    val found = result?.getOrNull()
    when {
        result == null -> MissingPage(null)
        found == null -> ServerProblem(browse, result.exceptionOrNull(), Icons.Rounded.Album, "${route.title} isn't on this server", retry)
        else -> LibraryAlbumPage(
            album = found.album,
            currentMediaId = current,
            artistId = null,
            artistRoute = index.artistIdOf(found.album.artistName)?.let(::LibraryArtistRoute)
                ?: ServerArtistRoute(route.serverId, found.album.artistName, artistId = found.artistId),
        )
    }
}

/** An artist on one of your servers: their top songs and albums there. */
@Composable
fun ServerArtistScreen(route: ServerArtistRoute) {
    val browse = rememberBrowse(route.serverId)
    val index = rememberLibraryIndex()
    val current = rememberCurrentMediaId()
    val (result, retry) = rememberLookup(browse, route) { artist(route.name, route.artistId, route.songId) }
    val found = result?.getOrNull()
    when {
        result == null -> MissingPage(null)
        found == null -> ServerProblem(browse, result.exceptionOrNull(), Icons.Rounded.Person, "${route.name} isn't on this server", retry)
        found.isEmpty -> ServerMessage(Icons.Rounded.Person, "Nothing by ${route.name} yet", "${browse?.server?.name ?: "Your server"} doesn't have any of their albums or songs.")
        else -> {
            val ids = remember(found) { found.albums.associate { ServerLibrary.albumId(route.serverId, it.id) to it.id } }
            val artist = remember(found) {
                LibraryArtist(
                    id = "server:${route.serverId}:artist:${route.name}",
                    name = found.name,
                    albums = found.albums.map { LibraryAlbum(ServerLibrary.albumId(route.serverId, it.id), it.title, it.artistName, it.coverUrl?.let(Uri::parse), it.year, emptyList()) },
                    songCount = found.songs.size,
                )
            }
            LibraryArtistPage(
                artist = artist,
                songs = found.songs,
                currentMediaId = current,
                songsTitle = "Top songs",
                albumRoute = { album ->
                    if (index.album(album.id) != null) LibraryAlbumRoute(album.id)
                    else ServerAlbumRoute(route.serverId, album.title, album.artistName, albumId = ids[album.id])
                },
            )
        }
    }
}

/** Why a server page has nothing: the server isn't answering, or doesn't have it. */
@Composable
private fun ServerProblem(browse: ServerBrowse?, error: Throwable?, icon: ImageVector, notFound: String, retry: () -> Unit) {
    when {
        browse == null -> ServerMessage(Icons.Rounded.CloudOff, "This server isn't set up anymore", "Add it again in Settings, Servers.")
        error is SubsonicError.NotFound -> ServerMessage(icon, notFound, "It may have been moved or removed.")
        else -> ServerMessage(Icons.Rounded.CloudOff, "Can't reach ${browse.server.name}", "Check that it's on, then try again.") {
            FilledTonalButton(onClick = retry) { Text("Try again") }
        }
    }
}

@Composable
private fun ServerMessage(icon: ImageVector, title: String, body: String, action: @Composable () -> Unit = {}) {
    val navigator = LocalNavigator.current
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(title = {}, navigationIcon = { IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { EmptyState(icon, title, body, actions = action) }
    }
}

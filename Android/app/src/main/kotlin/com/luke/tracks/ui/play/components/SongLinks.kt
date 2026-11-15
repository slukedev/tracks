package com.luke.tracks.ui.play.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.discover.ServerSong
import com.luke.tracks.graph
import com.luke.tracks.library.LibraryIndex
import com.luke.tracks.ui.insights.Identities
import com.luke.tracks.ui.navigation.AlbumRoute
import com.luke.tracks.ui.navigation.ArtistRoute
import com.luke.tracks.ui.navigation.LibraryAlbumRoute
import com.luke.tracks.ui.navigation.LibraryArtistRoute
import com.luke.tracks.ui.navigation.Navigator
import com.luke.tracks.ui.navigation.Route
import com.luke.tracks.ui.navigation.ServerAlbumRoute
import com.luke.tracks.ui.navigation.ServerArtistRoute

/** Where Go to album and Go to artist lead. Null leaves one out. */
@Immutable
data class GoTo(val album: Route? = null, val artist: Route? = null)

/**
 * Where a song leads: your music's album and artist when the song is yours, its server's
 * when a server found it and you don't have it, and your history's otherwise.
 */
fun songLinks(song: SongRef, index: LibraryIndex, found: ServerSong? = null): GoTo {
    index.find(song.identity)?.let { owned ->
        return GoTo(
            album = index.albumIdOf(owned)?.let(::LibraryAlbumRoute),
            artist = index.artistIdOf(owned.artistName)?.let(::LibraryArtistRoute) ?: historyArtist(song),
        )
    }
    if (found != null) {
        val s = found.song
        return GoTo(
            album = s.album?.takeIf { it.isNotBlank() }?.let { ServerAlbumRoute(found.serverId, it, s.artist, songId = s.id) },
            artist = ServerArtistRoute(found.serverId, s.artist, songId = s.id),
        )
    }
    return GoTo(
        album = Identities.album(song.albumTitle, song.artistName, song.title)?.let { AlbumRoute(it, song.albumTitle!!, song.artistName) },
        artist = historyArtist(song),
    )
}

private fun historyArtist(song: SongRef): Route =
    Identities.leadArtist(song.artistName, song.title).let { ArtistRoute(it.identity, it.name) }

/** An artist's page: yours in Your Music when you have them, your history's otherwise. */
fun artistLink(name: String, identity: String, index: LibraryIndex): Route =
    index.artistIdOf(name)?.let(::LibraryArtistRoute) ?: ArtistRoute(identity, name)

/** The library's lookups, kept current. */
@Composable
fun rememberLibraryIndex(): LibraryIndex {
    val index by LocalContext.current.graph.library.index.collectAsStateWithLifecycle()
    return index
}

/**
 * Go to album and Go to artist as TalkBack actions, for something whose menu opens on a press
 * and hold, so they're a swipe away without opening it.
 */
fun Modifier.goToActions(links: GoTo, navigator: Navigator): Modifier = semantics {
    customActions = listOfNotNull(
        links.album?.let { CustomAccessibilityAction("Go to album") { navigator.open(it); true } },
        links.artist?.let { CustomAccessibilityAction("Go to artist") { navigator.open(it); true } },
    )
}

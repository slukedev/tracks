package com.luke.tracks.library

import android.net.Uri
import com.luke.tracks.net.ServerLinks
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import uniffi.tracks_engine.foldedName

/** An album as a server has it, with who it's by there. */
data class ServerAlbumPage(val album: LibraryAlbum, val artistId: String?)

/** An album on a server artist's page, by the server's own id. */
data class ServerArtistAlbum(val id: String, val title: String, val artistName: String, val year: Int?, val coverUrl: String?)

/** An artist as a server has them: their albums, newest first, and their top songs. */
data class ServerArtistPage(val name: String, val albums: List<ServerArtistAlbum>, val songs: List<PlayableSong>) {
    val isEmpty: Boolean get() = albums.isEmpty() && songs.isEmpty()
}

/**
 * Albums and artists looked up on one server, for pages of ones that aren't in your music.
 * A song the server found leads there: by its album's and artist's ids where the server
 * gives them, and by name where it doesn't (as for a song Octo found elsewhere).
 */
class ServerBrowse(val server: SubsonicServer, private val client: MusicServerClient) {
    suspend fun album(title: String, artist: String, albumId: String? = null, songId: String? = null): ServerAlbumPage {
        val id = albumId ?: songId?.let { idsOf(it)?.albumId } ?: findAlbum(title, artist) ?: throw SubsonicError.NotFound
        val (found, songs) = client.album(id)
        val tracks = ServerLibrary.tracks(server, songs)
            .sortedWith(compareBy({ it.disc ?: 1 }, { it.song.trackNumber ?: Int.MAX_VALUE }))
            .map { it.song }
        val album = LibraryAlbum(
            id = ServerLibrary.albumId(server.id, id),
            title = found.name.ifBlank { title },
            artistName = found.artist?.takeIf { it.isNotBlank() } ?: tracks.firstOrNull()?.artistName ?: artist,
            artworkUri = found.coverArt?.let(::cover) ?: tracks.firstNotNullOfOrNull { it.artworkUri },
            year = found.year,
            songs = tracks,
        )
        return ServerAlbumPage(album, found.artistId)
    }

    suspend fun artist(name: String, artistId: String? = null, songId: String? = null): ServerArtistPage = coroutineScope {
        val top = async { runCatching { client.topSongs(name, 20) }.getOrDefault(emptyList()) }
        val id = artistId ?: songId?.let { idsOf(it)?.artistId } ?: findArtist(name)
        val folded = foldedName(name)
        // A server like Octo has no albums on the page of an artist it found elsewhere, so
        // those come from searching it for the name.
        val albums = id?.let { runCatching { client.artist(it).second }.getOrNull() }.orEmpty().ifEmpty {
            client.search(name, artists = 0, albums = 30, songs = 0).albums
                .filter { (id != null && it.artistId == id) || foldedName(it.artist.orEmpty()) == folded }
        }
        ServerArtistPage(
            name = name,
            albums = albums.sortedByDescending { it.year ?: 0 }.map {
                ServerArtistAlbum(it.id, it.name, it.artist ?: name, it.year, it.coverArt?.let { cover -> cover(cover).toString() })
            },
            songs = ServerLibrary.tracks(server, top.await()).map { it.song },
        )
    }

    private suspend fun idsOf(songId: String): SubsonicSong? = runCatching { client.song(songId) }.getOrNull()

    private suspend fun findAlbum(title: String, artist: String): String? {
        val (name, by) = foldedName(title) to foldedName(artist)
        val albums = client.search(title, artists = 0, albums = 20, songs = 0).albums.filter { foldedName(it.name) == name }
        return (albums.firstOrNull { foldedName(it.artist.orEmpty()) == by } ?: albums.firstOrNull())?.id
    }

    private suspend fun findArtist(name: String): String? {
        val folded = foldedName(name)
        return client.search(name, artists = 10, albums = 0, songs = 0).artists.firstOrNull { foldedName(it.name) == folded }?.id
    }

    private fun cover(id: String): Uri = ServerLinks.cover(server.id, id)
}

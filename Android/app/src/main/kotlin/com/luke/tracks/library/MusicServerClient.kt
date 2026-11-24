package com.luke.tracks.library

import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError

/**
 * What Tracks asks of any music server, in the Subsonic shapes the rest of the app reads:
 * a Subsonic server answers directly, Trackside through [TracksideMusicServer].
 */
interface MusicServerClient {
    /** Checks the address and sign-in. */
    suspend fun ping()
    /** What the server says it is: "navidrome", "octo", "trackside", or null. */
    suspend fun serverType(): String?
    suspend fun allSongs(limit: Int = 50_000, pageSize: Int = 500): List<SubsonicSong>
    suspend fun album(id: String): Pair<SubsonicAlbum, List<SubsonicSong>>
    suspend fun artist(id: String): Pair<SubsonicArtist, List<SubsonicAlbum>>
    suspend fun song(id: String): SubsonicSong
    /** An artist's most popular songs, by name. */
    suspend fun topSongs(artist: String, count: Int = 20): List<SubsonicSong>
    suspend fun albums(type: SubsonicClient.AlbumListType, size: Int = 30, offset: Int = 0): List<SubsonicAlbum>
    suspend fun search(query: String, artists: Int = 10, albums: Int = 20, songs: Int = 40, songOffset: Int = 0): SubsonicSearchResult
    suspend fun similarSongs(songId: String, count: Int = 15): List<SubsonicSong>
    suspend fun randomSongs(count: Int = 40): List<SubsonicSong>
    suspend fun star(songId: String)
    suspend fun unstar(songId: String)
    /**
     * The server's own picks for you, songs like the ones you play, or null when it doesn't
     * make any (Subsonic, or a Trackside server from before it did).
     */
    suspend fun picks(count: Int = 40): List<SubsonicSong>? = null
}

/** A Trackside server, asked as any music server is. */
class TracksideMusicServer(val client: TracksideClient) : MusicServerClient {
    override suspend fun ping() {
        client.me()
    }

    override suspend fun serverType() = "trackside"

    override suspend fun allSongs(limit: Int, pageSize: Int): List<SubsonicSong> {
        val songs = mutableListOf<SubsonicSong>()
        var cursor: String? = null
        while (songs.size < limit) {
            val page = client.sync(cursor, pageSize)
            songs += page.songs.orEmpty().filter { it.missing != true }.map { it.subsonic }
            if (page.complete == true) break
            cursor = page.nextCursor ?: break
        }
        return songs
    }

    override suspend fun album(id: String): Pair<SubsonicAlbum, List<SubsonicSong>> {
        val page = client.albumPage(id)
        return page.album.subsonic to page.songs.map { it.subsonic }
    }

    override suspend fun artist(id: String): Pair<SubsonicArtist, List<SubsonicAlbum>> {
        val page = client.artistPage(id)
        return page.artist.subsonic to page.albums.orEmpty().map { it.subsonic }
    }

    override suspend fun song(id: String) = client.song(id).subsonic

    override suspend fun topSongs(artist: String, count: Int): List<SubsonicSong> {
        val wanted = artist.trim().lowercase()
        val found = client.searchLibrary(artist, listOf("artist"), limit = 10).artists?.items.orEmpty()
        val id = (found.firstOrNull { it.name.trim().lowercase() == wanted } ?: found.firstOrNull())?.id ?: return emptyList()
        return client.topSongs(id, count).map { it.subsonic }
    }

    override suspend fun albums(type: SubsonicClient.AlbumListType, size: Int, offset: Int): List<SubsonicAlbum> {
        if (offset > 0) return emptyList()
        val sort = when (type) {
            SubsonicClient.AlbumListType.AlphabeticalByName -> "alphabetical"
            else -> type.value
        }
        return client.albums(sort, size).map { it.subsonic }
    }

    override suspend fun search(query: String, artists: Int, albums: Int, songs: Int, songOffset: Int): SubsonicSearchResult {
        val types = listOfNotNull("artist".takeIf { artists > 0 }, "album".takeIf { albums > 0 }, "song".takeIf { songs > 0 })
        if (types.isEmpty() || songOffset > 0) return SubsonicSearchResult(emptyList(), emptyList(), emptyList())
        val found = client.searchLibrary(query, types, limit = maxOf(artists, albums, songs))
        return SubsonicSearchResult(
            found.artists?.items.orEmpty().take(artists).map { it.subsonic },
            found.albums?.items.orEmpty().take(albums).map { it.subsonic },
            found.songs?.items.orEmpty().take(songs).map { it.subsonic },
        )
    }

    override suspend fun similarSongs(songId: String, count: Int) = client.similarSongs(songId, count).map { it.subsonic }

    override suspend fun randomSongs(count: Int) = client.random(count).map { it.subsonic }

    override suspend fun star(songId: String) = client.star(songId)

    override suspend fun unstar(songId: String) = client.unstar(songId)

    override suspend fun picks(count: Int): List<SubsonicSong>? = try {
        client.picks(count).songs.filter { it.missing != true }.map { it.subsonic }
    } catch (error: TracksideError) {
        // A server from before picks answers 404: the walk from similar songs stands in.
        if (error is TracksideError.NotFound || (error is TracksideError.Http && error.status == 404)) null else throw error
    }
}

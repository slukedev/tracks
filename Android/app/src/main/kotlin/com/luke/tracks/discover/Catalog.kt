package com.luke.tracks.discover

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.tracks_engine.CatalogAlbum
import uniffi.tracks_engine.CatalogArtist
import uniffi.tracks_engine.CatalogResults
import uniffi.tracks_engine.CatalogSearchKind
import uniffi.tracks_engine.CatalogSong
import uniffi.tracks_engine.ItunesCatalog
import uniffi.tracks_engine.WebRequest
import uniffi.tracks_engine.catalogParse
import uniffi.tracks_engine.foldedName
import java.util.Locale

/** An artist's page in the catalog: their albums (singles last) and most played songs. */
data class ArtistCatalog(val artist: CatalogArtist, val albums: List<CatalogAlbum>, val songs: List<CatalogSong>)

/** An album and its songs, in order. */
data class AlbumCatalog(val album: CatalogAlbum, val songs: List<CatalogSong>)

/**
 * Apple's free iTunes catalog, in the phone's own storefront: the engine builds each request
 * and reads each answer. Answers are kept for half an hour, so going back to a page or a
 * search doesn't ask again.
 */
class ItunesSource(
    private val transport: WebTransport,
    country: String = Locale.getDefault().country,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val itunes = ItunesCatalog(country)
    private val cache = LinkedHashMap<String, Pair<Long, CatalogResults>>()
    private val lock = Mutex()

    suspend fun search(text: String): CatalogResults = coroutineScope {
        val term = text.trim()
        if (term.isEmpty()) return@coroutineScope EMPTY
        val songs = async { fetch(itunes.search(term, CatalogSearchKind.SONG, 25u)).songs }
        val albums = async { fetch(itunes.search(term, CatalogSearchKind.ALBUM, 12u)).albums }
        val artists = async { fetch(itunes.search(term, CatalogSearchKind.ARTIST, 6u)).artists }
        CatalogResults(songs.await(), albums.await(), artists.await())
    }

    suspend fun searchAlbums(text: String): List<CatalogAlbum> = fetch(itunes.search(text.trim(), CatalogSearchKind.ALBUM, 12u)).albums

    /** The catalog artist with this very name, if there is one. */
    suspend fun artistNamed(name: String): CatalogArtist? {
        val wanted = foldedName(name)
        return fetch(itunes.search(name, CatalogSearchKind.ARTIST, 10u)).artists.firstOrNull { foldedName(it.name) == wanted }
    }

    suspend fun artist(artist: CatalogArtist): ArtistCatalog = coroutineScope {
        val albums = async { fetch(itunes.artistAlbums(artist.id, 60u)).albums }
        val songs = async { fetch(itunes.artistSongs(artist.id, 25u)).songs }
        // Albums first, newest first; singles and EPs after them.
        val sorted = albums.await()
            .filter { it.artistId == null || it.artistId == artist.id }
            .sortedWith(compareBy<CatalogAlbum> { it.kind != uniffi.tracks_engine.CatalogAlbumKind.ALBUM }.thenByDescending { it.year ?: 0u })
        ArtistCatalog(artist, sorted, songs.await())
    }

    suspend fun album(id: ULong): AlbumCatalog? {
        val found = fetch(itunes.albumSongs(id))
        val album = found.albums.firstOrNull() ?: return null
        return AlbumCatalog(album, found.songs.sortedWith(compareBy({ it.discNumber ?: 1u }, { it.trackNumber ?: 0u })))
    }

    private suspend fun fetch(request: WebRequest): CatalogResults {
        lock.withLock { cache[request.url]?.let { (at, value) -> if (now() - at < KEEP_MS) return value } }
        val parsed = catalogParse(transport.send(request))
        lock.withLock {
            cache[request.url] = now() to parsed
            while (cache.size > 120) cache.remove(cache.keys.first())
        }
        return parsed
    }

    companion object {
        val EMPTY = CatalogResults(emptyList(), emptyList(), emptyList())
        private const val KEEP_MS = 30 * 60_000L
    }
}

package com.luke.tracks.discover

import androidx.compose.runtime.staticCompositionLocalOf
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uniffi.tracks_engine.Ownership
import uniffi.tracks_engine.CatalogAlbum
import uniffi.tracks_engine.CatalogArtist
import uniffi.tracks_engine.CatalogResults
import uniffi.tracks_engine.CatalogSong

/** Where music you don't have can come from, if anywhere. */
data class FetchSources(
    /** Servers that fetch what they don't have, like Octo. */
    val octo: List<String> = emptyList(),
    /** Lidarr set up and answering. */
    val lidarr: Boolean = false,
    /** Trackside servers that get songs for you (`features.acquisition` in `/me`). */
    val trackside: List<String> = emptyList(),
) {
    /** Getting music is offered only when one of these is there to do it. */
    val canFetch: Boolean get() = octo.isNotEmpty() || lidarr || trackside.isNotEmpty()
}

/** What can be done about a song you don't have. */
sealed interface Availability {
    data object Checking : Availability
    data class Owned(val song: PlayableSong) : Availability
    /** A server like Octo has it or can find it; starring it brings it in. */
    data class OnServer(val find: ServerFind) : Availability
    /** Asked for, and on its way. */
    data object Waiting : Availability
    /** In Lidarr's collection: a sync of your server brings it in. */
    data class InLidarr(val album: String) : Availability
    /** Lidarr follows its album and is looking for it. */
    data object ComingFromLidarr : Availability
    /** Lidarr doesn't have it, and can be asked for its album. */
    data object Requestable : Availability
    /** A Trackside server can get it for you with a request (section 15.3). */
    data class FromTrackside(val serverId: String) : Availability
    /** Nothing here can get it. */
    data object Unavailable : Availability

    val canGet: Boolean get() = this is OnServer || this is Requestable || this is InLidarr || this is FromTrackside
}

data class AlbumHolding(val ownership: Ownership, val ownedSongs: Int)

/**
 * Music you don't have: the catalog to search and browse, whether something is already
 * yours, and getting it through your server or Lidarr when one of them can.
 */
interface Discovery {
    val sources: StateFlow<FetchSources>
    /** Identities of songs asked for that haven't arrived. */
    val waiting: StateFlow<Set<String>>

    /** Throws when the catalog can't be reached. */
    suspend fun search(query: String): CatalogResults
    suspend fun artist(name: String, catalogId: ULong? = null): ArtistCatalog?
    suspend fun album(id: ULong): AlbumCatalog?
    /** The catalog's album of this title by this artist, if it has one. */
    suspend fun findAlbum(title: String, artist: String): CatalogAlbum?

    /** The song in your music, if you have it. */
    fun owned(song: CatalogSong): PlayableSong?
    fun holding(album: CatalogAlbum): AlbumHolding
    suspend fun availability(song: CatalogSong): Availability

    /** Whether Play From has anywhere beyond your library to play from: a streaming service, or Get. */
    fun reachesBeyondLibrary(): Boolean = false

    /** Whether tapping [song] tries to play it rather than its preview: it's yours, or Play From may find it. */
    fun mayPlay(song: CatalogSong, availability: Availability): Boolean = availability is Availability.Owned || reachesBeyondLibrary()

    /**
     * What tapping [song] plays, where Play From finds it: yours, a streaming service's song that
     * is the same song, or a song to get as it's reached. Null when nothing in the order can.
     */
    suspend fun toPlay(song: CatalogSong, availability: Availability): PlayableSong? = (availability as? Availability.Owned)?.song

    /** Every song of an album, as [toPlay] has each play; null for ones nothing in the order can play. */
    suspend fun toPlayAll(songs: List<CatalogSong>, first: Int): List<PlayableSong?> = songs.map { toPlay(it, availability(it)) }

    /** Asks for a song, and returns what to tell the person. */
    suspend fun get(song: CatalogSong): String
    suspend fun getAlbum(album: CatalogAlbum): String
    /** Has Lidarr follow an artist; null when there's no Lidarr. */
    suspend fun followArtist(name: String): String?
}

/** Nothing configured and nothing found: what previews and tests start from. */
object NoDiscovery : Discovery {
    override val sources: StateFlow<FetchSources> = MutableStateFlow(FetchSources())
    override val waiting: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override suspend fun search(query: String) = ItunesSource.EMPTY
    override suspend fun artist(name: String, catalogId: ULong?): ArtistCatalog? = null
    override suspend fun album(id: ULong): AlbumCatalog? = null
    override suspend fun findAlbum(title: String, artist: String): CatalogAlbum? = null
    override fun owned(song: CatalogSong): PlayableSong? = null
    override fun holding(album: CatalogAlbum) = AlbumHolding(Ownership.MISSING, 0)
    override suspend fun availability(song: CatalogSong): Availability = Availability.Unavailable
    override suspend fun get(song: CatalogSong) = ""
    override suspend fun getAlbum(album: CatalogAlbum) = ""
    override suspend fun followArtist(name: String): String? = null
}

/** The app's [Discovery]; screenshots provide their own. */
val LocalDiscovery = staticCompositionLocalOf<Discovery?> { null }

/** A catalog artist from a search result's name and id, when that's all there is. */
fun catalogArtist(name: String, id: ULong?) = CatalogArtist(id ?: 0u, name, null, null)

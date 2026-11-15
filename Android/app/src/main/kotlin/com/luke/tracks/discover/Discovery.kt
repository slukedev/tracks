package com.luke.tracks.discover

import androidx.compose.runtime.staticCompositionLocalOf
import com.luke.tracks.playback.PlayableSong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uniffi.tracks_engine.AlbumOwnership
import uniffi.tracks_engine.CatalogAlbum
import uniffi.tracks_engine.CatalogArtist
import uniffi.tracks_engine.CatalogResults
import uniffi.tracks_engine.CatalogSong
import uniffi.tracks_engine.foldedName

/** Where music you don't have can come from, if anywhere. */
data class FetchSources(
    /** Servers that fetch what they don't have, like Octo. */
    val octo: List<String> = emptyList(),
    /** Lidarr set up and answering. */
    val lidarr: Boolean = false,
) {
    /** Getting music is offered only when one of these is there to do it. */
    val canFetch: Boolean get() = octo.isNotEmpty() || lidarr
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
    /** Nothing here can get it. */
    data object Unavailable : Availability

    val canGet: Boolean get() = this is OnServer || this is Requestable || this is InLidarr
}

data class AlbumHolding(val ownership: AlbumOwnership, val ownedSongs: Int)

data class SuggestedAlbum(val album: CatalogAlbum, val holding: AlbumHolding)

/** An artist you don't play, like ones you do. */
data class SuggestedArtist(
    val name: String,
    val pictureUrl: String?,
    /** Your artists they're like, the closest first. */
    val like: List<String>,
)

/** Music you'd likely want and don't have, from your listening. */
data class Suggestions(
    /** Songs by your artists and artists like them, best first. */
    val songs: List<CatalogSong> = emptyList(),
    val artists: List<SuggestedArtist> = emptyList(),
    /** What your artists put out in the last six months, or have coming, newest first. */
    val releases: List<SuggestedAlbum> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty() && releases.isEmpty()

    /** Without an artist asked not to be suggested, and their songs. */
    fun without(artist: String): Suggestions {
        val key = foldedName(artist)
        return copy(
            songs = songs.filter { foldedName(it.artistName) != key },
            artists = artists.filter { foldedName(it.name) != key },
        )
    }
}

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
    /**
     * What to suggest, worked out again a few times a day or when your top artists change.
     * Never throws: offline, it's what was found last, or nothing.
     */
    suspend fun suggestions(force: Boolean = false): Suggestions
    /** The last suggestions worked out in this process, for a page's first frame. */
    val lastSuggestions: Suggestions?
    /** Stops suggesting an artist and their songs. */
    suspend fun notInterested(artist: String)

    /** The song in your music, if you have it. */
    fun owned(song: CatalogSong): PlayableSong?
    fun holding(album: CatalogAlbum): AlbumHolding
    suspend fun availability(song: CatalogSong): Availability

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
    override suspend fun suggestions(force: Boolean) = Suggestions()
    override val lastSuggestions: Suggestions? = null
    override suspend fun notInterested(artist: String) = Unit
    override fun owned(song: CatalogSong): PlayableSong? = null
    override fun holding(album: CatalogAlbum) = AlbumHolding(AlbumOwnership.MISSING, 0)
    override suspend fun availability(song: CatalogSong): Availability = Availability.Unavailable
    override suspend fun get(song: CatalogSong) = ""
    override suspend fun getAlbum(album: CatalogAlbum) = ""
    override suspend fun followArtist(name: String): String? = null
}

/** The app's [Discovery]; screenshots provide their own. */
val LocalDiscovery = staticCompositionLocalOf<Discovery?> { null }

/** A catalog artist from a search result's name and id, when that's all there is. */
fun catalogArtist(name: String, id: ULong?) = CatalogArtist(id ?: 0u, name, null, null)

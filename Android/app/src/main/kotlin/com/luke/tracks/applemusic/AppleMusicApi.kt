package com.luke.tracks.applemusic

import com.luke.tracks.applemusic.Wire.toCollection
import com.luke.tracks.applemusic.Wire.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Why Apple Music can't do something, each with the one sentence the app says. */
enum class AppleMusicProblem(val message: String, val retryable: Boolean) {
    Offline("Can't reach Apple Music. Check your connection.", true),
    Busy("Apple Music is busy. Try again in a few minutes.", true),
    SignedOut("Sign in to Apple Music again to play this.", false),
    NoSubscription("An Apple Music subscription is needed to play here.", false),
    NeedsApp("Install Apple Music to sign in.", false),
    /** The developer token was refused or ran out: only a new build or server token fixes it. */
    Expired("Apple Music access in this build has expired.", false),
    Unavailable("This song isn't available on Apple Music.", false),
    ;

    /** Whether it's about the account rather than one song. */
    val accountWide: Boolean get() = this == SignedOut || this == NoSubscription || this == Expired
}

class AppleMusicException(val problem: AppleMusicProblem, cause: Throwable? = null) : IOException(problem.message, cause)

/** The Apple Music problem behind an error, if that's what it was. */
fun Throwable.appleMusicProblem(): AppleMusicProblem? =
    generateSequence(this) { it.cause }.take(8).filterIsInstance<AppleMusicException>().firstOrNull()?.problem

fun Throwable.toAppleMusicProblem(): AppleMusicProblem = appleMusicProblem() ?: AppleMusicProblem.Offline

/** A page of a list, and the offset of the next one. */
data class AppleMusicPage<T>(val items: List<T>, val nextOffset: Int?)

/** Search results from the library or the catalog. */
data class AppleMusicResults(
    val songs: List<AppleMusicSong> = emptyList(),
    val albums: List<AppleMusicCollection> = emptyList(),
    val playlists: List<AppleMusicCollection> = emptyList(),
)

/**
 * The Apple Music API (api.music.apple.com): the catalog with the developer token alone, and the
 * person's library with their Music User Token too. A refused developer token is fetched again
 * once; everything else comes back as an [AppleMusicException].
 */
class AppleMusicApi(
    private val client: OkHttpClient,
    private val tokens: DeveloperTokenProvider,
    private val userToken: () -> String?,
    private val base: HttpUrl = BASE.toHttpUrl(),
) {
    suspend fun storefront(): String =
        get("v1/me/storefront", Wire.Response.serializer(Wire.Storefront.serializer()), personal = true).data.firstOrNull()?.id
            ?: throw AppleMusicException(AppleMusicProblem.NoSubscription)

    suspend fun librarySongs(offset: Int = 0, limit: Int = LIBRARY_PAGE): AppleMusicPage<AppleMusicSong> =
        page("v1/me/library/songs", offset, limit, Wire.Song.serializer()) { it.toSong() }

    suspend fun libraryAlbums(offset: Int = 0, limit: Int = LIBRARY_PAGE): AppleMusicPage<AppleMusicCollection> =
        page("v1/me/library/albums", offset, limit, Wire.Collection.serializer()) { it.toCollection(AppleMusicCollection.Kind.LibraryAlbum) }

    suspend fun libraryPlaylists(offset: Int = 0, limit: Int = LIBRARY_PAGE): AppleMusicPage<AppleMusicCollection> =
        page("v1/me/library/playlists", offset, limit, Wire.Collection.serializer()) { it.toCollection(AppleMusicCollection.Kind.LibraryPlaylist) }

    /** Every song of an album or playlist, library or catalog, in order. */
    suspend fun songs(collection: AppleMusicCollection, storefront: String?): List<AppleMusicSong> {
        val path = when (collection.kind) {
            AppleMusicCollection.Kind.LibraryAlbum -> "v1/me/library/albums/${collection.id}/tracks"
            AppleMusicCollection.Kind.LibraryPlaylist -> "v1/me/library/playlists/${collection.id}/tracks"
            AppleMusicCollection.Kind.Album -> "v1/catalog/${storefront ?: throw AppleMusicException(AppleMusicProblem.SignedOut)}/albums/${collection.id}/tracks"
            AppleMusicCollection.Kind.Playlist -> "v1/catalog/${storefront ?: throw AppleMusicException(AppleMusicProblem.SignedOut)}/playlists/${collection.id}/tracks"
        }
        val all = mutableListOf<AppleMusicSong>()
        var offset: Int? = 0
        var pages = 0
        while (offset != null && pages++ < MAX_PAGES) {
            val page = page(path, offset, TRACKS_PAGE, Wire.Song.serializer()) { it.toSong() }
            all += page.items
            offset = page.nextOffset
        }
        return all
    }

    suspend fun searchLibrary(term: String, limit: Int = SEARCH_LIMIT): AppleMusicResults {
        val results = get(
            "v1/me/library/search",
            Wire.SearchResponse.serializer(),
            personal = true,
            query = mapOf("term" to term, "types" to "library-songs,library-albums,library-playlists", "limit" to limit.toString()),
        ).results
        return AppleMusicResults(
            songs = results.librarySongs?.data.orEmpty().mapNotNull { it.toSong() },
            albums = results.libraryAlbums?.data.orEmpty().mapNotNull { it.toCollection(AppleMusicCollection.Kind.LibraryAlbum) },
            playlists = results.libraryPlaylists?.data.orEmpty().mapNotNull { it.toCollection(AppleMusicCollection.Kind.LibraryPlaylist) },
        )
    }

    suspend fun searchCatalog(storefront: String, term: String, limit: Int = SEARCH_LIMIT): AppleMusicResults {
        val results = get(
            "v1/catalog/$storefront/search",
            Wire.SearchResponse.serializer(),
            personal = false,
            query = mapOf("term" to term, "types" to "songs,albums,playlists", "limit" to limit.toString()),
        ).results
        return AppleMusicResults(
            songs = results.songs?.data.orEmpty().mapNotNull { it.toSong() },
            albums = results.albums?.data.orEmpty().mapNotNull { it.toCollection(AppleMusicCollection.Kind.Album) },
            playlists = results.playlists?.data.orEmpty().mapNotNull { it.toCollection(AppleMusicCollection.Kind.Playlist) },
        )
    }

    private suspend fun <W, T> page(path: String, offset: Int, limit: Int, item: KSerializer<W>, map: (W) -> T?): AppleMusicPage<T> {
        val personal = path.startsWith("v1/me/")
        val response = get(path, Wire.Response.serializer(item), personal, mapOf("offset" to offset.toString(), "limit" to limit.toString()))
        return AppleMusicPage(response.data.mapNotNull(map), response.next?.let(::offsetOf))
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, personal: Boolean, query: Map<String, String> = emptyMap()): T =
        withContext(Dispatchers.IO) {
            val url = base.newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val body = send(url, personal) ?: send(url, personal) ?: throw AppleMusicException(AppleMusicProblem.Expired)
            try {
                WireJson.decodeFromString(serializer, body)
            } catch (error: IllegalArgumentException) {
                throw AppleMusicException(AppleMusicProblem.Unavailable, error)
            }
        }

    /** The body of a successful answer, or null when Apple refused the developer token, which is then let go. */
    private suspend fun send(url: HttpUrl, personal: Boolean): String? {
        val token = tokens.token() ?: throw AppleMusicException(AppleMusicProblem.Expired)
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${token.jwt}").apply {
            if (personal) header(USER_TOKEN_HEADER, userToken() ?: throw AppleMusicException(AppleMusicProblem.SignedOut))
        }.build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> response.body.string()
                    401 -> {
                        tokens.rejected(token)
                        null
                    }
                    403 -> throw AppleMusicException(if (personal) AppleMusicProblem.SignedOut else AppleMusicProblem.Expired)
                    404 -> throw AppleMusicException(AppleMusicProblem.Unavailable)
                    429 -> throw AppleMusicException(AppleMusicProblem.Busy)
                    else -> throw AppleMusicException(if (response.code >= 500) AppleMusicProblem.Busy else AppleMusicProblem.Unavailable)
                }
            }
        } catch (error: AppleMusicException) {
            throw error
        } catch (error: IOException) {
            throw AppleMusicException(AppleMusicProblem.Offline, error)
        }
    }

    /** The offset in a `next` path such as "/v1/me/library/songs?offset=100". */
    private fun offsetOf(next: String): Int? = runCatching { base.resolve(next)?.queryParameter("offset")?.toInt() }.getOrNull()

    companion object {
        const val BASE = "https://api.music.apple.com/"
        const val USER_TOKEN_HEADER = "Music-User-Token"
        const val LIBRARY_PAGE = 100
        const val TRACKS_PAGE = 100
        const val SEARCH_LIMIT = 25
        /** Enough for a 30,000-song playlist; past that the rest is left out. */
        const val MAX_PAGES = 300
    }
}

/** The JSON Apple sends, only the parts Tracks reads. */
internal object Wire {
    @Serializable
    data class Response<T>(val data: List<T> = emptyList(), val next: String? = null)

    @Serializable
    data class Storefront(val id: String)

    @Serializable
    data class Artwork(val url: String? = null)

    @Serializable
    data class PlayParams(val id: String? = null, val kind: String? = null, val isLibrary: Boolean? = null, val catalogId: String? = null)

    @Serializable
    data class SongAttributes(
        val name: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val durationInMillis: Long? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val releaseDate: String? = null,
        val genreNames: List<String>? = null,
        val artwork: Artwork? = null,
        val playParams: PlayParams? = null,
        val url: String? = null,
    )

    @Serializable
    data class Song(val id: String, val type: String? = null, val attributes: SongAttributes? = null)

    @Serializable
    data class CollectionAttributes(
        val name: String? = null,
        val artistName: String? = null,
        val curatorName: String? = null,
        val releaseDate: String? = null,
        val trackCount: Int? = null,
        val artwork: Artwork? = null,
        val url: String? = null,
    )

    @Serializable
    data class Collection(val id: String, val type: String? = null, val attributes: CollectionAttributes? = null)

    @Serializable
    data class SearchResponse(val results: Results = Results())

    @Serializable
    data class Results(
        val songs: Response<Song>? = null,
        val albums: Response<Collection>? = null,
        val playlists: Response<Collection>? = null,
        @kotlinx.serialization.SerialName("library-songs") val librarySongs: Response<Song>? = null,
        @kotlinx.serialization.SerialName("library-albums") val libraryAlbums: Response<Collection>? = null,
        @kotlinx.serialization.SerialName("library-playlists") val libraryPlaylists: Response<Collection>? = null,
    )

    fun Song.toSong(): AppleMusicSong? {
        val a = attributes ?: return null
        val library = id.startsWith("i.") || a.playParams?.isLibrary == true
        return AppleMusicSong(
            id = id,
            catalogId = if (library) a.playParams?.catalogId?.takeIf { it.isNotBlank() } else id,
            title = a.name.orEmpty(),
            artistName = a.artistName.orEmpty(),
            albumTitle = a.albumName,
            durationMs = a.durationInMillis?.takeIf { it > 0 },
            trackNumber = a.trackNumber,
            discNumber = a.discNumber,
            year = a.releaseDate?.take(4)?.toIntOrNull(),
            genre = a.genreNames?.firstOrNull { it != "Music" },
            artworkTemplate = a.artwork?.url,
        )
    }

    fun Collection.toCollection(kind: AppleMusicCollection.Kind): AppleMusicCollection? {
        val a = attributes ?: return null
        return AppleMusicCollection(
            id = id,
            kind = kind,
            title = a.name.orEmpty(),
            subtitle = a.artistName ?: a.curatorName,
            year = a.releaseDate?.take(4)?.toIntOrNull(),
            artworkTemplate = a.artwork?.url,
            url = a.url,
        )
    }
}

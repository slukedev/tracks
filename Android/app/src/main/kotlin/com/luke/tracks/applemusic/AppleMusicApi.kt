package com.luke.tracks.applemusic

import com.luke.tracks.applemusic.Wire.toCollection
import com.luke.tracks.applemusic.Wire.toFeedCollection
import com.luke.tracks.net.Logs
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
    /** Neither a paired Trackside server nor the build has a developer token to sign in with. */
    NoKey("Sign-in needs your server's Apple Music key.", true),
    /** No developer token could be had right now: the server didn't answer, or has no key. */
    NoToken("Couldn't get Apple Music access from your server. Check that it's reachable and has your MusicKit key.", true),
    OtherDevice("Apple Music is playing on another device.", true),
    AgeRestricted("Confirm your age in Apple Music to play this.", false),
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

/** One of Apple Music's recommendations for you: a titled row of albums and playlists. */
data class AppleMusicRecommendation(val id: String, val title: String, val items: List<AppleMusicCollection>)

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

    /**
     * Apple Music's recommendations for you (`GET /v1/me/recommendations`), as titled rows of
     * albums and playlists. Stations are left out, as MusicKit for Android can't play them; a
     * group of recommendations becomes one row for each of its own.
     */
    suspend fun recommendations(): List<AppleMusicRecommendation> {
        val response = get("v1/me/recommendations", Wire.Response.serializer(Wire.Recommendation.serializer()), personal = true)
        return response.data.flatMap { recommendation ->
            val own = row(recommendation.id, recommendation.attributes, recommendation.relationships?.contents?.data.orEmpty())
            val nested = recommendation.relationships?.recommendations?.data.orEmpty().mapNotNull { row(it.id, it.attributes, it.relationships?.contents?.data.orEmpty()) }
            listOfNotNull(own) + nested
        }
    }

    private fun row(id: String, attributes: Wire.RecommendationAttributes?, contents: List<Wire.Collection>): AppleMusicRecommendation? {
        val title = attributes?.title?.stringForDisplay?.takeIf { it.isNotBlank() } ?: return null
        val items = contents.mapNotNull { it.toFeedCollection() }.distinctBy { it.kind to it.id }
        return if (items.isEmpty()) null else AppleMusicRecommendation(id, title, items)
    }

    /** What you played lately in Apple Music (`GET /v1/me/recent/played`), albums and playlists, newest first. */
    suspend fun recentlyPlayed(pages: Int = 2): List<AppleMusicCollection> {
        val all = mutableListOf<AppleMusicCollection>()
        var offset: Int? = 0
        var page = 0
        while (offset != null && page++ < pages) {
            val response = get(
                "v1/me/recent/played",
                Wire.Response.serializer(Wire.Collection.serializer()),
                personal = true,
                query = mapOf("offset" to offset.toString(), "limit" to RECENT_PAGE.toString()),
            )
            all += response.data.mapNotNull { it.toFeedCollection() }
            offset = response.next?.let(::offsetOf)
        }
        return all.distinctBy { it.kind to it.id }
    }

    /** The storefront's most played albums (`GET /v1/catalog/{storefront}/charts`). */
    suspend fun topAlbums(storefront: String, limit: Int = CHART_LIMIT): List<AppleMusicCollection> =
        get(
            "v1/catalog/$storefront/charts",
            Wire.ChartsResponse.serializer(),
            personal = false,
            query = mapOf("types" to "albums", "limit" to limit.toString()),
        ).results.albums.firstOrNull()?.data.orEmpty().mapNotNull { it.toCollection(AppleMusicCollection.Kind.Album) }

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
        // A playlist made for you ("pl.pm-...") is read with your Music User Token too.
        val withUser = personal || path.contains("/playlists/")
        val response = get(path, Wire.Response.serializer(item), personal, mapOf("offset" to offset.toString(), "limit" to limit.toString()), withUser)
        return AppleMusicPage(response.data.mapNotNull(map), response.next?.let(::offsetOf))
    }

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        personal: Boolean,
        query: Map<String, String> = emptyMap(),
        withUser: Boolean = personal,
    ): T =
        withContext(Dispatchers.IO) {
            val url = base.newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val body = send(url, personal, withUser, first = true) ?: send(url, personal, withUser, first = false)
                ?: run {
                    Logs.w("Apple Music API: /$path was refused twice (401), the developer token isn't accepted")
                    throw AppleMusicException(AppleMusicProblem.Expired)
                }
            try {
                WireJson.decodeFromString(serializer, body)
            } catch (error: IllegalArgumentException) {
                Logs.w("Apple Music API: /$path answered with something Tracks couldn't read", error)
                throw AppleMusicException(AppleMusicProblem.Unavailable, error)
            }
        }

    /**
     * The body of a successful answer, or null when Apple refused the developer token and it's
     * worth asking once more: a 401 lets the token go, and a 403 does when a different one can be had.
     */
    private suspend fun send(url: HttpUrl, personal: Boolean, withUser: Boolean, first: Boolean): String? {
        val path = url.encodedPath
        val token = tokens.token() ?: run {
            // None at all is the server's doing; none left after Apple refused one is Apple's.
            Logs.w("Apple Music API: no developer token for $path" + if (first) " (none could be had from your server or the build)" else " after Apple refused the last one")
            throw AppleMusicException(if (first) AppleMusicProblem.NoToken else AppleMusicProblem.Expired)
        }
        val user = if (withUser) userToken() else null
        if (personal && user == null) {
            Logs.w("Apple Music API: $path needs your Music User Token, and there's none (signed out)")
            throw AppleMusicException(AppleMusicProblem.SignedOut)
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${token.jwt}").apply {
            if (user != null) header(USER_TOKEN_HEADER, user)
        }.build()
        val (code, body) = try {
            client.newCall(request).execute().use { response ->
                response.code to (if (response.isSuccessful) response.body.string() else null)
            }
        } catch (error: IOException) {
            Logs.w("Apple Music API: $path didn't go through", error)
            throw AppleMusicException(AppleMusicProblem.Offline, error)
        }
        if (code !in 200..299) Logs.w("Apple Music API: $path answered $code" + if (user != null) " (with your Music User Token)" else "")
        return when (code) {
            in 200..299 -> body
            401 -> {
                tokens.rejected(token)
                null
            }
            403 -> if (first && tokens.replaced(token)) null
            else throw AppleMusicException(if (personal) AppleMusicProblem.SignedOut else AppleMusicProblem.Expired)
            404 -> throw AppleMusicException(AppleMusicProblem.Unavailable)
            429 -> throw AppleMusicException(AppleMusicProblem.Busy)
            else -> throw AppleMusicException(if (code >= 500) AppleMusicProblem.Busy else AppleMusicProblem.Unavailable)
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
        /** Apple's most for recently played. */
        const val RECENT_PAGE = 10
        const val CHART_LIMIT = 20
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
    data class Text(val stringForDisplay: String? = null)

    @Serializable
    data class RecommendationAttributes(val title: Text? = null, val kind: String? = null)

    @Serializable
    data class Contents(val data: List<Collection> = emptyList())

    @Serializable
    data class NestedRelationships(val contents: Contents? = null)

    /** A recommendation inside a group of them. */
    @Serializable
    data class NestedRecommendation(val id: String, val attributes: RecommendationAttributes? = null, val relationships: NestedRelationships? = null)

    @Serializable
    data class Nested(val data: List<NestedRecommendation> = emptyList())

    @Serializable
    data class RecommendationRelationships(val contents: Contents? = null, val recommendations: Nested? = null)

    @Serializable
    data class Recommendation(val id: String, val attributes: RecommendationAttributes? = null, val relationships: RecommendationRelationships? = null)

    @Serializable
    data class Chart(val chart: String? = null, val name: String? = null, val data: List<Collection> = emptyList())

    @Serializable
    data class ChartResults(val albums: List<Chart> = emptyList())

    @Serializable
    data class ChartsResponse(val results: ChartResults = ChartResults())

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

    /** An album or playlist by its type, from the catalog or the library; null for anything else, such as a station. */
    fun Collection.toFeedCollection(): AppleMusicCollection? = when (type) {
        "albums" -> toCollection(AppleMusicCollection.Kind.Album)
        "playlists" -> toCollection(AppleMusicCollection.Kind.Playlist)
        "library-albums" -> toCollection(AppleMusicCollection.Kind.LibraryAlbum)
        "library-playlists" -> toCollection(AppleMusicCollection.Kind.LibraryPlaylist)
        else -> null
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

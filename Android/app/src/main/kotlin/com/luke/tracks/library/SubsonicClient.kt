package com.luke.tracks.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** Sends a request and hands back the status and body. OkHttp in the app; a stub in tests. */
fun interface SubsonicTransport {
    suspend fun get(url: HttpUrl): Pair<Int, String>
}

class OkHttpTransport(private val http: OkHttpClient) : SubsonicTransport {
    override suspend fun get(url: HttpUrl): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }
    }
}

/**
 * Talks to one Subsonic server, the same way the Swift app's client does: every request is
 * signed with md5(password + salt) and a salt of its own, so the password never leaves the
 * device.
 */
class SubsonicClient(
    val server: SubsonicServer,
    private val password: String,
    private val transport: SubsonicTransport,
    private val newSalt: () -> String = ::makeSalt,
) {
    /** An endpoint's address with the credentials every request carries. */
    fun url(endpoint: String, parameters: Map<String, String> = emptyMap(), json: Boolean = true, salt: String = newSalt()): HttpUrl {
        val builder = server.url.toHttpUrl().newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
            .addQueryParameter("u", server.username)
            .addQueryParameter("t", token(password, salt))
            .addQueryParameter("s", salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", "Tracks")
        if (json) builder.addQueryParameter("f", "json")
        parameters.toSortedMap().forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    /** The song as the server stores it, or re-encoded for a slow connection. */
    fun streamUrl(songId: String, maxBitRate: Int? = null): HttpUrl =
        if (maxBitRate != null) url("stream", mapOf("id" to songId, "maxBitRate" to "$maxBitRate", "format" to "mp3"), json = false)
        else url("stream", mapOf("id" to songId, "format" to "raw"), json = false)

    /** Signed with the server's own salt, so the address is the same every time and stays cached. */
    fun coverArtUrl(id: String, size: Int? = null): HttpUrl =
        url("getCoverArt", if (size == null) mapOf("id" to id) else mapOf("id" to id, "size" to "$size"), json = false, salt = server.salt)

    /** Checks the address and credentials. */
    suspend fun ping() {
        call("ping")
    }

    /** What the server says it is, from OpenSubsonic's ping: "navidrome", "octo", or null. */
    suspend fun serverType(): String? = call("ping").type

    suspend fun artists(): List<SubsonicArtist> =
        call("getArtists").artists?.index.orEmpty().flatMap { it.artist }

    suspend fun album(id: String): Pair<SubsonicAlbum, List<SubsonicSong>> {
        val album = call("getAlbum", mapOf("id" to id)).album ?: throw SubsonicError.NotFound
        return album.album() to album.song
    }

    /** An artist and their albums. */
    suspend fun artist(id: String): Pair<SubsonicArtist, List<SubsonicAlbum>> {
        val artist = call("getArtist", mapOf("id" to id)).artist ?: throw SubsonicError.NotFound
        return SubsonicArtist(artist.id, artist.name, artist.album.size, artist.coverArt, artist.artistImageUrl) to artist.album
    }

    /** One song, with the ids of its album and artist. */
    suspend fun song(id: String): SubsonicSong =
        call("getSong", mapOf("id" to id)).song ?: throw SubsonicError.NotFound

    /** An artist's most popular songs, by name: servers ask Last.fm. */
    suspend fun topSongs(artist: String, count: Int = 20): List<SubsonicSong> =
        call("getTopSongs", mapOf("artist" to artist, "count" to "$count")).topSongs?.song.orEmpty()

    enum class AlbumListType(val value: String) {
        Newest("newest"), Recent("recent"), Frequent("frequent"), Random("random"),
        AlphabeticalByName("alphabeticalByName"), Starred("starred"),
    }

    suspend fun albums(type: AlbumListType, size: Int = 30, offset: Int = 0): List<SubsonicAlbum> =
        call("getAlbumList2", mapOf("type" to type.value, "size" to "$size", "offset" to "$offset")).albumList2?.album.orEmpty()

    /**
     * Artists, albums and songs matching a query. An empty query matches everything on most
     * servers, which is how a whole library is paged through.
     */
    suspend fun search(query: String, artists: Int = 10, albums: Int = 20, songs: Int = 40, songOffset: Int = 0): SubsonicSearchResult {
        val result = call(
            "search3",
            mapOf(
                "query" to query,
                "artistCount" to "$artists",
                "albumCount" to "$albums",
                "songCount" to "$songs",
                "songOffset" to "$songOffset",
            ),
        ).searchResult3
        return SubsonicSearchResult(result?.artist.orEmpty(), result?.album.orEmpty(), result?.song.orEmpty())
    }

    /** Songs like one, by way of its artist: Octo gets them from Last.fm, and can fetch them. */
    suspend fun similarSongs(songId: String, count: Int = 15): List<SubsonicSong> =
        call("getSimilarSongs2", mapOf("id" to songId, "count" to "$count")).similarSongs2?.song.orEmpty()

    suspend fun randomSongs(count: Int = 40): List<SubsonicSong> =
        call("getRandomSongs", mapOf("size" to "$count")).randomSongs?.song.orEmpty()

    suspend fun star(songId: String) { call("star", mapOf("id" to songId)) }
    suspend fun unstar(songId: String) { call("unstar", mapOf("id" to songId)) }

    /** Every song on the server, a page at a time, up to [limit]. */
    suspend fun allSongs(limit: Int = 50_000, pageSize: Int = 500): List<SubsonicSong> {
        val songs = mutableListOf<SubsonicSong>()
        while (songs.size < limit) {
            val page = search("", artists = 0, albums = 0, songs = pageSize, songOffset = songs.size).songs
            songs += page
            if (page.size < pageSize) break
        }
        if (songs.isNotEmpty()) return songs
        // Some servers want a query for search3: walk the albums instead.
        var offset = 0
        while (songs.size < limit) {
            val page = albums(AlbumListType.AlphabeticalByName, size = pageSize, offset = offset)
            page.forEach { songs += album(it.id).second }
            if (page.size < pageSize) break
            offset += page.size
        }
        return songs
    }

    private suspend fun call(endpoint: String, parameters: Map<String, String> = emptyMap()): SubsonicBody {
        val (status, body) = try {
            transport.get(url(endpoint, parameters))
        } catch (error: IOException) {
            throw SubsonicError.Unreachable(error)
        } catch (error: IllegalArgumentException) {
            throw SubsonicError.Unreachable(error)
        }
        return decode(body, status)
    }

    companion object {
        /** 1.16.1 is the last Subsonic release; OpenSubsonic servers answer to it too. */
        const val API_VERSION = "1.16.1"

        private val random = SecureRandom()
        private const val SALT_LETTERS = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun makeSalt(): String = buildString { repeat(12) { append(SALT_LETTERS[random.nextInt(SALT_LETTERS.length)]) } }

        /** md5(password + salt), in lower-case hex, as the API asks. */
        fun token(password: String, salt: String): String =
            MessageDigest.getInstance("MD5").digest((password + salt).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        internal val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            isLenient = true
        }

        internal fun decode(text: String, status: Int): SubsonicBody {
            val body = try {
                json.decodeFromString(SubsonicEnvelope.serializer(), text).body
            } catch (_: Exception) {
                throw if (status in 200..299) SubsonicError.NotSubsonic else SubsonicError.Http(status)
            }
            if (body.status != "ok") {
                val code = body.error?.code ?: 0
                throw when (code) {
                    40, 41 -> SubsonicError.WrongCredentials
                    20, 30 -> SubsonicError.Incompatible
                    70 -> SubsonicError.NotFound
                    else -> SubsonicError.Server(code, body.error?.message.orEmpty())
                }
            }
            return body
        }
    }
}


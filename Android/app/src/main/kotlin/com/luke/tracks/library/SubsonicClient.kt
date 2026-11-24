package com.luke.tracks.library

import com.luke.tracks.net.Http
import com.luke.tracks.net.ServerEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Sends a request and hands back the status and body. OkHttp in the app; a stub in tests. */
fun interface SubsonicTransport {
    suspend fun get(url: HttpUrl): Pair<Int, String>
}

class OkHttpTransport(private val http: OkHttpClient = Http.api, private val timeoutMs: Long? = null) : SubsonicTransport {
    override suspend fun get(url: HttpUrl): Pair<Int, String> = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).build())
        timeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        call.execute().use { it.code to it.body.string() }
    }
}

/**
 * Talks to one Subsonic server, the same way the iPhone app's client does. With a password,
 * every request is signed with md5(password + salt) and a fresh random salt of its own, so the
 * password never leaves the device; with an OpenSubsonic API key, the key goes instead. Either
 * way requests go to the address [endpoints] has found working, once it's proven to be a
 * Subsonic server.
 */
class SubsonicClient(
    val server: SubsonicServer,
    /** The password, or the API key when [SubsonicServer.auth] says so. */
    private val secret: String,
    private val transport: SubsonicTransport,
    private val newSalt: () -> String = ::makeSalt,
    private val endpoints: ServerEndpoints? = null,
) : MusicServerClient {
    private val home: HttpUrl get() = server.url.toHttpUrl()

    /** An endpoint's address with the credentials every request carries. */
    fun url(endpoint: String, parameters: Map<String, String> = emptyMap(), json: Boolean = true, base: HttpUrl = endpoints?.baseUrl ?: home): HttpUrl {
        val builder = base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
        if (server.auth == SubsonicAuth.ApiKey) {
            builder.addQueryParameter("apiKey", secret)
        } else {
            val salt = newSalt()
            builder.addQueryParameter("u", server.username)
                .addQueryParameter("t", token(secret, salt))
                .addQueryParameter("s", salt)
        }
        builder.addQueryParameter("v", API_VERSION).addQueryParameter("c", CLIENT)
        if (json) builder.addQueryParameter("f", "json")
        parameters.toSortedMap().forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    /** The address in use, once proven; the one it was added with when there's nothing to race. */
    suspend fun provenBase(): HttpUrl = endpoints?.provenBaseUrl() ?: home

    /** The song as the server stores it, or re-encoded for a slow connection. Signed now, used once. */
    fun streamUrl(songId: String, maxBitRate: Int? = null, base: HttpUrl = endpoints?.baseUrl ?: home): HttpUrl =
        if (maxBitRate != null) url("stream", mapOf("id" to songId, "maxBitRate" to "$maxBitRate", "format" to "mp3"), json = false, base = base)
        else url("stream", mapOf("id" to songId, "format" to "raw"), json = false, base = base)

    /**
     * Signed with a fresh salt like everything else, so no address works for long. Covers stay
     * cached all the same: the image loader files them under their [com.luke.tracks.net.ServerLinks] link.
     */
    fun coverArtUrl(id: String, size: Int? = null, base: HttpUrl = endpoints?.baseUrl ?: home): HttpUrl =
        url("getCoverArt", if (size == null) mapOf("id" to id) else mapOf("id" to id, "size" to "$size"), json = false, base = base)

    /** Checks the address and credentials. */
    override suspend fun ping() {
        call("ping")
    }

    /** What the server says it is, from OpenSubsonic's ping: "navidrome", "octo", or null. */
    override suspend fun serverType(): String? = call("ping").type

    suspend fun artists(): List<SubsonicArtist> =
        call("getArtists").artists?.index.orEmpty().flatMap { it.artist }

    override suspend fun album(id: String): Pair<SubsonicAlbum, List<SubsonicSong>> {
        val album = call("getAlbum", mapOf("id" to id)).album ?: throw SubsonicError.NotFound
        return album.album() to album.song
    }

    /** An artist and their albums. */
    override suspend fun artist(id: String): Pair<SubsonicArtist, List<SubsonicAlbum>> {
        val artist = call("getArtist", mapOf("id" to id)).artist ?: throw SubsonicError.NotFound
        return SubsonicArtist(artist.id, artist.name, artist.album.size, artist.coverArt, artist.artistImageUrl) to artist.album
    }

    /** One song, with the ids of its album and artist. */
    override suspend fun song(id: String): SubsonicSong =
        call("getSong", mapOf("id" to id)).song ?: throw SubsonicError.NotFound

    /** An artist's most popular songs, by name: servers ask Last.fm. */
    override suspend fun topSongs(artist: String, count: Int): List<SubsonicSong> =
        call("getTopSongs", mapOf("artist" to artist, "count" to "$count")).topSongs?.song.orEmpty()

    enum class AlbumListType(val value: String) {
        Newest("newest"), Recent("recent"), Frequent("frequent"), Random("random"),
        AlphabeticalByName("alphabeticalByName"), Starred("starred"),
    }

    override suspend fun albums(type: AlbumListType, size: Int, offset: Int): List<SubsonicAlbum> =
        call("getAlbumList2", mapOf("type" to type.value, "size" to "$size", "offset" to "$offset")).albumList2?.album.orEmpty()

    /**
     * Artists, albums and songs matching a query. An empty query matches everything on most
     * servers, which is how a whole library is paged through.
     */
    override suspend fun search(query: String, artists: Int, albums: Int, songs: Int, songOffset: Int): SubsonicSearchResult {
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
    override suspend fun similarSongs(songId: String, count: Int): List<SubsonicSong> =
        call("getSimilarSongs2", mapOf("id" to songId, "count" to "$count")).similarSongs2?.song.orEmpty()

    override suspend fun randomSongs(count: Int): List<SubsonicSong> =
        call("getRandomSongs", mapOf("size" to "$count")).randomSongs?.song.orEmpty()

    override suspend fun star(songId: String) { call("star", mapOf("id" to songId)) }
    override suspend fun unstar(songId: String) { call("unstar", mapOf("id" to songId)) }

    /** Every song on the server, a page at a time, up to [limit]. */
    override suspend fun allSongs(limit: Int, pageSize: Int): List<SubsonicSong> {
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

    /** Asks once more at an https address when the server refuses plain http from here (code 50). */
    private suspend fun call(endpoint: String, parameters: Map<String, String> = emptyMap()): SubsonicBody = try {
        send(endpoint, parameters, provenBase())
    } catch (error: SubsonicError.HttpsRequired) {
        val secure = endpoints?.requireHttps()?.takeIf { it.isHttps } ?: throw error
        send(endpoint, parameters, secure)
    }

    private suspend fun send(endpoint: String, parameters: Map<String, String>, base: HttpUrl): SubsonicBody {
        val (status, body) = try {
            transport.get(url(endpoint, parameters, base = base)).also { endpoints?.recordSuccess() }
        } catch (error: IOException) {
            endpoints?.recordFailure()
            throw SubsonicError.Unreachable(error)
        } catch (error: IllegalArgumentException) {
            throw SubsonicError.Unreachable(error)
        }
        return decode(body, status)
    }

    companion object {
        /** 1.16.1 is the last Subsonic release; OpenSubsonic servers answer to it too. */
        const val API_VERSION = "1.16.1"
        const val CLIENT = "Tracks"

        private val random = SecureRandom()
        private const val SALT_LETTERS = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun makeSalt(): String = buildString { repeat(12) { append(SALT_LETTERS[random.nextInt(SALT_LETTERS.length)]) } }

        /** md5(password + salt), in lower-case hex, as the API asks. */
        fun token(password: String, salt: String): String =
            MessageDigest.getInstance("MD5").digest((password + salt).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Whether a Subsonic server answers at [base], asked without credentials: the API's
         * own envelope comes back (most often "required parameter missing"), where a captive
         * portal's login page or a dead address doesn't. The endpoints' probe for Subsonic.
         */
        suspend fun answers(base: HttpUrl, transport: SubsonicTransport): Boolean = runCatching {
            val (_, body) = transport.get(unsigned(base, "ping"))
            json.decodeFromString(SubsonicEnvelope.serializer(), body).body.status.isNotEmpty()
        }.getOrDefault(false)

        /**
         * Whether the server takes an OpenSubsonic API key instead of a password
         * (`apiKeyAuthentication`). Asked without credentials, as the extension allows.
         */
        suspend fun acceptsApiKey(base: HttpUrl, transport: SubsonicTransport): Boolean = runCatching {
            val (_, body) = transport.get(unsigned(base, "getOpenSubsonicExtensions"))
            val root = json.parseToJsonElement(body) as JsonObject
            val response = root["subsonic-response"] as JsonObject
            (response["openSubsonicExtensions"] as? JsonArray).orEmpty().any {
                (it as? JsonObject)?.get("name")?.jsonPrimitive?.content == "apiKeyAuthentication"
            }
        }.getOrDefault(false)

        private fun unsigned(base: HttpUrl, endpoint: String) = base.newBuilder()
            .addPathSegment("rest").addPathSegment(endpoint)
            .addQueryParameter("v", API_VERSION).addQueryParameter("c", CLIENT).addQueryParameter("f", "json")
            .build()

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
                    44 -> SubsonicError.WrongKey
                    42, 43 -> SubsonicError.AuthUnsupported
                    50 -> SubsonicError.HttpsRequired
                    20, 30 -> SubsonicError.Incompatible
                    70 -> SubsonicError.NotFound
                    else -> SubsonicError.Server(code, body.error?.message.orEmpty())
                }
            }
            return body
        }
    }
}


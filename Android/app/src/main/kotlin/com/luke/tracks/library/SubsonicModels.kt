package com.luke.tracks.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * One of your music servers: one that speaks the Subsonic API (Navidrome, Gonic, Airsonic,
 * Ampache, LMS and others) or Trackside. Stored in settings as JSON; the password, API key or
 * device key is kept apart, encrypted ([secret]).
 */
@Serializable
data class SubsonicServer(
    val id: String,
    val name: String,
    /** The address it was added with, without a trailing slash: "https://music.example.com". */
    val url: String,
    val username: String,
    /** The password, API key or Trackside device key, sealed with a Keystore key. Never the secret itself. */
    val secret: String = "",
    val kind: ServerKind = ServerKind.Subsonic,
    val auth: SubsonicAuth = SubsonicAuth.Password,
    /** Where else it answers: a Tailscale name, a public address. Trackside lists its own. */
    val addresses: List<StoredAddress> = emptyList(),
    /** Trackside: the server's own id, which every address has to prove. */
    val serverUuid: String? = null,
    /** Trackside: the id the server gave this phone, for revoking it. */
    val deviceId: String? = null,
    /** Trackside: the version (ETag) of [addresses], to ask only for changes. */
    val addressesVersion: String? = null,
    /** Certificates you chose to trust, by "host:port": SHA-256 of each, in hex. */
    val pins: Map<String, String> = emptyMap(),
) {
    val isTrackside: Boolean get() = kind == ServerKind.Trackside
}

@Serializable
enum class ServerKind {
    @SerialName("subsonic") Subsonic,
    @SerialName("trackside") Trackside,
}

/** How a Subsonic server is signed in to: a salted token from the password, or an OpenSubsonic API key. */
@Serializable
enum class SubsonicAuth {
    @SerialName("password") Password,
    @SerialName("apiKey") ApiKey,
}

@Serializable
data class StoredAddress(
    val url: String,
    /** "lan", "tailnet", "public" or "unknown". */
    val kind: String = "unknown",
    /** A self-signed certificate's SHA-256, as the server itself announced it. */
    val fingerprint: String? = null,
)

/** An id some servers send as a number and others as a string. */
object LenientString : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && !element.isString) JsonPrimitive(element.content) else element
}

private typealias Id = @Serializable(with = LenientString::class) String

@Serializable
data class SubsonicArtist(
    val id: Id,
    val name: String = "",
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val artistImageUrl: String? = null,
)

@Serializable
data class SubsonicAlbum(
    val id: Id,
    val name: String = "",
    val artist: String? = null,
    val artistId: Id? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val created: String? = null,
)

@Serializable
data class SubsonicSong(
    val id: Id,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val albumId: Id? = null,
    val artistId: Id? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** Seconds. */
    val duration: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int? = null,
    val coverArt: String? = null,
    /** OpenSubsonic: the album's artist, where the server knows it. */
    val displayAlbumArtist: String? = null,
    val created: String? = null,
    /** Octo: a song it found for you elsewhere rather than one in the library. */
    val isExternal: Boolean? = null,
)

data class SubsonicSearchResult(
    val artists: List<SubsonicArtist>,
    val albums: List<SubsonicAlbum>,
    val songs: List<SubsonicSong>,
)

/** The envelope every answer comes in: `{"subsonic-response": {...}}`. */
@Serializable
internal class SubsonicEnvelope(@SerialName("subsonic-response") val body: SubsonicBody)

@Serializable
internal class SubsonicBody(
    val status: String,
    /** OpenSubsonic: what the server is ("navidrome", "octo"). */
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val error: ErrorBody? = null,
    val artists: ArtistsIndex? = null,
    val album: AlbumWithSongs? = null,
    val albumList2: AlbumList? = null,
    val searchResult3: SearchResult? = null,
    val similarSongs2: SongList? = null,
    val randomSongs: SongList? = null,
    val artist: ArtistWithAlbums? = null,
    val song: SubsonicSong? = null,
    val topSongs: SongList? = null,
) {
    @Serializable class ErrorBody(val code: Int = 0, val message: String? = null)
    @Serializable class ArtistsIndex(val index: List<Index> = emptyList()) {
        @Serializable class Index(val artist: List<SubsonicArtist> = emptyList())
    }
    @Serializable class AlbumWithSongs(
        val id: Id,
        val name: String = "",
        val artist: String? = null,
        val artistId: Id? = null,
        val coverArt: String? = null,
        val songCount: Int? = null,
        val year: Int? = null,
        val genre: String? = null,
        val song: List<SubsonicSong> = emptyList(),
    ) {
        fun album() = SubsonicAlbum(id, name, artist, artistId, coverArt, songCount, null, year, genre)
    }
    @Serializable class ArtistWithAlbums(
        val id: Id,
        val name: String = "",
        val coverArt: String? = null,
        val artistImageUrl: String? = null,
        val album: List<SubsonicAlbum> = emptyList(),
    )
    @Serializable class AlbumList(val album: List<SubsonicAlbum> = emptyList())
    @Serializable class SongList(val song: List<SubsonicSong> = emptyList())
    @Serializable class SearchResult(
        val artist: List<SubsonicArtist> = emptyList(),
        val album: List<SubsonicAlbum> = emptyList(),
        val song: List<SubsonicSong> = emptyList(),
    )
}

/** Why a server said no. */
sealed class SubsonicError(message: String) : Exception(message) {
    /** Username or password wrong (Subsonic codes 40 and 41). */
    data object WrongCredentials : SubsonicError("The username or password isn't right.")
    /** The API key was refused (OpenSubsonic code 44). */
    data object WrongKey : SubsonicError("The server didn't accept that API key.")
    /** The server refused credentials over plain http from outside its network (Trackside's code 50). */
    data object HttpsRequired : SubsonicError("Your server only signs in over https from outside your home network. Use its https address.")
    /** The server doesn't take this way of signing in (OpenSubsonic codes 42 and 43). */
    data object AuthUnsupported : SubsonicError("This server doesn't take that way of signing in.")
    /** The server is too old for what was asked (codes 20 and 30). */
    data object Incompatible : SubsonicError("This server is too old for Tracks. Updating it should help.")
    data object NotFound : SubsonicError("The server couldn't find that.")
    class Server(val code: Int, detail: String) : SubsonicError(detail.ifBlank { "The server had a problem." })
    /** Not a Subsonic answer at all: the wrong address, most likely. */
    data object NotSubsonic : SubsonicError("That address doesn't look like a music server. Check it and the port.")
    class Http(val status: Int) : SubsonicError("The server answered with an error.")
    class Unreachable(cause: Throwable) : SubsonicError("Couldn't reach the server. Check the address and that you're on the right network.") {
        init { initCause(cause) }
    }
}

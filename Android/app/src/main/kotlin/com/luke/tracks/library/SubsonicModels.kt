package com.luke.tracks.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * A music server that speaks the Subsonic API: Navidrome, Gonic, Airsonic, Ampache, LMS and
 * others. Stored in settings as JSON; the password is kept apart, encrypted ([secret]).
 */
@Serializable
data class SubsonicServer(
    val id: String,
    val name: String,
    /** The base address, without a trailing slash: "https://music.example.com". */
    val url: String,
    val username: String,
    /**
     * Mixed into the password for the token cover addresses carry, so those addresses stay
     * the same between launches and their images stay cached. Every other request gets a salt
     * of its own.
     */
    val salt: String,
    /** The password, sealed with a key in the Android Keystore. Never the password itself. */
    val secret: String = "",
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
    val error: ErrorBody? = null,
    val artists: ArtistsIndex? = null,
    val album: AlbumWithSongs? = null,
    val albumList2: AlbumList? = null,
    val searchResult3: SearchResult? = null,
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
    @Serializable class AlbumList(val album: List<SubsonicAlbum> = emptyList())
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

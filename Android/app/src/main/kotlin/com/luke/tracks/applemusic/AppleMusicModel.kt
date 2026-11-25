package com.luke.tracks.applemusic

import android.net.Uri
import com.luke.tracks.playback.PlayableSong
import kotlinx.serialization.Serializable

/** A song from the person's Apple Music library or the catalog. */
@Serializable
data class AppleMusicSong(
    /** The library's id ("i.…") or the catalog's. */
    val id: String,
    /** What MusicKit plays: null for a library song that isn't in the catalog (an upload), which can't play here. */
    val catalogId: String?,
    val title: String,
    val artistName: String,
    val albumTitle: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** Apple's artwork address, with "{w}" and "{h}" to fill in. */
    val artworkTemplate: String? = null,
) {
    val playable: Boolean get() = catalogId != null && (durationMs ?: 0) > 0

    fun toPlayableSong(identity: (title: String, artist: String) -> String): PlayableSong = PlayableSong(
        mediaId = catalogId?.let(AppleMusicUris::mediaId) ?: AppleMusicUris.libraryMediaId(id),
        uri = catalogId?.let { AppleMusicUris.song(it, durationMs ?: 0) } ?: Uri.EMPTY,
        title = title,
        artistName = artistName,
        albumTitle = albumTitle,
        artworkUri = artwork(artworkTemplate, SONG_ART)?.let(Uri::parse),
        durationMs = durationMs,
        identity = identity(title, artistName),
        trackNumber = trackNumber,
        year = year,
        genre = genre,
    )
}

/** An album or playlist, from the library or the catalog. */
data class AppleMusicCollection(
    val id: String,
    val kind: Kind,
    val title: String,
    val subtitle: String?,
    val year: Int? = null,
    val artworkTemplate: String? = null,
    /** Its page on music.apple.com, for the catalog's. */
    val url: String? = null,
) {
    enum class Kind { LibraryAlbum, LibraryPlaylist, Album, Playlist }

    val isAlbum: Boolean get() = kind == Kind.LibraryAlbum || kind == Kind.Album

    fun artworkUrl(size: Int = COLLECTION_ART): String? = artwork(artworkTemplate, size)
}

/** Apple's artwork address at [size] pixels square. */
fun artwork(template: String?, size: Int): String? =
    template?.takeIf { it.startsWith("https://") }?.replace("{w}", size.toString())?.replace("{h}", size.toString())?.replace("{f}", "jpg")

private const val SONG_ART = 600
private const val COLLECTION_ART = 600

/**
 * How Apple Music songs travel through the app. A song's media id is "applemusic:song:<catalog
 * id>", the same for its library and catalog copies; the address the player opens is
 * "applemusic://song/<catalog id>?ms=<length>", since the stand-in audio needs its length.
 */
object AppleMusicUris {
    const val SCHEME = "applemusic"
    private const val SONG_PREFIX = "applemusic:song:"
    private const val LIBRARY_PREFIX = "applemusic:library:"

    fun mediaId(catalogId: String) = SONG_PREFIX + catalogId

    fun libraryMediaId(id: String) = LIBRARY_PREFIX + id

    fun song(catalogId: String, durationMs: Long): Uri =
        Uri.Builder().scheme(SCHEME).authority("song").appendPath(catalogId).appendQueryParameter("ms", durationMs.toString()).build()

    fun isAppleMusic(mediaId: String?): Boolean = mediaId?.startsWith("$SCHEME:") == true

    /** The catalog id in a media id or player address; null for anything else. */
    fun catalogId(value: String): String? = when {
        value.startsWith(SONG_PREFIX) -> value.removePrefix(SONG_PREFIX).takeIf { it.isNotEmpty() }
        value.startsWith("$SCHEME://song/") -> Uri.parse(value).lastPathSegment?.takeIf { it.isNotEmpty() }
        else -> null
    }

    fun durationMs(uri: Uri): Long? =
        uri.takeIf { it.scheme == SCHEME && it.isHierarchical }?.getQueryParameter("ms")?.toLongOrNull()?.takeIf { it > 0 }

    /** The song's page on music.apple.com, for Open in Apple Music. */
    fun webPage(catalogId: String, storefront: String?): String = "https://music.apple.com/${storefront ?: "us"}/song/$catalogId"
}

package com.luke.tracks.library.trackside

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Streaming services connected to a Trackside server (the wire contract's section 2). Every
// enum stays a String and every capability list keeps names this build doesn't know; refs are
// opaque and case-sensitive, so they are never parsed, built or changed here.

/** A streaming service the server can search and play, such as "Spotify". Its name is data, never source. */
@Serializable
data class TracksideCatalogService(
    val id: String,
    val name: String = "",
    /** The service's kind ("spotify", "tidal", ...), for its glyph. Open. */
    @SerialName("logo_key") val logoKey: String? = null,
    val capabilities: List<String> = emptyList(),
    val account: Account = Account(),
    /** lossless, high or standard: the best it plays through the server. */
    val quality: String? = null,
) {
    @Serializable
    data class Account(
        /** ok, signed_out, error, unavailable. */
        val state: String = "unavailable",
        /** English for logs, never shown. */
        val detail: String? = null,
    )

    fun can(capability: String): Boolean = capability in capabilities

    val isSignedIn: Boolean get() = account.state == "ok"

    /** Searchable now: it searches, and its account works. */
    val searches: Boolean get() = isSignedIn && can(CatalogCapability.SEARCH)
}

object CatalogCapability {
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val TOP_TRACKS = "top_tracks"
    const val SIMILAR = "similar"
    const val RADIO = "radio"
    const val RECOMMENDATIONS = "recommendations"
}

/** `GET /catalog/services`. */
@Serializable
data class TracksideCatalogServices(
    val services: List<TracksideCatalogService> = emptyList(),
    /** ok, connecting, unavailable. */
    val state: String = "ok",
    val since: String? = null,
)

/** A name and, where the service knows it, its artist page. */
@Serializable
data class TracksideCatalogArtistRef(val name: String = "", val ref: String? = null)

/** A song on a streaming service. */
@Serializable
data class TracksideCatalogTrack(
    val ref: String,
    @SerialName("service_id") val serviceId: String = "",
    val title: String = "",
    val artist: String = "",
    val artists: List<TracksideCatalogArtistRef>? = null,
    val album: String? = null,
    @SerialName("album_ref") val albumRef: String? = null,
    @SerialName("artwork_id") val artworkId: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** clean, explicit, unknown. */
    val explicit: String? = null,
    val isrc: String? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    val quality: String? = null,
    @SerialName("song_key") val songKey: String? = null,
    /** A song in your library with the same key: play that copy instead. */
    @SerialName("song_id") val songId: String? = null,
    /** Sent only as false, when the service says it can't play. */
    val playable: Boolean? = null,
    val kind: String = "track",
) {
    val canPlay: Boolean get() = playable != false
    val isExplicit: Boolean get() = explicit == "explicit"
}

/**
 * An album, artist, playlist or radio on a streaming service: one shape for all four, as
 * their fields overlap and every one but [kind], [ref] and [name] may be left out.
 */
@Serializable
data class TracksideCatalogItem(
    val kind: String = "album",
    val ref: String,
    @SerialName("service_id") val serviceId: String = "",
    val name: String = "",
    /** An album's artist. */
    val artist: String? = null,
    val artists: List<TracksideCatalogArtistRef>? = null,
    val year: Int? = null,
    @SerialName("artwork_id") val artworkId: String? = null,
    @SerialName("track_count") val trackCount: Int? = null,
    @SerialName("album_key") val albumKey: String? = null,
    /** An artist's identity, as library artists have. */
    val identity: String? = null,
    /** A playlist's owner. */
    val owner: String? = null,
    val editable: Boolean? = null,
) {
    enum class Kind { Album, Artist, Playlist, Radio, Unknown }

    val kindValue: Kind get() = when (kind) {
        "album" -> Kind.Album
        "artist" -> Kind.Artist
        "playlist" -> Kind.Playlist
        "radio" -> Kind.Radio
        else -> Kind.Unknown
    }
}

/** A search on the streaming services: `GET /catalog/search`, or the `catalog` section of `/search`. */
@Serializable
data class TracksideCatalogSearch(
    val tracks: Section<TracksideCatalogTrack>? = null,
    val albums: Section<TracksideCatalogItem>? = null,
    val artists: Section<TracksideCatalogItem>? = null,
    val playlists: Section<TracksideCatalogItem>? = null,
    val radios: Section<TracksideCatalogItem>? = null,
    /** False when a service didn't answer in time. */
    val complete: Boolean = true,
    val services: List<ServiceState> = emptyList(),
    /** Left out when fine; connecting or unavailable when the server can't reach its services. */
    val state: String? = null,
) {
    @Serializable
    data class Section<T>(val items: List<T> = emptyList())

    @Serializable
    data class ServiceState(
        val id: String,
        /** ok, timed_out, failed, signed_out. */
        val state: String = "ok",
    )

    val isUnavailable: Boolean get() = state != null && state != "ok"

    val isEmpty: Boolean
        get() = tracks?.items.isNullOrEmpty() && albums?.items.isNullOrEmpty() && artists?.items.isNullOrEmpty() &&
            playlists?.items.isNullOrEmpty() && radios?.items.isNullOrEmpty()
}

/**
 * `GET /catalog/items/{ref}`: exactly one of [track], [album], [artist], [playlist] and
 * [radio], with what goes with it.
 */
@Serializable
data class TracksideCatalogPage(
    val track: TracksideCatalogTrack? = null,
    val album: TracksideCatalogItem? = null,
    val artist: TracksideCatalogItem? = null,
    val playlist: TracksideCatalogItem? = null,
    val radio: TracksideCatalogItem? = null,
    /** An album's in disc and track order, or a page of a playlist's. */
    val tracks: List<TracksideCatalogTrack> = emptyList(),
    @SerialName("top_tracks") val topTracks: List<TracksideCatalogTrack> = emptyList(),
    val albums: List<TracksideCatalogItem> = emptyList(),
    /** More of a playlist's tracks. */
    @SerialName("next_cursor") val nextCursor: String? = null,
) {
    /** The album, artist, playlist or radio this page is for. */
    val item: TracksideCatalogItem? get() = album ?: artist ?: playlist ?: radio
}

/** `GET /catalog/services/{id}/library`: what you saved on a service, newest first. */
@Serializable
data class TracksideCatalogLibrary<T>(
    val items: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
)

/**
 * `GET /catalog/recommendations`: the services' own rows of picks, in Music Assistant's order.
 * [complete] is false when a service didn't answer in time; the rows that did are still here.
 */
@Serializable
data class TracksideCatalogRecommendations(
    val complete: Boolean = true,
    val services: List<TracksideCatalogSearch.ServiceState> = emptyList(),
    val shelves: List<TracksideCatalogShelf> = emptyList(),
)

/** One of a service's rows: never empty, each ref at most once. */
@Serializable
data class TracksideCatalogShelf(
    /** Stays the same while Music Assistant keeps the row. */
    val id: String,
    @SerialName("service_id") val serviceId: String,
    val title: String,
    val subtitle: String? = null,
    /** Left out for a row of tiles; `timeline` for recently played. Open. */
    val type: String? = null,
    @Serializable(with = CatalogEntryListSerializer::class)
    val items: List<TracksideCatalogEntry> = emptyList(),
) {
    val isTimeline: Boolean get() = type == "timeline"
}

/** A song, or an album, artist, playlist or station, told apart by `kind`. */
sealed interface TracksideCatalogEntry {
    val ref: String
    val serviceId: String
    val artworkId: String?

    data class Track(val track: TracksideCatalogTrack) : TracksideCatalogEntry {
        override val ref get() = track.ref
        override val serviceId get() = track.serviceId
        override val artworkId get() = track.artworkId
    }

    data class Item(val item: TracksideCatalogItem) : TracksideCatalogEntry {
        override val ref get() = item.ref
        override val serviceId get() = item.serviceId
        override val artworkId get() = item.artworkId
    }
}

/** Reads each entry by its kind; one of a kind this build doesn't know, or that doesn't read, is left out. */
internal object CatalogEntryListSerializer : KSerializer<List<TracksideCatalogEntry>> {
    override val descriptor: SerialDescriptor = ListSerializer(JsonElement.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<TracksideCatalogEntry> {
        val input = decoder as? JsonDecoder ?: error("Recommendations are read from JSON")
        val array = input.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val kind = ((element as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
            runCatching {
                when (kind) {
                    "track" -> TracksideCatalogEntry.Track(input.json.decodeFromJsonElement(TracksideCatalogTrack.serializer(), element))
                    "album", "artist", "playlist", "radio" -> TracksideCatalogEntry.Item(input.json.decodeFromJsonElement(TracksideCatalogItem.serializer(), element))
                    else -> null
                }
            }.getOrNull()
        }
    }

    override fun serialize(encoder: Encoder, value: List<TracksideCatalogEntry>) {
        error("Recommendations are only read")
    }
}

/** The event saying a service was added, removed, or signed in or out. Re-read the list. */
object CatalogEvents {
    const val SERVICES = "catalog_services"

    @Serializable
    data class Services(val state: String = "ok")
}

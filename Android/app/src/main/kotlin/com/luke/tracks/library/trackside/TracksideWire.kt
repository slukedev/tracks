package com.luke.tracks.library.trackside

import com.luke.tracks.library.SubsonicAlbum
import com.luke.tracks.library.SubsonicArtist
import com.luke.tracks.library.SubsonicSong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

// What Trackside's native API sends and takes, as tracks-companion's docs/api.md writes it and
// the iPhone app reads it. Fields the server leaves out are null; fields and values this
// build doesn't know are ignored, so a newer server still reads.

internal val TracksideJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** RFC 3339 with or without milliseconds, or null for anything else. */
internal fun instant(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }

@Serializable
data class TracksideFormat(
    val container: String = "",
    val codec: String = "",
    @SerialName("content_type") val contentType: String? = null,
    val lossless: Boolean? = null,
    @SerialName("bitrate_kbps") val bitrateKbps: Int? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    @SerialName("bit_depth") val bitDepth: Int? = null,
    val size: Long? = null,
)

@Serializable
data class TracksideSong(
    val id: String,
    val title: String = "",
    val artist: String = "",
    @SerialName("artist_id") val artistId: String? = null,
    val album: String? = null,
    @SerialName("album_id") val albumId: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    val year: Int? = null,
    val genres: List<String>? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val format: TracksideFormat? = null,
    @SerialName("artwork_id") val artworkId: String? = null,
    @SerialName("song_key") val songKey: String? = null,
    @SerialName("added_at") val addedAt: String? = null,
    /** The file is gone but the server hasn't given up on it yet. It can't be played. */
    val missing: Boolean? = null,
) {
    /** The song in the shape the rest of Tracks reads server songs in. */
    val subsonic: SubsonicSong
        get() = SubsonicSong(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumId = albumId,
            artistId = artistId,
            track = trackNumber,
            discNumber = discNumber,
            year = year,
            genre = genres?.firstOrNull(),
            duration = durationMs?.let { ((it + 500) / 1_000).toInt() },
            suffix = format?.container,
            contentType = format?.contentType,
            bitRate = format?.bitrateKbps,
            coverArt = artworkId?.takeIf { it.isNotEmpty() },
            displayAlbumArtist = albumArtist,
            created = addedAt,
        )
}

@Serializable
data class TracksideAlbum(
    val id: String,
    val name: String = "",
    @SerialName("album_artist") val albumArtist: String? = null,
    @SerialName("artist_id") val artistId: String? = null,
    val year: Int? = null,
    val genres: List<String>? = null,
    @SerialName("song_count") val songCount: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("artwork_id") val artworkId: String? = null,
    @SerialName("added_at") val addedAt: String? = null,
) {
    val subsonic: SubsonicAlbum
        get() = SubsonicAlbum(
            id = id, name = name, artist = albumArtist, artistId = artistId, coverArt = artworkId, songCount = songCount,
            duration = durationMs?.let { ((it + 500) / 1_000).toInt() }, year = year, genre = genres?.firstOrNull(), created = addedAt,
        )
}

@Serializable
data class TracksideArtist(
    val id: String,
    val name: String = "",
    val identity: String? = null,
    @SerialName("album_count") val albumCount: Int? = null,
    @SerialName("artwork_id") val artworkId: String? = null,
) {
    val subsonic: SubsonicArtist get() = SubsonicArtist(id, name, albumCount, artworkId)
}

@Serializable
data class TracksidePing(
    @SerialName("server_id") val serverId: String,
    val time: String? = null,
    /** The answer to a challenge, when one was asked ([TracksideProof]). */
    val proof: String? = null,
)

/** `GET /api/v1/capabilities`, which tells Trackside from other servers before signing in. */
@Serializable
data class TracksideCapabilities(
    val product: String = "",
    @SerialName("server_id") val serverId: String? = null,
    val name: String? = null,
    val version: String? = null,
    @SerialName("setup_required") val setupRequired: Boolean? = null,
    val auth: Auth? = null,
    val media: Media? = null,
    val features: Features? = null,
    val cloud: Cloud? = null,
) {
    val isTrackside: Boolean get() = product == "trackside"

    /** Whether the server keeps Trackside Cloud for its users. */
    val offersCloud: Boolean get() = features?.cloud == true || cloud != null

    @Serializable
    data class Media(
        val hls: Boolean? = null,
        val transcoding: Boolean? = null,
        val profiles: List<String>? = null,
        @SerialName("suggested_cellular_profile") val suggestedCellularProfile: String? = null,
    )

    @Serializable
    data class Features(val cloud: Boolean? = null, val events: Boolean? = null)

    @Serializable
    data class Cloud(
        val entities: List<String> = emptyList(),
        val epoch: String? = null,
        @SerialName("max_batch") val maxBatch: Int? = null,
        @SerialName("max_page") val maxPage: Int? = null,
    )

    @Serializable
    data class Auth(
        @SerialName("password_login") val passwordLogin: Boolean? = null,
        val pairing: Boolean? = null,
        @SerialName("pair_requests") val pairRequests: Boolean? = null,
    )
}

/** One of the addresses a server can be reached at. */
@Serializable
data class TracksideServerUrl(
    val url: String,
    /** "lan", "tailnet", "public", or one this build doesn't know. */
    val kind: String = "unknown",
    val tls: Boolean? = null,
    /** The SHA-256 of a self-signed certificate, for pinning. */
    val fingerprint: String? = null,
)

@Serializable
data class TracksideServerInfo(
    @SerialName("server_id") val serverId: String,
    val name: String? = null,
    val version: String? = null,
    val urls: List<TracksideServerUrl>? = null,
)

/** This device, as Tracks introduces it when signing in or pairing. */
@Serializable
data class TracksideDeviceInfo(
    /** Stays the same for this install, so signing in again replaces this device's key. */
    @SerialName("client_device_id") val clientDeviceId: String,
    val name: String,
    val platform: String = "android",
    val model: String? = null,
    @SerialName("app_name") val appName: String? = "Tracks",
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class TracksideDevice(val id: String, val name: String = "")

@Serializable
data class TracksideUser(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val role: String? = null,
    val permissions: Permissions? = null,
) {
    @Serializable
    data class Permissions(
        @SerialName("can_download") val canDownload: Boolean? = null,
        @SerialName("can_upload") val canUpload: Boolean? = null,
        /** Whether this account may ask the server to get songs it doesn't have. */
        @SerialName("can_request") val canRequest: Boolean? = null,
    )
}

/** What media addresses carry as `mt`: short-lived, so one that leaks soon stops working. */
@Serializable
data class TracksideMediaToken(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    /** A day before it runs out, so a song started late at night doesn't stop partway. */
    fun needsRenewal(now: Instant = Instant.now()): Boolean =
        instant(expiresAt)?.let { it.epochSecond - now.epochSecond < 24 * 3600 } ?: true
}

/** What every way of signing in ends with: a key for this device, and where the server is. */
@Serializable
data class TracksidePairResult(
    @SerialName("api_key") val apiKey: String,
    val device: TracksideDevice,
    val user: TracksideUser,
    @SerialName("media_token") val mediaToken: TracksideMediaToken? = null,
    val server: TracksideServerInfo,
)

@Serializable
internal data class TracksideLoginRequest(val username: String, val password: String, val device: TracksideDeviceInfo)

@Serializable
internal data class TracksideClaimRequest(val code: String, val device: TracksideDeviceInfo)

@Serializable
internal data class TracksidePairRequestBody(val device: TracksideDeviceInfo)

/** A pairing this device asked for: show [code] and poll until someone approves it. */
@Serializable
data class TracksidePairRequest(
    val id: String,
    /** Six digits, as the dashboard asks for them: "481 207". */
    val code: String,
    @SerialName("poll_secret") val pollSecret: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("poll_interval_ms") val pollIntervalMs: Long? = null,
)

@Serializable
data class TracksidePairPoll(
    /** "pending", "approved", "expired". */
    val state: String,
    val result: TracksidePairResult? = null,
)

/** `GET /api/v1/server/urls`: where the server can be reached now. The ETag is [version]. */
@Serializable
data class TracksideServerUrls(
    @SerialName("server_id") val serverId: String,
    val urls: List<TracksideServerUrl> = emptyList(),
    val version: String? = null,
)

/** The `server_urls` event: the list changed, so ask for it again. */
@Serializable
data class TracksideServerUrlsEvent(val version: String? = null)

@Serializable
data class TracksideMe(
    val user: TracksideUser,
    val device: TracksideDevice? = null,
    val server: TracksideServerInfo,
    val features: Features? = null,
) {
    /**
     * Whether this phone can ask the server to get songs it doesn't have. The server sends
     * `features.acquisition` only when acquisition is on with a source, the account may request
     * songs, and this credential has the `request` scope; otherwise the key is left out.
     */
    val offersAcquisition: Boolean get() = features?.acquisition != null

    /** Whether the account may request songs at all, as the server's admin set it. */
    val canRequest: Boolean get() = user.permissions?.canRequest != false

    @Serializable
    data class Features(
        val acquisition: Acquisition? = null,
    )

    @Serializable
    data class Acquisition(
        @SerialName("instant_play") val instantPlay: Boolean? = null,
        @SerialName("external_play") val externalPlay: Boolean? = null,
    )
}

@Serializable
data class TracksideAlbumPage(val album: TracksideAlbum, val songs: List<TracksideSong> = emptyList())

@Serializable
data class TracksideArtistPage(
    val artist: TracksideArtist,
    val albums: List<TracksideAlbum>? = null,
    @SerialName("top_songs") val topSongs: List<TracksideSong>? = null,
)

@Serializable
internal data class TracksideSongList(val songs: List<TracksideSong> = emptyList())

/** A song Last.fm suggests that this server doesn't have. */
@Serializable
data class TracksideSuggestion(
    val title: String,
    val artist: String,
    /** The server's cover for it, through `GET /art/{id}`; left out when it has none. */
    @SerialName("artwork_id") val artworkId: String? = null,
)

/** Songs the server picked from what you play, and ones it suggests that it doesn't have. */
@Serializable
data class TracksideSuggestedSongs(val songs: List<TracksideSong> = emptyList(), val external: List<TracksideSuggestion>? = null)

@Serializable
internal data class TracksideAlbumList(val albums: List<TracksideAlbum> = emptyList(), @SerialName("next_cursor") val nextCursor: String? = null)

@Serializable
data class TracksideSearchResults(
    val songs: Section<TracksideSong>? = null,
    val albums: Section<TracksideAlbum>? = null,
    val artists: Section<TracksideArtist>? = null,
    /** With `types=external`, where the server gets songs: ones it could get, never in the library. */
    val external: TracksideAcquisitionSearch? = null,
) {
    @Serializable
    data class Section<T>(val items: List<T> = emptyList(), @SerialName("next_cursor") val nextCursor: String? = null)
}

/** A song the server could get, from a search for one it doesn't have. */
@Serializable
data class TracksideCandidate(
    val token: String = "",
    val title: String,
    val artist: String,
    /** The server's cover for it, through `GET /art/{id}`; left out when it has none. */
    @SerialName("artwork_id") val artworkId: String? = null,
    val album: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** "lossless", "high" or "standard". */
    val quality: String? = null,
    val instant: Boolean? = null,
    /** "exact" or "close". */
    val match: String? = null,
)

/** `POST /api/v1/acquisitions/search`, and the external part of a search. */
@Serializable
data class TracksideAcquisitionSearch(
    @SerialName("search_id") val searchId: String? = null,
    val complete: Boolean? = null,
    @SerialName("in_library") val inLibrary: List<TracksideSong>? = null,
    val candidates: List<TracksideCandidate> = emptyList(),
)

/** What to ask the server to get: only the title and artist are needed. */
@Serializable
data class TracksideAcquisitionRequest(
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** A candidate's token, tried first. */
    val candidate: String? = null,
    @SerialName("allow_versions") val allowVersions: Boolean = false,
    val instant: Boolean = false,
)

/** A song asked of the server, and how getting it is going (section 15.1). */
@Serializable
data class TracksideAcquisition(
    val id: String,
    /** queued, searching, downloading, verifying, importing, done, failed or cancelled. */
    val state: String,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val progress: Progress? = null,
    val quality: String? = null,
    /** The song's id in the library, final once it's done. */
    @SerialName("song_id") val songId: String? = null,
    /** It was in the library already. */
    val existing: Boolean? = null,
    /** Why it failed: no_match, quota_exceeded and the rest. */
    val failure: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** With instant play, where it streams from while it downloads, once bytes are flowing. */
    val stream: TracksideStream? = null,
) {
    @Serializable
    data class Progress(val bytes: Long = 0, val total: Long? = null)

    val isDone: Boolean get() = state == "done"
    val isFinished: Boolean get() = state == "done" || state == "failed" || state == "cancelled"
}

/** Where a song the server is getting, or playing without keeping, streams from: paths that take the media token. */
@Serializable
data class TracksideStream(val hls: String? = null, val progressive: String? = null)

/** `POST /api/v1/external/play`: a candidate's token, or a suggestion's title and artist. */
@Serializable
data class TracksideExternalPlayRequest(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val candidate: String? = null,
)

/**
 * A song the server plays without keeping it (section 15.6): where it streams from, or
 * `song_id` when your library has it after all.
 */
@Serializable
data class TracksideExternalPlay(
    val title: String = "",
    val artist: String = "",
    @SerialName("duration_ms") val durationMs: Long? = null,
    val stream: TracksideStream? = null,
    @SerialName("song_id") val songId: String? = null,
)

@Serializable
data class TracksideAcquisitionList(
    val acquisitions: List<TracksideAcquisition> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** A page of `GET /api/v1/sync`: a snapshot's, or the changes after a cursor. */
@Serializable
data class TracksideSyncPage(
    /** "snapshot" or "delta". */
    val mode: String,
    val complete: Boolean? = null,
    val libraries: List<Library>? = null,
    val artists: List<TracksideArtist>? = null,
    val songs: List<TracksideSong>? = null,
    val upserts: Upserts? = null,
    val deletes: Deletes? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
) {
    @Serializable
    data class Library(@SerialName("song_count") val songCount: Int? = null)

    @Serializable
    data class Upserts(val songs: List<TracksideSong>? = null, val artists: List<TracksideArtist>? = null)

    @Serializable
    data class Deletes(val songs: List<String>? = null, val artists: List<String>? = null)
}

@Serializable
data class TracksidePlaybackRequest(
    @SerialName("song_id") val songId: String,
    val network: String,
    val client: Client,
) {
    @Serializable
    data class Client(
        val direct: List<Direct>,
        val hls: Boolean,
        @SerialName("max_bitrate_kbps") val maxBitrateKbps: Int? = null,
        @SerialName("preferred_profile") val preferredProfile: String? = null,
        @SerialName("allow_original_fallback") val allowOriginalFallback: Boolean,
    )

    @Serializable
    data class Direct(val container: String, val codecs: List<String>)

    companion object {
        /**
         * What ExoPlayer plays as it is. No HLS: a whole file streams with range requests,
         * so a dropped connection resumes where it broke off.
         */
        fun exoPlayer(songId: String, maxBitRate: Int?, network: String, profile: String? = TracksideClient.profile(maxBitRate)) = TracksidePlaybackRequest(
            songId = songId,
            network = network,
            client = Client(
                direct = listOf(
                    Direct("flac", listOf("flac")),
                    Direct("m4a", listOf("aac", "alac")),
                    Direct("mp4", listOf("aac", "alac")),
                    Direct("mp3", listOf("mp3")),
                    Direct("ogg", listOf("vorbis", "opus", "flac")),
                    Direct("opus", listOf("opus")),
                    Direct("wav", listOf("pcm")),
                ),
                hls = false,
                maxBitrateKbps = maxBitRate,
                preferredProfile = profile,
                allowOriginalFallback = true,
            ),
        )
    }
}

@Serializable
data class TracksidePlaybackDecision(
    /** "direct", "file" or "hls". */
    val mode: String,
    /** A path on the server, resolved against the address in use. */
    val url: String,
    val reason: String? = null,
)

@Serializable
internal data class TracksideStarRequest(val at: String)

/** A play to tell the server about, idempotent by [clientListenId]: the play's own id. */
@Serializable
data class TracksideListen(
    @SerialName("client_listen_id") val clientListenId: String,
    @SerialName("song_id") val songId: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("played_ms") val playedMs: Long? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

@Serializable
internal data class TracksideListens(@SerialName("client_time") val clientTime: String, val listens: List<TracksideListen>)

@Serializable
data class TracksideListenResult(
    @SerialName("client_listen_id") val clientListenId: String,
    /** "accepted", "duplicate" or "rejected". */
    val status: String,
    val reason: String? = null,
)

@Serializable
internal data class TracksideListenResults(val results: List<TracksideListenResult> = emptyList())

@Serializable
internal data class TracksideNowPlaying(
    @SerialName("song_id") val songId: String,
    @SerialName("position_ms") val positionMs: Long,
    val state: String,
)

/** The body of every refusal: `{"error": {"code": ..., "message": ...}}`. */
@Serializable
internal data class TracksideErrorEnvelope(val error: Body) {
    @Serializable
    data class Body(val code: String, val message: String? = null, val details: Details? = null)

    @Serializable
    data class Details(
        val reason: String? = null,
        @SerialName("resets_at") val resetsAt: String? = null,
        val field: String? = null,
        /** With `https_required`: the server's https addresses, to try before any it listed earlier. */
        @SerialName("https_urls") val httpsUrls: List<TracksideServerUrl>? = null,
    )
}

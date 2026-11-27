package com.luke.tracks.library.trackside

import com.luke.tracks.net.Endpoint
import com.luke.tracks.net.Http
import com.luke.tracks.net.Nearby
import com.luke.tracks.net.ServerEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Why Trackside said no, from the stable code in its error body. */
sealed class TracksideError(message: String) : Exception(message) {
    /** This device's key was refused: revoked, or never valid. */
    data object Unauthenticated : TracksideError("Your server doesn't know this phone anymore. Pair it again.")
    data object MediaTokenExpired : TracksideError("The server asked for a fresh media token.")
    data object Forbidden : TracksideError("Your server didn't allow that.")
    data object NotFound : TracksideError("The server couldn't find that.")
    data object SyncReset : TracksideError("The server started its library over.")
    class RateLimited(val retryAfterSeconds: Long?) : TracksideError("The server is busy. Try again in a moment.")
    /** The account's limit on requested songs is used up until [resetsAt]. */
    class QuotaExceeded(val resetsAt: Instant?) : TracksideError("You've reached your server's limit on requested songs.")
    /** A song offered for getting isn't known anymore: search again. */
    data object CandidateExpired : TracksideError("That song isn't on offer anymore. Try again.")
    class Unavailable(val code: String, val retryAfterSeconds: Long?) : TracksideError("The server can't play that right now. Try again in a moment.")
    class Server(
        val status: Int,
        val code: String,
        detail: String,
        val field: String? = null,
        /** With `conflict`: why, such as `speaker_offline` or `not_playable`. */
        val reason: String? = null,
        /** With `unsupported`: the capability that's missing. */
        val capability: String? = null,
    ) : TracksideError(detail.ifBlank { "The server had a problem." })
    /** The server can't reach its speakers right now; it says so again when they're back. */
    class SpeakersUnavailable(val retryAfterSeconds: Long?) : TracksideError("Speakers aren't available right now.")
    class CatalogUnavailable(val retryAfterSeconds: Long?) : TracksideError("Music services aren't available right now.")
    /** Not an answer Tracks can read: not Trackside, most likely. */
    data object NotTrackside : TracksideError("That address doesn't look like a Trackside server.")
    class Http(val status: Int) : TracksideError("The server answered with an error.")
    /** None of the server's addresses proved it is the server, so the device key wasn't sent. */
    data object Unproven : TracksideError("Couldn't reach your server. Check that you're online.")
    /** Plain http to an address that isn't nearby: a password, code or key isn't sent there. */
    data object InsecureAddress : TracksideError("This address isn't encrypted. Use https, or your home network address.")
    /** The server refused a password or key sent over plain http from beyond the home network. */
    data object HttpsRequired : TracksideError("Your server only signs in over https from outside your home network. Use its https address.")
}

/** What came back: the status, the body and the one header that matters. */
data class TracksideAnswer(val status: Int, val body: String, val retryAfter: String? = null, val etag: String? = null, val date: String? = null)

/** Sends a request. OkHttp in the app; a stand-in in tests. */
fun interface TracksideTransport {
    suspend fun send(request: Request, timeoutMs: Long?): TracksideAnswer
}

class OkHttpTracksideTransport(private val http: OkHttpClient = Http.api) : TracksideTransport {
    override suspend fun send(request: Request, timeoutMs: Long?): TracksideAnswer = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        timeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        call.execute().use { TracksideAnswer(it.code, it.body.string(), it.header("Retry-After"), it.header("ETag"), it.header("Date")) }
    }
}

/**
 * Talks to one Trackside server with this device's key, at whichever of its addresses
 * [endpoints] picked: the same protocol as the iPhone app's client. The key goes only in
 * the `Authorization` header and only to an address that has proven it is the server; media
 * addresses carry a short-lived token instead.
 */
class TracksideClient(
    val endpoints: ServerEndpoints,
    private val apiKey: String,
    /** The id the server gave this device, for revoking it. */
    val deviceId: String?,
    @Volatile var mediaToken: TracksideMediaToken?,
    private val transport: TracksideTransport = OkHttpTracksideTransport(),
    private val now: () -> Instant = Instant::now,
    /** The server's own client for its event stream, which holds its pinned certificates. */
    val events: OkHttpClient? = null,
) {
    private val bearer get() = "Bearer $apiKey"

    // MARK: The user

    suspend fun me(): TracksideMe = get("me", TracksideMe.serializer())

    /**
     * The server's addresses, asked of an address that has already proven itself, or null
     * when they haven't changed since [version] (the last answer's ETag). A new address is
     * used only once it passes its own proof in the endpoints' next race.
     */
    suspend fun serverUrls(version: String?): TracksideServerUrls? {
        val answer = call { base ->
            request(base, "server/urls", authorization = bearer).newBuilder()
                .apply { version?.let { header("If-None-Match", it) } }
                .build()
        }
        if (answer.status == 304) return null
        val urls = decode(answer, TracksideServerUrls.serializer())
        return urls.copy(version = answer.etag ?: urls.version)
    }

    suspend fun renewMediaToken(): TracksideMediaToken =
        send("media-token", "POST", null, TracksideMediaToken.serializer()).also { mediaToken = it }

    /** A token that's good for the next day at least, asking for one when it isn't. */
    suspend fun freshMediaToken(): TracksideMediaToken {
        mediaToken?.takeUnless { it.needsRenewal(now()) }?.let { return it }
        return renewMediaToken()
    }

    /** Revokes this device's key on the server, as removing the server from Tracks does. */
    suspend fun revokeThisDevice() {
        val id = deviceId ?: return
        perform("me/devices/$id", "DELETE")
    }

    // MARK: Browsing

    suspend fun song(id: String): TracksideSong = get("songs/$id", TracksideSong.serializer())

    suspend fun albumPage(id: String): TracksideAlbumPage = get("albums/$id", TracksideAlbumPage.serializer())

    suspend fun artistPage(id: String): TracksideArtistPage = get("artists/$id", TracksideArtistPage.serializer())

    suspend fun albums(sort: String, limit: Int = 100, cursor: String? = null): List<TracksideAlbum> =
        get("albums", TracksideAlbumList.serializer(), listOfNotNull("sort" to sort, "limit" to "$limit", cursor?.let { "cursor" to it }).toMap()).albums

    suspend fun topSongs(artistId: String, count: Int = 20): List<TracksideSong> =
        get("artists/$artistId/top-songs", TracksideSongList.serializer(), mapOf("count" to "$count")).songs

    suspend fun similarSongs(songId: String, count: Int = 20): List<TracksideSong> = similar(songId, count).songs

    /** Songs like one, and with acquisition, ones the server doesn't have in `external`. */
    suspend fun similar(songId: String, count: Int = 20): TracksideSuggestedSongs =
        get("songs/$songId/similar", TracksideSuggestedSongs.serializer(), mapOf("count" to "$count"))

    suspend fun random(count: Int = 40): List<TracksideSong> =
        get("songs/random", TracksideSongList.serializer(), mapOf("count" to "$count")).songs

    // MARK: Shelves

    /** Songs like the ones you play that you haven't lately: the server's picks for you. */
    suspend fun picks(count: Int = 40, timeZone: String = java.time.ZoneId.systemDefault().id): TracksideSuggestedSongs =
        get("me/picks", TracksideSuggestedSongs.serializer(), mapOf("count" to "$count", "tz" to timeZone))

    /** One of Tracks' mixes, made by the server from your listens there: "onRepeat", "rediscover" and the rest. */
    suspend fun mix(id: String, timeZone: String = java.time.ZoneId.systemDefault().id): List<TracksideSong> =
        get("me/mixes/$id", TracksideSongList.serializer(), mapOf("tz" to timeZone)).songs

    /** Songs by the artists you play and ones like them that you've never played. */
    suspend fun getMore(count: Int = 24): TracksideSuggestedSongs =
        get("me/get-more", TracksideSuggestedSongs.serializer(), mapOf("count" to "$count"))

    /** The most played songs on the server. */
    suspend fun mostPlayed(count: Int = 50): List<TracksideSong> =
        get("songs/top", TracksideSongList.serializer(), mapOf("count" to "$count")).songs

    /** The library only: songs the server doesn't have are never in it. */
    suspend fun searchLibrary(query: String, types: List<String>, limit: Int = 20, cursor: String? = null): TracksideSearchResults =
        get("search", TracksideSearchResults.serializer(), listOfNotNull("q" to query, "types" to types.joinToString(","), "limit" to "$limit", cursor?.let { "cursor" to it }).toMap())

    /** A page of the library's changes after [cursor], or of a snapshot without one. */
    suspend fun sync(cursor: String?, limit: Int = 2_000): TracksideSyncPage =
        get("sync", TracksideSyncPage.serializer(), listOfNotNull("limit" to "$limit", cursor?.let { "cursor" to it }).toMap())

    // MARK: Getting songs the server doesn't have (section 15)

    /**
     * Songs the server could get for [query], waiting up to 8 s for its sources; null when
     * this phone can't request songs there (no `features.acquisition`), never an error.
     */
    suspend fun searchExternal(query: String, limit: Int = 20): TracksideAcquisitionSearch? =
        get("search", TracksideSearchResults.serializer(), mapOf("q" to query, "types" to "external", "limit" to "$limit")).external

    /**
     * Asks the server to get a song: `done` with `existing` when it has it already (200), or
     * on its way (202). [key] is the `Idempotency-Key`: a request lost on the way is sent once
     * more with the same key, so the server takes it once. A used-up quota throws
     * [TracksideError.QuotaExceeded] with when it resets; an expired candidate,
     * [TracksideError.CandidateExpired]; a server that doesn't get songs, NotFound.
     */
    suspend fun requestAcquisition(request: TracksideAcquisitionRequest, key: String = java.util.UUID.randomUUID().toString()): TracksideAcquisition {
        val body = TracksideJson.encodeToString(TracksideAcquisitionRequest.serializer(), request)
        val build = { base: HttpUrl ->
            request(base, "acquisitions", method = "POST", body = body, authorization = bearer).newBuilder().header("Idempotency-Key", key).build()
        }
        val answer = try {
            call(build)
        } catch (_: IOException) {
            call(build)
        }
        return decode(answer, TracksideAcquisition.serializer())
    }

    /** How a request is going (section 15.4). */
    suspend fun acquisition(id: String): TracksideAcquisition = get("acquisitions/$id", TracksideAcquisition.serializer())

    /** Your requests, newest first: still going, or [finished]. */
    suspend fun acquisitions(finished: Boolean = false, limit: Int = 100, cursor: String? = null): TracksideAcquisitionList =
        get("acquisitions", TracksideAcquisitionList.serializer(), listOfNotNull("state" to if (finished) "finished" else "active", "limit" to "$limit", cursor?.let { "cursor" to it }).toMap())

    /** Cancels a request that hasn't finished. */
    suspend fun cancelAcquisition(id: String) = perform("acquisitions/$id", "DELETE")

    /**
     * Plays a song the server doesn't have without keeping it (section 15.6), where `/me` says
     * `external_play`. Its `stream` paths take the media token, as [mediaUrl] adds.
     */
    suspend fun playExternal(request: TracksideExternalPlayRequest): TracksideExternalPlay =
        send("external/play", "POST", TracksideJson.encodeToString(TracksideExternalPlayRequest.serializer(), request), TracksideExternalPlay.serializer())

    // MARK: Playing

    suspend fun decide(playback: TracksidePlaybackRequest): TracksidePlaybackDecision =
        send("playback/decisions", "POST", TracksideJson.encodeToString(TracksidePlaybackRequest.serializer(), playback), TracksidePlaybackDecision.serializer())

    /** Without [size], the picture as stored. */
    fun artworkUrl(id: String, size: Int?): HttpUrl = withMediaToken(url(endpoints.baseUrl, "art/$id", size?.let { mapOf("size" to "$it") }.orEmpty()))

    /** A media address the server gave, resolved against the address in use, with the media token. */
    fun mediaUrl(reference: String): HttpUrl? = resolve(reference, endpoints.baseUrl)?.let(::withMediaToken)

    suspend fun star(songId: String) =
        perform("me/stars/song/$songId", "PUT", TracksideJson.encodeToString(TracksideStarRequest.serializer(), TracksideStarRequest(now().toString())))

    suspend fun unstar(songId: String) = perform("me/stars/song/$songId", "DELETE")

    // MARK: Live, listens and Trackside Cloud

    /** What the server offers, asked once per connection. */
    @Volatile private var capabilities: TracksideCapabilities? = null

    suspend fun capabilities(): TracksideCapabilities =
        capabilities ?: decode(call { base -> request(base, "capabilities") }, TracksideCapabilities.serializer()).also { capabilities = it }

    /** The profile to stream on cellular: the server's suggestion, or AAC at 256 kbps. */
    suspend fun cellularProfile(): String =
        runCatching { capabilities().media?.suggestedCellularProfile }.getOrNull()?.takeIf { it.isNotBlank() } ?: "aac-256"

    /** The live event stream's request, to the proven address with the key. */
    suspend fun eventsRequest(lastEventId: String?): Request {
        val base = endpoints.provenBaseUrl()
        return request(base, "events", authorization = bearer).newBuilder()
            .header("Accept", "text/event-stream")
            .apply { lastEventId?.let { header("Last-Event-ID", it) } }
            .build()
    }

    suspend fun sendListens(listens: List<TracksideListen>, clientTime: Instant = now()): List<TracksideListenResult> =
        send("listens", "POST", TracksideJson.encodeToString(TracksideListens.serializer(), TracksideListens(clientTime.toString(), listens)), TracksideListenResults.serializer()).results

    /** "playing", "paused" or "stopped". */
    suspend fun nowPlaying(songId: String, positionMs: Long, state: String) =
        perform("now-playing", "POST", TracksideJson.encodeToString(TracksideNowPlaying.serializer(), TracksideNowPlaying(songId, positionMs, state)))

    /** A Trackside Cloud request with a body in [CloudJson]; any answer but 2xx throws. */
    suspend fun <T> cloud(path: String, method: String, body: String?, serializer: KSerializer<T>, query: Map<String, String> = emptyMap()): T {
        val answer = call { base -> request(base, "cloud/$path", query, method = method, body = body, authorization = bearer) }
        check(answer)
        return try {
            CloudJson.decodeFromString(serializer, answer.body)
        } catch (_: Exception) {
            throw TracksideError.NotTrackside
        }
    }

    // MARK: Speakers (the wire contract's section 1)

    /** How far the server's clock is ahead of this phone's, from the last speaker answer's `Date`. */
    @Volatile var clockSkewMs: Long = 0
        private set

    /**
     * Whether this server has speakers and lets this key see them: the server has the API
     * (`/capabilities`), and `/me` says it's set up for this account. An old server, or one
     * without speakers set up, is false and its speakers are never asked for.
     */
    suspend fun speakerAccess(): SpeakerAccess {
        if (capabilities().features?.speakers != true) return SpeakerAccess.None
        val me = me()
        return when {
            !me.offersSpeakers -> SpeakerAccess.None
            me.controlsSpeakers -> SpeakerAccess.Control
            else -> SpeakerAccess.Look
        }
    }

    enum class SpeakerAccess { None, Look, Control }

    /** Every speaker you can see, or null when nothing changed since [version] (the last answer's ETag). */
    suspend fun speakers(version: String? = null): Pair<TracksideSpeakerList, String?>? {
        val answer = call { base ->
            request(base, "speakers", authorization = bearer).newBuilder()
                .apply { version?.let { header("If-None-Match", it) } }
                .build()
        }
        noteClock(answer)
        if (answer.status == 304) return null
        return decode(answer, TracksideSpeakerList.serializer()) to answer.etag
    }

    suspend fun speaker(id: String): TracksideSpeaker =
        call { base -> request(base, "speakers/$id", authorization = bearer) }.also { noteClock(it) }.let { decode(it, TracksideSpeaker.serializer()) }

    /** Up next on a speaker, from its current item less [history] before it. */
    suspend fun speakerQueue(id: String, cursor: String? = null, limit: Int = 100, history: Int = 0): TracksideSpeakerQueue =
        get(
            "speakers/$id/queue",
            TracksideSpeakerQueue.serializer(),
            listOfNotNull("limit" to "$limit", cursor?.let { "cursor" to it }, history.takeIf { it > 0 }?.let { "history" to "$it" }).toMap(),
        )

    /** Play, pause, stop, next, previous, seek or jump. A toggle is never sent twice. */
    suspend fun speakerPlayback(id: String, command: TracksideSpeakerPlayback): TracksideSpeakerCommand =
        speakerCommand("speakers/$id/playback", "POST", TracksideJson.encodeToString(TracksideSpeakerPlayback.serializer(), command), retry = command.action != "toggle")

    suspend fun patchSpeaker(id: String, patch: TracksideSpeakerPatch): TracksideSpeakerCommand =
        speakerCommand("speakers/$id", "PATCH", TracksideJson.encodeToString(TracksideSpeakerPatch.serializer(), patch))

    /** Groups the speakers in [memberIds] with [id] leading; an empty list ungroups everyone from it. */
    suspend fun setSpeakerMembers(id: String, memberIds: List<String>): TracksideSpeakerCommand =
        speakerCommand("speakers/$id/members", "PUT", TracksideJson.encodeToString(TracksideSpeakerMembers.serializer(), TracksideSpeakerMembers(memberIds)))

    /** Takes [id] out of whatever group it's in. */
    suspend fun leaveSpeakerGroup(id: String): TracksideSpeakerCommand = speakerCommand("speakers/$id/group", "DELETE", null)

    suspend fun playOnSpeaker(id: String, play: TracksideSpeakerPlayRequest, key: String = java.util.UUID.randomUUID().toString()): TracksideSpeakerCommand =
        speakerCommand("speakers/$id/play", "POST", TracksideJson.encodeToString(TracksideSpeakerPlayRequest.serializer(), play), key = key)

    suspend fun transferSpeaker(id: String, transfer: TracksideSpeakerTransfer, key: String = java.util.UUID.randomUUID().toString()): TracksideSpeakerCommand =
        speakerCommand("speakers/$id/transfer", "POST", TracksideJson.encodeToString(TracksideSpeakerTransfer.serializer(), transfer), key = key)

    /**
     * A speaker command, with an `Idempotency-Key`: one lost on the way is sent once more with
     * the same key, so the server acts on it once. A deliberate repeat is a new key.
     */
    private suspend fun speakerCommand(path: String, method: String, body: String?, retry: Boolean = true, key: String = java.util.UUID.randomUUID().toString()): TracksideSpeakerCommand {
        val build = { base: HttpUrl ->
            request(base, path, method = method, body = body, authorization = bearer).newBuilder().header("Idempotency-Key", key).build()
        }
        val answer = try {
            call(build)
        } catch (error: IOException) {
            if (!retry) throw error
            call(build)
        }
        noteClock(answer)
        return decode(answer, TracksideSpeakerCommand.serializer())
    }

    /** Only skews of more than two seconds count: `Date` is to the second, and closer clocks are left alone. */
    private fun noteClock(answer: TracksideAnswer) {
        val date = answer.date ?: return
        val server = runCatching { java.time.ZonedDateTime.parse(date, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() ?: return
        val skew = server.toEpochMilli() - now().toEpochMilli()
        clockSkewMs = if (kotlin.math.abs(skew) > 2_000) skew else 0
    }

    // MARK: Requests

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, query: Map<String, String> = emptyMap()): T =
        decode(call { base -> request(base, path, query, authorization = bearer) }, serializer)

    private suspend fun <T> send(path: String, method: String, body: String?, serializer: KSerializer<T>): T =
        decode(call { base -> request(base, path, method = method, body = body, authorization = bearer) }, serializer)

    private suspend fun perform(path: String, method: String, body: String? = null) {
        check(call { base -> request(base, path, method = method, body = body, authorization = bearer) })
    }

    /**
     * Sends a request with the key to the proven address. When the server answers that this
     * isn't its home network (`https_required`), it's sent once more to an https address, never
     * by asking you to sign in again.
     */
    private suspend fun call(build: (HttpUrl) -> Request): TracksideAnswer {
        val base = endpoints.provenBaseUrl()
        val answer = exchange(build(base))
        if (base.isHttps || !isHttpsRequired(answer)) return answer
        // The https addresses the refusal names come first; each still proves itself before use.
        val named = httpsUrls(answer).mapNotNull { stored ->
            stored.url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.let { Endpoint(it, kind(stored.kind, it)) }
        }
        val known = endpoints.candidates.map { it.url }.toSet()
        if (named.any { it.url !in known }) endpoints.update(named.filter { it.url !in known } + endpoints.candidates)
        val secure = endpoints.requireHttps()?.takeIf { it.isHttps } ?: throw TracksideError.HttpsRequired
        return exchange(build(secure))
    }

    /** Sends a request and tells the endpoints whether the server could be reached. */
    private suspend fun exchange(request: Request): TracksideAnswer = try {
        transport.send(request, null).also { endpoints.recordSuccess() }
    } catch (error: IOException) {
        endpoints.recordFailure()
        throw error
    }

    /** Only an address at the server in use carries the token: one for somewhere else never sees it. */
    private fun withMediaToken(url: HttpUrl): HttpUrl {
        val token = mediaToken ?: return url
        if (!isSameOrigin(url, endpoints.baseUrl)) return url
        return url.newBuilder().removeAllQueryParameters("mt").addQueryParameter("mt", token.token).build()
    }

    companion object {
        /** What the server at an address is, without signing in; null for anything that isn't Trackside. */
        suspend fun detect(base: HttpUrl, transport: TracksideTransport = OkHttpTracksideTransport()): TracksideCapabilities? = try {
            capabilities(base, transport).takeIf { it.isTrackside }
        } catch (_: TracksideError) {
            null
        }

        suspend fun capabilities(base: HttpUrl, transport: TracksideTransport = OkHttpTracksideTransport()): TracksideCapabilities =
            decode(transport.send(request(base, "capabilities"), PROBE_TIMEOUT_MS * 3), TracksideCapabilities.serializer())

        suspend fun ping(base: HttpUrl, query: Map<String, String>, timeoutMs: Long, transport: TracksideTransport): TracksidePing =
            decode(transport.send(request(base, "ping", query), timeoutMs), TracksidePing.serializer())

        /**
         * Whether [endpoint] proves it's the server [serverId]: the endpoints' probe. A captive
         * portal, a different server at the same home address, or nothing at all, all fail.
         */
        suspend fun proves(endpoint: Endpoint, serverId: String, key: TracksideProof.Key, timeoutMs: Long, transport: TracksideTransport): Boolean {
            val nonce = TracksideProof.nonce()
            val ping = runCatching { ping(endpoint.url, TracksideProof.query(key, nonce, endpoint.url), timeoutMs, transport) }.getOrNull() ?: return false
            return ping.serverId.equals(serverId, ignoreCase = true) && TracksideProof.verify(ping.proof, key, ping.serverId, endpoint.url, nonce)
        }

        /** Signs in with a username and password, making this phone one of the user's devices. */
        suspend fun login(base: HttpUrl, username: String, password: String, device: TracksideDeviceInfo, transport: TracksideTransport = OkHttpTracksideTransport()): TracksidePairResult {
            requireSafe(base)
            val body = TracksideJson.encodeToString(TracksideLoginRequest.serializer(), TracksideLoginRequest(username, password, device))
            return decode(transport.send(request(base, "auth/login", method = "POST", body = body), null), TracksidePairResult.serializer())
        }

        /** Pairs with a code: eight characters typed from the dashboard, or the secret in a QR code. */
        suspend fun claim(base: HttpUrl, code: String, device: TracksideDeviceInfo, transport: TracksideTransport = OkHttpTracksideTransport()): TracksidePairResult {
            requireSafe(base)
            val body = TracksideJson.encodeToString(TracksideClaimRequest.serializer(), TracksideClaimRequest(code, device))
            return decode(transport.send(request(base, "pair/claim", method = "POST", body = body), null), TracksidePairResult.serializer())
        }

        /** Asks to be paired from the dashboard, where someone signed in enters the code shown. */
        suspend fun requestPairing(base: HttpUrl, device: TracksideDeviceInfo, transport: TracksideTransport = OkHttpTracksideTransport()): TracksidePairRequest {
            requireSafe(base)
            val body = TracksideJson.encodeToString(TracksidePairRequestBody.serializer(), TracksidePairRequestBody(device))
            return decode(transport.send(request(base, "pair/requests", method = "POST", body = body), null), TracksidePairRequest.serializer())
        }

        suspend fun pollPairing(pairing: TracksidePairRequest, base: HttpUrl, transport: TracksideTransport = OkHttpTracksideTransport()): TracksidePairPoll {
            requireSafe(base)
            return decode(transport.send(request(base, "pair/requests/${pairing.id}", authorization = "Pair ${pairing.pollSecret}"), null), TracksidePairPoll.serializer())
        }

        /** The profile for a bit rate: null for the original. */
        fun profile(bitRate: Int?): String? = bitRate?.let { if (it >= 256) "aac-256" else "aac-128" }

        /** A profile's bit rate from its name ("aac-256" is 256), or null for one without, such as "flac". */
        fun bitRate(profile: String): Int? = profile.substringAfterLast('-', "").toIntOrNull()

        const val PROBE_TIMEOUT_MS = 2_000L

        private fun requireSafe(base: HttpUrl) {
            if (!Nearby.allowsCredentials(base)) throw TracksideError.InsecureAddress
        }

        fun url(base: HttpUrl, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
            val builder = base.newBuilder()
            ("api/v1/$path").split('/').forEach { builder.addPathSegment(it) }
            query.toSortedMap().forEach { (key, value) -> builder.addQueryParameter(key, value) }
            return builder.build()
        }

        internal fun request(base: HttpUrl, path: String, query: Map<String, String> = emptyMap(), method: String = "GET", body: String? = null, authorization: String? = null): Request =
            Request.Builder()
                .url(url(base, path, query))
                .header("Accept", "application/json")
                .apply { authorization?.let { header("Authorization", it) } }
                .method(method, body?.toRequestBody(JSON) ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null)
                .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun isSameOrigin(url: HttpUrl, base: HttpUrl) =
            url.scheme == base.scheme && TracksideProof.authority(url) == TracksideProof.authority(base)

        /**
         * A server's path resolved against the address in use. Its paths start at `/api/v1`; a
         * server under a base path such as `/music` may or may not include it, and either way
         * the answer lands under the base.
         */
        fun resolve(reference: String, base: HttpUrl): HttpUrl? {
            reference.toHttpUrlOrNull()?.let { return it }
            val basePath = base.encodedPath.trimEnd('/')
            val path = reference.substringBefore('?').let { if (it.startsWith("/")) it else "/$it" }
            val query = reference.substringAfter('?', "").takeIf { reference.contains('?') }
            val full = if (basePath.isNotEmpty() && path.startsWith("$basePath/")) path else basePath + path
            return base.newBuilder().encodedPath(full).encodedQuery(query).build()
        }

        /** The https addresses an `https_required` refusal names, if any. */
        fun httpsUrls(answer: TracksideAnswer): List<TracksideServerUrl> =
            runCatching { TracksideJson.decodeFromString(TracksideErrorEnvelope.serializer(), answer.body).error.details?.httpsUrls }.getOrNull().orEmpty()

        private fun kind(kind: String, url: HttpUrl) = when (kind) {
            "lan" -> Endpoint.Kind.Lan
            "tailnet" -> Endpoint.Kind.Tailnet
            "public" -> Endpoint.Kind.Public
            else -> Endpoint.guess(url).kind
        }

        fun isHttpsRequired(answer: TracksideAnswer) = answer.status == 403 &&
            runCatching { TracksideJson.decodeFromString(TracksideErrorEnvelope.serializer(), answer.body).error.code == "https_required" }.getOrDefault(false)

        fun check(answer: TracksideAnswer) {
            if (answer.status in 200..299) return
            throw error(answer)
        }

        fun <T> decode(answer: TracksideAnswer, serializer: KSerializer<T>): T {
            check(answer)
            return try {
                TracksideJson.decodeFromString(serializer, answer.body)
            } catch (_: Exception) {
                throw TracksideError.NotTrackside
            }
        }

        fun error(answer: TracksideAnswer): TracksideError {
            val body = runCatching { TracksideJson.decodeFromString(TracksideErrorEnvelope.serializer(), answer.body).error }.getOrNull()
                ?: return TracksideError.Http(answer.status)
            val retryAfter = answer.retryAfter?.trim()?.toLongOrNull()
            return when (body.code) {
                "unauthenticated" -> TracksideError.Unauthenticated
                "media_token_expired" -> TracksideError.MediaTokenExpired
                "forbidden", "csrf", "setup_required" -> TracksideError.Forbidden
                "not_found" -> TracksideError.NotFound
                "https_required" -> TracksideError.HttpsRequired
                "sync_reset" -> TracksideError.SyncReset
                "rate_limited" -> TracksideError.RateLimited(retryAfter)
                "quota_exceeded" -> TracksideError.QuotaExceeded(instant(body.details?.resetsAt))
                "candidate_expired" -> TracksideError.CandidateExpired
                "library_unavailable", "transcoder_busy", "unavailable" -> TracksideError.Unavailable(body.code, retryAfter)
                "speakers_unavailable" -> TracksideError.SpeakersUnavailable(retryAfter)
                "catalog_unavailable" -> TracksideError.CatalogUnavailable(retryAfter)
                else -> TracksideError.Server(answer.status, body.code, body.message.orEmpty(), body.details?.field, body.details?.reason, body.details?.capability)
            }
        }
    }
}

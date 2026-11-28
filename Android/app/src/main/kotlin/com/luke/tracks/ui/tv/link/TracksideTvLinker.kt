package com.luke.tracks.ui.tv.link

import com.luke.tracks.library.Inspection
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksideLinkInfo
import com.luke.tracks.library.trackside.TracksideLinkPoll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The app's [TvLinker] on the Trackside client (api.md section 6). The TV's half asks the
 * server at [LinkServer.url]; a linked TV is kept with [ServerLibrary.saveLink], as a pairing
 * is. The phone's half asks the paired server through its own client.
 */
class TracksideTvLinker(private val servers: () -> ServerLibrary?) : TvLinker {
    override suspend fun start(server: LinkServer): LinkOffer {
        val library = servers() ?: throw LinkException(LinkProblem.Failed)
        val canLink = server.deviceLink ?: when (val found = library.inspect(server.url)) {
            is Inspection.Trackside -> found.capabilities.auth?.deviceLink == true
            is Inspection.Unreachable, is Inspection.NeedsTrust -> throw LinkException(LinkProblem.Offline)
            is Inspection.Subsonic -> throw LinkException(LinkProblem.NotTrackside)
        }
        if (!canLink) throw LinkException(LinkProblem.NeedsUpdate)
        val start = translating(tvSide = true) { library.startLink(server.url) }
        val expiresAt = try {
            Instant.parse(start.expiresAt)
        } catch (error: DateTimeParseException) {
            throw LinkException(LinkProblem.Failed, error)
        }
        return LinkOffer(
            server = server,
            deviceCode = start.deviceCode,
            userCode = normalizedUserCode(start.userCode) ?: start.userCode,
            linkUrl = start.linkUrl,
            intervalSeconds = start.intervalS.coerceIn(0, 3_600).toInt(),
            expiresAt = expiresAt,
        )
    }

    override suspend fun poll(offer: LinkOffer): LinkPoll {
        val library = servers() ?: throw LinkException(LinkProblem.Failed)
        val answer = try {
            library.pollLink(offer.server.url, offer.deviceCode)
        } catch (error: TracksideError.RateLimited) {
            return LinkPoll.SlowDown(error.retryAfterSeconds?.toInt() ?: (offer.intervalSeconds * 2))
        }
        return when (answer) {
            TracksideLinkPoll.Pending -> LinkPoll.Pending
            is TracksideLinkPoll.SlowDown -> LinkPoll.SlowDown(answer.intervalS.coerceIn(1, 3_600).toInt())
            TracksideLinkPoll.Denied -> LinkPoll.Denied
            TracksideLinkPoll.Expired -> LinkPoll.Expired
            // The key comes only once, so failing to keep it ends this code rather than asking again.
            is TracksideLinkPoll.Linked -> translating(tvSide = true) {
                LinkPoll.Linked(library.saveLink(offer.server.url, answer.result).name)
            }
        }
    }

    override suspend fun lookUp(serverId: String, userCode: String) = decide(serverId) { it.lookupLink(userCode) }

    override suspend fun approve(serverId: String, userCode: String) = decide(serverId) { it.approveLink(userCode) }

    override suspend fun deny(serverId: String, userCode: String) = decide(serverId) { it.denyLink(userCode) }

    /** [serverId] is the server's own id, as a tracks://link names it, or the entry's id here. */
    private suspend fun decide(serverId: String, ask: suspend (TracksideClient) -> TracksideLinkInfo): LinkRequest {
        val library = servers() ?: throw LinkException(LinkProblem.Failed)
        library.loading.first { !it }
        val entry = library.entries.value.firstOrNull {
            it.server.isTrackside && (it.server.serverUuid.equals(serverId, ignoreCase = true) || it.server.id == serverId)
        } ?: throw LinkException(LinkProblem.Failed)
        val client = library.tracksideClient(entry.server.id) ?: throw LinkException(LinkProblem.Failed)
        val info = translating(tvSide = false) { ask(client) }
        return LinkRequest(info.device.name, info.device.platform, info.nearby)
    }

    /** Trackside's refusals as the sentence each side says; a dropped connection stays an [IOException]. */
    private suspend fun <T> translating(tvSide: Boolean, call: suspend () -> T): T = try {
        call()
    } catch (error: CancellationException) {
        throw error
    } catch (error: IOException) {
        throw error
    } catch (error: LinkException) {
        throw error
    } catch (error: TracksideError) {
        throw LinkException(problem(error, tvSide), error)
    }

    companion object {
        internal fun problem(error: TracksideError, tvSide: Boolean): LinkProblem = when (error) {
            TracksideError.NotFound -> if (tvSide) LinkProblem.NeedsUpdate else LinkProblem.NotFound
            TracksideError.LinkExpired -> LinkProblem.CodeExpired
            TracksideError.LinkLocked -> LinkProblem.Locked
            TracksideError.LinkUsed -> LinkProblem.Used
            is TracksideError.RateLimited -> LinkProblem.RateLimited
            TracksideError.InsecureAddress, TracksideError.HttpsRequired -> LinkProblem.Insecure
            TracksideError.NotTrackside -> LinkProblem.NotTrackside
            TracksideError.Unproven -> LinkProblem.Offline
            else -> LinkProblem.Failed
        }
    }
}

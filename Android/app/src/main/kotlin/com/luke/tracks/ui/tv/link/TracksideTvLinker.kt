package com.luke.tracks.ui.tv.link

/**
 * The app's [TvLinker], for the Trackside client's device-link calls (link/start, link/poll
 * and link/approve), which store an approved link exactly as a pairing. Until the client has
 * them, every server reads as one that can't link yet.
 */
object TracksideTvLinker : TvLinker {
    override suspend fun start(server: LinkServer): LinkOffer = throw LinkException(LinkProblem.NeedsUpdate)

    override suspend fun poll(offer: LinkOffer): LinkPoll = throw LinkException(LinkProblem.NeedsUpdate)

    override suspend fun approve(serverId: String, userCode: String): String = throw LinkException(LinkProblem.NeedsUpdate)
}

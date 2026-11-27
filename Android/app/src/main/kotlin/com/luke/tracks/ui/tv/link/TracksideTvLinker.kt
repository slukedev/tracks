package com.luke.tracks.ui.tv.link

/**
 * The app's [TvLinker] on the Trackside client, the one file to change when the client's
 * device-link calls land (api.md section 6):
 * - [start]: `TracksideClient.startLink(server.url, device)` with the same device object as
 *   sign-in (platform "android_tv"), into a [LinkOffer].
 * - [poll]: `TracksideClient.pollLink(base, deviceCode)`; its `Linked(result)` is kept with
 *   `ServerLibrary.pair(null, name, url, Pairing.Approved(result))`, exactly as a pairing is.
 * - [lookUp], [approve], [deny]: `lookupLink`, `approveLink` and `denyLink` on the paired
 *   server's client, into a [LinkRequest]; `NotFound`, `LinkExpired`, `LinkLocked`, `LinkUsed`
 *   and `RateLimited` become the [LinkProblem]s of the same names.
 * Until then every server reads as one that can't link yet, so a build without them is safe.
 */
object TracksideTvLinker : TvLinker {
    override suspend fun start(server: LinkServer): LinkOffer = throw LinkException(LinkProblem.NeedsUpdate)

    override suspend fun poll(offer: LinkOffer): LinkPoll = throw LinkException(LinkProblem.NeedsUpdate)

    override suspend fun lookUp(serverId: String, userCode: String): LinkRequest = throw LinkException(LinkProblem.NeedsUpdate)

    override suspend fun approve(serverId: String, userCode: String): LinkRequest = throw LinkException(LinkProblem.NeedsUpdate)

    override suspend fun deny(serverId: String, userCode: String): LinkRequest = throw LinkException(LinkProblem.NeedsUpdate)
}

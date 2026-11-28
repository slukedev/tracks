package com.luke.tracks.ui.tv.link

import com.luke.tracks.AppGraph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.ServerAddress
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.sync.CloudSync
import com.luke.tracks.sync.TvSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Once this TV is linked to [server], turns on Trackside Cloud with the server it was saved
 * as, so the TV's Summary, Charts and History match the phone's. Runs in the app's scope, so
 * leaving the screen doesn't stop it, and does nothing when the server doesn't keep the cloud
 * or the TV already syncs with another server it's linked to.
 */
fun syncAfterLink(graph: AppGraph, server: LinkServer) {
    val sync = CloudSync.of(graph) ?: return
    val servers = (graph.library as? CombinedLibrary)?.servers ?: return
    graph.scope.launch {
        try {
            val entry = withTimeoutOrNull(SavedWithinMs) { servers.entries.first { linkedEntry(it, server) != null } }
                ?.let { linkedEntry(it, server) } ?: return@launch
            val offersCloud = servers.tracksideClient(entry.server.id)?.capabilities()?.offersCloud == true
            val others = TvSync.linkedServers(servers.entries.value).map { it.server.id } - entry.server.id
            TvSync.linked(sync, entry.server.id, offersCloud, others)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Out of reach for now: the Sync switch in Settings turns it on later.
        }
    }
}

/** The saved Trackside server that linking [server] made: the same server id, else the same address. */
internal fun linkedEntry(entries: List<ServerEntry>, server: LinkServer): ServerEntry? {
    val trackside = entries.filter { it.server.isTrackside }
    return server.id?.let { id -> trackside.firstOrNull { it.server.serverUuid.equals(id, ignoreCase = true) } }
        ?: trackside.firstOrNull { it.server.url == ServerAddress.stored(server.url) }
}

private const val SavedWithinMs = 5_000L

package com.luke.tracks.sync

import com.luke.tracks.library.ServerEntry

/** Which server [CloudSync] syncs with, and the ways to change it: all a TV needs of it. */
interface SyncChoice {
    /** The chosen server's id, or null when sync is off. */
    suspend fun chosen(): String?

    suspend fun choose(serverId: String?)

    /** Syncs with the chosen server again after it was paired again. */
    suspend fun confirm()
}

/**
 * Trackside Cloud on a TV: only ever with a server the TV is linked to. Linking the first
 * turns it on, the Sync switch on a server in Settings turns it off or on again, and nothing
 * else chooses a server.
 */
object TvSync {
    /**
     * At launch, before sync starts. A server chosen on another device (a phone's choice,
     * restored from its backup) is turned off unless this TV is linked to it. One it's linked
     * to stays as it was; if it was paired again since, it waits for [SyncChoice.confirm] as
     * it would on a phone.
     */
    suspend fun settle(sync: SyncChoice, linkedServerIds: Collection<String>) {
        val chosen = sync.chosen() ?: return
        if (chosen !in linkedServerIds) sync.choose(null)
    }

    /**
     * Just linked to [serverId]: syncs with it when it keeps Trackside Cloud, unless sync is
     * already on with another server this TV is linked to ([otherLinked]).
     */
    suspend fun linked(sync: SyncChoice, serverId: String, offersCloud: Boolean, otherLinked: Collection<String> = emptyList()) {
        if (!offersCloud) return
        val chosen = sync.chosen()
        if (chosen != null && chosen != serverId && chosen in otherLinked) return
        turnOn(sync, serverId)
    }

    /** On with [serverId]. Already chosen means it was paired again, and asking is the say-so it waits for. */
    suspend fun turnOn(sync: SyncChoice, serverId: String) {
        if (sync.chosen() == serverId) sync.confirm() else sync.choose(serverId)
    }

    /** The servers this TV is linked to, as Settings shows them. */
    fun linkedServers(entries: List<ServerEntry>): List<ServerEntry> = entries.filter { it.server.isTrackside }
}

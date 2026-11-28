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
 * Trackside Cloud on a TV: only ever with the server the TV is linked to. Linking turns it on,
 * the Sync switch in Settings turns it off or on again, and nothing else chooses a server.
 */
object TvSync {
    /**
     * At launch, before sync starts. A server chosen on another device (a phone's choice,
     * restored from its backup) is turned off unless it's the one this TV is linked to. That
     * one stays as it was; if it was paired again since, it waits for [SyncChoice.confirm]
     * as it would on a phone.
     */
    suspend fun settle(sync: SyncChoice, linkedServerId: String?) {
        val chosen = sync.chosen() ?: return
        if (chosen != linkedServerId) sync.choose(null)
    }

    /** Just linked to [serverId]: syncs with it when it keeps Trackside Cloud. */
    suspend fun linked(sync: SyncChoice, serverId: String, offersCloud: Boolean) {
        if (offersCloud) turnOn(sync, serverId)
    }

    /** On with [serverId]. Already chosen means it was paired again, and asking is the say-so it waits for. */
    suspend fun turnOn(sync: SyncChoice, serverId: String) {
        if (sync.chosen() == serverId) sync.confirm() else sync.choose(serverId)
    }

    /** The server this TV is linked to, as Settings shows it. */
    fun linkedServer(entries: List<ServerEntry>): ServerEntry? = entries.firstOrNull { it.server.isTrackside }
}

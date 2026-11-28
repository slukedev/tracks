package com.luke.tracks.sync

import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerKind
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.ui.tv.link.LinkServer
import com.luke.tracks.ui.tv.link.linkedEntry
import com.luke.tracks.ui.tv.settings.SyncSwitch
import com.luke.tracks.ui.tv.settings.syncSwitch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Trackside Cloud on a TV: a phone's restored choice, linking, and the Sync switch. */
class TvSyncTest {
    private class FakeSync(var chosen: String?) : SyncChoice {
        val calls = mutableListOf<String>()
        override suspend fun chosen() = chosen
        override suspend fun choose(serverId: String?) {
            calls += "choose $serverId"
            chosen = serverId
        }
        override suspend fun confirm() {
            calls += "confirm"
        }
    }

    @Test fun aPhonesChoiceRestoredFromItsBackupDoesntSyncFromTheTv() = runBlocking {
        val otherServer = FakeSync(chosen = "phone-server")
        TvSync.settle(otherServer, linkedServerId = "tv-server")
        assertNull(otherServer.chosen)

        val notLinkedYet = FakeSync(chosen = "phone-server")
        TvSync.settle(notLinkedYet, linkedServerId = null)
        assertNull(notLinkedYet.chosen)
    }

    @Test fun theLinkedServerStaysAsItWasAtLaunch() = runBlocking {
        val on = FakeSync(chosen = "tv-server")
        TvSync.settle(on, linkedServerId = "tv-server")
        assertEquals("tv-server", on.chosen)
        // A server paired again is left to CloudSync, which waits to be confirmed.
        assertEquals(emptyList<String>(), on.calls)

        // Turned off on the TV: launching doesn't turn it back on.
        val off = FakeSync(chosen = null)
        TvSync.settle(off, linkedServerId = "tv-server")
        assertNull(off.chosen)
        assertEquals(emptyList<String>(), off.calls)
    }

    @Test fun linkingTurnsOnSyncWithThatServerWhenItKeepsTheCloud() = runBlocking {
        val fresh = FakeSync(chosen = null)
        TvSync.linked(fresh, "tv-server", offersCloud = true)
        assertEquals(listOf("choose tv-server"), fresh.calls)

        val elsewhere = FakeSync(chosen = "phone-server")
        TvSync.linked(elsewhere, "tv-server", offersCloud = true)
        assertEquals("tv-server", elsewhere.chosen)

        val noCloud = FakeSync(chosen = null)
        TvSync.linked(noCloud, "tv-server", offersCloud = false)
        assertEquals(emptyList<String>(), noCloud.calls)
    }

    @Test fun linkingAgainOrSwitchingOnConfirmsAServerPairedAgain() = runBlocking {
        val relinked = FakeSync(chosen = "tv-server")
        TvSync.linked(relinked, "tv-server", offersCloud = true)
        assertEquals(listOf("confirm"), relinked.calls)

        val switchedOn = FakeSync(chosen = "tv-server")
        TvSync.turnOn(switchedOn, "tv-server")
        assertEquals(listOf("confirm"), switchedOn.calls)
    }

    @Test fun theSyncSwitchSaysHowItsGoing() {
        val on = SyncStatus(serverId = "tv", lastSyncedAt = 1)
        assertEquals(SyncSwitch(on = true, line = "Up to date", problem = false), syncSwitch(on, "tv", offersCloud = true))
        // Already on shows even before the server has said what it keeps, so it can be turned off.
        assertEquals(SyncSwitch(on = true, line = "Up to date", problem = false), syncSwitch(on, "tv", offersCloud = null))
        assertEquals("Syncing", syncSwitch(on.copy(syncing = true, problem = "Couldn't reach Den. Tracks will try again."), "tv", true)?.line)
        // Just turned on: the first sync is on its way.
        assertEquals("Syncing", syncSwitch(SyncStatus(serverId = "tv"), "tv", true)?.line)

        val problem = "Couldn't reach Den. Tracks will try again."
        assertEquals(SyncSwitch(on = true, line = problem, problem = true), syncSwitch(on.copy(problem = problem), "tv", true))
        val paired = "Den was paired again. Tap to keep syncing."
        assertEquals(SyncSwitch(on = false, line = paired, problem = true), syncSwitch(on.copy(problem = paired, needsConfirmation = true), "tv", true))

        val off = SyncSwitch(on = false, line = null, problem = false)
        assertEquals(off, syncSwitch(SyncStatus(), "tv", offersCloud = true))
        assertEquals(off, syncSwitch(SyncStatus(serverId = "phone"), "tv", offersCloud = true))
        assertNull(syncSwitch(SyncStatus(), "tv", offersCloud = false))
        assertNull(syncSwitch(SyncStatus(), "tv", offersCloud = null))
        assertNull(syncSwitch(on, linkedServerId = null, offersCloud = true))
    }

    @Test fun aLinkFindsTheServerItWasSavedAs() {
        val subsonic = entry("s", "https://den.example.com", kind = ServerKind.Subsonic)
        val trackside = entry("t", "https://den.example.com", uuid = "6F1C2B9E-0A44-4B8D-9C1E-2D3F4A5B6C7D")
        val entries = listOf(subsonic, trackside)
        val byId = LinkServer("6f1c2b9e-0a44-4b8d-9c1e-2d3f4a5b6c7d", "Den", "https://elsewhere.example.com".toHttpUrl())
        assertEquals(trackside, linkedEntry(entries, byId))
        val byAddress = LinkServer(null, "Den", "https://den.example.com/".toHttpUrl())
        assertEquals(trackside, linkedEntry(entries, byAddress))
        assertNull(linkedEntry(entries, LinkServer("another", "Attic", "https://attic.example.com".toHttpUrl())))
        assertEquals(trackside, TvSync.linkedServer(entries))
        assertNull(TvSync.linkedServer(listOf(subsonic)))
    }

    private fun entry(id: String, url: String, kind: ServerKind = ServerKind.Trackside, uuid: String? = null) =
        ServerEntry(SubsonicServer(id = id, name = "Den", url = url, username = "luke", kind = kind, serverUuid = uuid), ServerStatus.Syncing)
}

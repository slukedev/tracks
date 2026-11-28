package com.luke.tracks.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.sync.CloudSync
import com.luke.tracks.sync.SyncStatus
import com.luke.tracks.sync.TvSync
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.ui.sources.songs
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvSidePanel
import com.luke.tracks.ui.tv.link.TvLinkFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Where this TV's link to Trackside stands, for the page. */
sealed interface TracksideLink {
    data object None : TracksideLink
    data class Linked(val name: String, val status: ServerStatus) : TracksideLink
}

fun ServerEntry?.toLink(): TracksideLink = this?.let { TracksideLink.Linked(it.server.name, it.status) } ?: TracksideLink.None

/** The Sync switch: on while history goes to and from the linked server, with how it's going in a few words. */
data class SyncSwitch(val on: Boolean, val line: String?, val problem: Boolean)

/**
 * The switch for [status] on the server this TV is linked to; null hides it, where that
 * server doesn't keep Trackside Cloud (or hasn't said) and sync isn't on with it. A server
 * paired again reads as off until it's switched on again, since nothing syncs until then.
 */
fun syncSwitch(status: SyncStatus, linkedServerId: String?, offersCloud: Boolean?): SyncSwitch? {
    linkedServerId ?: return null
    val chosen = status.serverId == linkedServerId
    if (!chosen && offersCloud != true) return null
    if (!chosen) return SyncSwitch(on = false, line = null, problem = false)
    // The phone's sentence says to tap; a TV is driven with a remote.
    val problem = (if (status.needsConfirmation) PairedAgain else status.problem).takeUnless { status.syncing }
    val line = when {
        status.syncing -> "Syncing"
        problem != null -> problem
        status.lastSyncedAt != null -> "Up to date"
        else -> "Syncing"
    }
    return SyncSwitch(on = !status.needsConfirmation, line = line, problem = problem != null)
}

internal const val PairedAgain = "Paired again. Select to keep syncing."

/** Settings > Trackside: the server this TV plays from, syncing with it, refreshing it and unlinking; linking when there's none. */
@Composable
fun TvTracksidePage() {
    val graph = LocalContext.current.graph
    val servers = (graph.library as? CombinedLibrary)?.servers
    val sync = remember(graph) { CloudSync.of(graph) }
    val entries by remember(servers) { servers?.entries ?: MutableStateFlow(emptyList()) }.collectAsState()
    val status by remember(sync) { sync?.status ?: MutableStateFlow(SyncStatus()) }.collectAsStateWithLifecycle()
    val entry = TvSync.linkedServer(entries)
    val serverId = entry?.server?.id
    val offersCloud by produceState<Boolean?>(null, servers, serverId) {
        value = serverId?.let { id ->
            try {
                servers?.tracksideClient(id)?.capabilities()?.offersCloud
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }
    val scope = rememberCoroutineScope()
    TvTracksideContent(
        link = entry.toLink(),
        onRefresh = { entry?.let { scope.launch { servers?.sync(it.server) } } },
        onUnlink = {
            entry?.let {
                scope.launch {
                    // Sync goes with the link.
                    if (sync?.chosen() == it.server.id) sync.choose(null)
                    servers?.remove(it.server.id)
                }
            }
        },
        sync = if (sync == null) null else syncSwitch(status, serverId, offersCloud),
        onSync = { on ->
            val id = serverId
            if (sync != null && id != null) scope.launch { if (on) TvSync.turnOn(sync, id) else sync.choose(null) }
        },
    )
}

/** The page from plain values, for screenshots. The link flow sits in the page when there's no server. */
@Composable
fun TvTracksideContent(
    link: TracksideLink,
    onRefresh: () -> Unit,
    onUnlink: () -> Unit,
    sync: SyncSwitch? = null,
    onSync: (Boolean) -> Unit = {},
    linkAgain: Boolean = false,
) {
    var relinking by remember(link) { mutableStateOf(linkAgain) }
    var confirming by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf(false) }
    val unlinkButton = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
    PageColumn {
        when {
            link is TracksideLink.Linked && !relinking -> Linked(link, sync, onSync, onRefresh, onRelink = { relinking = true }, onUnlink = {
                unlinking = false
                confirming = true
            }, unlinkButton)
            else -> {
                PageHeading("Link This TV")
                TvLinkFlow(onLinked = { relinking = false }, claimFocus = false)
            }
        }
    }
    TvSidePanel(
        visible = confirming,
        onDismiss = { confirming = false },
        title = "Unlink This TV?",
        width = 400.dp,
        returnFocus = unlinkButton.takeUnless { unlinking },
    ) {
        Text("Your plays stay in History.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        TvButton("Unlink", onClick = {
            unlinking = true
            confirming = false
            onUnlink()
        }, style = TvButtonStyle.Primary)
        TvButton("Cancel", onClick = { confirming = false })
    }
    }
}

@Composable
private fun Linked(
    link: TracksideLink.Linked,
    sync: SyncSwitch?,
    onSync: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRelink: () -> Unit,
    onUnlink: () -> Unit,
    unlinkButton: FocusRequester,
) {
    val colors = MaterialTheme.colorScheme
    val status = link.status
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 20.dp)) {
        Text(link.name, style = MaterialTheme.typography.headlineMedium, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            statusLine(status),
            style = MaterialTheme.typography.titleMedium,
            color = if (status is ServerStatus.Failed || status is ServerStatus.NeedsPassword || status is ServerStatus.NeedsTrust) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (status == ServerStatus.NeedsPassword) {
            TvButton("Link Again", onClick = onRelink, style = TvButtonStyle.Primary)
        } else {
            TvButton("Refresh", onClick = onRefresh, pending = status == ServerStatus.Syncing)
        }
        TvButton("Unlink", onClick = onUnlink, modifier = Modifier.focusRequester(unlinkButton))
    }
    if (sync != null) {
        Box(Modifier.padding(top = 24.dp)) {
            ToggleRow(
                "Sync",
                checked = sync.on,
                onChange = onSync,
                icon = Icons.Rounded.Sync,
                supporting = sync.line,
                supportingColor = if (sync.problem) colors.error else Color.Unspecified,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// "Refreshing", like its button, so it never reads as a second "Syncing" beside the Sync switch's.
private fun statusLine(status: ServerStatus): String = when (status) {
    ServerStatus.Syncing -> "Refreshing"
    is ServerStatus.Ready -> "Connected, ${songs(status.songCount)}"
    is ServerStatus.Failed -> status.message
    ServerStatus.NeedsPassword -> "This TV was signed out."
    is ServerStatus.NeedsTrust -> "The server's certificate changed."
}

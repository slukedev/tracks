package com.luke.tracks.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** Settings > Trackside: the server this TV plays from, refreshing it and unlinking; linking when there's none. */
@Composable
fun TvTracksidePage() {
    val graph = LocalContext.current.graph
    val servers = (graph.library as? CombinedLibrary)?.servers
    val entries by remember(servers) { servers?.entries ?: MutableStateFlow(emptyList()) }.collectAsState()
    val entry = entries.firstOrNull { it.server.isTrackside }
    val scope = rememberCoroutineScope()
    TvTracksideContent(
        link = entry.toLink(),
        onRefresh = { entry?.let { scope.launch { servers?.sync(it.server) } } },
        onUnlink = { entry?.let { scope.launch { servers?.remove(it.server.id) } } },
    )
}

/** The page from plain values, for screenshots. The link flow sits in the page when there's no server. */
@Composable
fun TvTracksideContent(
    link: TracksideLink,
    onRefresh: () -> Unit,
    onUnlink: () -> Unit,
    linkAgain: Boolean = false,
) {
    var relinking by remember(link) { mutableStateOf(linkAgain) }
    var confirming by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    PageColumn {
        when {
            link is TracksideLink.Linked && !relinking -> Linked(link, onRefresh, onRelink = { relinking = true }, onUnlink = { confirming = true })
            else -> {
                PageHeading("Link This TV")
                TvLinkFlow(onLinked = { relinking = false }, claimFocus = false)
            }
        }
    }
    TvSidePanel(visible = confirming, onDismiss = { confirming = false }, title = "Unlink This TV?", width = 400.dp) {
        Text("Your plays stay in History.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        TvButton("Unlink", onClick = {
            confirming = false
            onUnlink()
        }, style = TvButtonStyle.Primary)
        TvButton("Cancel", onClick = { confirming = false })
    }
    }
}

@Composable
private fun Linked(link: TracksideLink.Linked, onRefresh: () -> Unit, onRelink: () -> Unit, onUnlink: () -> Unit) {
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
        TvButton("Unlink", onClick = onUnlink)
    }
}

private fun statusLine(status: ServerStatus): String = when (status) {
    ServerStatus.Syncing -> "Syncing"
    is ServerStatus.Ready -> "Connected, ${songs(status.songCount)}"
    is ServerStatus.Failed -> status.message
    ServerStatus.NeedsPassword -> "This TV was signed out."
    is ServerStatus.NeedsTrust -> "The server's certificate changed."
}

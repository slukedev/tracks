package com.luke.tracks.ui.library.servers

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.discover.FetchCapability
import com.luke.tracks.discover.LidarrState
import com.luke.tracks.discover.discovery
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.currentNavigator
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.StatusHero
import com.luke.tracks.ui.settings.Tone
import com.luke.tracks.ui.settings.footnote
import com.luke.tracks.ui.settings.group
import com.luke.tracks.ui.settings.link
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Settings > Get music: which of your servers can fetch songs you don't have (Octo), and
 * Lidarr. Getting music shows up around the app only when one of them can.
 */
@Composable
fun GetMusicSettingsPage() {
    val context = LocalContext.current
    val discovery = remember(context) { context.graph.discovery }
    val servers = (context.graph.library as? CombinedLibrary)?.servers
    val entries by remember(servers) { servers?.entries ?: MutableStateFlow(emptyList()) }.collectAsStateWithLifecycle()
    val capabilities by discovery.octo.capabilities.collectAsStateWithLifecycle()
    val lidarr by discovery.lidarr.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    GetMusicPageContent(entries, capabilities, lidarr, onCheck = { scope.launch { discovery.recheck(it) } })
}

@Composable
fun GetMusicPageContent(
    entries: List<ServerEntry>,
    capabilities: Map<String, FetchCapability>,
    lidarr: LidarrState,
    onCheck: (SubsonicServer) -> Unit,
) {
    val navigator = currentNavigator()
    val fetching = entries.filter { capabilities[it.server.id] == FetchCapability.Fetches }
    val lidarrReady = lidarr is LidarrState.Connected
    val (headline, body) = when {
        fetching.isNotEmpty() && lidarrReady -> "Ready" to "${fetching.first().server.name} and Lidarr can get music you don't have."
        fetching.isNotEmpty() -> "Ready" to "${fetching.joinToString(" and ") { it.server.name }} can get songs you don't have."
        lidarrReady -> "Ready" to "Lidarr can get albums you don't have."
        else -> "Not set up" to "Add a server with Octo, or connect Lidarr, to get songs and albums you don't have."
    }
    SettingsScaffold(title = "Get music") {
        item(key = "hero") {
            StatusHero(
                icon = Icons.Rounded.CloudDownload,
                shape = MaterialShapes.Sunny,
                tone = if (fetching.isNotEmpty() || lidarrReady) Tone.Primary else Tone.Neutral,
                headline = headline,
                body = body,
                modifier = Modifier.animateItem(),
                actions = if (entries.isEmpty() && !lidarrReady) ({
                    Button(onClick = { navigator.open(SettingsPageRoute("servers")) }) { Text("Add a server") }
                    OutlinedButton(onClick = { navigator.open(SettingsPageRoute("lidarr")) }) { Text("Connect Lidarr") }
                }) else null,
            )
        }
        if (entries.isNotEmpty()) {
            group("Your servers", key = "servers") {
                entries.forEach { entry ->
                    val capability = capabilities[entry.server.id] ?: FetchCapability.Unknown
                    link(
                        entry.server.name,
                        summary = summary(entry, capability),
                        icon = Icons.Rounded.Dns,
                        tone = if (capability == FetchCapability.Fetches) Tone.Primary else Tone.Neutral,
                        enabled = entry.status is ServerStatus.Ready,
                        trailing = {
                            if (capability == FetchCapability.Checking) LoadingIndicator(Modifier.padding(4.dp))
                            else Icon(Icons.Rounded.Refresh, "Check ${entry.server.name} again")
                        },
                    ) { onCheck(entry.server) }
                }
            }
            footnote("Octo sits in front of Navidrome and finds songs your library doesn't have. Tracks checks each server for it.", key = "octo-note")
        }
        group(key = "lidarr") {
            link(
                "Lidarr",
                summary = when (lidarr) {
                    LidarrState.Off -> "Not connected"
                    LidarrState.Connecting -> "Connecting"
                    is LidarrState.Connected -> "Connected"
                    LidarrState.WrongKey -> "Key not accepted"
                    LidarrState.NeedsKey -> "Enter the key again"
                    is LidarrState.Failed -> "Can't reach Lidarr"
                },
                icon = Icons.Rounded.Sync,
                tone = if (lidarrReady) Tone.Primary else Tone.Secondary,
            ) { navigator.open(SettingsPageRoute("lidarr")) }
        }
    }
}

private fun summary(entry: ServerEntry, capability: FetchCapability): String = when {
    entry.status !is ServerStatus.Ready -> "Not connected"
    else -> when (capability) {
        FetchCapability.Fetches -> "Gets songs you don't have"
        FetchCapability.PlaysOnly -> "Plays what it has"
        FetchCapability.Checking -> "Checking"
        FetchCapability.Unknown -> "Not checked yet"
    }
}

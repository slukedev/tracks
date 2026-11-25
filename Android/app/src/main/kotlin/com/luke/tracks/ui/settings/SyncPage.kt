package com.luke.tracks.ui.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.library.trackside.CloudUsage
import com.luke.tracks.sync.CloudSync
import com.luke.tracks.sync.SyncServer
import com.luke.tracks.sync.SyncStatus
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.currentNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Settings > Sync: off, or Trackside Cloud on one of your Trackside servers. */
@Composable
fun SyncPage() {
    val context = LocalContext.current
    val sync = remember { CloudSync.of(context.graph) }
    val scope = rememberCoroutineScope()
    val navigator = currentNavigator()
    val status by remember(sync) { sync?.status ?: MutableStateFlow(SyncStatus()) }.collectAsStateWithLifecycle()
    val servers by remember(sync) { sync?.choices ?: flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    SyncPageContent(
        status = status,
        servers = servers,
        onChoose = { id -> scope.launch { sync?.choose(id) } },
        onSyncNow = { if (status.needsConfirmation) scope.launch { sync?.confirm() } else sync?.syncNow() },
        onAddServer = { navigator.open(SettingsPageRoute("servers")) },
    )
}

/** The Sync page as it looks for [status]; stateless, for screenshots. */
@Composable
fun SyncPageContent(
    status: SyncStatus,
    servers: List<SyncServer>,
    onChoose: (serverId: String?) -> Unit,
    onSyncNow: () -> Unit,
    onAddServer: () -> Unit,
) {
    SettingsScaffold(title = "Sync") {
        group(key = "backend") {
            choice("Off", selected = status.serverId == null) { onChoose(null) }
            servers.forEach { server ->
                choice(server.name, detail = "Trackside Cloud", selected = status.serverId == server.id) { onChoose(server.id) }
            }
            if (servers.isEmpty()) {
                link("Add a Trackside Server", icon = Icons.Rounded.Add, tone = Tone.Neutral, onClick = onAddServer)
            }
        }
        if (status.serverId == null) return@SettingsScaffold
        group(key = "status") {
            val problem = status.problem
            link(
                title = when {
                    status.syncing -> "Syncing"
                    problem != null -> problem
                    else -> "Sync Now"
                },
                summary = status.lastSyncedAt?.let { "Last synced ${lastSynced(it)}" },
                icon = if (problem != null && !status.syncing) Icons.Rounded.ErrorOutline else Icons.Rounded.Sync,
                tone = if (problem != null && !status.syncing) Tone.Error else Tone.Primary,
                enabled = !status.syncing,
                trailing = if (status.syncing) ({ LoadingIndicator(Modifier.size(32.dp)) }) else null,
                onClick = onSyncNow,
            )
            status.usage?.let { usage ->
                val context = LocalContext.current
                info("Used", usageLine({ Formatter.formatShortFileSize(context, it) }, usage))
            }
        }
    }
}

private fun GroupScope.choice(title: String, detail: String? = null, selected: Boolean, onClick: () -> Unit) = row { index, count ->
    val haptics = LocalHapticFeedback.current
    SegmentedListItem(
        onClick = {
            if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = detail?.let { { Text(it) } },
        colors = rowColors(),
        modifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        },
    ) {
        Text(title)
    }
}

/** A row that only says something. */
private fun GroupScope.info(title: String, detail: String) = custom {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun lastSynced(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().replaceFirstChar { it.lowercase() }

/** "152,340 items · 98 MB of 1.1 GB". */
internal fun usageLine(bytes: (Long) -> String, usage: CloudUsage): String {
    val items = NumberFormat.getIntegerInstance().format(usage.items) + if (usage.items == 1L) " item" else " items"
    val size = bytes(usage.bytes) + (usage.quota?.bytes?.let { " of ${bytes(it)}" } ?: "")
    return "$items · $size"
}

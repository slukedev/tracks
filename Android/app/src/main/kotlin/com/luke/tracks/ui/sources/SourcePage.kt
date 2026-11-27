package com.luke.tracks.ui.sources

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.ServerAddress
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.SubsonicServer
import com.luke.tracks.library.musicPermission
import com.luke.tracks.sources.Removal
import com.luke.tracks.sources.Source
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.SourceStatus
import com.luke.tracks.ui.library.servers.rememberServerEditing
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.StatusHero
import com.luke.tracks.ui.settings.Tone
import com.luke.tracks.ui.settings.group
import com.luke.tracks.ui.settings.info
import com.luke.tracks.ui.settings.link
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** One source's page, by its stored id ("device", "server:<id>"). */
@Composable
fun SourceDetailPage(raw: String, showBack: Boolean = true) {
    val id = remember(raw) { SourceId.parse(raw) }
    val registry = rememberSources()
    val sources by sourceList()
    val navigator = LocalNavigator.current
    val source = sources.firstOrNull { it.id == id }
    when (id) {
        SourceId.Device -> DeviceSourcePage(source, showBack)
        is SourceId.Server -> {
            val library = LocalContext.current.graph.library as? CombinedLibrary
            val entries by remember(library) { library?.servers?.entries ?: MutableStateFlow(emptyList()) }.collectAsState()
            val entry = entries.firstOrNull { it.server.id == id.id }
            if (entry != null && source != null) {
                ServerSourcePage(source, entry, showBack)
            } else if (source != null) {
                ServerSourcePageContent(source, addresses = emptyList(), showBack = showBack, onRename = {}, onCheck = {}, onFix = null, onRemove = {})
            } else {
                // Removed while open: back to the list.
                LaunchedEffect(Unit) { if (showBack) navigator.back() }
            }
        }
        else -> registry.registered.collectAsState().value.lastOrNull { it.kind.matches(id) }
            ?.Page(source, onDone = { if (showBack) navigator.back() })
    }
}

private fun SourceKind.matches(id: SourceId) = when (this) {
    SourceKind.Device -> id == SourceId.Device
    SourceKind.Server -> id is SourceId.Server
}

/** On This Device: how many songs, or what it takes to let Tracks read them. */
@Composable
fun DeviceSourcePage(source: Source?, showBack: Boolean = true) {
    val context = LocalContext.current
    val library = context.graph.library as? CombinedLibrary
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        haptics.performHapticFeedback(if (granted) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        asked = true
        scope.launch { library?.device?.refresh() }
    }
    val activity = context as? android.app.Activity
    // After "Don't ask again" the system answers no without asking, so the settings page is the way.
    val blocked = asked && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, musicPermission)
    val status = source?.status ?: SourceStatus.Off
    SettingsScaffold(title = DeviceName, showBack = showBack) {
        item(key = "hero") {
            StatusHero(
                icon = Icons.Rounded.PhoneAndroid,
                shape = MaterialShapes.Cookie9Sided,
                tone = if (status == SourceStatus.Off) Tone.Error else Tone.Secondary,
                headline = if (status == SourceStatus.Off) "Access is off" else status.word(),
                actions = if (status != SourceStatus.Off) null else ({
                    if (blocked) {
                        Button(onClick = { openAppSettings(context) }) { Text("Open settings") }
                    } else {
                        Button(onClick = { permission.launch(musicPermission) }) { Text("Allow access") }
                    }
                }),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private const val DeviceName = "On This Device"

/** A server: where it stands, its name and addresses, checking it, and removing it. */
@Composable
fun ServerSourcePage(source: Source, entry: ServerEntry, showBack: Boolean = true) {
    val context = LocalContext.current
    val library = context.graph.library as? CombinedLibrary
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val editing = rememberServerEditing()
    val registry = rememberSources()
    var removing by remember { mutableStateOf(false) }
    ServerSourcePageContent(
        source = source,
        addresses = addresses(entry.server),
        showBack = showBack,
        onRename = { editing.edit(entry.server) },
        onCheck = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch { library?.servers?.sync(entry.server) }
        },
        onFix = when (source.status) {
            SourceStatus.SignIn -> ({ editing.edit(entry.server) })
            SourceStatus.CheckCertificate -> ({ editing.trust(entry) })
            else -> null
        },
        onRemove = { removing = true },
    )
    editing.Sheets()
    if (removing) {
        val provider = registry.provider(SourceKind.Server)
        RemoveSourceDialog(
            provider?.removal(source) ?: Removal.standard(source),
            onConfirm = {
                removing = false
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch { provider?.remove(source.id) }
            },
            onDismiss = { removing = false },
        )
    }
}

/** An address a server answers at, and the kind of route it is. */
data class ServerRoute(val address: String, val label: String)

fun addresses(server: SubsonicServer): List<ServerRoute> {
    val main = ServerRoute(ServerAddress.display(server.url), routeLabel(null, server.url))
    val others = server.addresses
        .filter { ServerAddress.display(it.url) != main.address }
        .map { ServerRoute(ServerAddress.display(it.url), routeLabel(it.kind, it.url)) }
    return (listOf(main) + others).distinctBy { it.address }
}

private fun routeLabel(kind: String?, url: String): String = when {
    kind == "lan" -> "Home network"
    kind == "tailnet" || url.contains(".ts.net") -> "Tailscale"
    kind == "public" -> "Internet"
    com.luke.tracks.net.Nearby.isNearby(url.substringAfter("://").substringBefore('/').substringBefore(':')) -> "Home network"
    else -> "Internet"
}

/** The page from plain values, so it renders the same in screenshots. */
@Composable
fun ServerSourcePageContent(
    source: Source,
    addresses: List<ServerRoute>,
    showBack: Boolean,
    onRename: () -> Unit,
    onCheck: () -> Unit,
    onFix: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val status = source.status
    SettingsScaffold(title = source.name, showBack = showBack) {
        item(key = "hero") {
            StatusHero(
                icon = Icons.Rounded.Dns,
                shape = MaterialShapes.Cookie9Sided,
                tone = if (status.needsAction) Tone.Error else Tone.Secondary,
                headline = heroLine(source),
                body = (status as? SourceStatus.Offline)?.reason,
                actions = onFix?.let { fix ->
                    {
                        Button(onClick = fix) { Text(if (status == SourceStatus.SignIn) "Sign in again" else "Review certificate") }
                    }
                } ?: if (status is SourceStatus.Offline) ({
                    FilledTonalButton(onClick = onCheck) { Text("Try again") }
                }) else null,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (status is SourceStatus.Syncing) {
            item(key = "progress") {
                val fraction = status.fraction
                if (fraction != null) {
                    LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        group(key = "name") {
            link("Name", summary = source.name, icon = Icons.Rounded.Edit, tone = Tone.Primary, onClick = onRename)
        }
        if (addresses.isNotEmpty()) {
            group("Addresses") {
                addresses.forEach { route -> info(route.address, route.label, ltr = true) }
            }
        }
        group(key = "actions") {
            link("Check connection", icon = Icons.Rounded.Sync, tone = Tone.Tertiary, onClick = onCheck)
            link("Remove", icon = Icons.Rounded.Delete, tone = Tone.Error, onClick = onRemove)
        }
    }
}

private fun heroLine(source: Source): String = when (val status = source.status) {
    SourceStatus.Connected -> source.songCount?.let { "Connected, ${songs(it)}" } ?: "Connected"
    SourceStatus.Away -> "Connected away from home"
    is SourceStatus.Offline -> "Offline"
    is SourceStatus.Syncing -> "Syncing"
    SourceStatus.SignIn -> "This phone was signed out"
    SourceStatus.CheckCertificate -> "Certificate changed"
    else -> status.word()
}

fun openAppSettings(context: android.content.Context) {
    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

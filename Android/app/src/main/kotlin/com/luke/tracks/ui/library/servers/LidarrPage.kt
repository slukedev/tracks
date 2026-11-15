package com.luke.tracks.ui.library.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.discover.LidarrSetup
import com.luke.tracks.discover.LidarrState
import com.luke.tracks.discover.discovery
import com.luke.tracks.graph
import com.luke.tracks.library.ServerAddress
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.StatusHero
import com.luke.tracks.ui.settings.Tone
import com.luke.tracks.ui.settings.custom
import com.luke.tracks.ui.settings.footnote
import com.luke.tracks.ui.settings.group
import com.luke.tracks.ui.settings.link
import com.luke.tracks.ui.settings.toggle
import kotlinx.coroutines.launch
import uniffi.tracks_engine.LidarrMonitor
import uniffi.tracks_engine.LidarrQueueItem

/** Settings > Lidarr: connect it, choose how it files what Tracks asks for, see what's coming. */
@Composable
fun LidarrSettingsPage() {
    val context = LocalContext.current
    val lidarr = remember(context) { context.graph.discovery.lidarr }
    val state by lidarr.state.collectAsStateWithLifecycle()
    val setup by lidarr.setup.collectAsStateWithLifecycle()
    val queue by lidarr.queue.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { if (state is LidarrState.Connected) lidarr.refreshQueue() }
    LidarrPageContent(
        state = state,
        setup = setup,
        queue = queue,
        onTest = lidarr::test,
        onSave = lidarr::save,
        onChoose = { q, m, r, mon, now -> lidarr.choose(q, m, r, mon, now) },
        onCheck = { scope.launch { lidarr.check() } },
        onDisconnect = lidarr::disconnect,
    )
}

/** What "follow" means for an artist Tracks adds, in plain words. */
private val monitors = listOf(
    LidarrMonitor.ALL to "All albums",
    LidarrMonitor.FUTURE to "New albums only",
    LidarrMonitor.LATEST to "Latest album and new ones",
    LidarrMonitor.NONE to "Only what you ask for",
)

@Composable
fun LidarrPageContent(
    state: LidarrState,
    setup: LidarrSetup?,
    queue: List<LidarrQueueItem>,
    onTest: suspend (address: String, key: String) -> String?,
    onSave: suspend (address: String, key: String) -> String?,
    onChoose: (quality: Long?, metadata: Long?, root: String?, monitor: LidarrMonitor?, searchNow: Boolean?) -> Unit,
    onCheck: () -> Unit,
    onDisconnect: () -> Unit,
    startEditing: Boolean = false,
) {
    var editing by remember { mutableStateOf(startEditing || setup == null) }
    LaunchedEffect(setup == null) { if (setup == null) editing = true }
    val (headline, body, tone) = when (state) {
        LidarrState.Off -> Triple("Not connected", "Connect Lidarr to ask it for albums you don't have.", Tone.Neutral)
        LidarrState.Connecting -> Triple("Connecting", ServerAddress.display(setup?.url.orEmpty()), Tone.Secondary)
        is LidarrState.Connected -> Triple("Connected", "Lidarr ${state.version} at ${ServerAddress.display(setup?.url.orEmpty())}", Tone.Primary)
        LidarrState.WrongKey -> Triple("Key not accepted", "Enter the API key from Lidarr's Settings, General.", Tone.Error)
        LidarrState.NeedsKey -> Triple("Enter the key again", "This phone can't open the key saved before.", Tone.Error)
        is LidarrState.Failed -> Triple("Can't reach Lidarr", state.message, Tone.Error)
    }
    SettingsScaffold(title = "Lidarr") {
        item(key = "hero") {
            StatusHero(
                icon = if (state is LidarrState.Connected) Icons.Rounded.CheckCircle else if (tone == Tone.Error) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudDownload,
                shape = MaterialShapes.Cookie9Sided,
                tone = tone,
                headline = headline,
                body = body,
                modifier = Modifier.animateItem(),
                actions = if (!editing && setup != null && state !is LidarrState.Connected) ({
                    Button(onClick = onCheck) { Text("Try again") }
                    OutlinedButton(onClick = { editing = true }) { Text("Edit") }
                }) else null,
            )
        }
        if (editing) {
            group("Connect", key = "form") {
                custom { LidarrForm(setup, onTest, onSave, onDone = { editing = false }, onCancel = if (setup != null) ({ editing = false }) else null) }
            }
        }
        if (setup != null && state is LidarrState.Connected) {
            group("When Tracks adds an artist", key = "options") {
                choice("Follow", monitors.first { it.first == setup.monitor }.second, Icons.Rounded.PersonAdd, monitors.map { it.second }) { i ->
                    onChoose(null, null, null, monitors[i].first, null)
                }
                choice("Quality", setup.quality?.name ?: "None", Icons.Rounded.HighQuality, setup.qualityProfiles.map { it.name }) { i ->
                    onChoose(setup.qualityProfiles[i].id, null, null, null, null)
                }
                choice("Metadata", setup.metadata?.name ?: "None", Icons.Rounded.Inbox, setup.metadataProfiles.map { it.name }) { i ->
                    onChoose(null, setup.metadataProfiles[i].id, null, null, null)
                }
                choice("Folder", setup.root?.path ?: "None", Icons.Rounded.Folder, setup.rootFolders.map { it.path }) { i ->
                    onChoose(null, null, setup.rootFolders[i].path, null, null)
                }
                toggle("Search right away", checked = setup.searchNow, summary = "Rather than at Lidarr's next check", icon = Icons.Rounded.CloudDownload, tone = Tone.Secondary) {
                    onChoose(null, null, null, null, it)
                }
            }
            group("Downloading", key = "queue") {
                if (queue.isEmpty()) {
                    custom { Text("Nothing downloading", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    queue.take(8).forEach { item -> custom { QueueRow(item) } }
                }
            }
        }
        if (setup != null && !editing) {
            group(key = "manage") {
                link("Edit address or key", icon = Icons.Rounded.Edit, tone = Tone.Secondary) { editing = true }
                link("Disconnect", icon = Icons.Rounded.LinkOff, tone = Tone.Error) { onDisconnect() }
            }
        }
        footnote("Tracks asks Lidarr for albums and artists. Lidarr finds them and files them where your music server plays them.", key = "note")
    }
}

/** A row that opens a short menu of choices. */
private fun com.luke.tracks.ui.settings.GroupScope.choice(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, options: List<String>, onPick: (Int) -> Unit) = custom {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .then(
                    if (options.size > 1) Modifier.clickable(onClickLabel = "Change $title") {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        open = true
                    } else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.luke.tracks.ui.settings.ToneIcon(icon, Tone.Primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (options.size > 1) Icon(Icons.Rounded.UnfoldMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = androidx.compose.ui.unit.DpOffset(56.dp, 0.dp)) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}

/** Lidarr's "00:03:20" (or "1.02:00:00" past a day) as "3 min left". */
internal fun timeLeft(raw: String?): String? {
    val parts = raw?.substringAfterLast('.')?.split(':')?.mapNotNull { it.toIntOrNull() } ?: return null
    if (parts.size != 3) return null
    val days = raw.substringBefore('.', "0").toIntOrNull() ?: 0
    val minutes = days * 1440 + parts[0] * 60 + parts[1] + if (parts[2] >= 30) 1 else 0
    return when {
        minutes < 1 -> "Almost done"
        minutes < 60 -> "$minutes min left"
        else -> "${minutes / 60} hr ${minutes % 60} min left"
    }
}

@Composable
private fun QueueRow(item: LidarrQueueItem) {
    Column(Modifier.fillMaxWidth()) {
        Text(item.albumTitle ?: item.title.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            item.errorMessage ?: listOfNotNull(item.artistName, timeLeft(item.timeLeft)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        if (item.errorMessage == null) {
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(progress = { item.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LidarrForm(
    setup: LidarrSetup?,
    onTest: suspend (String, String) -> String?,
    onSave: suspend (String, String) -> String?,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(setup?.url?.let(ServerAddress::display).orEmpty()) }
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    val url = ServerAddress.url(address)
    val ready = url != null && (key.isNotBlank() || setup != null) && !busy
    fun run(save: Boolean) = scope.launch {
        busy = true
        val problem = if (save) onSave(address, key) else onTest(address, key)
        busy = false
        ok = problem == null
        result = problem ?: if (save) null else "Connected"
        haptics.performHapticFeedback(if (problem == null) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        if (save && problem == null) onDone()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; result = null },
            label = { Text("Address") },
            placeholder = { Text("nas.local:8686") },
            singleLine = true,
            isError = address.isNotBlank() && url == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; result = null },
            label = { Text("API key") },
            placeholder = if (setup != null) ({ Text("Unchanged") }) else null,
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide key" else "Show key")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            supportingText = { Text("In Lidarr: Settings, General, Security") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy || result != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
            if (busy) LoadingIndicator(Modifier.size(28.dp))
            else Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text(if (busy) "Connecting" else result.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = if (busy || ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (onCancel != null) androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
            OutlinedButton(onClick = { run(save = false) }, enabled = ready) { Text("Test") }
            Button(onClick = { run(save = true) }, enabled = ready) { Text(if (setup == null) "Connect" else "Save") }
        }
    }
}

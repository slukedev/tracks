package com.luke.tracks.ui.library.servers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.ServerAddress
import com.luke.tracks.library.ServerEntry
import com.luke.tracks.library.ServerStatus
import com.luke.tracks.library.SubsonicServer
import kotlinx.coroutines.launch

/** Your servers as a segmented group, Android 16 Settings style, with a way to add one. */
@Composable
fun ServerList(
    entries: List<ServerEntry>,
    onAdd: () -> Unit,
    onEdit: (SubsonicServer) -> Unit,
    onRemove: (SubsonicServer) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "intro") {
            Text(
                "Play from your own music server: Navidrome, Gonic, Airsonic, Ampache and any other that speaks the Subsonic API. Its songs join Your Music, your mixes and Tracks Radio.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            )
        }
        itemsIndexed(entries, key = { _, entry -> entry.server.id }) { i, entry ->
            ServerRow(
                entry = entry,
                index = i,
                count = entries.size,
                onEdit = { onEdit(entry.server) },
                onRemove = { onRemove(entry.server) },
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = ListItemDefaults.SegmentedGap).animateItem(),
            )
        }
        item(key = "add") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
                FilledTonalButton(onClick = onAdd, modifier = Modifier.heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 24.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add a server", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ServerRow(entry: ServerEntry, index: Int, count: Int, onEdit: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        SegmentedListItem(
            onClick = onEdit,
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            onLongClickLabel = "More options",
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            leadingContent = {
                Surface(shape = MaterialShapes.Cookie6Sided.toShape(), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            },
            supportingContent = { StatusLine(entry) },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${entry.server.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onRemove() })
                    }
                }
            },
            content = { Text(entry.server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
    }
}

@Composable
private fun StatusLine(entry: ServerEntry) {
    val address = ServerAddress.display(entry.server.url)
    val (text, color) = when (val status = entry.status) {
        ServerStatus.Syncing -> "Getting your songs" to MaterialTheme.colorScheme.onSurfaceVariant
        is ServerStatus.Ready -> address to MaterialTheme.colorScheme.onSurfaceVariant
        is ServerStatus.Failed -> status.message to MaterialTheme.colorScheme.error
        ServerStatus.NeedsPassword -> "Sign in again to play from it" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Adding, editing and removing servers, from wherever the list is shown. */
@Stable
class ServerEditing {
    internal var editing by mutableStateOf<EditorTarget?>(null)
    internal var removing by mutableStateOf<SubsonicServer?>(null)

    fun add() { editing = EditorTarget(null) }
    fun edit(server: SubsonicServer) { editing = EditorTarget(server) }
    fun remove(server: SubsonicServer) { removing = server }

    @Composable
    fun Sheets() {
        val context = LocalContext.current
        val servers = (context.graph.library as? CombinedLibrary)?.servers
        val scope = rememberCoroutineScope()
        editing?.let { target ->
            ServerEditorSheet(
                existing = target.server,
                onTest = { url, user, password ->
                    val pass = password.ifEmpty { target.server?.let { servers?.savedPassword(it) }.orEmpty() }
                    servers?.test(url, user, pass)?.exceptionOrNull()?.let { it.message ?: "Couldn't connect." }
                        ?: if (servers == null) "Servers aren't available here." else null
                },
                onSave = { name, url, user, password -> servers?.save(target.server?.id, name, url, user, password) },
                onDismiss = { editing = null },
            )
        }
        removing?.let { server ->
            AlertDialog(
                onDismissRequest = { removing = null },
                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                title = { Text("Remove ${server.name}?") },
                text = { Text("Its songs leave Your Music. What you've played stays in your history.") },
                confirmButton = {
                    TextButton(onClick = { removing = null; scope.launch { servers?.remove(server.id) } }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
            )
        }
    }
}

internal data class EditorTarget(val server: SubsonicServer?)

@Composable
fun rememberServerEditing(): ServerEditing = remember { ServerEditing() }

private sealed interface Check {
    data object Idle : Check
    data object Checking : Check
    data object Connected : Check
    data class Failed(val message: String) : Check
}

/**
 * The form for a server: address, sign-in and an optional name, with a connection check
 * that has to pass before it's saved.
 */
@Composable
fun ServerEditorSheet(
    existing: SubsonicServer?,
    /** Returns null when the server answered, or why it didn't. */
    onTest: suspend (url: okhttp3.HttpUrl, username: String, password: String) -> String?,
    onSave: suspend (name: String, url: okhttp3.HttpUrl, username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ServerEditorForm(
            existing = existing,
            onTest = onTest,
            onSave = { name, url, user, password ->
                onSave(name, url, user, password)
                scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
            },
            onCancel = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } },
        )
    }
}

@Composable
fun ServerEditorForm(
    existing: SubsonicServer?,
    onTest: suspend (url: okhttp3.HttpUrl, username: String, password: String) -> String?,
    onSave: suspend (name: String, url: okhttp3.HttpUrl, username: String, password: String) -> Unit,
    onCancel: () -> Unit,
    initialCheck: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(existing?.url?.let(ServerAddress::display).orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<Check>(if (initialCheck == null) Check.Idle else if (initialCheck.isEmpty()) Check.Connected else Check.Failed(initialCheck)) }

    val url = ServerAddress.url(address)
    val canCheck = url != null && username.isNotBlank() && (password.isNotEmpty() || existing != null)

    suspend fun runCheck(): Boolean {
        val target = url ?: return false
        check = Check.Checking
        val problem = onTest(target, username, password)
        check = if (problem == null) Check.Connected else Check.Failed(problem)
        haptics.performHapticFeedback(if (problem == null) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        return problem == null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (existing == null) "Add a server" else "Edit ${existing.name}", style = MaterialTheme.typography.headlineSmallEmphasized)
        Text(
            "Works with Navidrome, Gonic, Airsonic, Ampache, LMS and other servers that speak the Subsonic API.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; check = Check.Idle },
            label = { Text("Address") },
            placeholder = { Text("music.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            isError = address.isNotBlank() && url == null,
            supportingText = if (address.isNotBlank() && url == null) ({ Text("That doesn't look like an address") }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(visible = url != null && ServerAddress.isUnencrypted(url), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "This address isn't encrypted. Use https if your server has it, especially away from home.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; check = Check.Idle },
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; check = Check.Idle },
            label = { Text("Password") },
            placeholder = if (existing != null) ({ Text("Unchanged") }) else null,
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            trailingIcon = {
                IconButton(onClick = {
                    reveal = !reveal
                    haptics.performHapticFeedback(if (reveal) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                }) {
                    Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Hide password" else "Show password")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            placeholder = { Text(url?.host ?: "Home server") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        CheckLine(check)

        Text(
            "Tracks sends a token made from your password, never the password itself, and keeps the password encrypted on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            OutlinedButton(
                onClick = { scope.launch { runCheck() } },
                enabled = canCheck && check != Check.Checking,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Test connection") }
            Button(
                onClick = {
                    scope.launch {
                        val target = url ?: return@launch
                        if (check == Check.Connected || runCheck()) onSave(name, target, username, password)
                    }
                },
                enabled = canCheck && check != Check.Checking,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun CheckLine(check: Check) {
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = check,
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        label = "check",
    ) { state ->
        val (color, text) = when (state) {
            Check.Idle -> Color.Unspecified to null
            Check.Checking -> MaterialTheme.colorScheme.onSurfaceVariant to "Connecting"
            Check.Connected -> MaterialTheme.colorScheme.primary to "Connected"
            is Check.Failed -> MaterialTheme.colorScheme.error to state.message
        }
        if (text == null) {
            Spacer(Modifier.height(0.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 4.dp)) {
                when (state) {
                    Check.Checking -> LoadingIndicator(Modifier.size(28.dp))
                    Check.Connected -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = color)
                    else -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = color)
                }
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

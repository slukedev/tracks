package com.luke.tracks.ui.library.servers

import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.QrCodeScanner
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
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.luke.tracks.library.Inspection
import com.luke.tracks.library.ServerLibrary
import com.luke.tracks.library.SubsonicAuth
import com.luke.tracks.library.trackside.PairingPayload
import com.luke.tracks.library.trackside.TracksideCapabilities
import com.luke.tracks.library.trackside.TracksideError
import com.luke.tracks.library.trackside.TracksidePairRequest
import com.luke.tracks.library.trackside.TracksidePairResult
import com.luke.tracks.net.CertificateNeedsTrust
import com.luke.tracks.net.Nearby
import com.luke.tracks.net.PinStore
import com.luke.tracks.net.certificateNeedingTrust
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import kotlinx.coroutines.launch

/** Adding, editing and removing servers, and trusting their certificates, from wherever the list is shown. */
@Stable
class ServerEditing {
    internal var editing by mutableStateOf<EditorTarget?>(null)
    internal var removing by mutableStateOf<SubsonicServer?>(null)
    internal var trusting by mutableStateOf<ServerEntry?>(null)

    fun add() { editing = EditorTarget(null) }
    fun edit(server: SubsonicServer) { editing = EditorTarget(server) }
    fun remove(server: SubsonicServer) { removing = server }
    fun trust(entry: ServerEntry) { trusting = entry }

    @Composable
    fun Sheets() {
        val context = LocalContext.current
        val servers = (context.graph.library as? CombinedLibrary)?.servers
        val scope = rememberCoroutineScope()
        val actions = remember(servers) { servers?.let(::LibraryServerActions) }
        editing?.let { target ->
            if (actions == null) {
                editing = null
                return@let
            }
            ServerEditorSheet(existing = target.server, actions = actions, onDismiss = { editing = null })
        }
        removing?.let { server ->
            AlertDialog(
                onDismissRequest = { removing = null },
                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                title = { Text("Remove ${server.name}?") },
                text = { Text("Your plays stay in History.") },
                confirmButton = {
                    TextButton(onClick = { removing = null; scope.launch { servers?.remove(server.id) } }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
            )
        }
        trusting?.let { entry ->
            val refusal = (entry.status as? ServerStatus.NeedsTrust)?.refusal
            if (refusal == null) {
                trusting = null
                return@let
            }
            TrustDialog(
                refusal = refusal,
                onTrust = {
                    trusting = null
                    scope.launch {
                        servers?.trust(entry.server.id, refusal)
                    }
                },
                onDismiss = { trusting = null },
            )
        }
    }
}

internal data class EditorTarget(val server: SubsonicServer?)

@Composable
fun rememberServerEditing(): ServerEditing = remember { ServerEditing() }

/**
 * Asks before a certificate the phone doesn't recognise is trusted: a server's self-signed
 * one, or one that isn't what you trusted before. Shows the fingerprint to compare with the
 * server's own.
 */
@Composable
fun TrustDialog(refusal: CertificateNeedsTrust, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val changed = refusal.previous.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (changed) Icons.Rounded.GppMaybe else Icons.Rounded.Lock, contentDescription = null) },
        title = { Text(if (changed) "Certificate changed" else "Trust this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (changed) "${refusal.key.host} shows a different certificate from the one you trusted. If you didn't change it, don't continue."
                    else "${refusal.key.host} uses a certificate your phone doesn't know. Trust it only if this fingerprint matches your server's.",
                )
                Text(
                    PinStore.display(refusal.fingerprint),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the form asks of your servers; the server library in the app, a stand-in in screenshots. */
interface ServerActions {
    suspend fun inspect(url: HttpUrl): Inspection
    fun trustWhileAdding(refusal: CertificateNeedsTrust)
    /** Null when the server took the sign-in, or why it didn't. */
    suspend fun test(url: HttpUrl, username: String, secret: String, auth: SubsonicAuth, existing: SubsonicServer?): String?
    suspend fun save(existing: SubsonicServer?, name: String, url: HttpUrl, username: String, secret: String, auth: SubsonicAuth, others: List<HttpUrl>)
    suspend fun pair(existing: SubsonicServer?, name: String, url: HttpUrl, pairing: ServerLibrary.Pairing)
    suspend fun pair(payload: PairingPayload)
    suspend fun requestApproval(url: HttpUrl): TracksidePairRequest
    suspend fun awaitApproval(url: HttpUrl, request: TracksidePairRequest): TracksidePairResult?
}

private class LibraryServerActions(private val servers: ServerLibrary) : ServerActions {
    override suspend fun inspect(url: HttpUrl) = servers.inspect(url)
    override fun trustWhileAdding(refusal: CertificateNeedsTrust) = servers.trustWhileAdding(refusal)
    override suspend fun test(url: HttpUrl, username: String, secret: String, auth: SubsonicAuth, existing: SubsonicServer?): String? {
        val saved = secret.ifEmpty { existing?.let(servers::savedPassword).orEmpty() }
        return servers.test(url, username, saved, auth).exceptionOrNull()?.let { it.message ?: "Couldn't connect." }
    }
    override suspend fun save(existing: SubsonicServer?, name: String, url: HttpUrl, username: String, secret: String, auth: SubsonicAuth, others: List<HttpUrl>) {
        servers.save(existing?.id, name, url, username, secret, auth, others)
    }
    override suspend fun pair(existing: SubsonicServer?, name: String, url: HttpUrl, pairing: ServerLibrary.Pairing) {
        servers.pair(existing?.id, name, url, pairing)
    }
    override suspend fun pair(payload: PairingPayload) {
        servers.pair(payload)
    }
    override suspend fun requestApproval(url: HttpUrl) = servers.requestApproval(url)
    override suspend fun awaitApproval(url: HttpUrl, request: TracksidePairRequest) = servers.awaitApproval(url, request)
}

/** How you sign in to Trackside. */
internal enum class TracksideSignIn(val label: String) { Code("Code"), Approval("Approval"), Password("Password") }

@Composable
fun ServerEditorSheet(existing: SubsonicServer?, actions: ServerActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ServerEditorForm(existing = existing, actions = actions, onDone = { close() }, onCancel = { close() })
    }
}

/**
 * The form for a server. The address is asked what it is as typing pauses, so the form offers
 * the right way in: a pairing code, approval from the dashboard or a password for Trackside; a
 * password or an API key for a Subsonic server that takes one. A `trackside://pair` link
 * pasted as the address pairs straight away. A certificate the phone doesn't know is shown
 * for you to trust or not.
 */
@Composable
fun ServerEditorForm(
    existing: SubsonicServer?,
    actions: ServerActions,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    initialInspection: Inspection? = null,
    initialCheck: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(existing?.url?.let(ServerAddress::display).orEmpty()) }
    var others by remember { mutableStateOf(existing?.takeUnless { it.isTrackside }?.addresses.orEmpty().joinToString(", ") { ServerAddress.display(it.url) }) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var secret by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<Check>(if (initialCheck == null) Check.Idle else if (initialCheck.isEmpty()) Check.Connected else Check.Failed(initialCheck)) }
    var auth by remember { mutableStateOf(existing?.auth ?: SubsonicAuth.ApiKey) }
    var signIn by remember { mutableStateOf(TracksideSignIn.Code) }
    var approval by remember { mutableStateOf<TracksidePairRequest?>(null) }
    var trustAsked by remember { mutableStateOf<CertificateNeedsTrust?>(null) }
    var inspection by remember {
        mutableStateOf(
            initialInspection ?: existing?.let {
                if (it.isTrackside) Inspection.Trackside(TracksideCapabilities(product = "trackside", name = it.name))
                else Inspection.Subsonic(acceptsApiKey = it.auth == SubsonicAuth.ApiKey)
            },
        )
    }
    var inspected by remember { mutableStateOf(existing?.url?.let(ServerAddress::url)) }
    var attempt by remember { mutableIntStateOf(0) }

    val payload = remember(address) { if (PairingPayload.isLink(address)) runCatching { PairingPayload.parse(address) } else null }
    val url = if (payload != null) null else ServerAddress.url(address)
    val exposed = url?.let(Nearby::isExposed) == true
    val otherUrls = others.split(',', ' ', '\n').filter { it.isNotBlank() }.map { ServerAddress.url(it) }

    LaunchedEffect(url, attempt) {
        if (url == null || exposed || (url == inspected && attempt == 0)) return@LaunchedEffect
        inspection = null
        delay(500)
        val found = actions.inspect(url)
        inspected = url
        inspection = found
        if (found is Inspection.NeedsTrust) trustAsked = found.refusal
    }

    val trackside = (inspection as? Inspection.Trackside)?.capabilities
    val subsonic = inspection as? Inspection.Subsonic
    val methods = remember(trackside) {
        listOfNotNull(
            TracksideSignIn.Code,
            TracksideSignIn.Approval.takeIf { trackside?.auth?.pairRequests != false },
            TracksideSignIn.Password.takeIf { trackside?.auth?.passwordLogin != false },
        )
    }
    val usesKey = subsonic?.acceptsApiKey == true && auth == SubsonicAuth.ApiKey
    val ready = when {
        payload != null -> payload.isSuccess
        url == null || exposed -> false
        trackside != null -> trackside.setupRequired != true && when (signIn) {
            TracksideSignIn.Code -> PairingPayload.typedCode(code) != null
            TracksideSignIn.Approval -> approval == null
            TracksideSignIn.Password -> username.isNotBlank() && secret.isNotEmpty()
        }
        subsonic != null -> (usesKey || username.isNotBlank()) && (secret.isNotEmpty() || existing != null) && otherUrls.none { it == null || Nearby.isExposed(it) }
        else -> false
    }

    suspend fun connect() {
        check = Check.Checking
        val problem = try {
            when {
                payload != null -> actions.pair(payload.getOrThrow()).let { null }
                trackside != null && url != null -> {
                    val pairing = when (signIn) {
                        TracksideSignIn.Code -> ServerLibrary.Pairing.Code(PairingPayload.typedCode(code)!!)
                        TracksideSignIn.Password -> ServerLibrary.Pairing.Password(username, secret)
                        TracksideSignIn.Approval -> {
                            val request = actions.requestApproval(url)
                            approval = request
                            check = Check.Idle
                            val result = actions.awaitApproval(url, request)
                            approval = null
                            result?.let(ServerLibrary.Pairing::Approved) ?: throw IllegalStateException("The code expired. Choose Pair to get a new one.")
                        }
                    }
                    check = Check.Checking
                    actions.pair(existing, name, url, pairing)
                    null
                }
                subsonic != null && url != null -> {
                    val chosen = if (usesKey) SubsonicAuth.ApiKey else SubsonicAuth.Password
                    actions.test(url, username, secret, chosen, existing)
                        ?: actions.save(existing, name, url, username, secret, chosen, otherUrls.filterNotNull()).let { null }
                }
                else -> "That address doesn't look like a music server."
            }
        } catch (error: Exception) {
            error.certificateNeedingTrust()?.let { trustAsked = it }
            approval = null
            error.message ?: "Couldn't connect."
        }
        check = if (problem == null) Check.Connected else Check.Failed(problem)
        haptics.performHapticFeedback(if (problem == null) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        if (problem == null) onDone()
    }

    trustAsked?.let { refusal ->
        TrustDialog(
            refusal = refusal,
            onTrust = {
                actions.trustWhileAdding(refusal)
                trustAsked = null
                check = Check.Idle
                attempt++
            },
            onDismiss = { trustAsked = null },
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (existing == null) "Add server" else "Edit ${existing.name}", style = MaterialTheme.typography.headlineSmallEmphasized)
        if (existing == null) {
            val scan = rememberPairingScanner(
                onLink = { address = it; check = Check.Idle; approval = null },
                onProblem = { check = Check.Failed(it) },
            )
            FilledTonalButton(onClick = scan, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan code")
            }
            // Trackside servers on this network, as they announce themselves; a tap fills the address.
            val context = LocalContext.current
            val nearby by remember(context) { com.luke.tracks.library.trackside.NearbyServers(context).found }
                .collectAsState(initial = emptyList())
            AnimatedVisibility(
                nearby.isNotEmpty(),
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    Text("On your network", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    nearby.forEachIndexed { i, server ->
                        val shown = ServerAddress.display(server.url.toString())
                        SegmentedListItem(
                            selected = address == shown,
                            onClick = { address = shown; check = Check.Idle; approval = null },
                            shapes = ListItemDefaults.segmentedShapes(i, nearby.size),
                            supportingContent = { Text(shown, style = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)) },
                            trailingContent = { if (address == shown) Icon(Icons.Rounded.CheckCircle, "Chosen") },
                        ) { Text("${server.name} · Trackside") }
                    }
                }
            }
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; check = Check.Idle; approval = null },
            label = { Text("Address") },
            placeholder = { Text("music.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            isError = address.isNotBlank() && ((payload == null && url == null) || payload?.isFailure == true || exposed),
            supportingText = when {
                payload?.isFailure == true -> ({ Text(payload.exceptionOrNull()?.message.orEmpty()) })
                payload != null -> ({ Text("Pairing with ${payload.getOrNull()?.name ?: "your server"}") })
                address.isNotBlank() && url == null -> ({ Text("That doesn't look like an address") })
                exposed -> ({ Text(TracksideError.InsecureAddress.message.orEmpty()) })
                else -> null
            },
            trailingIcon = if (url != null && !exposed && inspection == null) ({ LoadingIndicator(Modifier.size(24.dp)) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(visible = url != null && ServerAddress.isUnencrypted(url) && !exposed, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Not encrypted. Tracks uses it only on your home network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        (inspection as? Inspection.Unreachable)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        if (trackside?.setupRequired == true) {
            Text("Finish setting up your server in its dashboard, then pair this phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        if (payload == null && trackside != null) {
            if (methods.size > 1) {
                Choice(methods, signIn, { it.label }) { signIn = it; check = Check.Idle }
            }
            when (signIn) {
                TracksideSignIn.Code -> OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; check = Check.Idle },
                    label = { Text("Code") },
                    placeholder = { Text("Q7RM-4X2K") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                TracksideSignIn.Approval -> ApprovalCode(approval)
                TracksideSignIn.Password -> CredentialFields(username, { username = it; check = Check.Idle }, secret, { secret = it; check = Check.Idle }, "Password", reveal, { reveal = it }, existing = null)
            }
        }
        if (payload == null && subsonic != null) {
            if (subsonic.acceptsApiKey) {
                Choice(listOf(SubsonicAuth.ApiKey, SubsonicAuth.Password), auth, { if (it == SubsonicAuth.ApiKey) "API key" else "Password" }) { auth = it; check = Check.Idle }
            }
            if (usesKey) {
                SecretField(secret, { secret = it; check = Check.Idle }, "API key", reveal, { reveal = it }, existing)
            } else {
                CredentialFields(username, { username = it; check = Check.Idle }, secret, { secret = it; check = Check.Idle }, "Password", reveal, { reveal = it }, existing)
            }
            OutlinedTextField(
                value = others,
                onValueChange = { others = it; check = Check.Idle },
                label = { Text("Other addresses (optional)") },
                placeholder = { Text("nas.tail1234.ts.net:4533") },
                singleLine = true,
                isError = otherUrls.any { it == null || Nearby.isExposed(it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (payload == null && (trackside != null || subsonic != null)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                placeholder = { Text(trackside?.name?.takeIf { it.isNotBlank() } ?: url?.host ?: "Home server") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        CheckLine(check)

        Text(
            when {
                trackside != null && signIn == TracksideSignIn.Password -> "Your password goes to your server once, to pair this phone. Tracks keeps only the key it gets back, encrypted on this phone."
                trackside != null || payload != null -> "Tracks keeps the key your server gives this phone, encrypted on this phone."
                usesKey -> "Tracks keeps the API key encrypted on this phone."
                else -> "Tracks sends a token made from your password, never the password itself, and keeps the password encrypted on this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            Button(
                onClick = { scope.launch { connect() } },
                enabled = ready && check != Check.Checking,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (trackside != null || payload != null) "Pair" else "Save") }
        }
    }
}

/** A choice of two or three, as connected toggle buttons. */
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = {
                    if (option != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(option)
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

/** The six digits to enter in the dashboard, once Pair has asked for them. */
@Composable
private fun ApprovalCode(approval: TracksidePairRequest?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(targetState = approval?.code, transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) }, label = "approval") { shown ->
            if (shown != null) {
                Text(
                    shown,
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = shown.filter(Char::isDigit).toList().joinToString(" ") },
                )
            } else {
                Spacer(Modifier.height(0.dp))
            }
        }
        Text(
            if (approval != null) "In your Trackside dashboard, open My Account and enter this code under Pair a Device."
            else "Choose Pair to show a code here, then approve it in your Trackside dashboard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CredentialFields(
    username: String, onUsername: (String) -> Unit,
    secret: String, onSecret: (String) -> Unit,
    label: String, reveal: Boolean, onReveal: (Boolean) -> Unit,
    existing: SubsonicServer?,
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsername,
        label = { Text("Username") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    SecretField(secret, onSecret, label, reveal, onReveal, existing)
}

@Composable
private fun SecretField(secret: String, onSecret: (String) -> Unit, label: String, reveal: Boolean, onReveal: (Boolean) -> Unit, existing: SubsonicServer?) {
    val haptics = LocalHapticFeedback.current
    OutlinedTextField(
        value = secret,
        onValueChange = onSecret,
        label = { Text(label) },
        placeholder = if (existing != null) ({ Text("Unchanged") }) else null,
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = {
            IconButton(onClick = {
                onReveal(!reveal)
                haptics.performHapticFeedback(if (!reveal) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            }) {
                Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (reveal) "Hide ${label.lowercase()}" else "Show ${label.lowercase()}")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private sealed interface Check {
    data object Idle : Check
    data object Checking : Check
    data object Connected : Check
    data class Failed(val message: String) : Check
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

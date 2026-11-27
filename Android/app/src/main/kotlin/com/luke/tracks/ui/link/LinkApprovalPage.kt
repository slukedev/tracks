package com.luke.tracks.ui.link

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.StatusHero
import com.luke.tracks.ui.settings.Tone
import com.luke.tracks.ui.tv.link.LinkException
import com.luke.tracks.ui.tv.link.LinkProblem
import com.luke.tracks.ui.tv.link.LinkRequest
import com.luke.tracks.ui.tv.link.LocalTvLinker
import com.luke.tracks.ui.tv.link.normalizedUserCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Where approving a TV stands. */
sealed interface Approval {
    /** Waiting for a code to be typed. */
    data object Typing : Approval
    data object LookingUp : Approval
    data class Asking(val request: LinkRequest, val busy: Boolean = false) : Approval
    data class Linked(val deviceName: String) : Approval
    data class Denied(val deviceName: String) : Approval
    data class Failed(val problem: LinkProblem) : Approval
}

/**
 * Approves or turns down a TV linking to a Trackside server this phone is paired with, from a
 * tracks://link QR or a code typed from the server's page ([userCode] null asks for it). It
 * looks the code up first, so the TV's name shows before anything is approved.
 */
@Composable
fun LinkApprovalPage(serverId: String, userCode: String?, onDone: () -> Unit) {
    val graph = LocalContext.current.graph
    val linker = LocalTvLinker.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val servers = (graph.library as? CombinedLibrary)?.servers
    val entries by remember(servers) { servers?.entries ?: MutableStateFlow(emptyList()) }.collectAsState()
    val server = entries.firstOrNull { it.server.isTrackside && (it.server.serverUuid.equals(serverId, ignoreCase = true) || it.server.id == serverId) }?.server
    val target = server?.serverUuid ?: server?.id
    var code by rememberSaveable { mutableStateOf(userCode?.let(::normalizedUserCode)) }
    var approval by remember { mutableStateOf<Approval>(if (code == null) Approval.Typing else Approval.LookingUp) }
    var attempt by remember { mutableStateOf(0) }

    fun run(step: suspend () -> Approval) {
        scope.launch {
            approval = try {
                step()
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkException) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                Approval.Failed(error.problem)
            } catch (_: Exception) {
                Approval.Failed(LinkProblem.Offline)
            }
        }
    }
    LaunchedEffect(target, code, attempt) {
        val id = target ?: return@LaunchedEffect
        val typed = code ?: return@LaunchedEffect
        approval = Approval.LookingUp
        run { Approval.Asking(linker.lookUp(id, typed).clean()) }
    }

    LinkApprovalContent(
        serverName = server?.name,
        userCode = code,
        approval = approval,
        onCode = { code = it },
        onRetry = { attempt++ },
        onApprove = {
            val asking = approval as? Approval.Asking ?: return@LinkApprovalContent
            val id = target ?: return@LinkApprovalContent
            val typed = code ?: return@LinkApprovalContent
            approval = asking.copy(busy = true)
            run {
                Approval.Linked(linker.approve(id, typed).clean().deviceName).also { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
            }
        },
        onDeny = {
            val asking = approval as? Approval.Asking ?: return@LinkApprovalContent
            val id = target ?: return@LinkApprovalContent
            val typed = code ?: return@LinkApprovalContent
            approval = asking.copy(busy = true)
            run { Approval.Denied(linker.deny(id, typed).clean().deviceName) }
        },
        onDone = onDone,
    )
}

/** Only what's safe to show of what the server says about the TV. */
private fun LinkRequest.clean() = copy(
    deviceName = deviceName.filterNot { it.isISOControl() }.trim().take(80).ifEmpty { "TV" },
    platform = platform.filterNot { it.isISOControl() }.trim().take(40),
)

/** The page from plain values, for screenshots. */
@Composable
fun LinkApprovalContent(
    serverName: String?,
    userCode: String?,
    approval: Approval,
    onCode: (String) -> Unit,
    onRetry: () -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDone: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    SettingsScaffold(title = "Link a TV") {
        item(key = "hero") {
            val done: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = { Button(onClick = onDone) { Text("Done") } }
            when {
                serverName == null -> Hero(Tone.Error, "Pair this phone with that server first", actions = done)
                approval is Approval.Linked -> Hero(Tone.Primary, "${approval.deviceName} is linked", actions = done)
                approval is Approval.Denied -> Hero(Tone.Neutral, "${approval.deviceName} wasn't linked", actions = done)
                approval is Approval.Failed -> Hero(Tone.Error, approval.problem.message, actions = {
                    if (approval.problem == LinkProblem.Offline) OutlinedButton(onClick = onRetry) { Text("Try again") }
                    Button(onClick = onDone) { Text("Done") }
                })
                approval is Approval.Asking -> Hero(
                    tone = if (approval.request.nearby) Tone.Secondary else Tone.Error,
                    headline = "Link ${approval.request.deviceName} to $serverName?",
                    body = if (approval.request.nearby) null else "This TV isn't on your network.",
                    actions = {
                        Button(onClick = onApprove, enabled = !approval.busy) { Text("Approve") }
                        OutlinedButton(onClick = onDeny, enabled = !approval.busy) { Text("Deny") }
                    },
                )
                approval == Approval.LookingUp -> Hero(Tone.Secondary, "Finding the TV…")
                else -> Hero(Tone.Secondary, "Enter the code on your TV", actions = {
                    Button(onClick = { normalizedUserCode(typed)?.let(onCode) }, enabled = normalizedUserCode(typed) != null) { Text("Continue") }
                    OutlinedButton(onClick = onDone) { Text("Cancel") }
                })
            }
        }
        if (serverName != null && approval is Approval.Typing) {
            item(key = "field") {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' || c == ' ' }.take(10) },
                    label = { Text("Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { normalizedUserCode(typed)?.let(onCode) }),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (serverName != null && userCode != null && (approval is Approval.Asking || approval == Approval.LookingUp)) {
            item(key = "code") {
                Text(userCode, style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun Hero(tone: Tone, headline: String, body: String? = null, actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
    StatusHero(
        icon = Icons.Rounded.Tv,
        shape = MaterialShapes.Cookie9Sided,
        tone = tone,
        headline = headline,
        body = body,
        actions = actions,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

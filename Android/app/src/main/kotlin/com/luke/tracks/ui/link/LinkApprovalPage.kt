package com.luke.tracks.ui.link

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.luke.tracks.ui.tv.link.LocalTvLinker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Where approving a TV stands. */
sealed interface Approval {
    data object Asking : Approval
    data object Approving : Approval
    data class Linked(val deviceName: String) : Approval
    data class Failed(val problem: LinkProblem) : Approval
}

/**
 * Approves a TV linking to a Trackside server this phone is paired with, from a tracks://link
 * QR or typed from the server's source page ([userCode] null asks for it). Only for a paired
 * server: anything else says so and goes back.
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
    var approval by remember { mutableStateOf<Approval>(Approval.Asking) }
    LinkApprovalContent(
        serverName = server?.name,
        userCode = userCode,
        approval = approval,
        onApprove = { code ->
            val paired = server ?: return@LinkApprovalContent
            approval = Approval.Approving
            scope.launch {
                approval = try {
                    Approval.Linked(linker.approve(paired.serverUuid ?: paired.id, code).trim().take(80).ifEmpty { "TV" }).also {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: LinkException) {
                    Approval.Failed(error.problem)
                } catch (_: Exception) {
                    Approval.Failed(LinkProblem.Offline)
                }
            }
        },
        onDone = onDone,
    )
}

/** The page from plain values, for screenshots. */
@Composable
fun LinkApprovalContent(serverName: String?, userCode: String?, approval: Approval, onApprove: (String) -> Unit, onDone: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val code = userCode ?: normalized(typed)
    SettingsScaffold(title = "Link a TV") {
        item(key = "hero") {
            when {
                serverName == null -> StatusHero(
                    icon = Icons.Rounded.Tv,
                    shape = MaterialShapes.Cookie9Sided,
                    tone = Tone.Error,
                    headline = "Pair this phone with that server first",
                    actions = { Button(onClick = onDone) { Text("Done") } },
                )
                approval is Approval.Linked -> StatusHero(
                    icon = Icons.Rounded.Tv,
                    shape = MaterialShapes.Cookie9Sided,
                    tone = Tone.Primary,
                    headline = "${approval.deviceName} is linked",
                    actions = { Button(onClick = onDone) { Text("Done") } },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                else -> StatusHero(
                    icon = Icons.Rounded.Tv,
                    shape = MaterialShapes.Cookie9Sided,
                    tone = if (approval is Approval.Failed) Tone.Error else Tone.Secondary,
                    headline = if (userCode != null) "Link a TV to $serverName?" else "Enter the code on your TV",
                    body = (approval as? Approval.Failed)?.problem?.message,
                    actions = {
                        Button(
                            onClick = { code?.let(onApprove) },
                            enabled = code != null && approval != Approval.Approving,
                        ) { Text(if (approval == Approval.Approving) "Approving…" else "Approve") }
                        OutlinedButton(onClick = onDone, colors = ButtonDefaults.outlinedButtonColors()) { Text("Cancel") }
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (serverName != null && approval !is Approval.Linked) {
            item(key = "code") {
                if (userCode != null) {
                    Text(
                        userCode,
                        style = androidx.compose.material3.MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(9) },
                        label = { Text("Code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { code?.let(onApprove) }),
                        textStyle = androidx.compose.material3.MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** "Q7RM4X2K" or "q7rm-4x2k" as Trackside shows it, "Q7RM-4X2K"; null until it's whole. */
fun normalized(text: String): String? {
    val plain = text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
    return if (plain.length == 8) plain.take(4) + "-" + plain.drop(4) else null
}

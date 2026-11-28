package com.luke.tracks.ui.library.servers

import com.luke.tracks.system.isTv
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.luke.tracks.graph
import com.luke.tracks.library.CombinedLibrary
import com.luke.tracks.library.ServerAddress
import com.luke.tracks.library.trackside.PairingLinks
import com.luke.tracks.library.trackside.PairingPayload
import kotlinx.coroutines.launch

/**
 * Scans a pairing QR code with Google's code scanner, which shows its own camera screen and
 * needs no camera permission. Hands over the link it read; [onProblem] says why it couldn't.
 */
fun scanPairingCode(context: Context, onLink: (String) -> Unit, onProblem: (String) -> Unit) {
    if (context.isTv) return
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    runCatching {
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                val text = code.rawValue.orEmpty()
                if (PairingPayload.isLink(text)) onLink(text) else onProblem("That isn't a Trackside pairing code.")
            }
            .addOnFailureListener { onProblem("Scanning isn't available on this phone. Paste the link instead.") }
    }.onFailure { onProblem("Scanning isn't available on this phone. Paste the link instead.") }
}

/** Remembers a scanner to start from a button. */
@Composable
fun rememberPairingScanner(onLink: (String) -> Unit, onProblem: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val link by rememberUpdatedState(onLink)
    val problem by rememberUpdatedState(onProblem)
    return remember(context) { { scanPairingCode(context, { link(it) }, { problem(it) }) } }
}

/**
 * Asks before pairing with a `trackside://pair` link opened from outside the app or scanned:
 * a link can come from anywhere, so nothing is sent until you say so.
 */
@Composable
fun PairingLinkPrompt() {
    val context = LocalContext.current
    val pending by PairingLinks.pending.collectAsStateWithLifecycle()
    val link = pending ?: return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val payload = remember(link) { runCatching { PairingPayload.parse(link) } }
    var pairing by remember(link) { mutableStateOf(false) }
    var problem by remember(link) { mutableStateOf(payload.exceptionOrNull()?.message) }
    val servers = (context.graph.library as? CombinedLibrary)?.servers
    val name = payload.getOrNull()?.name?.takeIf { it.isNotBlank() } ?: "this server"
    // Where it would connect: the name is whatever the link says, the addresses are what count.
    val hosts = payload.getOrNull()?.urls.orEmpty().map { ServerAddress.hostPort(it.url) }.distinct()

    AlertDialog(
        onDismissRequest = { if (!pairing) PairingLinks.clear() },
        title = { Text(if (problem != null) "Can't pair" else "Pair with $name?") },
        text = { Text(problem ?: hosts.joinToString("\n")) },
        confirmButton = {
            if (problem == null && servers != null) {
                TextButton(
                    enabled = !pairing,
                    onClick = {
                        val offer = payload.getOrNull() ?: return@TextButton
                        pairing = true
                        scope.launch {
                            runCatching { servers.pair(offer) }
                                .onSuccess {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    PairingLinks.clear()
                                }
                                .onFailure {
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                    problem = it.message ?: "Couldn't pair. Try again."
                                }
                            pairing = false
                        }
                    },
                ) { Text(if (pairing) "Pairing…" else "Pair") }
            }
        },
        dismissButton = { TextButton(enabled = !pairing, onClick = { PairingLinks.clear() }) { Text(if (problem != null) "OK" else "Cancel") } },
    )
}

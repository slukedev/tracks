package com.luke.tracks.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.lastfm.LastFm
import com.luke.tracks.lastfm.ScrobbleWorker
import com.luke.tracks.lastfm.lastFm
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Connecting Last.fm, scrobbling, and bringing its history in. */
@Composable
fun LastFmPage() {
    val context = LocalContext.current
    val graph = context.graph
    val lastFm = graph.lastFm
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val connection by lastFm.connection.collectAsStateWithLifecycle()
    val import by lastFm.import.collectAsStateWithLifecycle()
    val problem by lastFm.problem.collectAsStateWithLifecycle()
    val owed by remember(connection) {
        if (connection is LastFm.Connection.Connected) lastFm.owedCount() else flowOf(0)
    }.collectAsStateWithLifecycle(0)
    var confirmDisconnect by remember { mutableStateOf(false) }
    var offerImport by remember { mutableStateOf(false) }

    // Coming back from the approval page: ask Last.fm straight away.
    LifecycleResumeEffect(connection) {
        if (connection is LastFm.Connection.Waiting) lastFm.checkNow()
        onPauseOrDispose {}
    }
    var previous by remember { mutableStateOf(connection) }
    LaunchedEffect(connection) {
        if (previous is LastFm.Connection.Waiting && connection is LastFm.Connection.Connected) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            if (lastFm.import.value is LastFm.Import.Idle) offerImport = true
        }
        if (connection is LastFm.Connection.Failed && previous !is LastFm.Connection.Failed) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
        }
        previous = connection
    }

    SettingsScaffold(title = "Last.fm") {
        item(key = "hero") {
            val state = connection
            val (headline, body) = when (state) {
                LastFm.Connection.NotConfigured -> "Last.fm isn't set up in this build" to
                    "This copy of Tracks was built without a Last.fm key, so it can't connect. Everything else works as usual."
                LastFm.Connection.Idle -> "Scrobble to Last.fm" to
                    "Every song Tracks keeps goes to your Last.fm profile, radio included."
                LastFm.Connection.Waiting -> "Waiting for Last.fm" to
                    "Allow Tracks on the Last.fm page, then come back here."
                is LastFm.Connection.Failed -> "Couldn't connect" to state.message
                is LastFm.Connection.Connected -> (if (settings.scrobbleEnabled) "Scrobbling as ${state.username}" else "Connected as ${state.username}") to
                    (if (settings.scrobbleEnabled) "Every song Tracks keeps goes to Last.fm, radio included." else "Nothing is sent until you turn scrobbling back on.")
            }
            StatusHero(
                icon = Icons.Rounded.GraphicEq,
                shape = MaterialShapes.SoftBurst,
                tone = when (state) {
                    is LastFm.Connection.Connected -> Tone.Primary
                    is LastFm.Connection.Failed -> Tone.Error
                    LastFm.Connection.NotConfigured -> Tone.Neutral
                    else -> Tone.Tertiary
                },
                headline = headline,
                body = body,
                modifier = Modifier.animateItem(),
                actions = when (state) {
                    LastFm.Connection.Idle, is LastFm.Connection.Failed -> {
                        { Button(onClick = { lastFm.connect(context) }) { Text(if (state is LastFm.Connection.Failed) "Try again" else "Connect to Last.fm") } }
                    }
                    LastFm.Connection.Waiting -> {
                        {
                            LoadingIndicator(Modifier.size(40.dp))
                            FilledTonalButton(onClick = { lastFm.checkNow() }) { Text("I've allowed it") }
                            TextButton(onClick = { lastFm.cancel() }) { Text("Cancel") }
                        }
                    }
                    else -> null
                },
            )
        }
        val state = connection
        if (state !is LastFm.Connection.Connected) {
            group("With Last.fm", key = "what") {
                custom {
                    Rule(Icons.Rounded.CloudUpload, "Every song Tracks keeps is scrobbled to your profile, radio included.")
                    Rule(Icons.Rounded.CloudDownload, "Bring the songs you scrobbled before into your Tracks history.")
                    Rule(Icons.Rounded.WifiOff, "Offline? Scrobbles wait and go when you're back.", last = true)
                }
            }
            if (state != LastFm.Connection.NotConfigured) {
                footnote("Tracks opens Last.fm so you can allow it. Your password is never shared with Tracks.", key = "how")
            }
            return@SettingsScaffold
        }
        group("Scrobbling", key = "scrobbling") {
            toggle(
                "Scrobble what you play",
                checked = settings.scrobbleEnabled,
                summary = if (graph.sampleData) "Sample plays are never sent" else "Sent as each song is kept",
                icon = Icons.Rounded.CloudUpload,
            ) { on ->
                scope.launch { graph.settings.update { it.copy(scrobbleEnabled = on) } }
                if (on) ScrobbleWorker.enqueue(context)
            }
            if (settings.scrobbleEnabled && owed > 0) {
                link(
                    "Waiting to send",
                    summary = (if (owed == 1) "1 song" else "${NumberFormat.getIntegerInstance().format(owed)} songs") + ". Tap to send now.",
                    icon = Icons.Rounded.CloudUpload,
                    tone = Tone.Secondary,
                ) { ScrobbleWorker.enqueue(context) }
            }
            val trouble = problem
            if (settings.scrobbleEnabled && trouble != null) {
                link(trouble, summary = "Tracks will try again", icon = Icons.Rounded.ErrorOutline, tone = Tone.Error) {
                    if (trouble == LastFm.RECONNECT) lastFm.connect(context) else ScrobbleWorker.enqueue(context)
                }
            }
        }
        group("Your Last.fm history", key = "history") {
            val current = import
            link(
                "Import from Last.fm",
                summary = when (current) {
                    LastFm.Import.Idle -> "Bring in songs you scrobbled before Tracks"
                    is LastFm.Import.Running -> if (current.added == 0) "Reading your history" else "Added ${count(current.added)} so far"
                    is LastFm.Import.Done -> if (current.added == 0) "Already up to date" else "Added ${count(current.added)}"
                    is LastFm.Import.Failed -> current.message
                },
                icon = Icons.Rounded.CloudDownload,
                tone = Tone.Tertiary,
                enabled = current !is LastFm.Import.Running && !graph.sampleData,
                trailing = if (current is LastFm.Import.Running) ({ LoadingIndicator(Modifier.size(32.dp)) }) else null,
            ) { lastFm.startImport() }
        }
        group(key = "account") {
            link("Open your profile", summary = "last.fm/user/${state.username}", icon = Icons.Rounded.OpenInNew, tone = Tone.Neutral) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/user/${Uri.encode(state.username)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            link("Disconnect", summary = "Songs already sent stay on Last.fm", icon = Icons.AutoMirrored.Rounded.Logout, tone = Tone.Error) {
                confirmDisconnect = true
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Last.fm?") },
            text = { Text("Tracks stops scrobbling and forgets the account on this phone. Your history in Tracks stays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    lastFm.disconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
    if (offerImport) {
        AlertDialog(
            onDismissRequest = { offerImport = false },
            title = { Text("Import your Last.fm history?") },
            text = { Text("Tracks adds the songs you scrobbled before, so your charts and mixes start full. Songs Tracks already has are skipped.") },
            confirmButton = { TextButton(onClick = { offerImport = false; lastFm.startImport() }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { offerImport = false }) { Text("Not now") } },
        )
    }
}

private fun count(n: Int) = if (n == 1) "1 song" else "${NumberFormat.getIntegerInstance().format(n)} songs"

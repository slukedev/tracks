package com.luke.tracks.ui.spotify

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NetworkCell
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.spotify.SpotifyProblem
import com.luke.tracks.spotify.SpotifyQuality
import com.luke.tracks.spotify.SpotifyService
import com.luke.tracks.spotify.SpotifyState
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.Tone
import com.luke.tracks.ui.settings.group
import com.luke.tracks.ui.settings.link

/**
 * Settings > Sources > Spotify: sign in through the browser, and once in, the account, its
 * quality on Wi-Fi and cellular, and Sign Out.
 */
@Composable
fun SpotifySourcePage(service: SpotifyService, onDone: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by service.state.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }

    // Back from the browser: a closed tab with no redirect is a cancel.
    LifecycleResumeEffect(state) {
        if (state == SpotifyState.SigningIn) service.browserClosed()
        onPauseOrDispose {}
    }
    var previous by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        val now = state
        if (previous == SpotifyState.Checking && now is SpotifyState.Connected && !now.refused) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        if (now is SpotifyState.Failed) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        previous = now
    }

    SpotifySourcePageContent(
        state = state,
        quality = service.quality.collectAsStateWithLifecycle().value,
        onSignIn = { service.beginSignIn()?.let { openInBrowser(context, it) } },
        onDone = {
            service.dismissProblem()
            onDone()
        },
        onTryAgain = {
            when (state) {
                is SpotifyState.Connected -> service.tryAgain()
                else -> service.beginSignIn()?.let { openInBrowser(context, it) }
            }
        },
        onRemove = { confirmSignOut = true },
        onQuality = service::setQuality,
    )

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Spotify?") },
            text = { Text("Your plays stay in History. Spotify playlists leave your library.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    service.signOut()
                    onDone()
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

/** The page drawn from its state alone, so screenshots and tests need no account. */
@Composable
fun SpotifySourcePageContent(
    state: SpotifyState,
    quality: Pair<SpotifyQuality, SpotifyQuality>,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    onTryAgain: () -> Unit,
    onRemove: () -> Unit,
    onQuality: (wifi: SpotifyQuality, cellular: SpotifyQuality) -> Unit,
) {
    val connected = state as? SpotifyState.Connected
    SettingsScaffold(title = "Spotify") {
        item(key = "hero") {
            Hero(state, onSignIn, onDone, onTryAgain, onRemove, Modifier.animateItem())
        }
        if (connected == null) return@SettingsScaffold
        group("Quality", key = "quality") {
            val (wifi, cellular) = quality
            row { index, count -> QualityRow("Wi-Fi", Icons.Rounded.Wifi, wifi, index, count) { onQuality(it, cellular) } }
            row { index, count -> QualityRow("Cellular", Icons.Rounded.NetworkCell, cellular, index, count) { onQuality(wifi, it) } }
        }
        group(key = "account") {
            link("Sign out", icon = Icons.AutoMirrored.Rounded.Logout, tone = Tone.Error, onClick = onRemove)
        }
    }
}

@Composable
private fun Hero(
    state: SpotifyState,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    onTryAgain: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 20.dp),
    ) {
        AnimatedContent(state.step(), label = "spotify") { step ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpotifyMark(Modifier.size(40.dp), monochrome = false)
                    Spacer(Modifier.size(16.dp))
                    when (step) {
                        Step.Connected -> {
                            val name = (state as? SpotifyState.Connected)?.name
                            Text(
                                listOfNotNull(name, "Connected").joinToString(" · "),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Step.Checking -> {
                            Text("Checking…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            LoadingIndicator(Modifier.size(24.dp))
                        }
                        Step.Idle, Step.SigningIn -> Text("Needs Premium", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Step.Premium -> Problem(SpotifyProblem.NeedsPremium)
                        Step.Offline -> Problem(SpotifyProblem.Offline)
                        Step.Refused -> Problem(SpotifyProblem.Refused)
                    }
                }
                when (step) {
                    Step.Idle, Step.SigningIn -> SignInButton(signingIn = step == Step.SigningIn, onClick = onSignIn)
                    Step.Premium -> Button(onClick = onDone) { Text("Done") }
                    Step.Offline -> Button(onClick = onTryAgain) { Text("Try again") }
                    Step.Refused -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onTryAgain) { Text("Try again") }
                        OutlinedButton(onClick = onRemove) { Text("Remove") }
                    }
                    else -> Unit
                }
            }
        }
    }
}

private enum class Step { Idle, SigningIn, Checking, Premium, Offline, Refused, Connected }

private fun SpotifyState.step(): Step = when (this) {
    SpotifyState.SignedOut, is SpotifyState.NeedsSignIn -> Step.Idle
    SpotifyState.SigningIn -> Step.SigningIn
    SpotifyState.Checking -> Step.Checking
    is SpotifyState.Failed -> if (problem == SpotifyProblem.NeedsPremium) Step.Premium else Step.Offline
    is SpotifyState.Connected -> if (refused) Step.Refused else Step.Connected
}

@Composable
private fun Problem(problem: SpotifyProblem) {
    Text(problem.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
}

/** Spotify's own button: black on light pages, white on dark. */
@Composable
private fun SignInButton(signingIn: Boolean, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Button(
        onClick = onClick,
        enabled = !signingIn,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) Color.White else Color.Black,
            contentColor = if (dark) Color.Black else Color.White,
        ),
        modifier = Modifier.height(56.dp),
        contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
    ) {
        Text(if (signingIn) "Signing in…" else "Sign in with Spotify", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun QualityRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: SpotifyQuality,
    index: Int,
    count: Int,
    onPick: (SpotifyQuality) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.SegmentedListItem(
            onClick = { open = true },
            shapes = androidx.compose.material3.ListItemDefaults.segmentedShapes(index = index, count = count),
            leadingContent = { com.luke.tracks.ui.settings.ToneIcon(icon, Tone.Secondary) },
            supportingContent = { Text(value.label) },
            colors = com.luke.tracks.ui.settings.rowColors(),
        ) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SpotifyQuality.entries.forEach { quality ->
                DropdownMenuItem(
                    text = { Text(quality.label) },
                    onClick = {
                        open = false
                        onPick(quality)
                    },
                    trailingIcon = if (quality == value) ({ Icon(Icons.Rounded.Check, contentDescription = "Selected") }) else null,
                )
            }
        }
    }
}

/** Spotify's sign-in page in a Custom Tab, sharing the browser's cookies so a signed-in person taps once. */
fun openInBrowser(context: Context, url: String) {
    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
        .onFailure {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
}

package com.luke.tracks.ui.applemusic

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.applemusic.AppleMusic
import com.luke.tracks.applemusic.AppleMusicProblem
import com.luke.tracks.applemusic.AppleMusicService
import com.luke.tracks.applemusic.AppleMusicState
import com.luke.tracks.sources.SourceId
import com.luke.tracks.sources.SourceKind
import com.luke.tracks.sources.SourceSelection
import com.luke.tracks.ui.settings.SettingsScaffold
import com.luke.tracks.ui.settings.Tone
import com.luke.tracks.ui.settings.footnote
import com.luke.tracks.ui.settings.group
import com.luke.tracks.ui.settings.link
import com.luke.tracks.ui.sources.SourceGlyph
import com.luke.tracks.ui.sources.rememberSources
import kotlinx.coroutines.launch

/**
 * Settings > Sources > Apple Music: sign in through the Apple Music app, and once in, the
 * library and Remove. Also shown from Add Source before Apple Music is added.
 */
@Composable
fun AppleMusicSourcePage(service: AppleMusicService, onDone: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val registry = rememberSources()
    val state by service.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }

    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        service.signInFinished(result.data)
    }
    // Back from the Play Store with the app installed: ready to sign in.
    LifecycleResumeEffect(state) {
        val now = state
        if (now is AppleMusicState.Failed && now.problem == AppleMusicProblem.NeedsApp && AppleMusic.isAppInstalled(context)) service.dismissProblem()
        onPauseOrDispose {}
    }
    var previous by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        val now = state
        if (previous == AppleMusicState.Checking && now is AppleMusicState.Connected) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (now is AppleMusicState.Failed) haptics.performHapticFeedback(HapticFeedbackType.Reject)
        previous = now
    }

    AppleMusicSourcePageContent(
        state = state,
        onSignIn = {
            scope.launch {
                val intent = service.beginSignIn() ?: return@launch
                runCatching { signIn.launch(intent) }.onFailure { service.signInFinished(null) }
            }
        },
        onDone = {
            service.dismissProblem()
            onDone()
        },
        onTryAgain = service::tryAgain,
        onGetApp = { openStore(context) },
        onLibrary = {
            registry.select(SourceSelection.Only(SourceId.AppleMusic))
            onDone()
        },
        onRemove = { confirmRemove = true },
    )

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove Apple Music?") },
            text = { Text("Your plays stay in History.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    service.signOut()
                    onDone()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** The page drawn from its state alone, so screenshots and tests need no account. */
@Composable
fun AppleMusicSourcePageContent(
    state: AppleMusicState,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    onTryAgain: () -> Unit,
    onGetApp: () -> Unit,
    onLibrary: () -> Unit,
    onRemove: () -> Unit,
) {
    val connected = state is AppleMusicState.Connected
    SettingsScaffold(title = "Apple Music") {
        item(key = "hero") {
            Hero(state, onSignIn, onDone, onTryAgain, onGetApp, onRemove, Modifier.animateItem())
        }
        if (connected) {
            group(key = "library") {
                link("Library", icon = Icons.Rounded.LibraryMusic, onClick = onLibrary)
            }
            group(key = "account") {
                link("Remove", icon = Icons.AutoMirrored.Rounded.Logout, tone = Tone.Error, onClick = onRemove)
            }
        }
        footnote(TRADEMARKS, key = "trademarks")
    }
}

/** Apple's credit line, where Tracks' legal copy appears; once in the app. */
const val TRADEMARKS = "Apple and Apple Music are trademarks of Apple Inc., registered in the U.S. and other countries."

private enum class Step { Idle, SigningIn, Checking, Problem, Connected }

private fun AppleMusicState.step(): Step = when (this) {
    AppleMusicState.SignedOut -> Step.Idle
    AppleMusicState.SigningIn -> Step.SigningIn
    AppleMusicState.Checking -> Step.Checking
    is AppleMusicState.Failed, is AppleMusicState.NeedsSignIn -> Step.Problem
    is AppleMusicState.Connected -> Step.Connected
}

@Composable
private fun Hero(
    state: AppleMusicState,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    onTryAgain: () -> Unit,
    onGetApp: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 20.dp),
    ) {
        AnimatedContent(state.step() to state, contentKey = { it.first }, transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, label = "apple-music") { (step, shown) ->
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceGlyph(SourceKind.AppleMusic)
                    Spacer(Modifier.size(16.dp))
                    when (step) {
                        Step.Connected -> Text("Connected", style = MaterialTheme.typography.titleMedium)
                        Step.Checking -> {
                            Text("Checking…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            LoadingIndicator(Modifier.size(24.dp))
                        }
                        Step.Idle, Step.SigningIn -> Text("Apple Music", style = MaterialTheme.typography.titleMedium)
                        Step.Problem -> Text(
                            shown.problem()?.message.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                when (step) {
                    Step.Idle, Step.SigningIn -> SignInButton(signingIn = step == Step.SigningIn, onClick = onSignIn)
                    Step.Problem -> Actions(shown, onSignIn, onDone, onTryAgain, onGetApp, onRemove)
                    else -> Unit
                }
            }
        }
    }
}

private fun AppleMusicState.problem(): AppleMusicProblem? = when (this) {
    is AppleMusicState.Failed -> problem
    is AppleMusicState.NeedsSignIn -> problem
    else -> null
}

@Composable
private fun Actions(state: AppleMusicState, onSignIn: () -> Unit, onDone: () -> Unit, onTryAgain: () -> Unit, onGetApp: () -> Unit, onRemove: () -> Unit) {
    val problem = state.problem() ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state is AppleMusicState.NeedsSignIn -> {
                if (problem == AppleMusicProblem.SignedOut) SignInButton(signingIn = false, onClick = onSignIn) else Button(onClick = onTryAgain) { Text("Try again") }
                OutlinedButton(onClick = onRemove) { Text("Remove") }
            }
            problem == AppleMusicProblem.NeedsApp -> Button(onClick = onGetApp) { Text("Get Apple Music") }
            problem.retryable -> Button(onClick = onSignIn) { Text("Try again") }
            else -> Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun SignInButton(signingIn: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !signingIn,
        modifier = Modifier.height(56.dp),
        contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
    ) {
        Text(if (signingIn) "Signing in…" else "Sign in", style = MaterialTheme.typography.titleMedium)
    }
}

/** Apple Music's Play Store page, in the store when it's there. */
private fun openStore(context: Context) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${AppleMusic.APP_PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${AppleMusic.APP_PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(app)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(web) }
    }
}

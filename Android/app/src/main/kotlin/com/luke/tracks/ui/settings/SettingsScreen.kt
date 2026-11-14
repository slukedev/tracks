package com.luke.tracks.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.BuildConfig
import com.luke.tracks.capture.CaptureAccess
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.SampleData
import com.luke.tracks.data.ThemeChoice
import com.luke.tracks.graph
import com.luke.tracks.lastfm.LastFm
import com.luke.tracks.lastfm.lastFm
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.currentNavigator
import kotlinx.coroutines.launch

/**
 * Settings, laid out like Android 16's own: a large title that folds into the bar, and
 * rows in joined groups, each saying where its page stands.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val navigator = currentNavigator()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val lastFm by graph.lastFm.connection.collectAsStateWithLifecycle()
    val access = rememberCaptureAccess()
    var confirmSample by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    fun open(page: String) = navigator.open(SettingsPageRoute(page))

    SettingsScaffold(title = "Settings", showBack = navigator.backStack.size > 1) {
        if (settings.captureOtherApps && !access && !graph.sampleData) {
            item(key = "access") { AccessBanner(Modifier.animateItem()) { context.startActivity(CaptureAccess.settingsIntent(context)) } }
        }
        group("Listening") {
            link(
                "Listening history",
                summary = historySummary(settings, access, graph.sampleData),
                icon = Icons.Rounded.History,
                tone = Tone.Primary,
            ) { open("capture") }
            link("Last.fm", summary = lastFmSummary(lastFm, settings), icon = Icons.Rounded.GraphicEq, tone = Tone.Error) { open("lastfm") }
            link("Your Music servers", summary = "Navidrome and other Subsonic servers", icon = Icons.Rounded.Dns, tone = Tone.Secondary) {
                open("servers")
            }
        }
        group("Play") {
            link("Tracks Radio", summary = RadioLevels.at(settings.radioAdventure).name, icon = Icons.Rounded.Radio, tone = Tone.Tertiary) {
                open("radio")
            }
            link(
                "Hear less of",
                summary = when (val n = settings.hiddenSongs.size) {
                    0 -> "Songs you ask to hear less of"
                    1 -> "1 song"
                    else -> "$n songs"
                },
                icon = Icons.Rounded.ThumbDown,
                tone = Tone.Secondary,
            ) { open("hidden") }
        }
        group("Look and feel") {
            link("Appearance", summary = appearanceSummary(settings), icon = Icons.Rounded.Palette, tone = Tone.Primary) { open("appearance") }
            link("App language", summary = "Follows your phone unless you pick one", icon = Icons.Rounded.Language, tone = Tone.Tertiary) {
                openAppLanguage(context)
            }
        }
        group("Tracks") {
            toggle(
                "Sample data",
                checked = graph.sampleData,
                summary = if (graph.sampleData) "Showing invented listening. Your own history is safe." else "Try Tracks with invented listening",
                icon = Icons.Rounded.Science,
                tone = Tone.Tertiary,
            ) { confirmSample = it }
            link("Privacy", summary = "Your history stays on this phone", icon = Icons.Rounded.Shield, tone = Tone.Secondary) { open("privacy") }
            link("About Tracks", summary = "Version ${BuildConfig.VERSION_NAME}", icon = Icons.Rounded.Info, tone = Tone.Neutral) { open("about") }
        }
    }

    confirmSample?.let { on ->
        val haptics = LocalHapticFeedback.current
        AlertDialog(
            onDismissRequest = { confirmSample = null },
            title = { Text(if (on) "Show sample data?" else "Back to your history?") },
            text = {
                Text(
                    if (on) "Tracks restarts with ten months of invented listening, kept apart from yours. Nothing is kept or scrobbled while it's on."
                    else "Tracks restarts with your own history.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.toggle(on)
                    confirmSample = null
                    scope.launch {
                        graph.settings.update { it.copy(showSampleData = on) }
                        SampleData.switch(context, on)
                    }
                }) { Text("Restart") }
            },
            dismissButton = { TextButton(onClick = { confirmSample = null }) { Text("Cancel") } },
        )
    }
}

/** Whether notification access is on, checked again each time Tracks comes back. */
@Composable
fun rememberCaptureAccess(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(CaptureAccess.isGranted(context)) }
    LifecycleResumeEffect(Unit) {
        granted = CaptureAccess.isGranted(context)
        onPauseOrDispose {}
    }
    return granted
}

@Composable
private fun AccessBanner(modifier: Modifier = Modifier, onAllow: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialShapes.Cookie9Sided.toShape(), modifier = Modifier.size(48.dp)) {}
                    Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.size(16.dp))
                Text("Keep songs from every app", style = MaterialTheme.typography.titleMediumEmphasized)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Allow notification access and Tracks keeps what you play in Spotify, YouTube Music and radio apps. It only reads what's playing, and nothing leaves your phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onAllow) { Text("Allow access") }
            }
        }
    }
}

fun historySummary(settings: AppSettings, access: Boolean, sample: Boolean): String = when {
    sample -> "Paused while sample data is on"
    !settings.captureOtherApps -> "Only songs played in Tracks"
    !access -> "Needs notification access"
    settings.excludedApps.isEmpty() -> "Keeping songs from every app"
    else -> "Keeping songs from your music apps"
}

fun lastFmSummary(state: LastFm.Connection, settings: AppSettings): String = when (state) {
    LastFm.Connection.NotConfigured -> "Not set up in this build"
    LastFm.Connection.Idle -> "Not connected"
    LastFm.Connection.Waiting -> "Waiting for Last.fm"
    is LastFm.Connection.Failed -> "Couldn't connect"
    is LastFm.Connection.Connected -> if (settings.scrobbleEnabled) "Scrobbling as ${state.username}" else "Connected as ${state.username}, not scrobbling"
}

fun appearanceSummary(settings: AppSettings): String {
    val theme = when (settings.theme) {
        ThemeChoice.System -> "System theme"
        ThemeChoice.Light -> "Light"
        ThemeChoice.Dark -> "Dark"
    }
    val colors = if (settings.colors == ColorChoice.Tracks) "Tracks red" else "wallpaper colors"
    return "$theme, $colors"
}

/** Android's per-app language page, or the app's info page where there isn't one. */
fun openAppLanguage(context: Context) {
    val uri = Uri.fromParts("package", context.packageName, null)
    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

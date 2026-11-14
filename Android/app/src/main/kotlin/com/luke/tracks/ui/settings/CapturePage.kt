package com.luke.tracks.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.capture.CaptureAccess
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.currentNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Listening history: keeping what other apps play, and which apps. */
@Composable
fun CapturePage() {
    val context = LocalContext.current
    val graph = context.graph
    val navigator = currentNavigator()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val access = rememberCaptureAccess()

    // A small thank you the moment access arrives.
    var hadAccess by remember { mutableStateOf(access) }
    LaunchedEffect(access) {
        if (access && !hadAccess) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        hadAccess = access
    }

    val (headline, body) = when {
        graph.sampleData -> "Paused for sample data" to "Nothing is kept while sample data is on."
        !settings.captureOtherApps -> "Only songs played in Tracks" to "Songs you play in other apps aren't kept. Tracks' own player always keeps its songs."
        !access -> "Tracks can't see other apps yet" to "Android calls this notification access. Tracks only reads what's playing from music apps' media controls, never your notifications."
        else -> "Keeping songs from all music apps" to "Spotify, YouTube Music, radio apps and others. A song is kept once it has played for half its length or four minutes."
    }

    SettingsScaffold(title = "Listening history") {
        item(key = "hero") {
            StatusHero(
                icon = Icons.Rounded.History,
                shape = MaterialShapes.Cookie12Sided,
                tone = if (access && settings.captureOtherApps) Tone.Primary else Tone.Tertiary,
                headline = headline,
                body = body,
                modifier = Modifier.animateItem(),
            ) {
                if (settings.captureOtherApps && !graph.sampleData) {
                    if (!access) {
                        Button(onClick = { context.startActivity(CaptureAccess.settingsIntent(context)) }) {
                            Text("Allow notification access")
                        }
                    } else {
                        OutlinedButton(onClick = { context.startActivity(CaptureAccess.settingsIntent(context)) }) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Access allowed")
                        }
                    }
                }
            }
        }
        group(key = "keep") {
            toggle(
                "Keep songs from other apps",
                checked = settings.captureOtherApps,
                summary = "Music and radio apps, while they play",
                icon = Icons.Rounded.MusicNote,
            ) { on -> scope.launch { graph.settings.update { it.copy(captureOtherApps = on) } } }
            if (settings.captureOtherApps) {
                link(
                    "Choose apps",
                    summary = when (val n = settings.excludedApps.size) {
                        0 -> "All music apps"
                        1 -> "1 app left out"
                        else -> "$n apps left out"
                    },
                    icon = Icons.Rounded.Apps,
                    tone = Tone.Secondary,
                ) { navigator.open(SettingsPageRoute("apps")) }
            }
        }
        group("What's kept", key = "rules") {
            custom {
                Rule(Icons.Rounded.Check, "Songs that play for half their length, or four minutes. The same rule as Last.fm.")
                Rule(Icons.Rounded.Radio, "Songs on radio stations, with the station's name.")
                Rule(Icons.Rounded.Podcasts, "Not podcasts, audiobooks, videos or ads.")
                Rule(Icons.Rounded.Block, "Not the apps you leave out.", last = true)
            }
        }
        footnote("Everything Tracks keeps stays on this phone, unless you connect Last.fm.")
    }
}

@Composable
internal fun Rule(icon: ImageVector, text: String, last: Boolean = false) {
    Row(Modifier.padding(bottom = if (last) 0.dp else 14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The music apps on the phone, each with a switch for whether its songs are kept. */
@Composable
fun AppsPage() {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val apps by produceState<List<CaptureAccess.MusicApp>?>(null) {
        value = CaptureAccess.musicApps(context, settings.excludedApps)
    }

    SettingsScaffold(title = "Choose apps", subtitle = "Songs from apps that are on are kept") {
        val list = apps
        when {
            list == null -> item(key = "loading") {
                Box(Modifier.padding(48.dp).animateItem(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
            list.isEmpty() -> item(key = "empty") {
                StatusHero(
                    icon = Icons.Rounded.MusicNote,
                    shape = MaterialShapes.Flower,
                    tone = Tone.Secondary,
                    headline = "No music apps yet",
                    body = "Apps show up here once they've played something, or when they offer their music to Android.",
                    modifier = Modifier.animateItem(),
                )
            }
            else -> group(key = "apps") {
                list.forEach { app ->
                    var menu by key(app.packageName) { remember { mutableStateOf(false) } }
                    toggle(
                        app.label,
                        checked = app.packageName !in settings.excludedApps,
                        leading = {
                            Box {
                                AppIcon(app.packageName)
                                AppMenu(app, expanded = menu, onDismiss = { menu = false })
                            }
                        },
                        onLongClick = { menu = true },
                    ) { on ->
                        scope.launch {
                            graph.settings.update {
                                it.copy(excludedApps = if (on) it.excludedApps - app.packageName else it.excludedApps + app.packageName)
                            }
                        }
                    }
                }
            }
        }
        footnote("Press and hold an app for more.", key = "hint")
    }
}

@Composable
private fun AppMenu(app: CaptureAccess.MusicApp, expanded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Open ${app.label}") },
            leadingIcon = { Icon(Icons.Rounded.OpenInNew, contentDescription = null) },
            onClick = {
                onDismiss()
                context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                    runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            },
        )
        DropdownMenuItem(
            text = { Text("App info") },
            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
            onClick = {
                onDismiss()
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

/** The app's own icon, read off the main thread. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(120, 120).asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(40.dp).clip(CircleShape))
    } else {
        ToneIcon(Icons.Rounded.MusicNote, Tone.Neutral, modifier)
    }
}

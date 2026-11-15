package com.luke.tracks.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import com.luke.tracks.discover.discovery
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.BuildConfig
import com.luke.tracks.data.AppSettings
import com.luke.tracks.data.ColorChoice
import com.luke.tracks.data.SampleData
import com.luke.tracks.data.ThemeChoice
import com.luke.tracks.graph
import com.luke.tracks.playback.radio.RadioWords
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
    var confirmSample by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    fun open(page: String) = navigator.open(SettingsPageRoute(page))

    SettingsScaffold(title = "Settings", showBack = navigator.backStack.size > 1) {
        group("Listening") {
            link("Last.fm", summary = lastFmSummary(lastFm, settings), icon = Icons.Rounded.GraphicEq, tone = Tone.Error) { open("lastfm") }
            link("Your Music servers", summary = "Navidrome and other Subsonic servers", icon = Icons.Rounded.Dns, tone = Tone.Secondary) {
                open("servers")
            }
            link("Get music", summary = getMusicSummary(graph), icon = Icons.Rounded.CloudDownload, tone = Tone.Tertiary) {
                open("getmusic")
            }
        }
        group("Play") {
            val radio = if (settings.showTracksRadio) RadioWords.shortSummary(settings.radioDiscovery, settings.radioGenres) else "Hidden"
            link("Tracks Radio", summary = radio, icon = Icons.Rounded.Radio, tone = Tone.Tertiary) {
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
                summary = if (graph.sampleData) "On. Your own history is kept separately." else "Invented listening history for trying Tracks",
                icon = Icons.Rounded.Science,
                tone = Tone.Tertiary,
            ) { confirmSample = it }
            link("Privacy", summary = "What Tracks keeps and where", icon = Icons.Rounded.Shield, tone = Tone.Secondary) { open("privacy") }
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
                    if (on) "Tracks restarts with ten months of invented listening, stored separately from yours. Nothing is kept or scrobbled while it's on."
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

/** Whether anything can get music you don't have: a server with Octo, or Lidarr. */
@Composable
private fun getMusicSummary(graph: com.luke.tracks.AppGraph): String {
    val discovery = remember(graph) { graph.discovery }
    val sources by discovery.sources.collectAsStateWithLifecycle()
    return when {
        sources.octo.isNotEmpty() && sources.lidarr -> "${sources.octo.first()} and Lidarr"
        sources.octo.isNotEmpty() -> "With ${sources.octo.first()}"
        sources.lidarr -> "With Lidarr"
        else -> "Octo and Lidarr"
    }
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
    val colors = if (settings.colors == ColorChoice.Custom) "your color" else "wallpaper colors"
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

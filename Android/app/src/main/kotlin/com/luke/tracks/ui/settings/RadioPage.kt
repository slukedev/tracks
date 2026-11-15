package com.luke.tracks.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.radio.RadioWords
import com.luke.tracks.playback.radio.radioGenreChoices
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.currentNavigator
import kotlinx.coroutines.launch
import uniffi.tracks_engine.RadioDiscovery

/**
 * Tracks Radio, as the iPhone's tuner: whether it shows, how far it strays, the genres it
 * leans into and whether old favorites come back. Changes apply as they're made, from the next
 * song if it's playing.
 */
@Composable
fun RadioPage() {
    val graph = LocalContext.current.graph
    val navigator = currentNavigator()
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val player by graph.player.state.collectAsStateWithLifecycle()
    val isOn = player.origin?.kind == QueueOrigin.Kind.Radio
    fun update(change: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(change) }

    SettingsScaffold(title = "Tracks Radio") {
        item(key = "hero") {
            StatusHero(
                icon = Icons.Rounded.Radio,
                shape = MaterialShapes.Cookie9Sided,
                tone = Tone.Tertiary,
                headline = RadioWords.title(settings.radioDiscovery),
                body = RadioWords.summary(settings.radioDiscovery, settings.radioGenres),
                modifier = Modifier.animateItem(),
                actions = if (isOn) null else {
                    {
                        Button(
                            onClick = {
                                graph.player.startRadio()
                                navigator.nowPlayingOpen = true
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Play Tracks Radio")
                        }
                    }
                },
            )
        }
        group(key = "show") {
            toggle("Show Tracks Radio", checked = settings.showTracksRadio, icon = Icons.Rounded.Visibility, tone = Tone.Primary) { on ->
                update { it.copy(showTracksRadio = on) }
            }
        }
        footnote(showFooter(settings.showTracksRadio), key = "show-note")
        group("Discovery", key = "discovery") {
            RadioDiscovery.entries.forEach { option ->
                choice(RadioWords.title(option), RadioWords.detail(option), selected = settings.radioDiscovery == option) {
                    update { it.copy(radioDiscovery = option) }
                }
            }
        }
        group(key = "genres") {
            link("Genres", summary = RadioWords.genreList(settings.radioGenres), icon = Icons.Rounded.Tune, tone = Tone.Secondary) {
                navigator.open(SettingsPageRoute("radio-genres"))
            }
            toggle("Bring back old favorites", checked = settings.radioOldFavorites, icon = Icons.Rounded.History, tone = Tone.Tertiary) { on ->
                update { it.copy(radioOldFavorites = on) }
            }
        }
        footnote("Old favorites are songs you haven't played in a few months. They come round more often.", key = "genres-note")
        group(key = "more") {
            if (settings.isTuned) {
                link("Reset to Balanced", icon = Icons.Rounded.Restore, tone = Tone.Neutral) {
                    update { it.copy(radioDiscovery = RadioDiscovery.BALANCED, radioGenres = emptySet(), radioOldFavorites = false) }
                }
            }
            link("Hear less of", summary = "Songs the radio and your mixes leave out", icon = Icons.Rounded.ThumbDown, tone = Tone.Secondary) {
                navigator.open(SettingsPageRoute("hidden"))
            }
        }
    }
}

/** What Show Tracks Radio's footer says as it's set. */
internal fun showFooter(isShown: Boolean): String =
    if (isShown) {
        "Your own station, from everything you love and new finds like it, picked as it plays. " +
            "It's at the top of Play. Assistant plays it when you say “Play music on Tracks.”"
    } else {
        "Tracks Radio is hidden. Assistant plays your mix for this time of day when you say “Play music on Tracks.”"
    }

private val AppSettings.isTuned: Boolean
    get() = radioDiscovery != RadioDiscovery.BALANCED || radioGenres.isNotEmpty() || radioOldFavorites

/** One of a few choices, picked with a radio button, with a line saying what it does. */
private fun GroupScope.choice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) = row { index, count ->
    val haptics = LocalHapticFeedback.current
    SegmentedListItem(
        onClick = {
            if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = { Text(detail) },
        colors = rowColors(),
        modifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        },
    ) {
        Text(title)
    }
}

/**
 * The genres Tracks Radio leans into, picked from the ones you play most. None picked plays
 * across everything.
 */
@Composable
fun RadioGenresPage() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val revision by graph.engine.revision.collectAsStateWithLifecycle()
    val genres by produceState<List<String>?>(null, revision) { value = graph.radioGenreChoices(settings.radioGenres) }
    fun set(change: (Set<String>) -> Set<String>) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch { graph.settings.update { it.copy(radioGenres = change(it.radioGenres)) } }
    }

    SettingsScaffold(title = "Genres") {
        group(key = "everything") {
            check("Everything", checked = settings.radioGenres.isEmpty()) { set { emptySet() } }
        }
        val found = genres ?: return@SettingsScaffold
        if (found.isEmpty()) {
            item(key = "empty") {
                StatusHero(
                    icon = Icons.Rounded.Tune,
                    shape = MaterialShapes.Cookie9Sided,
                    tone = Tone.Neutral,
                    headline = "No genres yet",
                    body = "Genres show up here as Tracks learns them from what you play.",
                    modifier = Modifier.animateItem(),
                )
            }
            return@SettingsScaffold
        }
        group("Your genres", key = "yours") {
            found.forEach { genre ->
                check(genre, checked = genre in settings.radioGenres) {
                    set { picked -> if (genre in picked) picked - genre else picked + genre }
                }
            }
        }
        footnote("Tracks Radio plays more of the genres you pick, and less of everything else. Nothing is left out.", key = "note")
    }
}

/** A row that's ticked or not, as a list of picks is. */
private fun GroupScope.check(title: String, checked: Boolean, onClick: () -> Unit) = row { index, count ->
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        trailingContent = {
            Box(Modifier.padding(end = 4.dp), contentAlignment = Alignment.Center) {
                if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        colors = rowColors(),
        modifier = Modifier.fillMaxWidth().semantics { selected = checked },
    ) {
        Text(title)
    }
}

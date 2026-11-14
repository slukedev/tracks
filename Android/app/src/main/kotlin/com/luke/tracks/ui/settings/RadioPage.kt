package com.luke.tracks.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.ui.navigation.SettingsPageRoute
import com.luke.tracks.ui.navigation.currentNavigator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How adventurous Tracks Radio is, in five steps. */
object RadioLevels {
    data class Level(val value: Float, val name: String, val detail: String)

    val all = listOf(
        Level(0f, "Only favorites", "Songs you've played most, and nothing new."),
        Level(0.25f, "Mostly favorites", "Now and then, something new that's like what you love."),
        Level(0.5f, "A good mix", "Half favorites, half songs you haven't heard yet."),
        Level(0.75f, "Adventurous", "Mostly new finds, with favorites to keep you grounded."),
        Level(1f, "Explorer", "Almost all songs you've never played."),
    )

    fun index(value: Float): Int = (value * (all.size - 1)).roundToInt().coerceIn(0, all.lastIndex)

    fun at(value: Float): Level = all[index(value)]
}

@Composable
fun RadioPage() {
    val context = LocalContext.current
    val graph = context.graph
    val navigator = currentNavigator()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var value by remember { mutableFloatStateOf(settings.radioAdventure) }
    LaunchedEffect(settings.radioAdventure) { value = settings.radioAdventure }
    val level = RadioLevels.at(value)

    SettingsScaffold(title = "Tracks Radio") {
        item(key = "hero") {
            StatusHero(
                icon = Icons.Rounded.Radio,
                shape = MaterialShapes.Sunny,
                tone = Tone.Tertiary,
                headline = level.name,
                body = level.detail,
                modifier = Modifier.animateItem(),
            )
        }
        group("How adventurous", key = "adventure") {
            custom {
                Slider(
                    value = value,
                    onValueChange = { new ->
                        if (RadioLevels.index(new) != RadioLevels.index(value)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        value = new
                    },
                    onValueChangeFinished = {
                        val snapped = RadioLevels.at(value).value
                        scope.launch { graph.settings.update { it.copy(radioAdventure = snapped) } }
                    },
                    steps = RadioLevels.all.size - 2,
                    modifier = Modifier.fillMaxWidth().semantics { stateDescription = level.name },
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Favorites", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("New finds", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        group(key = "more") {
            link("Play Tracks Radio", summary = "Endless, picked a song at a time", icon = Icons.Rounded.PlayArrow, tone = Tone.Primary) {
                graph.player.startRadio()
                navigator.nowPlayingOpen = true
            }
            link("Hear less of", summary = "Songs the radio and your mixes leave out", icon = Icons.Rounded.ThumbDown, tone = Tone.Secondary) {
                navigator.open(SettingsPageRoute("hidden"))
            }
        }
        footnote("Tracks Radio is made from your history and your music. Skips teach it what to play less.", key = "note")
    }
}

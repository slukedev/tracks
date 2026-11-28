package com.luke.tracks.ui.tv.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.playback.QueueOrigin
import com.luke.tracks.playback.radio.RadioWords
import com.luke.tracks.playback.radio.radioGenreChoices
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvChip
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.navigation.LocalTvNavigator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.tracks_engine.RadioDiscovery

/** Settings > Tracks Radio: whether it shows, how far it strays, its genres and old favourites. */
@Composable
fun TvRadioPage() {
    val graph = LocalContext.current.graph
    val navigator = LocalTvNavigator.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val playing by remember(graph) { graph.player.state.map { it.origin?.kind == QueueOrigin.Kind.Radio }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(false)
    val revision by graph.engine.revision.collectAsStateWithLifecycle()
    val genres by produceState<List<String>?>(null, revision) {
        // It maps the whole library first, so it stays off the main thread.
        value = withContext(Dispatchers.Default) { runCatching { graph.radioGenreChoices(settings.radioGenres) }.getOrDefault(emptyList()) }
    }
    TvRadioContent(
        settings = settings,
        genres = genres,
        playing = playing,
        onPlay = {
            graph.player.startRadio()
            navigator.openNowPlaying()
        },
        update = { change -> scope.launch { graph.settings.update(change) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvRadioContent(
    settings: AppSettings,
    genres: List<String>?,
    playing: Boolean,
    onPlay: () -> Unit,
    update: (transform: (AppSettings) -> AppSettings) -> Unit,
) {
    PageColumn {
        Column(Modifier.padding(top = 20.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(RadioWords.title(settings.radioDiscovery), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(RadioWords.summary(settings.radioDiscovery, settings.radioGenres), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!playing) {
            TvButton("Play Tracks Radio", onClick = onPlay, icon = Icons.Rounded.PlayArrow, style = TvButtonStyle.Primary, modifier = Modifier.padding(top = 18.dp))
        }
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleRow("Show Tracks Radio", checked = settings.showTracksRadio, onChange = { on -> update { it.copy(showTracksRadio = on) } }, icon = Icons.Rounded.Visibility)
        }
        PageHeading("Discovery")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RadioDiscovery.entries.forEach { option ->
                ChoiceRow(RadioWords.title(option), RadioWords.detail(option), selected = settings.radioDiscovery == option, onClick = { update { it.copy(radioDiscovery = option) } })
            }
        }
        PageHeading("Genres")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvChip("Everything", selected = settings.radioGenres.isEmpty(), onClick = { update { it.copy(radioGenres = emptySet()) } })
            genres.orEmpty().forEach { genre ->
                TvChip(genre, selected = genre in settings.radioGenres, onClick = {
                    update { it.copy(radioGenres = if (genre in it.radioGenres) it.radioGenres - genre else it.radioGenres + genre) }
                })
            }
        }
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleRow("Bring Back Old Favorites", checked = settings.radioOldFavorites, onChange = { on -> update { it.copy(radioOldFavorites = on) } }, icon = Icons.Rounded.History)
            if (settings.isTuned) {
                TvListRow(
                    onClick = { update { it.copy(radioDiscovery = RadioDiscovery.BALANCED, radioGenres = emptySet(), radioOldFavorites = false) } },
                    headline = "Reset to Balanced",
                    leading = { RowIcon(Icons.Rounded.Restore) },
                )
            }
        }
    }
}

private val AppSettings.isTuned: Boolean
    get() = radioDiscovery != RadioDiscovery.BALANCED || radioGenres.isNotEmpty() || radioOldFavorites

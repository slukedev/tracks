package com.luke.tracks.ui.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.components.TvListSkeleton
import kotlinx.coroutines.launch

/** A song asked to be heard less of, as the history knows it. */
data class HiddenSong(val identity: String, val title: String, val artist: String, val artwork: String?)

/** Settings > Hear Less Of: the songs every mix and the radio leave out. Select one to hear it again. */
@Composable
fun TvHiddenSongsPage() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle<AppSettings?>(null)
    val ids = settings?.hiddenSongs
    val songs by produceState<List<HiddenSong>?>(null, ids) {
        val list = ids?.toList() ?: return@produceState
        value = graph.engine.read {
            list.map { id ->
                val stats = runCatching { song(id) }.getOrNull()
                HiddenSong(id, stats?.title ?: id.substringBefore('\u0000'), stats?.artistName.orEmpty(), stats?.artworkUrl)
            }
        }.sortedBy { it.title.lowercase() }
    }
    TvHiddenSongsContent(songs) { id -> scope.launch { graph.settings.update { it.copy(hiddenSongs = it.hiddenSongs - id) } } }
}

@Composable
fun TvHiddenSongsContent(songs: List<HiddenSong>?, onHearAgain: (String) -> Unit) {
    val requesters = remember { HashMap<String, FocusRequester>() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    PageColumn {
        Spacer(Modifier.height(20.dp))
        when {
            songs == null -> TvListSkeleton(rows = 5)
            songs.isEmpty() -> Text(
                "Nothing Here",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { songs.forEachIndexed { i, song ->
                val requester = requesters.getOrPut(song.identity) { FocusRequester() }
                val focused = focusedKey == song.identity
                TvListRow(
                    onClick = {
                        // Focus moves to the song next to it, so it doesn't fall off the page.
                        (songs.getOrNull(i + 1) ?: songs.getOrNull(i - 1))?.let { requesters[it.identity]?.requestFocus() }
                        onHearAgain(song.identity)
                    },
                    headline = song.title,
                    supporting = song.artist.ifBlank { null },
                    leading = { TvArtwork(song.artwork, song.title + song.artist, Modifier.size(48.dp), sizePx = 96, shape = RoundedCornerShape(10.dp)) },
                    trailing = { if (focused) Text("Hear Again", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.focusRequester(requester).onFocusChanged {
                        if (it.isFocused) focusedKey = song.identity else if (focusedKey == song.identity) focusedKey = null
                    },
                )
            } }
        }
    }
}

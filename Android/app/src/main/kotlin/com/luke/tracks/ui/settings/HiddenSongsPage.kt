package com.luke.tracks.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.AppSettings
import com.luke.tracks.graph
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.SongRoute
import com.luke.tracks.ui.navigation.currentNavigator
import kotlinx.coroutines.launch

private data class HiddenSong(val identity: String, val title: String, val artist: String, val artwork: String?)

/** Songs you asked to hear less of, which every mix and the radio leave out. */
@Composable
fun HiddenSongsPage() {
    val context = LocalContext.current
    val graph = context.graph
    val navigator = currentNavigator()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val songs by produceState<List<HiddenSong>?>(null, settings.hiddenSongs) {
        val ids = settings.hiddenSongs.toList()
        value = graph.engine.read {
            ids.map { id ->
                val stats = runCatching { song(id) }.getOrNull()
                HiddenSong(id, stats?.title ?: id.substringBefore('\u0000'), stats?.artistName ?: "", stats?.artworkUrl)
            }
        }.sortedBy { it.title.lowercase() }
    }
    fun restore(id: String) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch { graph.settings.update { it.copy(hiddenSongs = it.hiddenSongs - id) } }
    }

    SettingsScaffold(title = "Hear less of") {
        val list = songs
        when {
            list == null -> item(key = "loading") {
                Box(Modifier.padding(48.dp).animateItem(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
            list.isEmpty() -> item(key = "empty") {
                StatusHero(
                    icon = Icons.Rounded.ThumbDown,
                    shape = MaterialShapes.Ghostish,
                    tone = Tone.Secondary,
                    headline = "Nothing here",
                    body = "Songs you ask to hear less of leave every mix and Tracks Radio, and show up here in case you change your mind.",
                    modifier = Modifier.animateItem(),
                )
            }
            else -> {
                group(key = "songs") {
                    list.forEach { song ->
                        var menu by key(song.identity) { remember { mutableStateOf(false) } }
                        link(
                            song.title,
                            summary = song.artist.ifBlank { null },
                            onLongClick = { menu = true },
                            trailing = {
                                Box {
                                    IconButton(onClick = { restore(song.identity) }) {
                                        Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Hear ${song.title} again")
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Hear it again") },
                                            leadingIcon = { Icon(Icons.Rounded.Undo, contentDescription = null) },
                                            onClick = { menu = false; restore(song.identity) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Go to song") },
                                            leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                                            onClick = { menu = false; navigator.open(SongRoute(song.identity, song.title, song.artist)) },
                                        )
                                    }
                                }
                            },
                            leading = { Cover(song.artwork, song.title + song.artist) },
                        ) { navigator.open(SongRoute(song.identity, song.title, song.artist)) }
                    }
                }
                footnote("Tap the minus to hear a song again. Press and hold for more.", key = "hint")
            }
        }
    }
}

@Composable
private fun Cover(url: String?, seed: String) {
    Artwork(url, seed, Modifier.size(44.dp), shape = RoundedCornerShape(10.dp), showInitials = false)
}

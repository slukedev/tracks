package com.luke.tracks.ui.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.play.components.MenuItem
import com.luke.tracks.ui.play.components.MiniPlayerClearance
import com.luke.tracks.ui.play.components.MixArt
import com.luke.tracks.ui.play.components.MixTheme
import com.luke.tracks.ui.play.components.SongActionsSheet
import com.luke.tracks.ui.play.components.SongRef
import com.luke.tracks.ui.play.components.SongRow
import com.luke.tracks.ui.play.components.ref
import com.luke.tracks.ui.play.components.rememberPlayActions
import kotlinx.coroutines.launch
import uniffi.tracks_engine.Mix

/**
 * A mix's page: its art, what it's made of and why, Play and Shuffle, and every song in it.
 * Songs this phone can't play stay on the list, dimmed, with a way to open them elsewhere.
 */
@Composable
fun MixScreen(mixId: String) {
    val feed by rememberPlayFeed()
    val mix = feed?.mix(mixId)
    val loaded = feed != null
    when {
        mix != null -> MixTheme(mix) { MixPage(mix, feed!!) }
        loaded -> MixGone()
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
    }
}

@Composable
private fun MixPage(mix: Mix, feed: PlayFeed) {
    val navigator = LocalNavigator.current
    val graph = LocalContext.current.graph
    val player by graph.player.state.collectAsStateWithLifecycle()
    val actions = rememberPlayActions()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var sheetSong by remember { mutableStateOf<SongRef?>(null) }
    var onlyPlayable by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val songs = remember(mix, feed) { mix.songs.map { it.ref(feed.playable[it.identity]) } }
    val playable = remember(mix, feed) { feed.playable(mix) }
    val shown = if (onlyPlayable) songs.filter { it.playable != null } else songs
    val scrolledPast by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }

    val unplayable: () -> Unit = {
        scope.launch { snackbar.showSnackbar("None of these songs are in your music on this phone yet") }
    }
    val hearLess: (SongRef) -> Unit = { song ->
        actions.hearLess(song.identity)
        scope.launch {
            val result = snackbar.showSnackbar("${song.title} will leave your mixes", actionLabel = "Undo")
            if (result == SnackbarResult.ActionPerformed) actions.undoHearLess(song.identity)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = MiniPlayerClearance - 16.dp)) },
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(scrolledPast, enter = fadeIn(), exit = fadeOut()) {
                        Text(mix.kind.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.large) {
                            MenuItem(Icons.Rounded.AddToHomeScreen, "Pin to home screen") {
                                menu = false
                                actions.pinMix(mix)
                            }
                            MenuItem(Icons.Rounded.Share, "Share") {
                                menu = false
                                actions.shareMix(mix)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (scrolledPast) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = MiniPlayerClearance + 16.dp),
        ) {
            item(key = "header", contentType = "header") {
                MixHeader(
                    mix = mix,
                    total = songs.size,
                    playableCount = playable.size,
                    topPadding = padding.calculateTopPadding(),
                    onPlay = { if (!actions.playMix(mix, playable)) unplayable() },
                    onShuffle = { if (!actions.playMix(mix, playable, shuffle = true)) unplayable() },
                )
            }
            if (playable.size in 1 until songs.size) {
                item(key = "filter") {
                    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
                        FilterChip(
                            selected = onlyPlayable,
                            onClick = { onlyPlayable = !onlyPlayable },
                            label = { Text("In your music") },
                            leadingIcon = if (onlyPlayable) {
                                { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                        )
                    }
                }
            }
            items(shown, key = { it.identity }, contentType = { "song" }) { song ->
                SongRow(
                    song = song,
                    onClick = {
                        if (song.playable == null) actions.openElsewhere(song)
                        else actions.playMix(mix, playable, startAt = song.playable)
                    },
                    onLongClick = { sheetSong = song },
                    onMore = { sheetSong = song },
                    onOpenElsewhere = { actions.openElsewhere(song) },
                    isCurrent = player.current?.identity == song.identity,
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "footer") {
                Text(
                    "Made from ${playsLabel(mix.playCount.toLong())} in your history",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }

    sheetSong?.let { song ->
        SongActionsSheet(song, actions, onDismiss = { sheetSong = null }, onHearLess = hearLess)
    }
}

@Composable
private fun MixHeader(
    mix: Mix,
    total: Int,
    playableCount: Int,
    topPadding: Dp,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.surface)))
            .padding(top = topPadding + 8.dp, start = 24.dp, end = 24.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MixArt(
            mix,
            Modifier.size(232.dp).sharedElement("cover:mix:${mix.id}"),
            shape = MaterialTheme.shapes.extraLarge,
            badgeSize = 40.dp,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(mix.kind.icon, null, Modifier.size(16.dp), tint = scheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(mix.kind.tileLine.uppercase(), style = MaterialTheme.typography.labelMediumEmphasized, color = scheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            mix.kind.title,
            style = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            mix.kind.reason,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when (playableCount) {
                total -> if (total == 1) "1 song" else "$total songs"
                0 -> "None in your music on this phone yet"
                else -> "$playableCount of $total in your music"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PlayShuffleGroup(onPlay, onShuffle, enabled = playableCount > 0)
    }
}

/** Play and Shuffle as one connected Material 3 Expressive button group. */
@Composable
fun PlayShuffleGroup(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val height = ButtonDefaults.MediumContainerHeight
        Button(
            onClick = onPlay,
            shapes = ButtonShapes(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedLeadingButtonPressShape),
            enabled = enabled,
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.weight(1f).heightIn(min = height),
        ) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Spacer(Modifier.width(8.dp))
            Text("Play", style = ButtonDefaults.textStyleFor(height))
        }
        FilledTonalButton(
            onClick = onShuffle,
            shapes = ButtonShapes(ButtonGroupDefaults.connectedTrailingButtonShape, ButtonGroupDefaults.connectedTrailingButtonPressShape),
            enabled = enabled,
            contentPadding = ButtonDefaults.contentPaddingFor(height),
            modifier = Modifier.weight(1f).heightIn(min = height),
        ) {
            Icon(Icons.Rounded.Shuffle, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
            Spacer(Modifier.width(8.dp))
            Text("Shuffle", style = ButtonDefaults.textStyleFor(height))
        }
    }
}

@Composable
private fun MixGone() {
    val navigator = LocalNavigator.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This mix has moved on", style = MaterialTheme.typography.titleLargeEmphasized)
        Spacer(Modifier.height(8.dp))
        Text(
            "Mixes change with your listening. Today's are on Play.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { navigator.back() }) { Text("Back to Play") }
    }
}


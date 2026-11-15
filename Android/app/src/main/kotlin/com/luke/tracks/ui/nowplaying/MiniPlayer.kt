package com.luke.tracks.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.hasArtist
import com.luke.tracks.playback.titleLabel
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.play.components.MorphPlayButton
import com.luke.tracks.ui.play.components.pressable
import com.luke.tracks.ui.theme.LocalDarkTheme
import com.luke.tracks.ui.theme.TracksTheme
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * The mini player: a floating card above the navigation bar, as Google's own apps float theirs,
 * tinted from the cover, with the song's progress as a ring around the play button. Tap or
 * swipe up to open the full player, swipe sideways to skip, press and hold for the queue.
 * Hidden while nothing is playing.
 */
@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val navigator = LocalNavigator.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    var queueOpen by remember { mutableStateOf(false) }
    val song = state.current
    // Tells the shell how much room pages should leave under their lists.
    androidx.compose.runtime.SideEffect { navigator.miniPlayerVisible = song != null }

    AnimatedVisibility(
        visible = song != null,
        modifier = modifier,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        // Keeps the last song on the card while it slides away.
        val shown = remember { mutableStateOf<PlayableSong?>(null) }
        if (song != null) shown.value = song
        val current = shown.value ?: return@AnimatedVisibility
        var drag by remember { mutableStateOf(Offset.Zero) }

        // Tinted from the cover like Now Playing, quietly, so the card it grows into matches.
        val seed = rememberCoverSeed(current.artworkUri, current.albumTitle ?: current.title)
        TracksTheme(dark = LocalDarkTheme.current, seed = seed, animate = true) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = drag.x * 0.5f
                        translationY = (drag.y * 0.4f).coerceAtMost(0f)
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                val (x, y) = drag
                                when {
                                    y < -60f && abs(y) > abs(x) -> onExpand()
                                    x < -120f -> graph.player.next().also { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                                    x > 120f -> graph.player.previous().also { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                                }
                                drag = Offset.Zero
                            },
                            onDragCancel = { drag = Offset.Zero },
                        ) { change, amount ->
                            change.consume()
                            drag += amount
                        }
                    }
                    .pressable(
                        onClick = onExpand,
                        onLongClick = { queueOpen = true },
                        pressedScale = 0.98f,
                        onClickLabel = "Open player",
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Hides while the full player is open, so the cover can fly up into it.
                    Box(Modifier.size(48.dp)) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !navigator.nowPlayingOpen,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Artwork(
                                current.artworkUri,
                                current.albumTitle ?: current.title,
                                Modifier.size(48.dp).sharedElement("cover:nowplaying", this),
                                // Concentric with the card: its 28dp corner less the 8dp inset.
                                shape = RoundedCornerShape(20.dp),
                                showInitials = false,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    AnimatedContent(
                        targetState = current,
                        contentKey = { it.mediaId },
                        transitionSpec = {
                            (slideInHorizontally(motion.defaultSpatialSpec()) { it / 4 } + fadeIn(motion.defaultEffectsSpec()))
                                .togetherWith(slideOutHorizontally(motion.fastSpatialSpec()) { -it / 4 } + fadeOut(motion.fastEffectsSpec()))
                        },
                        modifier = Modifier.weight(1f),
                        label = "song",
                    ) { s ->
                        Column {
                            Text(s.titleLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.hasArtist) {
                                Text(
                                    s.artistName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                        state.lengthMs?.let { ProgressRing(graph.player.position, it, state.isPlaying, Modifier.matchParentSize()) }
                        MorphPlayButton(
                            playing = state.isPlaying,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                graph.player.playPause()
                            },
                            size = 38.dp,
                            restShape = MaterialShapes.Cookie6Sided,
                            playingShape = MaterialShapes.Circle,
                        )
                    }
                    IconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        graph.player.next()
                    }) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                    }
                }
            }
        }
    }

    if (queueOpen) QueueSheet(state, graph.player, onDismiss = { queueOpen = false })
}

/**
 * Where the song is, as a ring around the play button: wavy while it plays, flat when paused.
 * The only part that reads the live position, and only while drawing, so a tick redraws the
 * ring and nothing else. The wave holds still (no wave speed) so nothing runs between ticks.
 */
@Composable
private fun ProgressRing(position: StateFlow<Long>, lengthMs: Long, playing: Boolean, modifier: Modifier) {
    val live by position.collectAsStateWithLifecycle()
    val wave by animateFloatAsState(if (playing) 1f else 0f, MaterialTheme.motionScheme.slowEffectsSpec(), label = "wave")
    val density = LocalDensity.current
    val stroke = remember(density) { with(density) { Stroke(3.dp.toPx(), cap = StrokeCap.Round) } }
    val primary = MaterialTheme.colorScheme.primary
    CircularWavyProgressIndicator(
        progress = { (live.toFloat() / lengthMs).coerceIn(0f, 1f) },
        modifier = modifier.clearAndSetSemantics {},
        color = primary,
        trackColor = primary.copy(alpha = 0.18f),
        stroke = stroke,
        trackStroke = stroke,
        amplitude = { wave * WavyProgressIndicatorDefaults.indicatorAmplitude(it) },
        wavelength = 12.dp,
        waveSpeed = 0.dp,
    )
}

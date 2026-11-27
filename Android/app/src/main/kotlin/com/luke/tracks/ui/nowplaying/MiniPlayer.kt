package com.luke.tracks.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.data.MiniPlayerColors
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/** The play button's size, and the toolbar's: Material's floating toolbar height. */
private val FabSize = 64.dp

/**
 * The mini player, in Material 3 Expressive's floating toolbar with a FAB: a toolbar for the
 * song (cover, title, next) stretching across the screen beside a round play button whose
 * edge fills with the song's progress, floating above the navigation bar and tinted from the
 * cover. Tap or swipe up on the toolbar to open the full player, swipe sideways to skip,
 * press and hold for the queue. Hidden while nothing is playing.
 */
@Composable
fun MiniPlayer(onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val navigator = LocalNavigator.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    var queueOpen by remember { mutableStateOf(false) }
    val colors by remember { graph.settings.settings.map { it.miniPlayerColors }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(MiniPlayerColors.Album)
    val song = state.current
    // Tells the shell how much room pages should leave under their lists.
    SideEffect { navigator.miniPlayerVisible = song != null }

    AnimatedVisibility(
        visible = song != null,
        modifier = modifier,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        // Keeps the last song on the toolbar while it slides away.
        val shown = remember { mutableStateOf<PlayableSong?>(null) }
        if (song != null) shown.value = song
        val current = shown.value ?: return@AnimatedVisibility
        var drag by remember { mutableStateOf(Offset.Zero) }

        // Tinted from the cover like Now Playing, so the toolbar matches the screen it opens,
        // unless Settings asks for the app's own colors. Either way a change fades across.
        val seed = rememberCoverSeed(current.artworkUri, current.albumTitle ?: current.title)
        val appScheme = MaterialTheme.colorScheme
        TracksTheme(
            dark = LocalDarkTheme.current,
            seed = seed,
            scheme = appScheme.takeIf { colors == MiniPlayerColors.App },
            animate = true,
        ) {
            val scheme = MaterialTheme.colorScheme
            Row(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
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
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                    contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(FabSize)
                        .pressable(
                            onClick = onExpand,
                            onLongClick = { queueOpen = true },
                            pressedScale = 0.98f,
                            onClickLabel = "Open player",
                        )
                        .semantics {
                            customActions = listOf(CustomAccessibilityAction("Show queue") { queueOpen = true; true })
                        },
                ) {
                    // The toolbar lines its content up at the top once it's stretched; center it.
                    Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        // Hides while the full player is open, so the cover can fly up into it.
                        Box(Modifier.size(44.dp)) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !navigator.nowPlayingOpen,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                Artwork(
                                    current.artworkUri,
                                    current.albumTitle ?: current.title,
                                    Modifier.size(44.dp).sharedElement("cover:nowplaying", this),
                                    shape = CircleShape,
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
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            graph.player.next()
                        }) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                MorphPlayButton(
                    playing = state.isPlaying || state.isLoading,
                    loading = state.isLoading,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        graph.player.playPause()
                    },
                    size = FabSize,
                    restShape = MaterialShapes.Circle,
                    playingShape = MaterialShapes.Circle,
                    pressedShape = MaterialShapes.Square,
                    shadowElevation = 6.dp,
                ) {
                    state.lengthMs?.let { ProgressEdge(graph.player.position, it, scheme.onPrimary, Modifier.matchParentSize()) }
                }
            }
        }
    }


    if (queueOpen) QueueSheet(state, graph.player, onDismiss = { queueOpen = false })
}

/**
 * Where the song is, as an arc along the inside of the play button's edge, the way Clock's
 * timer fills. The only part that reads the live position, and only while drawing, so a tick
 * redraws the arc and nothing else; nothing moves between ticks or while paused.
 */
@Composable
private fun ProgressEdge(position: StateFlow<Long>, lengthMs: Long, color: Color, modifier: Modifier) {
    val live by position.collectAsStateWithLifecycle()
    Spacer(
        modifier.drawBehind {
            val width = ArcWidth.toPx()
            val inset = width / 2 + ArcInset.toPx()
            val topLeft = Offset(inset, inset)
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(color.copy(alpha = 0.24f), 0f, 360f, false, topLeft, arcSize, style = Stroke(width))
            val sweep = 360f * (live.toFloat() / lengthMs).coerceIn(0f, 1f)
            drawArc(color, -90f, sweep, false, topLeft, arcSize, style = Stroke(width, cap = StrokeCap.Round))
        },
    )
}

private val ArcWidth: Dp = 4.dp
private val ArcInset: Dp = 4.dp

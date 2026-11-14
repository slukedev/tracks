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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.artistLabel
import com.luke.tracks.playback.titleLabel
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.navigation.LocalNavigator
import com.luke.tracks.ui.navigation.sharedElement
import com.luke.tracks.ui.play.components.MorphPlayButton
import com.luke.tracks.ui.play.components.pressable
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * The mini player: a floating card above the navigation bar, as Google's own apps float theirs.
 * Tap or swipe up to open the full player, swipe sideways to skip, press and hold for the
 * queue. Hidden while nothing is playing.
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

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp,
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
            Box {
                Row(
                    Modifier.fillMaxWidth().height(68.dp).padding(start = 10.dp, end = 6.dp),
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
                                shape = RoundedCornerShape(14.dp),
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
                            Text(
                                s.artistLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    MorphPlayButton(
                        playing = state.isPlaying,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            graph.player.playPause()
                        },
                        size = 48.dp,
                        restShape = MaterialShapes.Cookie6Sided,
                        playingShape = MaterialShapes.Square,
                    )
                    IconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        graph.player.next()
                    }) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                    }
                }
                MiniProgress(
                    graph.player.position,
                    state.lengthMs ?: 0,
                    Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 6.dp).fillMaxWidth().height(3.dp),
                )
            }
        }
    }

    if (queueOpen) QueueSheet(state, graph.player, onDismiss = { queueOpen = false })
}

/** A hairline of progress along the card's foot; the only part that reads the live position. */
@Composable
private fun MiniProgress(position: StateFlow<Long>, durationMs: Long, modifier: Modifier) {
    val live by position.collectAsStateWithLifecycle()
    val fraction = if (durationMs > 0) (live.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Box(
        modifier.drawBehind {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, Offset.Zero, size, r)
            drawRoundRect(active, Offset.Zero, Size(size.width * fraction, size.height), r)
        },
    )
}

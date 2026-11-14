package com.luke.tracks.ui.nowplaying

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.PlayerController
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.ui.components.Artwork
import com.luke.tracks.ui.play.components.pressable
import kotlin.math.roundToInt

private val RowHeight = 68.dp

/**
 * Up next, as a sheet: what's playing, then the queue. Drag a row by its handle to move it,
 * swipe it away to take it out, tap to jump to it.
 */
@Composable
fun QueueSheet(state: PlayerState, player: PlayerController, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        QueueContent(state, player)
    }
}

@Composable
fun QueueContent(state: PlayerState, player: PlayerController, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val upNext = state.upNext
    val base = state.index + 1
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }

    Column(modifier.fillMaxWidth().navigationBarsPadding()) {
        Text(
            "Up next",
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        state.origin?.let {
            Text(
                "Playing from ${it.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        state.current?.let { NowRow(it) }
        if (upNext.isEmpty()) {
            Text(
                "Nothing after this song",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            itemsIndexed(upNext, key = { i, song -> "${song.mediaId}:$i" }) { i, song ->
                val isDragged = dragging == i
                val lift by animateDpAsState(if (isDragged) 6.dp else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "lift")
                val dismiss = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = dismiss,
                    enableDismissFromStartToEnd = false,
                    onDismiss = {
                        if (it == SwipeToDismissBoxValue.EndToStart) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            player.removeFromQueue(base + i)
                        }
                    },
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    },
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) offset else 0f }
                        .then(if (dragging == null) Modifier.animateItem() else Modifier),
                ) {
                    Surface(tonalElevation = lift, shadowElevation = lift, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        QueueRow(
                            song = song,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                player.skipTo(base + i)
                            },
                            handle = Modifier.pointerInput(i, upNext.size) {
                                detectDragGestures(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        dragging = i
                                        offset = 0f
                                    },
                                    onDragEnd = {
                                        val from = dragging ?: return@detectDragGestures
                                        val to = (from + (offset / rowPx).roundToInt()).coerceIn(0, upNext.lastIndex)
                                        if (to != from) player.moveInQueue(base + from, base + to)
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        dragging = null
                                        offset = 0f
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        offset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val before = (offset / rowPx).roundToInt()
                                        offset += amount.y
                                        if ((offset / rowPx).roundToInt() != before) {
                                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowRow(song: PlayableSong) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artworkUri, song.albumTitle ?: song.title, Modifier.size(48.dp), shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(song.artistName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
        }
        Icon(Icons.Rounded.GraphicEq, "Now playing", tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun QueueRow(song: PlayableSong, onClick: () -> Unit, handle: Modifier) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .pressable(onClick = onClick, pressedScale = 0.98f)
            .padding(start = 28.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artworkUri, song.albumTitle ?: song.title, Modifier.size(44.dp), shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artistName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DragHandle, "Move", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

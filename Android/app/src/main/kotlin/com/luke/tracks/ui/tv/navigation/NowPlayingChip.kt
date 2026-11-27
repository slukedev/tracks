package com.luke.tracks.ui.tv.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.luke.tracks.playback.PlayableSong
import com.luke.tracks.playback.artistLabel
import com.luke.tracks.playback.titleLabel
import com.luke.tracks.ui.tv.components.TvArtwork
import com.luke.tracks.ui.tv.theme.LocalReducedMotion
import com.luke.tracks.ui.tv.theme.TvMotion

/**
 * The song playing, at the top of the rail: its cover with moving bars while it plays, and
 * its title and artist once the rail opens. Opens Now Playing.
 */
@Composable
internal fun NowPlayingChip(
    song: PlayableSong,
    playing: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val width by animateDpAsState(
        if (expanded) RailItemExpandedWidth else RailItemCollapsedWidth,
        tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut),
        label = "chip width",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(width)
            .height(56.dp)
            .semantics { contentDescription = "Now Playing, ${song.titleLabel}" },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
            focusedContainerColor = colors.primary,
            focusedContentColor = colors.onPrimary,
            pressedContainerColor = colors.primary,
            pressedContentColor = colors.onPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(0.dp, Color.Transparent))),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                TvArtwork(song.artworkUri, song.albumTitle ?: song.title, Modifier.size(40.dp), sizePx = 96, shape = CircleShape, showInitials = false)
                EqualizerBars(playing, Modifier.size(40.dp))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)),
                exit = fadeOut(tween(TvMotion.FocusOutMs, easing = TvMotion.EaseOut)),
            ) {
                Row {
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(song.titleLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            song.artistLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val RestLevels = floatArrayOf(0.35f, 0.6f, 0.45f)

/**
 * Three bars over the cover that bounce while music plays and rest low when it's paused or
 * motion is reduced. They only animate while visible and playing.
 */
@Composable
private fun EqualizerBars(playing: Boolean, modifier: Modifier) {
    val moving = playing && !LocalReducedMotion.current
    val levels: List<State<Float>> = if (moving) {
        val transition = rememberInfiniteTransition(label = "eq")
        listOf(520, 380, 450).mapIndexed { i, ms ->
            transition.animateFloat(
                initialValue = 0.25f + 0.1f * i,
                targetValue = 0.95f - 0.1f * i,
                animationSpec = infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Reverse),
                label = "bar$i",
            )
        }
    } else {
        remember { RestLevels.map { mutableFloatStateOf(it) } }
    }
    Canvas(modifier) {
        drawCircle(Color.Black.copy(alpha = 0.45f))
        val barWidth = size.width * 0.12f
        val gap = size.width * 0.08f
        val total = barWidth * 3 + gap * 2
        val left = (size.width - total) / 2
        val maxHeight = size.height * 0.5f
        val bottom = size.height * 0.75f
        levels.forEachIndexed { i, level ->
            val h = maxHeight * level.value
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left + i * (barWidth + gap), bottom - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

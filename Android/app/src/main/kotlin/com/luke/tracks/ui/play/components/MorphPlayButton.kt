package com.luke.tracks.ui.play.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * The expressive play button: a scalloped cookie at rest that morphs to a rounded square when
 * playing, squeezes toward a circle (or [pressedShape]) under a finger and turns a notch each
 * time it changes.
 * Every motion is a spring that settles, so nothing moves while it sits still.
 */
@Composable
fun MorphPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    restShape: RoundedPolygon = MaterialShapes.Cookie9Sided,
    playingShape: RoundedPolygon = MaterialShapes.Square,
    pressedShape: RoundedPolygon = MaterialShapes.Circle,
    playIcon: ImageVector = Icons.Rounded.PlayArrow,
    contentDescription: String = if (playing) "Pause" else "Play",
    enabled: Boolean = true,
    shadowElevation: Dp = 0.dp,
    /** Waiting on the song: a loading indicator in place of the icon. */
    loading: Boolean = false,
    /** Drawn over the button, inside its shape, pressing and turning with it. */
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val toPlaying = remember(restShape, playingShape) { Morph(restShape, playingShape) }
    val toPressed = remember(restShape, playingShape, pressedShape) {
        Morph(restShape, pressedShape) to Morph(playingShape, pressedShape)
    }
    val state by animateFloatAsState(if (playing) 1f else 0f, motion.defaultSpatialSpec(), label = "state")
    val squeeze by animateFloatAsState(if (pressed) 1f else 0f, motion.fastSpatialSpec(), label = "squeeze")
    val turn by animateFloatAsState(if (playing) 90f else 0f, motion.slowSpatialSpec(), label = "turn")
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, motion.fastSpatialSpec(), label = "scale")

    // Pressing wins over the state: squeeze whichever shape it's nearest toward the pressed one.
    val shape = when {
        squeeze > 0.01f -> MorphShape(if (state < 0.5f) toPressed.first else toPressed.second, squeeze * 0.8f, turn)
        else -> MorphShape(toPlaying, state, turn)
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.shape = shape
                clip = true
                this.shadowElevation = shadowElevation.toPx()
            }
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.38f))
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = if (loading) null else playing,
            transitionSpec = {
                (scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f) + fadeIn(motion.fastEffectsSpec()))
                    .togetherWith(scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f) + fadeOut(motion.fastEffectsSpec()))
            },
            label = "icon",
        ) { isPlaying ->
            if (isPlaying == null) {
                LoadingIndicator(Modifier.size(size * 0.62f).semantics { this.contentDescription = "Loading" }, color = contentColor)
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else playIcon,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(size * 0.42f),
                )
            }
        }
        overlay()
    }
}

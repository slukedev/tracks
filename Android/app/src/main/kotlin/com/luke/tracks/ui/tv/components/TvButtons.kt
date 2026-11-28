package com.luke.tracks.ui.tv.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.luke.tracks.ui.tv.theme.TvMotion
import com.luke.tracks.ui.tv.theme.tvFocusScale
import androidx.compose.material3.MaterialTheme as M3Theme

enum class TvButtonStyle { Primary, Secondary }

/**
 * A pill button. Focus fills it with the primary color, the same in every style, so the
 * focused control always reads the same way; [TvButtonStyle.Primary] rests tinted too.
 * [pending] swaps the icon for a ring while its action is on its way.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: TvButtonStyle = TvButtonStyle.Secondary,
    enabled: Boolean = true,
    pending: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val resting = when (style) {
        TvButtonStyle.Primary -> colors.primaryContainer
        TvButtonStyle.Secondary -> M3Theme.colorScheme.surfaceContainerHigh
    }
    val onResting = when (style) {
        TvButtonStyle.Primary -> colors.onPrimaryContainer
        TvButtonStyle.Secondary -> colors.onSurface
    }
    FocusPill(onClick, modifier.semantics { role = Role.Button }, enabled, onLongClick, resting, onResting) {
        Row(
            Modifier.height(48.dp).padding(PaddingValues(start = if (icon != null || pending) 20.dp else 28.dp, end = 28.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                pending -> CircularProgressIndicator(Modifier.size(22.dp), color = LocalContentColor.current, strokeWidth = 3.dp)
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A round icon button; [big] for play and pause. [selected] marks an on toggle (Shuffle, Repeat). */
@Composable
fun TvIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    big: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val resting = if (selected) colors.secondaryContainer else M3Theme.colorScheme.surfaceContainerHigh
    val onResting = if (selected) colors.onSecondaryContainer else colors.onSurface
    val size = if (big) 72.dp else 52.dp
    FocusPill(
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.Button
            this.contentDescription = contentDescription
            this.selected = selected
        },
        enabled = enabled,
        onLongClick = null,
        resting = resting,
        onResting = onResting,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (big) 36.dp else 26.dp))
        }
    }
}

/** A small pill for periods, filters and recent searches. */
@Composable
fun TvChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** A mark of its own (a service's logo) in place of [icon], drawn at 20dp. */
    mark: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val resting = if (selected) colors.secondaryContainer else M3Theme.colorScheme.surfaceContainer
    val onResting = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
    FocusPill(
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.Tab
            this.selected = selected
        },
        enabled = true,
        onLongClick = null,
        resting = resting,
        onResting = onResting,
    ) {
        Row(
            Modifier.height(40.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                mark != null -> mark()
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FocusPill(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    onLongClick: (() -> Unit)?,
    resting: androidx.compose.ui.graphics.Color,
    onResting: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.tvTouch(source, enabled, onLongClick = onLongClick, onClick = onClick).tvFocusScale(source, TvMotion.ButtonFocusScale),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = resting,
            contentColor = onResting,
            focusedContainerColor = colors.primary,
            focusedContentColor = colors.onPrimary,
            pressedContainerColor = colors.primary,
            pressedContentColor = colors.onPrimary,
            disabledContainerColor = resting.copy(alpha = 0.4f),
            disabledContentColor = onResting.copy(alpha = 0.38f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedDisabledBorder = Border(BorderStroke(2.dp, colors.border.copy(alpha = 0.5f)), shape = CircleShape),
        ),
        interactionSource = source,
    ) {
        content()
    }
}

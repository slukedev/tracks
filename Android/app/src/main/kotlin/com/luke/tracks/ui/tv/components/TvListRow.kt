package com.luke.tracks.ui.tv.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.luke.tracks.ui.tv.theme.TvMotion
import com.luke.tracks.ui.tv.theme.tvFocusScale
import androidx.compose.material3.MaterialTheme as M3Theme

private val RowShape = RoundedCornerShape(14.dp)

/**
 * A focusable row for songs, plays, ranks and settings. Resting rows sit flat on the page;
 * focus lifts one onto a filled surface with a primary edge. [selected] marks the current
 * choice (the song playing, a picked option).
 */
@Composable
fun TvListRow(
    onClick: () -> Unit,
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    /** For a [supporting] line that says what went wrong; unspecified keeps the row's own. */
    supportingColor: Color = Color.Unspecified,
    supportingLines: Int = 1,
    overline: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    dense: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val raised = M3Theme.colorScheme.surfaceContainerHighest
    val resting = if (selected) M3Theme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else colors.surface.copy(alpha = 0f)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusScale(source, TvMotion.RowFocusScale),
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = resting,
            contentColor = colors.onSurface,
            focusedContainerColor = raised,
            focusedContentColor = colors.onSurface,
            pressedContainerColor = raised,
            pressedContentColor = colors.onSurface,
            disabledContainerColor = resting,
            disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(TvMotion.FocusBorderWidth, colors.primary), inset = 0.dp, shape = RowShape),
        ),
        interactionSource = source,
    ) {
        Row(
            Modifier
                .heightIn(min = if (dense) 56.dp else 72.dp)
                .padding(PaddingValues(horizontal = 16.dp, vertical = if (dense) 8.dp else 12.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                if (overline != null) {
                    Text(overline, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(headline, style = if (dense) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = supportingColor.takeOrElse { LocalContentColor.current.copy(alpha = 0.72f) },
                        maxLines = supportingLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
